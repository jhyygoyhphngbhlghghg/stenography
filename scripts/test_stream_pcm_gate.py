#!/usr/bin/env python3
"""StreamPcmGate v3 Python mirror vs Implementer a5b7aed (sha256 == Platform 686ac4a)."""
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
    def test_pinned_to_implementer_a5b7aed(self):
        self.assertEqual(G.IMPLEMENTER_GATE_V3_SHA, "a5b7aed7d89ec76e919eed62c69f31bf0e926725")
        self.assertEqual(G.PLATFORM_GATE_V3_SHA, "686ac4a790fd52b831709955525d029098a65bae")
        self.assertEqual(
            G.STREAM_PCM_GATE_KT_SHA256,
            "e29a34bb2503000b48b083e3ee1234e81e0f020a08cac52937da6db161a20d15",
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

    def test_unbound_phone_crush_hard_band_limited(self):
        stack = G.inspect_s16(G.tone_stack_s16(G.PHONE_HZ))
        self.assertEqual(stack["code"], "BAND_LIMITED")
        self.assertFalse(stack["asr_allowed"])
        self.assertEqual(stack["clip_ratio"], 0.0)
        self.assertLess(abs(stack["dc_fs"]), G.DC_REJECT_FS)
        self.assertLess(stack["hf_ratio"], G.BAND_LIMITED_HF_RATIO)
        self.assertGreaterEqual(stack["speech_occupancy"], G.BAND_LIMITED_SPEECH_OCC)
        self.assertEqual(stack["hf_occupancy"], 0)
        lpf = G.inspect_s16(G.phone_crush_lpf_s16())
        self.assertEqual(lpf["code"], "BAND_LIMITED")
        self.assertFalse(lpf["asr_allowed"])
        self.assertEqual(lpf["clip_ratio"], 0.0)
        self.assertLess(abs(lpf["dc_fs"]), G.DC_REJECT_FS)
        self.assertLess(lpf["hf_ratio"], G.BAND_LIMITED_HF_RATIO)

    def test_bound_phone_crush_soft_band_limited_still_asr(self):
        q = G.inspect_s16(G.phone_crush_lpf_s16(), bound_seat=True)
        self.assertTrue(q["asr_allowed"])
        self.assertIsNone(q["code"])
        self.assertIn("BAND_LIMITED", q["soft_flags"])
        stack = G.inspect_s16(G.tone_stack_s16(G.PHONE_HZ), bound_seat=True)
        self.assertTrue(stack["asr_allowed"])
        self.assertIn("BAND_LIMITED", stack["soft_flags"])

    def test_v2_bound_crush_still_hard(self):
        q = G.inspect_s16(G.phone_crush_lpf_s16(), bound_seat=True, version=2)
        self.assertEqual(q["code"], "BAND_LIMITED")
        self.assertFalse(q["asr_allowed"])

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
