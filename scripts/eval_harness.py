#!/usr/bin/env python3
"""Eval skeleton: 3 fixed clips, WER + RTF.

Does NOT invent numbers. If a wav/reference/hypothesis is missing, the row
stays pending. Fill artifacts/test-results/results.csv only after a real run.

Usage:
  python3 scripts/eval_harness.py
  python3 scripts/eval_harness.py --hyp-dir artifacts/eval/hyp

Hypothesis files (optional): artifacts/eval/hyp/clip_01.json matching brief schema.
Wall time (optional): artifacts/eval/hyp/clip_01.wall_ms  (integer milliseconds).
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "samples" / "manifest.json"
RESULTS = ROOT / "artifacts" / "test-results" / "results.csv"


def tokenize(text: str) -> list[str]:
    t = text.lower().replace("ё", "е")
    for ch in ".,!?;:«»\"'()[]":
        t = t.replace(ch, " ")
    return [w for w in t.split() if w]


def levenshtein(a: list[str], b: list[str]) -> int:
    if not a:
        return len(b)
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            ins, delete, sub = cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + (ca != cb)
            cur.append(min(ins, delete, sub))
        prev = cur
    return prev[-1]


def wer(ref: str, hyp: str) -> float | None:
    r = tokenize(ref)
    if not r:
        return None
    return levenshtein(r, tokenize(hyp)) / len(r)


def utterance_text(doc: dict) -> str:
    rows = doc.get("transcript") or []
    return " ".join(str(u.get("text", "")) for u in rows)


def wav_duration_ms(path: Path) -> int | None:
    # Minimal PCM WAV header parse; returns None for non-PCM / missing.
    try:
        data = path.read_bytes()
    except OSError:
        return None
    if len(data) < 44 or data[0:4] != b"RIFF" or data[8:12] != b"WAVE":
        return None
    pos = 12
    sample_rate = bits = channels = None
    data_size = None
    while pos + 8 <= len(data):
        cid = data[pos:pos + 4]
        size = int.from_bytes(data[pos + 4:pos + 8], "little")
        body = pos + 8
        if cid == b"fmt ":
            channels = int.from_bytes(data[body + 2:body + 4], "little")
            sample_rate = int.from_bytes(data[body + 4:body + 8], "little")
            bits = int.from_bytes(data[body + 14:body + 16], "little")
        elif cid == b"data":
            data_size = size
            break
        pos = body + size + (size & 1)
    if not all((sample_rate, bits, channels, data_size)):
        return None
    bytes_per_sec = sample_rate * channels * (bits // 8)
    if not bytes_per_sec:
        return None
    return int(data_size * 1000 / bytes_per_sec)


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--hyp-dir", type=Path, default=ROOT / "artifacts" / "eval" / "hyp")
    p.add_argument("--write-csv", action="store_true", help="overwrite results.csv (only with real numbers)")
    args = p.parse_args()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    rows = []
    print("test_id\tstatus\twer\trtf\tnotes")
    for clip in manifest["clips"]:
        test_id = clip["test_id"]
        wav = ROOT / clip["file"]
        ref_path = ROOT / clip["reference_json"]
        hyp_path = args.hyp_dir / f"{test_id}.json"
        wall_path = args.hyp_dir / f"{test_id}.wall_ms"
        status = "pending"
        wer_s = ""
        rtf_s = ""
        notes = []
        if not wav.is_file():
            notes.append("wav missing")
        if not ref_path.is_file():
            notes.append("reference missing")
        if not hyp_path.is_file():
            notes.append("hypothesis missing")
        if wav.is_file() and ref_path.is_file() and hyp_path.is_file():
            ref = json.loads(ref_path.read_text(encoding="utf-8"))
            hyp = json.loads(hyp_path.read_text(encoding="utf-8"))
            w = wer(utterance_text(ref), utterance_text(hyp))
            if w is not None:
                wer_s = f"{w:.4f}"
                status = "measured"
            dur = wav_duration_ms(wav)
            wall = None
            if wall_path.is_file():
                wall = int(wall_path.read_text().strip())
            if dur and wall is not None and dur > 0:
                rtf_s = f"{wall / dur:.4f}"
            else:
                notes.append("RTF needs duration + wall_ms")
        note = "; ".join(notes) if notes else clip.get("notes", "")
        print(f"{test_id}\t{status}\t{wer_s or '-'}\t{rtf_s or '-'}\t{note}")
        rows.append(
            {
                "test_id": test_id,
                "timestamp_utc": "",
                "commit": "",
                "file_name": clip["file"],
                "duration_ms": "",
                "wall_ms": "",
                "rtf": rtf_s,
                "speakers_hyp": "",
                "notes": note or "pending real on-device run",
            }
        )
    if args.write_csv:
        # Only write measured rows' metrics; pending stays empty numeric cells.
        with RESULTS.open("w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(
                f,
                fieldnames=[
                    "test_id",
                    "timestamp_utc",
                    "commit",
                    "file_name",
                    "duration_ms",
                    "wall_ms",
                    "rtf",
                    "speakers_hyp",
                    "notes",
                ],
            )
            w.writeheader()
            for r in rows:
                w.writerow(r)
        print(f"wrote {RESULTS} (empty metrics unless hyp+ref+wav existed)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
