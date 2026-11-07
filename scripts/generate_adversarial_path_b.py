#!/usr/bin/env python3
"""Adversarial Path B fixtures: worst-case demo-room, not clean CV.

Path B = ≤20 PresentConnect dry streams, oracle s0N↔speaker_N, merge by time.
CLEAN baseline `mix_20talker_per_stream` is NOT rewritten here.

All room IR / HVAC / chair / door / phone events are SYNTHETIC (generated in
this file). Speech is Mozilla Common Voice 17 RU test, CC0-1.0. Do not invent
metrics — this script only writes wavs + gold refs.

Usage:
  python3 scripts/generate_adversarial_path_b.py
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import numpy as np

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import generate_demo_stress_mix as G  # noqa: E402

HZ = G.HZ
SEED = 20261106
OUT = ROOT / "samples" / "demo-stress" / "adversarial"
CLEAN_DRY = ROOT / "samples" / "demo-stress" / "dry"
CLEAN_REF = ROOT / "samples" / "demo-stress" / "ref" / "mix_20talker_per_stream.json"
N_TALKERS = 20
# Main vs interference on the MAIN stream after bleed. ≤ +3 dB; worst is lower.
BLEED_SNR_EDGE_DB = 3.0
BLEED_SNR_WORST_DB = 1.5


def dump_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def fft_filter(x: np.ndarray, sr: int, gain_fn) -> np.ndarray:
    n = x.size
    spec = np.fft.rfft(x.astype(np.float64))
    freqs = np.fft.rfftfreq(n, 1.0 / sr)
    spec *= gain_fn(freqs)
    y = np.fft.irfft(spec, n=n)
    return y.astype(np.float32)


def pink_like(n: int, rng: np.random.Generator) -> np.ndarray:
    x = rng.standard_normal(n).astype(np.float64)
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(n)
    f = np.maximum(f, f[1] if f.size > 1 else 1.0)
    spec /= np.sqrt(f)
    y = np.fft.irfft(spec, n=n)
    r = float(np.sqrt(np.mean(np.square(y)))) or 1.0
    return (y / r).astype(np.float32)


def apply_reverb(x: np.ndarray, sr: int, *, rt60: float = 0.80, wet: float = 0.62, mic_i: int = 0) -> np.ndarray:
    """SYNTHETIC echo-y room: decaying taps + exponential tail. Not a measured IR."""
    if x.size == 0:
        return x
    delays_ms = [18 + (mic_i % 5), 29, 37, 43, 58, 71, 89, 107, 131, 163, 197]
    y = (1.0 - wet) * x.astype(np.float32)
    for k, dms in enumerate(delays_ms):
        d = int(sr * dms / 1000.0)
        if d <= 0 or d >= x.size:
            continue
        # ~60 dB down at rt60
        gain = wet * (10 ** (-3.0 * (dms / 1000.0) / max(rt60, 0.05))) * (0.85 ** (k % 3))
        y[d:] += gain * x[:-d]
    # cheap decaying tail
    tail_ms = min(int(rt60 * 1000), 900)
    tn = int(sr * tail_ms / 1000.0)
    if tn > 8:
        t = np.arange(tn, dtype=np.float32) / sr
        env = np.exp(-3.0 * t / rt60).astype(np.float32)
        # one-pole-ish: convolve with short env via overlap (decimate taps)
        step = max(1, sr // 200)
        for i in range(0, tn, step):
            if i >= x.size:
                break
            g = float(env[i]) * wet * 0.12
            y[i:] += g * x[: x.size - i]
    return y.astype(np.float32)


def apply_bleed(sources: list[np.ndarray], *, target_snr_db: float) -> tuple[list[np.ndarray], dict]:
    """Leaky PresentConnect mics: geometric 1/r plus scale so MAIN is only slightly above bleed."""
    n = len(sources)
    if n == 0:
        return sources, {}
    angles = np.linspace(0.0, 2.0 * math.pi, n, endpoint=False)
    xy = np.stack([2.1 * np.cos(angles), 1.15 * np.sin(angles)], axis=1)
    mouth_to_own_mic = 0.22
    raw = []
    iso = np.zeros((n, n), dtype=np.float64)
    for i in range(n):
        acc = sources[i].astype(np.float64).copy()
        iso[i, i] = 1.0
        for j in range(n):
            if i == j:
                continue
            dist = float(np.linalg.norm(xy[i] - xy[j]))
            g = mouth_to_own_mic / max(dist, 0.35)
            iso[i, j] = g
            acc += g * sources[j]
        raw.append(acc)
    main = sources[0].astype(np.float64)
    others = raw[0] - main
    active = np.abs(main) > 1e-3
    snr_before = None
    snr_after = None
    scale = 1.0
    if np.any(active) and G.rms(others[active].astype(np.float32)) > 1e-8:
        r_m = G.rms(main[active].astype(np.float32))
        r_o = G.rms(others[active].astype(np.float32))
        snr_before = 20.0 * math.log10(r_m / max(r_o, 1e-12))
        want = r_m / (10 ** (target_snr_db / 20.0))
        scale = want / max(r_o, 1e-12)
        others = others * scale
        raw[0] = main + others
        snr_after = 20.0 * math.log10(r_m / max(G.rms(others[active].astype(np.float32)), 1e-12))
        for i in range(1, n):
            own = sources[i].astype(np.float64)
            bleed = (raw[i] - own) * scale
            raw[i] = own + bleed
    out = [a.astype(np.float32) for a in raw]
    return out, {
        "target_snr_db": target_snr_db,
        "snr_before_scale_db": None if snr_before is None else round(snr_before, 3),
        "snr_after_scale_db": None if snr_after is None else round(snr_after, 3),
        "bleed_scale": round(float(scale), 4),
        "isolation_main_to_spk2": round(float(iso[0, 1]), 4),
        "note": "main stream SNR vs sum of other talkers on main-active frames; do not boost main",
    }


def add_room_noises(x: np.ndarray, sr: int, rng: np.random.Generator, *, mic_i: int) -> np.ndarray:
    """SYNTHETIC HVAC + white + chair scrapes + door slam + phone ring. Not field recordings."""
    n = x.size
    y = x.astype(np.float32).copy()
    speech_r = max(G.rms(x), 1e-4)
    # white sensor noise
    white = rng.standard_normal(n).astype(np.float32)
    y += white * (0.045 * speech_r / max(G.rms(white), 1e-8))
    # HVAC: lowpassed pink, continuous
    hvac = pink_like(n, rng)
    hvac = fft_filter(hvac, sr, lambda f: np.where(f < 350.0, 1.0, (350.0 / np.maximum(f, 1.0)) ** 1.7))
    y += hvac * (0.22 * speech_r / max(G.rms(hvac), 1e-8))
    # chair scrapes (shared room times, slight per-mic delay)
    chair_ms = [2100, 6400, 12100]
    for t0 in chair_ms:
        a = int((t0 + mic_i * 3) * sr / 1000.0)
        ln = int(0.09 * sr)
        if a >= n:
            continue
        b = min(n, a + ln)
        burst = rng.standard_normal(b - a).astype(np.float32)
        burst = fft_filter(burst, sr, lambda f: np.where((f > 180) & (f < 1400), 1.0, 0.08))
        env = np.hanning(b - a).astype(np.float32)
        y[a:b] += burst * env * (0.55 * speech_r / max(G.rms(burst), 1e-8))
    # door slam
    door_at = int((4800 + (mic_i % 7)) * sr / 1000.0)
    if door_at < n:
        ln = int(0.22 * sr)
        t = np.arange(min(ln, n - door_at), dtype=np.float32) / sr
        slam = (np.sin(2 * np.pi * 72 * t) * np.exp(-18.0 * t)).astype(np.float32)
        slam += 0.4 * rng.standard_normal(t.size).astype(np.float32) * np.exp(-22.0 * t)
        y[door_at : door_at + t.size] += slam * (0.7 * speech_r / max(float(np.max(np.abs(slam))), 1e-8))
    # phone ring: dual-tone, two bursts (synthetic, not a commercial ringtone)
    for t0 in (3200, 9800):
        a = int((t0 + mic_i) * sr / 1000.0)
        ln = int(0.38 * sr)
        if a >= n:
            continue
        b = min(n, a + ln)
        t = np.arange(b - a, dtype=np.float32) / sr
        gate = ((np.arange(b - a) % int(0.04 * sr)) < int(0.02 * sr)).astype(np.float32)
        tone = (np.sin(2 * np.pi * 425 * t) + np.sin(2 * np.pi * 480 * t)).astype(np.float32)
        y[a:b] += tone * gate * (0.18 * speech_r / 2.0)
    return y


def compress(x: np.ndarray, thresh: float = 0.12, ratio: float = 10.0) -> np.ndarray:
    a = np.abs(x)
    g = np.ones_like(a)
    over = a > thresh
    g[over] = (thresh + (a[over] - thresh) / ratio) / np.maximum(a[over], 1e-8)
    return (x * g).astype(np.float32)


def apply_bad_mic(x: np.ndarray, sr: int, rng: np.random.Generator) -> np.ndarray:
    """Phone-mic chain: band-limit, cheap EQ, compression, clipping, extra noise."""
    y = fft_filter(
        x,
        sr,
        lambda f: np.where(
            (f >= 300.0) & (f <= 3400.0),
            np.where((f >= 1400.0) & (f <= 2400.0), 1.45, 1.0),
            0.04,
        ),
    )
    y = compress(y * 1.8, thresh=0.10, ratio=12.0)
    y = np.clip(y, -0.38, 0.38).astype(np.float32)
    # mu-law-ish crush
    mu = 80.0
    y = np.sign(y) * np.log1p(mu * np.abs(y)) / np.log1p(mu)
    y = np.clip(y * 0.9, -1.0, 1.0).astype(np.float32)
    hiss = rng.standard_normal(y.size).astype(np.float32) * 0.012
    return (y + hiss).astype(np.float32)


def peak_protect(streams: list[np.ndarray], peak: float = 0.97) -> list[np.ndarray]:
    m = max((float(np.max(np.abs(s))) if s.size else 0.0) for s in streams) or 1.0
    if m <= peak:
        return streams
    g = peak / m
    return [(s * g).astype(np.float32) for s in streams]


def overlap_stats(utterances: list[dict], duration_ms: int, frame_ms: int = 10) -> dict:
    n = max(1, duration_ms // frame_ms)
    counts = np.zeros(n, dtype=np.int32)
    for u in utterances:
        a = max(0, int(u["start_ms"]) // frame_ms)
        b = min(n, int(u["end_ms"]) // frame_ms)
        counts[a:b] += 1
    speech = counts > 0
    n_speech = int(speech.sum()) or 1
    return {
        "max_active_speakers": int(counts.max()),
        "frac_speech_frames_2plus": round(float((counts >= 2).sum() / n_speech), 4),
        "frac_speech_frames_5plus": round(float((counts >= 5).sum() / n_speech), 4),
        "mean_active_on_speech": round(float(counts[speech].mean()) if np.any(speech) else 0.0, 3),
    }


def load_clean_dry(n_talkers: int = N_TALKERS) -> list[np.ndarray]:
    out = []
    for i in range(1, n_talkers + 1):
        p = CLEAN_DRY / f"spk_{i:02d}.wav"
        if not p.is_file():
            raise FileNotFoundError(f"clean dry missing {p}; run generate_demo_stress_mix.py first")
        x, sr = G.read_wav_mono(p)
        out.append(G.resample_linear(x, sr, HZ))
    return out


def load_talker_clips(selection: dict) -> tuple[list[np.ndarray], list[np.ndarray], list[dict]]:
    talkers = selection["talkers"][:N_TALKERS]
    main = talkers[0]
    main_clips = [G.load_cv_clip(selection, main, c) for c in main["clips"][:3]]
    babble = []
    meta = []
    for t in talkers[1:]:
        clip = G.load_cv_clip(selection, t, t["clips"][0])
        babble.append(clip)
        meta.append(t)
    return main_clips, babble, meta


def build_overlap_sources(selection: dict):
    """True overlap: two talker clusters on top of main, not 90 ms stagger. No looping."""
    main_clips, babble, babble_talkers = load_talker_clips(selection)
    main_plan = []
    cursor = 120
    for clip, meta in zip(main_clips, selection["talkers"][0]["clips"][:3]):
        dur_ms = int(round(clip.size * 1000.0 / HZ))
        main_plan.append((cursor, cursor + dur_ms, clip, meta["sentence"]))
        cursor = cursor + dur_ms + 160  # tight gaps — meeting, not turn-taking
    # Cluster A (speakers 2–11) pile onto main turn 1
    # Cluster B (speakers 12–20) pile onto main turns 2–3
    last_end = main_plan[-1][1]
    max_b = max(int(round(c.size * 1000.0 / HZ)) for c in babble)
    dur_ms = max(last_end, 900 + max_b, 2800 + max_b) + 500
    n = int(dur_ms * HZ / 1000)
    dry = [np.zeros(n, dtype=np.float32) for _ in range(N_TALKERS)]
    utt = []
    channels = []
    channels.append({"stream_id": "s01", "speaker_id": "speaker_1", "offset_ms": main_plan[0][0], "role": "main"})
    for start, _e, clip, text in main_plan:
        s, e = G.place(dry[0], clip, start)
        utt.append(G.item("speaker_1", s, e, text))
    for i, (t, clip) in enumerate(zip(babble_talkers, babble), start=1):
        if i <= 10:
            offset = 40 + (i - 1) * 170  # 40..1570 ms, overlap main1 + each other
        else:
            # start near main turn 2 so later conference still overlapped
            offset = main_plan[1][0] - 300 + (i - 11) * 190
            offset = max(80, offset)
        channels.append(
            {
                "stream_id": t["stream_id"],
                "speaker_id": t["speaker_id"],
                "offset_ms": offset,
                "role": "babble_cluster_a" if i <= 10 else "babble_cluster_b",
            }
        )
        s, e = G.place(dry[i], clip, offset)
        utt.append(G.item(t["speaker_id"], s, e, t["clips"][0]["sentence"]))
    utt.sort(key=lambda u: (u["start_ms"], u["speaker_id"]))
    stats = overlap_stats(utt, int(round(n * 1000.0 / HZ)))
    return dry, {"transcript": utt}, channels, stats, int(round(n * 1000.0 / HZ))


def apply_named(
    sources: list[np.ndarray],
    *,
    reverb: bool,
    bleed_snr_db: float | None,
    noise: bool,
    badmic: bool,
    rng: np.random.Generator,
) -> tuple[list[np.ndarray], dict]:
    streams = [s.copy() for s in sources]
    meta: dict = {"reverb": reverb, "noise": noise, "badmic": badmic, "bleed_snr_db": bleed_snr_db}
    if bleed_snr_db is not None:
        streams, bleed_meta = apply_bleed(streams, target_snr_db=bleed_snr_db)
        meta["bleed"] = bleed_meta
    if reverb:
        streams = [apply_reverb(s, HZ, rt60=0.82, wet=0.64, mic_i=i) for i, s in enumerate(streams)]
        meta["reverb_rt60_s"] = 0.82
        meta["reverb_wet"] = 0.64
        meta["reverb_license"] = "SYNTHETIC taps+tail in generate_adversarial_path_b.py (not a recorded IR)"
    if noise:
        streams = [add_room_noises(s, HZ, rng, mic_i=i) for i, s in enumerate(streams)]
        meta["noise_license"] = "SYNTHETIC HVAC/white/chair/door/phone (not field recordings)"
    if badmic:
        streams = [apply_bad_mic(s, HZ, rng) for s in streams]
        meta["badmic"] = "band-limit 300-3400 Hz, 2 kHz EQ bump, compressor, clip ±0.38, mu-law crush, hiss"
    streams = peak_protect(streams)
    return streams, meta


def write_condition(
    out: Path,
    name: str,
    streams: list[np.ndarray],
    ref: dict,
    *,
    notes: str,
    dsp_meta: dict,
    channels: list | None = None,
) -> dict:
    dry_dir = out / f"dry_{name}"
    dry_dir.mkdir(parents=True, exist_ok=True)
    mix = np.zeros_like(streams[0])
    for s in streams:
        mix += s
    peak = float(np.max(np.abs(mix))) or 1.0
    mix_w = (mix * (0.89 / peak)).astype(np.float32)
    G.write_wav(out / f"{name}.wav", mix_w)
    for i, s in enumerate(streams):
        G.write_wav(dry_dir / f"spk_{i + 1:02d}.wav", s)
    dump_json(out / "ref" / f"{name}.json", ref)
    dur = int(round(streams[0].size * 1000.0 / HZ))
    print(f"wrote {name} ({dur} ms, streams={len(streams)})")
    return {
        "test_id": name,
        "duration_ms": dur,
        "n_streams": len(streams),
        "n_ref_utt": len(ref.get("transcript") or []),
        "notes": notes,
        "dsp": dsp_meta,
        "channels": channels,
    }


def build_smoke(out: Path, selection: dict, rng: np.random.Generator) -> dict:
    talkers = selection["talkers"][:4]
    crop = int(0.42 * HZ)
    stagger_forbidden = True  # all start together
    n = crop + int(0.35 * HZ)
    dry = [np.zeros(n, dtype=np.float32) for _ in talkers]
    utt = []
    for i, t in enumerate(talkers):
        clip = G.load_cv_clip(selection, t, t["clips"][0])
        if clip.size > crop:
            mid = clip.size // 2
            clip = clip[mid : mid + crop]
        G.place(dry[i], clip, 40)
        utt.append(G.item(t["speaker_id"], 40, 40 + int(round(clip.size * 1000.0 / HZ)), t["clips"][0]["sentence"]))
    utt.sort(key=lambda u: (u["start_ms"], u["speaker_id"]))
    streams, dsp = apply_named(dry, reverb=True, bleed_snr_db=BLEED_SNR_WORST_DB, noise=True, badmic=True, rng=rng)
    _ = stagger_forbidden
    mix = np.zeros_like(streams[0])
    for s in streams:
        mix += s
    peak = float(np.max(np.abs(mix))) or 1.0
    G.write_wav(out / "smoke_worst.wav", (mix * (0.89 / peak)).astype(np.float32))
    dump_json(out / "ref" / "smoke_worst.json", {"transcript": utt})
    print(f"wrote smoke_worst.wav ({int(round(streams[0].size * 1000.0 / HZ))} ms, 4 streams, SYNTHETIC room+noise+mic)")
    return {
        "test_id": "smoke_worst",
        "duration_ms": int(round(streams[0].size * 1000.0 / HZ)),
        "n_streams": 4,
        "n_ref_utt": len(utt),
        "notes": "Tiny committed WORST_CASE smoke (4 talkers, cropped). Not headline.",
        "dsp": dsp,
    }


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--out-dir", type=Path, default=OUT)
    args = p.parse_args()
    out: Path = args.out_dir
    out.mkdir(parents=True, exist_ok=True)
    (out / "ref").mkdir(exist_ok=True)

    sel_path = G.CORPUS / "selection.json"
    if not sel_path.is_file():
        print("missing corpus; run python3 scripts/fetch_cv17_ru.py", file=sys.stderr)
        return 2
    selection = json.loads(sel_path.read_text(encoding="utf-8"))
    if not CLEAN_REF.is_file():
        print("missing clean Path B ref; run generate_demo_stress_mix.py", file=sys.stderr)
        return 2
    clean_ref = json.loads(CLEAN_REF.read_text(encoding="utf-8"))
    rng = np.random.default_rng(SEED)

    clean_src = load_clean_dry()
    overlap_src, overlap_ref, overlap_ch, ov_stats, _ov_ms = build_overlap_sources(selection)

    specs = []
    # Ablations on CLEAN timeline (isolates each degradation).
    s, dsp = apply_named(clean_src, reverb=True, bleed_snr_db=None, noise=False, badmic=False, rng=rng)
    specs.append(
        write_condition(
            out,
            "EDGE_reverb",
            s,
            clean_ref,
            notes="EDGE: SYNTHETIC echo-y room on CLEAN Path B dry streams. No bleed/noise/mic crush.",
            dsp_meta=dsp,
        )
    )
    s, dsp = apply_named(overlap_src, reverb=False, bleed_snr_db=BLEED_SNR_EDGE_DB, noise=False, badmic=False, rng=rng)
    specs.append(
        write_condition(
            out,
            "EDGE_overlap",
            s,
            overlap_ref,
            notes="EDGE: true overlap (two clusters, not 90 ms stagger) + mic bleed, main ~+3 dB vs bleed on s01.",
            dsp_meta={**dsp, "overlap": ov_stats},
            channels=overlap_ch,
        )
    )
    s, dsp = apply_named(clean_src, reverb=False, bleed_snr_db=None, noise=True, badmic=False, rng=rng)
    specs.append(
        write_condition(
            out,
            "EDGE_noise",
            s,
            clean_ref,
            notes="EDGE: SYNTHETIC white+HVAC+chair+door+phone on CLEAN Path B streams.",
            dsp_meta=dsp,
        )
    )
    s, dsp = apply_named(clean_src, reverb=False, bleed_snr_db=None, noise=False, badmic=True, rng=rng)
    specs.append(
        write_condition(
            out,
            "EDGE_badmic",
            s,
            clean_ref,
            notes="EDGE: bad phone-mic (band-limit, clip, compress, EQ, crush) on CLEAN Path B streams.",
            dsp_meta=dsp,
        )
    )
    s, dsp = apply_named(
        overlap_src,
        reverb=True,
        bleed_snr_db=BLEED_SNR_WORST_DB,
        noise=True,
        badmic=True,
        rng=rng,
    )
    specs.append(
        write_condition(
            out,
            "WORST_CASE",
            s,
            overlap_ref,
            notes=(
                "WORST_CASE Path B: true overlap+bleed (main ~+1.5 dB vs interferers on s01) "
                "+ SYNTHETIC reverb + room noises + bad mic. Expect FAIL. Not tuned to look good."
            ),
            dsp_meta={**dsp, "overlap": ov_stats},
            channels=overlap_ch,
        )
    )
    smoke = build_smoke(out, selection, rng)

    recipe = {
        "label": "ADVERSARIAL",
        "johann_hard_requirement": "worst-case demo room, not clean CV; expect FAIL; do not tune fixtures",
        "path": "B_per_stream",
        "n_talkers_cap": 20,
        "clean_baseline": "mix_20talker_per_stream (not rewritten)",
        "speech_corpus": {
            "name": "Mozilla Common Voice 17.0 Russian test split",
            "license": "CC0-1.0",
            "gold": "corpus sentence field",
        },
        "synthetic_fx_license": "All IR/HVAC/chair/door/phone/white generated in this script; no third-party audio",
        "main_snr_db_edge_bleed": BLEED_SNR_EDGE_DB,
        "main_snr_db_worst_bleed": BLEED_SNR_WORST_DB,
        "host_linux_only": True,
        "invented_metrics": False,
        "overlap_clean_note": "CLEAN Path B stagger is 90 ms; EDGE_overlap/WORST_CASE use clustered concurrent onsets",
        "overlap_stats_shared_by_EDGE_overlap_and_WORST_CASE": ov_stats,
        "conditions": specs,
        "smoke_worst": smoke,
        "json_schema": "transcript[].{speaker_id,start_ms,end_ms,text}",
        "oracle": "s0N↔speaker_N, main=s01/speaker_1",
    }
    dump_json(out / "recipe.json", recipe)
    dump_json(out / "channels_overlap.json", {"channels": overlap_ch, "overlap": ov_stats})
    print("wrote", out / "recipe.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
