"""COMET scoring — optional, and off unless explicitly installed and asked for.

chrF measures character-n-gram overlap with the ideal, which is why this harness could
report a two-stage translation as +19.7 better when the honest figure was about zero: the
ideal was DeepL output and so was that arm, and chrF rewarded the shared phrasing.
`scripts/calibrate_metric.py` corrects for that with a control. COMET is the other way
round — a trained model that scores meaning rather than surface form, so an equally
correct translation phrased differently is not punished for it in the first place.

It is **not** installed by default and nothing here requires it. COMET pulls in torch and
a ~2.3 GB checkpoint, against a harness whose whole posture is "scoring is free, offline
and runs in CI". Enable it deliberately:

    pip install -r requirements-comet.txt
    EVAL_COMET=1 python scoring/scorecard.py

Without both the install and the flag, `comet_scores` returns None and every caller falls
back to chrF alone — the report then says COMET was not run rather than implying it agreed.
"""
import os
from functools import lru_cache
from typing import List, Optional

# Reference-based, the standard choice when you have a reference translation and want a
# quality estimate that is not surface-form. wmt22-comet-da is the widely-reported one, so
# published numbers are a sanity check on this harness rather than an unknown.
DEFAULT_MODEL = os.environ.get("EVAL_COMET_MODEL", "Unbabel/wmt22-comet-da")


def comet_enabled() -> bool:
    """True only when the caller asked for COMET *and* it is importable."""
    if os.environ.get("EVAL_COMET", "0") != "1":
        return False
    try:
        import comet  # noqa: F401
    except ImportError:
        return False
    return True


@lru_cache(maxsize=1)
def _model():
    from comet import download_model, load_from_checkpoint
    return load_from_checkpoint(download_model(DEFAULT_MODEL))


def comet_scores(sources: List[str], hypotheses: List[str],
                 references: List[str]) -> Optional[List[float]]:
    """Per-segment COMET scores (roughly 0–1, higher is better), or None when disabled.

    `sources` is the English the translation came from. COMET reads it, which is the point:
    it can tell a mistranslation from a rephrasing, where a reference-only metric cannot.
    """
    if not comet_enabled():
        return None
    if not (len(sources) == len(hypotheses) == len(references)):
        raise ValueError("sources, hypotheses and references must be the same length")
    if not sources:
        return []
    data = [{"src": s, "mt": h, "ref": r} for s, h, r in zip(sources, hypotheses, references)]
    return list(_model().predict(data, batch_size=8, gpus=0).scores)


def comet_status() -> str:
    """One line for the report, so a missing COMET is never read as a passing COMET."""
    if os.environ.get("EVAL_COMET", "0") != "1":
        return "not run (set EVAL_COMET=1 to enable)"
    try:
        import comet  # noqa: F401
    except ImportError:
        return "not run (EVAL_COMET=1 but `comet` is not installed: pip install -r requirements-comet.txt)"
    return f"enabled ({DEFAULT_MODEL})"
