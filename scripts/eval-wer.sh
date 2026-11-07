#!/usr/bin/env bash
# Print one WER/RTF CSV row. See eval/README.md.
# Omits nothing into a fake score: missing duration or wall time stays pending.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 -m eval.metrics.wer_rtf "$@"
