#!/usr/bin/env python3
"""Python mirror of Platform DemoPcmPrep.kt @ 5bfc582 (docs @ b66581c).

Single-channel DEMO_STAGE6 prep (offline ViewModel path — not Path B workers).
CLIPPED / BAD_MIC / empty on raw PCM (hard), then far-field gain so 4–6 m
speech clears the ASR energy floor, then inspect as bound main:
SOFT_LOW / soft BAND_LIMITED, never hard-drop distant talkers for energy.
"""
from __future__ import annotations

from typing import Any

import numpy as np

import farfield_gain as FF
import stream_pcm_gate as PCM_GATE

PLATFORM_FFGAIN_FEAT_SHA = FF.PLATFORM_FFGAIN_FEAT_SHA
PLATFORM_FFGAIN_DOCS_SHA = FF.PLATFORM_FFGAIN_DOCS_SHA

HARD_SKIP = ("CLIPPED", "BAD_MIC")


def _hard_skip_code(stats: dict[str, Any]) -> str | None:
    code = stats.get("code")
    if code in HARD_SKIP:
        return code
    if code == "LOW_ENERGY" and int(stats.get("n") or 0) == 0:
        return code
    return None


def prepare(
    samples,
    sample_rate_hz: int = PCM_GATE.SAMPLE_RATE_HZ,
    *,
    bound_main: bool = True,
) -> dict[str, Any]:
    x = np.asarray(samples, dtype=np.float32).reshape(-1)
    raw_gate = PCM_GATE.inspect_float(
        x, sample_rate_hz=sample_rate_hz, bound_seat=bound_main, version=3
    )
    hard = _hard_skip_code(raw_gate)
    if hard is not None:
        empty = FF.stage(np.zeros(0, dtype=np.float32))
        return {
            "samples": x.copy(),
            "skip_asr": True,
            "skip_code": hard,
            "raw_gate": raw_gate,
            "after_gate": raw_gate,
            "gain": empty,
        }
    gain = FF.stage(x)
    after_gate = PCM_GATE.inspect_float(
        gain["samples"],
        sample_rate_hz=sample_rate_hz,
        bound_seat=bound_main,
        version=3,
    )
    return {
        "samples": gain["samples"],
        "skip_asr": False,
        "skip_code": None,
        "raw_gate": raw_gate,
        "after_gate": after_gate,
        "gain": gain,
    }


def gain_meta(prep: dict[str, Any]) -> dict[str, Any]:
    g = prep["gain"]
    rg = prep["raw_gate"]
    ag = prep["after_gate"]
    return {
        "platform": "b66581c / 5bfc582 FarFieldGain+DemoPcmPrep",
        "skip_asr": prep["skip_asr"],
        "skip_code": prep["skip_code"],
        "gain_db": g.get("gain_db"),
        "gain_linear": g.get("gain_linear"),
        "rms_before": g.get("rms_before"),
        "rms_after": g.get("rms_after"),
        "peak_before": g.get("peak_before"),
        "peak_after": g.get("peak_after"),
        "clip_guard": g.get("clip_guard"),
        "boosted": g.get("boosted"),
        "vad_fire_before": g.get("vad_fire_before"),
        "vad_fire_after": g.get("vad_fire_after"),
        "raw_gate": {
            "verdict": rg.get("verdict"),
            "code": rg.get("code"),
            "soft_flags": rg.get("soft_flags"),
            "asr_allowed": rg.get("asr_allowed"),
            "rms_fs": rg.get("rms_fs"),
            "n": rg.get("n"),
        },
        "after_gate": {
            "verdict": ag.get("verdict"),
            "code": ag.get("code"),
            "soft_flags": ag.get("soft_flags"),
            "asr_allowed": ag.get("asr_allowed"),
            "rms_fs": ag.get("rms_fs"),
            "n": ag.get("n"),
        },
    }
