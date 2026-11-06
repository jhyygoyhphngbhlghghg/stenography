#!/usr/bin/env python3
"""Python mirror of Platform PhoneDistanceEq.kt @ af71d91.

Platform source (do not cherry-pick Android onto this eval branch):
  HEAD  af71d91fce841e02d841afb0b093d16f51e5bddb
  feat  5deb55a7e761998787e992a0b164e8003e9a2ac5  (intro)
  feat  040274d538e191c1afdafcb52bc94b9b28077402  (250–4500 Hz band)
  files PhoneDistanceEq.kt, DemoPcmPrep.kt, FarFieldGain.kt (FarFieldSynth),
        PhoneDistanceEqTest.kt, scripts/measure-phone-distance-eq.py

DEMO_STAGE6 phone-distance EQ (offline host ingest — not Path B).
Eval per-turn @ 54177a1e: energy islands already ≥0.08 FS so FarFieldGain is a
no-op; remaining damage is Nothing-class phone high-end crush + 4–6 m muffling.
Per-turn 1-pole high-shelf on **far** islands only. Loud near seat
(RMS ≥ NEAR_IDENTITY_RMS_FS) is identity (0 dB). Peak cap by reducing the
shelf, never by attenuating the whole turn (gain stays frozen).

Does not retune Eval DEMO_STAGE6 wavs. Ingest-only staging.
"""
from __future__ import annotations

import math
from typing import Any

import numpy as np

import farfield_gain as FF

PLATFORM_PHONE_EQ_HEAD = "af71d91fce841e02d841afb0b093d16f51e5bddb"
PLATFORM_PHONE_EQ_INTRO_SHA = "5deb55a7e761998787e992a0b164e8003e9a2ac5"
PLATFORM_PHONE_EQ_FEAT_SHA = "040274d538e191c1afdafcb52bc94b9b28077402"

SAMPLE_RATE_HZ = FF.SAMPLE_RATE_HZ
SHELF_FC_HZ = 1_600.0
MAX_SHELF_DB = 9.0
NEAR_IDENTITY_RMS_FS = 0.12
PEAK_LIMIT = FF.PEAK_LIMIT
SPEECH_LO_HZ = 250.0
SPEECH_HI_HZ = 4_500.0
PRESENCE_LO_HZ = 2_000.0
PRESENCE_TARGET = 0.22
GOERTZEL_HZ = (
    250.0, 500.0, 800.0, 1_000.0, 1_500.0, 2_000.0, 2_500.0, 3_000.0, 3_500.0,
    4_000.0, 4_500.0, 5_000.0, 6_000.0, 7_000.0,
)


def max_high_gain() -> float:
    return FF.db_to_linear(MAX_SHELF_DB)


def is_near_seat(rms_fs: float, max_rms_fs: float) -> bool:
    if rms_fs >= NEAR_IDENTITY_RMS_FS:
        return True
    return max_rms_fs >= NEAR_IDENTITY_RMS_FS and rms_fs >= 0.92 * max_rms_fs


def goertzel_power(samples, freq: float, sample_rate_hz: int = SAMPLE_RATE_HZ) -> float:
    xs = np.asarray(samples, dtype=np.float64).reshape(-1)
    w = 2.0 * math.pi * freq / sample_rate_hz
    coeff = 2.0 * math.cos(w)
    s1 = 0.0
    s2 = 0.0
    for x in xs:
        s0 = float(x) + coeff * s1 - s2
        s2 = s1
        s1 = s0
    return s1 * s1 + s2 * s2 - coeff * s1 * s2


def band_energy(samples, sample_rate_hz: int = SAMPLE_RATE_HZ) -> dict[str, float]:
    speech = 0.0
    presence = 0.0
    full = 0.0
    for hz in GOERTZEL_HZ:
        p = goertzel_power(samples, hz, sample_rate_hz)
        full += p
        if hz + 1e-9 >= SPEECH_LO_HZ and hz - 1e-9 <= SPEECH_HI_HZ:
            speech += p
        if hz + 1e-9 >= PRESENCE_LO_HZ and hz - 1e-9 <= SPEECH_HI_HZ:
            presence += p
    eps = 1e-18
    return {
        "speech": speech,
        "presence": presence,
        "full": full,
        "rms": FF.rms(samples),
        "peak": FF.peak(samples),
        "speech_to_full": speech / (full + eps),
        "presence_to_speech": presence / (speech + eps),
    }


def shelf_gain_for(bands: dict[str, float]) -> float:
    crushed = (PRESENCE_TARGET - bands["presence_to_speech"]) / PRESENCE_TARGET
    crushed = min(1.0, max(0.0, crushed))
    high_gain = 1.0 + crushed * (max_high_gain() - 1.0)
    return 1.0 if high_gain < 1.0 + 1e-9 else float(high_gain)


def _shelf_once(
    samples,
    fc_hz: float,
    high_gain: float,
    sample_rate_hz: int,
) -> np.ndarray:
    xs = np.asarray(samples, dtype=np.float32).reshape(-1)
    a = 1.0 - math.exp(-2.0 * math.pi * fc_hz / sample_rate_hz)
    out = np.empty(xs.size, dtype=np.float32)
    lp = 0.0
    for i, x in enumerate(xs.astype(np.float64, copy=False)):
        lp += a * (float(x) - lp)
        out[i] = np.float32(lp + high_gain * (float(x) - lp))
    return out


