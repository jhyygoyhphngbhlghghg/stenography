#!/usr/bin/env python3
"""Regression smoke: Path B PresentConnect row stays measured (no invented numbers).

Scores committed hyp + ref. Does not run inference (that is --run-host).
Fails if mix_20talker_per_stream is missing, schema-invalid, or not measured
with numeric DER/JER/WER_main and 20 hyp speakers.

Usage:
  python3 scripts/test_path_b_regression.py
"""
from __future__ import annotations

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

HYP = ROOT / "artifacts" / "eval" / "demo-stress" / "mix_20talker_per_stream.json"
REF = ROOT / "samples" / "demo-stress" / "ref" / "mix_20talker_per_stream.json"
# Fallback while generator still only has the mix ref.
REF_FALLBACK = ROOT / "samples" / "demo-stress" / "ref" / "mix_20talker.json"


def main() -> int:
    if not HYP.is_file():
        print(f"FAIL Path B hyp missing: {HYP}")
        return 1
    errs = verify_file(HYP)
    if errs:
        print("FAIL Path B JSON schema")
        for e in errs:
            print(" ", e)
        return 1
    ref_path = REF if REF.is_file() else REF_FALLBACK
    if not ref_path.is_file():
        print(f"FAIL Path B ref missing: {REF}")
        return 1
    ref = json.loads(ref_path.read_text(encoding="utf-8"))
    hyp = json.loads(HYP.read_text(encoding="utf-8"))
    diar = M.diarization_scores(ref, hyp)
    w = M.main_speaker_wer(ref, hyp, diar["mapping_hyp_to_ref"], main_id="speaker_1")
    n_hyp = diar["n_hyp_speakers"]
    if diar["der"] is None and diar["der_no_collar"] is None:
        print("FAIL Path B DER empty (not measured)")
        return 1
    if w is None:
        print("FAIL Path B WER_main empty")
        return 1
    if n_hyp != 20:
        print(f"FAIL Path B speakers_hyp={n_hyp} want 20")
        return 1
    der = diar["der"] if diar["der"] is not None else diar["der_no_collar"]
    print(
        f"OK Path B measured DER={der:.4f} JER={diar['jer']:.4f} "
        f"purity={diar['speaker_purity']:.4f} WER_main={w:.4f} speakers=20/20 "
        f"schema=transcript[].{{speaker_id,start_ms,end_ms,text}}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
