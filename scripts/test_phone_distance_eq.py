#!/usr/bin/env python3
"""PhoneDistanceEq + DemoPcmPrep vs Platform af71d91 (synth only)."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

import numpy as np  # noqa: E402

import demo_pcm_prep as PREP  # noqa: E402
import farfield_gain as FF  # noqa: E402
import phone_distance_eq as EQ  # noqa: E402


class Thresholds(unittest.TestCase):
    def test_pinned_to_platform_af71d91(self):
        self.assertEqual(FF.TARGET_RMS_FS, 0.08)
        self.assertEqual(FF.MAX_GAIN_DB, 24.0)
        self.assertEqual(FF.gain_for_segment(0.12, 0.4), 1.0)
        self.assertEqual(EQ.SPEECH_LO_HZ, 250.0)
        self.assertEqual(EQ.SPEECH_HI_HZ, 4500.0)
        self.assertEqual(EQ.SHELF_FC_HZ, 1600.0)
        self.assertEqual(EQ.MAX_SHELF_DB, 9.0)
        self.assertEqual(EQ.NEAR_IDENTITY_RMS_FS, 0.12)
        self.assertEqual(EQ.PLATFORM_PHONE_EQ_HEAD, "af71d91fce841e02d841afb0b093d16f51e5bddb")
        self.assertEqual(EQ.PLATFORM_PHONE_EQ_FEAT_SHA, "040274d538e191c1afdafcb52bc94b9b28077402")
        self.assertEqual(EQ.PLATFORM_PHONE_EQ_INTRO_SHA, "5deb55a7e761998787e992a0b164e8003e9a2ac5")


class NearSeat(unittest.TestCase):
    def test_near_seat_is_identity(self):
        near = FF.speech_stack(peak_amp=0.36)
        eq = EQ.apply(near)
        self.assertFalse(eq["applied"])
        self.assertFalse(eq["near_crushed"])
        self.assertAlmostEqual(FF.rms(near), FF.rms(eq["samples"]), places=9)
        self.assertAlmostEqual(FF.peak(near), FF.peak(eq["samples"]), places=9)
        self.assertTrue(np.allclose(near, eq["samples"], atol=1e-7, rtol=0.0))


class FarScenes(unittest.TestCase):
    def test_far_phone_scenes_lift_speech_band_and_never_hard_drop(self):
        for d in (3.0, 4.0, 5.0, 6.0):
            scene = FF.phone_distance_scene(d)
            prep = PREP.prepare(scene, phone_eq=True)
            self.assertFalse(prep["skip_asr"], f"hard-skip at {d}m")
            self.assertTrue(prep["after_gate"]["asr_allowed"], f"asr {d}m")
            before = EQ.band_energy(prep["gain"]["samples"])
            after = EQ.band_energy(prep["eq"]["samples"])
            self.assertGreater(
                after["speech"], before["speech"], f"speech energy {d}m"
            )
            self.assertGreater(after["presence"], before["presence"], f"presence {d}m")
            self.assertTrue(prep["eq"]["applied"], f"eq applied {d}m")

    def test_sequential_mix_does_not_crush_near_seat(self):
        pcm, turns = FF.sequential_phone_mix()
        prep = PREP.prepare(pcm, phone_eq=True)
        self.assertFalse(prep["skip_asr"])
        self.assertFalse(prep["eq"]["near_crushed"])
        near = next(t for t in turns if t["name"] == "near_3m")
        far6 = next(t for t in turns if t["name"] == "far_6m")
        near0 = pcm[near["start"] : near["end"]]
        near1 = prep["samples"][near["start"] : near["end"]]
        self.assertAlmostEqual(FF.rms(near0), FF.rms(near1), places=6)
        self.assertAlmostEqual(FF.peak(near0), FF.peak(near1), places=6)
        far_g = EQ.band_energy(prep["gain"]["samples"][far6["start"] : far6["end"]])
        far_e = EQ.band_energy(prep["eq"]["samples"][far6["start"] : far6["end"]])
        self.assertGreater(far_e["speech"], far_g["speech"])

    def test_clipped_and_empty_still_hard_skip(self):
        clipped_pcm = [1.0 if i % 2 == 0 else -1.0 for i in range(6400)]
        clipped = PREP.prepare(clipped_pcm, phone_eq=True)
        self.assertTrue(clipped["skip_asr"])
        self.assertEqual(clipped["skip_code"], "CLIPPED")
        empty = PREP.prepare([], phone_eq=True)
        self.assertTrue(empty["skip_asr"])
        self.assertEqual(empty["skip_code"], "LOW_ENERGY")

    def test_phone_eq_forces_per_turn_never_whole_file_crush(self):
        pcm, _ = FF.sequential_phone_mix()
        # Even if the caller asks for whole-file AGC, phone_eq keeps frozen per-turn gain.
        prep = PREP.prepare(pcm, per_turn=False, phone_eq=True)
        self.assertEqual(prep["gain"]["mode"], "per_turn")
        self.assertFalse(prep["gain"]["loud_crushed"])
        self.assertTrue(prep["phone_eq"])
        whole = FF.stage_whole_file(pcm)
        self.assertNotEqual(whole["mode"], prep["gain"]["mode"])


if __name__ == "__main__":
    unittest.main()
