"""Gemini translation with the harness's record/replay posture.

This exists for one job: to be a translation engine that is *not* DeepL. The scorecard's
"ideal" is DeepL's translation of the verified reference, so every column is scored on
how closely it resembles DeepL output. A second engine translating the *same perfect
English* measures what that resemblance is worth on its own, separately from any
difference in translation quality — see scripts/calibrate_metric.py.

Deliberately mirrors scoring/translate.py: same {kind}/{clip_id}.{lang}.json fixture
layout, same EVAL_LIVE gate, same "live calls cost money, scoring replays for free".

The prompt gives the model *no* glossary and no domain context, because DeepL got none
either (deepl_translate sends text + target_lang and nothing else). The control is only
fair if both engines are handicapped identically.
"""
import os
import json
import re

import httpx

GEMINI_KEY_ENV = "GEMINI_API_KEY"
API_ROOT = "https://generativelanguage.googleapis.com/v1beta"

# Preference order when the caller does not name a model. Resolved against the key's own
# ListModels response rather than hardcoded, so a key without access to the newest model
# still runs instead of 404-ing.
MODEL_PREFERENCE = ("gemini-3.5-flash", "gemini-2.0-flash", "gemini-1.5-flash", "gemini-1.5-pro")

_resolved_model: str | None = None


def _key() -> str:
    key = os.environ.get(GEMINI_KEY_ENV)
    if not key:
        raise ValueError(f"{GEMINI_KEY_ENV} is not set")
    return key


def resolve_model(preferred: str | None = None) -> str:
    """The model id to translate with: the caller's, else the best available on this key.

    Asks the API what it has rather than assuming; model availability varies by key and a
    wrong guess fails the whole run at the first clip.
    """
    global _resolved_model
    if preferred:
        return preferred
    if _resolved_model:
        return _resolved_model

    resp = httpx.get(f"{API_ROOT}/models", params={"key": _key()}, timeout=30.0)
    resp.raise_for_status()
    available = {
        m["name"].removeprefix("models/")
        for m in resp.json().get("models", [])
        if "generateContent" in m.get("supportedGenerationMethods", [])
    }
    for candidate in MODEL_PREFERENCE:
        if candidate in available:
            _resolved_model = candidate
            return candidate
    if not available:
        raise RuntimeError("No Gemini models on this key support generateContent.")
    _resolved_model = sorted(available)[0]
    return _resolved_model


def _strip_preamble(text: str) -> str:
    """Drop a conversational wrapper if the model added one.

    chrF is character-n-gram overlap, so a single "Here is the translation:" line would
    depress the score for a reason that has nothing to do with translation quality — and
    this script exists precisely to stop measurement artifacts from being read as findings.
    """
    text = text.strip()
    text = re.sub(r"^```(?:\w+)?\n(.*)\n```$", r"\1", text, flags=re.DOTALL).strip()
    first, _, rest = text.partition("\n")
    if rest.strip() and re.match(r"^(here'?s|here is|sure|certainly|translation)\b.*:\s*$", first.strip(), re.I):
        return rest.strip()
    return text


def gemini_translate(text: str, target_lang: str, model: str | None = None) -> str:
    """Translate `text` into `target_lang` (a DeepL-style code such as HU)."""
    if not text.strip():
        return ""
    model_id = resolve_model(model)
    prompt = (
        f"Translate the following English text into {target_lang}.\n"
        "It is a transcript of a spoken lecture, so it contains disfluencies and "
        "incomplete sentences; translate them as they are rather than tidying them up.\n"
        "Output only the translation. No preamble, no notes, no quotation marks.\n\n"
        f"{text}"
    )
    resp = httpx.post(
        f"{API_ROOT}/models/{model_id}:generateContent",
        params={"key": _key()},
        json={
            "contents": [{"parts": [{"text": prompt}]}],
            # Deterministic, so a re-run reproduces the fixture rather than a new sample.
            "generationConfig": {"temperature": 0.0},
        },
        timeout=120.0,
    )
    resp.raise_for_status()
    candidates = resp.json().get("candidates", [])
    if not candidates:
        raise RuntimeError(f"Gemini returned no candidates: {resp.text[:300]}")
    parts = candidates[0].get("content", {}).get("parts", [])
    return _strip_preamble("".join(p.get("text", "") for p in parts))


def translate_cached(kind: str, clip_id: str, text: str, target_lang: str,
                     translations_dir: str, model: str | None = None) -> str:
    """Live-translate + record when EVAL_LIVE=1, else replay the fixture."""
    is_live = os.environ.get("EVAL_LIVE", "0") == "1"
    kind_dir = os.path.join(translations_dir, kind)
    fix_path = os.path.join(kind_dir, f"{clip_id}.{target_lang}.json")

    if not is_live:
        if not os.path.exists(fix_path):
            raise FileNotFoundError(
                f"Translation fixture not found: {fix_path}. Run scripts/calibrate_metric.py first"
            )
        with open(fix_path, "r") as f:
            return json.load(f)["text"]

    os.makedirs(kind_dir, exist_ok=True)
    translated = gemini_translate(text, target_lang, model)
    with open(fix_path, "w") as f:
        json.dump({
            "text": translated,
            "target_lang": target_lang,
            "source_chars": len(text),
            "engine": f"gemini:{resolve_model(model)}",
        }, f, indent=2, ensure_ascii=False)
    return translated
