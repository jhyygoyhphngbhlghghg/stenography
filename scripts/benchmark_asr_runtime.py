#!/usr/bin/env python3
"""Host-only Zipformer lifecycle/decoder ablation on committed STAGE6 excerpts.

These are oracle time crops, NOT the complete frozen mix or on-device results.
No diarization or LLM is run. Hex punctuation remains in the scoring denominator.
The audio, references, gain and EQ are not rewritten. Cold means new ONNX
sessions (the OS file cache may be warm), not a cold boot or first APK launch.

  .venv/Scripts/python.exe scripts/benchmark_asr_runtime.py --repeats 5 \
      --out artifacts/optimization/asr-runtime.json
"""
from __future__ import annotations

import argparse
import gc
import hashlib
import json
import platform
import statistics
import time
from pathlib import Path

import eval_metrics as metrics
import host_asr_diar as host

ROOT = Path(__file__).resolve().parents[1]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def decode(recognizer, samples, sr: int) -> dict:
    stream = recognizer.create_stream()
    stream.accept_waveform(sr, samples)
    recognizer.decode_stream(stream)
    result = stream.result
    return {
        "text": result.text.strip(),
        "tokens": list(result.tokens),
        "timestamps": list(result.timestamps),
    }


def benchmark(models: Path, repeats: int) -> dict:
    import sherpa_onnx

    z = host.find_zipformer(models)
    paths = sorted((ROOT / "artifacts/demo-stage6-handoff").glob("stem_or_slice_*.wav"))
    ref_path = ROOT / "samples/demo-stress/stage6/ref/DEMO_STAGE6.json"
    refs = json.loads(ref_path.read_text(encoding="utf-8"))["transcript"]
    if len(paths) != len(refs) or len(paths) != 6:
        raise ValueError("Need the six committed mix excerpts and their unchanged references")
    clips = [host.load_wav_mono16k(p) for p in paths]

    def create(method="greedy_search", threads=2):
        return sherpa_onnx.OfflineRecognizer.from_transducer(
            **{k: str(v) for k, v in z.items()}, num_threads=threads,
            sample_rate=16000, feature_dim=80, model_type="transducer",
            decoding_method=method, max_active_paths=4, provider="cpu",
        )

    variants = []
    # Alternate new-session and retained-session runs to reduce order bias.
    init_ms, cold_ms, warm_ms = [], [], []
    rec = create()
    expected = [decode(rec, x, sr) for x, sr in clips]
    del rec
    gc.collect()
    parity = []
    for i in range(repeats):
        for lifecycle in (("cold", "warm") if i % 2 == 0 else ("warm", "cold")):
            # Only ONE recognizer is alive in either condition. Warm up the
            # retained model outside the timer, then decode new streams again.
            if lifecycle == "warm":
                rec = create()
                parity.append([decode(rec, x, sr) for x, sr in clips] == expected)
            t0 = time.perf_counter()
            if lifecycle == "cold":
                rec = create()
            t1 = time.perf_counter()
            actual = [decode(rec, x, sr) for x, sr in clips]
            elapsed = (time.perf_counter() - t0) * 1000
            parity.append(actual == expected)
            if lifecycle == "cold":
                init_ms.append((t1 - t0) * 1000)
                cold_ms.append(elapsed)
            else:
                warm_ms.append(elapsed)
            del rec
            gc.collect()
    if not all(parity):
        raise AssertionError("Session reuse changed text, tokens, or timestamps")

    # Decoder ablation, not a production policy or a confidence calibration.
    for method in ("greedy_search", "modified_beam_search"):
        rec = create(method)
        runs, timings = [], []
        for _ in range(repeats):
            t0 = time.perf_counter()
            runs.append([decode(rec, x, sr) for x, sr in clips])
            timings.append((time.perf_counter() - t0) * 1000)
        rows = []
        for p, ref, hyp in zip(paths, refs, runs[0]):
            rt = metrics.tokenize(ref["text"])
            edits = metrics.levenshtein(rt, metrics.tokenize(hyp["text"]))
            rows.append({"file": p.relative_to(ROOT).as_posix(), "speaker_id": ref["speaker_id"],
                         "ref_tokens": len(rt), "edits": edits, "wer": edits / len(rt), **hyp})
        variants.append({"method": method, "max_active_paths": 4,
                         "decode_ms": timings, "median_decode_ms": statistics.median(timings),
                         "repeat_output_identical": all(r == runs[0] for r in runs),
                         "edits": sum(r["edits"] for r in rows),
                         "ref_tokens": sum(r["ref_tokens"] for r in rows), "clips": rows})
        del rec
        gc.collect()
    return {
        "test_id": "STAGE6_EXCERPTS_ASR_RUNTIME", "scope": "host-only oracle excerpt ASR",
        "not_full_gate": True, "not_android": True, "no_diarization": True, "no_llm": True,
        "preprocessing": "unchanged PCM16 excerpt, no additional gain/EQ",
        "cold_definition": "new recognizer sessions; OS file cache not flushed",
        "python": platform.python_version(), "platform": platform.platform(),
        "processor": platform.processor(), "sherpa_onnx": sherpa_onnx.__version__,
        "num_threads": 2, "repeats": repeats,
        "script_sha256": sha256(Path(__file__)),
        "one_recognizer_at_a_time": True,
        "audio_seconds_sum": sum(len(x) / sr for x, sr in clips),
        "reference_sha256": sha256(ref_path),
        "audio_sha256": {p.relative_to(ROOT).as_posix(): sha256(p) for p in paths},
        "models_sha256": {k: sha256(v) for k, v in z.items()},
        "lifecycle": {"init_ms": init_ms, "cold_total_ms": cold_ms, "warm_total_ms": warm_ms,
                      "median_cold_ms": statistics.median(cold_ms),
                      "median_warm_ms": statistics.median(warm_ms),
                      "speedup": statistics.median(cold_ms) / statistics.median(warm_ms),
                      "text_tokens_timestamps_identical": all(parity)},
        "decoders": variants,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", type=Path, default=ROOT / "models")
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.repeats < 2:
        parser.error("--repeats must be >= 2")
    report = benchmark(args.models, args.repeats)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"out": str(args.out), "lifecycle": report["lifecycle"],
                      "decoders": [{k: v[k] for k in ("method", "edits", "ref_tokens", "median_decode_ms")}
                                   for v in report["decoders"]]}, indent=2))


if __name__ == "__main__":
    main()
