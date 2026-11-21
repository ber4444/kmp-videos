# The Metric Was the Bug

### How 19.4 points of engine self-recognition almost bought a second cloud vendor

*Live multi-language captions for a video app, and the evaluation harness that reversed an
architecture decision before it was built.*

---

## The scorecard said: add a second vendor

The product is a Compose Multiplatform video player for a lecture archive, running on
Android, iOS and web from one Kotlin codebase. Live events needed captions, and because the
audience is international, captions in languages other than the spoken one.

The speech engine, Soniox, does both on a single WebSocket. You send 16 kHz PCM, you set
`translation: {type: "one_way", target_language: "hu"}` in the config frame, and translated
tokens come back on the same socket, chunk by chunk, at no extra cost. One vendor, one
connection, no second hop.

The alternative is the architecture most teams reach for, which is to take the transcript
from the ASR vendor and push it through a dedicated machine-translation vendor. Two
specialists, each best in class.

My offline evaluation harness compared them across a 21-clip golden set of real event audio
with human-verified references. This first investigation used Soniox v5; the v6 follow-up
below keeps its results separate. The initial result was not close.

| Arm | chrF |
|---|---:|
| Soniox in-band (single socket) | 54.6 |
| Soniox transcript → DeepL (two-stage) | 74.3 |

**+19.7 chrF for the two-stage path.** In a metric that runs 0 to 100, a gap that size reads
as a mandate rather than a tiebreaker. The work it implied was already scoped, and it was not
small. It meant a DeepL account with per-character billing, another secret to hold and
another minting path through the `:server` module, another sub-processor line in the privacy
policy, and a second network hop inserted into a feature whose entire value proposition is
that it is real-time.

I nearly wrote that ticket. What stopped me was a nagging asymmetry in how the two numbers
were produced.

## Where the answer key came from

Every arm in the harness is scored against one ideal, **DeepL's translation of the verified
human reference**. That is a reasonable choice. You need some fixed target, and a
professional MT engine translating perfect input is a defensible stand-in for "what this
should have said."

Now look at what each arm actually is.

- `in-band` is **Soniox's** translation, scored against **DeepL's** translation. It differs
  from the ideal by ASR error and also by every valid-but-different word a non-DeepL engine
  would choose.
- `two-stage` is **DeepL's** translation, scored against **DeepL's** translation. It differs
  from the ideal by ASR error and nothing else.

chrF is character-n-gram overlap. Two DeepL outputs of near-identical English source text
share phrasing, register, punctuation and comma placement. The second arm was being measured
against its own handwriting.

So the +19.7 was an unknown mixture of *"this translation is better"* and *"this metric
recognises its own output,"* and that unknown mixture was the entire business case for a
second vendor.

## Measuring the ruler

The fix is a control. From
[`eval/scripts/calibrate_metric.py`](../../eval/scripts/calibrate_metric.py):

> Translate the *verified reference*, which is perfect input with no ASR error at all, using
> a second engine, and score that against the DeepL ideal. A flawless translation from the
> wrong engine is the floor the in-band arm should be compared to.

Gemini plays the second engine, deliberately handicapped the same way DeepL was, with no
glossary, no domain context, and nothing DeepL did not also get. Then every hypothesis is
scored against *two* ideals, DeepL's and Gemini's, so the engine axis separates from the
quality axis.

Hungarian, n=21 for the controls and two-stage arms, n=5 for the initial in-band arm
([`eval/reports/metric_calibration.hu.md`](../../eval/reports/metric_calibration.hu.md)):

| Hypothesis | Ideal | chrF | What it is |
|---|---|---:|---|
| Gemini(reference) | DeepL(ref) | **68.1** | perfect input, wrong engine, **the floor** |
| Soniox in-band | DeepL(ref) | 54.6 | what the scorecard reported |
| Soniox in-band | Gemini(ref) | 55.8 | same caption, other ideal |
| DeepL(Soniox ASR) | DeepL(ref) | 74.3 | two-stage, **same engine as the ideal** |
| Gemini(Soniox ASR) | Gemini(ref) | 76.6 | two-stage, **same engine as the ideal** |
| Gemini(Soniox ASR) | DeepL(ref) | 57.2 | two-stage, **cross-engine** |

