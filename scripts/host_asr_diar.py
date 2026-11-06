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


def transcribe_per_stream(
    dry_dir: Path,
    models: Path,
    *,
    n_talkers: int = 20,
    num_clusters: int = 1,
    num_threads: int = 2,
) -> tuple[dict, dict]:
    """Path B: one dry track per PresentConnect stream, then merge.

    Each file is a single known talker (oracle stream→speaker_id). Diarization
    with num_clusters=1 finds speech regions; ASR fills text. Timestamps stay
    on the shared mix timeline (stagger already baked into the dry wavs).
    """
    dry_dir = Path(dry_dir)
    t0 = time.perf_counter()
    merged: list[dict] = []
    per = []
    duration_ms = 0
    for i in range(1, n_talkers + 1):
        wav = dry_dir / f"spk_{i:02d}.wav"
        if not wav.is_file():
            raise HostBlocked(f"dry track missing: {wav}")
        doc, meta = transcribe_file(
            wav,
            models,
            num_clusters=num_clusters,
            num_threads=num_threads,
            speaker_id_override=f"speaker_{i}",
        )
        duration_ms = max(duration_ms, int(meta.get("duration_ms") or 0))
        per.append({"file": str(wav), "n_segments": meta["n_segments"], "wall_ms": meta["wall_ms"]})
        merged.extend(doc["transcript"])
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
        "stack": "linux-sherpa-onnx per-stream diar+ASR (oracle stream id), merge by time",
        "streams": per,
        "no_kvm_adb": True,
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
