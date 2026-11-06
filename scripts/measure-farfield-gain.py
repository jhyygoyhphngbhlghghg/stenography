#!/usr/bin/env python3
"""Measure DEMO_STAGE6 per-turn far-field gain.

Synthetic only — does **not** touch Eval DEMO_STAGE6 wavs.

Matches Kotlin FarFieldGain / FarFieldSynth:
  close-talk 1 kHz; 1/r distance; optional phone LPF+tanh + HVAC;
  energy-window segments; each quiet turn independently RMS-targets 0.08 FS
  (max +24 dB, peak 0.95). Loud turns never attenuated (gain ≥ 0 dB).

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
SEGMENT_ONSET = 0.0025
HANGOVER = 3
MIN_SEG_FRAMES = 4
DIP_RATIO = 0.4
DIP_FRAMES = 3
GAP_N = 3_200
NEAR_AMP = 0.34


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


def scene(distance_m: float, n: int = N) -> list[float]:
    far = at_distance(close_sine(n=n), distance_m)
    h = hvac(n)
    return [a + b for a, b in zip(phone_mic(far), h)]


def gain_for_segment(r0: float, p0: float) -> float:
    if r0 < MIN_RMS:
        return 1.0
    if r0 >= TARGET_RMS:
        return 1.0
    desired = TARGET_RMS / r0
    peak_cap = PEAK_LIMIT / max(p0, 1e-12)
    capped = min(desired, peak_cap, MAX_GAIN)
    return 1.0 if capped < 1.0 else capped


def active_rms(xs: list[float], start: int, end: int) -> float:
    """RMS of frames at/above onset so hangover zeros do not inflate gain."""
    acc = 0.0
    n = 0
    i = start
    while i + FRAME <= end:
        r = rms(xs[i : i + FRAME])
        if r >= SEGMENT_ONSET:
            chunk = xs[i : i + FRAME]
            acc += sum(x * x for x in chunk)
            n += FRAME
        i += FRAME
    return math.sqrt(acc / n) if n else rms(xs[start:end])


def find_segments(xs: list[float]) -> list[tuple[int, int]]:
    """Inclusive start, exclusive end."""
    n = len(xs)
    if n < FRAME:
        return [(0, n)] if rms(xs) >= SEGMENT_ONSET else []
    n_frames = n // FRAME
    rms_f = [rms(xs[f * FRAME : (f + 1) * FRAME]) for f in range(n_frames)]
    mask = [False] * n_frames
    hold = 0
    for f in range(n_frames):
        if rms_f[f] >= SEGMENT_ONSET:
            mask[f] = True
            hold = HANGOVER
        elif hold > 0:
            mask[f] = True
            hold -= 1
    raw: list[tuple[int, int]] = []
    f = 0
    while f < n_frames:
        if not mask[f]:
            f += 1
            continue
        a = f
        while f < n_frames and mask[f]:
            f += 1
        b = f - 1
        if b - a + 1 >= MIN_SEG_FRAMES:
            raw.append((a * FRAME, min((b + 1) * FRAME, n)))
    return split_dips(xs, raw)


def split_dips(xs: list[float], islands: list[tuple[int, int]]) -> list[tuple[int, int]]:
    out: list[tuple[int, int]] = []
    for start, end in islands:
        n_frames = max(1, (end - start) // FRAME)
        if n_frames < MIN_SEG_FRAMES * 2:
            out.append((start, end))
            continue
        rms_f = []
        peak_f = 0.0
        for f in range(n_frames):
            a = start + f * FRAME
            r = rms(xs[a : min(a + FRAME, end)])
            rms_f.append(r)
            if r > peak_f:
                peak_f = r
        dip = peak_f * DIP_RATIO
        cuts: list[int] = []
        f = MIN_SEG_FRAMES
        while f < n_frames - MIN_SEG_FRAMES:
            ok = True
            for k in range(DIP_FRAMES):
                if f + k >= n_frames or rms_f[f + k] >= dip:
                    ok = False
                    break
            if ok:
                cuts.append(f)
                f += DIP_FRAMES
            else:
                f += 1
        if not cuts:
            out.append((start, end))
            continue
        prev = 0
        for c in cuts:
            if c - prev >= MIN_SEG_FRAMES:
                a = start + prev * FRAME
                b = min(start + c * FRAME, end)
                out.append((a, b))
            prev = c + DIP_FRAMES
        if n_frames - prev >= MIN_SEG_FRAMES:
            out.append((start + prev * FRAME, end))
    return out or islands


def stage_per_segment(xs: list[float]) -> dict:
    r0 = rms(xs)
    p0 = peak(xs)
    v0 = vad_fire(xs)
    out = list(xs)
    segs = []
    any_boost = False
    any_clip = False
    max_g = 1.0
    for start, end in find_segments(xs):
        sl = xs[start:end]
        sr = active_rms(xs, start, end)
        sp = peak(sl)
        g = gain_for_segment(sr, sp)
        if g > max_g:
            max_g = g
        desired_boost = sr >= MIN_RMS and sr < TARGET_RMS
        clip_guard = desired_boost and g + 1e-12 < TARGET_RMS / max(sr, 1e-12)
        if clip_guard:
            any_clip = True
        if g > 1.0 + 1e-9:
            any_boost = True
        for i in range(start, end):
            out[i] = max(-PEAK_LIMIT, min(PEAK_LIMIT, xs[i] * g))
        after = out[start:end]
        segs.append(
            {
                "start": start,
                "end": end,
                "rms_before": sr,
                "rms_after": rms(after),
                "peak_before": sp,
                "gain_linear": g,
                "gain_db": 20.0 * math.log10(g) if g > 0 else float("-inf"),
                "boosted": g > 1.0 + 1e-9,
                "clip_guard": clip_guard,
            }
        )
    return {
        "gain_linear": max_g,
        "gain_db": 20.0 * math.log10(max_g) if max_g > 0 else float("-inf"),
        "rms_before": r0,
        "rms_after": rms(out),
        "peak_before": p0,
        "peak_after": peak(out),
        "clip_guard": any_clip,
        "boosted": any_boost,
        "vad_fire_before": v0,
        "vad_fire_after": vad_fire(out),
        "samples": out,
        "segments": segs,
        "loud_crushed": any(s["gain_linear"] < 1.0 - 1e-9 for s in segs),
    }


def stage_whole_file(xs: list[float]) -> dict:
    """Legacy whole-file AGC (Eval ffgain @ e0e64b85). Comparison only."""
    r0 = rms(xs)
    p0 = peak(xs)
    if r0 < MIN_RMS:
        g = 1.0
        desired = 1.0
    else:
        desired = TARGET_RMS / r0
        peak_cap = PEAK_LIMIT / max(p0, 1e-12)
        g = min(desired, peak_cap, MAX_GAIN)
    out = [max(-PEAK_LIMIT, min(PEAK_LIMIT, x * g)) for x in xs]
    return {
        "gain_linear": g,
        "gain_db": 20.0 * math.log10(g) if g > 0 else float("-inf"),
        "rms_before": r0,
        "rms_after": rms(out),
        "would_attenuate": g < 1.0 - 1e-12,
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


def hf_ratio(xs: list[float], fc: float = 2500.0) -> float:
    """First-order HP energy / total — spectral-tilt probe, not applied."""
    a = 1.0 - math.exp(-2.0 * math.pi * fc / SR)
    y = 0.0
    hp = []
    prev = 0.0
    for x in xs:
        y += a * (x - y)
        hp.append(x - y)
        prev = x
    _ = prev
    tot = rms(xs)
    if tot < 1e-12:
        return 0.0
    return rms(hp) / tot


def row(name: str, xs: list[float], distance_m: float | None = None) -> dict:
    st = stage_per_segment(xs)
    before_skip = inspect_hard_unbound(xs)
    after_skip = inspect_hard_unbound(st["samples"])
    return {
        "name": name,
        "n": len(xs),
        "distance_m": distance_m,
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
        "hard_skip_demo_after": None,
        "soft_energy_before": st["rms_before"] < 0.020,
        "peak_before": st["peak_before"],
        "peak_after": st["peak_after"],
        "n_segments": len(st["segments"]),
        "loud_crushed": st["loud_crushed"],
    }


def sequential_mix() -> tuple[list[float], list[dict]]:
    near = close_sine(amp=NEAR_AMP)
    g1 = [0.0] * GAP_N
    far5 = scene(5.0)
    g2 = [0.0] * GAP_N
    far6 = scene(6.0)
    parts = [
        ("near_3m", 3.0, near),
        ("gap1", 0.0, g1),
        ("far_5m", 5.0, far5),
        ("gap2", 0.0, g2),
        ("far_6m", 6.0, far6),
    ]
    pcm: list[float] = []
    turns = []
    o = 0
    for name, dist, p in parts:
        turns.append({"name": name, "distance_m": dist, "start": o, "end": o + len(p)})
        pcm.extend(p)
        o += len(p)
    return pcm, turns


def turn_row(name: str, distance_m: float, before: list[float], after: list[float]) -> dict:
    g = rms(after) / rms(before) if rms(before) > 1e-12 else 1.0
    gdb = 20.0 * math.log10(g) if g > 0 else float("-inf")
    return {
        "name": name,
        "distance_m": distance_m,
        "n": len(before),
        "rms_before": rms(before),
        "rms_after": rms(after),
        "gain_db": gdb,
        "boosted": g > 1.0 + 1e-9,
        "attenuated": g < 1.0 - 1e-9,
        "vad_fire_before": vad_fire(before),
        "vad_fire_after": vad_fire(after),
        "hard_skip_unbound_before": inspect_hard_unbound(before),
        "hard_skip_unbound_after": inspect_hard_unbound(after),
        "hf_ratio_before": hf_ratio(before),
        "hf_ratio_after": hf_ratio(after),
        "clears_reject_after": rms(after) >= VAD_RMS,
    }


def main() -> int:
    close = close_sine()
    rows = []
    for dist in (3.0, 4.0, 5.0, 6.0):
        far = at_distance(close, dist)
        rows.append(row(f"sine_{int(dist)}m", far, dist))
        rows.append(row(f"phone_hvac_{int(dist)}m", scene(dist), dist))
    rows.append(row("close_talk_0.5m", close, 0.5))
    rows.append(row("silence", [0.0] * N, None))

    pcm, turns = sequential_mix()
    whole = stage_whole_file(pcm)
    per = stage_per_segment(pcm)
    mix_turns = []
    for t in turns:
        if t["name"].startswith("gap"):
            continue
        sl0 = pcm[t["start"] : t["end"]]
        sl1 = per["samples"][t["start"] : t["end"]]
        mix_turns.append(turn_row(t["name"], t["distance_m"], sl0, sl1))

    quiet = [t for t in mix_turns if t["name"].startswith("far_")]
    loud = [t for t in mix_turns if t["name"].startswith("near_")]
    quiet_lifted = all(t["boosted"] and not t["attenuated"] for t in quiet)
    loud_not_crushed = all((not t["attenuated"]) and t["gain_db"] <= 1e-6 for t in loud)
    far_rms_ok = all(t["clears_reject_after"] for t in quiet)
    # Spectral tilt only if per-turn left quiet turns below floor.
    tilt_needed = not (quiet_lifted and far_rms_ok)
    demo_hard_skip = 0  # DEMO_STAGE6 never hard-drops energy on bound mix
    phone = [r for r in rows if r.get("distance_m") in (3.0, 4.0, 5.0, 6.0) and str(r["name"]).startswith("phone_hvac")]

    payload = {
        "sample_rate_hz": SR,
        "ref_distance_m": REF_M,
        "target_rms_fs": TARGET_RMS,
        "max_gain_db": MAX_GAIN_DB,
        "peak_limit": PEAK_LIMIT,
        "vad_rms_fs": VAD_RMS,
        "frame_ms": 20,
        "hvac_rms_fs": HVAC_RMS,
        "segment_onset_fs": SEGMENT_ONSET,
        "mode": "per_segment",
        "eval_fixtures_untouched": True,
        "demo_claim": "DEMO_STAGE6 single phone-mic 3-6m; Path B <=20 optional",
        "eval_stage6_cite": "a8b39d5a 3m ideal; 4-6m WER~1 s5 dropped — not re-run here",
        "eval_ffgain_cite": "e0e64b85 agg WER 0.73->0.70; mix 0.119 FS whole-file AGC -3.46 dB; s5 missing — not re-run here",
        "summary": {
            "phone_hvac_distances_m": [3, 4, 5, 6],
            "phone_hvac_hard_skip_demo": 0,
            "phone_hvac_soft_energy_before": sum(1 for r in phone if r["soft_energy_before"]),
            "phone_hvac_unbound_hard_skip_before": sum(
                1 for r in phone if r["hard_skip_unbound_before"] == "LOW_ENERGY"
            ),
            "mix_file_rms_before": rms(pcm),
            "mix_whole_file_agc_gain_db": whole["gain_db"],
            "mix_whole_file_would_attenuate": whole["would_attenuate"],
            "mix_per_turn_loud_crushed": per["loud_crushed"],
            "mix_quiet_turns_lifted": quiet_lifted,
            "mix_loud_turns_not_crushed": loud_not_crushed,
            "mix_hard_skip_demo": demo_hard_skip,
            "spectral_tilt_applied": False,
            "spectral_tilt_needed": tilt_needed,
            "spectral_tilt_reason": (
                "per-turn RMS lift put quiet 5-6 m turns above VAD floor; no tilt"
                if not tilt_needed
                else "per-turn left a quiet turn below floor — tilt not auto-applied"
            ),
        },
        "sequential_mix": {
            "n": len(pcm),
            "rms_before": rms(pcm),
            "rms_after": per["rms_after"],
            "n_segments": len(per["segments"]),
            "whole_file_agc": whole,
            "turns": mix_turns,
            "segments": [
                {k: v for k, v in s.items() if k != "samples"} for s in per["segments"]
            ],
        },
        "rows": rows,
    }
    text = json.dumps(payload, indent=2)
    print(text)
    print("\n# sequential mix per-turn RMS", file=sys.stderr)
    print(
        f"{'turn':<10} {'rms_before':>12} {'rms_after':>12} {'gain_dB':>8} {'vad':>12} {'skip':>12}",
        file=sys.stderr,
    )
    for t in mix_turns:
        print(
            f"{t['name']:<10} {t['rms_before']:12.4f} {t['rms_after']:12.4f} "
            f"{t['gain_db']:8.2f} {t['vad_fire_before']:.2f}->{t['vad_fire_after']:.2f} "
            f"{(t['hard_skip_unbound_after'] or 'none'):>12}",
            file=sys.stderr,
        )
    print(
        f"whole-file AGC would be {whole['gain_db']:.2f} dB "
        f"(attenuate={whole['would_attenuate']}); "
        f"per-turn crushed={per['loud_crushed']} demo_hard_skip={demo_hard_skip} "
        f"tilt={payload['summary']['spectral_tilt_applied']}",
        file=sys.stderr,
    )
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