Read the last two rows together. They are the **same hypothesis text**, Gemini translating
the Soniox transcript, scored against two different answer keys. It scores **76.6** against
the key its own engine wrote and **57.2** against the other one.

That is **19.4 points of pure engine resemblance, against a reported lead of 19.7.** The
reference change alone explains a gap almost as large as the reported advantage.
That made 19.7 a poor basis for buying another vendor.

Two more things fell out of the same table.

**Perfect English input does not make two translation engines agree.** Gemini translating
the verified reference reaches 68.1 against DeepL's Hungarian reference and 66.2 against its
Russian one. Those are agreement scores on this corpus, not human judgments that either
translation is flawless. The full in-band Hungarian result, 54.4, sits 13.7 points below
that control. Neither 68.1 nor 100 is a demonstrated human-quality ceiling.

**The case for a second vendor shrinks.** Comparing like with like means using a
translator that is *not* the engine that wrote the answer key, so both arms carry the same
handicap.

| Target | In-band (ships) | Two-stage (cross-engine) | Δ | Different-engine floor |
|---|---:|---:|---:|---:|
| hu | 54.4 | 57.2 | **+2.8** | 68.1 |
| ru | 53.2 | 58.3 | **+5.1** | 66.2 |

A second vendor, a second bill, a second sub-processor disclosure and an extra hop in the
hot path, for 2.8 chrF on Hungarian. Flipping the framing, so that everything is scored
against the *Gemini* ideal with a non-Gemini translator, collapses the two-stage lead the
same way. Both framings agree, and both reverse the sign of the decision.

The scorecard now carries the floor as a permanent column, with the instruction in bold:
**read every number against the floor, not against 100.** The same-engine arm is still
reported, because it is a real path someone could ship, but it is quarantined under a
heading that says *Not comparable*, so nobody differences it against in-band again.

### The generalisable failure

The underlying problem is one of **reference provenance**, and translation is only where I
happened to run into it. The same shape turns up all over applied AI right now.

- An LLM-as-judge scoring candidates, one of which is from the judge's own model family.
- A RAG eval whose golden answers were generated by the model under test.
- Embedding retrieval scored by cosine similarity in the embedding space of one of the
  candidate encoders.
- Any "we compared five vendors" spreadsheet where the ground truth came from vendor three.

The tell is always the same. **One candidate is structurally more similar to the answer key
than the others, for reasons that have nothing to do with being right.** The remedy is
always the same too, and it is cheap. Run a *control* whose score you can predict. Here, a
flawless translation should have scored 100. It scored 68.1, and the 31.9-point deficit is
the size of the artifact I was about to buy a vendor with.

The calibration run cost roughly one cent.

## Act two: the batch ceiling

With the vendor question closed, the obvious next lever was latency. Real-time translation
commits words before the sentence is finished, and Hungarian is verb-final and heavily
agglutinative, which is exactly the case where committing early should hurt. The instinctive
fixes are all client-side and all expensive.

- buffer 3 to 5 seconds before displaying
- hold the tail until a sentence boundary and re-translate
- turn on the vendor's endpoint detection and finalize on utterance boundaries

Each is a week of work, and each trades a real thing (immediacy) for a hoped-for thing
(quality). So before building any of them, I measured the **ceiling on all of them at
once**.

The same model, the same audio and the same context, through the *batch* API, which reads
the whole clip before answering a single word. Batch has perfect hindsight. No client-side
buffering scheme, no sentence-boundary retranslation and no vendor endpointing knob can beat
a system that already has the entire clip.

| Target | In-band (streaming) | Batch (full clip) | Δ streaming |
|---|---:|---:|---:|
| hu | 54.4 | 56.4 | **+2.1** |
| ru | 53.2 | 55.1 | **+2.0** |

Two points is the total addressable value of every latency-buying mechanism that exists, for
this language, on this material. An entire branch of the roadmap was priced and closed by one
eval arm that cost less to run than the arms it replaced, because batch has no real-time
pacing and so finishes faster than wall clock.

