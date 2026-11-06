#!/usr/bin/env python3
"""Measure far-field gain staging at simulated 3 m and 6 m.

Synthetic only — does **not** touch Eval DEMO_STAGE6 wavs.

Matches Kotlin FarFieldGain / FarFieldSynth:
  close-talk 1 kHz peak 0.1 at 0.5 m ref; 1/r distance; optional phone LPF+tanh
  + HVAC; then RMS-target gain with peak clip guard.

  python3 scripts/measure-farfield-gain.py --record
"""
from __future__ import annotations

import json
import math
import os
import sys

SR = 16_000
N = int(0.4 * SR)
FS = 32768.0
REF_M = 0.5
TARGET_RMS = 0.08
MAX_GAIN_DB = 24.0
MAX_GAIN = 10 ** (MAX_GAIN_DB / 20.0)
PEAK_LIMIT = 0.95
MIN_RMS = 1e-5
VAD_RMS = 0.008
FRAME = int(SR * 0.020)
CLIP_S16 = 32760
HVAC_RMS = 0.004


def rms(xs: list[float]) -> float:
    if not xs:
        return 0.0
    return math.sqrt(sum(x * x for x in xs) / len(xs))


def peak(xs: list[float]) -> float:
    return max((abs(x) for x in xs), default=0.0)


def vad_fire(xs: list[float]) -> float:
    if len(xs) < FRAME:
        return 1.0 if rms(xs) >= VAD_RMS else 0.0
    fires = 0
    frames = 0
    off = 0
    while off + FRAME <= len(xs):
        chunk = xs[off : off + FRAME]
        if rms(chunk) >= VAD_RMS:
            fires += 1
        frames += 1
        off += FRAME
    return fires / frames if frames else 0.0


def close_sine(amp: float = 0.1, hz: float = 1000.0, n: int = N) -> list[float]:
    return [amp * math.sin(2.0 * math.pi * hz * i / SR) for i in range(n)]


def at_distance(xs: list[float], distance_m: float, ref_m: float = REF_M) -> list[float]:
    g = ref_m / distance_m
    return [x * g for x in xs]


def hvac(n: int = N, target: float = HVAC_RMS, seed: int = 1) -> list[float]:
    state = seed & 0xFFFFFFFF
    raw = []
    for _ in range(n):
        state ^= (state << 13) & 0xFFFFFFFF
        state ^= (state >> 17) & 0xFFFFFFFF
        state ^= (state << 5) & 0xFFFFFFFF
        raw.append(((state % 7) - 3) / 32768.0)
    r = rms(raw) or 1e-12
    g = target / r
    return [x * g for x in raw]


def phone_mic(xs: list[float], fc: float = 2400.0, stages: int = 4, drive: float = 2.0) -> list[float]:
    a = 1.0 - math.exp(-2.0 * math.pi * fc / SR)
    cur = list(xs)
    for _ in range(stages):
        y = 0.0
        out = []
        for x in cur:
            y += a * (x - y)
            out.append(y)
        cur = out
    den = math.tanh(drive)
    return [math.tanh(drive * x) / den for x in cur]


def stage(xs: list[float]) -> dict:
    r0 = rms(xs)
    p0 = peak(xs)
    v0 = vad_fire(xs)
    if r0 < MIN_RMS:
        return {
            "gain_linear": 1.0,
            "gain_db": 0.0,
            "rms_before": r0,
            "rms_after": r0,
            "peak_before": p0,
            "peak_after": p0,
            "clip_guard": False,
            "boosted": False,
            "vad_fire_before": v0,
            "vad_fire_after": v0,
            "samples": list(xs),
        }
    desired = TARGET_RMS / r0
    peak_cap = PEAK_LIMIT / max(p0, 1e-12)
    g = min(desired, peak_cap, MAX_GAIN)
    out = [max(-PEAK_LIMIT, min(PEAK_LIMIT, x * g)) for x in xs]
    return {
        "gain_linear": g,
        "gain_db": 20.0 * math.log10(g) if g > 0 else float("-inf"),
        "rms_before": r0,
        "rms_after": rms(out),
        "peak_before": p0,
        "peak_after": peak(out),
        "clip_guard": g < desired - 1e-12,
        "boosted": g > 1.0 + 1e-9,
        "vad_fire_before": v0,
        "vad_fire_after": vad_fire(out),
        "samples": out,
    }


