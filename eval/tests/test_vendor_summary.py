"""Optional word times must not prevent offline vendor-refresh reporting."""
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def test_vendor_summary_filters_untimed_clips_and_prints_na(tmp_path, monkeypatch):
    import config
    from scripts.summarize_vendor_refresh import summarize, markdown
    def write(path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value))
    entries = [{'id': c, 'ref': 'refs.txt', 'domain_terms': ['hello'], 'verified': True} for c in ('c1', 'c2')]
    (tmp_path / 'refs.txt').write_text('hello')
    write(tmp_path / 'manifest.json', {'entries': entries})
    monkeypatch.setattr(config, 'MANIFEST_PATH', str(tmp_path / 'manifest.json'))
    monkeypatch.setattr(config, 'GOLDEN_DIR', str(tmp_path))
    baseline, candidate = tmp_path / 'baseline', tmp_path / 'candidate'
    for e in entries:
        clip = e['id']
        for label, root in [('old', baseline), ('new', candidate)]:
            write(root / f'soniox/{clip}.json', {'text': 'hello'})
            start = 0 if label == 'old' and clip == 'c1' else None
            write(root / f'soniox-stream/{clip}.json', {
                'final_text': 'hello', 'events': [], 'audio_duration_s': 3,
                'final_words': [{'word': 'hello', 'start_s': start, 'final_t_recv': 2}],
            })
        write(candidate / f'soniox-boost/{clip}.json', {'text': 'hello', 'model': 'stt-async-v6'})
        for lang in ('hu', 'ru'):
            for ideal in ('ref', 'ref-gemini'):
                write(baseline / f'translations/{ideal}/{clip}.{lang.upper()}.json', {'text': 'Szia'})
            for arm in ('none', 'app'):
                write(candidate / f'soniox-inband-{arm}/{clip}.{lang}.json', {
                    'model': 'stt-async-v6', 'target_language': lang, 'context_arm': arm,
                    'translation_text': 'Szia', 'request_config': {'context': {'text': 'domain'}},
                })
            for arm in ('soniox-translate-batch', 'soniox-translate'):
                write(baseline / f'{arm}/{lang}/{clip}.json', {'text': 'Szia'})
        for provider in ('moonshine', 'whistle'):
            run = {'text': 'hello', 'result': {'text': 'hello'}, 'rtf': 0.1, 'elapsed_s': 1,
                   'events': [{'result': {'text': 'hello'}}]}
            for mode in ('batch', 'stream'):
                write(candidate / f'pixel7a/{provider}/{mode}/{clip}.json', {'runs': [run, run]})
    result = summarize(baseline, candidate)
    old, new = result['asr']['legacy_v5'], result['asr']['v6']
    assert old['final_latency_med_s'] == 2
    assert old['final_latency_med_s_clips'] == 1
    assert new['final_latency_med_s'] is None
    assert new['final_latency_med_s_clips'] == 0
    report = markdown(result)
    assert '| 2.00 (n=1) | n/a (n=0) |' in report
