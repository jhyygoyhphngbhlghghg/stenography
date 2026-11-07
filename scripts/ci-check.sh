#!/usr/bin/env bash
# CI-ish local gate: JSON schema + eval skeleton (+ Gradle if SDK present).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 scripts/verify-transcript-json.py artifacts/test-results/example-transcript.json
python3 scripts/eval_harness.py

if [[ -f src/android/local.properties ]] || [[ -n "${ANDROID_HOME:-}" ]]; then
  (cd src/android && ./gradlew :app:testStenographerDebugUnitTest :app:testPresentConnectDebugUnitTest :app:assembleDebug --quiet)
else
  echo "skip Gradle (no ANDROID_HOME / local.properties)"
fi
echo "ci-check OK"