I ran the endpointing arm anyway, and it produced my favourite null result in the harness.
Flicker, the fraction of already-displayed characters later rewritten, moved by **0.000**,
because on a translated session Soniox emits translated tokens only as final. The arm could
not move a number that was already zero. No amount of reading the docs surfaced that fact
about the protocol, and it now sits in the scorecard so the next person does not re-litigate
it.

One guardrail came out of this. The scorecard prints **`Config actually sent:
{'enable_endpoint_detection': True}`** next to the result, with a note that *an ignored field
and an ineffective one look identical here*. That line exists because of a real bug.

## The bug that made a feature look worthless

Earlier in the harness's life, the Soniox keyterm-boosting arm reported no benefit at all.
Boosting looked like snake oil.

The arm turned out to be a commented-out guess at a `speech_context` field the SDK does not
have. The "boosted" run was silently re-running the baseline on the same audio with the same
config, producing the same output, and the scorecard dutifully reported that boosting was
worth nothing.

Wired up correctly, keyterm boosting is worth **−0.037 WER**.

The useful lesson goes past "check your config." **A benchmark can fail silently in the
direction of "no effect," and "no effect" is the most believable result there is.** Nobody
investigates a null. The harness now asserts on the config it actually transmitted, and
prints it in the report.

## Act three: where the points actually were

Having eliminated the vendor and the latency, the harness had one question left. If
translation is not the bottleneck, what is?

The audio, the model and the async path stay fixed, and **only the session `context`
changes** ([`eval/reports/asr_context.md`](../../eval/reports/asr_context.md)):

| What the model is told beforehand | WER | Caption chrF (hu) |
|---|---:|---:|
| nothing | 0.242 | 54.9 |
| the app's 49-term glossary | 0.205 | 57.0 (+2.0) |
| this clip's distinctive vocabulary *(oracle)* | 0.192 | 59.7 (+4.7) |
| this clip's full reference prose *(oracle)* | 0.103 | 63.7 (+8.8) |

The last two rows are not shippable, because their vocabulary is drawn from the reference,
so they are cheating on purpose. They are there to **bound** what any real scheme for
supplying vocabulary in advance could achieve, whether that is slide OCR, a per-lecture term
list or a speaker-supplied glossary. Anything you build tops out around there.

Against a floor of 68.1, the bottom row closes about two-thirds of the gap, where engine
choice, latency and the static glossary were each worth two to five points. Recognition
error is where the caption quality goes, and translation turns out to be a much smaller part
of the story than it looked.

The shape of it is worth noticing. A *word list* buys only a third of the win. The rest
comes from the model knowing the **phrasing**, meaning the register, the sentence shapes and
what kind of talk this is. That is a genuinely useful thing to know before designing the next
iteration.

The shipped context therefore has three sections, all read out of one Kotlin file.

```kotlin
// CaptionGlossary.kt, the app's single source of truth
context.text              // "These recordings are lectures on the Fourth Way…", the register
context.terms             // 49 terms whose spelling a general model cannot guess
context.translation_terms // the accepted rendering per language, not a fresh translation
```

The middle section pins vocabulary a general model has no reason to expect. The third exists
because a term can be transcribed perfectly and still translated wrongly. *Uncreated light*
has a settled rendering in this tradition (`Несотворённый Свет`, `Teremtetlen fény`), and a
literal translation of the two English words does not produce it.

Measured per language, the glossary is worth **+2.5 chrF on Hungarian and +0.6 on Russian.**
Reporting the second number is as much the point as reporting the first.

## The harness itself is the deliverable

None of the above is possible without an eval you can afford to run repeatedly and trust when
it disagrees with you. Five properties made the difference.

**1. Record/replay by default.** Scoring, tests and CI read only from `fixtures/` and never
spend a cent. Recording scripts alone hit provider APIs, and they cache each successful response as a
fixture. Dry runs and scoring remain offline. Rerunning the entire scorecard is free, which is why arms accumulate instead of
being deleted when they get inconvenient.

