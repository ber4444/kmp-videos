"""The vocabulary-injection arms, scored offline.

`record_asr_context.py` answers whether telling Soniox the words in advance fixes the
transcript, and bounds what slide OCR could be worth. The parts worth pinning are the ones
that could be wrong while still producing plausible numbers: the oracle's term selection
(too generous and the ceiling is meaningless, too strict and it understates), and the
context shape per arm (send the wrong field and the arm silently measures nothing).
"""
import os
import sys
import json

import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
import config
from scripts.record_asr_context import build_oracle_terms, context_for, STOPWORDS


def _entry(clip_id, ref_name, domain_terms=()):
    return {"id": clip_id, "ref": f"refs/{ref_name}", "domain_terms": list(domain_terms),
            "source": {"event": "e", "rendition": "r", "start_s": 0, "dur_s": 60},
            "conditions": ["clean"], "verified": True}


@pytest.fixture
def corpus(tmp_path, monkeypatch):
    """Eight clips sharing ordinary words; two carry a rare term each."""
    refs = tmp_path / "refs"
    refs.mkdir(parents=True)
    common = "the work of the school is that which we do here every day and again"
    texts = {
        "c1": f"{common} uncreated light",
        "c2": f"{common} dominocus",
        **{f"c{i}": common for i in range(3, 9)},
    }
    for cid, text in texts.items():
        (refs / f"{cid}.txt").write_text(text, encoding="utf-8")
    monkeypatch.setattr(config, "GOLDEN_DIR", str(tmp_path))
    return [_entry(cid, f"{cid}.txt") for cid in texts]


def test_rare_words_are_selected_and_common_ones_are_not(corpus):
    oracle = build_oracle_terms(corpus)
    assert "uncreated" in oracle["c1"] and "dominocus" in oracle["c2"]
    # "school" and "every" appear in all eight, so they are not distinctive to any clip.
    for terms in oracle.values():
        assert "school" not in terms
        assert "every" not in terms


def test_a_clips_rare_word_does_not_leak_into_other_clips(corpus):
    """The oracle is per clip. Pooling every clip's vocabulary would hand each session
    words that were never spoken in it and quietly overstate the ceiling."""
    oracle = build_oracle_terms(corpus)
    assert "dominocus" not in oracle["c1"]
    assert "uncreated" not in oracle["c2"]


def test_short_words_and_stopwords_are_excluded(corpus):
    oracle = build_oracle_terms(corpus)
    for terms in oracle.values():
        assert all(len(t) >= 4 for t in terms if " " not in t)
        assert not (set(terms) & STOPWORDS)


def test_labelled_domain_terms_are_always_included(tmp_path, monkeypatch):
    """A term on the manifest belongs in the context whatever its corpus frequency — it is
    exactly what a slide would carry, and rarity is only a proxy for that."""
    refs = tmp_path / "refs"
    refs.mkdir(parents=True)
    for cid in ("c1", "c2"):
        (refs / f"{cid}.txt").write_text("the work the work the work", encoding="utf-8")
    monkeypatch.setattr(config, "GOLDEN_DIR", str(tmp_path))
    entries = [_entry("c1", "c1.txt", ["the work"]), _entry("c2", "c2.txt")]
    assert "the work" in build_oracle_terms(entries)["c1"]


def test_each_arm_sends_the_field_it_claims_to(corpus):
    oracle = build_oracle_terms(corpus)
    entry = corpus[0]
    glossary = ["Uncreated light"]

    assert context_for("none", entry, glossary, oracle) is None
    assert context_for("glossary", entry, glossary, oracle) == {"terms": glossary}
    # The slide proxy is a term list; the ceiling is free text. Swapping them would compare
    # two things that are not the arms the report names.
    assert "terms" in context_for("oracle-terms", entry, glossary, oracle)
    full = context_for("oracle-full", entry, glossary, oracle)
    assert "text" in full and "uncreated light" in full["text"]


def test_an_unknown_arm_is_rejected(corpus):
    with pytest.raises(ValueError):
        context_for("made-up", corpus[0], [], build_oracle_terms(corpus))
