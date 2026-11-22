"""Pin the Kotlin port and the experiment's cap/cache boundaries, offline."""
import sys
from pathlib import Path
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

SAMPLES = [
    -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6,
    32767, -32768, 0, -7, 1, 1, 7, -1, -1, -32768, -32768, -32768,
    32767, 32767, 32767, 0, 0, 0, 100, 200, 300, -100, -200, -301,
    17, 18, 19, -17, -18, -20, 1000, -1000, 2, -1000, 1000, -2,
]
EXPECTED = bytes.fromhex('fcffffff020005000000ffff01000080ff7f0000c80038ff1200eeff00000000')


def test_averaging_matches_kotlin_fixed_48_sample_vector():
    from resampling import decimate
    assert decimate(SAMPLES, 48000, 'avg') == EXPECTED


def test_noninteger_step_and_negative_division_truncate_toward_zero():
    from resampling import decimate
    assert decimate([-7, 1, 1, 1, 2, 3, 4, 5, 6, 7, 8], 44100, 'avg') == bytes.fromhex('ffff02000600')


def test_nearest_uses_first_sample_of_each_bin():
    from resampling import decimate
    assert decimate([-7, 1, 1, 1, 2, 3, 4, 5, 6, 7, 8], 44100, 'nearest') == bytes.fromhex('f9ff01000500')


def test_identity_and_empty_pcm():
    from resampling import decimate
    assert decimate([-32768, -1, 0, 32767], 16000, 'avg') == bytes.fromhex('0080ffff0000ff7f')
    assert decimate([], 48000, 'avg') == b''


@pytest.fixture
def experiment(tmp_path, monkeypatch):
    import json
    import config
    from resampling import write_pcm
    entries = [{'id': 'c1', 'verified': True, 'domain_terms': [], 'ref': 'refs/c1.txt'}]
    manifest = tmp_path / 'manifest.json'
    manifest.write_text(json.dumps({'entries': entries}))
    monkeypatch.setattr(config, 'MANIFEST_PATH', str(manifest))
    monkeypatch.setattr(config, 'GOLDEN_DIR', str(tmp_path))
    (tmp_path / 'refs').mkdir()
    (tmp_path / 'refs/c1.txt').write_text('hello')
    root = tmp_path / 'experiment'
    jobs = []
    for arm in ('avg', 'nearest', 'ref'):
        relative = f'audio/48000/{arm}/c1.wav'
        write_pcm(root / relative, b'\0\0' * 32000)
        jobs.append({'clip_id': 'c1', 'source_rate': 48000, 'arm': arm, 'audio': relative})
    (root / 'preparation.json').write_text(json.dumps({'jobs': jobs, 'source_validation': {'status': 'matched', 'checked_clips': 1, 'matched_clips': 1}}))
    monkeypatch.delenv('SONIOX_API_KEY', raising=False)
    return root


def test_dry_run_and_cap_count_all_pending_arms_without_api_calls(experiment, monkeypatch, capsys):
    import config
    from scripts.eval_resampling import main
    main(['record', '--root', str(experiment), '--dry-run'])
    assert 'Pending recordings: 3; billed audio: 6s' in capsys.readouterr().out
    monkeypatch.setattr(config, 'MAX_BILLED_AUDIO_SECONDS', 5.9)
    with pytest.raises(SystemExit):
        main(['record', '--root', str(experiment), '--dry-run'])
    assert not (experiment / 'responses').exists()


