"""COMET's opt-in gating.

The risk with an optional metric is not that it breaks — it is that it quietly does
nothing and the report still looks complete, so a chrF-only table gets read as two metrics
agreeing. These tests pin the gate and the status line that prevents that.
"""
import os
import sys

import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
from scoring import semantic


def test_disabled_by_default(monkeypatch):
    monkeypatch.delenv("EVAL_COMET", raising=False)
    assert semantic.comet_enabled() is False
    assert semantic.comet_scores(["a"], ["b"], ["c"]) is None


def test_the_flag_alone_is_not_enough(monkeypatch):
    """EVAL_COMET=1 without the package installed must stay off rather than raise."""
    monkeypatch.setenv("EVAL_COMET", "1")
    monkeypatch.setitem(sys.modules, "comet", None)
    real_import = __builtins__["__import__"] if isinstance(__builtins__, dict) else __builtins__.__import__

    def fake_import(name, *args, **kwargs):
        if name == "comet":
            raise ImportError("not installed")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr("builtins.__import__", fake_import)
    assert semantic.comet_enabled() is False
    assert "not installed" in semantic.comet_status()


def test_the_status_line_distinguishes_off_from_broken(monkeypatch):
    """"Not asked for" and "asked for but unavailable" need different fixes, so the report
    must not collapse them into one "n/a"."""
    monkeypatch.delenv("EVAL_COMET", raising=False)
    assert "set EVAL_COMET=1" in semantic.comet_status()


def test_mismatched_input_lengths_are_rejected(monkeypatch):
    """Silently zipping to the shortest list would score hypotheses against the wrong
    references and report a plausible number."""
    monkeypatch.setenv("EVAL_COMET", "1")
    monkeypatch.setattr(semantic, "comet_enabled", lambda: True)
    with pytest.raises(ValueError):
        semantic.comet_scores(["a", "b"], ["c"], ["d"])


def test_empty_input_is_not_an_error(monkeypatch):
    monkeypatch.setattr(semantic, "comet_enabled", lambda: True)
    assert semantic.comet_scores([], [], []) == []