**2. A hard spending cap.** 90 minutes of billed audio per invocation unless you pass
`--force`. Streaming arms are real-time paced, so wall-clock time approximates billed audio,
and the cap is enforced in code rather than in a wiki page about being careful.

**3. An honesty convention.** *Only report numbers from runs that actually executed.* A step
that could not run prints `n/a (not run)`, never an estimate. Anything aspirational in the
docs gets exactly one explicit "not wired yet" sentence. The finalization-latency figures say
out loud that they are measured against the providers' *self-reported* word timestamps,
because forced alignment is not built.

**4. The eval reads the app's source.** [`eval/app_context.py`](../../eval/app_context.py)
parses `DOMAIN`, `TERMS` and the per-language maps **directly out of `CaptionGlossary.kt`**
rather than restating them in Python. A copy would drift the first time a term was added on
one side, and then the harness would be scoring a configuration nothing ships, which is the
one way a benchmark can be worse than no benchmark. The parser raises on a rename, so
refactoring the Kotlin breaks the eval loudly instead of quietly scoring an empty context.

**5. Paired deltas, never differences of means.** Every Δ is a per-clip mean over the clips
*both* arms recorded. Averaging two arms over different clip sets and subtracting reports a
difference between samples as if it were an effect.

## What it changed in the product

The measurement did not stay in a report.

- **No second MT vendor.** No DeepL contract, no per-character bill, no extra sub-processor,
  no extra hop.
- **No latency-buying client work.** Two points was not worth a week, and now nobody has to
  argue about it from intuition.
- **Deepgram switched off entirely.** 0.350 against 0.242 normalized WER and 0.49 against
  0.77 Entity F1, so it missed the domain vocabulary roughly twice as often, and its
  streaming API has no translation, so it could only ever caption in English. Two providers
  behind one provider-agnostic interface meant switching it off was a config change, and the
  interface is still there for the next contender.
- **The investment moved to context**, which is where the harness said the points were.

The caption UI accepts a separate trade-off. **The chosen language persists across videos
and app launches**, because making viewers re-pick it before every video was too much
friction. Cloud ASR is still metered per minute of audio: remembering the choice means later
videos can start a billable caption session without another confirmation. Persistence makes
that cost easier to incur; it does not remove it.

Before any choice has been saved, captions start in the device's language if it is a
supported translation target. English and unsupported device languages start off. An explicit
**"No translation"** is stored as its own choice, distinct from an absent preference, so
turning captions off keeps them off on later videos and launches. While captions are on,
the language menu becomes a single turn-off button labelled in the caption language;
Close, Play and Pause use that language too.

## Rechecking the decision with Soniox v6

Vendor releases expire benchmark conclusions. On November 20–21, 2026 I ran the same
21 verified clips through `stt-async-v6` and `stt-rt-v6`, keeping the old fixtures separate.
The [recorded refresh results](../../eval/VENDOR_REFRESH_RESULTS_2026.md) are mixed:

| Metric | Historical v5 | v6 |
|---|---:|---:|
| Mean per-clip normalized WER | 0.242 | 0.268 |
| Corpus normalized WER | 0.159 | 0.184 |
| Entity F1, 12 clips with terms | 0.770 | 0.680 |
| Streaming WER, 21 clips | 0.309 | 0.293 |
| Flicker | 0.228 | 0.237 |
| Mean per-clip median / p95 finalization | 5.77 / 6.78 s | 6.09 / 7.14 s |

V6 improves the streamed final transcript on this set, but worsens batch recognition,
Entity F1, flicker and finalization time. The two WER aggregations matter:
the scorecard averages clips equally, while corpus WER weights their reference-word counts.
Both move against v6 in the batch arm. These are recognition-only arms, without the full
shipped caption context, and their latency uses provider timestamps rather than alignment.
The legacy batch fixtures also lack a stored model identifier; their v5 attribution comes
from the recording code, while streaming fixtures explicitly name their model.