def high_shelf(
    samples,
    fc_hz: float = SHELF_FC_HZ,
    high_gain: float = 1.0,
    sample_rate_hz: int = SAMPLE_RATE_HZ,
    peak_limit: float = PEAK_LIMIT,
) -> np.ndarray:
    xs = np.asarray(samples, dtype=np.float32).reshape(-1)
    if high_gain <= 1.0 + 1e-12:
        return xs.copy()
    g = float(high_gain)
    out = _shelf_once(xs, fc_hz, g, sample_rate_hz)
    if FF.peak(out) <= peak_limit:
        return out
    lo = 1.0
    hi = g
    for _ in range(12):
        mid = 0.5 * (lo + hi)
        cand = _shelf_once(xs, fc_hz, mid, sample_rate_hz)
        if FF.peak(cand) > peak_limit:
            hi = mid
        else:
            lo = mid
            out = cand
            g = mid
    lim = np.float32(peak_limit)
    return np.clip(out, -lim, lim).astype(np.float32)


def _identity(samples) -> dict[str, Any]:
    x = np.asarray(samples, dtype=np.float32).reshape(-1)
    return {
        "samples": x.copy(),
        "applied": False,
        "near_crushed": False,
        "segments": [],
        "platform": (
            f"PhoneDistanceEq.kt @ {PLATFORM_PHONE_EQ_HEAD[:7]} "
            f"(feat {PLATFORM_PHONE_EQ_FEAT_SHA[:7]} / {PLATFORM_PHONE_EQ_INTRO_SHA[:7]})"
        ),
    }


def apply(samples, sample_rate_hz: int = SAMPLE_RATE_HZ) -> dict[str, Any]:
    x = np.asarray(samples, dtype=np.float32).reshape(-1)
    n = int(x.size)
    if n == 0:
        return _identity(x)
    ranges = FF.find_segments(x, sample_rate_hz)
    if not ranges:
        return _identity(x)
    frame = max(1, int((sample_rate_hz * FF.FRAME_MS) / 1000))
    rms_seg = [FF.active_rms(x, a, b, frame) for a, b in ranges]
    max_rms = max(rms_seg) if rms_seg else 0.0
    out = x.copy()
    segs: list[dict[str, Any]] = []
    any_eq = False
    for (start, end), r0 in zip(ranges, rms_seg):
        sl = x[start:end]
        near = is_near_seat(r0, max_rms)
        bands0 = band_energy(sl, sample_rate_hz)
        if near:
            g = 1.0
            applied = False
        else:
            g = shelf_gain_for(bands0)
            applied = g > 1.0 + 1e-9
            if applied:
                eqed = high_shelf(sl, SHELF_FC_HZ, g, sample_rate_hz)
                out[start:end] = eqed
                any_eq = True
        after = out[start:end]
        segs.append(
            {
                "start": int(start),
                "end": int(end),
                "rms_before": r0,
                "rms_after": FF.active_rms(out, start, end, frame),
                "peak_before": FF.peak(sl),
                "peak_after": FF.peak(after),
                "near_identity": bool(near),
                "high_gain_linear": float(g),
                "shelf_db": FF.linear_to_db(g),
                "applied": bool(applied),
                "speech_ratio_before": bands0["speech_to_full"],
                "speech_ratio_after": band_energy(after, sample_rate_hz)["speech_to_full"],
            }
        )
    return {
        "samples": out,
        "applied": bool(any_eq),
        "near_crushed": any(
            s["near_identity"] and s["rms_after"] + 1e-6 < s["rms_before"] for s in segs
        ),
        "segments": segs,
        "platform": (
            f"PhoneDistanceEq.kt @ {PLATFORM_PHONE_EQ_HEAD[:7]} "
            f"(feat {PLATFORM_PHONE_EQ_FEAT_SHA[:7]} / {PLATFORM_PHONE_EQ_INTRO_SHA[:7]})"
        ),
    }


def eq_meta(eq: dict[str, Any] | None) -> dict[str, Any]:
    if not eq:
        return {"applied": False, "n_segments": 0, "near_crushed": False}
    segs = eq.get("segments") or []
    compact = [
        {
            "start": s.get("start"),
            "end": s.get("end"),
            "near_identity": s.get("near_identity"),
            "applied": s.get("applied"),
            "shelf_db": s.get("shelf_db"),
            "rms_before": s.get("rms_before"),
            "rms_after": s.get("rms_after"),
        }
        for s in segs
    ]
    applied_n = sum(1 for s in segs if s.get("applied"))
    ident_n = sum(1 for s in segs if s.get("near_identity"))
    return {
        "platform": eq.get("platform"),
        "applied": bool(eq.get("applied")),
        "near_crushed": bool(eq.get("near_crushed")),
        "n_segments": len(segs),
        "n_applied": applied_n,
        "n_near_identity": ident_n,
        "segments": compact,
    }
