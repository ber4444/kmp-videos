# Translated-token timing correction (offline archive rescore)

All 3,125 Hungarian default-arm translated final tokens (n=21 clips) and all
709 endpointed-arm translated final tokens (n=5 clips) stored zero starts.
The recorder supplied that zero when Soniox omitted `start_ms`; the old latency
therefore measured time since stream opening, not time since speech.

Untimed final words now have `start_s=null`; latency excludes them and is `n/a`
when no timed words remain. Legacy unversioned all-zero Soniox translation
fixtures are normalized on replay without overwriting the archived files.
A real zero start in a versioned recording remains a valid audio timestamp.

| Target | Flicker default | Flicker endpointed | Paired flicker delta | Final latency default | Final latency endpointed | Paired chrF delta |
|---|---:|---:|---:|---|---|---:|
| hu | 0.000 (n=21) | 0.000 (n=5) | +0.000 (n=5) | n/a (n=0 timed clips) | n/a (n=0 timed clips) | -0.1 (n=5) |

Offline `scoring/scorecard.py` runs before and after the correction produced
identical preceding scorecard tables after removing the newly added timed-clip
counts from transcription latency cells. Accuracy, flicker and chrF values are
unchanged; translated latency is now unavailable. The vendor-refresh summarizer
also handles partial or absent timing and prints timed-clip counts.
Translation lag is n/a (not run): the retained translated-caption events do not
map each translated final to the last source final it covers.
