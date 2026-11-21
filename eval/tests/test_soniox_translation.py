"""Offline boundary and spending checks for Soniox in-band translation."""
import json
from pathlib import Path
import sys
from types import SimpleNamespace
import wave

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import config


def test_app_context_reads_actual_glossary_and_rejects_shape_drift(tmp_path):
    from vendor_context import load_app_context, GLOSSARY_PATH
    context = load_app_context("hu")
    assert context["terms"][0] == "Uncreated light"
    assert context["translation_terms"][0] == {"source": "Uncreated light", "target": "Teremtetlen fény"}
    assert len(context["terms"]) == len(context["translation_terms"]) == 49
    assert {"terms", "translation_terms"} <= context.keys()
    changed = tmp_path / "CaptionGlossary.kt"
    changed.write_text(GLOSSARY_PATH.read_text().replace('"Uncreated light" to "Teremtetlen fény",', '"Uncreated light" to renderTerm(),'))
    with pytest.raises(ValueError, match="glossary"):
        load_app_context("hu", glossary_path=changed)


def test_request_and_tokens_keep_asr_and_translation_separate():
    from soniox_translation import request_config, split_tokens
    assert request_config("ru", "none") == {
        "model": "stt-async-v6", "language_hints": ["en"],
        "translation": {"type": "one_way", "target_language": "ru"},
    }
    app = request_config("ru", "app")
    assert app["context"]["translation_terms"][0]["target"] == "Несотворённый Свет"
    tokens = [
        {"text": "Good", "translation_status": "original", "language": "en"},
        {"text": " day", "translation_status": "none", "language": "en"},
        {"text": "Jó", "translation_status": "translation", "language": "hu", "source_language": "en"},
        {"text": " napot", "translation_status": "translation", "language": "hu", "source_language": "en"},
        {"text": "<end>", "translation_status": "translation", "language": "hu"},
    ]
    assert split_tokens(tokens, "hu") == ("Good day", "Jó napot")
    # Language tags are absent when language identification is not requested.
    assert split_tokens([
        {"text": "Hello", "translation_status": "original"},
        {"text": "Szia", "translation_status": "translation"},
    ], "hu") == ("Hello", "Szia")
    with pytest.raises(ValueError, match="language"):
        split_tokens(tokens, "ru")
    with pytest.raises(ValueError, match="translation_status"):
        split_tokens([{"text": "ambiguous"}], "hu")
    with pytest.raises(ValueError, match="translated"):
        split_tokens(tokens[:2], "hu")


def test_app_context_includes_domain_from_merged_glossary(tmp_path):
    from vendor_context import load_app_context, GLOSSARY_PATH
    path = tmp_path / "CaptionGlossary.kt"
    source = GLOSSARY_PATH.read_text()
    start = source.index("const val DOMAIN")
    end = source.index("val TERMS", start)
    source = source[:start] + 'const val DOMAIN: String = "Fourth " + "Way lectures."\n    ' + source[end:]
    path.write_text(source)
    assert load_app_context("hu", glossary_path=path)["text"] == "Fourth Way lectures."


@pytest.fixture
def recording_env(tmp_path, monkeypatch):
    clips = tmp_path / "clips"
    clips.mkdir()
    entries = []
    for name in ["first", "second"]:
        with wave.open(str(clips / f"{name}.wav"), "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(100)
            audio.writeframes(b"\0\0" * 6000)
        entries.append({"id": name, "source": {"dur_s": 60}})
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({"entries": entries}))
    monkeypatch.setattr(config, "MANIFEST_PATH", str(manifest))
    monkeypatch.setattr(config, "CLIPS_DIR", str(clips))
    monkeypatch.setattr(config, "FIXTURES_DIR", str(tmp_path / "v6"))
    monkeypatch.delenv("SONIOX_API_KEY", raising=False)
    return tmp_path


def test_dry_run_counts_each_target_context_and_first_n_without_network(recording_env, capsys):
    from scripts.record_soniox_translation import main
    main(["--dry-run", "--max-clips", "1"])
    assert "Pending recordings: 4; billed audio: 240s" in capsys.readouterr().out
    assert not (recording_env / "v6").exists()


def fake_client(requests):
    from soniox.types.api import TranscriptionTranscript
    def transcribe(**kwargs):
        requests.append(kwargs)
        return TranscriptionTranscript(id="offline", text="MIXED OUTPUT MUST NOT BE USED", tokens=[
            {"text": "Hello", "translation_status": "original", "language": "en"},
            {"text": "Szia", "translation_status": "translation", "language": "hu", "source_language": "en"},
        ])
    return SimpleNamespace(stt=SimpleNamespace(transcribe_and_wait_with_tokens=transcribe))


def test_record_replay_and_cache_identity(recording_env, monkeypatch, capsys):
    import soniox_translation
    from scripts.record_soniox_translation import main
    requests = []
    monkeypatch.setenv("SONIOX_API_KEY", "offline-test")
    monkeypatch.setattr(soniox_translation, "SonioxClient", lambda **kwargs: fake_client(requests))
    args = ["--max-clips", "1", "--target", "hu", "--context", "app"]
    main(args)
    path = recording_env / "v6/soniox-inband-app/first.hu.json"
    data = json.loads(path.read_text())
    assert data["asr_text"] == "Hello"
    assert data["translation_text"] == "Szia"
    assert data["model"] == "stt-async-v6"
    assert data["mode"] == "async"
    assert data["audio_duration_s"] == 60
    assert data["request_config"]["context"]["terms"][0] == "Uncreated light"
    assert requests[0]["model"] == data["model"]
    assert requests[0]["config"].model_dump(exclude_none=True)["translation"] == {"type": "one_way", "target_language": "hu"}
    main(args)
    assert len(requests) == 1
    assert "Pending recordings: 0; billed audio: 0s" in capsys.readouterr().out
    data["request_config"]["translation"]["target_language"] = "ru"
    path.write_text(json.dumps(data))
    with pytest.raises(SystemExit) as error:
        main(args)
    assert error.value.code != 0
    assert len(requests) == 1
    assert "cache" in capsys.readouterr().err.lower()


def test_cap_uses_pending_real_wav_durations(recording_env, monkeypatch, capsys):
    from scripts.record_soniox_translation import main
    monkeypatch.setattr(config, "MAX_BILLED_AUDIO_SECONDS", 239)
    with pytest.raises(SystemExit) as error:
        main(["--max-clips", "1", "--dry-run"])
    assert error.value.code != 0
    assert "240s exceeds cap of 239s" in capsys.readouterr().err
    assert not (recording_env / "v6").exists()


def test_provider_failure_is_nonzero_and_stops_first_job(recording_env, monkeypatch):
    import soniox_translation
    from scripts.record_soniox_translation import main
    calls = []
    def fail(**kwargs):
        calls.append(kwargs)
        raise RuntimeError("offline provider rejection")
    monkeypatch.setenv("SONIOX_API_KEY", "offline-test")
    monkeypatch.setattr(soniox_translation, "SonioxClient", lambda **kwargs: SimpleNamespace(stt=SimpleNamespace(transcribe_and_wait_with_tokens=fail)))
    with pytest.raises(SystemExit) as error:
        main([])
    assert error.value.code != 0
    assert len(calls) == 1
    assert not list((recording_env / "v6").rglob("*.json"))
