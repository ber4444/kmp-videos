"""The control that decides whether the scorecard's two-stage lead is real.

`scripts/calibrate_metric.py` exists because the in-band table scores every arm against
DeepL's translation of the reference, which quietly advantages the one arm that is also
DeepL output. These tests cover the parts that can be wrong without failing loudly: the
response cleanup (a preamble left in place would depress chrF for a reason unrelated to
translation), the model resolution (a hardcoded id 404s on a key that lacks it), and the
scoring matrix itself, on synthetic fixtures — no key, no network, no spend.
"""
import os
import re
import sys
import json

import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
from scoring.gemini import _strip_preamble, MODEL_PREFERENCE
import scoring.gemini as gemini


def test_a_bare_translation_is_left_alone():
    assert _strip_preamble("Teremtetlen fény.") == "Teremtetlen fény."


def test_a_conversational_preamble_is_dropped():
    """chrF counts characters, so "Here is the translation:" is pure noise in the score."""
    assert _strip_preamble("Here is the translation:\nTeremtetlen fény.") == "Teremtetlen fény."
    assert _strip_preamble("Sure, here's the Hungarian:\nTeremtetlen fény.") == "Teremtetlen fény."


def test_a_colon_inside_real_prose_is_not_mistaken_for_a_preamble():
    """The lectures do contain lines ending in a colon; dropping one would silently
    truncate the hypothesis and score the remainder."""
    text = "The teaching says this:\nthe light is uncreated."
    assert _strip_preamble(text) == text


def test_a_fenced_block_is_unwrapped():
    assert _strip_preamble("```\nTeremtetlen fény.\n```") == "Teremtetlen fény."


def test_model_resolution_prefers_the_newest_available(monkeypatch):
    class FakeResp:
        def raise_for_status(self): pass
        def json(self):
            return {"models": [
                {"name": "models/gemini-1.5-flash", "supportedGenerationMethods": ["generateContent"]},
                {"name": "models/gemini-2.0-flash", "supportedGenerationMethods": ["generateContent"]},
            ]}

    monkeypatch.setattr(gemini, "_resolved_model", None)
    monkeypatch.setenv("GEMINI_API_KEY", "test-key")
    monkeypatch.setattr(gemini.httpx, "get", lambda *a, **k: FakeResp())
    assert gemini.resolve_model() == "gemini-2.0-flash"


def test_model_resolution_skips_models_that_cannot_generate(monkeypatch):
    """An embedding model answers ListModels but not generateContent."""
    class FakeResp:
        def raise_for_status(self): pass
        def json(self):
            return {"models": [
                {"name": "models/gemini-3.5-flash", "supportedGenerationMethods": ["embedContent"]},
                {"name": "models/gemini-1.5-pro", "supportedGenerationMethods": ["generateContent"]},
            ]}

    monkeypatch.setattr(gemini, "_resolved_model", None)
    monkeypatch.setenv("GEMINI_API_KEY", "test-key")
    monkeypatch.setattr(gemini.httpx, "get", lambda *a, **k: FakeResp())
    assert gemini.resolve_model() == "gemini-1.5-pro"


def test_an_explicit_model_is_not_looked_up(monkeypatch):
    def explode(*a, **k):
        raise AssertionError("should not call the API when the caller named a model")
    monkeypatch.setattr(gemini.httpx, "get", explode)
    assert gemini.resolve_model("gemini-3.0-whatever") == "gemini-3.0-whatever"


def test_the_preference_list_is_ordered_newest_first():
    """Ordering is what resolve_model relies on, so assert that rather than a version
    string — pinning one means this test fails every time the default is bumped, which
    says nothing about whether the list is still sorted."""
    versions = [float(re.match(r"gemini-(\d+\.\d+)", m).group(1)) for m in MODEL_PREFERENCE]
    assert versions == sorted(versions, reverse=True)


