import pytest
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
import config
from scoring.scorecard import generate_scorecard

@pytest.mark.scorecard
def test_scorecard_generation(tmp_path, monkeypatch):
    """
    Runs the scorecard generation offline using fixtures.
    """
    # Tests may replay local fixtures but must not overwrite measured reports.
    monkeypatch.setattr(config, "REPORTS_DIR", str(tmp_path))
    # Allow unverified for testing purposes if references are unverified
    generate_scorecard(allow_unverified=True)
    report_path = os.path.join(config.REPORTS_DIR, "scorecard.md")
    assert os.path.exists(report_path)
