import os
import json
import sys
from pathlib import Path
from typing import Dict, List, Any
from collections import defaultdict

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
import config
from providers import TranscriptResult, StreamResult
from scoring.metrics import calculate_and_dump_diff, calculate_streaming_metrics, calculate_translation_fidelity
from scoring.semantic import comet_enabled, comet_scores, comet_status


def _load_translation(kind, clip_id, target_lang):
    """Replay a recorded DeepL translation, or None if not recorded."""
    path = os.path.join(config.TRANSLATIONS_DIR, kind, f"{clip_id}.{target_lang}.json")
    if not os.path.exists(path):
        return None
    with open(path, "r") as f:
        return json.load(f).get("text", "")

def _inband_languages():
    """Language codes with recorded in-band translation, from either variant's fixtures."""
    langs = set()
    for base in (config.INBAND_DIR, config.INBAND_NOCONTEXT_DIR, config.INBAND_BATCH_DIR,
                 config.INBAND_ENDPOINTED_DIR):
        if os.path.isdir(base):
            langs.update(d for d in os.listdir(base) if os.path.isdir(os.path.join(base, d)))
    return sorted(langs)


def generate_scorecard(allow_unverified: bool = False):
    with open(config.MANIFEST_PATH, 'r') as f:
        manifest = json.load(f)
        
    providers = ["deepgram", "soniox"]
    results_by_provider = defaultdict(list)
    results_by_condition = defaultdict(lambda: defaultdict(list))
    
    # Also track boosted vs baseline runs
    boost_comparison = defaultdict(lambda: defaultdict(dict)) # clip_id -> provider -> {"baseline": metrics, "boosted": metrics}

    # Streaming (Phase 4) results
    stream_by_provider = defaultdict(list)  # provider -> [{clip_id, metrics}]

    # Translation fidelity (ASR -> DeepL) results
    target_lang = config.TRANSLATE_TARGET_LANG
    translation_by_provider = defaultdict(list)  # provider -> [{clip_id, metrics}]

    # Soniox in-band translation (what the app ships), per language and variant.
    # lang -> {"context": [...], "nocontext": [...], "batch": [...], "control": [...],
    #          "two_stage": [...], "via_deepl_same_engine": [...]}
    inband_langs = _inband_languages()
    inband = defaultdict(lambda: defaultdict(list))
    
    for entry in manifest["entries"]:
        clip_id = entry["id"]
        ref_path = os.path.join(config.GOLDEN_DIR, entry["ref"])
        domain_terms = entry.get("domain_terms", [])
        
        if not entry.get("verified", False) and not allow_unverified:
            # Skip unverified
            continue
            
        if not os.path.exists(ref_path):
            print(f"Warning: Ref not found for {clip_id}, skipping.")
            continue
            
        with open(ref_path, "r") as f:
            ref_text = f.read()

        ref_translation = _load_translation("ref", clip_id, target_lang)

        for provider in providers:
            # Baseline
            fix_path = os.path.join(config.FIXTURES_DIR, provider, f"{clip_id}.json")
            if os.path.exists(fix_path):
                with open(fix_path, "r") as f:
                    hyp = TranscriptResult(**json.load(f))
                    
                metrics = calculate_and_dump_diff(ref_text, hyp.text, clip_id, provider, config.REPORTS_DIR, domain_terms)
                
                res = {
                    "clip_id": clip_id,
                    "metrics": metrics
                }
                results_by_provider[provider].append(res)
                
                for cond in entry.get("conditions", []):
                    results_by_condition[cond][provider].append(res)
                    
                if domain_terms:
                    boost_comparison[clip_id][provider]["baseline"] = metrics
                    
            # Boosted
            if domain_terms:
                boost_fix_path = os.path.join(config.FIXTURES_DIR, f"{provider}-boost", f"{clip_id}.json")
                if os.path.exists(boost_fix_path):
                    with open(boost_fix_path, "r") as f:
                        hyp_boost = TranscriptResult(**json.load(f))
                    
                    boost_metrics = calculate_and_dump_diff(ref_text, hyp_boost.text, clip_id, f"{provider}-boost", config.REPORTS_DIR, domain_terms)
                    boost_comparison[clip_id][provider]["boosted"] = boost_metrics

            # Streaming (real-time)
            stream_fix_path = os.path.join(config.FIXTURES_DIR, f"{provider}-stream", f"{clip_id}.json")
            if os.path.exists(stream_fix_path):
                with open(stream_fix_path, "r") as f:
                    sr = StreamResult(**json.load(f))
                stream_metrics = calculate_streaming_metrics(ref_text, sr)
                stream_by_provider[provider].append({"clip_id": clip_id, "metrics": stream_metrics})

            # Translation fidelity (ASR -> DeepL): translate(hyp) vs translate(ref)
            hyp_translation = _load_translation(provider, clip_id, target_lang)
            if ref_translation is not None and hyp_translation is not None:
                fidelity = calculate_translation_fidelity(ref_translation, hyp_translation)
                translation_by_provider[provider].append({"clip_id": clip_id, "metrics": fidelity})

        # Soniox in-band translation, scored per language against the DeepL translation of
        # the verified reference. Sharing one ideal is necessary for these columns to be
        # comparable but not sufficient: an arm that is *itself* DeepL output is scored on
        # resembling its own engine, which is worth ~19 chrF here and has nothing to do
        # with translation quality. Hence the control and two-stage arms below.
        for lang in inband_langs:
            ideal = _load_translation("ref", clip_id, config.deepl_target(lang))
            if ideal is None:
                continue
            for variant, base in (("context", config.INBAND_DIR),
                                  ("nocontext", config.INBAND_NOCONTEXT_DIR),
                                  ("endpointed", config.INBAND_ENDPOINTED_DIR)):
                path = os.path.join(base, lang, f"{clip_id}.json")
                if not os.path.exists(path):
                    continue
                with open(path, "r") as f:
                    stream = StreamResult(**json.load(f))
                inband[lang][variant].append({
                    "clip_id": clip_id,
                    "metrics": calculate_translation_fidelity(ideal, stream.final_text),
                    # Kept for the endpointing comparison, which is about how the caption
                    # behaves on screen rather than which words it lands on.
                    "stream": calculate_streaming_metrics(ref_text, stream),
                    "session_config": stream.session_config,
                    # For COMET, which reads the source to tell a mistranslation from a
                    # rephrasing — the distinction chrF cannot make.
                    "src": ref_text, "hyp": stream.final_text, "ref": ideal,
                })
            # The full-context ceiling: the same model on the same audio, async, so nothing
            # is committed before the sentence ends.
            batch_path = os.path.join(config.INBAND_BATCH_DIR, lang, f"{clip_id}.json")
            if os.path.exists(batch_path):
                with open(batch_path, "r") as f:
                    batch_text = TranscriptResult(**json.load(f)).text
                inband[lang]["batch"].append({
                    "clip_id": clip_id,
                    "metrics": calculate_translation_fidelity(ideal, batch_text),
                    "src": ref_text, "hyp": batch_text, "ref": ideal,
                })

            # The different-engine floor: a second engine translating the *verified
            # reference*. Perfect input, zero ASR error, so everything it scores below 100
            # is the price of not being the engine that wrote the ideal. Every cross-engine
            # arm should be read against this, not against 100.
            control = _load_translation("ref-gemini", clip_id, config.deepl_target(lang))
            if control is not None:
                inband[lang]["control"].append({
                    "clip_id": clip_id,
                    "metrics": calculate_translation_fidelity(ideal, control),
                })

            # The two-stage alternative, scored fairly: a non-DeepL engine translating the
            # Soniox transcript, carrying the same cross-engine handicap as the in-band arm.
            # This is the column to read when deciding whether to add a second vendor.
            two_stage = _load_translation("soniox-gemini", clip_id, config.deepl_target(lang))
            if two_stage is not None:
                inband[lang]["two_stage"].append({
                    "clip_id": clip_id,
                    "metrics": calculate_translation_fidelity(ideal, two_stage),
                    "src": ref_text, "hyp": two_stage, "ref": ideal,
                })

            # Soniox transcript -> DeepL. Retained because it is what a two-stage path on
            # DeepL would actually produce, but it is NOT comparable to the arms above: it
            # is DeepL output scored against a DeepL ideal, so it collects a same-engine
            # bonus none of the others can. Reported separately and never differenced
            # against in-band.
            via_deepl = _load_translation("soniox", clip_id, config.deepl_target(lang))
            if via_deepl is not None:
                inband[lang]["via_deepl_same_engine"].append({
                    "clip_id": clip_id,
                    "metrics": calculate_translation_fidelity(ideal, via_deepl),
                })

    # COMET, if the caller opted in. Scored here in one batched pass per arm rather than
    # per clip inside the loop above: loading the checkpoint is the expensive part, and a
    # semantic metric is only worth its cost when it can be compared across whole arms.
    if comet_enabled():
        for lang, arms in inband.items():
            for arm, runs in arms.items():
                scorable = [r for r in runs if "src" in r]
                if not scorable:
                    continue
                scores = comet_scores([r["src"] for r in scorable],
                                      [r["hyp"] for r in scorable],
                                      [r["ref"] for r in scorable])
                for run, score in zip(scorable, scores or []):
                    run["metrics"]["comet"] = score

    # Generate Markdown
    md = []
    title = "STT Provider Scorecard"
    if allow_unverified:
        title += " (DRAFT - Unverified Refs)"
    md.append(f"# {title}\n")
    
    def avg_metric(res_list, key):
        if not res_list:
            return 0.0
        return sum(r["metrics"].get(key, 0.0) for r in res_list) / len(res_list)
        
    md.append("## Overall Metrics\n")
    md.append("| Provider | WER (Norm) | WER (Fmt) | CER (Norm) | Entity F1 |")
    md.append("|---|---|---|---|---|")
    
    for p in providers:
        runs = results_by_provider.get(p, [])
        if not runs:
            md.append(f"| {p} | n/a (not run) | n/a (not run) | n/a (not run) | n/a |")
            continue
            
        w_norm = avg_metric(runs, "wer_norm")
        w_fmt = avg_metric(runs, "wer_fmt")
        c_norm = avg_metric(runs, "cer_norm")
        
        # calculate avg entity F1 across clips that actually had domain terms
        runs_with_entities = [r for r in runs if "entity_f1" in r["metrics"]]
        if runs_with_entities:
            ent_f1 = sum(r["metrics"]["entity_f1"] for r in runs_with_entities) / len(runs_with_entities)
            ent_str = f"{ent_f1:.3f}"
        else:
            ent_str = "n/a"
            
        md.append(f"| {p} | {w_norm:.3f} | {w_fmt:.3f} | {c_norm:.3f} | {ent_str} |")
        
    md.append("\n## Metrics by Condition\n")
    for cond, p_dict in results_by_condition.items():
        md.append(f"### {cond}\n")
        md.append("| Provider | WER (Norm) |")
        md.append("|---|---|")
        for p in providers:
            runs = p_dict.get(p, [])
            if not runs:
                md.append(f"| {p} | n/a (not run) |")
            else:
                md.append(f"| {p} | {avg_metric(runs, 'wer_norm'):.3f} |")
        md.append("\n")
        
    md.append("## Keyterm Boosting Impact (Clips with Domain Terms)\n")
    md.append("| Clip ID | Provider | Baseline WER | Boosted WER | Baseline Entity F1 | Boosted Entity F1 |")
    md.append("|---|---|---|---|---|---|")
    
    for clip_id, p_dict in boost_comparison.items():
        for p, metrics_dict in p_dict.items():
            base = metrics_dict.get("baseline", {})
            boost = metrics_dict.get("boosted", {})
            if base and boost:
                b_wer = base.get("wer_norm", 0.0)
                bt_wer = boost.get("wer_norm", 0.0)
                b_f1 = base.get("entity_f1", 0.0)
                bt_f1 = boost.get("entity_f1", 0.0)
                md.append(f"| {clip_id} | {p} | {b_wer:.3f} | {bt_wer:.3f} | {b_f1:.3f} | {bt_f1:.3f} |")
    md.append("\n")
        
    # Streaming section (Phase 4)
    if any(stream_by_provider.get(p) for p in providers):
        md.append("## Streaming (Live-Caption Realism)\n")
        md.append("Real-time paced sessions. Flicker = fraction of already-shown characters later "
                  "rewritten (0 = captions only append). Finalization latency is measured against each "
                  "provider's **self-reported** word times (forced-alignment ground truth is out of scope).\n")
        md.append("| Provider | Clips | Streaming WER | Flicker | Final Latency (med) | Final Latency (p95) |")
        md.append("|---|---|---|---|---|---|")
        for p in providers:
            runs = stream_by_provider.get(p, [])
            if not runs:
                md.append(f"| {p} | 0 | n/a (not run) | n/a | n/a | n/a |")
                continue
            swer = avg_metric(runs, "stream_wer")
            flick = avg_metric(runs, "flicker")
            lat_med = avg_metric(runs, "final_latency_med_s")
            lat_p95 = avg_metric(runs, "final_latency_p95_s")
            md.append(f"| {p} | {len(runs)} | {swer:.3f} | {flick:.3f} | {lat_med:.2f}s | {lat_p95:.2f}s |")
        md.append("\n")

    # Translation fidelity section (ASR -> DeepL)
    if any(translation_by_provider.get(p) for p in providers):
        md.append(f"## Translation Fidelity (ASR → DeepL, target = {target_lang})\n")
        md.append("How much ASR error survives machine translation: each provider's transcript is "
                  "translated with DeepL and compared to the translation of the **verified reference** "
                  "(the ideal translation from perfect ASR). chrF is 0–100 (higher = closer to ideal); "
                  "Post-MT WER is on raw target-language text, comparable to the source WER above.\n")
        md.append("| Provider | Clips | chrF vs ideal | Post-MT WER | Source WER (Norm) |")
        md.append("|---|---|---|---|---|")
        for p in providers:
            runs = translation_by_provider.get(p, [])
            if not runs:
                md.append(f"| {p} | 0 | n/a (not run) | n/a | - |")
                continue
            chrf = avg_metric(runs, "trans_chrf")
            twer = avg_metric(runs, "trans_wer")
            # source WER over the same clips that were translated
            translated_ids = {r["clip_id"] for r in runs}
            src_runs = [r for r in results_by_provider.get(p, []) if r["clip_id"] in translated_ids]
            src_wer = avg_metric(src_runs, "wer_norm") if src_runs else 0.0
            md.append(f"| {p} | {len(runs)} | {chrf:.1f} | {twer:.3f} | {src_wer:.3f} |")
        md.append("\n")

    # Soniox in-band translation — the captions the app actually shows.
    if inband:
        md.append("## In-Band Translation Quality (Soniox, per language)\n")
        md.append("What the player actually renders: Soniox translating on the same socket, "
                  "streamed and paced in real time, with the app's own session context "
                  "(domain sentence + glossary, read from `CaptionGlossary.kt`). Every column "
                  "is chrF against DeepL's translation of the verified reference.\n")
        md.append("**Read every number against the floor, not against 100.** The ideal is one "
                  "engine's output, so an arm is scored partly on resembling *that engine* "
                  "rather than on being a good translation. The **floor** column measures "
                  "exactly that: a second engine translating the verified reference — perfect "
                  "input, no ASR error — so whatever it scores below 100 is the price of not "
                  "being DeepL. Measured at ~68 for Hungarian, i.e. a flawless translation "
                  "scores 68, not 100.\n")
        md.append("- **In-band (context)** — what ships today.\n"
                  "- **In-band (no context)** — same audio, context withheld. The gap is what "
                  "the glossary and domain sentence are worth for this language.\n"
                  "- **Batch (full context)** — the same model and context through the async "
                  "API, which reads the whole clip before answering. **Δ streaming** is the "
                  "price of committing a translation before the sentence ends, and it is also "
                  "the ceiling on every latency-buying trick there is: holding the tail, "
                  "re-translating on sentence end, or the vendor's own endpointing knobs. A "
                  "small Δ means no client change will move this language.\n"
                  "- **Two-stage (cross-engine)** — the Soniox transcript translated by a "
                  "*different* engine than the one that wrote the ideal, so it carries the "
                  "same handicap as in-band and the two can be subtracted. **This is the "
                  "column that decides whether a second MT vendor is worth its cost**, and on "
                  "Hungarian it came out level with in-band, not ahead.\n")
        md.append("| Target | In-band (context) | In-band (no context) | Δ context | "
                  "Batch (full context) | Δ streaming | Two-stage (cross-engine) | Δ two-stage | "
                  "Different-engine floor |")
        md.append("|---|---|---|---|---|---|---|---|---|")

        def _by_clip(runs):
            return {r["clip_id"]: r["metrics"]["trans_chrf"] for r in runs}

        def cell(runs):
            """Mean chrF and the number of clips behind it. The n is per-arm because arms
            are recorded independently and a half-finished one must not look complete."""
            if not runs:
                return "n/a"
            return f"{avg_metric(runs, 'trans_chrf'):.1f} (n={len(runs)})"

        def paired_delta(better, worse):
            """Mean per-clip difference over the clips *both* arms recorded.

            Subtracting two means taken over different clip sets compares two different
            samples and calls it an effect — which is how a 21-clip arm once appeared to
            beat a 5-clip one by 2.6 points on clips it had never been run against.
            """
            a, b = _by_clip(better), _by_clip(worse)
            shared = sorted(set(a) & set(b))
            if not shared:
                return "n/a"
            mean = sum(a[c] - b[c] for c in shared) / len(shared)
            return f"{mean:+.1f} (n={len(shared)})"

        for lang in sorted(inband):
            runs = inband[lang]["context"]
            no_ctx = inband[lang]["nocontext"]
            batch = inband[lang]["batch"]
            two_stage = inband[lang]["two_stage"]
            control = inband[lang]["control"]
            if not runs and not no_ctx and not batch:
                continue
            md.append(f"| {lang} | {cell(runs)} | {cell(no_ctx)} | "
                      f"{paired_delta(runs, no_ctx)} | {cell(batch)} | "
                      f"{paired_delta(batch, runs)} | {cell(two_stage)} | "
                      f"{paired_delta(two_stage, runs)} | {cell(control)} |")
        md.append("\nchrF is 0–100, higher is better; it is character-n-gram based, so it does "
                  "not punish a morphologically rich language for inflecting differently than "
                  "the reference the way BLEU would. Absolute values are not comparable across "
                  "languages (a chrF of 55 means different things in Hungarian and Spanish) — "
                  "the comparisons within a row are. Every Δ is a per-clip mean over the clips "
                  "both arms recorded, never a difference of two independently-averaged arms.\n")

        # COMET scores meaning rather than surface form, so it does not have the
        # same-engine bias the control corrects for. Reported when enabled, and explicitly
        # marked absent when not, so nobody reads a chrF-only table as two metrics agreeing.
        if comet_enabled():
            md.append("#### COMET (semantic)\n")
            md.append("A trained metric that reads the English source, so an equally correct "
                      "translation phrased differently is not penalised for the wording. It "
                      "does not carry the same-engine bias the *floor* column corrects for, "
                      "so where COMET and chrF disagree, prefer COMET.\n")
            md.append("| Target | In-band (context) | Batch (full context) | Two-stage (cross-engine) |")
            md.append("|---|---|---|---|")

            def comet_cell(runs):
                vals = [r["metrics"]["comet"] for r in runs if "comet" in r["metrics"]]
                return f"{sum(vals)/len(vals):.3f} (n={len(vals)})" if vals else "n/a"

            for lang in sorted(inband):
                md.append(f"| {lang} | {comet_cell(inband[lang]['context'])} | "
                          f"{comet_cell(inband[lang]['batch'])} | "
                          f"{comet_cell(inband[lang]['two_stage'])} |")
            md.append("")
        else:
            md.append(f"*COMET (semantic scoring): {comet_status()}. chrF alone carries a "
                      "same-engine bias, which is what the floor column is for.*\n")

        # Kept out of the table above on purpose: this arm shares an engine with the ideal.
        same_engine = {lang: inband[lang]["via_deepl_same_engine"] for lang in sorted(inband)
                       if inband[lang]["via_deepl_same_engine"]}
        if same_engine:
            md.append("### Not comparable: Soniox → DeepL\n")
            md.append("DeepL translating the Soniox transcript, scored against DeepL's own "
                      "translation of the reference. Both sides are the same engine and differ "
                      "only by ASR error, so this collects a same-engine bonus that no other "
                      "arm can — measured at **+19.4 chrF** on Hungarian by scoring one "
                      "hypothesis against two different engines' ideals "
                      "(`scripts/calibrate_metric.py`). It is reported because it is a real "
                      "path you could ship, and kept out of the table because differencing it "
                      "against in-band measures engine agreement, not quality. Use the "
                      "**two-stage (cross-engine)** column for that decision instead.\n")
            md.append("| Target | Soniox → DeepL chrF |")
            md.append("|---|---|")
            for lang, runs in same_engine.items():
                md.append(f"| {lang} | {cell(runs)} |")
            md.append("")

        # Endpoint detection, judged on how the caption behaves rather than on its words.
        endpointed = {lang: inband[lang]["endpointed"] for lang in sorted(inband)
                      if inband[lang]["endpointed"]}
        if endpointed:
            md.append("### Endpoint detection (flicker, not quality)\n")
            md.append("The same real-time arm with Soniox's `enable_endpoint_detection` on, so "
                      "it finalizes at utterance boundaries instead of mid-clause. Judged on "
                      "**flicker** — the fraction of already-displayed characters later "
                      "rewritten — because the batch arm above already bounds what any "
                      "latency-buying mechanism can do for the words themselves. Lower flicker "
                      "is a real improvement to what a viewer sees even when chrF does not "
                      "move; higher finalization latency is what it costs.\n")
            md.append("| Target | Flicker (default) | Flicker (endpointed) | Δ flicker | "
                      "Final latency med (default) | Final latency med (endpointed) | chrF Δ |")
            md.append("|---|---|---|---|---|---|---|")

            def _stream_by_clip(runs, key):
                return {r["clip_id"]: r["stream"][key] for r in runs if "stream" in r}

            def _paired_stream(a_runs, b_runs, key, fmt="{:+.3f}"):
                a, b = _stream_by_clip(a_runs, key), _stream_by_clip(b_runs, key)
                shared = sorted(set(a) & set(b))
                if not shared:
                    return "n/a"
                return fmt.format(sum(a[c] - b[c] for c in shared) / len(shared)) + f" (n={len(shared)})"

            def _mean_stream(runs, key, fmt="{:.3f}"):
                vals = [r["stream"][key] for r in runs if "stream" in r]
                return fmt.format(sum(vals) / len(vals)) if vals else "n/a"

            for lang, runs in endpointed.items():
                base = inband[lang]["context"]
                md.append(
                    f"| {lang} | {_mean_stream(base, 'flicker')} | "
                    f"{_mean_stream(runs, 'flicker')} | "
                    f"{_paired_stream(runs, base, 'flicker')} | "
                    f"{_mean_stream(base, 'final_latency_med_s', '{:.2f}s')} | "
                    f"{_mean_stream(runs, 'final_latency_med_s', '{:.2f}s')} | "
                    f"{paired_delta(runs, base)} |")

            # An arm defined by a config field is only evidence if the field was sent and
            # the vendor acted on it. Identical results may mean "no effect" or may mean
            # "silently ignored", and those call for different next steps.
            sent = next((r.get("session_config", {}) for r in next(iter(endpointed.values()))
                         if r.get("session_config")), {})
            knobs = {k: v for k, v in sent.items() if "endpoint" in k}
            md.append(f"\nConfig actually sent: `{knobs or 'none recorded'}`. If the Δ columns "
                      "are all zero, check this is non-empty before reading it as a negative "
                      "result — an ignored field and an ineffective one look identical here.\n")

    md.append("## Worst 5 Clips by Provider (WER Norm)\n")
    for p in providers:
        runs = results_by_provider.get(p, [])
        if not runs:
            continue
        
        runs = sorted(runs, key=lambda x: x["metrics"]["wer_norm"], reverse=True)[:5]
        md.append(f"### {p}\n")
        md.append("| Clip ID | WER (Norm) | Diff |")
        md.append("|---|---|---|")
        for r in runs:
            cid = r["clip_id"]
            w = r["metrics"]["wer_norm"]
            diff_link = f"diffs/{p}_{cid}.txt"
            md.append(f"| {cid} | {w:.3f} | [View Diff]({diff_link}) |")
        md.append("\n")
        
    os.makedirs(config.REPORTS_DIR, exist_ok=True)
    report_path = os.path.join(config.REPORTS_DIR, "scorecard.md")
    with open(report_path, "w") as f:
        f.write("\n".join(md))
    print(f"Scorecard generated at {report_path}")

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--allow-unverified", action="store_true")
    args = parser.parse_args()
    generate_scorecard(args.allow_unverified)
