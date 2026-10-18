"""The Kotlin glossary parser behind the in-band translation eval.

`app_context.py` reads the context the app sends out of `CaptionGlossary.kt` so the eval
scores the configuration that actually ships. These are the invariants that keep that true —
and they are the Python mirror of `CaptionGlossaryTest.kt`, so a term added on the Kotlin side
without a rendering fails on both.
"""
import os
import sys

import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
from app_context import load_app_context, soniox_context


@pytest.fixture(scope="module")
def app():
    return load_app_context()


def test_the_domain_sentence_names_the_teaching(app):
    assert "Fourth Way" in app["domain"]
    assert "Ouspensky" in app["domain"]


def test_terms_parse_out_of_the_kotlin_list(app):
    assert len(app["terms"]) > 40
    assert "Uncreated light" in app["terms"]
    assert all(t.strip() for t in app["terms"])


def test_comments_are_not_mistaken_for_renderings(app):
    """The glossary keeps the wording it *rejected* in a `//` comment next to the one it uses.

    A parser that scanned for quoted strings without stripping comments would send the
    rejected alternate to Soniox — a silent, plausible-looking wrong answer, which is the
    worst kind for an eval to have.
    """
    hungarian = app["translations"]["hu"]
    assert hungarian["Uncreated light"] == "Teremtetlen fény"
    assert "Nemteremtett fény" not in hungarian.values()


def test_every_language_renders_every_term(app):
    # The same invariant CaptionGlossaryTest.kt enforces, checked here because this is the
    # copy the eval actually sends.
    for lang, pairs in app["translations"].items():
        assert set(pairs) == set(app["terms"]), f"{lang} does not render exactly TERMS"


def test_context_matches_what_the_client_would_send(app):
    with_glossary = soniox_context("hu")
    assert with_glossary["text"] == app["domain"]
    assert len(with_glossary["translation_terms"]) == len(app["terms"])
    assert {"source": "Uncreated light", "target": "Teremtetlen fény"} in with_glossary["translation_terms"]

    # A language with no glossary gets text + terms and no empty translation_terms array,
    # matching sonioxContext()'s omissions.
    without = soniox_context("ja")
    assert "translation_terms" not in without
    assert without["terms"] == app["terms"]