def test_recording_caches_identity_and_stops_on_failure(experiment, monkeypatch):
    import json
    from providers.soniox import SonioxProvider
    from providers.base import TranscriptResult
    from scripts.eval_resampling import main
    monkeypatch.setenv('SONIOX_API_KEY', 'offline-test')
    calls = []
    def transcribe(self, wav_path, domain_terms, boost, context=None):
        calls.append(wav_path)
        if len(calls) == 2:
            raise RuntimeError('provider rejected')
        return TranscriptResult(text='hello', words=[], raw_response={}, model='stt-async-v6')
    monkeypatch.setattr(SonioxProvider, '_transcribe_batch', transcribe)
    args = ['record', '--root', str(experiment)]
    with pytest.raises(SystemExit):
        main(args)
    assert len(calls) == 2
    paths = list((experiment / 'responses').rglob('*.json'))
    assert len(paths) == 1
    cached = json.loads(paths[0].read_text())
    assert cached['identity']['context']
    assert 'translation' not in cached['identity']
    assert cached['identity']['model'] == 'stt-async-v6'
    # A changed WAV must fail cache validation before spending on remaining jobs.
    Path(calls[0]).write_bytes(Path(calls[0]).read_bytes() + b'x')
    with pytest.raises(SystemExit):
        main(args)
    assert len(calls) == 2


def test_paired_report_uses_shared_clips_and_direction_specific_wins():
    from scripts.eval_resampling import paired_stats
    left = {'c1': 0.3, 'c2': 0.1, 'unpaired': 99}
    right = {'c1': 0.2, 'c2': 0.4}
    stats = paired_stats(left, right)
    assert stats['n'] == 2
    assert stats['delta'] == pytest.approx(-0.1)
    assert stats['positive'] == 1
    assert stats['negative'] == 1
    assert stats['lo'] <= 0 <= stats['hi']
    assert paired_stats({}, {}) is None


def test_offline_report_includes_paired_values_and_missing_arm(experiment, monkeypatch):
    import config
    from providers.soniox import SonioxProvider
    from providers.base import TranscriptResult
    from scripts.eval_resampling import main
    monkeypatch.setenv('SONIOX_API_KEY', 'offline-test')
    monkeypatch.setattr(config, 'REPORTS_DIR', str(experiment / 'reports'))
    def transcribe(self, wav_path, domain_terms, boost, context=None):
        text = 'goodbye' if '/nearest/' in wav_path else 'hello'
        return TranscriptResult(text=text, words=[], raw_response={}, model='stt-async-v6')
    monkeypatch.setattr(SonioxProvider, '_transcribe_batch', transcribe)
    main(['record', '--root', str(experiment)])
    monkeypatch.delenv('SONIOX_API_KEY')
    main(['score', '--root', str(experiment)])
    report = (experiment / 'reports/resampling.md').read_text()
    assert '| 48000 | nearest − avg | wer_norm | +1.0000 [+1.0000, +1.0000] | 1 | 0 | 1 | 0 |' in report
    assert 'hello' not in report and 'goodbye' not in report
    (experiment / 'responses/48000/ref/c1.json').unlink()
    main(['score', '--root', str(experiment)])
    report = (experiment / 'reports/resampling.md').read_text()
    assert '| 48000 | avg − ref | wer_norm | n/a (not run) | 0 |' in report


def test_entity_sample_excludes_unlabelled_clips_and_two_rates_share_a_table(experiment, monkeypatch):
    import json
    import config
    from resampling import write_pcm
    from providers.soniox import SonioxProvider
    from providers.base import TranscriptResult
    from scripts.eval_resampling import main
    manifest = Path(config.MANIFEST_PATH)
    data = json.loads(manifest.read_text())
    data['entries'][0]['domain_terms'] = ['hello']
    data['entries'].append({**data['entries'][0], 'id': 'unlabelled', 'domain_terms': []})
    manifest.write_text(json.dumps(data))
    preparation = experiment / 'preparation.json'
    data = json.loads(preparation.read_text())
    for clip, rate, arms in [('unlabelled', 48000, ('avg', 'nearest', 'ref')), ('c1', 44100, ('avg', 'nearest'))]:
        for arm in arms:
            relative = f'audio/{rate}/{arm}/{clip}.wav'
            write_pcm(experiment / relative, b'\0\0' * 32000)
            data['jobs'].append({'clip_id': clip, 'source_rate': rate, 'arm': arm, 'audio': relative})
    preparation.write_text(json.dumps(data))
    monkeypatch.setattr(config, 'REPORTS_DIR', str(experiment / 'reports'))
    monkeypatch.setenv('SONIOX_API_KEY', 'offline-test')
    monkeypatch.setattr(SonioxProvider, '_transcribe_batch', lambda *a, **k:
                        TranscriptResult(text='hello', words=[], raw_response={}, model='stt-async-v6'))
    main(['record', '--root', str(experiment)])
    main(['score', '--root', str(experiment)])
    report = (experiment / 'reports/resampling.md').read_text()
    assert '| 48000 | avg | 0.0000 (n=2) | 1.0000 (n=1) |' in report
    assert '| 48000 | nearest − avg | entity_f1 | +0.0000 [+0.0000, +0.0000] | 1 |' in report
    table = report.split('| Source Hz | Paired delta')[1].split('\n\n')[0]
    assert '| 44100 | nearest − avg | wer_norm |' in table


