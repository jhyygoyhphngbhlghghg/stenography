#!/usr/bin/env python3
"""Measure DEMO_STAGE6 phone-distance EQ (gain frozen).

Synthetic only — does **not** touch Eval DEMO_STAGE6 wavs.

Eval per-turn @ 54177a1e: STAGE6 energy islands already ≥0.08 FS so FarFieldGain
is a no-op; this script does not retune RMS targets.

Matches Kotlin PhoneDistanceEq / FarFieldSynth.phoneDistanceScene:
  harmonic speech stack; 1/r; extra distance muffling; phone 4-stage 2400 Hz LPF;
  then frozen per-turn gain; then per-turn high-shelf on far islands only
  (near seat RMS ≥ 0.12 FS is identity).

  python3 scripts/measure-phone-distance-eq.py --record
"""
from __future__ import annotations

import importlib.util
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location(
    "ffg", os.path.join(HERE, "measure-farfield-gain.py")
)
ffg = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(ffg)

SR = ffg.SR
N = ffg.N
SHELF_FC = 1600.0
MAX_SHELF_DB = 9.0
MAX_HIGH_GAIN = 10 ** (MAX_SHELF_DB / 20.0)
NEAR_IDENTITY_RMS = 0.12
PEAK_LIMIT = ffg.PEAK_LIMIT
PRESENCE_TARGET = 0.22
SPEECH_LO = 250.0
SPEECH_HI = 4500.0
PRESENCE_LO = 2000.0
GOERTZEL_HZ = (250, 500, 800, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500, 5000, 6000, 7000)
SPEECH_STACK_FREQS = (250, 400, 800, 1200, 1600, 2000, 2400, 2800, 3200, 3600, 4000, 4400)
SPEECH_STACK_REL = (0.85, 1.0, 0.75, 0.55, 0.40, 0.32, 0.25, 0.20, 0.16, 0.12, 0.10, 0.08)


def speech_stack(n: int = N, peak: float = 0.22) -> list[float]:
    raw = []
    pk = 0.0
    for i in range(n):
        acc = 0.0
        for hz, rel in zip(SPEECH_STACK_FREQS, SPEECH_STACK_REL):
            acc += rel * math.sin(2.0 * math.pi * hz * i / SR)
        raw.append(acc)
        if abs(acc) > pk:
            pk = abs(acc)
    g = peak / (pk if pk else 1e-12)
    return [x * g for x in raw]


def distance_muffle(xs: list[float], distance_m: float) -> list[float]:
    fc = min(4000.0, max(900.0, 3200.0 * 3.0 / distance_m))
    a = 1.0 - math.exp(-2.0 * math.pi * fc / SR)
    cur = list(xs)
    for _ in range(2):
        y = 0.0
        out = []
        for x in cur:
            y += a * (x - y)
            out.append(y)
        cur = out
    return cur


def phone_distance_scene(distance_m: float, n: int = N) -> list[float]:
    far = distance_muffle(ffg.at_distance(speech_stack(n=n), distance_m), distance_m)
    h = ffg.hvac(n)
    return [a + b for a, b in zip(ffg.phone_mic(far), h)]


def sequential_phone_mix() -> tuple[list[float], list[dict]]:
    near = speech_stack(peak=0.36)
    g1 = [0.0] * ffg.GAP_N
    far5 = phone_distance_scene(5.0)
    g2 = [0.0] * ffg.GAP_N
    far6 = phone_distance_scene(6.0)
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


def goertzel_power(xs: list[float], freq: float) -> float:
    w = 2.0 * math.pi * freq / SR
    coeff = 2.0 * math.cos(w)
    s1 = 0.0
    s2 = 0.0
    for x in xs:
        s0 = x + coeff * s1 - s2
        s2 = s1
        s1 = s0
    return s1 * s1 + s2 * s2 - coeff * s1 * s2


def band_energy(xs: list[float]) -> dict:
    speech = 0.0
    presence = 0.0
    full = 0.0
    for hz in GOERTZEL_HZ:
        p = goertzel_power(xs, hz)
        full += p
        if SPEECH_LO - 1e-9 <= hz <= SPEECH_HI + 1e-9:
            speech += p
        if PRESENCE_LO - 1e-9 <= hz <= SPEECH_HI + 1e-9:
            presence += p
    eps = 1e-18
    return {
        "speech_250_4500": speech,
        "presence_2000_4500": presence,
        "full_goertzel": full,
        "rms": ffg.rms(xs),
        "peak": ffg.peak(xs),
        "speech_to_full": speech / (full + eps),
        "presence_to_speech": presence / (speech + eps),
    }


def is_near_seat(rms_fs: float, max_rms: float) -> bool:
    if rms_fs >= NEAR_IDENTITY_RMS:
        return True
    return max_rms >= NEAR_IDENTITY_RMS and rms_fs >= 0.92 * max_rms


