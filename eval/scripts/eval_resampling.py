"""Fetch native clips, prepare PCM arms, record recognition, and score offline.

Only `record` spends. Preparation and responses stay under gitignored fixtures/;
reports contain aggregate tables only. The entire pending experiment is capped
before its first API call. No --force option is provided.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import config
from app_context import soniox_context
from providers.base import TranscriptResult
from providers.soniox import SonioxProvider, BATCH_MODEL
from resampling import decimate, read_pcm, write_pcm, reference_pcm
from scoring.metrics import calculate_metrics, calculate_entity_metrics
from scripts.fetch_clips import get_stream_url


def entries():
    return [e for e in json.loads(Path(config.MANIFEST_PATH).read_text())['entries'] if e.get('verified')]


def fetch(root, clips):
    for entry in clips:
        out = root / 'native' / f"{entry['id']}.wav"
        source = entry['source']
        if not out.exists():
            out.parent.mkdir(parents=True, exist_ok=True)
            # Neither credentials nor the private stream address belong in logs.
            partial = out.with_suffix('.partial.wav')
            try:
                subprocess.run([
                    'ffmpeg', '-hide_banner', '-loglevel', 'error', '-y',
                    '-ss', str(source['start_s']), '-i', get_stream_url(source['event'], source['rendition']),
                    '-t', str(source['dur_s']), '-vn', '-ac', '1', '-c:a', 'pcm_s16le', str(partial),
                ], capture_output=True, check=True, timeout=180)
                samples, rate = read_pcm(partial)
                if abs(len(samples) / rate - source['dur_s']) > 0.1:
                    raise ValueError('Native extraction duration differs from manifest')
                partial.replace(out)
            finally:
                partial.unlink(missing_ok=True)
        samples, rate = read_pcm(out)
        if abs(len(samples) / rate - source['dur_s']) > 0.1:
            raise ValueError('Cached native extraction duration differs from manifest')
        print(f"Native {entry['id']}: {rate} Hz, {len(samples) / rate:g}s", flush=True)


def prepare(root, clips, supplemental_clips):
    import scipy
    jobs = []
    for index, entry in enumerate(clips):
        samples, native_rate = read_pcm(root / 'native' / f"{entry['id']}.wav")
        sources = [(native_rate, samples, ('avg', 'nearest', 'ref'))]
        if native_rate == 48000 and index < supplemental_clips:
            source_path = root / 'source-44100' / f"{entry['id']}.wav"
            write_pcm(source_path, reference_pcm(samples, native_rate, 44100), 44100)
            source441, _ = read_pcm(source_path)
            sources.append((44100, source441, ('avg', 'nearest')))
        for rate, source, arms in sources:
            for arm in arms:
                relative = f"audio/{rate}/{arm}/{entry['id']}.wav"
                pcm = reference_pcm(source, rate) if arm == 'ref' else decimate(source, rate, arm)
                write_pcm(root / relative, pcm)
                jobs.append({'clip_id': entry['id'], 'source_rate': rate, 'arm': arm, 'audio': relative})
        print(f"Prepared {entry['id']} ({native_rate} Hz)", flush=True)
    (root / 'preparation.json').write_text(json.dumps({
        'reference': f'scipy {scipy.__version__} resample_poly, default Kaiser window; rounded/clipped s16',
        'supplemental_selection': f'first {supplemental_clips} verified manifest entries at native 48000 Hz',
        'jobs': jobs,
    }, indent=2))


def jobs_with_identity(root, clips):
    allowed = {e['id'] for e in clips}
    context = soniox_context('en')  # Recognition only; English has no translation_terms.
    data = json.loads((root / 'preparation.json').read_text())
    seen = set()
    for job in data['jobs']:
        key = (job['source_rate'], job['arm'], job['clip_id'])
        if job['clip_id'] not in allowed or job['arm'] not in ('avg', 'nearest', 'ref') or key in seen:
            raise ValueError('Invalid or duplicate preparation job')
        seen.add(key)
        audio = root / job['audio']
        samples, rate = read_pcm(audio)
        if rate != 16000:
            raise ValueError('Recording requires 16 kHz audio')
        identity = {**{k: job[k] for k in ('clip_id', 'source_rate', 'arm')},
                    'audio_sha256': hashlib.sha256(audio.read_bytes()).hexdigest(),
                    'model': BATCH_MODEL, 'context': context}
        out = root / 'responses' / str(job['source_rate']) / job['arm'] / f"{job['clip_id']}.json"
        yield job, audio, out, identity, len(samples) / rate


def cached_result(out, identity):
    data = json.loads(out.read_text())
    if data.get('identity') != identity:
        raise ValueError('Fixture cache identity differs; select a new --root')
    result = TranscriptResult.model_validate(data['result'])
    if result.model != BATCH_MODEL:
        raise ValueError('Fixture model differs from requested model')
    return result


def record(root, clips, dry_run, parser):
    pending = []
    for job, audio, out, identity, duration in jobs_with_identity(root, clips):
        if out.exists():
            cached_result(out, identity)
        else:
            pending.append((job, audio, out, identity, duration))
    total = sum(job[-1] for job in pending)
    print(f'Pending recordings: {len(pending)}; billed audio: {total:g}s.', flush=True)
    if total > config.MAX_BILLED_AUDIO_SECONDS:
        parser.error(f'Billed audio {total:g}s exceeds cap of {config.MAX_BILLED_AUDIO_SECONDS:g}s')
    if dry_run or not pending:
        return
    if not os.getenv('SONIOX_API_KEY'):
        parser.error('SONIOX_API_KEY is not set')
    for job, audio, out, identity, duration in pending:
        print(f"Recording {job['source_rate']}/{job['arm']}/{job['clip_id']}", flush=True)
        result = SonioxProvider()._transcribe_batch(str(audio), [], False, context=identity['context'])
        if result.model != BATCH_MODEL:
            raise ValueError('Provider returned an unexpected model')
        out.parent.mkdir(parents=True, exist_ok=True)
        partial = out.with_suffix('.partial')
        partial.write_text(json.dumps({'identity': identity, 'audio_duration_s': duration,
                                       'result': result.model_dump()}, indent=2))
        partial.replace(out)


def paired_stats(left, right):
    shared = sorted(left.keys() & right.keys())
    if not shared:
        return None
    differences = [left[c] - right[c] for c in shared]
    rng = random.Random(0)
    bootstrap = sorted(sum(rng.choices(differences, k=len(shared))) / len(shared) for _ in range(10000))
    return {'delta': sum(differences) / len(shared), 'n': len(shared),
            'positive': sum(d > 0 for d in differences), 'negative': sum(d < 0 for d in differences),
            'lo': bootstrap[249], 'hi': bootstrap[9749]}


def score(root, clips):
    by_id = {e['id']: e for e in clips}
    cells = {}
    for job, audio, out, identity, duration in jobs_with_identity(root, clips):
        if not out.exists():
            continue
        result = cached_result(out, identity)
        entry = by_id[job['clip_id']]
        ref = (Path(config.GOLDEN_DIR) / entry['ref']).read_text()
        metrics = {**calculate_metrics(ref, result.text),
                   **calculate_entity_metrics(ref, result.text, entry['domain_terms'])}
        cells.setdefault(job['source_rate'], {}).setdefault(job['arm'], {})[job['clip_id']] = metrics
    preparation = json.loads((root / 'preparation.json').read_text())
    md = ['# PCM resampling recognition experiment', '',
          f'Model: `{BATCH_MODEL}`; recognition only. Context: app_context.soniox_context("en") (text + terms).',
          f"Reference: {preparation.get('reference', 'not recorded')}",
          f"Supplemental source: {preparation.get('supplemental_selection', 'not recorded')}", '',
          'Native clips use the verified manifest offsets, mono s16 without -ar. All arms receive the same native samples.',
          'Each cell is a per-clip mean. Deltas use only paired clips; ties count as neither win.',
          'CI: percentile paired bootstrap (10,000 resamples, seed 0). One API response per arm/clip; exploratory, no multiple-testing correction.', '',
          '| Source Hz | Arm | Normalized WER | Entity F1 |', '|---|---|---:|---:|']
    rates = sorted({j['source_rate'] for j in preparation['jobs']}, reverse=True)
    for rate in rates:
        arms = ('avg', 'nearest', 'ref') if any(j['source_rate'] == rate and j['arm'] == 'ref' for j in preparation['jobs']) else ('avg', 'nearest')
        for arm in arms:
            values = cells.get(rate, {}).get(arm, {})
            def mean(metric):
                return f"{sum(v[metric] for v in values.values()) / len(values):.4f} (n={len(values)})" if values else 'n/a (not run; n=0)'
            md.append(f'| {rate} | {arm} | {mean("wer_norm")} | {mean("entity_f1")} |')
    md.extend(['', '| Source Hz | Paired delta | Metric | Mean delta [95% CI] | n | Left wins | Right wins | Ties |',
               '|---|---|---|---:|---:|---:|---:|---:|'])
    for rate in rates:
        all_noise = True
        measured = False
        planned = {j['arm'] for j in preparation['jobs'] if j['source_rate'] == rate}
        for left, right in (('nearest', 'avg'), ('avg', 'ref')):
            if not {left, right} <= planned:
                continue
            for metric in ('wer_norm', 'entity_f1'):
                a = {c: v[metric] for c, v in cells.get(rate, {}).get(left, {}).items()}
                b = {c: v[metric] for c, v in cells.get(rate, {}).get(right, {}).items()}
                stats = paired_stats(a, b)
                if stats is None:
                    md.append(f'| {rate} | {left} − {right} | {metric} | n/a (not run) | 0 | n/a | n/a | n/a |')
                    continue
                measured = True
                all_noise &= stats['lo'] <= 0 <= stats['hi']
                wins = stats['negative'] if metric == 'wer_norm' else stats['positive']
                losses = stats['positive'] if metric == 'wer_norm' else stats['negative']
                md.append(f"| {rate} | {left} − {right} | {metric} | {stats['delta']:+.4f} [{stats['lo']:+.4f}, {stats['hi']:+.4f}] | {stats['n']} | {wins} | {losses} | {stats['n'] - wins - losses} |")
        if measured:
            md.extend(['', f'{rate} Hz: ' + ('no measurable difference on this set (all paired intervals include zero).' if all_noise else 'at least one paired interval excludes zero; see direction and metric above.'), ''])
    out = Path(config.REPORTS_DIR) / 'resampling.md'
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text('\n'.join(md) + '\n')
    print('\n'.join(md))
    print(f'Written to {out}')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['fetch', 'prepare', 'record', 'score'])
    parser.add_argument('--root', type=Path, default=Path(config.FIXTURES_DIR) / 'resampling')
    parser.add_argument('--supplemental-clips', type=int, default=12)
    parser.add_argument('--dry-run', action='store_true', help='Plan record without provider calls')
    args = parser.parse_args(argv)
    if args.supplemental_clips < 0:
        parser.error('--supplemental-clips must be nonnegative')
    try:
        clips = entries()
        if args.action == 'fetch':
            fetch(args.root, clips)
        elif args.action == 'prepare':
            prepare(args.root, clips, args.supplemental_clips)
        elif args.action == 'record':
            record(args.root, clips, args.dry_run, parser)
        else:
            score(args.root, clips)
    except Exception:
        # Provider errors and ffmpeg exceptions can contain stream URLs or request
        # details. Stop at the first failure without leaking those into shared logs.
        parser.error('Experiment failed (audio, provider, or cache); check local inputs and credentials. No further jobs run.')


if __name__ == '__main__':
    main()
