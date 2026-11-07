#!/usr/bin/env python3
"""DEMO_STAGE6 primary demo fixture: 6 RU talkers, one-mic room mix.

NOT 20 PresentConnect streams. Path A style: a single phone in a large room.

  * 6 distinct Common Voice 17 RU (CC0) talkers
  * Turn-taking one-by-one with ~200 ms interrupt overlap at replica ends
  * Distance 3–6 m (attenuation + wetter reverb farther away)
  * SYNTHETIC large-room IR (RT60 ~1.4 s)
  * Medium HVAC + quiet unintelligible babble bed
  * Nothing-class phone mic on the mix (band-limit + light compression, NOT
    the EDGE_badmic mu-law crush)

Speech gold = corpus `sentence`. Do not invent metrics. Large wav gitignored;
recipe + ref + smoke committed.

Usage:
  python3 scripts/generate_demo_stage6.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import generate_adversarial_path_b as A  # noqa: E402
import generate_demo_stress_mix as G  # noqa: E402

HZ = G.HZ
SEED = 20261106
N_TALKERS = 6
OVERLAP_MS = 200
LEAD_MS = 250
TAIL_MS = 400
DISTANCES_M = (3.0, 3.6, 4.2, 4.8, 5.4, 6.0)
RT60_S = 1.40
HVAC_SNR_DB = 14.0
BABBLE_SNR_DB = 18.0
OUT = ROOT / "samples" / "demo-stress" / "stage6"
CORPUS_SEL = ROOT / "samples" / "demo-stress" / "corpus" / "cv17-ru" / "selection.json"


def dump_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def distance_gain(distance_m: float, near_m: float = 3.0) -> float:
    """Relative to the nearest seat (3 m), inverse-distance. 6 m → 0.5 (−6 dB)."""
    return float(near_m / max(distance_m, 0.5))


def apply_large_room(x: np.ndarray, sr: int, *, distance_m: float, mic_i: int) -> np.ndarray:
    """Farther seats are wetter. SYNTHETIC IR, not a measured hall."""
    t = (distance_m - 3.0) / 3.0
    wet = 0.46 + 0.28 * t
    return A.apply_reverb(x, sr, rt60=RT60_S, wet=wet, mic_i=mic_i)


def apply_nothing_phone_mic(x: np.ndarray, sr: int, rng: np.random.Generator) -> np.ndarray:
    """Consumer phone (Nothing-class): band-limit + light compression + hiss.

    Not EDGE_badmic (no ±0.38 clip, no mu-law crush). Still not a studio mic.
    """

    def gain_fn(f):
        g = np.ones_like(f, dtype=np.float64)
        lo = np.clip(f / 250.0, 0.04, 1.0)
        g = np.where(f < 250.0, (lo ** 1.4) * 0.40, g)
        hi = np.clip(4500.0 / np.maximum(f, 1.0), 0.04, 1.0)
        g = np.where(f > 4500.0, (hi ** 1.6) * 0.28, g)
        g = np.where((f >= 1500.0) & (f <= 2800.0), g * 1.12, g)
        g = np.where((f > 3400.0) & (f <= 4500.0), g * 0.62, g)
        return g

    y = A.fft_filter(x, sr, gain_fn)
    y = A.compress(y * 1.12, thresh=0.22, ratio=3.5)
    hiss = rng.standard_normal(y.size).astype(np.float32) * 0.004
    return np.clip(y + hiss, -0.98, 0.98).astype(np.float32)


def add_hvac(mix: np.ndarray, sr: int, rng: np.random.Generator, speech_rms: float) -> tuple[np.ndarray, dict]:
    n = mix.size
    y = mix.astype(np.float32).copy()
    hvac = A.pink_like(n, rng)
    hvac = A.fft_filter(
        hvac, sr, lambda f: np.where(f < 350.0, 1.0, (350.0 / np.maximum(f, 1.0)) ** 1.7)
    )
    want_h = speech_rms / (10 ** (HVAC_SNR_DB / 20.0))
    y += hvac * (want_h / max(G.rms(hvac), 1e-8))
    return y, {
        "hvac_snr_db_target": HVAC_SNR_DB,
        "hvac_license": "SYNTHETIC lowpassed pink (not a field recording)",
    }


def load_stage6_talkers(selection: dict) -> list[dict]:
    talkers = selection["talkers"][:N_TALKERS]
    if len(talkers) < N_TALKERS:
        raise RuntimeError(f"need {N_TALKERS} CV talkers, have {len(talkers)}")
    out = []
    for i, t in enumerate(talkers):
        clip_meta = t["clips"][0]
        x = G.load_cv_clip(selection, t, clip_meta)
        out.append(
            {
                "speaker_id": f"speaker_{i + 1}",
                "client_id": t["client_id"],
                "distance_m": DISTANCES_M[i],
                "sentence": clip_meta["sentence"],
                "path": clip_meta["path"],
                "dry": x,
                "duration_ms": int(round(x.size * 1000.0 / HZ)),
            }
        )
    return out


def build(selection: dict, rng: np.random.Generator) -> dict:
    talkers = load_stage6_talkers(selection)
    # Timeline: one-by-one, next starts OVERLAP_MS before previous end.
    cursor = LEAD_MS
    plan = []
    for t in talkers:
        start = cursor
        end = start + t["duration_ms"]
        plan.append({**t, "start_ms": start, "end_ms": end})
        cursor = end - OVERLAP_MS
    last_end = plan[-1]["end_ms"] + TAIL_MS
    n = int(round(last_end * HZ / 1000.0))
    canvas = [np.zeros(n, dtype=np.float32) for _ in plan]
    utt = []
    channels = []
    for i, p in enumerate(plan):
        g = distance_gain(p["distance_m"])
        near = G.fade(p["dry"] * g, HZ, 6.0)
        wet = apply_large_room(near, HZ, distance_m=p["distance_m"], mic_i=i)
        s, e = G.place(canvas[i], wet, p["start_ms"])
        utt.append(G.item(p["speaker_id"], p["start_ms"], p["end_ms"], p["sentence"]))
        channels.append(
            {
                "speaker_id": p["speaker_id"],
                "client_id": p["client_id"],
                "distance_m": p["distance_m"],
                "gain_vs_3m": round(g, 4),
                "start_ms": p["start_ms"],
                "end_ms": p["end_ms"],
                "duration_ms": p["duration_ms"],
                "path": p["path"],
            }
        )
    mix = np.zeros(n, dtype=np.float32)
    for c in canvas:
        mix += c
    speech_r = G.rms(mix)
    mix, bed_meta = add_hvac(mix, HZ, rng, speech_r)
    extra = selection["talkers"][N_TALKERS : N_TALKERS + 3]
    if extra:
        bed = np.zeros(n, dtype=np.float32)
        off = LEAD_MS
        for t in extra:
            clip = G.load_cv_clip(selection, t, t["clips"][0])
            looped = G.loop_to(clip, n, HZ)
            bed += looped
        want_b = speech_r / (10 ** (BABBLE_SNR_DB / 20.0))
        mix += bed * (want_b / max(G.rms(bed), 1e-8))
        bed_meta["babble_talkers"] = [t["speaker_id"] for t in extra]
        bed_meta["babble_license"] = "looped unused CV17 CC0 clips, −18 dB vs speech (unintelligible bed)"
    mix = apply_nothing_phone_mic(mix, HZ, rng)
    peak = float(np.max(np.abs(mix))) or 1.0
    mix = (mix * (0.89 / peak)).astype(np.float32)
    overlap = A.overlap_stats(utt, int(round(n * 1000.0 / HZ)))
    return {
        "mix": mix,
        "ref": {"transcript": utt},
        "channels": channels,
        "overlap": overlap,
        "beds": bed_meta,
        "duration_ms": int(round(n * 1000.0 / HZ)),
        "dsp": {
            "path": "A_one_mic",
            "n_talkers": N_TALKERS,
            "overlap_ms_at_replica_ends": OVERLAP_MS,
            "distances_m": list(DISTANCES_M),
            "rt60_s": RT60_S,
            "reverb_license": "SYNTHETIC taps+tail (not a recorded IR)",
            "phone_mic": "Nothing-class: 250–4500 Hz roll-off, light compression ratio 3.5, hiss; NOT EDGE_badmic crush",
            "hvac_snr_db_target": HVAC_SNR_DB,
            "babble_snr_db_target": BABBLE_SNR_DB,
        },
    }


def write_smoke(mix: np.ndarray, out: Path) -> None:
    n = min(mix.size, int(1.2 * HZ))
    G.write_wav(out / "smoke.wav", mix[:n])


def main() -> int:
    if not CORPUS_SEL.is_file():
        raise SystemExit(f"missing {CORPUS_SEL}; run python3 scripts/fetch_cv17_ru.py")
    selection = json.loads(CORPUS_SEL.read_text(encoding="utf-8"))
    rng = np.random.default_rng(SEED)
    built = build(selection, rng)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "ref").mkdir(exist_ok=True)
    G.write_wav(OUT / "DEMO_STAGE6.wav", built["mix"])
    dump_json(OUT / "ref" / "DEMO_STAGE6.json", built["ref"])
    write_smoke(built["mix"], OUT)
    recipe = {
        "test_id": "DEMO_STAGE6",
        "role": "primary_demo_fixture",
        "secondary": "20-stream Path B CLEAN+EDGE+WORST / gated_* is regression only",
        "label": "REAL",
        "corpus": "Mozilla Common Voice 17.0 ru test split",
        "license": "CC0-1.0",
        "seed": SEED,
        "n_talkers": N_TALKERS,
        "one_voice_channel": True,
        "duration_ms": built["duration_ms"],
        "overlap": built["overlap"],
        "channels": built["channels"],
        "dsp": built["dsp"],
        "beds": built["beds"],
        "gold": "corpus sentence field (not eSpeak, not ASR)",
        "notes": "Turn-taking with 200 ms end overlap. Do not retune gold to fake low WER.",
    }
    dump_json(OUT / "recipe.json", recipe)
    print(
        f"DEMO_STAGE6 {built['duration_ms']} ms  talkers={N_TALKERS}  "
        f"max_active={built['overlap']['max_active_speakers']}  "
        f"frac_2plus={built['overlap']['frac_speech_frames_2plus']}"
    )
    print(f"wrote {OUT / 'DEMO_STAGE6.wav'} (gitignored) and {OUT / 'ref' / 'DEMO_STAGE6.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