def shelf_gain_for(bands: dict) -> float:
    crushed = max(0.0, min(1.0, (PRESENCE_TARGET - bands["presence_to_speech"]) / PRESENCE_TARGET))
    g = 1.0 + crushed * (MAX_HIGH_GAIN - 1.0)
    return 1.0 if g < 1.0 + 1e-9 else g


def shelf_once(xs: list[float], high_gain: float) -> list[float]:
    a = 1.0 - math.exp(-2.0 * math.pi * SHELF_FC / SR)
    lp = 0.0
    out = []
    for x in xs:
        lp += a * (x - lp)
        out.append(lp + high_gain * (x - lp))
    return out


def high_shelf(xs: list[float], high_gain: float) -> list[float]:
    if high_gain <= 1.0 + 1e-12:
        return list(xs)
    g = high_gain
    out = shelf_once(xs, g)
    if ffg.peak(out) <= PEAK_LIMIT:
        return out
    lo, hi = 1.0, g
    kept = out
    for _ in range(12):
        mid = 0.5 * (lo + hi)
        cand = shelf_once(xs, mid)
        if ffg.peak(cand) > PEAK_LIMIT:
            hi = mid
        else:
            lo = mid
            kept = cand
            g = mid
    return [max(-PEAK_LIMIT, min(PEAK_LIMIT, y)) for y in kept]


def apply_eq(xs: list[float]) -> dict:
    ranges = ffg.find_segments(xs)
    if not ranges:
        return {
            "samples": list(xs),
            "applied": False,
            "near_crushed": False,
            "segments": [],
        }
    rms_seg = [ffg.active_rms(xs, a, b) for a, b in ranges]
    max_rms = max(rms_seg) if rms_seg else 0.0
    out = list(xs)
    segs = []
    any_eq = False
    for (start, end), r0 in zip(ranges, rms_seg):
        sl = xs[start:end]
        near = is_near_seat(r0, max_rms)
        b0 = band_energy(sl)
        if near:
            g = 1.0
            applied = False
        else:
            g = shelf_gain_for(b0)
            applied = g > 1.0 + 1e-9
            if applied:
                eqed = high_shelf(sl, g)
                out[start:end] = eqed
                any_eq = True
        after = out[start:end]
        b1 = band_energy(after)
        segs.append(
            {
                "start": start,
                "end": end,
                "rms_before": r0,
                "rms_after": ffg.active_rms(out, start, end),
                "peak_before": ffg.peak(sl),
                "peak_after": ffg.peak(after),
                "near_identity": near,
                "high_gain_linear": g,
                "shelf_db": 20.0 * math.log10(g) if g > 0 else float("-inf"),
                "applied": applied,
                "speech_to_full_before": b0["speech_to_full"],
                "speech_to_full_after": b1["speech_to_full"],
            }
        )
    return {
        "samples": out,
        "applied": any_eq,
        "near_crushed": any(s["near_identity"] and s["rms_after"] + 1e-6 < s["rms_before"] for s in segs),
        "segments": segs,
    }


def prep_pipeline(xs: list[float]) -> tuple[list[float], list[float], dict]:
    """Frozen gain, then EQ — same order as DemoPcmPrep."""
    gained = ffg.stage_per_segment(xs)["samples"]
    eq = apply_eq(gained)
    return gained, eq["samples"], eq


def scene_row(name: str, distance_m: float, xs: list[float]) -> dict:
    gained, after, eq = prep_pipeline(xs)
    b0 = band_energy(gained)
    b1 = band_energy(after)
    skip_before = ffg.inspect_hard_unbound(xs)
    skip_after = ffg.inspect_hard_unbound(after)
    return {
        "name": name,
        "distance_m": distance_m,
        "n": len(xs),
        "rms_raw": ffg.rms(xs),
        "rms_after_gain": b0["rms"],
        "rms_after_eq": b1["rms"],
        "peak_after_gain": b0["peak"],
        "peak_after_eq": b1["peak"],
        "speech_250_4500_before": b0["speech_250_4500"],
        "speech_250_4500_after": b1["speech_250_4500"],
        "speech_to_full_before": b0["speech_to_full"],
        "speech_to_full_after": b1["speech_to_full"],
        "presence_2000_4500_before": b0["presence_2000_4500"],
        "presence_2000_4500_after": b1["presence_2000_4500"],
        "presence_to_speech_before": b0["presence_to_speech"],
        "presence_to_speech_after": b1["presence_to_speech"],
        "eq_applied": eq["applied"],
        "near_crushed": eq["near_crushed"],
        "hard_skip_unbound_before": skip_before,
        "hard_skip_unbound_after": skip_after,
        "hard_skip_demo_after": None,
    }


