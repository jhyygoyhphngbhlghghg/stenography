#!/usr/bin/env python3
"""Python mirror of StreamPcmGate.kt v2.

Source artifact (byte-identical Kotlin):
  Implementer PR #6 e3ad9085b58b563a4feff5932396909c09e18fe2
  == Platform b12a2bbbf3c5b7770063c21a2e36d2c63b88d5c5
  path: src/android/app/src/main/java/team/mikcoders/neurosteno/present/StreamPcmGate.kt
  sha256: 3caa3a6a2d4aaf0ed9ed0592847ab7c9aadfbe8b5916eb95136745c293d26c51

Path B pre-ASR gate on one stream's pcm_s16le.
Bound known seats (s01..s20): near/below RMS floor is SOFT_LOW — still ASR.
Hard LOW_ENERGY only for unbound streams or empty (n==0).
Phone-mic crush (no hard clip/DC): Goertzel HF/speech occupancy → BAND_LIMITED.

Do not retune constants. Eval fixture wavs are not modified here.
"""
from __future__ import annotations

import math
from typing import Any

IMPLEMENTER_GATE_V2_SHA = "e3ad9085b58b563a4feff5932396909c09e18fe2"
PLATFORM_GATE_V2_SHA = "b12a2bbbf3c5b7770063c21a2e36d2c63b88d5c5"
STREAM_PCM_GATE_KT_SHA256 = "3caa3a6a2d4aaf0ed9ed0592847ab7c9aadfbe8b5916eb95136745c293d26c51"

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

GOERTZEL_HZ = (
    250.0, 500.0, 750.0, 1000.0, 1500.0, 2000.0, 2500.0, 3000.0, 3500.0,
    4000.0, 5000.0, 6000.0, 7000.0,
)
SPEECH_BANDS = 9
OCCUPANCY_FRAC = 0.05
BAND_LIMITED_HF_RATIO = 0.08
BAND_LIMITED_SPEECH_OCC = 2
BAND_LIMITED_HF_OCC = 0

PHONE_HZ = (400.0, 1000.0, 2000.0, 3000.0)
BROADBAND_HZ = (400.0, 1000.0, 2000.0, 3500.0, 5000.0, 7000.0)


def goertzel_power(samples: list[float], freq: float, sr: int) -> float:
    w = 2.0 * math.pi * freq / sr
    coeff = 2.0 * math.cos(w)
    s1 = 0.0
    s2 = 0.0
    for x in samples:
        s0 = x + coeff * s1 - s2
        s2 = s1
        s1 = s0
    return s1 * s1 + s2 * s2 - coeff * s1 * s2


def goertzel_powers(samples: list[float], freqs=GOERTZEL_HZ, sr: int = SAMPLE_RATE_HZ) -> list[float]:
    """One pass over samples, 13 bins — same recurrence as Kotlin goertzelPower."""
    coeffs = [2.0 * math.cos(2.0 * math.pi * freq / sr) for freq in freqs]
    s1 = [0.0] * len(freqs)
    s2 = [0.0] * len(freqs)
    n_f = len(freqs)
    for x in samples:
        for i in range(n_f):
            s0 = x + coeffs[i] * s1[i] - s2[i]
            s2[i] = s1[i]
            s1[i] = s0
    return [
        s1[i] * s1[i] + s2[i] * s2[i] - coeffs[i] * s1[i] * s2[i] for i in range(n_f)
    ]


def band_energy(xs: list[float], sample_rate_hz: int) -> dict[str, Any]:
    powers = goertzel_powers(xs, GOERTZEL_HZ, sample_rate_hz)
    total = sum(powers)
    peak_p = max(powers) if powers else 0.0
    thresh = OCCUPANCY_FRAC * peak_p
    occ_speech = 0
    occ_hf = 0
    speech = 0.0
    hf = 0.0
    for i, p in enumerate(powers):
        if i < SPEECH_BANDS:
            speech += p
            if p > thresh:
                occ_speech += 1
        else:
            hf += p
            if p > thresh:
                occ_hf += 1
    return {
        "hf_ratio": hf / (total + SNR_EPS),
        "speech_ratio": speech / (total + SNR_EPS),
        "speech_occupancy": occ_speech,
        "hf_occupancy": occ_hf,
    }


