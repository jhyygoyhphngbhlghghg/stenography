#!/usr/bin/env python3
"""Unit checks for DER/JER/WER helpers. These are NOT clip scores."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import eval_metrics as M  # noqa: E402


def doc(rows):
    return {"transcript": rows}


def u(sid, s, e, text=""):
    return {"speaker_id": sid, "start_ms": s, "end_ms": e, "text": text}


class WerTests(unittest.TestCase):
    def test_identical(self):
        self.assertEqual(M.wer("привет мир", "привет мир"), 0.0)

    def test_empty_hyp_is_all_deletions(self):
        self.assertEqual(M.wer("один два", ""), 1.0)

    def test_yo(self):
        self.assertEqual(M.wer("ёлка", "елка"), 0.0)


class DiarTests(unittest.TestCase):
    def test_identical_der_zero(self):
        ref = doc([u("speaker_1", 0, 1000), u("speaker_2", 1000, 2000)])
        s = M.diarization_scores(ref, ref, collar_ms=0)
        self.assertIsNotNone(s["der"])
        self.assertLess(s["der"], 1e-6)
        self.assertLess(s["jer"], 1e-6)
        self.assertGreater(s["speaker_purity"], 0.99)

    def test_complete_miss(self):
        ref = doc([u("speaker_1", 0, 1000)])
        hyp = doc([])
        s = M.diarization_scores(ref, hyp, collar_ms=0)
        self.assertEqual(s["der"], 1.0)

    def test_label_permutation_maps(self):
        ref = doc([u("speaker_1", 0, 800), u("speaker_2", 800, 1600)])
        hyp = doc([u("speaker_9", 0, 800), u("speaker_3", 800, 1600)])
        s = M.diarization_scores(ref, hyp, collar_ms=0)
        self.assertLess(s["der"], 1e-6)
        self.assertEqual(s["mapping_hyp_to_ref"]["speaker_9"], "speaker_1")

    def test_main_wer_uses_mapping(self):
        ref = doc(
            [
                u("speaker_1", 0, 500, "привет"),
                u("speaker_2", 500, 900, "шум"),
            ]
        )
        hyp = doc(
            [
                u("speaker_7", 0, 500, "привет"),
                u("speaker_8", 500, 900, "шум"),
            ]
        )
        self.assertEqual(M.main_speaker_wer(ref, hyp, main_id="speaker_1"), 0.0)

    def test_speaker_wers_aggregate(self):
        ref = doc(
            [
                u("speaker_1", 0, 500, "привет мир"),
                u("speaker_2", 500, 900, "один"),
            ]
        )
        hyp = doc(
            [
                u("speaker_7", 0, 500, "привет мир"),
                u("speaker_8", 500, 900, "два"),
            ]
        )
        s = M.speaker_wers(ref, hyp)
        self.assertEqual(s["per_speaker"]["speaker_1"], 0.0)
        self.assertEqual(s["per_speaker"]["speaker_2"], 1.0)
        self.assertEqual(s["wer_aggregate"], 1 / 3)


if __name__ == "__main__":
    raise SystemExit(unittest.main(verbosity=2))