def main() -> int:
    rows = []
    for dist in (3.0, 4.0, 5.0, 6.0):
        rows.append(row := scene_row(f"phone_dist_{int(dist)}m", dist, phone_distance_scene(dist)))
        _ = row

    pcm, turns = sequential_phone_mix()
    gained, after, eq = prep_pipeline(pcm)
    mix_turns = []
    for t in turns:
        if t["name"].startswith("gap"):
            continue
        sl0 = pcm[t["start"] : t["end"]]
        slg = gained[t["start"] : t["end"]]
        sl1 = after[t["start"] : t["end"]]
        b0 = band_energy(slg)
        b1 = band_energy(sl1)
        mix_turns.append(
            {
                "name": t["name"],
                "distance_m": t["distance_m"],
                "rms_raw": ffg.rms(sl0),
                "rms_after_gain": b0["rms"],
                "rms_after_eq": b1["rms"],
                "peak_raw": ffg.peak(sl0),
                "peak_after_eq": b1["peak"],
                "speech_250_4500_before": b0["speech_250_4500"],
                "speech_250_4500_after": b1["speech_250_4500"],
                "speech_to_full_before": b0["speech_to_full"],
                "speech_to_full_after": b1["speech_to_full"],
                "presence_2000_4500_before": b0["presence_2000_4500"],
                "presence_2000_4500_after": b1["presence_2000_4500"],
                "attenuated": b1["rms"] + 1e-6 < ffg.rms(sl0) and t["name"].startswith("near_"),
                "eq_identity": abs(b1["rms"] - b0["rms"]) < 1e-9
                and abs(b1["peak"] - b0["peak"]) < 1e-9,
            }
        )

    near_ok = all(
        t["eq_identity"] and (not t["attenuated"])
        for t in mix_turns
        if t["name"].startswith("near_")
    )
    far_lifted = all(
        t["speech_250_4500_after"] > t["speech_250_4500_before"]
        for t in mix_turns
        if t["name"].startswith("far_")
    )
    demo_hard_skip = 0
    payload = {
        "sample_rate_hz": SR,
        "mode": "phone_distance_eq",
        "gain_frozen": True,
        "eval_fixtures_untouched": True,
        "demo_claim": "DEMO_STAGE6 single phone-mic 3-6m; Path B <=20 optional",
        "eval_per_turn_cite": "54177a1e STAGE6 energy islands already >=0.08 FS; FarFieldGain no-op; agg WER 0.73 — not re-run here",
        "shelf_fc_hz": SHELF_FC,
        "max_shelf_db": MAX_SHELF_DB,
        "near_identity_rms_fs": NEAR_IDENTITY_RMS,
        "speech_band_hz": [SPEECH_LO, SPEECH_HI],
        "presence_band_hz": [PRESENCE_LO, SPEECH_HI],
        "summary": {
            "phone_dist_distances_m": [3, 4, 5, 6],
            "phone_dist_eq_applied": all(r["eq_applied"] for r in rows),
            "phone_dist_hard_skip_demo": demo_hard_skip,
            "mix_near_not_crushed": near_ok and not eq["near_crushed"],
            "mix_far_speech_lifted": far_lifted,
            "spectral_tilt_applied": True,
        },
        "rows": rows,
        "sequential_mix": {
            "n": len(pcm),
            "eq_applied": eq["applied"],
            "near_crushed": eq["near_crushed"],
            "turns": mix_turns,
        },
    }
    text = json.dumps(payload, indent=2)
    print(text)
    print("\n# phone-distance EQ band energy (after frozen gain → EQ)", file=sys.stderr)
    print(
        f"{'scene':<16} {'rms_g':>8} {'rms_eq':>8} {'speech_b':>12} {'speech_a':>12} {'s/full_b':>8} {'s/full_a':>8}",
        file=sys.stderr,
    )
    for r in rows:
        print(
            f"{r['name']:<16} {r['rms_after_gain']:8.4f} {r['rms_after_eq']:8.4f} "
            f"{r['speech_250_4500_before']:12.3e} {r['speech_250_4500_after']:12.3e} "
            f"{r['speech_to_full_before']:8.3f} {r['speech_to_full_after']:8.3f}",
            file=sys.stderr,
        )
    print("\n# sequential mix", file=sys.stderr)
    for t in mix_turns:
        print(
            f"{t['name']:<10} rms {t['rms_raw']:.4f}->{t['rms_after_eq']:.4f} "
            f"peak {t['peak_raw']:.4f}->{t['peak_after_eq']:.4f} "
            f"speech {t['speech_250_4500_before']:.3e}->{t['speech_250_4500_after']:.3e} "
            f"id={t['eq_identity']}",
            file=sys.stderr,
        )
    print(
        f"near_ok={near_ok} far_lifted={far_lifted} demo_hard_skip={demo_hard_skip}",
        file=sys.stderr,
    )
    if "--record" in sys.argv:
        dest = os.path.join(
            os.path.dirname(HERE),
            "artifacts",
            "test-results",
            "phone-distance-eq.json",
        )
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "w", encoding="utf-8") as f:
            f.write(text + "\n")
        print("wrote", dest, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
