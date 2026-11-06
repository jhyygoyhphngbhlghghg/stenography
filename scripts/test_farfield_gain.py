#!/usr/bin/env python3
"""Far-field gain + DemoPcmPrep vs Platform 5bfc582 / b66581c (synth only)."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

import demo_pcm_prep as PREP  # noqa: E402
import farfield_gain as FF  # noqa: E402
import stream_pcm_gate as G  # noqa: E402


class Thresholds(unittest.TestCase):
    def test_pinned_to_platform_b66581c(self):
        self.assertEqual(FF.TARGET_RMS_FS, 0.08)
        self.assertEqual(FF.MAX_GAIN_DB, 24.0)
        self.assertEqual(FF.PEAK_LIMIT, 0.95)
        self.assertEqual(FF.VAD_RMS_FS, 0.008)
        self.assertEqual(FF.PLATFORM_FFGAIN_FEAT_SHA, "5bfc582d07bbc1556c77638e8bc958db1cc2f83d")
        self.assertEqual(FF.PLATFORM_FFGAIN_DOCS_SHA, "b66581c883d32f3195488cdd02680bdfa0b35038")
        self.assertEqual(G.RMS_REJECT_FS, FF.VAD_RMS_FS)


class SineScenes(unittest.TestCase):
    def test_three_and_six_meter_sine_rms_rise_above_vad_floor(self):
        close = FF.close_talk_sine()
        at3 = FF.at_distance(close, 3.0)
        at6 = FF.at_distance(close, 6.0)
        g3 = FF.stage(at3)
        g6 = FF.stage(at6)
        self.assertLess(g3["rms_before"], G.RMS_SOFT_FS)
        self.assertGreater(g3["rms_before"], G.RMS_REJECT_FS)
        self.assertLess(g6["rms_before"], G.RMS_REJECT_FS)
        self.assertGreaterEqual(g3["rms_after"], G.RMS_SOFT_FS)
        self.assertGreaterEqual(g6["rms_after"], G.RMS_SOFT_FS)
        self.assertAlmostEqual(g3["vad_fire_after"], 1.0, places=9)
        self.assertAlmostEqual(g6["vad_fire_before"], 0.0, places=9)
        self.assertAlmostEqual(g6["vad_fire_after"], 1.0, places=9)
        self.assertGreater(g3["gain_db"], 0.0)
        self.assertGreater(g6["gain_db"], g3["gain_db"])
        self.assertLessEqual(g6["gain_db"], FF.MAX_GAIN_DB + 1e-6)

    def test_five_and_six_meter_sine_would_hard_skip_unbound_until_gain(self):
        close = FF.close_talk_sine()
        for d in (5.0, 6.0):
            far = FF.at_distance(close, d)
            before = G.inspect_float(far, bound_seat=False)
            self.assertEqual(before["code"], "LOW_ENERGY")
            g = FF.stage(far)
            after_unbound = G.inspect_float(g["samples"], bound_seat=False)
            self.assertTrue(after_unbound["asr_allowed"])
            after_bound = G.inspect_float(g["samples"], bound_seat=True)
            self.assertTrue(after_bound["asr_allowed"])
            self.assertAlmostEqual(g["vad_fire_before"], 0.0, places=9)
            self.assertAlmostEqual(g["vad_fire_after"], 1.0, places=9)


class DemoPrep(unittest.TestCase):
    def test_four_to_six_meter_phone_scenes_clear_floor_and_never_hard_drop(self):
        for d in (4.0, 5.0, 6.0):
            scene = FF.scene(d)
            prep = PREP.prepare(scene)
            self.assertFalse(prep["skip_asr"], f"hard-skip at {d}m")
            self.assertTrue(prep["gain"]["boosted"], f"boost {d}m")
            self.assertGreaterEqual(prep["gain"]["rms_after"], G.RMS_REJECT_FS)
            self.assertTrue(prep["after_gate"]["asr_allowed"])
            self.assertGreater(prep["gain"]["vad_fire_after"], 0.5)

    def test_silence_not_boosted_into_hiss(self):
        z = FF.stage([0.0] * FF.N_400MS)
        self.assertFalse(z["boosted"])
        self.assertEqual(z["gain_linear"], 1.0)
        self.assertEqual(z["rms_after"], 0.0)

    def test_demo_prep_hard_skips_clipped_and_empty_keeps_far_speech(self):
        clipped_pcm = [1.0 if i % 2 == 0 else -1.0 for i in range(6400)]
        clipped = PREP.prepare(clipped_pcm)
        self.assertTrue(clipped["skip_asr"])
        self.assertEqual(clipped["skip_code"], "CLIPPED")

        empty = PREP.prepare([])
        self.assertTrue(empty["skip_asr"])
        self.assertEqual(empty["skip_code"], "LOW_ENERGY")

        far = PREP.prepare(FF.scene(6.0))
        self.assertFalse(far["skip_asr"])
        self.assertTrue(far["gain"]["boosted"])
        self.assertTrue(far["after_gate"]["asr_allowed"])

    def test_clip_guard_caps_peak(self):
        sparse = [0.0] * 8000
        sparse[100] = 0.99
        g = FF.stage(sparse)
        self.assertTrue(g["clip_guard"])
        self.assertLessEqual(g["peak_after"], FF.PEAK_LIMIT + 1e-5)


if __name__ == "__main__":
    unittest.main()