def test_replay_uses_recorded_context_but_live_resume_rejects_configuration_drift(experiment, monkeypatch):
    import config
    from providers.soniox import SonioxProvider
    from providers.base import TranscriptResult
    from scripts import eval_resampling as run
    monkeypatch.setattr(config, 'REPORTS_DIR', str(experiment / 'reports'))
    monkeypatch.setenv('SONIOX_API_KEY', 'offline-test')
    monkeypatch.setattr(SonioxProvider, '_transcribe_batch', lambda *a, **k:
                        TranscriptResult(text='hello', words=[], raw_response={}, model='stt-async-v6'))
    args = ['--root', str(experiment)]
    run.main(['record', *args])
    monkeypatch.delenv('SONIOX_API_KEY')
    monkeypatch.setattr(run, 'soniox_context', lambda lang: {'text': 'new domain', 'terms': ['changed']})
    monkeypatch.setattr(run, 'BATCH_MODEL', 'future-model')
    run.main(['score', *args])
    assert 'stt-async-v6' in (experiment / 'reports/resampling.md').read_text()
    with pytest.raises(SystemExit):
        run.main(['record', '--dry-run', *args])


def test_recording_refuses_a_run_with_unmatched_reference_audio(experiment, capsys):
    import json
    from scripts.eval_resampling import main
    path = experiment / 'preparation.json'
    data = json.loads(path.read_text())
    data['source_validation'] = {'status': 'mismatched'}
    path.write_text(json.dumps(data))
    with pytest.raises(SystemExit):
        main(['record', '--root', str(experiment), '--dry-run'])
    assert 'source' in capsys.readouterr().err.lower()
    assert not (experiment / 'responses').exists()


def test_source_match_distinguishes_reencoding_from_unrelated_audio():
    pytest.importorskip('scipy')
    import random
    from resampling import source_similarity
    rng = random.Random(123)
    original = [rng.randrange(-10000, 10000) for _ in range(16000)]
    unrelated = [rng.randrange(-10000, 10000) for _ in range(16000)]
    assert source_similarity(original, original) == pytest.approx(1)
    assert source_similarity(original, unrelated) < 0.1


def test_invalid_source_report_does_not_publish_accuracy_or_quality_deltas(experiment, monkeypatch):
    import json
    import config
    from providers.soniox import SonioxProvider
    from providers.base import TranscriptResult
    from scripts.eval_resampling import main
    monkeypatch.setattr(config, 'REPORTS_DIR', str(experiment / 'reports'))
    monkeypatch.setenv('SONIOX_API_KEY', 'offline-test')
    monkeypatch.setattr(SonioxProvider, '_transcribe_batch', lambda *a, **k:
                        TranscriptResult(text='hello', words=[], raw_response={}, model='stt-async-v6'))
    main(['record', '--root', str(experiment)])
    path = experiment / 'preparation.json'
    data = json.loads(path.read_text())
    data['source_validation'] = {'status': 'mismatched', 'matched_clips': 0, 'checked_clips': 1}
    path.write_text(json.dumps(data))
    main(['score', '--root', str(experiment)])
    report = (experiment / 'reports/resampling.md').read_text()
    assert 'invalid source' in report.lower()
    assert 'n/a (reference mismatch; n=0)' in report
    assert 'no measurable difference' not in report
    assert '| 48000 | avg | 1 |' in report
