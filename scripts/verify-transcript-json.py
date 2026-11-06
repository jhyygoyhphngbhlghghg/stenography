#!/usr/bin/env python3
"""Verify Hex.Team brief JSON: exactly transcript[].{speaker_id,start_ms,end_ms,text}."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ALLOWED_ROOT = {"transcript"}
ALLOWED_ITEM = {"speaker_id", "start_ms", "end_ms", "text"}


def errors_for(obj, path: str = "$") -> list[str]:
    err: list[str] = []
    if not isinstance(obj, dict):
        return [f"{path}: root must be an object"]
    extra = set(obj) - ALLOWED_ROOT
    missing = ALLOWED_ROOT - set(obj)
    if extra:
        err.append(f"{path}: extra keys {sorted(extra)}")
    if missing:
        err.append(f"{path}: missing keys {sorted(missing)}")
    rows = obj.get("transcript")
    if "transcript" in obj and not isinstance(rows, list):
        err.append(f"{path}.transcript: must be an array")
        return err
    if not isinstance(rows, list):
        return err
    prev_start = None
    for i, item in enumerate(rows):
        p = f"{path}.transcript[{i}]"
        if not isinstance(item, dict):
            err.append(f"{p}: must be an object")
            continue
        extra_i = set(item) - ALLOWED_ITEM
        missing_i = ALLOWED_ITEM - set(item)
        if extra_i:
            err.append(f"{p}: extra keys {sorted(extra_i)}")
        if missing_i:
            err.append(f"{p}: missing keys {sorted(missing_i)}")
        sid = item.get("speaker_id")
        if "speaker_id" in item and not isinstance(sid, str):
            err.append(f"{p}.speaker_id: must be string")
        elif isinstance(sid, str) and not sid:
            err.append(f"{p}.speaker_id: empty")
        for k in ("start_ms", "end_ms"):
            v = item.get(k)
            if k in item and type(v) is bool or (k in item and type(v) is not int):
                err.append(f"{p}.{k}: must be integer (got {type(v).__name__})")
        start = item.get("start_ms")
        end = item.get("end_ms")
        if type(start) is int and start < 0:
            err.append(f"{p}.start_ms: negative")
        if type(start) is int and type(end) is int and end < start:
            err.append(f"{p}: end_ms < start_ms")
        if type(start) is int:
            if prev_start is not None and start < prev_start:
                err.append(f"{p}.start_ms: not sorted ascending")
            prev_start = start
        text = item.get("text")
        if "text" in item and not isinstance(text, str):
            err.append(f"{p}.text: must be string")
    return err


def verify_file(path: Path) -> list[str]:
    raw = path.read_text(encoding="utf-8-sig")
    if raw.startswith("\ufeff"):
        return [f"{path}: BOM not allowed"]
    try:
        obj = json.loads(raw)
    except json.JSONDecodeError as e:
        return [f"{path}: invalid JSON ({e})"]
    return [f"{path}: {e}" if not e.startswith(str(path)) else e for e in errors_for(obj)]


def main(argv: list[str]) -> int:
    if not argv:
        print("usage: verify-transcript-json.py FILE.json [FILE.json ...]", file=sys.stderr)
        return 2
    n = 0
    for a in argv:
        p = Path(a)
        if not p.is_file():
            print(f"FAIL {p}: not a file")
            n += 1
            continue
        errs = verify_file(p)
        if errs:
            n += 1
            print(f"FAIL {p}")
            for e in errs:
                print(f"  {e}")
        else:
            print(f"OK   {p}")
    return 1 if n else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
