#!/usr/bin/env python3
"""Python mirror of Platform FarFieldGain.kt @ 5bfc582 / docs @ b66581c.

RMS-target 0.08 FS, max +24 dB, peak cap 0.95. Energy-VAD proxy: 20 ms frames,
fire if RMS ≥ 0.008 FS (not Silero JNI). Digital silence is not boosted.

Does not retune Eval DEMO_STAGE6 wavs. Used as ingest-only staging.
"""
from __future__ import annotations

import math
from typing import Any

import numpy as np

PLATFORM_FFGAIN_FEAT_SHA = "5bfc582d07bbc1556c77638e8bc958db1cc2f83d"
PLATFORM_FFGAIN_DOCS_SHA = "b66581c883d32f3195488cdd02680bdfa0b35038"

SAMPLE_RATE_HZ = 16_000
TARGET_RMS_FS = 0.08
MAX_GAIN_DB = 24.0
PEAK_LIMIT = 0.95
MIN_RMS_FOR_GAIN = 1e-5
FRAME_MS = 20
VAD_RMS_FS = 0.008
FRAME = int(SAMPLE_RATE_HZ * FRAME_MS / 1000)

REF_DISTANCE_M = 0.5
HVAC_RMS_FS = 0.004
CLOSE_SINE_AMP = 0.1
PHONE_LP_HZ = 2400.0
PHONE_LP_STAGES = 4
COMPRESS_DRIVE = 2.0
N_400MS = 6_400


def max_gain_linear() -> float:
    return 10.0 ** (MAX_GAIN_DB / 20.0)


def db_to_linear(db: float) -> float:
    return 10.0 ** (db / 20.0)


def linear_to_db(g: float) -> float:
    if g <= 0.0:
        return float("-inf")
    return 20.0 * math.log10(g)


def rms(samples) -> float:
    x = np.asarray(samples, dtype=np.float64).reshape(-1)
    if x.size == 0:
        return 0.0
    return float(np.sqrt(np.mean(np.square(x))))


def peak(samples) -> float:
    x = np.asarray(samples, dtype=np.float64).reshape(-1)
    if x.size == 0:
        return 0.0
    return float(np.max(np.abs(x)))


def energy_vad_fire_rate(
    samples,
    *,
    sample_rate_hz: int = SAMPLE_RATE_HZ,
    frame_ms: int = FRAME_MS,
    threshold_fs: float = VAD_RMS_FS,
) -> float:
    x = np.asarray(samples, dtype=np.float64).reshape(-1)
    frame = max(1, int((sample_rate_hz * frame_ms) / 1000))
    if x.size < frame:
        return 1.0 if rms(x) >= threshold_fs else 0.0
    n_frames = x.size // frame
    frames = x[: n_frames * frame].reshape(n_frames, frame)
    e = np.sqrt(np.mean(np.square(frames), axis=1))
    return float(np.mean(e >= threshold_fs)) if n_frames else 0.0


def stage(samples) -> dict[str, Any]:
    x = np.asarray(samples, dtype=np.float32).reshape(-1)
    n = int(x.size)
    if n == 0:
        return {
            "samples": x.copy(),
            "gain_linear": 1.0,
            "gain_db": 0.0,
            "rms_before": 0.0,
            "rms_after": 0.0,
            "peak_before": 0.0,
            "peak_after": 0.0,
            "clip_guard": False,
            "boosted": False,
            "vad_fire_before": 0.0,
            "vad_fire_after": 0.0,
        }
    rms_before = rms(x)
    peak_before = peak(x)
    vad_before = energy_vad_fire_rate(x)
    if rms_before < MIN_RMS_FOR_GAIN:
        return {
            "samples": x.copy(),
            "gain_linear": 1.0,
            "gain_db": 0.0,
            "rms_before": rms_before,
            "rms_after": rms_before,
            "peak_before": peak_before,
            "peak_after": peak_before,
            "clip_guard": False,
            "boosted": False,
            "vad_fire_before": vad_before,
            "vad_fire_after": vad_before,
        }
    desired = TARGET_RMS_FS / rms_before
    peak_cap = PEAK_LIMIT / max(peak_before, 1e-12)
    gain = min(desired, peak_cap, max_gain_linear())
    clip_guard = gain + 1e-12 < desired
    out = np.clip(x.astype(np.float32) * np.float32(gain), -PEAK_LIMIT, PEAK_LIMIT)
    return {
        "samples": out,
        "gain_linear": float(gain),
        "gain_db": linear_to_db(gain),
        "rms_before": rms_before,
        "rms_after": rms(out),
        "peak_before": peak_before,
        "peak_after": peak(out),
        "clip_guard": bool(clip_guard),
        "boosted": bool(gain > 1.0 + 1e-9),
        "vad_fire_before": vad_before,
        "vad_fire_after": energy_vad_fire_rate(out),
    }


def close_talk_sine(
    n: int = N_400MS,
    amplitude: float = CLOSE_SINE_AMP,
    hz: float = 1_000.0,
    sample_rate_hz: int = SAMPLE_RATE_HZ,
) -> np.ndarray:
    i = np.arange(n, dtype=np.float64)
    return (amplitude * np.sin(2.0 * math.pi * hz * i / sample_rate_hz)).astype(np.float32)


def at_distance(samples, distance_m: float, ref_m: float = REF_DISTANCE_M) -> np.ndarray:
    g = np.float32(ref_m / distance_m)
    return np.asarray(samples, dtype=np.float32).reshape(-1) * g


def hvac(n: int = N_400MS, target_rms: float = HVAC_RMS_FS, seed: int = 1) -> np.ndarray:
    state = seed & 0xFFFFFFFF
    raw = np.empty(n, dtype=np.float64)
    for i in range(n):
        state ^= (state << 13) & 0xFFFFFFFF
        state ^= (state >> 17) & 0xFFFFFFFF
        state ^= (state << 5) & 0xFFFFFFFF
        raw[i] = ((state % 7) - 3) / 32768.0
    r = rms(raw) or 1e-12
    return (raw * (target_rms / r)).astype(np.float32)


def phone_mic(samples, sample_rate_hz: int = SAMPLE_RATE_HZ) -> np.ndarray:
    xs = np.asarray(samples, dtype=np.float64).reshape(-1)
    a = 1.0 - math.exp(-2.0 * math.pi * PHONE_LP_HZ / sample_rate_hz)
    cur = xs.copy()
    for _ in range(PHONE_LP_STAGES):
        y = 0.0
        out = np.empty_like(cur)
        for i, x in enumerate(cur):
            y += a * (x - y)
            out[i] = y
        cur = out
    den = math.tanh(COMPRESS_DRIVE)
    return (np.tanh(COMPRESS_DRIVE * cur) / den).astype(np.float32)


def mix(a, b) -> np.ndarray:
    aa = np.asarray(a, dtype=np.float32).reshape(-1)
    bb = np.asarray(b, dtype=np.float32).reshape(-1)
    n = min(aa.size, bb.size)
    return aa[:n] + bb[:n]


def scene(distance_m: float, n: int = N_400MS) -> np.ndarray:
    far = at_distance(close_talk_sine(n), distance_m)
    return mix(phone_mic(far), hvac(n))
