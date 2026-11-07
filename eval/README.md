# Eval harness (A-12 skeleton)

Team harness for **WER** and **RTF** on exactly three clip slots. This tree is a skeleton: layout, empty gold stubs, and a metrics CLI. It does **not** contain judge audio, judge references, or measured scores.

Official weights (documentation only — this harness does not compute a weighted total): ASR WER 40 · diarization 25 · timestamps 10 · speed 10 · UX 10 · defense 5.

Gate checklist: `eval/GATE.md`.

## Slots

`eval/manifest.json` lists `clip_01`, `clip_02`, `clip_03`.

| Piece | Where | In git? |
|---|---|---|
| How to drop files | `eval/clips/README.md` | yes |
| Audio | `eval/clips/clip_XX.wav` (or other local extension) | no |
| Gold transcript | `eval/gold/clip_XX.gold.json` | yes, **empty** `transcript` |
| Hypothesis | path you pass on the CLI (app export) | no |

The committed gold files match `docs/JSON.md` and contain `"transcript": []`. They are placeholders. They are not «котики» gold.

Own RU multi-speaker files for a dry run live under `samples/` (see `samples/README.md`). Do not call those the judge set.

## Text normalization (WER)

WER here is **speaker-blind word error rate** on the concatenated utterance strings. It is not a diarization score and not a timestamp score.

1. Read `transcript` in array order. Do not re-sort.
2. Join each `text` with a single space.
3. Unicode casefold (`str.casefold`), which lowercases Cyrillic, including `Ё` → `ё`.
4. Replace every character whose Unicode general category starts with `P` (punctuation) with a space. Hyphens are punctuation, so `как-нибудь` becomes two tokens. Letters and digits stay. No stemming and no number rewriting.
5. Collapse whitespace and strip the ends.
6. Split on spaces. Those tokens are the words.
7. WER = word Levenshtein distance / reference word count = `(S + D + I) / N`.
8. If `N = 0` (empty reference after normalization), WER is **undefined**. The CLI writes `pending`. It does not write `0`.

## RTF

`RTF = wall_ms / duration_ms` when both values are passed and `duration_ms > 0`.

If either value is omitted, RTF is `pending`. The CLI does not guess a duration or a wall time. Zero or negative `duration_ms` is an error, not a score.

## Run one clip

After you have a hypothesis export, a non-empty gold file, and **measured** `duration_ms` and `wall_ms`:

```bash
python3 -m eval.metrics.wer_rtf \
  --hyp path/to/hypothesis.json \
  --gold eval/gold/clip_01.gold.json \
  --duration-ms <measured> \
  --wall-ms <measured> \
  --clip-id clip_01 \
  --test-id <your-id> \
  --commit <git-sha> \
  --file-name clip_01.wav \
  --notes "device or host run"
```

Same entry via `./scripts/eval-wer.sh` with the same flags.

The process prints one CSV row on stdout (header + row). Pass `--out path.csv` to append that row. Do **not** point `--out` at `artifacts/test-results/results.csv` until the inputs are a real run. Do not type WER or RTF by hand.

Blank metadata flags stay blank. Unmeasured `wer` and `rtf` are the word `pending`, not a number.

`status` says where the row came from, and the CLI refuses to write a
measured claim without the inputs behind it:

| status | means | written by |
|---|---|---|
| `pending` | neither `wer` nor `rtf` could be computed | derived |
| `partial` | one of them is still pending (usually no `--duration-ms`) | derived |
| `measured-host` | both computed from a host run | derived or `--status` |
| `measured-host-synthetic` | host run on our own synthetic mix, **not** a judge clip | `--status` |
| `measured-on-device` | the installed APK on the demo phone | `--on-device` |

`--on-device` or `--status measured-*` with a pending number exits non-zero
with `claims a real run` — that is the anti-fabrication gate, tested in
`eval/metrics/test_wer.py`.

Columns match `artifacts/test-results/results.csv`:

`test_id,clip_id,timestamp_utc,commit,file_name,duration_ms,wall_ms,rtf,wer,speakers_hyp,speakers_ref,status,notes`

`speakers_hyp` and `speakers_ref` are counts of distinct `speaker_id` values in each file. They are counts, not a diarization score.

## Dry run (toy strings, not a clip score)

`eval/fixtures/` holds two tiny JSON files so the WER function can be checked without audio. The millisecond fields there are schema scaffolding, not timings. The printed WER is the word-error of those strings. It is **not** a production or judge number. Do not copy it into `results.csv`.

```bash
python3 -m eval.metrics.wer_rtf \
  --hyp eval/fixtures/toy_hyp.json \
  --gold eval/fixtures/toy_gold.json \
  --clip-id toy \
  --notes "toy fixture; not a measured clip"
```

Unit tests (hand-checked strings, not speech):

```bash
python3 -m unittest eval.metrics.test_wer
```

No third-party packages. WER is implemented in the standard library (no `jiwer`).

## This is not the Hex.Team scoring normalize

Two WER definitions live in this repo on purpose, and they are not
interchangeable:

| | what it does | where it is cited |
|---|---|---|
| `eval/metrics/wer_rtf.py` (this harness) | speaker-blind, casefold, **punctuation removed** and used only as a separator | `artifacts/test-results/results.csv`, the A-12 gate |
| `scripts/eval_metrics.py` (eval track) | Hex.Team lock: **keeps punctuation**, folds `ё`→`е`, scores per speaker with a Hungarian mapping | every `DEMO_STAGE6` row in `docs/TEST_RESULTS.md` |

Mikhail Grigorev's scoring rule (Hex.Team expert Q&A, 2026-11-06) is that
punctuation counts inside transcription accuracy, commas follow the speaker's
intonation rather than textbook orthography, and `ё`/`е` is free. So the number
a judge will reproduce comes from `scripts/eval_metrics.py`, and the aggregate
cited for the demo claim is **Hex keep-punct WER 0.7794 → 0.6471** after the
K≤6 diar cap — never a number from this harness.

Use this harness for the three-clip A-12 gate (it needs no numpy) and for
checking the app's own export round-trip. Do not paste its `wer` into a
DEMO_STAGE6 sentence.
