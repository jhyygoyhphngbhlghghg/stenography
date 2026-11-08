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

Matrix v2 (2026-11-08 refinement, user device verdict "3-person conversation
produced 7 speakers"): casual-scene diarClusterThreshold in {0.5, 0.6, 0.65,
0.7} x SAME_VOICE_MAX_DISTANCE in {0.55, 0.60, 0.65}, run on:
  (a) casual 3-speaker wav — BUILT here by alternating-concatenating turns
      from the committed handoff slices speaker_1_3m / speaker_2_3p6m /
      speaker_3_4p2m; written to samples/demo-stress/casual_3spk_mic.wav
      (gitignored); truth = 3 speakers, known 3 s turn boundaries.
  (b) casual 2-speaker wav — same construction from the 3 m + 3.6 m slices;
      written to samples/demo-stress/casual_2spk_mic.wav (gitignored).
  (c) the frozen 6-speaker stage6 mix samples/demo-stress/stage6/DEMO_STAGE6.wav
      (gitignored; found in the worktree or the primary checkout, gold ref in
      samples/demo-stress/stage6/ref/DEMO_STAGE6.json) at its FROZEN scene
      (numClusters=0/auto, threshold 0.5) as a regression guard: merge OFF
      must stay 5/6 seats, and the chosen merge threshold must not glue
      distinct seats (min distinct-seat embedding distance is the margin).

The casual fixtures run through the CASUAL scene mirror (expectedSpeakers=-1:
no FarFieldGain staging, reconcile returns clustering as-is — exactly
FarFieldScene.casual), so the grid exercises the raw clustering threshold.

Also prints same-voice vs diff-voice per-segment embedding distance
distributions on both casual wavs (the evidence for the merge threshold).

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
STEM_C = "artifacts/demo-stage6-handoff/stem_or_slice_speaker_3_4p2m.wav"
CASUAL_OUT = "samples/demo-stress/casual_2spk_mic.wav"
CASUAL3_OUT = "samples/demo-stress/casual_3spk_mic.wav"
STAGE6_WAV = "samples/demo-stress/stage6/DEMO_STAGE6.wav"
STAGE6_REF = "samples/demo-stress/stage6/ref/DEMO_STAGE6.json"

# v2 matrix: casual diarClusterThreshold x SAME_VOICE_MAX_DISTANCE.
DIAR_CLUSTER_GRID = [0.5, 0.6, 0.65, 0.7]
MERGE_THRESHOLDS = [0.55, 0.60, 0.65]
STAGE6_MERGE_GRID = [None, *MERGE_THRESHOLDS]  # None = merge OFF (guard row)

# Host mirror of FarFieldScene.casual: same tuning as stage6 but
# expectedSpeakers=-1 => no FarFieldGain staging, reconcile returns as-is.
CASUAL: dict = {**SCENE, "name": "casual-mic", "expected_speakers": -1}


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


def build_casual_wav(out_path: Path, stems: list[tuple[Path, str]]) -> list[dict]:
    """Alternate-concatenate 3 s turns round-robin over the handoff slices.
    Returns truth turns. stems = [(wav_path, speaker_label), ...]."""
    loaded = []
    for stem, spk in stems:
        x, sr = HOST.load_wav_mono16k(stem)
        assert sr == 16000
        turns = slice_turns(x, sr)
        if not turns:
            raise SystemExit(f"BLOCKED: no turns found in slice {stem}")
        loaded.append((turns, x, spk))
    sr = 16000
    gap = np.zeros(int(0.40 * sr), dtype=np.float32)
    chunks: list[np.ndarray] = []
    truth: list[dict] = []
    pos = 0
    n = max(len(t) for t, _, _ in loaded)
    for i in range(n):
        for turns, x, spk in loaded:
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
def run_app_diar_path(samples: np.ndarray, sr: int, models: Path, num_clusters: int,
                      scene: dict | None = None, cluster_threshold: float | None = None):
    """Diar -> reconcile. Mirrors SherpaStenoPipeline.transcribeAsr +
    runDiarizerAndReconcile for the given scene. For expectedSpeakers<=0
    (FarFieldScene.casual) the app skips FarFieldGain staging, works on the
    raw samples, and the reconcile returns the clustering as-is, so the VAD
    union is computed but cannot alter the result."""
    import sherpa_onnx

    scene = scene or SCENE
    ct = float(scene["diar_cluster_threshold"] if cluster_threshold is None else cluster_threshold)
    casual = int(scene.get("expected_speakers") or 0) <= 0
    if casual:
        work = samples.astype(np.float32)
        vad = []
    else:
        staged = FF.stage(samples, per_turn=True)
        work = staged["samples"].astype(np.float32)
        energy = [
            {"start_sample": int(s["start"]), "end_sample": int(s["end"])} for s in staged["segments"]
        ]
        silero = HOST.collect_silero(
            work,
            sr,
            models,
            threshold=float(scene["vad_threshold"]),
            min_silence_sec=float(scene["vad_min_silence_sec"]),
            min_speech_sec=float(scene["vad_min_speech_sec"]),
            max_speech_sec=float(scene["vad_max_speech_sec"]),
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
            num_clusters=num_clusters, threshold=ct
        ),
        min_duration_on=float(scene["diar_min_duration_on_sec"]),
        min_duration_off=float(scene["diar_min_duration_off_sec"]),
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
    reconciled = RECON.run(vad, mapped, sr, scene, samples=work)
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


