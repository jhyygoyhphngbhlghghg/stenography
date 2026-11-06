#!/usr/bin/env python3
"""Python mirror of Platform DemoPcmPrep.kt @ ae86903 / db3afe4.

Single-channel DEMO_STAGE6 prep (offline ViewModel path — not Path B workers).
CLIPPED / BAD_MIC / empty on raw PCM (hard), then **per-turn** far-field gain
so quiet 4–6 m islands clear the ASR floor without attenuating a loud near
seat. Bound-main inspect: SOFT_LOW / soft BAND_LIMITED, never hard-drop
distant talkers for energy.

`per_turn=False` keeps historical whole-file AGC for DEMO_STAGE6_ffgain.
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
    per_turn: bool = True,
) -> dict[str, Any]:
    x = np.asarray(samples, dtype=np.float32).reshape(-1)
    raw_gate = PCM_GATE.inspect_float(
        x, sample_rate_hz=sample_rate_hz, bound_seat=bound_main, version=3
    )
    hard = _hard_skip_code(raw_gate)
    if hard is not None:
        empty = FF.stage(np.zeros(0, dtype=np.float32), per_turn=per_turn)
        return {
            "samples": x.copy(),
            "skip_asr": True,
            "skip_code": hard,
            "raw_gate": raw_gate,
            "after_gate": raw_gate,
            "gain": empty,
            "per_turn": bool(per_turn),
        }
    gain = FF.stage(x, per_turn=per_turn)
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
        "per_turn": bool(per_turn),
    }


def gain_meta(prep: dict[str, Any]) -> dict[str, Any]:
    g = prep["gain"]
    rg = prep["raw_gate"]
    ag = prep["after_gate"]
    segs = g.get("segments") or []
    compact = [
        {
            "start": s.get("start"),
            "end": s.get("end"),
            "rms_before": s.get("rms_before"),
            "rms_after": s.get("rms_after"),
            "gain_db": s.get("gain_db"),
            "boosted": s.get("boosted"),
        }
        for s in segs
    ]
    per_turn = bool(prep.get("per_turn", g.get("mode") == "per_turn"))
    platform = (
        "db3afe4 / ae86903 FarFieldGain per-turn + DemoPcmPrep"
        if per_turn
        else "b66581c / 5bfc582 FarFieldGain whole-file + DemoPcmPrep"
    )
    return {
        "platform": platform,
        "mode": g.get("mode"),
        "per_turn": per_turn,
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
        "loud_crushed": g.get("loud_crushed"),
        "n_segments": len(segs),
        "vad_fire_before": g.get("vad_fire_before"),
        "vad_fire_after": g.get("vad_fire_after"),
        "segments": compact,
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
