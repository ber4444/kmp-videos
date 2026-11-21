"""Recompute the vendor-refresh tables offline from complete recorded fixtures."""
import argparse
import hashlib
import json
from pathlib import Path
from statistics import mean, median
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import config
import jiwer
from providers.base import StreamResult
from scoring.metrics import calculate_metrics, calculate_entity_metrics, calculate_streaming_metrics, calculate_translation_fidelity
from scoring.normalize import normalize_text


def summarize(baseline, candidate):
    entries = json.loads(Path(config.MANIFEST_PATH).read_text())["entries"]
    if not all(entry.get("verified") for entry in entries):
        raise ValueError("Vendor comparison requires verified references")
    refs = {entry["id"]: (Path(config.GOLDEN_DIR) / entry["ref"]).read_text() for entry in entries}
    digests = {}
    def load(path):
        raw = path.read_bytes()
        # Only aggregate fingerprints are published, never audio or transcripts.
        resolved = path.resolve()
        if resolved.is_relative_to(candidate.resolve()):
            key = "candidate/" + str(resolved.relative_to(candidate.resolve()))
        else:
            key = "baseline/" + str(resolved.relative_to(baseline.resolve()))
        digests[key] = hashlib.sha256(raw).hexdigest()
        return json.loads(raw)
    def accuracy(hyps):
        metrics = [calculate_metrics(refs[e["id"]], hyps[e["id"]]) for e in entries]
        entities = [calculate_entity_metrics(refs[e["id"]], hyps[e["id"]], e["domain_terms"])["entity_f1"] for e in entries if e["domain_terms"]]
        return {"clips": len(metrics), "mean_clip_wer": mean(m["wer_norm"] for m in metrics),
                "corpus_wer": jiwer.wer([normalize_text(refs[e["id"]]) or "<empty>" for e in entries], [normalize_text(hyps[e["id"]]) for e in entries]),
                "entity_f1": mean(entities), "entity_clips": len(entities)}
    result = {"manifest_sha256": hashlib.sha256(Path(config.MANIFEST_PATH).read_bytes()).hexdigest(), "asr": {}, "translation": {}, "device": {}, "device_stream_smoke": {}}
    candidate_batch = {}
    for label, root in [("legacy_v5", baseline), ("v6", candidate)]:
        hyps = {e["id"]: load(root / "soniox" / f"{e['id']}.json")["text"] for e in entries}
        if label == "v6":
            candidate_batch = hyps
        batch = accuracy(hyps)
        streamed = [calculate_streaming_metrics(refs[e["id"]], StreamResult(**load(root / "soniox-stream" / f"{e['id']}.json"))) for e in entries]
        result["asr"][label] = {**batch, "streaming_clips": len(streamed), **{key: mean(m[key] for m in streamed) for key in streamed[0]}}
    boost_rows = []
    for e in entries:
        if not e["domain_terms"]:
            continue
        boosted = load(candidate / "soniox-boost" / f"{e['id']}.json")
        if boosted["model"] != "stt-async-v6":
            raise ValueError("Boosted fixture model mismatch")
        ref = refs[e["id"]]
        plain = candidate_batch[e["id"]]
        boost_rows.append({
            "baseline_wer": calculate_metrics(ref, plain)["wer_norm"],
            "boosted_wer": calculate_metrics(ref, boosted["text"])["wer_norm"],
            "baseline_entity_f1": calculate_entity_metrics(ref, plain, e["domain_terms"])["entity_f1"],
            "boosted_entity_f1": calculate_entity_metrics(ref, boosted["text"], e["domain_terms"])["entity_f1"],
        })
    result["v6_term_boost"] = {
        "clips": len(boost_rows),
        **{key: mean(row[key] for row in boost_rows) for key in boost_rows[0]},
        "paired_delta_wer": mean(row["boosted_wer"] - row["baseline_wer"] for row in boost_rows),
    }
    for arm in ["none", "app"]:
        for lang in ["hu", "ru"]:
            rows = []
            for e in entries:
                r = load(candidate / f"soniox-inband-{arm}" / f"{e['id']}.{lang}.json")
                if r["model"] != "stt-async-v6" or r["target_language"] != lang or r["context_arm"] != arm:
                    raise ValueError("Translation fixture arm/model mismatch")
                if arm == "app" and not r["request_config"]["context"].get("text"):
                    raise ValueError("App context is missing merged DOMAIN")
                row = {}
                for ideal in ["ref", "ref-gemini"]:
                    reference = load(baseline / "translations" / ideal / f"{e['id']}.{lang.upper()}.json")["text"]
                    row[ideal] = calculate_translation_fidelity(reference, r["translation_text"])["trans_chrf"]
                rows.append(row)
            result["translation"][f"v6_{arm}_{lang}"] = {"clips": len(rows), "deepl_ideal_chrf": mean(r['ref'] for r in rows), "gemini_ideal_chrf": mean(r['ref-gemini'] for r in rows)}
    for arm in ["soniox-translate-batch", "soniox-translate"]:
        for lang in ["hu", "ru"]:
            scores = []
            for e in entries:
                r = load(baseline / arm / lang / f"{e['id']}.json")
                reference = load(baseline / "translations/ref" / f"{e['id']}.{lang.upper()}.json")["text"]
                scores.append(calculate_translation_fidelity(reference, r['text'] if 'text' in r else r['final_text'])["trans_chrf"])
            result["translation"][f"legacy_{arm}_{lang}"] = {"clips": len(scores), "deepl_ideal_chrf": mean(scores)}
    for provider in ["moonshine", "whistle"]:
        hyps, rtfs, first_rtfs = {}, [], []
        for e in entries:
            r = load(candidate / "pixel7a" / provider / "batch" / f"{e['id']}.json")
            run = r['runs'][1]
            hyps[e['id']] = run['text'] if provider == 'moonshine' else run['result']['text']
            rtfs.append(run['rtf']); first_rtfs.append(r['runs'][0]['rtf'])
        result['device'][provider] = {**accuracy(hyps), 'median_warm_rtf': median(rtfs), 'max_warm_rtf': max(rtfs), 'median_first_rtf': median(first_rtfs)}
        e = entries[0]
        r = load(candidate / 'pixel7a' / provider / 'stream' / f"{e['id']}.json")
        runs = []
        for run in r['runs']:
            text = run['text'] if provider == 'moonshine' else ' '.join(event['result'].get('text','') for event in run['events']).strip()
            runs.append({'wer': calculate_metrics(refs[e['id']],text)['wer_norm'], 'compute_rtf':run['rtf'], 'elapsed_s':run['elapsed_s']})
        result['device_stream_smoke'][provider] = {'clip_id':e['id'],'runs':runs}
    result['input_fingerprint'] = hashlib.sha256(json.dumps(digests,sort_keys=True).encode()).hexdigest()
    return result


