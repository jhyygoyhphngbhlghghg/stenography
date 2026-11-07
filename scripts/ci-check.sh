#!/usr/bin/env bash
# CI-ish local gate: JSON schema + eval skeleton (+ Gradle if SDK present).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Documentation rot first: a dead path in README.md is what a reviewer sees.
python3 scripts/check-doc-links.py --all

python3 scripts/verify-transcript-json.py artifacts/test-results/example-transcript.json
if [[ -f artifacts/eval/demo-stress/golden_path_b_brief.json ]]; then
  python3 scripts/verify-transcript-json.py artifacts/eval/demo-stress/golden_path_b_brief.json
fi
if [[ -f artifacts/test-results/golden_path_b_brief.json ]]; then
  python3 scripts/verify-transcript-json.py artifacts/test-results/golden_path_b_brief.json
fi
python3 scripts/eval_harness.py
python3 scripts/test_eval_metrics.py -q
python3 scripts/test_stream_pcm_gate.py -q
python3 scripts/test_farfield_gain.py -q
python3 scripts/eval_harness.py --suite demo-stress
if [[ -f samples/demo-stress/ref/smoke.json ]]; then
  python3 scripts/verify-transcript-json.py samples/demo-stress/ref/smoke.json
fi
if [[ -f samples/ref/clip_01.json ]]; then
  python3 scripts/verify-transcript-json.py samples/ref/clip_01.json samples/ref/clip_02.json samples/ref/clip_03.json
fi
if [[ -f artifacts/eval/demo-stress/hyp.json ]]; then
  python3 scripts/verify-transcript-json.py artifacts/eval/demo-stress/hyp.json
fi
if [[ -f artifacts/eval/demo-stress/mix_20talker_per_stream.json ]]; then
  python3 scripts/verify-transcript-json.py artifacts/eval/demo-stress/mix_20talker_per_stream.json
  python3 scripts/test_path_b_regression.py
fi
if [[ -f samples/demo-stress/adversarial/ref/WORST_CASE.json ]]; then
  python3 scripts/verify-transcript-json.py samples/demo-stress/adversarial/ref/EDGE_reverb.json \
    samples/demo-stress/adversarial/ref/EDGE_overlap.json \
    samples/demo-stress/adversarial/ref/EDGE_noise.json \
    samples/demo-stress/adversarial/ref/EDGE_badmic.json \
    samples/demo-stress/adversarial/ref/WORST_CASE.json \
    samples/demo-stress/adversarial/ref/smoke_worst.json
fi
if [[ -f artifacts/eval/demo-stress/WORST_CASE.json ]]; then
  python3 scripts/verify-transcript-json.py artifacts/eval/demo-stress/EDGE_reverb.json \
    artifacts/eval/demo-stress/EDGE_overlap.json \
    artifacts/eval/demo-stress/EDGE_noise.json \
    artifacts/eval/demo-stress/EDGE_badmic.json \
    artifacts/eval/demo-stress/WORST_CASE.json
fi
if [[ -f artifacts/eval/demo-stress/WORST_CASE_gated.json ]]; then
  python3 scripts/verify-transcript-json.py artifacts/eval/demo-stress/EDGE_noise_gated.json \
    artifacts/eval/demo-stress/EDGE_badmic_gated.json \
    artifacts/eval/demo-stress/WORST_CASE_gated.json
fi
if [[ -f artifacts/eval/demo-stress/WORST_CASE_gated_b142a4d.json ]]; then
  python3 scripts/verify-transcript-json.py artifacts/eval/demo-stress/EDGE_noise_gated_b142a4d.json \
    artifacts/eval/demo-stress/EDGE_badmic_gated_b142a4d.json \
    artifacts/eval/demo-stress/WORST_CASE_gated_b142a4d.json
fi
if [[ -f artifacts/eval/demo-stress/WORST_CASE_gated_v2.json ]]; then
  python3 scripts/verify-transcript-json.py artifacts/eval/demo-stress/EDGE_noise_gated_v2.json \
    artifacts/eval/demo-stress/EDGE_badmic_gated_v2.json \
    artifacts/eval/demo-stress/WORST_CASE_gated_v2.json
fi
if [[ -f artifacts/eval/demo-stress/WORST_CASE_gated_v3.json ]]; then
  python3 scripts/verify-transcript-json.py artifacts/eval/demo-stress/EDGE_noise_gated_v3.json \
    artifacts/eval/demo-stress/EDGE_badmic_gated_v3.json \
    artifacts/eval/demo-stress/WORST_CASE_gated_v3.json
fi
if [[ -f samples/demo-stress/stage6/ref/DEMO_STAGE6.json ]]; then
  python3 scripts/verify-transcript-json.py samples/demo-stress/stage6/ref/DEMO_STAGE6.json
fi
if [[ -f artifacts/eval/demo-stress/DEMO_STAGE6.json ]]; then
  python3 scripts/verify-transcript-json.py artifacts/eval/demo-stress/DEMO_STAGE6.json
fi
if [[ -f artifacts/eval/demo-stress/DEMO_STAGE6_ffgain.json ]]; then
  python3 scripts/verify-transcript-json.py artifacts/eval/demo-stress/DEMO_STAGE6_ffgain.json
fi
if [[ -f artifacts/eval/demo-stress/DEMO_STAGE6_perturn.json ]]; then
  python3 scripts/verify-transcript-json.py artifacts/eval/demo-stress/DEMO_STAGE6_perturn.json
fi

if [[ -f src/android/local.properties ]] || [[ -n "${ANDROID_HOME:-}" ]]; then
  (cd src/android && ./gradlew :app:testStenographerDebugUnitTest :app:testPresentConnectDebugUnitTest :app:assembleDebug --quiet)
else
  echo "skip Gradle (no ANDROID_HOME / local.properties)"
fi
# The demo-stress harness above re-scores committed hypotheses and rewrites
# artifacts/eval/demo-stress/metrics.{csv,json} + run.log with *this* machine's
# paths and the current commit. That is a re-score, not a new measurement, so
# those rows must not be committed as evidence — say so instead of leaving a
# mystery dirty tree.
if [[ -n "$(git status --porcelain -- artifacts/eval 2>/dev/null)" ]]; then
  echo "note: harness re-scored artifacts/eval (paths/commit stamp only) — restore with:"
  echo "      git restore artifacts/eval"
fi

echo "ci-check OK"
