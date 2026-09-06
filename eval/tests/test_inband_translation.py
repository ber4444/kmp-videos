"""The in-band translation section of the scorecard, scored offline on synthetic fixtures.

No audio, no keys, no network: a tiny fake golden set and hand-written fixtures exercise the
whole path — fixture discovery, the Soniox→DeepL language-code mapping, chrF against the
ideal, and the three columns the report is actually for. The point is that the scoring is
right *before* anyone spends an hour of real-time streaming on it.
"""
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
import config
from scoring.scorecard import generate_scorecard

# One sentence in Hungarian, then three renderings of it: perfect, close, and mangled.
IDEAL_HU = "A teremtetlen fény a tudatos szeretet forrása, és az önemlékezés vezet hozzá."
CLOSE_HU = "A teremtetlen fény a tudatos szeretet forrása, és az önemlékezés vezet oda."
MANGLED_HU = "A nem létrehozott lámpa a tudatos szerelem forrás, és az ön-emlékezés megy ott."


def _write(path, payload):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False)


def _stream_fixture(text):
    return {
        "final_text": text,
        "events": [{"t_recv": 1.0, "display_text": text, "is_final_update": True}],
        "final_words": [],
        "audio_duration_s": 60.0,
        "model": "stt-rt-v5+translate:hu",
    }


@pytest.fixture
def workspace(tmp_path, monkeypatch):
    """A self-contained golden set + fixtures tree, pointed at by config."""
    golden, fixtures, reports = tmp_path / "golden", tmp_path / "fixtures", tmp_path / "reports"
    (golden / "refs").mkdir(parents=True)
    (golden / "refs" / "clip-1.txt").write_text(
        "The uncreated light is the source of conscious love, and self-remembering leads to it.",
        encoding="utf-8",
    )
    manifest = {"entries": [{
        "id": "clip-1",
        "source": {"event": "8", "rendition": "_aac", "start_s": 0, "dur_s": 60},
        "conditions": ["clean"],
        "domain_terms": ["uncreated light"],
        "ref": "refs/clip-1.txt",
        "verified": True,
    }]}
    (golden / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

    monkeypatch.setattr(config, "GOLDEN_DIR", str(golden))
    monkeypatch.setattr(config, "MANIFEST_PATH", str(golden / "manifest.json"))
    monkeypatch.setattr(config, "FIXTURES_DIR", str(fixtures))
    monkeypatch.setattr(config, "TRANSLATIONS_DIR", str(fixtures / "translations"))
    monkeypatch.setattr(config, "INBAND_DIR", str(fixtures / "soniox-translate"))
    monkeypatch.setattr(config, "INBAND_NOCONTEXT_DIR", str(fixtures / "soniox-translate-nocontext"))
    monkeypatch.setattr(config, "REPORTS_DIR", str(reports))

    # The ideal every column is scored against: DeepL on the verified reference.
    _write(str(fixtures / "translations" / "ref" / "clip-1.HU.json"),
           {"text": IDEAL_HU, "target_lang": "HU", "source_chars": 88})
    return tmp_path, fixtures, reports


def _report(reports):
    return (reports / "scorecard.md").read_text(encoding="utf-8")


def _row(report, lang):
    for line in report.splitlines():
        if line.startswith(f"| {lang} |"):
            return [c.strip() for c in line.strip("|").split("|")]
    raise AssertionError(f"no in-band row for {lang} in report")


def test_the_section_is_absent_until_something_is_recorded(workspace):
    _, _, reports = workspace
    generate_scorecard()
    assert "In-Band Translation Quality" not in _report(reports)


def test_context_and_nocontext_arms_are_scored_against_the_same_ideal(workspace):
    _, fixtures, reports = workspace
    _write(str(fixtures / "soniox-translate" / "hu" / "clip-1.json"), _stream_fixture(CLOSE_HU))
    _write(str(fixtures / "soniox-translate-nocontext" / "hu" / "clip-1.json"), _stream_fixture(MANGLED_HU))

    generate_scorecard()
    report = _report(reports)
    assert "In-Band Translation Quality" in report

    lang, clips, ctx, noctx, delta, via, delta_via = _row(report, "hu")
    assert clips == "1"
    # The glossary arm keeps the accepted terms; the arm without it does not. The report has
    # to show that as a positive delta, or the A/B says nothing.
    assert float(ctx) > float(noctx)
    assert delta.startswith("+")
    # Nothing to compare against on the DeepL path until those fixtures exist.
    assert via == "n/a" and delta_via == "n/a"


def test_a_perfect_caption_scores_100(workspace):
    _, fixtures, reports = workspace
    _write(str(fixtures / "soniox-translate" / "hu" / "clip-1.json"), _stream_fixture(IDEAL_HU))
    generate_scorecard()
    assert float(_row(_report(reports), "hu")[2]) == pytest.approx(100.0, abs=0.5)


def test_the_via_deepl_column_uses_the_mapped_language_code(workspace):
    """Fixtures are keyed by Soniox's code ("hu"), DeepL's by its own ("HU")."""
    _, fixtures, reports = workspace
    _write(str(fixtures / "soniox-translate" / "hu" / "clip-1.json"), _stream_fixture(MANGLED_HU))
    _write(str(fixtures / "translations" / "soniox" / "clip-1.HU.json"),
           {"text": CLOSE_HU, "target_lang": "HU", "source_chars": 88})

    generate_scorecard()
    lang, clips, ctx, noctx, delta, via, delta_via = _row(_report(reports), "hu")
    assert via != "n/a"
    # DeepL beat the in-band caption here, which is exactly the finding the column exists to
    # surface — reported as a positive gap rather than left for the reader to subtract.
    assert float(via) > float(ctx)
    assert delta_via.startswith("+")


def test_language_codes_map_to_deepl_targets():
    assert config.deepl_target("hu") == "HU"
    assert config.deepl_target("ru") == "RU"
    # DeepL splits Portuguese and English by region and has no bare "PT"/"EN" target.
    assert config.deepl_target("pt") == "PT-BR"
    assert config.deepl_target("en") == "EN-US"