def markdown(r):
    lines=['# Vendor refresh measurements', '', 'Recorded November 20–21, 2026. Offline recomputation from the same 21 verified golden clips.', '', '## Soniox recognition', '', '| Metric | Historical v5 | v6 |', '|---|---:|---:|']
    a,b=r['asr']['legacy_v5'],r['asr']['v6']
    for title,key,fmt in [('Mean per-clip normalized WER','mean_clip_wer','.3f'),('Corpus normalized WER','corpus_wer','.3f'),('Mean Entity F1 (12 clips with terms)','entity_f1','.3f'),('Streaming WER (21 clips)','stream_wer','.3f'),('Flicker','flicker','.3f'),('Mean per-clip median final latency, seconds','final_latency_med_s','.2f'),('Mean per-clip p95 final latency, seconds','final_latency_p95_s','.2f')]:
        lines.append(f'| {title} | {a[key]:{fmt}} | {b[key]:{fmt}} |')
    boost = r['v6_term_boost']
    lines += ['', f"On the same {boost['clips']} clips with labelled terms, v6 term boosting changes mean WER from {boost['baseline_wer']:.3f} to {boost['boosted_wer']:.3f} (paired delta {boost['paired_delta_wer']:+.3f}) and Entity F1 from {boost['baseline_entity_f1']:.3f} to {boost['boosted_entity_f1']:.3f}. This arm supplies each clip's labelled vocabulary, not the fixed 49-term app glossary; it is not a controlled comparison with the historical full-set glossary experiment. The response fixtures store model IDs but not their request context; arm attribution follows the recording code."]
    lines += ['', 'Batch v6 is worse on both WER aggregations; streaming WER improves while flicker and finalization latency worsen. Latency uses provider timestamps and token finalizations, not forced alignment. Legacy batch fixtures lack stored model provenance; the historical v5 label follows the previous provider code. Streaming fixtures explicitly record v5/v6.', '', '## Async in-band translation', '', '| Target | No context, DeepL ideal | App context, DeepL ideal | No context, Gemini ideal | App context, Gemini ideal |', '|---|---:|---:|---:|---:|']
    for lang in ['hu','ru']:
        n=r['translation'][f'v6_none_{lang}'];a=r['translation'][f'v6_app_{lang}']
        lines.append(f"| {lang} (n=21) | {n['deepl_ideal_chrf']:.1f} | {a['deepl_ideal_chrf']:.1f} | {n['gemini_ideal_chrf']:.1f} | {a['gemini_ideal_chrf']:.1f} |")
    lines += ['', 'App context is the merged PR #82 DOMAIN text, 49 terms, and target-language mappings, read directly from Kotlin. Existing DeepL and Gemini reference translations were reused; no reference engine was called. These are whole-file async measurements, not real-time caption quality or latency.', '', '| Historical arm | HU chrF vs DeepL | RU chrF vs DeepL |', '|---|---:|---:|']
    for arm in ['soniox-translate-batch','soniox-translate']:
        lines.append(f"| {arm} (n=21 each) | {r['translation']['legacy_'+arm+'_hu']['deepl_ideal_chrf']:.1f} | {r['translation']['legacy_'+arm+'_ru']['deepl_ideal_chrf']:.1f} |")
    lines += ['', 'Historical async requests did not preserve their context; the real-time arm used different/partly missing session configuration. These rows are historical comparisons, not controlled v5→v6 deltas. Different-engine reference overlap is not a human quality ceiling.', '', '## Pixel 7a standalone recognition', '', '| Model | Mean normalized WER | Corpus WER | Median warm compute RTF | Maximum warm compute RTF |', '|---|---:|---:|---:|---:|']
    for name in ['moonshine','whistle']:
        d=r['device'][name]
        lines.append(f"| {name} (n=21) | {d['mean_clip_wer']:.3f} | {d['corpus_wer']:.3f} | {d['median_warm_rtf']:.3f} | {d['max_warm_rtf']:.3f} |")
    lines += ['', 'Pixel 7a, Tensor G2/GS201, Android 17, arm64. Two runs per clip with the model resident; the second supplies WER and warm RTF. Engines ran sequentially. RTF excludes load and pacing sleeps. Neither an HLS/Compose concurrency test nor a Fold3 repeat was performed.', '', '| Real-time-paced smoke on evt8-clip-1 | Run 1 WER / compute RTF | Run 2 WER / compute RTF |', '|---|---:|---:|']
    for name in ['moonshine','whistle']:
        a,b=r['device_stream_smoke'][name]['runs']
        lines.append(f"| {name} (one 60-second clip) | {a['wer']:.3f} / {a['compute_rtf']:.3f} | {b['wer']:.3f} / {b['compute_rtf']:.3f} |")
    lines += ['', 'A streaming smoke verifies chunk feeding and final flush on one clip; it is not a corpus streaming WER or a word-finalization latency estimate. See [native benchmark setup](native/README.md) for exact runtime, model, compiler and pacing settings.', '', '## Reproduce', '', 'Run `venv/bin/python scripts/summarize_vendor_refresh.py` from `eval/` with the original local fixtures and complete candidate recordings. Missing inputs fail rather than yielding a partial full-set table. Raw audio, transcripts and responses remain gitignored; this document and its JSON companion contain aggregate measurements only.', '', f"Manifest SHA-256: `{r['manifest_sha256']}`.", '', f"Input fingerprint: `{r['input_fingerprint']}`.", '']
    return '\n'.join(lines)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline',type=Path,default=Path(config.GOLDEN_DIR).parent/'fixtures')
    parser.add_argument('--candidate',type=Path,default=Path(config.GOLDEN_DIR).parent/'fixtures/vendor-refresh-2026-v6')
    parser.add_argument('--output',type=Path,default=Path(config.GOLDEN_DIR).parent/'VENDOR_REFRESH_RESULTS_2026.md')
    args=parser.parse_args()
    result=summarize(args.baseline,args.candidate)
    args.output.write_text(markdown(result))
    args.output.with_suffix('.json').write_text(json.dumps(result,indent=2)+'\n')
    print(f'Wrote aggregate report: {args.output}')


if __name__=='__main__':main()
