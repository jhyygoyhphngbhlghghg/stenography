#!/usr/bin/env python3
"""Host repro + parameter sweep for the live-mic diarization churn bug.

Symptom (real device, stenographer flavor, demo APK @ 1906eaa): a casual
2-person mic recording gets a NEW speaker label on (nearly) every line.

App path mirrored here (SherpaStenoPipeline.transcribeAsr, stage6 scene):
  FarFieldGain.stage per-turn  -> energy regions + silero VAD union
  -> OfflineSpeakerDiarization(pyannote seg + 3D-Speaker emb +
     FastClustering(numClusters=scene.expectedSpeakers=6, threshold=0.5))
  -> VadDiarReconcile.run (stage6 scene params; host mirror
     scripts/vad_diar_reconcile.py — mirror of 3c3f9ce, close enough for the
     clustering/merge question; the K=6 hard cap semantics are identical)
  -> per-segment embedding (embedSegment: 1.5 s windows, unit-norm average)
  -> SpeakerIdentityMerge.merge (union-find at SAME_VOICE_MAX_DISTANCE)

Matrix: numClusters in {6, 0(threshold 0.5 = auto)} x mergeThreshold in
{0.45, 0.55, 0.60, off}, run on:
  (a) casual 2-speaker wav — BUILT here by alternating-concatenating the
      committed handoff slices stem_or_slice_speaker_1_3m.wav (3 m seat) and
      stem_or_slice_speaker_2_3p6m.wav (3.6 m seat); written to
      samples/demo-stress/casual_2spk_mic.wav (gitignored).
  (b) the frozen 6-speaker stage6 mix samples/demo-stress/stage6/DEMO_STAGE6.wav
      (gitignored; found in the worktree or the primary checkout, gold ref in
      samples/demo-stress/stage6/ref/DEMO_STAGE6.json).

Also prints same-voice vs diff-voice per-segment embedding distance
distributions on the casual wav (the evidence for the merge threshold).

Does not invent metrics: everything printed is measured on this host.

Usage (from the repo root, host venv with sherpa-onnx 2.0.1):
  .venv/Scripts/python.exe scripts/mic_diar_sweep.py \
      [--models ../neuro-stenographer/models] \
      [--stage6-wav ../neuro-stenographer/samples/demo-stress/stage6/DEMO_STAGE6.wav]
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import wave
from pathlib import Path

import numpy as np

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import farfield_gain as FF  # noqa: E402
import host_asr_diar as HOST  # noqa: E402
import vad_diar_reconcile as RECON  # noqa: E402

SCENE = RECON.STAGE6  # host mirror of FarFieldScene.stage6 (values verified equal)

STEM_A = "artifacts/demo-stage6-handoff/stem_or_slice_speaker_1_3m.wav"
STEM_B = "artifacts/demo-stage6-handoff/stem_or_slice_speaker_2_3p6m.wav"
CASUAL_OUT = "samples/demo-stress/casual_2spk_mic.wav"
STAGE6_WAV = "samples/demo-stress/stage6/DEMO_STAGE6.wav"
STAGE6_REF = "samples/demo-stress/stage6/ref/DEMO_STAGE6.json"

MERGE_THRESHOLDS = [0.45, 0.55, 0.60, None]  # None = merge off
NUM_CLUSTERS_GRID = [6, 0]  # 0 => FastClustering threshold path (auto)


# ---------------------------------------------------------------- utilities
def resolve(rel: str, extra_roots: list[Path]) -> Path | None:
    for root in [ROOT, *extra_roots]:
        p = root / rel
        if p.is_file():
            return p
    return None


def write_wav(path: Path, samples: np.ndarray, sr: int = 16000) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    s16 = np.clip(samples, -1.0, 1.0)
    pcm = (s16 * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


# ------------------------------------------------- casual 2-speaker fixture
def slice_turns(x: np.ndarray, sr: int, turn_s: float = 3.0, min_turn_s: float = 0.6):
    """Fixed-length turns of one slice. The handoff slices are continuous
    far-field speech (frame RMS never dips below the 4% energy-VAD floor), so
    phrase-level VAD cannot split them; deterministic 3 s windows are the turns.
    """
    n = int(turn_s * sr)
    turns = []
    a = 0
    while a < x.size:
        b = min(a + n, x.size)
        if b - a >= int(min_turn_s * sr):
            turns.append((a, b))
        a = b
    return turns


def build_casual_wav(out_path: Path, stem_a: Path, stem_b: Path) -> list[dict]:
    """Alternate-concatenate turns of the two handoff slices. Returns truth turns."""
    xa, sr = HOST.load_wav_mono16k(stem_a)
    xb, sr2 = HOST.load_wav_mono16k(stem_b)
    assert sr == sr2 == 16000
    ta = slice_turns(xa, sr)
    tb = slice_turns(xb, sr)
    if not ta or not tb:
        raise SystemExit(f"BLOCKED: no turns found in slices ({len(ta)}/{len(tb)})")
    gap = np.zeros(int(0.40 * sr), dtype=np.float32)
    chunks: list[np.ndarray] = []
    truth: list[dict] = []
    pos = 0
    n = max(len(ta), len(tb))
    for i in range(n):
        for turns, x, spk in ((ta, xa, "A"), (tb, xb, "B")):
            if i >= len(turns):
                continue
            a, b = turns[i]
            seg = x[a:b]
            if chunks:
                chunks.append(gap)
                pos += gap.size
            chunks.append(seg)
            truth.append(
                {"speaker": spk, "start_ms": pos * 1000 // sr, "end_ms": (pos + seg.size) * 1000 // sr}
            )
            pos += seg.size
    wav = np.concatenate(chunks).astype(np.float32)
    write_wav(out_path, wav, sr)
    return truth


# ------------------------------------------------------- app path (mirror)
def run_app_diar_path(samples: np.ndarray, sr: int, models: Path, num_clusters: int):
    """FarFieldGain per-turn -> VAD union -> diarization -> reconcile. Mirrors
    SherpaStenoPipeline.transcribeAsr + runDiarizerAndReconcile (stage6)."""
    import sherpa_onnx

    staged = FF.stage(samples, per_turn=True)
    work = staged["samples"].astype(np.float32)
    energy = [
        {"start_sample": int(s["start"]), "end_sample": int(s["end"])} for s in staged["segments"]
    ]
    silero = HOST.collect_silero(
        work,
        sr,
        models,
        threshold=float(SCENE["vad_threshold"]),
        min_silence_sec=float(SCENE["vad_min_silence_sec"]),
        min_speech_sec=float(SCENE["vad_min_speech_sec"]),
        max_speech_sec=float(SCENE["vad_max_speech_sec"]),
    )
    vad = RECON.union_regions(energy, silero)

    d = HOST.find_diar(models)
    cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                model=str(d["segmentation"]),
            ),
        ),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(
            model=str(d["embedding"]), num_threads=2, provider="cpu"
        ),
        clustering=sherpa_onnx.FastClusteringConfig(
            num_clusters=num_clusters, threshold=float(SCENE["diar_cluster_threshold"])
        ),
        min_duration_on=float(SCENE["diar_min_duration_on_sec"]),
        min_duration_off=float(SCENE["diar_min_duration_off_sec"]),
    )
    sd = sherpa_onnx.OfflineSpeakerDiarization(cfg)
    raw = sd.process(work)
    segs = list(raw.sort_by_start_time()) if hasattr(raw, "sort_by_start_time") else sorted(raw, key=lambda r: float(r.start))
    mapped = [
        {
            "speaker_index": int(s.speaker),
            "start_ms": int(round(float(s.start) * 1000.0)),
            "end_ms": int(round(float(s.end) * 1000.0)),
        }
        for s in segs
    ]
    reconciled = RECON.run(vad, mapped, sr, SCENE, samples=work)
    return work, mapped, reconciled


# ------------------------------------- embedSegment / SpeakerIdentityMerge
WINDOW_MS = 1500
MIN_EMBED_MS = 300


def embedding_windows(start_ms: int, end_ms: int) -> list[tuple[int, int]]:
    dur = end_ms - start_ms
    if dur < MIN_EMBED_MS:
        return []
    if dur <= WINDOW_MS:
        return [(start_ms, end_ms)]
    span = dur - WINDOW_MS
    return [(start_ms + a, start_ms + a + WINDOW_MS) for a in (0, span // 2, span)]


def embed_audio(extractor, crop: np.ndarray, sr: int):
    if crop.size == 0:
        return None
    stream = extractor.create_stream()
    try:
        stream.accept_waveform(sr, crop.astype(np.float32))
        stream.input_finished()
        if not extractor.is_ready(stream):
            return None
        return np.asarray(extractor.compute(stream), dtype=np.float32)
    finally:
        pass


def embed_segments(work: np.ndarray, sr: int, segs: list[dict], models: Path):
    """Per-segment unit-norm average embedding (SherpaStenoPipeline.embedSegment)."""
    import sherpa_onnx

    d = HOST.find_diar(models)
    extractor = sherpa_onnx.SpeakerEmbeddingExtractor(
        sherpa_onnx.SpeakerEmbeddingExtractorConfig(
            model=str(d["embedding"]), num_threads=2, provider="cpu"
        )
    )
    out: list[np.ndarray | None] = []
    n_null = 0
    for s in segs:
        acc = None
        for w0, w1 in embedding_windows(int(s["start_ms"]), int(s["end_ms"])):
            a = max(0, min(int(w0 * sr // 1000), work.size))
            b = max(a, min(int(w1 * sr // 1000), work.size))
            if b - a < sr // 10:
                continue
            e = embed_audio(extractor, work[a:b], sr)
            if e is None:
                continue
            nrm = float(np.linalg.norm(e))
            if nrm <= 0:
                continue
            acc = e / nrm if acc is None else acc + e / nrm
        if acc is None:
            out.append(None)
            n_null += 1
            continue
        nrm = float(np.linalg.norm(acc))
        out.append(None if nrm <= 0 else (acc / nrm).astype(np.float32))
    return out, n_null


def merge_union_find(segs: list[dict], embeddings, threshold: float | None):
    """Mirror of SpeakerIdentityMerge.merge (incl. null-stub attach).
    Returns (merged_segs, n_unions, labels_before, labels_after)."""
    if threshold is None or len(segs) <= 1:
        return [dict(s) for s in segs], 0
    parent = list(range(len(segs)))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[max(ra, rb)] = min(ra, rb)
            return 1
        return 0

    n_unions = 0
    for i in range(len(segs)):
        a = embeddings[i]
        if a is None:
            continue
        for j in range(i + 1, len(segs)):
            b = embeddings[j]
            if b is None:
                continue
            dist = float(1.0 - np.dot(a, b))
            if dist <= threshold:
                n_unions += union(i, j)

    def gap(i, j):
        A, B = segs[i], segs[j]
        if A["end_ms"] <= B["start_ms"]:
            return B["start_ms"] - A["end_ms"]
        if B["end_ms"] <= A["start_ms"]:
            return A["start_ms"] - B["end_ms"]
        return 0

    for i in range(len(segs)):
        if embeddings[i] is not None:
            continue
        same_label = [
            j for j in range(len(segs)) if j != i and embeddings[j] is not None and segs[j]["speaker_index"] == segs[i]["speaker_index"]
        ]
        host = min(same_label, key=lambda j: gap(j, i)) if same_label else None
        if host is None:
            emb_idx = [j for j in range(len(segs)) if embeddings[j] is not None]
            host = min(emb_idx, key=lambda j: gap(j, i)) if emb_idx else None
        if host is not None:
            union(i, host)

    labels_before = len({s["speaker_index"] for s in segs})
    roots = [find(i) for i in range(len(segs))]
    merged = [dict(s, speaker_index=r) for s, r in zip(segs, roots)]
    # relabel by appearance
    order, seen = [], set()
    for s in sorted(merged, key=lambda x: x["start_ms"]):
        if s["speaker_index"] not in seen:
            seen.add(s["speaker_index"])
            order.append(s["speaker_index"])
    remap = {old: new for new, old in enumerate(order)}
    for s in merged:
        s["speaker_index"] = remap[s["speaker_index"]]
    labels_after = len({s["speaker_index"] for s in merged})
    return merged, n_unions, labels_before, labels_after


# ------------------------------------------------------------- truth evals
def overlap_ms(a0, a1, b0, b1):
    return max(0, min(a1, b1) - max(a0, b0))


def dominant_truth(seg: dict, truth: list[dict]):
    best, best_ov = None, 0
    for t in truth:
        ov = overlap_ms(seg["start_ms"], seg["end_ms"], t["start_ms"], t["end_ms"])
        if ov > best_ov:
            best, best_ov = t["speaker"], ov
    return best if best_ov > 0 else None


def eval_labels(segs: list[dict], truth: list[dict]) -> dict:
    """hyp->truth majority map (by overlap ms); per-segment agreement."""
    votes: dict[int, dict[str, int]] = {}
    per_seg = []
    for s in sorted(segs, key=lambda x: x["start_ms"]):
        best_t, best_ov = None, 0
        for t in truth:
            ov = overlap_ms(s["start_ms"], s["end_ms"], t["start_ms"], t["end_ms"])
            if ov > best_ov:
                best_t, best_ov = t["speaker"], ov
            if ov:
                votes.setdefault(s["speaker_index"], {}).setdefault(t["speaker"], 0)
                votes[s["speaker_index"]][t["speaker"]] += ov
        per_seg.append((s["speaker_index"], best_t))
    hmap = {h: max(v, key=v.get) for h, v in votes.items()}
    scored = [(h, t) for h, t in per_seg if t is not None and h in hmap]
    agree = sum(1 for h, t in scored if hmap[h] == t)
    return {
        "n_hyp_labels": len({s["speaker_index"] for s in segs}),
        "hyp_to_truth": hmap,
        "seg_agreement": (agree / len(scored)) if scored else None,
        "n_seg_scored": len(scored),
        "truth_seq": "".join(t or "." for _, t in per_seg),
        "hyp_seq_mapped": "".join(str(hmap.get(h, "?")) for h, _ in per_seg),
    }


def eval_seats(segs: list[dict], ref_turns: list[dict]) -> dict:
    """stage6 seat check: mutual-best hyp<->ref-speaker mapping by overlap ms."""
    pairs: dict[tuple[str, int], int] = {}
    for s in segs:
        for t in ref_turns:
            ov = overlap_ms(s["start_ms"], s["end_ms"], t["start_ms"], t["end_ms"])
            if ov:
                pairs[(t["speaker"], s["speaker_index"])] = pairs.get((t["speaker"], s["speaker_index"]), 0) + ov
    best_hyp_for_ref: dict[str, int] = {}
    for (spk, h), ov in pairs.items():
        if ov > pairs.get((spk, best_hyp_for_ref.get(spk, -1)), 0):
            best_hyp_for_ref[spk] = h
    best_ref_for_hyp: dict[int, str] = {}
    for (spk, h), ov in pairs.items():
        if ov > pairs.get((best_ref_for_hyp.get(h, ""), h), 0):
            best_ref_for_hyp[h] = spk
    correct = [
        spk for spk, h in best_hyp_for_ref.items() if best_ref_for_hyp.get(h) == spk
    ]
    n_ref = len({t["speaker"] for t in ref_turns})
    return {
        "n_hyp_labels": len({s["speaker_index"] for s in segs}),
        "seats_correct": len(correct),
        "seats_total": n_ref,
        "correct_seat_ids": sorted(correct),
    }


# --------------------------------------------------------------------- main
def dist_stats(vals: list[float]) -> str:
    if not vals:
        return "n=0"
    a = np.asarray(vals)
    return (
        f"n={a.size} min={a.min():.3f} p25={np.percentile(a,25):.3f} "
        f"med={np.median(a):.3f} p75={np.percentile(a,75):.3f} max={a.max():.3f}"
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", type=Path, default=None)
    ap.add_argument("--stage6-wav", type=Path, default=None)
    ap.add_argument("--main-root", type=Path, default=None,
                    help="primary checkout with gitignored models/wavs "
                         "(default: sibling of this worktree)")
    args = ap.parse_args()

    main_root = args.main_root or ROOT.parent / "neuro-stenographer"
    extra_roots = [main_root] if main_root.is_dir() else []
    models = args.models or next(
        (r / "models" for r in [ROOT, *extra_roots] if (r / "models" / "diar").is_dir()), None
    )
    if models is None:
        print("BLOCKED: no models/ dir with diar ONNX found", file=sys.stderr)
        return 2
    stem_a = resolve(STEM_A, extra_roots)
    stem_b = resolve(STEM_B, extra_roots)
    stage6_wav = args.stage6_wav or resolve(STAGE6_WAV, extra_roots)
    stage6_ref = resolve(STAGE6_REF, extra_roots)
    if not stem_a or not stem_b:
        print("BLOCKED: handoff slices missing", file=sys.stderr)
        return 2
    if not stage6_wav or not stage6_ref:
        print("BLOCKED: DEMO_STAGE6 wav/ref missing (gitignored; pass --stage6-wav)", file=sys.stderr)
        return 2

    casual_path = ROOT / CASUAL_OUT
    truth_a = build_casual_wav(casual_path, stem_a, stem_b)
    print(f"built casual 2spk wav: {casual_path} "
          f"({sum(t['end_ms']-t['start_ms'] for t in truth_a)/1000:.1f}s speech, "
          f"{len(truth_a)} turns, truth seq {''.join(t['speaker'] for t in truth_a)})")
    samples_a, sr = HOST.load_wav_mono16k(casual_path)
    samples_b, _ = HOST.load_wav_mono16k(stage6_wav)
    ref = json.loads(stage6_ref.read_text(encoding="utf-8"))
    truth_b = [
        {"speaker": u["speaker_id"], "start_ms": int(u["start_ms"]), "end_ms": int(u["end_ms"])}
        for u in ref["transcript"]
    ]

    wavs = [
        ("casual_2spk", samples_a, truth_a, eval_labels),
        ("stage6_mix", samples_b, truth_b, eval_seats),
    ]

    results = []
    for name, samples, truth, evalf in wavs:
        for nc in NUM_CLUSTERS_GRID:
            t0 = time.perf_counter()
            work, mapped, reconciled = run_app_diar_path(samples, sr, models, nc)
            embeddings, n_null = embed_segments(work, sr, reconciled, models)
            diar_wall = time.perf_counter() - t0
            n_pre = len({s["speaker_index"] for s in reconciled})
            print(f"\n=== {name} numClusters={nc} ===  "
                  f"diar_raw_segs={len(mapped)} diar_raw_labels={len({m['speaker_index'] for m in mapped})} "
                  f"reconciled_segs={len(reconciled)} reconciled_labels={n_pre} "
                  f"null_embeddings={n_null}/{len(reconciled)} wall={diar_wall:.1f}s")
            if name == "casual_2spk":
                # distance distributions on the casual wav (reconciled segments)
                seg_spk = [dominant_truth(s, truth) for s in reconciled]
                same, diff, same_long, diff_long = [], [], [], []
                for i in range(len(reconciled)):
                    for j in range(i + 1, len(reconciled)):
                        if embeddings[i] is None or embeddings[j] is None:
                            continue
                        dd = float(1.0 - np.dot(embeddings[i], embeddings[j]))
                        li = reconciled[i]["end_ms"] - reconciled[i]["start_ms"] >= 1500
                        lj = reconciled[j]["end_ms"] - reconciled[j]["start_ms"] >= 1500
                        if seg_spk[i] is not None and seg_spk[i] == seg_spk[j]:
                            same.append(dd)
                            if li and lj:
                                same_long.append(dd)
                        elif seg_spk[i] is not None and seg_spk[j] is not None:
                            diff.append(dd)
                            if li and lj:
                                diff_long.append(dd)
                print(f"  same-voice seg distances : {dist_stats(same)}")
                print(f"  diff-voice seg distances : {dist_stats(diff)}")
                print(f"  same-voice (both >=1.5s) : {dist_stats(same_long)}")
                print(f"  diff-voice (both >=1.5s) : {dist_stats(diff_long)}")
            for thr in MERGE_THRESHOLDS:
                if thr is None:
                    merged = [dict(s) for s in reconciled]
                    n_unions, l_before, l_after = 0, n_pre, n_pre
                else:
                    merged, n_unions, l_before, l_after = merge_union_find(reconciled, embeddings, thr)
                ev = evalf(merged, truth)
                row = {
                    "wav": name, "num_clusters": nc,
                    "merge_threshold": thr, "unions": n_unions,
                    "labels_before": l_before, "labels_after": l_after, **ev,
                }
                results.append(row)
                extra = (
                    f"truth={ev.get('truth_seq','')} hyp={ev.get('hyp_seq_mapped','')} "
                    f"agree={ev['seg_agreement']:.2f}"
                    if "truth_seq" in ev
                    else f"seats_correct={ev['seats_correct']}/{ev['seats_total']} {ev['correct_seat_ids']}"
                )
                print(f"  merge_thr={thr if thr is not None else 'off':>4}  "
                      f"labels {l_before}->{l_after}  merges(unions)={n_unions}  {extra}")

    ok_a = [r for r in results if r["wav"] == "casual_2spk" and r["n_hyp_labels"] == 2
            and (r.get("seg_agreement") or 0) >= 0.9]
    print("\n=== PICK ===")
    if ok_a:
        for r in ok_a:
            b = next((x for x in results if x["wav"] == "stage6_mix"
                      and x["num_clusters"] == r["num_clusters"]
                      and x["merge_threshold"] == r["merge_threshold"]), None)
            print(f"casual OK: nc={r['num_clusters']} thr={r['merge_threshold']} "
                  f"(agree={r['seg_agreement']:.2f}); stage6 seats="
                  f"{b['seats_correct'] if b else '?'}")
    else:
        print("no config reached 2 casual labels with >=0.90 agreement on the host mirror")
        for r in results:
            key = "agree" if "seg_agreement" in r else "seats"
            val = (f"labels={r['n_hyp_labels']} agree={r.get('seg_agreement'):.2f}"
                   if key == "agree" else
                   f"labels={r['n_hyp_labels']} seats={r['seats_correct']}/{r['seats_total']}")
            print(f"  nc={r['num_clusters']} thr={str(r['merge_threshold']):>4} {r['wav']:<12} {val}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
