#!/usr/bin/env python3
"""Deterministic noise fixtures for the casual-mic phantom-speaker hypothesis.

User device verdict (2026-11-08, present branch): a 3-person casual recording
still produced 7 speakers after the diarClusterThreshold=0.65 /
SAME_VOICE_MAX_DISTANCE=0.60 refinement. Hypothesis under test: background
noise bursts get VAD/diarized as words of extra speakers (Silero+energy VAD
islands -> pyannote segments -> scattered noise embeddings -> own clusters;
expectedSpeakers=-1 reconcile returns clustering as-is; SpeakerIdentityMerge
re-roots every unglued fragment as a fresh label).

This module builds, fully deterministically in numpy (seeded default_rng, no
network, no randomness at call time):
  (a) casual 3-speaker clean fixture + injected noise events: 0.3-1.5 s
      pink/white bursts at -20/-15/-10 dBFS relative to the speech peak,
      placed in the inter-turn gaps plus one burst overlapping a turn;
  (b) same as (a) + continuous pink room noise at -35 dBFS (speech peak).

The clean fixtures themselves (2spk/3spk alternating-concat of the committed
handoff slices) are built by scripts/mic_diar_sweep.build_casual_wav.

dBFS convention here: amplitude relative to the CLEAN fixture's speech peak,
i.e. level_db=-20 -> amp = peak * 10^(-20/20).
"""
from __future__ import annotations

from pathlib import Path

import numpy as np

SR = 16000

# Burst schedule for fixture (a)/(b): (offset_from_turn_boundary_s, dur_s,
# level_db, kind, placement). "gap" bursts start inside the 0.4 s inter-turn
# gap and spill into the following silence; "overlap" bursts sit on top of a
# real speech turn (worst case: gated segment mixes speech + noise).
BURST_LEVELS_DB = (-20.0, -15.0, -10.0)
BURST_DURS_S = (0.30, 0.65, 1.10, 1.50)


def pink_noise(n: int, seed: int, sr: int = SR) -> np.ndarray:
    """Unit-RMS pink noise: seeded white noise shaped by 1/sqrt(f) in FFT."""
    rng = np.random.default_rng(seed)
    x = rng.standard_normal(n).astype(np.float64)
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(n, d=1.0 / sr)
    scale = np.ones_like(freqs)
    nz = freqs > 0
    scale[nz] = 1.0 / np.sqrt(freqs[nz] / freqs[nz][0] + 1e-12)
    scale[0] = scale[1] if scale.size > 1 else 1.0
    y = np.fft.irfft(spec * scale, n=n)
    rms = float(np.sqrt(np.mean(y * y))) or 1.0
    return (y / rms).astype(np.float32)


def white_noise(n: int, seed: int) -> np.ndarray:
    rng = np.random.default_rng(seed)
    x = rng.standard_normal(n).astype(np.float32)
    rms = float(np.sqrt(np.mean(x * x))) or 1.0
    return (x / rms).astype(np.float32)


def _burst(n: int, kind: str, seed: int) -> np.ndarray:
    """Speech-like noise burst: pink/white carrier gated by a deterministic
    syllable-rate (3-7 Hz) amplitude envelope with 10 ms ramps. Stationary
    noise is rejected by pyannote segmentation (measured 2026-11-08: the
    first un-modulated fixture draft produced ONE segment over the whole
    26 s noise wav); real room bursts that trigger VAD are transient and
    modulated, so the fixture must be too."""
    x = pink_noise(n, seed) if kind == "pink" else white_noise(n, seed)
    rng = np.random.default_rng(seed + 5000)
    t = np.arange(n, dtype=np.float64) / SR
    env = np.zeros(n, dtype=np.float64)
    for f in (3.0, 4.7, 6.5):
        ph = rng.uniform(0, 2 * np.pi)
        env += 0.5 + 0.5 * np.sin(2 * np.pi * f * t + ph)
    env /= env.max()
    env = (env > 0.35).astype(np.float64)
    # 10 ms linear ramps on envelope edges
    ramp = int(0.010 * SR)
    if ramp > 0 and n > 2 * ramp:
        k = np.linspace(0, 1, ramp)
        for i in range(1, n):
            if env[i] > env[i - 1]:
                e = min(i + ramp, n)
                env[i:e] *= np.linspace(0, 1, e - i) if e - i == ramp else np.linspace(0, 1, e - i)
            elif env[i] < env[i - 1]:
                s = max(0, i - ramp)
                env[s:i] *= np.linspace(1, 0, i - s) if i - s == ramp else np.linspace(1, 0, i - s)
    y = x.astype(np.float64) * env
    rms = float(np.sqrt(np.mean(y * y))) or 1.0
    # normalize to unit RMS so level_db controls the audible energy
    return (y / rms).astype(np.float32)


