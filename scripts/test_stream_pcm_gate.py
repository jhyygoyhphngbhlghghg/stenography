#!/usr/bin/env python3
"""StreamPcmGate Python mirror vs PR #6 / Platform f5ff5e2 constants.

Does not touch Eval wavs. No invented metrics.
"""
from __future__ import annotations

import math
import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

import stream_pcm_gate as G  # noqa: E402


def _sine(amp: float = 0.1, hz: float = 1000.0, n: int = 6400) -> list[int]:
    out = []
    for i in range(n):
        x = amp * math.sin(2.0 * math.pi * hz * i / G.SAMPLE_RATE_HZ)
        s = int(round(x * 32767.0))
        out.append(max(-32767, min(32767, s)))
    return out


def _tiny_noise(n: int = 6400, seed: int = 1) -> list[int]:
    state = seed & 0xFFFFFFFF
    out = []
    for _ in range(n):
        state ^= (state << 13) & 0xFFFFFFFF
        state ^= (state >> 17) & 0xFFFFFFFF
        state ^= (state << 5) & 0xFFFFFFFF
        out.append((state % 7) - 3)
    return out


class Thresholds(unittest.TestCase):
    def test_platform_f5ff5e2_constants(self):
        self.assertEqual(G.RMS_REJECT_FS, 0.008)
        self.assertEqual(G.PEAK_REJECT_FS, 0.012)
        self.assertEqual(G.CLIP_REJECT, 0.02)
        self.assertEqual(G.DC_REJECT_FS, 0.20)
        self.assertEqual(G.STUCK_VARIANCE_FS, 1e-7)
        self.assertEqual(G.MIN_SAMPLES, 800)
        self.assertEqual(G.CLIP_S16, 32760)
        self.assertEqual(G.FULL_SCALE, 32768.0)


class Verdicts(unittest.TestCase):
    def test_silence_low_energy(self):
        q = G.inspect_s16([0] * 6400)
        self.assertEqual(q["code"], "LOW_ENERGY")
        self.assertFalse(q["asr_allowed"])

    def test_tiny_noise_low_energy(self):
        q = G.inspect_s16(_tiny_noise())
        self.assertEqual(q["code"], "LOW_ENERGY")
        self.assertLess(q["rms_fs"], G.RMS_REJECT_FS)

    def test_clipped_square(self):
        s = [32767 if i % 2 == 0 else -32767 for i in range(6400)]
        q = G.inspect_s16(s)
        self.assertEqual(q["code"], "CLIPPED")
        self.assertGreaterEqual(q["clip_ratio"], G.CLIP_REJECT)

    def test_stuck_dc_bad_mic(self):
        q = G.inspect_s16([8000] * 6400)
        self.assertEqual(q["code"], "BAD_MIC")

    def test_extreme_dc_bad_mic(self):
        q = G.inspect_s16([20000] * 6400)
        self.assertEqual(q["code"], "BAD_MIC")

    def test_clean_tone_pass(self):
        q = G.inspect_s16(_sine())
        self.assertEqual(q["verdict"], "Pass")
        self.assertTrue(q["asr_allowed"])
        self.assertGreater(q["rms_fs"], G.RMS_SOFT_FS)

    def test_short_buffer(self):
        q = G.inspect_s16(_sine(n=400))
        self.assertEqual(q["code"], "LOW_ENERGY")
        self.assertIn("800", q["message"])


if __name__ == "__main__":
    unittest.main()