def is_band_limited(rms_fs: float, bands: dict[str, Any]) -> bool:
    if rms_fs < RMS_SOFT_FS:
        return False
    return (
        bands["hf_ratio"] < BAND_LIMITED_HF_RATIO
        and bands["speech_occupancy"] >= BAND_LIMITED_SPEECH_OCC
        and bands["hf_occupancy"] <= BAND_LIMITED_HF_OCC
    )


def _soft_flags(*, clip_ratio: float, below_floor: bool, rms_fs: float, dc_fs: float) -> list[str]:
    soft: list[str] = []
    if clip_ratio >= CLIP_SOFT:
        soft.append("SOFT_CLIP")
    if below_floor or rms_fs < RMS_SOFT_FS:
        soft.append("SOFT_LOW")
    if abs(dc_fs) >= DC_SOFT_FS:
        soft.append("SOFT_DC")
    return soft


def decide_v1(
    *,
    n: int,
    rms_fs: float,
    peak_fs: float,
    dc_fs: float,
    variance_fs: float,
    clip_ratio: float,
) -> tuple[str, str | None, str, list[str]]:
    """Match StreamPcmGate.decide @ f5ff5e2 / dae416f (no BAND_LIMITED, no boundSeat)."""
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
    below_floor = n < MIN_SAMPLES or rms_fs < RMS_REJECT_FS or peak_fs < PEAK_REJECT_FS
    if below_floor:
        return (
            "Reject",
            "LOW_ENERGY",
            f"rms_fs={rms_fs:.5f} peak_fs={peak_fs:.5f} n={n}",
            [],
        )
    return "Pass", None, "Pass", _soft_flags(
        clip_ratio=clip_ratio, below_floor=False, rms_fs=rms_fs, dc_fs=dc_fs
    )


def decide(
    *,
    n: int,
    rms_fs: float,
    peak_fs: float,
    dc_fs: float,
    variance_fs: float,
    clip_ratio: float,
    bands: dict[str, Any] | None,
    bound_seat: bool,
) -> tuple[str, str | None, str, list[str]]:
    """Match StreamPcmGate.decide @ e3ad908 / b12a2bb (sha256 STREAM_PCM_GATE_KT_SHA256)."""
    bands = bands or {
        "hf_ratio": 0.0,
        "speech_ratio": 0.0,
        "speech_occupancy": 0,
        "hf_occupancy": 0,
    }
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
    if n >= MIN_SAMPLES and is_band_limited(rms_fs, bands):
        return (
            "Reject",
            "BAND_LIMITED",
            f"hf_ratio={bands['hf_ratio']:.4f} occ_speech={bands['speech_occupancy']} occ_hf={bands['hf_occupancy']}",
            [],
        )
    below_floor = n < MIN_SAMPLES or rms_fs < RMS_REJECT_FS or peak_fs < PEAK_REJECT_FS
    if below_floor and not bound_seat:
        return (
            "Reject",
            "LOW_ENERGY",
            f"rms_fs={rms_fs:.5f} peak_fs={peak_fs:.5f} n={n} unbound",
            [],
        )
    return "Pass", None, "Pass", _soft_flags(
        clip_ratio=clip_ratio, below_floor=below_floor, rms_fs=rms_fs, dc_fs=dc_fs
    )


