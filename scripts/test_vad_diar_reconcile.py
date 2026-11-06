#!/usr/bin/env python3
"""VadDiarReconcile vs Impl 552b142 (synth only)."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

import farfield_gain as FF  # noqa: E402
import vad_diar_reconcile as R  # noqa: E402


HZ = 16_000


def vad_span(start_s: int, end_s: int) -> dict[str, int]:
    return {"start_sample": start_s, "end_sample": end_s}


def diar(spk: int, a: int, b: int) -> dict[str, int]:
    return {"speaker_index": spk, "start_ms": a, "end_ms": b}


class Reconcile(unittest.TestCase):
    def test_pinned_to_impl_552b142(self):
        self.assertEqual(R.IMPL_SECOND_PASS_HEAD, "552b142898f3cf3a8bddb939f0864730dbafb261")
        self.assertEqual(R.STAGE6["expected_speakers"], 6)
        self.assertEqual(R.STAGE6["orphan_min_ms"], 400)

    def test_orphan_fifth_turn_is_recovered_as_speaker(self):
        vad = [vad_span(i * HZ, (i + 1) * HZ) for i in range(6)]
        diar_segs = [
            diar(0, 0, 1_000),
            diar(1, 1_000, 2_000),
            diar(2, 2_000, 3_000),
            diar(3, 3_000, 4_000),
            diar(5, 5_000, 6_000),
        ]
        out = R.run(vad, diar_segs, HZ, R.STAGE6)
        self.assertEqual(6, len(out))
        self.assertEqual(6, len({s["speaker_index"] for s in out}))
        fifth = [s for s in out if s["start_ms"] == 4_000]
        self.assertEqual(1, len(fifth))
        self.assertGreater(fifth[0]["end_ms"], fifth[0]["start_ms"])

    def test_large_vad_blob_does_not_swallow_near_seat(self):
        vad = [vad_span(0, HZ * 6)]
        diar_segs = [diar(0, 0, 1_000), diar(1, 2_000, 2_202)]
        out = R.run(vad, diar_segs, HZ, R.STAGE6)
        near = min(out, key=lambda s: s["start_ms"])
        self.assertEqual(0, near["start_ms"])
        self.assertEqual(1_000, near["end_ms"])
        self.assertGreaterEqual(len(out), 3)
        self.assertTrue(all(not (s["start_ms"] == 0 and s["end_ms"] == 6_000) for s in out))

    def test_energy_hole_recovers_quiet_far_turn_without_moving_near(self):
        pcm, turns = FF.sequential_mix()
        near = next(t for t in turns if t["name"] == "near_3m")
        t0 = near["start"] * 1000 // HZ
        t1 = near["end"] * 1000 // HZ
        diar_segs = [diar(0, t0, t1)]
        vad = [vad_span(near["start"], near["end"])]
        out = R.run(vad, diar_segs, HZ, R.STAGE6, samples=pcm)
        kept = min(out, key=lambda s: s["start_ms"])
        self.assertEqual(t0, kept["start_ms"])
        self.assertEqual(t1, kept["end_ms"])
        self.assertGreaterEqual(len(out), 2, "recover s5-like far island")
        self.assertTrue(any(s["start_ms"] >= t1 and s["end_ms"] - s["start_ms"] >= 400 for s in out))

    def test_present_connect_scene_leaves_diar_untouched(self):
        vad = [vad_span(0, HZ)]
        diar_segs = [diar(3, 100, 200)]
        out = R.run(vad, diar_segs, HZ, R.PRESENT_CONNECT)
        self.assertEqual(1, len(out))
        self.assertEqual(3, out[0]["speaker_index"])
        self.assertEqual(100, out[0]["start_ms"])

    def test_relabel_by_appearance_is_sequential(self):
        segs = [diar(4, 3_000, 4_000), diar(9, 0, 1_000), diar(4, 5_000, 6_000)]
        out = R.relabel_by_appearance(segs)
        self.assertEqual([1, 0, 1], [s["speaker_index"] for s in out])


if __name__ == "__main__":
    unittest.main()
