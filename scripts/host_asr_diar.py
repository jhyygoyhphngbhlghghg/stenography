#!/usr/bin/env python3
"""Host-Linux Zipformer-ru + OfflineSpeakerDiarization (no Android / no ADB).

Cloud agents have no KVM/ADB. This is the eval path that can actually run
on a Linux VM. Models come from scripts/download-models.sh (same names as
docs/MODELS.md).

Output JSON is exactly {transcript:[{speaker_id,start_ms,end_ms,text}]}.

Does not invent metrics. On missing models / import failure / OOM it raises
HostBlocked with the real reason.

Usage:
  python3 scripts/host_asr_diar.py --wav samples/demo-stress/mix_20talker.wav \\
      --out artifacts/eval/demo-stress/hyp.json
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import stream_pcm_gate as PCM_GATE  # noqa: E402


class HostBlocked(RuntimeError):
    """Inference did not run. Callers must record pending/blocked, not fake WER."""


def find_zipformer(models: Path) -> dict[str, Path]:
    d = models / "asr" / "zipformer-ru-int8"
    if not d.is_dir():
        raise HostBlocked(f"missing Zipformer-ru dir {d} (run scripts/download-models.sh)")
    names = list(d.iterdir())

    def pick(kind: str) -> Path:
        if kind == "tokens":
            hits = [p for p in names if p.name.lower() == "tokens.txt"]
        else:
            hits = [p for p in names if kind in p.name.lower() and p.suffix == ".onnx"]
        if not hits:
            raise HostBlocked(f"Zipformer-ru missing {kind} under {d}")
        return hits[0]

    return {
        "encoder": pick("encoder"),
        "decoder": pick("decoder"),
        "joiner": pick("joiner"),
        "tokens": pick("tokens"),
    }


def find_diar(models: Path) -> dict[str, Path]:
    d = models / "diar"
    seg = None
    for cand in (d / "segmentation.onnx", d / "model.onnx"):
        if cand.is_file():
            seg = cand
            break
    if seg is None and d.is_dir():
        hits = [p for p in d.iterdir() if p.suffix == ".onnx" and "embed" not in p.name.lower()]
        seg = hits[0] if hits else None
    emb = None
    for cand in (
        d / "embedding.onnx",
        d / "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
    ):
        if cand.is_file():
            emb = cand
            break
    if emb is None and d.is_dir():
        hits = [p for p in d.iterdir() if p.suffix == ".onnx" and "3dspeaker" in p.name.lower()]
        emb = hits[0] if hits else None
    if seg is None or emb is None:
        raise HostBlocked(
            f"missing diarization ONNX under {d} (need pyannote segmentation + 3D-Speaker embedding; "
            "run scripts/download-models.sh)"
        )
    return {"segmentation": seg, "embedding": emb}


def load_wav_mono16k(path: Path):
    import wave

    import numpy as np

    with wave.open(str(path), "rb") as w:
        sr = w.getframerate()
        nch = w.getnchannels()
        sw = w.getsampwidth()
        raw = w.readframes(w.getnframes())
    if sw != 2:
        raise HostBlocked(f"{path} is not PCM16")
    x = np.frombuffer(raw, dtype="<i2").astype("float32") / 32768.0
    if nch > 1:
        x = x.reshape(-1, nch).mean(axis=1)
    if sr != 16000:
        n_out = max(1, int(round(x.size * 16000 / sr)))
        t_in = np.linspace(0.0, 1.0, x.size, endpoint=False)
        t_out = np.linspace(0.0, 1.0, n_out, endpoint=False)
        x = np.interp(t_out, t_in, x).astype("float32")
        sr = 16000
    return x, sr


def transcribe_asr_only(
    wav: Path,
    models: Path,
    *,
    speaker_id: str = "speaker_1",
    num_threads: int = 2,
) -> tuple[dict, dict]:
    """Single-channel ASR (PresentConnect oracle / dry main). No diarization."""
    try:
        import sherpa_onnx
    except ImportError as e:
        raise HostBlocked(
            "python package sherpa-onnx is not installed "
            "(pip install sherpa-onnx; Linux host, not Android AAR)"
        ) from e
    wav = Path(wav)
    if not wav.is_file():
        raise HostBlocked(f"wav missing: {wav}")
    z = find_zipformer(Path(models))
    samples, sr = load_wav_mono16k(wav)
    t0 = time.perf_counter()
    try:
        recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=str(z["encoder"]),
            decoder=str(z["decoder"]),
            joiner=str(z["joiner"]),
            tokens=str(z["tokens"]),
            num_threads=num_threads,
            sample_rate=16000,
            feature_dim=80,
            decoding_method="greedy_search",
            provider="cpu",
            model_type="transducer",
        )
    except Exception as e:
        raise HostBlocked(f"Zipformer OfflineRecognizer init failed: {type(e).__name__}: {e}") from e
    text = ""
    if samples.size:
        stream = recognizer.create_stream()
        stream.accept_waveform(sr, samples)
        recognizer.decode_stream(stream)
        text = (stream.result.text or "").strip()
    wall_ms = int(round((time.perf_counter() - t0) * 1000.0))
    duration_ms = int(round(samples.size * 1000.0 / sr))
    doc = {
        "transcript": [
            {
                "speaker_id": speaker_id,
                "start_ms": 0,
                "end_ms": duration_ms,
                "text": text,
            }
        ]
    }
    meta = {
        "wav": str(wav),
        "wall_ms": wall_ms,
        "duration_ms": duration_ms,
        "num_clusters": 1,
        "n_segments": 1,
        "n_hyp_speakers": 1,
        "asr_only": True,
        "stack": "linux-sherpa-onnx-python Zipformer-ru ASR-only (no diarization)",
        "sherpa_onnx": getattr(sherpa_onnx, "__version__", "unknown"),
        "no_kvm_adb": True,
    }
    if duration_ms > 0:
        meta["rtf"] = wall_ms / duration_ms
    return doc, meta


def transcribe_file(
    wav: Path,
    models: Path,
    *,
    num_clusters: int = 20,
    num_threads: int = 2,
    cluster_threshold: float = 0.5,
    min_duration_on: float = 0.2,
    min_duration_off: float = 0.5,
    asr_only: bool = False,
    skip_asr: bool = False,
    speaker_id_override: str | None = None,
) -> tuple[dict, dict]:
    if asr_only:
        return transcribe_asr_only(wav, models, num_threads=num_threads)
    try:
        import sherpa_onnx
    except ImportError as e:
        raise HostBlocked(
            "python package sherpa-onnx is not installed "
            "(pip install sherpa-onnx; Linux host, not Android AAR)"
        ) from e

    wav = Path(wav)
    models = Path(models)
    if not wav.is_file():
        raise HostBlocked(f"wav missing: {wav}")

    z = find_zipformer(models)
    d = find_diar(models)
    samples, sr = load_wav_mono16k(wav)

    t0 = time.perf_counter()
    diar_cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                model=str(d["segmentation"]),
            ),
        ),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(
            model=str(d["embedding"]),
            num_threads=num_threads,
            provider="cpu",
        ),
        clustering=sherpa_onnx.FastClusteringConfig(
            num_clusters=num_clusters,
            threshold=cluster_threshold,
        ),
        min_duration_on=min_duration_on,
        min_duration_off=min_duration_off,
    )
    if hasattr(diar_cfg, "validate") and not diar_cfg.validate():
        raise HostBlocked("OfflineSpeakerDiarizationConfig.validate() failed")

    try:
        sd = sherpa_onnx.OfflineSpeakerDiarization(diar_cfg)
    except Exception as e:
        raise HostBlocked(f"OfflineSpeakerDiarization init failed: {type(e).__name__}: {e}") from e

    try:
        raw = sd.process(samples)
        if hasattr(raw, "sort_by_start_time"):
            segs = list(raw.sort_by_start_time())
        else:
            segs = sorted(list(raw), key=lambda r: float(r.start))
    except MemoryError as e:
        raise HostBlocked(f"OOM during diarization: {e}") from e
    except Exception as e:
        raise HostBlocked(f"diarization process() failed: {type(e).__name__}: {e}") from e

    recognizer = None
    if not skip_asr:
        try:
            recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
                encoder=str(z["encoder"]),
                decoder=str(z["decoder"]),
                joiner=str(z["joiner"]),
                tokens=str(z["tokens"]),
                num_threads=num_threads,
                sample_rate=16000,
                feature_dim=80,
                decoding_method="greedy_search",
                provider="cpu",
                model_type="transducer",
            )
        except Exception as e:
            raise HostBlocked(f"Zipformer OfflineRecognizer init failed: {type(e).__name__}: {e}") from e

    rows = []
    for seg in segs:
        start = float(seg.start)
        end = float(seg.end)
        spk = int(seg.speaker)
        a = max(0, int(start * sr))
        b = min(samples.size, int(end * sr))
        crop = samples[a:b]
        text = ""
        if recognizer is not None and crop.size:
            try:
                stream = recognizer.create_stream()
                stream.accept_waveform(sr, crop)
                recognizer.decode_stream(stream)
                text = (stream.result.text or "").strip()
            except Exception as e:
                text = ""
                print(f"ASR segment speaker_{spk + 1} {start:.2f}-{end:.2f}s failed: {e}", file=sys.stderr)
        sid = speaker_id_override or f"speaker_{spk + 1}"
        rows.append(
            {
                "speaker_id": sid,
                "start_ms": int(round(start * 1000.0)),
                "end_ms": int(round(end * 1000.0)),
                "text": text,
            }
        )
    rows.sort(key=lambda u: (u["start_ms"], u["speaker_id"]))
    wall_ms = int(round((time.perf_counter() - t0) * 1000.0))
    doc = {"transcript": rows}
    meta = {
        "wav": str(wav),
        "wall_ms": wall_ms,
        "duration_ms": int(round(samples.size * 1000.0 / sr)),
        "num_clusters": num_clusters,
        "cluster_threshold": cluster_threshold,
        "min_duration_on": min_duration_on,
        "min_duration_off": min_duration_off,
        "skip_asr": skip_asr,
        "n_segments": len(rows),
        "n_hyp_speakers": len({r["speaker_id"] for r in rows}),
        "stack": "linux-sherpa-onnx-python Zipformer-ru + OfflineSpeakerDiarization",
        "sherpa_onnx": getattr(sherpa_onnx, "__version__", "unknown"),
        "models": {
            "encoder": str(z["encoder"]),
            "decoder": str(z["decoder"]),
            "joiner": str(z["joiner"]),
            "tokens": str(z["tokens"]),
            "segmentation": str(d["segmentation"]),
            "embedding": str(d["embedding"]),
        },
        "no_kvm_adb": True,
    }
    if meta["duration_ms"] > 0:
        meta["rtf"] = wall_ms / meta["duration_ms"]
    return doc, meta


def rms_speech_regions(
    samples,
    sr: int,
    *,
    frame_ms: int = 20,
    rel_thresh: float = 0.04,
    pad_ms: int = 80,
    min_ms: int = 150,
    merge_gap_ms: int = 250,
) -> list[tuple[float, float]]:
    """Energy VAD for a single-speaker dry track. Returns (start_s, end_s)."""
    import numpy as np

    x = np.asarray(samples, dtype=np.float32)
    if x.size == 0:
        return []
    frame = max(1, int(sr * frame_ms / 1000))
    n = (x.size + frame - 1) // frame
    pad = np.zeros(n * frame, dtype=np.float32)
    pad[: x.size] = x
    frames = pad.reshape(n, frame)
    e = np.sqrt(np.mean(np.square(frames), axis=1))
    peak = float(np.max(e)) or 1.0
    speech = e > (rel_thresh * peak)
    regions: list[list[int]] = []
    i = 0
    while i < n:
        if not speech[i]:
            i += 1
            continue
        j = i
        while j < n and speech[j]:
            j += 1
        regions.append([i, j])
        i = j
    merge_f = max(1, merge_gap_ms // frame_ms)
    merged: list[list[int]] = []
    for a, b in regions:
        if merged and a - merged[-1][1] <= merge_f:
            merged[-1][1] = b
        else:
            merged.append([a, b])
    pad_f = max(0, pad_ms // frame_ms)
    min_f = max(1, min_ms // frame_ms)
    padded: list[list[int]] = []
    for a, b in merged:
        a = max(0, a - pad_f)
        b = min(n, b + pad_f)
        if padded and a <= padded[-1][1]:
            padded[-1][1] = max(padded[-1][1], b)
        else:
            padded.append([a, b])
    out = []
    for a, b in padded:
        if b - a < min_f:
            continue
        out.append((a * frame / sr, min(x.size, b * frame) / sr))
    return out


def transcribe_per_stream(
    dry_dir: Path,
    models: Path,
    *,
    n_talkers: int = 20,
    num_clusters: int = 1,
    num_threads: int = 2,
    vad: str = "rms",
    pad_ms: int = 80,
    min_duration_on: float = 0.15,
    min_duration_off: float = 0.25,
    rel_thresh: float = 0.04,
    pcm_gate: bool = False,
) -> tuple[dict, dict]:
    """Path B: one dry track per PresentConnect stream, then merge.

    Oracle stream→speaker_id (s0N ↔ speaker_N). Default RMS VAD + edge pad,
    then Zipformer per region. OfflineSpeakerDiarization is the fallback if
    VAD finds nothing. Timestamps stay on the shared mix timeline.

    pcm_gate: StreamPcmGate (PR #6 dae416f / Platform f5ff5e2) on each
    stream's PCM before VAD/ASR. Hard reject LOW_ENERGY/CLIPPED/BAD_MIC:
    no ASR, no brief row. Soft flags still ASR. Fixtures are not rewritten.
    """
    dry_dir = Path(dry_dir)
    t0 = time.perf_counter()
    merged: list[dict] = []
    per = []
    duration_ms = 0
    recognizer = None

    def get_recognizer():
        nonlocal recognizer
        if recognizer is None:
            z = find_zipformer(Path(models))
            try:
                import sherpa_onnx
            except ImportError as e:
                raise HostBlocked("python package sherpa-onnx is not installed") from e
            recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
                encoder=str(z["encoder"]),
                decoder=str(z["decoder"]),
                joiner=str(z["joiner"]),
                tokens=str(z["tokens"]),
                num_threads=num_threads,
                sample_rate=16000,
                feature_dim=80,
                decoding_method="greedy_search",
                provider="cpu",
                model_type="transducer",
            )
        return recognizer

    for i in range(1, n_talkers + 1):
        wav = dry_dir / f"spk_{i:02d}.wav"
        if not wav.is_file():
            raise HostBlocked(f"dry track missing: {wav}")
        samples, sr = load_wav_mono16k(wav)
        duration_ms = max(duration_ms, int(round(samples.size * 1000.0 / sr)))
        sid = f"speaker_{i}"
        t1 = time.perf_counter()
        gate_stats = None
        if pcm_gate:
            gate_stats = PCM_GATE.inspect_float(samples, sample_rate_hz=sr)
            if not gate_stats["asr_allowed"]:
                wall_i = int(round((time.perf_counter() - t1) * 1000.0))
                per.append(
                    {
                        "file": str(wav),
                        "n_segments": 0,
                        "wall_ms": wall_i,
                        "vad": "pcm_gate_reject",
                        "stream_id": f"s{i:02d}",
                        "speaker_id": sid,
                        "pcm_gate": {
                            "verdict": gate_stats["verdict"],
                            "code": gate_stats["code"],
                            "message": gate_stats["message"],
                            "rms_fs": gate_stats["rms_fs"],
                            "peak_fs": gate_stats["peak_fs"],
                            "clip_ratio": gate_stats["clip_ratio"],
                            "dc_fs": gate_stats["dc_fs"],
                        },
                    }
                )
                continue
        regions: list[tuple[float, float]] = []
        method = vad
        if vad == "rms":
            regions = rms_speech_regions(
                samples,
                sr,
                rel_thresh=rel_thresh,
                pad_ms=pad_ms,
                min_ms=int(min_duration_on * 1000),
                merge_gap_ms=int(min_duration_off * 1000),
            )
        if not regions:
            method = "diar_fallback"
            doc, meta = transcribe_file(
                wav,
                models,
                num_clusters=num_clusters,
                num_threads=num_threads,
                min_duration_on=min_duration_on,
                min_duration_off=min_duration_off,
                speaker_id_override=sid,
            )
            for u in doc["transcript"]:
                if pad_ms:
                    u["start_ms"] = max(0, int(u["start_ms"]) - pad_ms)
                    u["end_ms"] = int(u["end_ms"]) + pad_ms
            row_meta = {
                "file": str(wav),
                "n_segments": len(doc["transcript"]),
                "wall_ms": meta["wall_ms"],
                "vad": method,
            }
            if gate_stats is not None:
                row_meta["pcm_gate"] = {
                    "verdict": gate_stats["verdict"],
                    "soft_flags": gate_stats["soft_flags"],
                    "code": gate_stats["code"],
                }
            per.append(row_meta)
            merged.extend(doc["transcript"])
            continue
        rec = get_recognizer()
        rows = []
        for start, end in regions:
            a = max(0, int(start * sr))
            b = min(samples.size, int(end * sr))
            crop = samples[a:b]
            text = ""
            if crop.size:
                stream = rec.create_stream()
                stream.accept_waveform(sr, crop)
                rec.decode_stream(stream)
                text = (stream.result.text or "").strip()
            rows.append(
                {
                    "speaker_id": sid,
                    "start_ms": int(round(start * 1000.0)),
                    "end_ms": int(round(end * 1000.0)),
                    "text": text,
                }
            )
        wall_i = int(round((time.perf_counter() - t1) * 1000.0))
        row_meta = {"file": str(wav), "n_segments": len(rows), "wall_ms": wall_i, "vad": method}
        if gate_stats is not None:
            row_meta["pcm_gate"] = {
                "verdict": gate_stats["verdict"],
                "soft_flags": gate_stats["soft_flags"],
                "code": gate_stats["code"],
            }
        per.append(row_meta)
        merged.extend(rows)
    merged.sort(key=lambda u: (u["start_ms"], u["speaker_id"]))
    wall_ms = int(round((time.perf_counter() - t0) * 1000.0))
    out_doc = {"transcript": merged}
    out_meta = {
        "dry_dir": str(dry_dir),
        "wall_ms": wall_ms,
        "duration_ms": duration_ms,
        "n_segments": len(merged),
        "n_hyp_speakers": len({u["speaker_id"] for u in merged}),
        "n_streams": n_talkers,
        "path": "B_per_stream",
        "vad": vad,
        "pad_ms": pad_ms,
        "min_duration_on": min_duration_on,
        "min_duration_off": min_duration_off,
        "rel_thresh": rel_thresh,
        "pcm_gate": pcm_gate,
        "pcm_gate_source": "StreamPcmGate.kt @ dae416f (Platform f5ff5e2 thresholds)",
        "streams_passed": sum(1 for s in per if s.get("vad") != "pcm_gate_reject"),
        "streams_dropped": sum(1 for s in per if s.get("vad") == "pcm_gate_reject"),
        "pcm_gate_codes": {
            code: sum(
                1
                for x in per
                if x.get("vad") == "pcm_gate_reject" and x.get("pcm_gate", {}).get("code") == code
            )
            for code in ("LOW_ENERGY", "CLIPPED", "BAD_MIC")
            if any(
                x.get("vad") == "pcm_gate_reject" and x.get("pcm_gate", {}).get("code") == code
                for x in per
            )
        },
        "stack": (
            "linux-sherpa-onnx per-stream StreamPcmGate+RMS-VAD+ASR (oracle stream id), merge by time"
            if pcm_gate
            else "linux-sherpa-onnx per-stream RMS-VAD+ASR (oracle stream id), merge by time"
        ),
        "streams": per,
        "no_kvm_adb": True,
        "json_schema": "transcript[].{speaker_id,start_ms,end_ms,text}",
    }
    if duration_ms > 0:
        out_meta["rtf"] = wall_ms / duration_ms
    return out_doc, out_meta


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--wav", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--models", type=Path, default=ROOT / "models")
    p.add_argument("--num-clusters", type=int, default=20)
    p.add_argument("--wall-out", type=Path, default=None, help="write integer milliseconds")
    args = p.parse_args()
    try:
        doc, meta = transcribe_file(args.wav, args.models, num_clusters=args.num_clusters)
    except HostBlocked as e:
        print(f"BLOCKED: {e}", file=sys.stderr)
        return 2
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    wall_path = args.wall_out or args.out.with_suffix(".wall_ms")
    wall_path.write_text(str(meta["wall_ms"]) + "\n", encoding="utf-8")
    meta_path = args.out.with_suffix(".meta.json")
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"ok": True, "out": str(args.out), **{k: meta[k] for k in ("wall_ms", "duration_ms", "n_segments")}}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
