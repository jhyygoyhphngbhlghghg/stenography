#!/usr/bin/env python3
"""Frozen Path B regression gate (no invented numbers, no fixture retune).

Scores committed hyp + ref. Does not run inference (that is --run-host).

Frozen ids (docs/GATE.md, H1 accepted WORST_CASE @ 9908353):
  mix_20talker_per_stream, EDGE_reverb, EDGE_overlap, EDGE_noise,
  EDGE_badmic, WORST_CASE.

Lock: 20/20 stream tracking holds under edge; do NOT claim clean WER/DER
on WORST_CASE. This smoke requires measured numeric DER/JER/WER_main and
20 hyp speakers on every frozen row. It does NOT require low WER/DER.

Usage:
  python3 scripts/test_path_b_regression.py
"""
from __future__ import annotations

import csv
import json
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import importlib.util

import eval_metrics as M  # noqa: E402

_spec = importlib.util.spec_from_file_location(
    "verify_transcript_json", SCRIPT_DIR / "verify-transcript-json.py"
)
_mod = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(_mod)
verify_file = _mod.verify_file

try:
    from eval_demo_stress import FROZEN_PATH_B_GATE
except Exception:
    FROZEN_PATH_B_GATE = (
        "mix_20talker_per_stream",
        "EDGE_reverb",
        "EDGE_overlap",
        "EDGE_noise",
        "EDGE_badmic",
        "WORST_CASE",
    )

HYP_DIR = ROOT / "artifacts" / "eval" / "demo-stress"
METRICS = HYP_DIR / "metrics.csv"
REFS = {
    "mix_20talker_per_stream": ROOT / "samples" / "demo-stress" / "ref" / "mix_20talker_per_stream.json",
    "EDGE_reverb": ROOT / "samples" / "demo-stress" / "adversarial" / "ref" / "EDGE_reverb.json",
    "EDGE_overlap": ROOT / "samples" / "demo-stress" / "adversarial" / "ref" / "EDGE_overlap.json",
    "EDGE_noise": ROOT / "samples" / "demo-stress" / "adversarial" / "ref" / "EDGE_noise.json",
    "EDGE_badmic": ROOT / "samples" / "demo-stress" / "adversarial" / "ref" / "EDGE_badmic.json",
    "WORST_CASE": ROOT / "samples" / "demo-stress" / "adversarial" / "ref" / "WORST_CASE.json",
}


def _check_row(test_id: str) -> str:
    hyp_path = HYP_DIR / f"{test_id}.json"
    ref_path = REFS[test_id]
    if not hyp_path.is_file():
        return f"FAIL {test_id} hyp missing: {hyp_path}"
    errs = verify_file(hyp_path)
    if errs:
        return f"FAIL {test_id} JSON schema: {errs[0]}"
    if not ref_path.is_file():
        return f"FAIL {test_id} ref missing: {ref_path}"
    ref = json.loads(ref_path.read_text(encoding="utf-8"))
    hyp = json.loads(hyp_path.read_text(encoding="utf-8"))
    diar = M.diarization_scores(ref, hyp)
    w = M.main_speaker_wer(ref, hyp, diar["mapping_hyp_to_ref"], main_id="speaker_1")
    n_hyp = diar["n_hyp_speakers"]
    if diar["der"] is None and diar["der_no_collar"] is None:
        return f"FAIL {test_id} DER empty (not measured)"
    if w is None:
        return f"FAIL {test_id} WER_main empty"
    if n_hyp != 20:
        return f"FAIL {test_id} speakers_hyp={n_hyp} want 20"
    der = diar["der"] if diar["der"] is not None else diar["der_no_collar"]
    print(
        f"OK {test_id} DER={der:.4f} JER={diar['jer']:.4f} "
        f"WER_main={w:.4f} speakers=20/20"
    )
    return ""


def _check_metrics_csv() -> str:
    if not METRICS.is_file():
        return f"FAIL metrics.csv missing: {METRICS}"
    with METRICS.open(encoding="utf-8", newline="") as f:
        rows = {r["test_id"]: r for r in csv.DictReader(f)}
    missing = [t for t in FROZEN_PATH_B_GATE if t not in rows]
    if missing:
        return f"FAIL metrics.csv missing frozen rows: {missing}"
    for t in FROZEN_PATH_B_GATE:
        r = rows[t]
        if r.get("status") != "measured":
            return f"FAIL {t} status={r.get('status')!r} want measured"
        if r.get("speakers_hyp") != "20" or r.get("speakers_ref") != "20":
            return f"FAIL {t} speakers {r.get('speakers_hyp')}/{r.get('speakers_ref')} want 20/20"
    return ""


def main() -> int:
    failed = False
    err = _check_metrics_csv()
    if err:
        print(err)
        failed = True
    for test_id in FROZEN_PATH_B_GATE:
        msg = _check_row(test_id)
        if msg:
            print(msg)
            failed = True
    if failed:
        return 1
    print(
        "OK frozen Path B gate: 20/20 stream tracking holds under edge; "
        "do NOT claim clean WER/DER on WORST_CASE"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