def inspect_s16(
    samples_s16,
    sample_rate_hz: int = SAMPLE_RATE_HZ,
    *,
    bound_seat: bool = False,
    version: int = 2,
) -> dict[str, Any]:
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
            "hf_ratio": 0.0,
            "speech_occupancy": 0,
            "hf_occupancy": 0,
            "verdict": "Reject",
            "code": "LOW_ENERGY",
            "message": "empty pcm",
            "soft_flags": [],
            "asr_allowed": False,
            "bound_seat": bound_seat,
            "version": version,
        }
    try:
        import numpy as np

        arr = np.asarray(samples_s16, dtype=np.int16)
        n = int(arr.size)
        acc = arr.astype(np.int64)
        ssum = float(acc.sum())
        ssq = float(np.square(acc).sum())
        peak_abs = int(np.max(np.abs(acc)))
        clip = int(np.count_nonzero(np.abs(acc) >= CLIP_S16))
        xs = (arr.astype(np.float64) / FULL_SCALE).tolist()
        mean = ssum / n
        var_acc = float(np.sum((arr.astype(np.float64) - mean) ** 2))
    except Exception:
        xs = []
        ssum = 0.0
        ssq = 0.0
        peak_abs = 0
        clip = 0
        for s in samples_s16:
            si = int(s)
            xs.append(si / FULL_SCALE)
            a = abs(si)
            if a > peak_abs:
                peak_abs = a
            if a >= CLIP_S16:
                clip += 1
            ssum += si
            ssq += si * si
        mean = ssum / n
        var_acc = 0.0
        for s in samples_s16:
            d = int(s) - mean
            var_acc += d * d
    rms_fs = math.sqrt(ssq / n) / FULL_SCALE
    dc_fs = (ssum / n) / FULL_SCALE
    peak_fs = peak_abs / FULL_SCALE
    variance_fs = (var_acc / n) / (FULL_SCALE * FULL_SCALE)
    ac_rms_fs = math.sqrt(max(variance_fs, 0.0))
    clip_ratio = clip / n
    rms_dbfs = 20.0 * math.log10(rms_fs) if rms_fs > 0.0 else float("-inf")
    snr_proxy = ac_rms_fs / (abs(dc_fs) + SNR_EPS)
    need_bands = version >= 2 and n >= MIN_SAMPLES
    bands = band_energy(xs, sample_rate_hz) if need_bands else {
        "hf_ratio": 0.0,
        "speech_ratio": 0.0,
        "speech_occupancy": 0,
        "hf_occupancy": 0,
    }
    duration_ms = 1000.0 * n / sample_rate_hz
    if version >= 2:
        verdict, code, message, soft = decide(
            n=n,
            rms_fs=rms_fs,
            peak_fs=peak_fs,
            dc_fs=dc_fs,
            variance_fs=variance_fs,
            clip_ratio=clip_ratio,
            bands=bands,
            bound_seat=bound_seat,
        )
    else:
        verdict, code, message, soft = decide_v1(
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
        "hf_ratio": bands["hf_ratio"],
        "speech_occupancy": bands["speech_occupancy"],
        "hf_occupancy": bands["hf_occupancy"],
        "verdict": verdict,
        "code": code,
        "message": message,
        "soft_flags": soft,
        "asr_allowed": verdict == "Pass",
        "bound_seat": bound_seat,
        "version": version,
    }


def float_to_s16(samples) -> list[int]:
    out = []
    for x in samples:
        v = int(round(float(x) * 32767.0))
        if v > 32767:
            v = 32767
        elif v < -32767:
            v = -32767
        out.append(v)
    return out


def inspect_float(
    samples,
    sample_rate_hz: int = SAMPLE_RATE_HZ,
    *,
    bound_seat: bool = False,
    version: int = 2,
) -> dict[str, Any]:
    return inspect_s16(
        float_to_s16(samples),
        sample_rate_hz=sample_rate_hz,
        bound_seat=bound_seat,
        version=version,
    )


def tone_stack_s16(freqs, amplitude: float = 0.08, n: int = 6400, sr: int = SAMPLE_RATE_HZ) -> list[int]:
    """Match StreamPcmSynth.toneStack @ e3ad908."""
    xs = [0.0] * n
    for hz in freqs:
        for i in range(n):
            xs[i] += amplitude * math.sin(2.0 * math.pi * hz * i / sr)
    return [max(-32767, min(32767, int(round(x * 32767.0)))) for x in xs]


def phone_crush_lpf_s16(
    freqs=BROADBAND_HZ,
    amplitude: float = 0.08,
    fc: float = 2400.0,
    stages: int = 4,
    n: int = 6400,
    sr: int = SAMPLE_RATE_HZ,
) -> list[int]:
    """Match StreamPcmSynth.phoneCrushLpf @ e3ad908."""
    xs = [0.0] * n
    for hz in freqs:
        for i in range(n):
            xs[i] += amplitude * math.sin(2.0 * math.pi * hz * i / sr)
    a = 1.0 - math.exp(-2.0 * math.pi * fc / sr)
    for _ in range(stages):
        y = 0.0
        for i in range(n):
            y += a * (xs[i] - y)
            xs[i] = y
    return [max(-32767, min(32767, int(round(x * 32767.0)))) for x in xs]
