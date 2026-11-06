#!/usr/bin/env python3
"""Python mirror of Implementer PR #6 StreamPcmGate.kt @ dae416f
(byte-identical thresholds to Platform f5ff5e2).

Path B pre-ASR gate on **one stream's** pcm_s16le only. Hard reject:
LOW_ENERGY / CLIPPED / BAD_MIC — no ASR, no brief row. Soft flags still ASR.

Do not retune these constants. Eval fixture wavs are not modified here.
"""
from __future__ import annotations

import math
from typing import Any

SAMPLE_RATE_HZ = 16_000
FULL_SCALE = 32768.0
CLIP_S16 = 32_760
RMS_REJECT_FS = 0.008
RMS_SOFT_FS = 0.020
PEAK_REJECT_FS = 0.012
CLIP_REJECT = 0.02
CLIP_SOFT = 0.002
DC_REJECT_FS = 0.20
DC_SOFT_FS = 0.05
STUCK_VARIANCE_FS = 1e-7
MIN_SAMPLES = 800
SNR_EPS = 1e-12


def inspect_s16(samples_s16, sample_rate_hz: int = SAMPLE_RATE_HZ) -> dict[str, Any]:
    """Match StreamPcmGate.inspect (little-endian s16 samples as ints)."""
    n = len(samples_s16)
    if n <= 0:
        return {
            "n": 0,
            "duration_ms": 0.0,
            "rms_fs": 0.0,
            "rms_dbfs": float("-inf"),
            "peak_fs": 0.0,
            "dc_fs": 0.0,
            "variance_fs": 0.0,
            "ac_rms_fs": 0.0,
            "clip_ratio": 0.0,
            "snr_proxy": 0.0,
            "verdict": "Reject",
            "code": "LOW_ENERGY",
            "message": "empty pcm",
            "soft_flags": [],
            "asr_allowed": False,
        }
    ssum = 0.0
    ssq = 0.0
    peak_abs = 0
    clip = 0
    for s in samples_s16:
        si = int(s)
        a = abs(si)
        if a > peak_abs:
            peak_abs = a
        if a >= CLIP_S16:
            clip += 1
        ssum += si
        ssq += si * si
    rms_fs = math.sqrt(ssq / n) / FULL_SCALE
    dc_fs = (ssum / n) / FULL_SCALE
    peak_fs = peak_abs / FULL_SCALE
    mean = ssum / n
    var_acc = 0.0
    for s in samples_s16:
        d = int(s) - mean
        var_acc += d * d
    variance_fs = (var_acc / n) / (FULL_SCALE * FULL_SCALE)
    ac_rms_fs = math.sqrt(max(variance_fs, 0.0))
    clip_ratio = clip / n
    rms_dbfs = 20.0 * math.log10(rms_fs) if rms_fs > 0.0 else float("-inf")
    snr_proxy = ac_rms_fs / (abs(dc_fs) + SNR_EPS)
    duration_ms = 1000.0 * n / sample_rate_hz
    verdict, code, message, soft = decide(
        n=n,
        rms_fs=rms_fs,
        peak_fs=peak_fs,
        dc_fs=dc_fs,
        variance_fs=variance_fs,
        clip_ratio=clip_ratio,
    )
    return {
        "n": n,
        "duration_ms": duration_ms,
        "rms_fs": rms_fs,
        "rms_dbfs": rms_dbfs,
        "peak_fs": peak_fs,
        "dc_fs": dc_fs,
        "variance_fs": variance_fs,
        "ac_rms_fs": ac_rms_fs,
        "clip_ratio": clip_ratio,
        "snr_proxy": snr_proxy,
        "verdict": verdict,
        "code": code,
        "message": message,
        "soft_flags": soft,
        "asr_allowed": verdict == "Pass",
    }


def decide(
    *,
    n: int,
    rms_fs: float,
    peak_fs: float,
    dc_fs: float,
    variance_fs: float,
    clip_ratio: float,
) -> tuple[str, str | None, str, list[str]]:
    """Match StreamPcmGate.decide order and codes."""
    if n < MIN_SAMPLES:
        return (
            "Reject",
            "LOW_ENERGY",
            f"buffer < {MIN_SAMPLES} samples ({n})",
            [],
        )
    if clip_ratio >= CLIP_REJECT:
        return (
            "Reject",
            "CLIPPED",
            f"clip_ratio={clip_ratio:.3f} ≥ {CLIP_REJECT}",
            [],
        )
    stuck = variance_fs < STUCK_VARIANCE_FS and peak_fs >= PEAK_REJECT_FS
    if abs(dc_fs) >= DC_REJECT_FS or stuck:
        return (
            "Reject",
            "BAD_MIC",
            f"dc_fs={dc_fs:.3f} variance_fs={variance_fs:.3g}",
            [],
        )
    if rms_fs < RMS_REJECT_FS or peak_fs < PEAK_REJECT_FS:
        return (
            "Reject",
            "LOW_ENERGY",
            f"rms_fs={rms_fs:.5f} peak_fs={peak_fs:.5f}",
            [],
        )
    soft: list[str] = []
    if clip_ratio >= CLIP_SOFT:
        soft.append("SOFT_CLIP")
    if rms_fs < RMS_SOFT_FS:
        soft.append("SOFT_LOW")
    if abs(dc_fs) >= DC_SOFT_FS:
        soft.append("SOFT_DC")
    return "Pass", None, "Pass", soft


def float_to_s16(samples) -> list[int]:
    """Match StreamPcmSynth.sine / generate_demo_stress_mix write_wav (×32767)."""
    out = []
    for x in samples:
        v = int(round(float(x) * 32767.0))
        if v > 32767:
            v = 32767
        elif v < -32767:
            v = -32767
        out.append(v)
    return out


def inspect_float(samples, sample_rate_hz: int = SAMPLE_RATE_HZ) -> dict[str, Any]:
    return inspect_s16(float_to_s16(samples), sample_rate_hz=sample_rate_hz)