@pytest.fixture
def calibration_run(tmp_path, monkeypatch):
    """A whole harness in a temp dir: one clip, all six cells populated."""
    import config

    golden = tmp_path / "golden"
    (golden / "refs").mkdir(parents=True)
    (golden / "refs" / "c1.txt").write_text("the uncreated light is not hocus pocus")
    (golden / "manifest.json").write_text(json.dumps({"entries": [
        {"id": "c1", "source": {"event": "e", "rendition": "r", "start_s": 0, "dur_s": 60},
         "conditions": ["clean"], "domain_terms": [], "ref": "refs/c1.txt", "verified": True},
    ]}))

    fixtures = tmp_path / "fixtures"
    trans = fixtures / "translations"
    for kind, text in [
        ("ref", "A teremtetlen fény nem hókuszpókusz."),          # DeepL ideal
        ("ref-gemini", "A teremtetlen fény nem hókusz-pókusz."),  # control: perfect, other engine
        ("soniox", "A teremtetlen fény nem hókuszpókusz ma."),    # two-stage via DeepL
        ("soniox-gemini", "A teremtetlen fény nem hókusz-pókusz ma."),
    ]:
        (trans / kind).mkdir(parents=True)
        (trans / kind / "c1.HU.json").write_text(json.dumps({"text": text}), encoding="utf-8")

    inband = fixtures / "soniox-translate" / "hu"
    inband.mkdir(parents=True)
    (inband / "c1.json").write_text(json.dumps(
        {"final_text": "Teremtetlen világosság, nem bűvészkedés.", "events": [],
         "final_words": [], "audio_duration_s": 60.0}), encoding="utf-8")

    reports = tmp_path / "reports"
    reports.mkdir()

    monkeypatch.setattr(config, "GOLDEN_DIR", str(golden))
    monkeypatch.setattr(config, "MANIFEST_PATH", str(golden / "manifest.json"))
    monkeypatch.setattr(config, "FIXTURES_DIR", str(fixtures))
    monkeypatch.setattr(config, "TRANSLATIONS_DIR", str(trans))
    monkeypatch.setattr(config, "INBAND_DIR", str(fixtures / "soniox-translate"))
    monkeypatch.setattr(config, "REPORTS_DIR", str(reports))
    monkeypatch.setattr(sys, "argv", ["calibrate_metric.py", "--target", "hu", "--score-only"])
    return reports / "metric_calibration.hu.md"


def test_score_only_needs_no_key_and_writes_every_cell(calibration_run, monkeypatch):
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    import importlib
    calibrate = importlib.import_module("scripts.calibrate_metric")
    importlib.reload(calibrate)
    calibrate.main()

    report = calibration_run.read_text()
    assert "n/a" not in report, "every cell should be scored from the synthetic fixtures"
    for hypothesis in ("Gemini(reference)", "Soniox in-band", "DeepL(Soniox ASR)"):
        assert hypothesis in report


def test_the_control_outscores_a_different_engines_wording(calibration_run, monkeypatch):
    """The point of the whole script, as an assertion.

    `ref-gemini` differs from the DeepL ideal by one hyphen — a flawless translation that
    happens to share the ideal's phrasing. The in-band caption says the same thing in
    different words. If chrF did not rank the first far above the second there would be no
    same-engine bias to correct for, and the calibration would be pointless.
    """
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    import importlib
    calibrate = importlib.import_module("scripts.calibrate_metric")
    importlib.reload(calibrate)
    calibrate.main()

    rows = {}
    for line in calibration_run.read_text().splitlines():
        if line.startswith("| ") and " | " in line:
            parts = [c.strip() for c in line.strip("|").split("|")]
            if len(parts) >= 3 and parts[2].replace(".", "").isdigit():
                rows[(parts[0], parts[1])] = float(parts[2])

    control = rows[("Gemini(reference)", "DeepL(ref)")]
    inband = rows[("Soniox in-band", "DeepL(ref)")]
    assert control > inband + 20
