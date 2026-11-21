"""Recording controls must bound spending and preserve earlier model runs."""
import importlib
import json
import os
from pathlib import Path
import subprocess
import sys
import wave

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import config
from providers import SonioxProvider, TranscriptResult, StreamResult


@pytest.fixture
def recording_env(tmp_path, monkeypatch):
    clips = tmp_path / "clips"
    clips.mkdir()
    entries = []
    for name in ["first", "second"]:
        with wave.open(str(clips / f"{name}.wav"), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(16000)
            wav.writeframes(b"\0\0" * 16000 * 60)
        entries.append({"id": name, "source": {"dur_s": 60}, "domain_terms": ["Apollo"]})
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({"entries": entries}))
    monkeypatch.setattr(config, "MANIFEST_PATH", str(manifest))
    monkeypatch.setattr(config, "CLIPS_DIR", str(clips))
    monkeypatch.setattr(config, "FIXTURES_DIR", str(tmp_path / "v6"))
    monkeypatch.setenv("SONIOX_API_KEY", "offline-test")
    return tmp_path


@pytest.mark.parametrize("script,folder", [("record", "soniox"), ("record_stream", "soniox-stream")])
def test_one_clip_soniox_recording_is_bounded(script, folder, recording_env, monkeypatch):
    module = importlib.import_module(f"scripts.{script}")
    monkeypatch.setattr(sys, "argv", [script, "--provider", "soniox", "--max-clips", "1", "--baseline-only"]
                        if script == "record" else [script, "--provider", "soniox", "--max-clips", "1"])
    monkeypatch.setattr(SonioxProvider, "_transcribe_batch", lambda *a: TranscriptResult(text="Apollo", words=[], raw_response={}))
    monkeypatch.setattr(SonioxProvider, "_transcribe_stream_live", lambda *a: StreamResult(final_text="Apollo", events=[], final_words=[], audio_duration_s=60))
    module.main()
    assert sorted(str(p.relative_to(recording_env / "v6")) for p in (recording_env / "v6").rglob("*.json")) == [f"{folder}/first.json"]


@pytest.mark.parametrize("script", ["record", "record_stream"])
def test_dry_run_makes_no_calls_or_fixture_directories(script, recording_env, monkeypatch, capsys):
    module = importlib.import_module(f"scripts.{script}")
    monkeypatch.setattr(sys, "argv", [script, "--provider", "soniox", "--max-clips", "1", "--dry-run"])
    module.main()
    assert not (recording_env / "v6").exists()
    assert f"billed audio: {120 if script == 'record' else 60}s" in capsys.readouterr().out


def test_batch_cap_counts_boosted_calls(recording_env, monkeypatch, capsys):
    from scripts import record
    monkeypatch.setattr(config, "MAX_BILLED_AUDIO_SECONDS", 90)
    monkeypatch.setattr(sys, "argv", ["record", "--provider", "soniox", "--max-clips", "1"])
    with pytest.raises(SystemExit) as exc:
        record.main()
    assert exc.value.code != 0
    assert "120s exceeds cap of 90s" in capsys.readouterr().err
    assert not (recording_env / "v6").exists()


@pytest.mark.parametrize("script", ["record", "record_stream"])
def test_provider_failure_returns_nonzero(script, recording_env, monkeypatch, capsys):
    module = importlib.import_module(f"scripts.{script}")
    monkeypatch.setattr(sys, "argv", [script, "--provider", "soniox", "--max-clips", "1"])
    def fail(*args):
        raise RuntimeError("provider rejected model")
    monkeypatch.setattr(SonioxProvider, "_transcribe_batch", fail)
    monkeypatch.setattr(SonioxProvider, "_transcribe_stream_live", fail)
    with pytest.raises(SystemExit) as exc:
        module.main()
    assert exc.value.code != 0
    assert "provider rejected model" in capsys.readouterr().out
    assert not list((recording_env / "v6").rglob("*.json"))


def test_fixture_roots_apply_to_replay_and_scoring(tmp_path):
    fixture = tmp_path / "v6" / "soniox" / "clip.json"
    fixture.parent.mkdir(parents=True)
    fixture.write_text(json.dumps({"text": "v6 only", "words": [], "raw_response": {}}))
    result = subprocess.run([sys.executable, "-c", "import config; from providers import SonioxProvider; print(SonioxProvider().transcribe('clip.wav', [], False).text); print(config.TRANSLATIONS_DIR); print(config.REPORTS_DIR)"],
                            cwd=Path(__file__).resolve().parents[1],
                            env={**os.environ, "EVAL_LIVE": "0", "EVAL_FIXTURES_DIR": str(tmp_path / "v6"), "EVAL_REPORTS_DIR": str(tmp_path / "reports")}, capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    assert result.stdout.splitlines() == ["v6 only", str(tmp_path / "v6" / "translations"), str(tmp_path / "reports")]


def test_cost_cap_uses_audio_length_when_manifest_understates_it(recording_env, monkeypatch, capsys):
    from scripts import record
    manifest = Path(config.MANIFEST_PATH)
    data = json.loads(manifest.read_text())
    for entry in data["entries"]:
        entry["source"]["dur_s"] = 1
    manifest.write_text(json.dumps(data))
    monkeypatch.setattr(config, "MAX_BILLED_AUDIO_SECONDS", 90)
    monkeypatch.setattr(sys, "argv", ["record", "--provider", "soniox", "--max-clips", "1", "--dry-run"])
    with pytest.raises(SystemExit):
        record.main()
    assert "120s exceeds cap of 90s" in capsys.readouterr().err


@pytest.mark.parametrize("script,folder,model", [("record", "soniox", ""), ("record_stream", "soniox-stream", "stt-rt-v5")])
def test_old_soniox_cache_is_not_silently_treated_as_v6(script, folder, model, recording_env, monkeypatch, capsys):
    out = recording_env / "v6" / folder / "first.json"
    out.parent.mkdir(parents=True)
    out.write_text(json.dumps({"model": model}))
    module = importlib.import_module(f"scripts.{script}")
    monkeypatch.setattr(sys, "argv", [script, "--provider", "soniox", "--max-clips", "1", "--dry-run"])
    with pytest.raises(SystemExit):
        module.main()
    assert "model" in capsys.readouterr().err


@pytest.mark.parametrize("script,folder,model", [("record", "soniox", "stt-async-v6"), ("record_stream", "soniox-stream", "stt-rt-v6")])
def test_malformed_cached_results_do_not_count_as_completed(script, folder, model, recording_env, monkeypatch, capsys):
    out = recording_env / "v6" / folder / "first.json"
    out.parent.mkdir(parents=True)
    out.write_text(json.dumps({"model": model}))
    module = importlib.import_module(f"scripts.{script}")
    monkeypatch.setattr(sys, "argv", [script, "--provider", "soniox", "--max-clips", "1", "--dry-run"])
    with pytest.raises(SystemExit):
        module.main()
    assert "Invalid fixture" in capsys.readouterr().err
