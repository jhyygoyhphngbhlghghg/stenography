#!/usr/bin/env python3
"""Measure RMS / clip / DC / variance of the synthetic PCM fixtures used by
StreamPcmGate (Path B pre-ASR reject). Does **not** touch Eval wavs.

Units: pcm_s16le mono 16 kHz; RMS/peak/DC as fraction of full-scale (32768).
dBFS = 20*log10(rms) when rms > 0.

  python3 scripts/measure-stream-pcm-gate.py
"""
from __future__ import annotations

import json
import math
import os
import sys

SR = 16_000
FS = 32768.0
CLIP_S16 = 32760
N = int(0.4 * SR)  # 400 ms
SPEECH_HZ = (250.0, 500.0, 750.0, 1000.0, 1500.0, 2000.0, 2500.0, 3000.0, 3500.0)
HF_HZ = (4000.0, 5000.0, 6000.0, 7000.0)
GOERTZEL_HZ = SPEECH_HZ + HF_HZ
OCCUPANCY_FRAC = 0.05


def goertzel_power(samples: list[float], freq: float, sr: int = SR) -> float:
    w = 2.0 * math.pi * freq / sr
    coeff = 2.0 * math.cos(w)
    s1 = 0.0
    s2 = 0.0
    for x in samples:
        s0 = x + coeff * s1 - s2
        s2 = s1
        s1 = s0
    return s1 * s1 + s2 * s2 - coeff * s1 * s2


def band_stats(samples: list[int]) -> dict:
    xs = [s / FS for s in samples]
    powers = [goertzel_power(xs, f) for f in GOERTZEL_HZ]
    total = sum(powers) + 1e-18
    speech = sum(powers[: len(SPEECH_HZ)])
    hf = sum(powers[len(SPEECH_HZ) :])
    peak_p = max(powers) if powers else 0.0
    thresh = OCCUPANCY_FRAC * peak_p
    occ_speech = sum(1 for p in powers[: len(SPEECH_HZ)] if p > thresh)
    occ_hf = sum(1 for p in powers[len(SPEECH_HZ) :] if p > thresh)
    return {
        "hf_ratio": hf / total,
        "speech_ratio": speech / total,
        "speech_occupancy": occ_speech,
        "hf_occupancy": occ_hf,
    }


def stats(name: str, samples: list[int]) -> dict:
    n = len(samples)
    if n == 0:
        return {"name": name, "n": 0}
    ssum = float(sum(samples))
    ssq = float(sum(s * s for s in samples))
    dc = (ssum / n) / FS
    rms = math.sqrt(ssq / n) / FS
    peak = max(abs(s) for s in samples) / FS
    mean = ssum / n
    var = sum((s - mean) ** 2 for s in samples) / n
    var_fs = var / (FS * FS)
    clip = sum(1 for s in samples if abs(s) >= CLIP_S16) / n
    ac = math.sqrt(max(var_fs, 0.0))
    dbfs = 20.0 * math.log10(rms) if rms > 0 else float("-inf")
    bands = band_stats(samples)
    return {
        "name": name,
        "n": n,
        "duration_ms": round(1000.0 * n / SR, 3),
        "rms_fs": rms,
        "rms_dbfs": dbfs,
        "peak_fs": peak,
        "dc_fs": dc,
        "variance_fs": var_fs,
        "ac_rms_fs": ac,
        "clip_ratio": clip,
        "crest": (peak / rms) if rms > 0 else None,
        **bands,
    }


def sine(amp: float, hz: float = 1000.0, n: int = N) -> list[int]:
    out = []
    for i in range(n):
        x = amp * math.sin(2.0 * math.pi * hz * i / SR)
        s = int(round(x * 32767.0))
        out.append(max(-32767, min(32767, s)))
    return out


def to_s16(xs: list[float]) -> list[int]:
    out = []
    for x in xs:
        s = int(round(x * 32767.0))
        out.append(max(-32767, min(32767, s)))
    return out


def tone_stack(freqs: tuple[float, ...], amp: float = 0.08, n: int = N) -> list[int]:
    xs = [0.0] * n
    for hz in freqs:
        for i in range(n):
            xs[i] += amp * math.sin(2.0 * math.pi * hz * i / SR)
    return to_s16(xs)


def one_pole_lp(xs: list[float], fc: float) -> list[float]:
    a = 1.0 - math.exp(-2.0 * math.pi * fc / SR)
    y = 0.0
    out = []
    for x in xs:
        y += a * (x - y)
        out.append(y)
    return out


def phone_crush_lpf(freqs: tuple[float, ...], amp: float = 0.08, fc: float = 2400.0, stages: int = 4, n: int = N) -> list[int]:
    xs = [0.0] * n
    for hz in freqs:
        for i in range(n):
            xs[i] += amp * math.sin(2.0 * math.pi * hz * i / SR)
    for _ in range(stages):
        xs = one_pole_lp(xs, fc)
    return to_s16(xs)


def tiny_noise(n: int = N, seed: int = 1) -> list[int]:
    # xorshift-ish, matches StreamPcmSynth.tinyNoise
    state = seed & 0xFFFFFFFF
    out = []
    for _ in range(n):
        state ^= (state << 13) & 0xFFFFFFFF
        state ^= (state >> 17) & 0xFFFFFFFF
        state ^= (state << 5) & 0xFFFFFFFF
        out.append((state % 7) - 3)  # -3..+3 LSB
    return out


def main() -> int:
    broadband = (400.0, 1000.0, 2000.0, 3500.0, 5000.0, 7000.0)
    phone_stack = (400.0, 1000.0, 2000.0, 3000.0)
    rows = [
        stats("silence", [0] * N),
        stats("tiny_noise", tiny_noise()),
        stats("clipped_square", [32767 if i % 2 == 0 else -32767 for i in range(N)]),
        stats("clean_tone_-20dBpeak", sine(0.1)),
        stats("low_peak_tone", sine(0.004)),
        stats("stuck_dc", [8000] * N),
        stats("extreme_dc", [20000] * N),
        stats("broadband_stack", tone_stack(broadband)),
        stats("phone_crush_stack", tone_stack(phone_stack)),
        stats("phone_crush_lpf", phone_crush_lpf(broadband)),
        stats("empty", []),
    ]
    payload = {
        "sample_rate_hz": SR,
        "full_scale": 32768,
        "n": N,
        "clip_s16": CLIP_S16,
        "goertzel_hz": list(GOERTZEL_HZ),
        "occupancy_frac": OCCUPANCY_FRAC,
        "thresholds": {
            "rms_reject_fs": 0.008,
            "rms_reject_dbfs": -41.94,
            "rms_soft_fs": 0.020,
            "peak_reject_fs": 0.012,
            "clip_reject": 0.02,
            "dc_reject_fs": 0.20,
            "stuck_variance_fs": 1e-7,
            "min_samples": 800,
            "band_limited_hf_ratio": 0.08,
            "band_limited_speech_occupancy": 3,
            "band_limited_hf_occupancy": 0,
            "bound_seat_policy": "n==0 still LOW_ENERGY; near-silence SOFT_LOW; crush soft BAND_LIMITED; s01 never hard-dropped for energy/band-limit",
        },
        "eval_fixtures_untouched": True,
        "rows": rows,
    }
    text = json.dumps(payload, indent=2)
    print(text)
    if "--record" in sys.argv:
        dest = os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            "artifacts",
            "test-results",
            "stream-pcm-gate.json",
        )
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "w", encoding="utf-8") as f:
            f.write(text + "\n")
        print("wrote", dest, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
