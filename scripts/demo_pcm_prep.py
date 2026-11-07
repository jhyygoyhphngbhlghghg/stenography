#!/usr/bin/env python3
"""Python mirror of Platform DemoPcmPrep.kt @ af71d91.

Single-channel DEMO_STAGE6 prep (offline ViewModel path — not Path B workers).
CLIPPED / BAD_MIC / empty on raw PCM (hard), then **frozen per-turn** far-field
gain (do not retune / do not re-enable whole-file crush), then **phone-distance
EQ** on far islands only (near seat identity). Bound-main inspect: SOFT_LOW /
soft BAND_LIMITED, never hard-drop distant talkers for energy.

`per_turn=False` keeps historical whole-file AGC for DEMO_STAGE6_ffgain.
`phone_eq=True` forces per-turn gain (frozen) then PhoneDistanceEq @ af71d91.
"""
from __future__ import annotations

from typing import Any

import numpy as np

import farfield_gain as FF
import phone_distance_eq as EQ
import stream_pcm_gate as PCM_GATE

PLATFORM_FFGAIN_FEAT_SHA = FF.PLATFORM_FFGAIN_FEAT_SHA
PLATFORM_FFGAIN_DOCS_SHA = FF.PLATFORM_FFGAIN_DOCS_SHA
PLATFORM_PHONE_EQ_HEAD = EQ.PLATFORM_PHONE_EQ_HEAD
PLATFORM_PHONE_EQ_FEAT_SHA = EQ.PLATFORM_PHONE_EQ_FEAT_SHA

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
    phone_eq: bool = False,
) -> dict[str, Any]:
    x = np.asarray(samples, dtype=np.float32).reshape(-1)
    if phone_eq:
        # Gain stays frozen: never whole-file crush when EQ is on.
        per_turn = True
    raw_gate = PCM_GATE.inspect_float(
        x, sample_rate_hz=sample_rate_hz, bound_seat=bound_main, version=3
    )
    hard = _hard_skip_code(raw_gate)
    if hard is not None:
        empty = FF.stage(np.zeros(0, dtype=np.float32), per_turn=per_turn)
        empty_eq = EQ.apply(np.zeros(0, dtype=np.float32), sample_rate_hz)
        return {
            "samples": x.copy(),
            "skip_asr": True,
            "skip_code": hard,
            "raw_gate": raw_gate,
            "after_gate": raw_gate,
            "gain": empty,
            "eq": empty_eq,
            "per_turn": bool(per_turn),
            "phone_eq": bool(phone_eq),
        }
    gain = FF.stage(x, per_turn=per_turn)
    staged = gain["samples"]
    if phone_eq:
        eq = EQ.apply(staged, sample_rate_hz)
        out = eq["samples"]
    else:
        eq = {
            "samples": staged,
            "applied": False,
            "near_crushed": False,
            "segments": [],
        }
        out = staged
    after_gate = PCM_GATE.inspect_float(
        out,
        sample_rate_hz=sample_rate_hz,
        bound_seat=bound_main,
        version=3,
    )
    return {
        "samples": out,
        "skip_asr": False,
        "skip_code": None,
        "raw_gate": raw_gate,
        "after_gate": after_gate,
        "gain": gain,
        "eq": eq,
        "per_turn": bool(per_turn),
        "phone_eq": bool(phone_eq),
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
    phone_eq = bool(prep.get("phone_eq"))
    if phone_eq:
        platform = (
            f"{EQ.PLATFORM_PHONE_EQ_HEAD[:7]} / {EQ.PLATFORM_PHONE_EQ_FEAT_SHA[:7]} "
            "PhoneDistanceEq 250–4500 Hz + frozen per-turn FarFieldGain + DemoPcmPrep"
        )
    elif per_turn:
        platform = "db3afe4 / ae86903 FarFieldGain per-turn + DemoPcmPrep"
    else:
        platform = "b66581c / 5bfc582 FarFieldGain whole-file + DemoPcmPrep"
    meta = {
        "platform": platform,
        "mode": g.get("mode"),
        "per_turn": per_turn,
        "phone_eq": phone_eq,
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
    if phone_eq:
        eqm = EQ.eq_meta(prep.get("eq"))
        meta["eq"] = eqm
        meta["eq_applied"] = eqm.get("applied")
        meta["eq_near_crushed"] = eqm.get("near_crushed")
        meta["eq_n_applied"] = eqm.get("n_applied")
        meta["eq_n_near_identity"] = eqm.get("n_near_identity")
    return meta
