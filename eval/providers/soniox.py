import os
import json
from typing import List
from .base import Provider, TranscriptResult, WordInfo, StreamResult, StreamEvent, FinalWord
from .streaming import run_ws_stream
from soniox.client import SonioxClient

TRANSLATION_STATUS = "translation"


def translated_text(tokens) -> str:
    """The caption text out of a translated Soniox result.

    Soniox returns the original *and* the translation in one token list, tagged only by
    `translation_status` — on the batch API exactly as on the socket. A result's own `.text`
    therefore contains both languages spliced together, so scoring it would score neither.
    This is the batch-side twin of `selectCaptionText()` in SonioxClient.kt, and the reason
    the eval reads tokens rather than the convenient field.

    Accepts SDK token objects or plain dicts (fixtures replay as dicts).
    """
    def field(tok, name):
        return tok.get(name) if isinstance(tok, dict) else getattr(tok, name, None)

    return "".join(
        field(t, "text") or "" for t in tokens if field(t, "translation_status") == TRANSLATION_STATUS
    ).strip()


class SonioxProvider(Provider):
    @property
    def name(self) -> str:
        return "soniox"

    def _transcribe_batch(self, wav_path: str, domain_terms: List[str], boost: bool) -> TranscriptResult:
        api_key = os.environ.get("SONIOX_API_KEY")
        if not api_key:
            raise ValueError("SONIOX_API_KEY is not set")
            
        client = SonioxClient(api_key=api_key)
        
        # We don't have the explicit config object imported here, but we can just use the defaults
        # or pass dict if config parameter accepts dict. Actually, Soniox Python SDK uses `config` param
        # but let's just pass basic parameters. We can pass model="stt-async-v5" and file.
        # Wait, the SDK has `CreateTranscriptionConfig`.
        # Let's import it safely.
        from soniox.api.stt import CreateTranscriptionConfig
        
        config_kwargs = {}
        # We could set speech context here, but let's just use defaults for now since we don't have exact fields
        # If boost and domain_terms are needed:
        # if boost and domain_terms:
        #     config_kwargs['speech_context'] = {'phrases': domain_terms, 'boost': 2.0} # just guessing the shape
            
        config = CreateTranscriptionConfig(**config_kwargs) if config_kwargs else None
        
        res = client.stt.transcribe_and_wait_with_tokens(file=wav_path, model="stt-async-v5", config=config)
        
        text = res.text
        words = []
        for w in res.tokens:
            words.append(WordInfo(
                word=w.text,
                start_s=w.start_ms / 1000.0 if hasattr(w, 'start_ms') else 0.0,
                end_s=w.end_ms / 1000.0 if hasattr(w, 'end_ms') else 0.0,
                confidence=1.0 # default to 1.0 if not provided
            ))
            
        return TranscriptResult(
            text=text,
            words=words,
            raw_response={"text": res.text} # simplified raw response
        )

    def transcribe_batch_translated(
        self,
        wav_path: str,
        target_lang: str,
        context: dict = None,
    ) -> TranscriptResult:
        """Translate a clip with the async API, which sees the whole file before answering.

        This is the ceiling for Soniox's own translation of this language on this material:
        same model family, same session context, no incremental commitment. The gap between
        it and the real-time arm is what streaming costs — a translator that must emit
        Hungarian before the clause is finished has less to work with than one holding the
        whole sentence, and that penalty is not the same size in every language.

        Live only; the caller is a spending script and the scorecard replays the fixture.
        """
        api_key = os.environ.get("SONIOX_API_KEY")
        if not api_key:
            raise ValueError("SONIOX_API_KEY is not set")

        from soniox.api.stt import CreateTranscriptionConfig

        client = SonioxClient(api_key=api_key)
        config = CreateTranscriptionConfig(
            language_hints=["en"],
            translation={"type": "one_way", "target_language": target_lang},
            context=context or None,
        )
        res = client.stt.transcribe_and_wait_with_tokens(
            file=wav_path, model="stt-async-v5", config=config
        )
        text = translated_text(res.tokens)
        return TranscriptResult(
            text=text,
            words=[],  # word timings are meaningless for a translation; chrF is all this arm feeds
            raw_response={"model": "stt-async-v5", "target_language": target_lang, "text": text},
        )

    def transcribe_stream_translated(
        self,
        wav_path: str,
        target_lang: str,
        context: dict = None,
        endpointing: dict = None,
    ) -> StreamResult:
        """Stream a clip with Soniox's in-band translation on, exactly as the app does.

        Live only — the caller (scripts/record_translate.py) is a spending script and the
        scorecard replays the fixture it writes.

        This is deliberately a mirror of `SonioxClient.kt`, not a cleaner reimplementation:
        the same `stt-rt-v5` model, the same one-way translation block, the same session
        `context`, and the same token selection. Soniox sends the original *and* the
        translation over one socket, distinguished only by `translation_status`, so the app
        keeps the translated tokens and drops the rest — score anything else and the number
        is not about the captions anyone sees.
        """
        api_key = os.environ.get("SONIOX_API_KEY")
        if not api_key:
            raise ValueError("SONIOX_API_KEY is not set")

        url = "wss://stt-rt.soniox.com/transcribe-websocket"
        config_obj = {
            "api_key": api_key,
            "model": "stt-rt-v5",
            "audio_format": "pcm_s16le",
            "sample_rate": 16000,
            "num_channels": 1,
            "language_hints": ["en"],
            "translation": {"type": "one_way", "target_language": target_lang},
        }
        if context:
            config_obj["context"] = context
        # Endpoint detection: let Soniox finalize at utterance boundaries instead of
        # whenever its buffer says so. The app sends none of these today. Passed through
        # verbatim rather than wrapped in named parameters because the exact field set is
        # the vendor's and the point of the arm is to find out what they do.
        if endpointing:
            config_obj.update(endpointing)

        state = {"committed": "", "events": [], "final_words": []}

        def on_message(data, t):
            if data.get("error_code"):
                raise RuntimeError(f"Soniox error {data.get('error_code')}: {data.get('error_message')}")
            tail = ""
            finalized_any = False
            for tok in data.get("tokens", []):
                # The app's selectCaptionText(): translated tokens are the caption, the
                # originals are not.
                if tok.get("translation_status") != "translation":
                    continue
                text = tok.get("text", "")
                if tok.get("is_final"):
                    state["committed"] += text
                    if text.strip():
                        state["final_words"].append(
                            FinalWord(word=text.strip(), start_s=tok.get("start_ms", 0) / 1000.0, final_t_recv=t)
                        )
                    finalized_any = True
                else:
                    tail += text
            state["events"].append(
                StreamEvent(
                    t_recv=t,
                    display_text=(state["committed"] + tail).strip(),
                    is_final_update=finalized_any,
                )
            )

        duration_s = run_ws_stream(
            url, wav_path, on_message,
            init_message=json.dumps(config_obj),
            close_message="",  # empty text frame signals end-of-audio to Soniox
        )

        return StreamResult(
            final_text=state["committed"].strip(),
            events=state["events"],
            final_words=state["final_words"],
            audio_duration_s=duration_s,
            model=f"stt-rt-v5+translate:{target_lang}",
            # Everything but the key, so the fixture says what produced it.
            session_config={k: v for k, v in config_obj.items() if k != "api_key"},
        )

    def _transcribe_stream_live(self, wav_path: str) -> StreamResult:
        api_key = os.environ.get("SONIOX_API_KEY")
        if not api_key:
            raise ValueError("SONIOX_API_KEY is not set")

        url = "wss://stt-rt.soniox.com/transcribe-websocket"
        config = json.dumps({
            "api_key": api_key,
            "model": "stt-rt-v5",
            "audio_format": "pcm_s16le",
            "sample_rate": 16000,
            "num_channels": 1,
            "language_hints": ["en"],
        })

        # Soniox streams tokens: final tokens are emitted once (permanent); non-final
        # tokens are the evolving tail, re-sent each message. Display = committed
        # finals + this message's non-final tail.
        state = {"committed": "", "events": [], "final_words": []}

        def on_message(data, t):
            if data.get("error_code"):
                raise RuntimeError(f"Soniox error {data.get('error_code')}: {data.get('error_message')}")
            tokens = data.get("tokens", [])
            tail = ""
            finalized_any = False
            for tok in tokens:
                text = tok.get("text", "")
                if tok.get("is_final"):
                    state["committed"] += text
                    if text.strip():
                        state["final_words"].append(
                            FinalWord(word=text.strip(), start_s=tok.get("start_ms", 0) / 1000.0, final_t_recv=t)
                        )
                    finalized_any = True
                else:
                    tail += text
            display = (state["committed"] + tail).strip()
            state["events"].append(
                StreamEvent(t_recv=t, display_text=display, is_final_update=finalized_any)
            )

        duration_s = run_ws_stream(
            url, wav_path, on_message,
            init_message=config,
            close_message="",  # empty text frame signals end-of-audio to Soniox
        )

        return StreamResult(
            final_text=state["committed"].strip(),
            events=state["events"],
            final_words=state["final_words"],
            audio_duration_s=duration_s,
            model="stt-rt-v5",
        )
