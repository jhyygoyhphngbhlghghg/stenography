#!/usr/bin/env python3
"""StreamPcmGate v2 Python mirror vs Implementer e3ad908 (sha256 == Platform b12a2bb)."""
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
    def test_pinned_to_implementer_e3ad908(self):
        self.assertEqual(G.IMPLEMENTER_GATE_V2_SHA, "e3ad9085b58b563a4feff5932396909c09e18fe2")
        self.assertEqual(G.PLATFORM_GATE_V2_SHA, "b12a2bbbf3c5b7770063c21a2e36d2c63b88d5c5")
        self.assertEqual(
            G.STREAM_PCM_GATE_KT_SHA256,
            "3caa3a6a2d4aaf0ed9ed0592847ab7c9aadfbe8b5916eb95136745c293d26c51",
        )

    def test_v2_constants(self):
        self.assertEqual(G.RMS_REJECT_FS, 0.008)
        self.assertEqual(G.PEAK_REJECT_FS, 0.012)
        self.assertEqual(G.CLIP_REJECT, 0.02)
        self.assertEqual(G.DC_REJECT_FS, 0.20)
        self.assertEqual(G.BAND_LIMITED_HF_RATIO, 0.08)
        self.assertEqual(G.BAND_LIMITED_SPEECH_OCC, 2)
        self.assertEqual(G.BAND_LIMITED_HF_OCC, 0)
        self.assertEqual(G.SPEECH_BANDS, 9)


class Verdicts(unittest.TestCase):
    def test_v1_bound_silence_still_low_energy(self):
        q = G.inspect_s16([0] * 6400, bound_seat=True, version=1)
        self.assertEqual(q["code"], "LOW_ENERGY")
        self.assertFalse(q["asr_allowed"])

    def test_unbound_silence_low_energy(self):
        q = G.inspect_s16([0] * 6400, bound_seat=False)
        self.assertEqual(q["code"], "LOW_ENERGY")
        self.assertFalse(q["asr_allowed"])

    def test_bound_silence_soft_low_still_asr(self):
        q = G.inspect_s16([0] * 6400, bound_seat=True)
        self.assertTrue(q["asr_allowed"])
        self.assertIsNone(q["code"])
        self.assertIn("SOFT_LOW", q["soft_flags"])
        tiny = G.inspect_s16(_tiny_noise(), bound_seat=True)
        self.assertTrue(tiny["asr_allowed"])
        self.assertIn("SOFT_LOW", tiny["soft_flags"])

    def test_empty_hard_low_energy_even_when_bound(self):
        q = G.inspect_s16([], bound_seat=True)
        self.assertEqual(q["code"], "LOW_ENERGY")
        self.assertFalse(q["asr_allowed"])
        self.assertEqual(q["n"], 0)

    def test_unbound_tiny_noise(self):
        q = G.inspect_s16(_tiny_noise())
        self.assertEqual(q["code"], "LOW_ENERGY")
        self.assertLess(q["rms_fs"], G.RMS_REJECT_FS)

    def test_clipped_square(self):
        s = [32767 if i % 2 == 0 else -32767 for i in range(6400)]
        q = G.inspect_s16(s)
        self.assertEqual(q["code"], "CLIPPED")

    def test_stuck_dc_bad_mic(self):
        self.assertEqual(G.inspect_s16([8000] * 6400)["code"], "BAD_MIC")
        self.assertEqual(G.inspect_s16([20000] * 6400)["code"], "BAD_MIC")

    def test_phone_crush_band_limited(self):
        stack = G.inspect_s16(G.tone_stack_s16(G.PHONE_HZ))
        self.assertEqual(stack["code"], "BAND_LIMITED")
        self.assertEqual(stack["clip_ratio"], 0.0)
        self.assertLess(abs(stack["dc_fs"]), G.DC_REJECT_FS)
        self.assertLess(stack["hf_ratio"], G.BAND_LIMITED_HF_RATIO)
        self.assertGreaterEqual(stack["speech_occupancy"], G.BAND_LIMITED_SPEECH_OCC)
        self.assertEqual(stack["hf_occupancy"], 0)
        lpf = G.inspect_s16(G.phone_crush_lpf_s16())
        self.assertEqual(lpf["code"], "BAND_LIMITED")
        self.assertEqual(lpf["clip_ratio"], 0.0)
        self.assertLess(abs(lpf["dc_fs"]), G.DC_REJECT_FS)
        self.assertLess(lpf["hf_ratio"], G.BAND_LIMITED_HF_RATIO)

    def test_broadband_and_clean_tone_not_band_limited(self):
        broad = G.inspect_s16(G.tone_stack_s16(G.BROADBAND_HZ))
        self.assertTrue(broad["asr_allowed"])
        self.assertGreater(broad["hf_ratio"], G.BAND_LIMITED_HF_RATIO)
        tone = G.inspect_s16(_sine())
        self.assertTrue(tone["asr_allowed"])
        self.assertIsNone(tone["code"])
        self.assertEqual(tone["speech_occupancy"], 1)


if __name__ == "__main__":
    unittest.main()