def to_s16(xs: list[float]) -> list[int]:
    out = []
    for x in xs:
        s = int(round(max(-1.0, min(1.0, x)) * 32767.0))
        out.append(max(-32767, min(32767, s)))
    return out


def inspect_hard_unbound(xs: list[float]) -> str | None:
    """Lightweight unbound energy/clip/dc skip (same floors as StreamPcmGate)."""
    s16 = to_s16(xs)
    n = len(s16)
    if n == 0:
        return "LOW_ENERGY"
    ssq = sum(s * s for s in s16)
    r = math.sqrt(ssq / n) / FS
    pk = max(abs(s) for s in s16) / FS
    clip = sum(1 for s in s16 if abs(s) >= CLIP_S16) / n
    dc = (sum(s16) / n) / FS
    if clip >= 0.02:
        return "CLIPPED"
    if abs(dc) >= 0.20:
        return "BAD_MIC"
    if r < 0.008 or pk < 0.012:
        return "LOW_ENERGY"
    return None


def row(name: str, xs: list[float]) -> dict:
    st = stage(xs)
    before_skip = inspect_hard_unbound(xs)
    after_skip = inspect_hard_unbound(st["samples"])
    return {
        "name": name,
        "n": len(xs),
        "distance_m": None,
        "rms_before": st["rms_before"],
        "rms_after": st["rms_after"],
        "rms_before_dbfs": 20 * math.log10(st["rms_before"]) if st["rms_before"] else float("-inf"),
        "rms_after_dbfs": 20 * math.log10(st["rms_after"]) if st["rms_after"] else float("-inf"),
        "gain_db": st["gain_db"],
        "clip_guard": st["clip_guard"],
        "vad_fire_before": st["vad_fire_before"],
        "vad_fire_after": st["vad_fire_after"],
        "hard_skip_unbound_before": before_skip,
        "hard_skip_unbound_after": after_skip,
        "hard_skip_demo_after": None,  # DEMO_STAGE6 never hard-drops energy/band-limit
        "soft_energy_before": st["rms_before"] < 0.020,
        "peak_before": st["peak_before"],
        "peak_after": st["peak_after"],
    }


def main() -> int:
    close = close_sine()
    rows = []
    for dist in (3.0, 4.0, 5.0, 6.0):
        far = at_distance(close, dist)
        r = row(f"sine_{int(dist)}m", far)
        r["distance_m"] = dist
        rows.append(r)
        scene = [a + b for a, b in zip(phone_mic(far), hvac())]
        r2 = row(f"phone_hvac_{int(dist)}m", scene)
        r2["distance_m"] = dist
        rows.append(r2)
    rows.append(row("close_talk_0.5m", close))
    rows.append(row("silence", [0.0] * N))
    far = [r for r in rows if r.get("distance_m") in (3.0, 4.0, 5.0, 6.0) and str(r["name"]).startswith("phone_hvac")]
    payload = {
        "sample_rate_hz": SR,
        "ref_distance_m": REF_M,
        "target_rms_fs": TARGET_RMS,
        "max_gain_db": MAX_GAIN_DB,
        "peak_limit": PEAK_LIMIT,
        "vad_rms_fs": VAD_RMS,
        "frame_ms": 20,
        "hvac_rms_fs": HVAC_RMS,
        "eval_fixtures_untouched": True,
        "demo_claim": "DEMO_STAGE6 single phone-mic 3-6m; Path B <=20 optional",
        "eval_stage6_cite": "a8b39d5a 3m ideal; 4-6m WER~1 s5 dropped — not re-run here",
        "summary": {
            "phone_hvac_distances_m": [3, 4, 5, 6],
            "phone_hvac_hard_skip_demo": 0,
            "phone_hvac_soft_energy_before": sum(1 for r in far if r["soft_energy_before"]),
            "phone_hvac_unbound_hard_skip_before": sum(
                1 for r in far if r["hard_skip_unbound_before"] == "LOW_ENERGY"
            ),
        },
        "rows": rows,
    }
    text = json.dumps(payload, indent=2)
    print(text)
    if "--record" in sys.argv:
        dest = os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            "artifacts",
            "test-results",
            "farfield-gain.json",
        )
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "w", encoding="utf-8") as f:
            f.write(text + "\n")
        print("wrote", dest, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
