"""Test the Soniox request/response boundary without billed API calls."""
import json
from pathlib import Path
import sys
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from providers import soniox
from soniox.types.api import TranscriptionTranscript


@pytest.mark.parametrize("boost", [False, True])
def test_batch_records_model_and_only_boosts_when_requested(monkeypatch, boost):
    requests = []
    transcript = TranscriptionTranscript(id="offline", text="Apollo", tokens=[
        {"text": "Apollo", "start_ms": 100, "end_ms": 500, "confidence": 0.9},
    ])
    def transcribe(**kwargs):
        requests.append(kwargs)
        return transcript
    monkeypatch.setenv("SONIOX_API_KEY", "offline-test")
    monkeypatch.setattr(soniox, "SonioxClient", lambda **kwargs: SimpleNamespace(stt=SimpleNamespace(transcribe_and_wait_with_tokens=transcribe)))
    result = soniox.SonioxProvider()._transcribe_batch("clip.wav", ["Apollo"], boost)
    request = requests[0]
    context = request["config"].model_dump(exclude_none=True).get("context") if request.get("config") else None
    assert context == ({"terms": ["Apollo"]} if boost else None)
    assert result.model == "stt-async-v6"
    assert request["model"] == result.model
    assert result.words[0].start_s == 0.1
    assert result.words[0].end_s == 0.5
    assert result.words[0].confidence == 0.9


def test_stream_control_tokens_do_not_become_caption_words(monkeypatch):
    def stream(url, wav_path, on_message, **kwargs):
        assert json.loads(kwargs["init_message"])["model"] == "stt-rt-v6"
        on_message({"tokens": [
            {"text": "Apollo", "is_final": True, "start_ms": 100},
            {"text": "<end>", "is_final": True},
            {"text": "<fin>", "is_final": True},
        ]}, 1.0)
        on_message({"tokens": [], "finished": True}, 1.1)
        return 1.0
    monkeypatch.setenv("SONIOX_API_KEY", "offline-test")
    monkeypatch.setattr(soniox, "run_ws_stream", stream)
    result = soniox.SonioxProvider()._transcribe_stream_live("clip.wav")
    assert result.final_text == "Apollo"
    assert [w.word for w in result.final_words] == ["Apollo"]
    assert result.events[-1].display_text == "Apollo"


def test_stream_without_finished_response_is_not_a_valid_recording(monkeypatch):
    monkeypatch.setenv("SONIOX_API_KEY", "offline-test")
    def truncated(url, wav_path, on_message, **kwargs):
        on_message({"tokens": [{"text": "partial", "is_final": True}]}, 1.0)
        return 60.0
    monkeypatch.setattr(soniox, "run_ws_stream", truncated)
    with pytest.raises(RuntimeError, match="finished"):
        soniox.SonioxProvider()._transcribe_stream_live("clip.wav")