def turn_purity(segs: list[dict], truth: list[dict]):
    """Overlap-ms-weighted per-truth-turn label purity: for each truth turn,
    the fraction of covering hyp ms that belongs to the dominant hyp label."""
    tot_ms = 0
    dom_ms = 0
    for t in truth:
        votes: dict[int, int] = {}
        for s in segs:
            ov = overlap_ms(s["start_ms"], s["end_ms"], t["start_ms"], t["end_ms"])
            if ov:
                votes[s["speaker_index"]] = votes.get(s["speaker_index"], 0) + ov
        if votes:
            tot_ms += sum(votes.values())
            dom_ms += max(votes.values())
    return (dom_ms / tot_ms) if tot_ms else None


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
        "turn_purity": turn_purity(segs, truth),
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


def f2(v) -> str:
    return f"{v:.2f}" if v is not None else "n/a"


def dist_pairs(segs: list[dict], embeddings, seg_spk):
    """same-voice / diff-voice per-segment embedding distances (by truth)."""
    same, diff, same_long, diff_long = [], [], [], []
    for i in range(len(segs)):
        for j in range(i + 1, len(segs)):
            if embeddings[i] is None or embeddings[j] is None:
                continue
            dd = float(1.0 - np.dot(embeddings[i], embeddings[j]))
            li = segs[i]["end_ms"] - segs[i]["start_ms"] >= 1500
            lj = segs[j]["end_ms"] - segs[j]["start_ms"] >= 1500
            if seg_spk[i] is not None and seg_spk[i] == seg_spk[j]:
                same.append(dd)
                if li and lj:
                    same_long.append(dd)
            elif seg_spk[i] is not None and seg_spk[j] is not None:
                diff.append(dd)
                if li and lj:
                    diff_long.append(dd)
    return same, diff, same_long, diff_long


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
    stem_c = resolve(STEM_C, extra_roots)
    stage6_wav = args.stage6_wav or resolve(STAGE6_WAV, extra_roots)
    stage6_ref = resolve(STAGE6_REF, extra_roots)
    if not stem_a or not stem_b or not stem_c:
        print("BLOCKED: handoff slices missing", file=sys.stderr)
        return 2
    if not stage6_wav or not stage6_ref:
        print("BLOCKED: DEMO_STAGE6 wav/ref missing (gitignored; pass --stage6-wav)", file=sys.stderr)
        return 2

    casual3_path = ROOT / CASUAL3_OUT
    truth3 = build_casual_wav(casual3_path, [(stem_a, "A"), (stem_b, "B"), (stem_c, "C")])
    print(f"built casual 3spk wav: {casual3_path} "
          f"({sum(t['end_ms']-t['start_ms'] for t in truth3)/1000:.1f}s speech, "
          f"{len(truth3)} turns, truth seq {''.join(t['speaker'] for t in truth3)})")
    casual_path = ROOT / CASUAL_OUT
    truth2 = build_casual_wav(casual_path, [(stem_a, "A"), (stem_b, "B")])
    print(f"built casual 2spk wav: {casual_path} "
          f"({sum(t['end_ms']-t['start_ms'] for t in truth2)/1000:.1f}s speech, "
          f"{len(truth2)} turns, truth seq {''.join(t['speaker'] for t in truth2)})")
    samples3, sr = HOST.load_wav_mono16k(casual3_path)
    samples2, _ = HOST.load_wav_mono16k(casual_path)
    samples_s6, _ = HOST.load_wav_mono16k(stage6_wav)
    ref = json.loads(stage6_ref.read_text(encoding="utf-8"))
    truth_s6 = [
        {"speaker": u["speaker_id"], "start_ms": int(u["start_ms"]), "end_ms": int(u["end_ms"])}
        for u in ref["transcript"]
    ]

    results = []

    # ---- casual grid: CASUAL scene mirror (expectedSpeakers=-1, no staging,
    #      reconcile as-is), FastClustering threshold path (numClusters=0).
    for name, samples, truth, n_truth in (
        ("casual_3spk", samples3, truth3, 3),
        ("casual_2spk", samples2, truth2, 2),
    ):
        for ct in DIAR_CLUSTER_GRID:
            t0 = time.perf_counter()
            work, mapped, reconciled = run_app_diar_path(
                samples, sr, models, 0, scene=CASUAL, cluster_threshold=ct
            )
            embeddings, n_null = embed_segments(work, sr, reconciled, models)
            wall = time.perf_counter() - t0
            n_pre = len({s["speaker_index"] for s in reconciled})
            n_raw = len({m["speaker_index"] for m in mapped})
            print(f"\n=== {name} scene=casual nc=auto diarClusterThreshold={ct} ===  "
                  f"raw_labels={n_raw} raw_segs={len(mapped)} reconciled_labels={n_pre} "
                  f"null_embeddings={n_null}/{len(reconciled)} wall={wall:.1f}s")
            seg_spk = [dominant_truth(s, truth) for s in reconciled]
            same, diff, same_long, diff_long = dist_pairs(reconciled, embeddings, seg_spk)
            print(f"  same-voice seg distances : {dist_stats(same)}")
            print(f"  diff-voice seg distances : {dist_stats(diff)}")
            print(f"  same-voice (both >=1.5s) : {dist_stats(same_long)}")
            print(f"  diff-voice (both >=1.5s) : {dist_stats(diff_long)}")
            for thr in MERGE_THRESHOLDS:
                merged, n_unions, l_before, l_after = merge_union_find(reconciled, embeddings, thr)
                ev = eval_labels(merged, truth)
                row = {
                    "wav": name, "cluster_threshold": ct, "merge_threshold": thr,
                    "unions": n_unions, "labels_before": l_before, "labels_after": l_after,
                    "n_truth": n_truth, **ev,
                }
                results.append(row)
                print(f"  merge_thr={thr:.2f}  labels {l_before}->{l_after} (truth {n_truth})  "
                      f"unions={n_unions}  agree={f2(ev['seg_agreement'])} "
                      f"turn_purity={f2(ev['turn_purity'])}  "
                      f"truth={ev['truth_seq']} hyp={ev['hyp_seq_mapped']}")

    # ---- stage6 regression guard: FROZEN scene (nc=auto, threshold 0.5).
    t0 = time.perf_counter()
    work, mapped, reconciled = run_app_diar_path(
        samples_s6, sr, models, 0, scene=SCENE, cluster_threshold=float(SCENE["diar_cluster_threshold"])
    )
    embeddings, n_null = embed_segments(work, sr, reconciled, models)
    n_pre = len({s["speaker_index"] for s in reconciled})
    print(f"\n=== stage6_mix FROZEN scene (nc=auto ct={SCENE['diar_cluster_threshold']}) ===  "
          f"raw_labels={len({m['speaker_index'] for m in mapped})} reconciled_labels={n_pre} "
          f"null_embeddings={n_null}/{len(reconciled)} wall={time.perf_counter()-t0:.1f}s")
    seg_spk_s6 = [dominant_truth(s, truth_s6) for s in reconciled]
    same_s6, diff_s6, _, _ = dist_pairs(reconciled, embeddings, seg_spk_s6)
    print(f"  same-seat seg distances    : {dist_stats(same_s6)}")
    print(f"  DISTINCT-seat seg distances: {dist_stats(diff_s6)}")

    # Seat-level margin evidence: embed every REF TURN crop of the staged mix
    # (the 0.62 'min distinct-seat distance' claim in SpeakerIdentityMerge.kt)
    # and report same-seat vs distinct-seat turn-embedding distances.
    import sherpa_onnx
    d = HOST.find_diar(models)
    extractor = sherpa_onnx.SpeakerEmbeddingExtractor(
        sherpa_onnx.SpeakerEmbeddingExtractorConfig(
            model=str(d["embedding"]), num_threads=2, provider="cpu"
        )
    )
    turn_embs: list[tuple[str, np.ndarray]] = []
    for t in truth_s6:
        for w0, w1 in embedding_windows(int(t["start_ms"]), int(t["end_ms"])):
            a = max(0, min(int(w0 * sr // 1000), work.size))
            b = max(a, min(int(w1 * sr // 1000), work.size))
            if b - a < sr // 10:
                continue
            e = embed_audio(extractor, work[a:b], sr)
            if e is not None and float(np.linalg.norm(e)) > 0:
                turn_embs.append((t["speaker"], (e / np.linalg.norm(e)).astype(np.float32)))
                break
    same_t, diff_t = [], []
    diff_pairs_by_seat: dict[tuple[str, str], float] = {}
    for i in range(len(turn_embs)):
        for j in range(i + 1, len(turn_embs)):
            (si, ei), (sj, ej) = turn_embs[i], turn_embs[j]
            dd = float(1.0 - np.dot(ei, ej))
            if si == sj:
                same_t.append(dd)
            else:
                diff_t.append(dd)
                key = tuple(sorted((si, sj)))
                diff_pairs_by_seat[key] = min(diff_pairs_by_seat.get(key, dd), dd)
    print(f"  same-seat TURN distances    : {dist_stats(same_t)}")
    print(f"  DISTINCT-seat TURN distances: {dist_stats(diff_t)}")
    if diff_pairs_by_seat:
        worst = sorted(diff_pairs_by_seat.items(), key=lambda kv: kv[1])[:3]
        print("  closest distinct-seat turn pairs: "
              + ", ".join(f"{a}|{b}={v:.3f}" for (a, b), v in worst))
    min_diff_s6 = min(
        [v for v in ([min(diff_s6)] if diff_s6 else []) + ([min(diff_t)] if diff_t else [])]
    ) if (diff_s6 or diff_t) else None

    for thr in STAGE6_MERGE_GRID:
        if thr is None:
            merged = [dict(s) for s in reconciled]
            n_unions, l_before, l_after = 0, n_pre, n_pre
        else:
            merged, n_unions, l_before, l_after = merge_union_find(reconciled, embeddings, thr)
        ev = eval_seats(merged, truth_s6)
        row = {
            "wav": "stage6_mix", "cluster_threshold": float(SCENE["diar_cluster_threshold"]),
            "merge_threshold": thr, "unions": n_unions,
            "labels_before": l_before, "labels_after": l_after,
            "min_diff_seat_dist": min_diff_s6, **ev,
        }
        results.append(row)
        print(f"  merge_thr={thr if thr is not None else 'off':>4}  "
              f"labels {l_before}->{l_after}  unions={n_unions}  "
              f"seats={ev['seats_correct']}/{ev['seats_total']} {ev['correct_seat_ids']}")

    # ---- PICK ----------------------------------------------------------
    def get(wav, ct, thr):
        return next((r for r in results if r["wav"] == wav
                     and r["cluster_threshold"] == ct and r["merge_threshold"] == thr), None)

    base2 = get("casual_2spk", 0.5, 0.55)          # current shipped config
    guard = get("stage6_mix", 0.5, None)            # merge OFF guard row
    base_agree = (base2 or {}).get("seg_agreement") or 0.0
    guard_seats = (guard or {}).get("seats_correct") or 0

    candidates = []
    for ct in DIAR_CLUSTER_GRID:
        for thr in MERGE_THRESHOLDS:
            r3 = get("casual_3spk", ct, thr)
            r2 = get("casual_2spk", ct, thr)
            rs6 = get("stage6_mix", 0.5, thr)
            if not r3 or not r2 or not rs6:
                continue
            if r3["n_hyp_labels"] > 4:                      # never worse than 4
                continue
            if not guard or guard["seats_correct"] < 5:     # merge-OFF guard must hold 5/6
                continue
            if min_diff_s6 is not None and thr >= min_diff_s6:
                # margin rule: identity threshold must stay below the min
                # distinct-seat embedding distance (<=0.60 unless this sweep
                # measured a wider margin that proves 0.65 safe)
                continue
            if rs6["unions"] > 0 and rs6["seats_correct"] < guard_seats:
                continue                                    # distance gluing hit distinct seats
            if (r2.get("seg_agreement") or 0.0) < base_agree - 1e-9:
                continue                                    # 2spk fixture must not degrade
            candidates.append((r3, r2, rs6, ct, thr))

    print("\n=== SWEEP TABLE (condensed) ===")
    print("| fixture | cluster_thr | merge_thr | labels(truth) | unions | agree | turn_purity/seats |")
    print("|---|---|---|---|---|---|---|")
    for r in results:
        if r["wav"] == "stage6_mix":
            metric = f"seats {r['seats_correct']}/{r['seats_total']}"
            agree = "-"
        else:
            metric = f"purity {f2(r['turn_purity'])}"
            agree = f2(r['seg_agreement'])
        thr = r["merge_threshold"] if r["merge_threshold"] is not None else "off"
        print(f"| {r['wav']} | {r['cluster_threshold']} | {thr} | "
              f"{r['labels_after']}({r.get('n_truth', 6)}) | {r['unions']} | {agree} | {metric} |")

    print("\n=== PICK ===")
    if base2:
        print(f"baseline casual_2spk ct=0.5 thr=0.55: labels={base2['n_hyp_labels']} "
              f"agree={f2(base2['seg_agreement'])} purity={f2(base2['turn_purity'])}")
    if guard:
        md = f"{min_diff_s6:.3f}" if min_diff_s6 is not None else "n/a"
        print(f"stage6 guard (merge OFF): seats={guard_seats}/6; "
              f"min DISTINCT-seat distance (seg+turn)={md}")
    if candidates:
        # rank: 3spk labels==3 first, then purity, then smaller merge thr (margin),
        # then smaller cluster thr (least drift from shipped 0.5).
        candidates.sort(key=lambda c: (
            abs(c[0]["n_hyp_labels"] - 3),
            -(c[0].get("turn_purity") or 0.0),
            abs(c[1]["n_hyp_labels"] - 2),   # fewer 2spk fragments wins ties
            c[4], c[3],                      # smaller merge thr (margin), then ct (drift)
        ))
        r3, r2, rs6, ct, thr = candidates[0]
        print(f"PICKED: diarClusterThreshold(casual)={ct} SAME_VOICE_MAX_DISTANCE={thr}")
        print(f"  casual_3spk: labels {r3['labels_before']}->{r3['labels_after']} (truth 3) "
              f"unions={r3['unions']} agree={f2(r3['seg_agreement'])} purity={f2(r3['turn_purity'])}")
        print(f"  casual_2spk: labels {r2['labels_before']}->{r2['labels_after']} (truth 2) "
              f"unions={r2['unions']} agree={f2(r2['seg_agreement'])} purity={f2(r2['turn_purity'])}")
        print(f"  stage6     : seats={rs6['seats_correct']}/{rs6['seats_total']} "
              f"unions={rs6['unions']} labels {rs6['labels_before']}->{rs6['labels_after']}")
        if min_diff_s6 is not None:
            print(f"  margin: merge thr {thr} < min distinct-seat dist {min_diff_s6:.3f} "
                  f"(margin {min_diff_s6 - thr:+.3f})")
        for r3b, r2b, rs6b, ctb, thrb in candidates[1:]:
            print(f"  alt: ct={ctb} thr={thrb} 3spk_labels={r3b['n_hyp_labels']} "
                  f"purity={f2(r3b['turn_purity'])} stage6_seats={rs6b['seats_correct']}")
    else:
        print("NO CONFIG satisfied: 3spk<=4 labels, 2spk agree >= baseline, "
              "stage6 seats >= guard, merge thr < min distinct-seat distance")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
