#!/usr/bin/env python3
"""Python mirror of Platform FarFieldGain.kt.

Headline @ ae86903 / db3afe4: **per-turn** energy windows. Each quiet island
independently targets RMS 0.08 FS (max +24 dB, peak 0.95). Loud turns stay at
0 dB (never crush). Whole-file AGC (`stage_whole_file`, 5bfc582 / b66581c)
attenuates when a near seat already sits ~0.12 FS — kept only for the
historical DEMO_STAGE6_ffgain row.

Does not retune Eval DEMO_STAGE6 wavs. Ingest-only staging.
"""
from __future__ import annotations

import math
from typing import Any

import numpy as np

PLATFORM_FFGAIN_WHOLEFILE_FEAT_SHA = "5bfc582d07bbc1556c77638e8bc958db1cc2f83d"
PLATFORM_FFGAIN_WHOLEFILE_DOCS_SHA = "b66581c883d32f3195488cdd02680bdfa0b35038"
PLATFORM_FFGAIN_FEAT_SHA = "ae8690303d12db51f5c23d14fdcf87b6e50a10f2"
PLATFORM_FFGAIN_DOCS_SHA = "db3afe44f7c02db7abb933a3be0b94fce7ef526a"

SAMPLE_RATE_HZ = 16_000
TARGET_RMS_FS = 0.08
MAX_GAIN_DB = 24.0
PEAK_LIMIT = 0.95
MIN_RMS_FOR_GAIN = 1e-5
FRAME_MS = 20
VAD_RMS_FS = 0.008
SEGMENT_ONSET_FS = 0.0025
HANGOVER_FRAMES = 3
MIN_SEGMENT_FRAMES = 4
DIP_RATIO = 0.4
DIP_FRAMES = 3
FRAME = int(SAMPLE_RATE_HZ * FRAME_MS / 1000)

REF_DISTANCE_M = 0.5
HVAC_RMS_FS = 0.004
CLOSE_SINE_AMP = 0.1
PHONE_LP_HZ = 2400.0
PHONE_LP_STAGES = 4
COMPRESS_DRIVE = 2.0
N_400MS = 6_400
LOUD_NEAR_AMP = 0.34
GAP_N = 3_200


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


def _empty_result(x: np.ndarray) -> dict[str, Any]:
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
        "segments": [],
        "loud_crushed": False,
        "mode": "per_turn",
    }


def gain_for_segment(rms_fs: float, peak_fs: float) -> float:
    if rms_fs < MIN_RMS_FOR_GAIN:
        return 1.0
    if rms_fs >= TARGET_RMS_FS:
        return 1.0
    desired = TARGET_RMS_FS / rms_fs
    peak_cap = PEAK_LIMIT / max(peak_fs, 1e-12)
    capped = min(desired, peak_cap, max_gain_linear())
    return 1.0 if capped < 1.0 else float(capped)


def _rms_range(x: np.ndarray, start: int, end: int) -> float:
    n = max(0, end - start)
    if n <= 0:
        return 0.0
    sl = x[start:end].astype(np.float64, copy=False)
    return float(np.sqrt(np.mean(np.square(sl))))


def active_rms(x: np.ndarray, start: int, end: int, frame: int = FRAME) -> float:
    acc = 0.0
    n = 0
    i = start
    while i + frame <= end:
        r = _rms_range(x, i, i + frame)
        if r >= SEGMENT_ONSET_FS:
            chunk = x[i : i + frame].astype(np.float64, copy=False)
            acc += float(np.sum(np.square(chunk)))
            n += frame
        i += frame
    return math.sqrt(acc / n) if n else _rms_range(x, start, end)


def find_segments(samples, sample_rate_hz: int = SAMPLE_RATE_HZ) -> list[tuple[int, int]]:
    x = np.asarray(samples, dtype=np.float32).reshape(-1)
    frame = max(1, int((sample_rate_hz * FRAME_MS) / 1000))
    n = int(x.size)
    if n < frame:
        return [(0, n)] if rms(x) >= SEGMENT_ONSET_FS else []
    n_frames = n // frame
    frames = x[: n_frames * frame].astype(np.float64).reshape(n_frames, frame)
    rms_f = np.sqrt(np.mean(np.square(frames), axis=1))
    mask = np.zeros(n_frames, dtype=bool)
    hold = 0
    for f in range(n_frames):
        if rms_f[f] >= SEGMENT_ONSET_FS:
            mask[f] = True
            hold = HANGOVER_FRAMES
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
        if b - a + 1 >= MIN_SEGMENT_FRAMES:
            raw.append((a * frame, min((b + 1) * frame, n)))
    return split_dips(x, raw, frame)


