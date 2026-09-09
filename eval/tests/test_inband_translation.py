"""The in-band translation section of the scorecard, scored offline on synthetic fixtures.

No audio, no keys, no network: a tiny fake golden set and hand-written fixtures exercise the
whole path — fixture discovery, the Soniox→DeepL language-code mapping, chrF against the
ideal, and every column the report is actually for (what ships, the context ablation, the
full-context ceiling, and the two-stage alternative). The point is that the scoring is
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
    monkeypatch.setattr(config, "INBAND_BATCH_DIR", str(fixtures / "soniox-translate-batch"))
    monkeypatch.setattr(config, "INBAND_ENDPOINTED_DIR", str(fixtures / "soniox-translate-endpointed"))
    monkeypatch.setattr(config, "REPORTS_DIR", str(reports))

    # The ideal every column is scored against: DeepL on the verified reference.
    _write(str(fixtures / "translations" / "ref" / "clip-1.HU.json"),
           {"text": IDEAL_HU, "target_lang": "HU", "source_chars": 88})
    return tmp_path, fixtures, reports


def _report(reports):
    return (reports / "scorecard.md").read_text(encoding="utf-8")


COLUMNS = ("lang", "ctx", "noctx", "delta_ctx", "batch", "delta_streaming",
           "two_stage", "delta_two_stage", "floor")


def _row(report, lang):
    """The main in-band row for `lang`, as a dict keyed by COLUMNS."""
    for line in report.splitlines():
        if line.startswith(f"| {lang} |") and line.count("|") == len(COLUMNS) + 1:
            cells = [c.strip() for c in line.strip("|").split("|")]
            return dict(zip(COLUMNS, cells))
    raise AssertionError(f"no in-band row for {lang} in report")


def _value(cell):
    """The number out of a `54.6 (n=5)` cell, or None for `n/a`.

    Every cell carries its own clip count because the arms are recorded independently;
    a bare mean would hide that two columns describe different samples.
    """
    if cell.startswith("n/a"):
        return None
    return float(cell.split(" (")[0])


def _n(cell):
    """The clip count out of a `54.6 (n=5)` cell."""
    return int(cell.split("(n=")[1].rstrip(")"))


def _transcript_fixture(text):
    """The batch arm records a TranscriptResult, not a stream."""
    return {"text": text, "words": [], "raw_response": {"model": "stt-async-v5"}}


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

    row = _row(report, "hu")
    assert _n(row["ctx"]) == 1
    # The glossary arm keeps the accepted terms; the arm without it does not. The report has
    # to show that as a positive delta, or the A/B says nothing.
    assert _value(row["ctx"]) > _value(row["noctx"])
    assert row["delta_ctx"].startswith("+")
    # Nothing to compare against on the batch or two-stage paths until those fixtures exist.
    assert row["batch"] == "n/a" and row["delta_streaming"] == "n/a"
    assert row["two_stage"] == "n/a" and row["delta_two_stage"] == "n/a"


def test_a_perfect_caption_scores_100(workspace):
    _, fixtures, reports = workspace
    _write(str(fixtures / "soniox-translate" / "hu" / "clip-1.json"), _stream_fixture(IDEAL_HU))
    generate_scorecard()
    assert _value(_row(_report(reports), "hu")["ctx"]) == pytest.approx(100.0, abs=0.5)


def test_the_same_engine_arm_is_kept_out_of_the_comparable_table(workspace):
    """Soniox→DeepL is DeepL output scored against a DeepL ideal.

    It collects a same-engine bonus no other arm can, so differencing it against in-band
    measures which engine wrote the answer key, not translation quality. It stays in the
    report — it is a real shippable path — but in its own section, never as a Δ.
    """
    _, fixtures, reports = workspace
    _write(str(fixtures / "soniox-translate" / "hu" / "clip-1.json"), _stream_fixture(MANGLED_HU))
    _write(str(fixtures / "translations" / "soniox" / "clip-1.HU.json"),
           {"text": CLOSE_HU, "target_lang": "HU", "source_chars": 88})

    generate_scorecard()
    report = _report(reports)
    assert "Not comparable: Soniox → DeepL" in report
    assert "Δ vs in-band" not in report, "the invalid delta must not come back"
    # Present as a reported number, absent from the comparable row.
    assert "| hu | 91.6 (n=1) |" in report or "Soniox → DeepL chrF" in report


def test_the_two_stage_column_uses_the_mapped_language_code(workspace):
    """Fixtures are keyed by Soniox's code ("hu"), DeepL's by its own ("HU")."""
    _, fixtures, reports = workspace
    _write(str(fixtures / "soniox-translate" / "hu" / "clip-1.json"), _stream_fixture(MANGLED_HU))
    _write(str(fixtures / "translations" / "soniox-gemini" / "clip-1.HU.json"),
           {"text": CLOSE_HU, "target_lang": "HU", "source_chars": 88})

    generate_scorecard()
    row = _row(_report(reports), "hu")
    assert _value(row["two_stage"]) is not None
    assert _value(row["two_stage"]) > _value(row["ctx"])
    assert row["delta_two_stage"].startswith("+")


def test_the_floor_column_reports_the_different_engine_control(workspace):
    """A flawless translation by an engine that is not the ideal's still scores well under
    100, and the report has to say so or every other number reads as worse than it is."""
    _, fixtures, reports = workspace
    _write(str(fixtures / "soniox-translate" / "hu" / "clip-1.json"), _stream_fixture(MANGLED_HU))
    _write(str(fixtures / "translations" / "ref-gemini" / "clip-1.HU.json"),
           {"text": CLOSE_HU, "target_lang": "HU", "source_chars": 88})

    generate_scorecard()
    row = _row(_report(reports), "hu")
    assert _value(row["floor"]) is not None
    assert _value(row["floor"]) < 100.0


def test_deltas_only_average_clips_both_arms_recorded(workspace):
    """The bug this column format exists to prevent.

    Averaging one arm over clips it ran on, averaging another over a different set, and
    subtracting the two means reports a difference between samples as if it were an effect.
    Here the batch arm has a second clip the streamed arm never saw; the Δ must ignore it.
    """
    _, fixtures, reports = workspace
    manifest_path = config.MANIFEST_PATH
    manifest = json.loads(open(manifest_path).read())
    entry2 = dict(manifest["entries"][0], id="clip-2")
    manifest["entries"].append(entry2)
    open(manifest_path, "w").write(json.dumps(manifest))
    (config.GOLDEN_DIR + "/refs/clip-2.txt")
    with open(os.path.join(config.GOLDEN_DIR, "refs", "clip-2.txt"), "w") as f:
        f.write("The uncreated light is the source of conscious love, and self-remembering leads to it.")
    _write(str(fixtures / "translations" / "ref" / "clip-2.HU.json"),
           {"text": IDEAL_HU, "target_lang": "HU", "source_chars": 88})

    # Streamed arm: clip-1 only. Batch arm: both, and perfect on the clip the other lacks.
    _write(str(fixtures / "soniox-translate" / "hu" / "clip-1.json"), _stream_fixture(MANGLED_HU))
    _write(str(fixtures / "soniox-translate-batch" / "hu" / "clip-1.json"), _transcript_fixture(MANGLED_HU))
    _write(str(fixtures / "soniox-translate-batch" / "hu" / "clip-2.json"), _transcript_fixture(IDEAL_HU))

    generate_scorecard()
    row = _row(_report(reports), "hu")
    assert _n(row["batch"]) == 2 and _n(row["ctx"]) == 1
    # Identical text on the one shared clip, so the honest delta is zero — not the ~+30 an
    # unpaired difference of means would report from clip-2's perfect score.
    assert _value(row["delta_streaming"]) == pytest.approx(0.0, abs=0.5)
    assert _n(row["delta_streaming"]) == 1


def test_the_batch_arm_reports_the_streaming_penalty(workspace):
    """Batch sees the whole clip, so it is the ceiling the real-time arm is measured against.

    This is the column that separates "the captions lose to incremental commitment" from
    "the model is bad at this language" — the first is fixable in the client by buying
    latency, the second is not fixable at all without a different engine.
    """
    _, fixtures, reports = workspace
    _write(str(fixtures / "soniox-translate" / "hu" / "clip-1.json"), _stream_fixture(MANGLED_HU))
    _write(str(fixtures / "soniox-translate-batch" / "hu" / "clip-1.json"), _transcript_fixture(CLOSE_HU))

    generate_scorecard()
    row = _row(_report(reports), "hu")
    assert _value(row["batch"]) > _value(row["ctx"])
    assert row["delta_streaming"].startswith("+")


def test_a_batch_only_recording_still_gets_a_row(workspace):
    # The cheap arm can be run on its own — no real-time pacing — and a language recorded
    # that way must not vanish from the report for want of the streamed arms.
    _, fixtures, reports = workspace
    _write(str(fixtures / "soniox-translate-batch" / "hu" / "clip-1.json"), _transcript_fixture(IDEAL_HU))

    generate_scorecard()
    row = _row(_report(reports), "hu")
    assert _n(row["batch"]) == 1
    assert _value(row["batch"]) == pytest.approx(100.0, abs=0.5)
    assert row["ctx"] == "n/a" and row["delta_streaming"] == "n/a"


def test_the_endpointing_arm_is_reported_on_flicker_not_chrf(workspace):
    """Endpoint detection is a display fix, so it is judged on what the viewer sees.

    The batch arm already bounds what any latency-buying mechanism does to the words, so a
    chrF-only verdict on this arm would call a real no-flicker win a null result.
    """
    _, fixtures, reports = workspace
    _write(str(fixtures / "soniox-translate" / "hu" / "clip-1.json"), _stream_fixture(CLOSE_HU))
    endpointed = _stream_fixture(CLOSE_HU)
    endpointed["session_config"] = {"model": "stt-rt-v5", "enable_endpoint_detection": True}
    _write(str(fixtures / "soniox-translate-endpointed" / "hu" / "clip-1.json"), endpointed)

    generate_scorecard()
    report = _report(reports)
    assert "Endpoint detection (flicker, not quality)" in report
    assert "Flicker (endpointed)" in report
    # The config actually sent is echoed, so an ignored knob cannot be read as a null result.
    assert "enable_endpoint_detection" in report


def test_only_translated_tokens_become_the_batch_caption():
    """Soniox returns the original and the translation in one list on the batch API too.

    `TranscriptionTranscript.text` is both languages spliced together, so the eval reads
    tokens and keeps the translated ones — the same selection SonioxClient.kt makes on the
    socket. Getting this wrong would score a bilingual mush and blame the language for it.
    """
    from providers.soniox import translated_text

    tokens = [
        {"text": "The uncreated ", "translation_status": "original"},
        {"text": "A teremtetlen ", "translation_status": "translation"},
        {"text": "light.", "translation_status": "original"},
        {"text": "fény.", "translation_status": "translation"},
        {"text": " untagged", "translation_status": None},
    ]
    assert translated_text(tokens) == "A teremtetlen fény."


def test_language_codes_map_to_deepl_targets():
    assert config.deepl_target("hu") == "HU"
    assert config.deepl_target("ru") == "RU"
    # DeepL splits Portuguese and English by region and has no bare "PT"/"EN" target.
    assert config.deepl_target("pt") == "PT-BR"
    assert config.deepl_target("en") == "EN-US"
