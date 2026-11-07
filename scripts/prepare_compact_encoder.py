#!/usr/bin/env python3
"""Prepare a CPU-portable Zipformer graph and require native output parity.

No weight requantization, gain/EQ/VAD tuning or fixture changes. ORT extended
optimization folds constants and fuses the same CPU operators normally built
on every model load. Hardware-layout optimization (ORT_ENABLE_ALL/NCHWc) is
forbidden. Original weights remain untouched; the compact build opts in to
the separately validated encoder. Validation is host-only, not Android WER.

Dependencies: onnxruntime==1.28.0, onnx, sherpa-onnx==2.0.1, numpy.
"""
from __future__ import annotations

import argparse
import gc
import json
import platform
import statistics
import time
from pathlib import Path

import onnx
import onnxruntime as ort
import sherpa_onnx

from benchmark_asr_runtime import decode, sha256
from host_asr_diar import find_zipformer, load_wav_mono16k

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", type=Path, default=ROOT / "models")
    parser.add_argument("--out", type=Path, default=ROOT / "models/compact/asr/zipformer-ru-int8/encoder.int8.onnx")
    parser.add_argument("--report", type=Path, default=ROOT / "artifacts/compact/encoder-validation.json")
    parser.add_argument("--repeats", type=int, default=5)
    args = parser.parse_args()
    if args.repeats < 2:
        parser.error("At least two repeats are required")
    if ort.__version__ != "1.28.0":
        parser.error("Use onnxruntime==1.28.0; target Android runtime is 1.28.2")
    z = find_zipformer(args.models)
    if args.out.resolve() == z["encoder"].resolve():
        parser.error("Do not overwrite the original encoder")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    pending = args.out.with_name("encoder.pending.onnx")
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED
    options.optimized_model_filepath = str(pending)
    session = ort.InferenceSession(str(z["encoder"]), sess_options=options, providers=["CPUExecutionProvider"])
    del session
    gc.collect()
    original = onnx.load(z["encoder"])
    prepared = onnx.load(pending)
    if list(original.graph.input) != list(prepared.graph.input) or list(original.graph.output) != list(prepared.graph.output):
        raise AssertionError("Encoder interface changed")
    domains = {node.domain for node in prepared.graph.node}
    if not domains <= {"", "com.microsoft"}:
        raise AssertionError(f"Nonportable graph domain: {domains}")
    # Keep original runtime metadata/order and only the imported domains in use.
    del prepared.metadata_props[:]
    prepared.metadata_props.extend(original.metadata_props)
    imports = {o.domain: o.version for o in original.opset_import}
    imports.update({o.domain: o.version for o in prepared.opset_import if o.domain in domains})
    del prepared.opset_import[:]
    prepared.opset_import.extend(onnx.helper.make_opsetid(k, v) for k, v in imports.items())
    onnx.checker.check_model(prepared)
    onnx.save(prepared, pending)

    wavs = sorted((ROOT / "artifacts/demo-stage6-handoff").glob("stem_or_slice_*.wav")) + [
        ROOT / "samples/demo-stress/smoke.wav",
        ROOT / "samples/demo-stress/stage6/smoke.wav",
        ROOT / "samples/demo-stress/adversarial/smoke_worst.wav",
    ]
    if len(wavs) != 9 or not all(p.is_file() for p in wavs):
        raise ValueError("Expected six committed excerpts and three committed smoke wavs")
    clips = [load_wav_mono16k(p) for p in wavs]
    expected = None
    rows = {"original": [], "prepared": []}
    all_identical = True
    for repeat in range(args.repeats):
        for variant in (["original", "prepared"] if repeat % 2 == 0 else ["prepared", "original"]):
            paths = {k: str(v) for k, v in z.items()}
            if variant == "prepared":
                paths["encoder"] = str(pending)
            start = time.perf_counter()
            rec = sherpa_onnx.OfflineRecognizer.from_transducer(
                **paths, num_threads=2, sample_rate=16000, feature_dim=80,
                decoding_method="greedy_search", model_type="transducer", provider="cpu",
            )
            init_ms = (time.perf_counter() - start) * 1000
            start = time.perf_counter()
            actual = [decode(rec, pcm, sr) for pcm, sr in clips]
            decode_ms = (time.perf_counter() - start) * 1000
            if expected is None:
                if variant != "original":
                    raise AssertionError("Reference must be a fresh original model")
                expected = actual
            identical = actual == expected
            all_identical &= identical
            rows[variant].append({"repeat": repeat, "init_ms": init_ms, "decode_ms": decode_ms,
                                  "text_tokens_timestamps_identical": identical})
            print(json.dumps({"variant": variant, **rows[variant][-1]}), flush=True)
            del rec
            gc.collect()
    report = {
        "scope": "host-only encoder preparation, unchanged PCM; not full frozen gate or Android PSS",
        "baseline_runtime_commit": "bd7b5dd", "platform": platform.platform(),
        "onnxruntime_optimizer": ort.__version__, "android_onnxruntime": "1.28.2",
        "sherpa_onnx": sherpa_onnx.__version__, "optimization": "ORT_ENABLE_EXTENDED, CPU",
        "hardware_layout_optimization": False, "weight_requantization": False,
        "source_sha256": sha256(z["encoder"]), "prepared_sha256": sha256(pending),
        "script_sha256": sha256(Path(__file__)),
        "source_bytes": z["encoder"].stat().st_size, "prepared_bytes": pending.stat().st_size,
        "source_nodes": len(original.graph.node), "prepared_nodes": len(prepared.graph.node),
        "opsets": imports, "graph_domains": sorted(domains),
        "metadata_identical": list(original.metadata_props) == list(prepared.metadata_props),
        "inputs": [{"path": p.relative_to(ROOT).as_posix(), "sha256": sha256(p)} for p in wavs],
        "num_threads": 2, "repeats": args.repeats, "trials": rows,
        "medians": {name: {key: statistics.median(row[key] for row in trials)
                            for key in ("init_ms", "decode_ms")} for name, trials in rows.items()},
        "all_text_tokens_timestamps_identical": all_identical,
        "reference_outputs": expected,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    if not all_identical:
        raise AssertionError("Candidate not promoted: native output parity failed")
    pending.replace(args.out)
    print(json.dumps({"out": str(args.out), "report": str(args.report), "medians": report["medians"]}))


if __name__ == "__main__":
    main()
