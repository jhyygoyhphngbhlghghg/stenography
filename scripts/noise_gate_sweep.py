#!/usr/bin/env python3
"""Noise-gate sweep for the casual-mic phantom-speaker bug (present branch).

Hypothesis (user device test, 3 persons -> 7 speakers even at
diarClusterThreshold=0.65 / SAME_VOICE_MAX_DISTANCE=0.60): background noise
bursts become VAD islands -> pyannote segments -> scattered noise embeddings
-> their own clusters; with expectedSpeakers=-1 the reconcile returns the
clustering as-is and SpeakerIdentityMerge re-roots every unglued fragment as a
fresh label.

Fixtures (built here, deterministic numpy noise, scripts/make_noise_fixtures):
  (a) casual 3spk clean + 0.3-1.5 s pink/white bursts at -20/-15/-10 dBFS
      (speech-peak-relative) between turns + one burst overlapping a turn
  (b) (a) + continuous pink room noise at -35 dBFS
  (c) clean casual 2spk and 3spk (regression guards: agree must not drop)
  (d) frozen stage6 mix guard (app-side the gate is ONLY applied when
      scene.expectedSpeakers <= 0, so stage6 is untouched by construction;
      we still run the gate mirror over its raw segments as a worst-case check)

Measurements:
  1. PRE-GATE at the current shipped params (casual ct=0.65, merge 0.60):
     label count vs truth, and per-label phantom evidence (label speech RMS
     vs global median segment RMS; overlap with truth speech turns).
  2. Gate grid noiseGateMinMs {500,700,900} x noiseGateRmsRatio
     {0.15,0.25,0.35}: drop raw segments with dur < minMs OR rms <
     ratio*median(segment rms) AFTER raw mapping, BEFORE reconcile/merge
     (mirror of pipeline/NoiseGate.kt). Diarization + per-segment embeddings
     run ONCE per fixture; the grid reuses them (the gate only drops rows).

Usage (repo root, host venv with sherpa-onnx 2.0.1):
  ../neuro-stenographer/.venv/Scripts/python.exe scripts/noise_gate_sweep.py
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import host_asr_diar as HOST  # noqa: E402
import make_noise_fixtures as NF  # noqa: E402
import mic_diar_sweep as M  # noqa: E402

CT = 0.65          # shipped casual diarClusterThreshold (e6aa331)
MERGE_THR = 0.60   # shipped SAME_VOICE_MAX_DISTANCE (e6aa331)
GATE_MIN_MS = [500, 700, 900]
GATE_RMS_RATIO = [0.15, 0.25, 0.35]

NOISE_A = "samples/demo-stress/casual_3spk_noise.wav"
NOISE_B = "samples/demo-stress/casual_3spk_noise_room.wav"


def seg_rms(work: np.ndarray, sr: int, s: dict) -> float:
    a = max(0, min(int(s["start_ms"] * sr // 1000), work.size))
    b = max(a, min(int(s["end_ms"] * sr // 1000), work.size))
    if b - a < 2:
        return 0.0
    x = work[a:b].astype(np.float64)
    return float(np.sqrt(np.mean(x * x)))


def noise_gate(segs: list[dict], work: np.ndarray, sr: int, min_ms: int, rms_ratio: float):
    """Mirror of pipeline/NoiseGate.kt: drop segments with dur < min_ms OR
    rms < rms_ratio * median(segment rms)."""
    if not segs or min_ms <= 0 or rms_ratio <= 0.0:
        return list(segs)
    rmss = [seg_rms(work, sr, s) for s in segs]
    med = float(np.median(rmss))
    keep = []
    for s, r in zip(segs, rmss):
        if (s["end_ms"] - s["start_ms"]) >= min_ms and r >= rms_ratio * med:
            keep.append(s)
    return keep


def label_stats(merged: list[dict], work: np.ndarray, sr: int, truth: list[dict]) -> list[dict]:
    """Per-label: ms, ms overlapping truth speech, label RMS, RMS/global-median."""
    all_rms = [seg_rms(work, sr, s) for s in merged]
    med = float(np.median(all_rms)) if all_rms else 0.0
    out = []
    for lab in sorted({s["speaker_index"] for s in merged}):
        rows = [s for s in merged if s["speaker_index"] == lab]
        ms = sum(s["end_ms"] - s["start_ms"] for s in rows)
        ov = 0
        for s in rows:
            for t in truth:
                ov += max(0, min(s["end_ms"], t["end_ms"]) - max(s["start_ms"], t["start_ms"]))
        e = 0.0
        n = 0
        for s in rows:
            a = max(0, min(int(s["start_ms"] * sr // 1000), work.size))
            b = max(a, min(int(s["end_ms"] * sr // 1000), work.size))
            e += float(np.sum(work[a:b].astype(np.float64) ** 2))
            n += b - a
        rms = (e / n) ** 0.5 if n else 0.0
        out.append({
            "label": lab, "ms": ms,
            "speech_overlap_frac": (ov / ms) if ms else 0.0,
            "rms": rms, "rms_over_median": (rms / med) if med > 0 else 0.0,
            "phantom": ms > 0 and (ov / ms) < 0.3,
        })
    return out


def safe_merge(segs, embeddings, thr):
    """merge_union_find returns a 2-tuple for degenerate inputs; normalize."""
    out = M.merge_union_find(segs, embeddings, thr)
    if len(out) == 4:
        return out
    merged, n_u = out
    l = len({s["speaker_index"] for s in merged})
    return merged, n_u, l, l


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", type=Path, default=None)
    ap.add_argument("--main-root", type=Path, default=None)
    args = ap.parse_args()
    main_root = args.main_root or ROOT.parent / "neuro-stenographer"
    extra_roots = [main_root] if main_root.is_dir() else []
    models = args.models or next(
        (r / "models" for r in [ROOT, *extra_roots] if (r / "models" / "diar").is_dir()), None
    )
    if models is None:
        print("BLOCKED: no models/diar", file=sys.stderr)
        return 2
    stem_a = M.resolve(M.STEM_A, extra_roots)
    stem_b = M.resolve(M.STEM_B, extra_roots)
    stem_c = M.resolve(M.STEM_C, extra_roots)
    stage6_wav = M.resolve(M.STAGE6_WAV, extra_roots)
    stage6_ref = M.resolve(M.STAGE6_REF, extra_roots)
    if not (stem_a and stem_b and stem_c and stage6_wav and stage6_ref):
        print("BLOCKED: fixture inputs missing", file=sys.stderr)
        return 2

    # ---- fixtures -------------------------------------------------------
    truth3 = M.build_casual_wav(ROOT / M.CASUAL3_OUT, [(stem_a, "A"), (stem_b, "B"), (stem_c, "C")])
    truth2 = M.build_casual_wav(ROOT / M.CASUAL_OUT, [(stem_a, "A"), (stem_b, "B")])
    clean3, sr = HOST.load_wav_mono16k(ROOT / M.CASUAL3_OUT)
    clean2, _ = HOST.load_wav_mono16k(ROOT / M.CASUAL_OUT)
    events, wav_a, wav_b = NF.build_noise_fixtures(
        clean3, truth3, ROOT / NOISE_A, ROOT / NOISE_B, M.write_wav
    )
    print(f"fixture (a): {len(events)} noise bursts -> {NOISE_A}")
    for ev in events:
        print(f"  burst {ev['start_s']:.2f}-{ev['end_s']:.2f}s {ev['level_db']:+.0f}dB {ev['kind']}"
              f"{' OVERLAPS-TURN' if ev.get('overlaps_turn') else ''}")
    print(f"fixture (b): (a) + continuous pink room -35 dBFS -> {NOISE_B}")
    samples_s6, _ = HOST.load_wav_mono16k(stage6_wav)
    ref = json.loads(stage6_ref.read_text(encoding="utf-8"))
    truth_s6 = [
        {"speaker": u["speaker_id"], "start_ms": int(u["start_ms"]), "end_ms": int(u["end_ms"])}
        for u in ref["transcript"]
    ]

    fixtures = [
        ("noise_a", wav_a, truth3, 3),
        ("noise_b", wav_b, truth3, 3),
        ("clean_3spk", clean3, truth3, 3),
        ("clean_2spk", clean2, truth2, 2),
    ]

    results = []
    for name, samples, truth, n_truth in fixtures:
        t0 = time.perf_counter()
        work, mapped, reconciled = M.run_app_diar_path(
            samples, sr, models, 0, scene=M.CASUAL, cluster_threshold=CT
        )
        embeddings, n_null = M.embed_segments(work, sr, reconciled, models)
        print(f"\n=== {name}: raw_labels={len({m['speaker_index'] for m in mapped})} "
              f"raw_segs={len(mapped)} null_emb={n_null}/{len(reconciled)} "
              f"wall={time.perf_counter()-t0:.1f}s (ct={CT}) ===")

        # PRE-GATE (current shipped behaviour)
        merged0, _, l_b, l_a0 = safe_merge(reconciled, embeddings, MERGE_THR)
        ev0 = M.eval_labels(merged0, truth)
        ls = label_stats(merged0, work, sr, truth)
        phantoms = [l for l in ls if l["phantom"]]
        print(f"  PRE-GATE labels {l_b}->{l_a0} (truth {n_truth}) "
              f"agree={M.f2(ev0['seg_agreement'])} purity={M.f2(ev0['turn_purity'])}")
        for l in ls:
            print(f"    label {l['label']}: {l['ms']}ms speech_overlap={l['speech_overlap_frac']:.2f} "
                  f"rms/median={l['rms_over_median']:.2f}"
                  + ("  <-- PHANTOM (noise-only)" if l["phantom"] else ""))
        results.append({"wav": name, "gate_min_ms": None, "gate_rms_ratio": None,
                        "labels_before": l_b, "labels_after": l_a0, "n_truth": n_truth,
                        "n_phantom_labels": len(phantoms), **ev0})

        if name.startswith("noise"):
            burst_ms = [(int(e["start_s"] * 1000), int(e["end_s"] * 1000)) for e in events]
        else:
            burst_ms = []

        # GATE GRID (diar + embeddings reused; gate only drops rows)
        for gmin in GATE_MIN_MS:
            for gr in GATE_RMS_RATIO:
                gated = noise_gate(reconciled, work, sr, gmin, gr)
                idx = [reconciled.index(s) for s in gated]
                emb_g = [embeddings[i] for i in idx]
                merged, n_u, l_bg, l_ag = safe_merge(gated, emb_g, MERGE_THR)
                ev = M.eval_labels(merged, truth)
                ls2 = label_stats(merged, work, sr, truth)
                ph = sum(1 for l in ls2 if l["phantom"])
                dropped = len(reconciled) - len(gated)
                # bursts fully removed from the hypothesis?
                cov = 0
                for (b0, b1) in burst_ms:
                    for s in merged:
                        if min(s["end_ms"], b1) - max(s["start_ms"], b0) > 100:
                            cov += 1
                            break
                row = {"wav": name, "gate_min_ms": gmin, "gate_rms_ratio": gr,
                       "labels_before": l_bg, "labels_after": l_ag, "n_truth": n_truth,
                       "dropped_segs": dropped, "n_phantom_labels": ph,
                       "bursts_hyp_covered": f"{cov}/{len(burst_ms)}" if burst_ms else "-",
                       **ev}
                results.append(row)
                print(f"  gate min_ms={gmin} rms_ratio={gr:.2f}: segs -{dropped}, "
                      f"labels {l_bg}->{l_ag} (truth {n_truth}), phantom={ph}, "
                      f"agree={M.f2(ev['seg_agreement'])} purity={M.f2(ev['turn_purity'])} "
                      f"bursts_covered={row['bursts_hyp_covered']}")

    # ---- stage6 worst-case guard (gate NOT applied on device: expectedSpeakers=6)
    t0 = time.perf_counter()
    work6, mapped6, rec6 = M.run_app_diar_path(samples_s6, sr, models, 0, scene=M.SCENE,
                                               cluster_threshold=float(M.SCENE["diar_cluster_threshold"]))
    emb6, _ = M.embed_segments(work6, sr, rec6, models)
    merged6, _, lb6, la6 = safe_merge(rec6, emb6, MERGE_THR)
    ev6 = M.eval_seats(merged6, truth_s6)
    agree6 = M.eval_labels(merged6, truth_s6)["seg_agreement"]
    print(f"\n=== stage6 guard (frozen scene, wall={time.perf_counter()-t0:.1f}s): "
          f"labels {lb6}->{la6} seats={ev6['seats_correct']}/{ev6['seats_total']} "
          f"agree={M.f2(agree6)} ===")
    results.append({"wav": "stage6_mix", "gate_min_ms": None, "gate_rms_ratio": None,
                    "labels_after": la6, "n_truth": 6, "seg_agreement": agree6, **ev6})
    for gmin in GATE_MIN_MS:
        for gr in GATE_RMS_RATIO:
            gated = noise_gate(rec6, work6, sr, gmin, gr)
            idx = [rec6.index(s) for s in gated]
            mg, _, _, la = safe_merge(gated, [emb6[i] for i in idx], MERGE_THR)
            evg = M.eval_seats(mg, truth_s6)
            ag = M.eval_labels(mg, truth_s6)["seg_agreement"]
            ok = "OK" if (evg["seats_correct"], (ag or 0)) >= (ev6["seats_correct"], (agree6 or 0) - 1e-9) else "REGRESSION"
            results.append({"wav": "stage6_mix", "gate_min_ms": gmin, "gate_rms_ratio": gr,
                            "labels_after": la, "n_truth": 6, "seg_agreement": ag, **evg})
            print(f"  worst-case gate min_ms={gmin} rms_ratio={gr:.2f}: seats={evg['seats_correct']}/6 "
                  f"agree={M.f2(ag)} labels={la} [{ok}]")

    # ---- PICK -----------------------------------------------------------
    def get(wav, gmin, gr):
        return next((r for r in results if r["wav"] == wav and r["gate_min_ms"] == gmin
                     and r["gate_rms_ratio"] == gr), None)

    print("\n=== PICK ===")
    base = {w: get(w, None, None) for w in ("noise_a", "noise_b", "clean_3spk", "clean_2spk")}
    best = None
    for gmin in GATE_MIN_MS:
        for gr in GATE_RMS_RATIO:
            ra, rb = get("noise_a", gmin, gr), get("noise_b", gmin, gr)
            g3, g2 = get("clean_3spk", gmin, gr), get("clean_2spk", gmin, gr)
            s6_bad = any(
                (r["gate_min_ms"], r["gate_rms_ratio"]) == (gmin, gr)
                and r["wav"] == "stage6_mix"
                and (r.get("seats_correct", 6) < ev6["seats_correct"]
                     or (r.get("seg_agreement") or 0) < (agree6 or 0) - 1e-9)
                for r in results
            )
            if not (ra and rb and g3 and g2) or s6_bad:
                continue
            # guards: clean-fixture agreement must not drop vs pre-gate
            if (g3.get("seg_agreement") or 0) < (base["clean_3spk"].get("seg_agreement") or 0) - 1e-9:
                continue
            if (g2.get("seg_agreement") or 0) < (base["clean_2spk"].get("seg_agreement") or 0) - 1e-9:
                continue
            key = (max(ra["labels_after"], rb["labels_after"]),
                   ra["n_phantom_labels"] + rb["n_phantom_labels"],
                   -(min(g3.get("seg_agreement") or 0, g2.get("seg_agreement") or 0)),
                   gmin, gr)
            if best is None or key < best[0]:
                best = (key, gmin, gr)
    if best:
        _, gmin, gr = best
        print(f"PICKED noiseGateMinMs={gmin} noiseGateRmsRatio={gr}")
        for w in ("noise_a", "noise_b", "clean_3spk", "clean_2spk"):
            r0, r1 = base[w], get(w, gmin, gr)
            print(f"  {w}: labels {r0['labels_after']}->{r1['labels_after']} (truth {r0['n_truth']}) "
                  f"phantom {r0['n_phantom_labels']}->{r1['n_phantom_labels']} "
                  f"agree {M.f2(r0['seg_agreement'])}->{M.f2(r1['seg_agreement'])} "
                  f"purity {M.f2(r0['turn_purity'])}->{M.f2(r1['turn_purity'])}")
    else:
        print("NO GATE CONFIG satisfied the guards")

    out_json = ROOT / "artifacts" / "noise_gate_sweep.json"
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps({"ct": CT, "merge_thr": MERGE_THR, "rows": results}, indent=1),
                        encoding="utf-8")
    print(f"\nwrote {out_json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
