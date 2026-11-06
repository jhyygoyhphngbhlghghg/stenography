#!/usr/bin/env bash
# CI-ish local gate: JSON schema + eval skeleton (+ Gradle if SDK present).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 scripts/verify-transcript-json.py artifacts/test-results/example-transcript.json
python3 scripts/eval_harness.py
python3 scripts/test_eval_metrics.py -q
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

if [[ -f src/android/local.properties ]] || [[ -n "${ANDROID_HOME:-}" ]]; then
  (cd src/android && ./gradlew :app:testStenographerDebugUnitTest :app:assembleStenographerDebug --quiet)
else
  echo "skip Gradle (no ANDROID_HOME / local.properties)"
fi
echo "ci-check OK"