def split_dips(samples, islands: list[tuple[int, int]], frame: int = FRAME) -> list[tuple[int, int]]:
    x = np.asarray(samples, dtype=np.float32).reshape(-1)
    out: list[tuple[int, int]] = []
    for start, end in islands:
        n_frames = max(1, (end - start) // frame)
        if n_frames < MIN_SEGMENT_FRAMES * 2:
            out.append((start, end))
            continue
        rms_f = []
        peak_f = 0.0
        for f in range(n_frames):
            a = start + f * frame
            r = _rms_range(x, a, min(a + frame, end))
            rms_f.append(r)
            if r > peak_f:
                peak_f = r
        dip = peak_f * DIP_RATIO
        cuts: list[int] = []
        f = MIN_SEGMENT_FRAMES
        while f < n_frames - MIN_SEGMENT_FRAMES:
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
            if c - prev >= MIN_SEGMENT_FRAMES:
                a = start + prev * frame
                b = min(start + c * frame, end)
                out.append((a, b))
            prev = c + DIP_FRAMES
        if n_frames - prev >= MIN_SEGMENT_FRAMES:
            out.append((start + prev * frame, end))
    return out or islands


def stage_whole_file(samples) -> dict[str, Any]:
    """Historical whole-file RMS AGC @ 5bfc582 / b66581c. Can attenuate a loud mix."""
    x = np.asarray(samples, dtype=np.float32).reshape(-1)
    n = int(x.size)
    if n == 0:
        r = _empty_result(x)
        r["mode"] = "whole_file"
        return r
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
            "segments": [],
            "loud_crushed": False,
            "mode": "whole_file",
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
        "segments": [],
        "loud_crushed": bool(gain < 1.0 - 1e-9),
        "mode": "whole_file",
    }


def stage_per_segment(samples, sample_rate_hz: int = SAMPLE_RATE_HZ) -> dict[str, Any]:
    """Headline per-turn gain @ ae86903 / db3afe4. Never apply gain < 1."""
    x = np.asarray(samples, dtype=np.float32).reshape(-1)
    n = int(x.size)
    if n == 0:
        return _empty_result(x)
    rms_before = rms(x)
    peak_before = peak(x)
    vad_before = energy_vad_fire_rate(x, sample_rate_hz=sample_rate_hz)
    out = x.copy()
    segs: list[dict[str, Any]] = []
    any_boost = False
    any_clip = False
    max_g = 1.0
    frame = max(1, int((sample_rate_hz * FRAME_MS) / 1000))
    for start, end in find_segments(x, sample_rate_hz):
        sl = x[start:end]
        r0 = active_rms(x, start, end, frame)
        p0 = peak(sl)
        g = gain_for_segment(r0, p0)
        if g > max_g:
            max_g = g
        desired_boost = r0 >= MIN_RMS_FOR_GAIN and r0 < TARGET_RMS_FS
        clip_guard = desired_boost and g + 1e-12 < TARGET_RMS_FS / max(r0, 1e-12)
        if clip_guard:
            any_clip = True
        if g > 1.0 + 1e-9:
            any_boost = True
        boosted = np.clip(sl.astype(np.float32) * np.float32(g), -PEAK_LIMIT, PEAK_LIMIT)
        out[start:end] = boosted
        segs.append(
            {
                "start": int(start),
                "end": int(end),
                "rms_before": r0,
                "rms_after": rms(boosted),
                "peak_before": p0,
                "gain_linear": float(g),
                "gain_db": linear_to_db(g),
                "boosted": bool(g > 1.0 + 1e-9),
                "clip_guard": bool(clip_guard),
            }
        )
    return {
        "samples": out,
        "gain_linear": float(max_g),
        "gain_db": linear_to_db(max_g),
        "rms_before": rms_before,
        "rms_after": rms(out),
        "peak_before": peak_before,
        "peak_after": peak(out),
        "clip_guard": bool(any_clip),
        "boosted": bool(any_boost),
        "vad_fire_before": vad_before,
        "vad_fire_after": energy_vad_fire_rate(out, sample_rate_hz=sample_rate_hz),
        "segments": segs,
        "loud_crushed": any(s["gain_linear"] < 1.0 - 1e-9 for s in segs),
        "mode": "per_turn",
    }


def stage(samples, *, per_turn: bool = True) -> dict[str, Any]:
    if per_turn:
        return stage_per_segment(samples)
    return stage_whole_file(samples)


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
        for i, val in enumerate(cur):
            y += a * (val - y)
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


def loud_near_turn(n: int = N_400MS) -> np.ndarray:
    return close_talk_sine(n=n, amplitude=LOUD_NEAR_AMP)


def gap(n: int = GAP_N) -> np.ndarray:
    return np.zeros(n, dtype=np.float32)


def concat(parts) -> np.ndarray:
    return np.concatenate([np.asarray(p, dtype=np.float32).reshape(-1) for p in parts])


def sequential_mix() -> tuple[np.ndarray, list[dict[str, Any]]]:
    near = loud_near_turn()
    g1 = gap()
    far5 = scene(5.0)
    g2 = gap()
    far6 = scene(6.0)
    o = 0
    turns = []
    for name, dist, p in (
        ("near_3m", 3.0, near),
        ("gap1", 0.0, g1),
        ("far_5m", 5.0, far5),
        ("gap2", 0.0, g2),
        ("far_6m", 6.0, far6),
    ):
        a = o
        o += int(np.asarray(p).size)
        turns.append({"name": name, "distance_m": dist, "start": a, "end": o})
    return concat([near, g1, far5, g2, far6]), turns