I also recorded whole-file v6 translation with and without the **merged app context**:
the 284-character domain description, 49 terms, and target-language mappings read from
Kotlin. Each cell below covers all 21 clips. DeepL and Gemini's existing reference
translations are reused, so neither reference engine was called again.

| Target | No context, DeepL ideal | App context, DeepL ideal | No context, Gemini ideal | App context, Gemini ideal |
|---|---:|---:|---:|---:|
| Hungarian | 52.5 | 54.1 | 54.8 | 56.4 |
| Russian | 53.9 | 54.6 | 57.9 | 59.8 |

Context helps under both answer keys, with a smaller Hungarian gain than the old oracle
experiment might tempt someone to promise. These are **async file translations**, not new
measurements of the real-time 54.4/53.2 rows above. Historical async v5 scored 56.4/55.1
against DeepL, but did not preserve its request context, so subtracting those values from
this table would mix a model change with an unknown configuration change. The v5 oracle
and two-stage hypotheses were not rerun; their numbers remain historical.

The on-device follow-up changes a different premise. On a **Pixel 7a, not the original
Fold3**, Moonshine Medium Streaming and Cactus Whistle both run faster than real time in
standalone native benchmarks. Their mean per-clip WERs, 0.549 and 0.643, remain much worse
than Soniox v6's 0.268 here. [The companion article](the-audio-tap-that-never-fires.md)
records their throughput and streaming smoke results. Speed alone no longer rules out
on-device recognition on that phone; recognition quality and translated captions still
need their own evidence.

## Limits

- **n=21 clips, one domain, one speaker community.** These are domain-specific results; neither their direction nor magnitude is a general
  provider ranking.
- **The calibration's in-band rows are n=5**, recorded before the full in-band sweep, so the
  n=21 figures in the scorecard (54.4 hu) are the ones to quote. They agree, but the
  calibration table is the smaller sample.
- **chrF carries a same-engine bias by construction.** That is the whole subject of this
  article, and the floor column mitigates it without curing it. COMET, a learned metric
  without that bias, is wired in and opt-in behind `EVAL_COMET=1`, kept off by default
  because it pulls torch and a 2.3 GB checkpoint.
- **Absolute chrF is not comparable across languages.** 55 means different things in
  Hungarian and Spanish. Only comparisons *within* a row are meaningful.
- **Finalization latency uses provider-reported word timestamps** rather than forced
  alignment. It is good enough to compare providers against each other and not good enough
  to quote as an absolute.

## The checklist

If you are about to approve an architecture on the strength of a benchmark:

1. **Where did the reference come from, and does any candidate share ancestry with it?** If
   it does, that candidate's lead is not yet a number.
2. **Run a control whose score you can predict.** A perfect input should score perfectly. If
   it scores 68, you have found the size of your artifact.
3. **Measure the ceiling before you build the mechanism.** A batch arm bounds every
   latency-buying scheme you could write, and it is cheaper than any of them.
4. **Assert on the config you actually sent.** A silently-ignored field and an ineffective
   feature produce identical results, and one of them is a bug you will never investigate.
5. **Make the eval read the app's real configuration** rather than a copy of it.
6. **Report the null results.** The +0.6 on Russian, the 0.000 flicker change and the boost
   that turned out to be a no-op are what stop the same idea being proposed again next
   quarter.
7. **Make rerunning free.** An eval you cannot afford to rerun is an eval you will
   rationalise around.

The most valuable artifact in this whole effort is a 200-line Python script that measures how
much the scoring function likes its own handwriting. It cost about a cent to run and it
deleted a vendor from the architecture.

---

*Source: [`eval/`](../../eval) for the harness and local historical reports; the
[2026 refresh report](../../eval/VENDOR_REFRESH_RESULTS_2026.md) for new aggregate
measurements. The original ADR is not archived in this checkout. Caption preferences and
controls follow the final merge of [PR #82](https://github.com/ber4444/kmp-videos/pull/82).*

[GitHub](https://github.com/ber4444/kmp-videos) · [Google Play](https://play.google.com/store/apps/details?id=com.livingpresence.inner.circle.squared) · [Apple TestFlight](https://testflight.apple.com/join/YBHu9W7V)
