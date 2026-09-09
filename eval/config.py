import os
from typing import List, Literal, Optional
from pydantic import BaseModel

class Source(BaseModel):
    event: str
    rendition: str
    start_s: int
    dur_s: int

class ManifestEntry(BaseModel):
    id: str
    source: Source
    conditions: List[Literal["clean", "music_bed", "multi_speaker", "crowd_noise", "poor_mic"]]
    domain_terms: List[str]
    ref: str
    verified: bool

class Manifest(BaseModel):
    entries: List[ManifestEntry]

# Config / Constants
GOLDEN_DIR = os.path.join(os.path.dirname(__file__), "golden")
MANIFEST_PATH = os.path.join(GOLDEN_DIR, "manifest.json")
CLIPS_DIR = os.path.join(GOLDEN_DIR, "clips")
REFS_DIR = os.path.join(GOLDEN_DIR, "refs")
ALIGN_DIR = os.path.join(GOLDEN_DIR, "align")

FIXTURES_DIR = os.path.join(os.path.dirname(__file__), "fixtures")
REPORTS_DIR = os.path.join(os.path.dirname(__file__), "reports")

# Cost caps (in seconds of billed audio)
MAX_BILLED_AUDIO_SECONDS = 90 * 60

# Translation-fidelity check (Deepgram/Soniox → DeepL). Measures how much ASR error
# survives machine translation: translate(hypothesis) vs translate(verified reference).
TRANSLATE_TARGET_LANG = os.environ.get("EVAL_TRANSLATE_TARGET", "DE")
# DeepL bills per source character; cap chars per invocation (free tier = 500k/month).
MAX_TRANSLATE_CHARS = 300_000
TRANSLATIONS_DIR = os.path.join(FIXTURES_DIR, "translations")

# Soniox's own in-band translation (what the app actually ships), recorded by
# scripts/record_translate.py and scored against the DeepL translation of the verified
# reference. One subdirectory per Soniox language code. The -nocontext arm is the same audio
# with the session context withheld, so the glossary's contribution is measurable rather
# than assumed.
INBAND_DIR = os.path.join(FIXTURES_DIR, "soniox-translate")
INBAND_NOCONTEXT_DIR = os.path.join(FIXTURES_DIR, "soniox-translate-nocontext")
# The same translation off the async API, which sees the whole clip before answering: the
# ceiling for this model on this language, and the baseline the real-time arm's streaming
# penalty is measured from.
INBAND_BATCH_DIR = os.path.join(FIXTURES_DIR, "soniox-translate-batch")
# The real-time arm with Soniox's own endpoint detection on, so it finalizes at utterance
# boundaries instead of mid-clause. The batch arm already bounds what this can do for
# *quality* (it has the whole clip and beats streaming by ~1 chrF), so this arm is scored
# on flicker and finalization latency: a no-flicker caption is a display win worth having
# even when the words are identical.
INBAND_ENDPOINTED_DIR = os.path.join(FIXTURES_DIR, "soniox-translate-endpointed")

# Sent as the endpointing arm's extra config. Only the toggle is on by default: the
# remaining knobs (max_endpoint_delay_ms, endpoint_sensitivity,
# endpoint_latency_adjustment_level) are per-model capabilities, so a value this model does
# not support is a session error rather than a silently ignored field. Override with
# `record_translate.py --endpoint-config '{"max_endpoint_delay_ms": 3000}'`.
ENDPOINTING_CONFIG = {"enable_endpoint_detection": True}

# Soniox language code -> DeepL target code, for the pairs whose spelling differs. Everything
# else is the code upper-cased ("hu" -> "HU"), which is what DeepL expects.
DEEPL_TARGET_OVERRIDES = {"pt": "PT-BR", "en": "EN-US", "zh": "ZH", "no": "NB"}


def deepl_target(soniox_lang: str) -> str:
    """The DeepL target code naming the same language as this Soniox code."""
    return DEEPL_TARGET_OVERRIDES.get(soniox_lang, soniox_lang.upper())