def noise_events(
    total_s: float,
    truth: list[dict],
    seed: int = 20261108,
) -> list[dict]:
    """Deterministic burst schedule: bursts in the gaps between turns (cycling
    -20/-15/-10 dB and 0.3/0.65/1.1/1.5 s) plus one -10 dB burst overlapping
    the middle of the longest turn. Returns [{start_s, end_s, level_db, kind}].
    """
    events: list[dict] = []
    bounds = sorted((t["start_ms"] / 1000.0, t["end_ms"] / 1000.0) for t in truth)
    gaps: list[tuple[float, float]] = []
    cur = 0.0
    for a, b in bounds:
        if a - cur >= 0.25:
            gaps.append((cur, a))
        cur = max(cur, b)
    if total_s - cur >= 0.25:
        gaps.append((cur, total_s))
    i = 0
    for g0, g1 in gaps:
        # Leave every second gap clean so turn boundaries (and thus the real
        # speaker changes) survive for the diarizer; filling every gap glues
        # the whole recording into one segment (measured on the first draft).
        if i % 2 == 1:
            i += 1
            continue
        dur = BURST_DURS_S[i % len(BURST_DURS_S)]
        lvl = BURST_LEVELS_DB[(i // 2) % len(BURST_LEVELS_DB)]
        kind = "pink" if (i // 2) % 2 == 0 else "white"
        start = g0 + min(0.10, max(0.0, (g1 - g0 - dur) / 2.0))
        if start + dur > total_s:
            dur = max(0.30, total_s - start)
        events.append(
            {"start_s": round(start, 3), "end_s": round(start + dur, 3),
             "level_db": lvl, "kind": kind}
        )
        i += 1
    # One loud burst overlapping a real turn (noise ON speech).
    if truth:
        longest = max(truth, key=lambda t: t["end_ms"] - t["start_ms"])
        mid = (longest["start_ms"] + longest["end_ms"]) / 2000.0
        dur = 0.9
        events.append(
            {"start_s": round(mid - dur / 2.0, 3), "end_s": round(mid + dur / 2.0, 3),
             "level_db": -10.0, "kind": "pink", "overlaps_turn": True}
        )
    return events


def inject_events(
    wav: np.ndarray,
    events: list[dict],
    seed: int = 20261108,
    sr: int = SR,
) -> np.ndarray:
    """Add the scheduled bursts to a copy of `wav` at levels relative to the
    clean speech peak. Deterministic: burst k uses seed+k."""
    out = wav.astype(np.float32).copy()
    peak = float(np.max(np.abs(out))) or 1.0
    for k, ev in enumerate(events):
        a = int(round(ev["start_s"] * sr))
        b = min(out.size, int(round(ev["end_s"] * sr)))
        if b - a < 16:
            continue
        amp = peak * (10.0 ** (ev["level_db"] / 20.0))
        out[a:b] += _burst(b - a, ev["kind"], seed + k) * np.float32(amp)
    return out


def add_room_noise(wav: np.ndarray, level_db: float = -35.0, seed: int = 777, sr: int = SR) -> np.ndarray:
    """Continuous pink room noise at level_db relative to the speech peak."""
    out = wav.astype(np.float32).copy()
    peak = float(np.max(np.abs(out))) or 1.0
    amp = peak * (10.0 ** (level_db / 20.0))
    return out + pink_noise(out.size, seed, sr) * np.float32(amp)


def build_noise_fixtures(
    clean3: np.ndarray,
    truth3: list[dict],
    out_a: Path,
    out_b: Path,
    write_wav,
) -> tuple[list[dict], np.ndarray, np.ndarray]:
    """Fixture (a): clean3 + burst events. Fixture (b): (a) + -35 dBFS room.
    Writes both wavs; returns (events, wav_a, wav_b)."""
    total_s = clean3.size / SR
    events = noise_events(total_s, truth3)
    wav_a = inject_events(clean3, events)
    wav_b = add_room_noise(wav_a, level_db=-35.0)
    for path, w in ((out_a, wav_a), (out_b, wav_b)):
        write_wav(path, w, SR)
    return events, wav_a, wav_b
