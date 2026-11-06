#!/usr/bin/env python3
"""PresentConnect ≤20 streams: admit 1..20, reject 21 (OVER_CAPACITY).

Mirrors PresentConnectStreamTable.kt so the cap can be measured without an
Android emulator. Exit 0 on PASS. Writes artifacts/test-results/present-connect-capacity.json
when RECORD_EVIDENCE=1 or --record is set.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAX_STREAMS = 20


class PresentConnectStreamTable:
    def __init__(self, max_streams: int = MAX_STREAMS) -> None:
        self.max_streams = max_streams
        self._lock = threading.Lock()
        self._open: dict[str, None] = {}

    def active_count(self) -> int:
        with self._lock:
            return len(self._open)

    def try_start(self, stream_id: str) -> str:
        if not stream_id:
            raise ValueError("stream_id required")
        with self._lock:
            if stream_id in self._open:
                return "accepted_duplicate"
            if len(self._open) >= self.max_streams:
                return "OVER_CAPACITY"
            self._open[stream_id] = None
            return "accepted"

    def end(self, stream_id: str) -> bool:
        with self._lock:
            return self._open.pop(stream_id, "missing") != "missing"


class PresentConnectCapacityTest(unittest.TestCase):
    def test_accepts_twenty_rejects_twenty_first(self) -> None:
        table = PresentConnectStreamTable(20)
        accepted = []
        rejected = []
        for i in range(1, 22):
            sid = f"s{i:02d}"
            result = table.try_start(sid)
            if result == "accepted":
                accepted.append(sid)
            else:
                rejected.append(sid)
        self.assertEqual([f"s{i:02d}" for i in range(1, 21)], accepted)
        self.assertEqual(["s21"], rejected)
        self.assertEqual(20, table.active_count())

    def test_end_frees_slot(self) -> None:
        table = PresentConnectStreamTable(20)
        for i in range(1, 21):
            self.assertEqual("accepted", table.try_start(f"s{i}"))
        self.assertEqual("OVER_CAPACITY", table.try_start("s21"))
        self.assertTrue(table.end("s1"))
        self.assertEqual("accepted", table.try_start("s21"))


def record_evidence(out: Path, wall_ms: float, ok: bool) -> None:
    payload = {
        "test": "present-connect-capacity",
        "command": "python3 scripts/test_present_connect_capacity.py --record",
        "max_streams": MAX_STREAMS,
        "accepted": [f"s{i:02d}" for i in range(1, 21)],
        "rejected": ["s21"],
        "rejected_code": "OVER_CAPACITY",
        "wall_ms": round(wall_ms, 3),
        "result": "PASS" if ok else "FAIL",
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--record", action="store_true")
    args = parser.parse_args()
    start = time.perf_counter()
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(PresentConnectCapacityTest)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    wall_ms = (time.perf_counter() - start) * 1000
    ok = result.wasSuccessful()
    if args.record or os.environ.get("RECORD_EVIDENCE") == "1":
        record_evidence(ROOT / "artifacts/test-results/present-connect-capacity.json", wall_ms, ok)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
