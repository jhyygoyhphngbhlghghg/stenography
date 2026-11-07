#!/usr/bin/env python3
"""Compare an isolated GGUF candidate without changing production weights.

Uses a local llama-completion executable, CPU only, deterministic greedy
sampling, and the Android SYSTEM prompt. Reports raw completion parity, NOT
Russian punctuation accuracy or Android RAM. The host llama build is newer
than the Android binding, so this is a screening test, not device validation.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import platform
import re
import subprocess
import threading
import time
from pathlib import Path

import psutil

ROOT = Path(__file__).resolve().parents[1]


def sha(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def system_prompt() -> str:
    source = (ROOT / "src/android/app/src/main/java/team/mikcoders/neurosteno/llm/LocalLlmPostPass.kt").read_text(encoding="utf-8")
    body = source.split("const val SYSTEM =", 1)[1].split("fun userPrompt", 1)[0]
    return "".join(json.loads(value) for value in re.findall(r'"(?:[^"\\]|\\.)*"', body))


def complete(runner: Path, model: Path, prompt_file: Path) -> dict:
    command = [str(runner.resolve()), "-m", str(model.resolve()), "-f", str(prompt_file.resolve()),
               "--no-conversation", "--no-display-prompt", "--simple-io", "--temp", "0",
               "--seed", "0", "-c", "1024", "-n", "96", "-t", "2", "-tb", "2",
               "-b", "128", "-ub", "128", "-ngl", "0"]
    before = time.perf_counter()
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stop = threading.Event()
    peak = {"rss_bytes": 0, "private_commit_bytes": 0}

    def sample() -> None:
        try:
            native = psutil.Process(process.pid)
            while not stop.is_set():
                memory = native.memory_info()
                peak["rss_bytes"] = max(peak["rss_bytes"], memory.rss, getattr(memory, "peak_wset", 0))
                peak["private_commit_bytes"] = max(peak["private_commit_bytes"], getattr(memory, "private", 0))
                stop.wait(0.02)
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            return

    sampler = threading.Thread(target=sample, daemon=True)
    sampler.start()
    try:
        stdout, stderr = process.communicate(timeout=120)
    except subprocess.TimeoutExpired:
        process.kill()
        process.communicate()
        raise
    finally:
        stop.set()
        sampler.join()
    if process.returncode:
        raise RuntimeError(stderr.decode("utf-8", errors="replace"))
    text = stdout.decode("utf-8", errors="strict").strip()
    return {"text": text, "wall_ms": (time.perf_counter() - before) * 1000,
            "host_peak_rss_bytes": peak["rss_bytes"],
            "host_peak_private_commit_bytes": peak["private_commit_bytes"],
            "native_log": stderr.decode("utf-8", errors="replace")}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runner", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, default=ROOT / "models/llm/qwen2-0_5b-instruct-q4_0.gguf")
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    work = args.out.parent / "build" / "llm-prompts"
    work.mkdir(parents=True, exist_ok=True)
    cases = [
        "Здравствуйте коллеги сегодня мы обсуждаем результаты работы",
        "Если решение будет принято мы сообщим об этом завтра",
        "Да конечно я согласен с вашим предложением",
        "Подождите пожалуйста вы уже отправили документ",
        "Мы хотели закончить раньше но возникли дополнительные вопросы",
        "Здесь нет сложных терминов только короткое сообщение",
    ]
    excerpts = json.loads((ROOT / "artifacts/optimization/asr-runtime.json").read_text(encoding="utf-8"))
    cases.extend(row["text"] for row in excerpts["decoders"][0]["clips"] if row["text"])
    system = system_prompt()
    output = {"scope": "host GGUF compaction screen; not Android PSS or punctuation accuracy",
              "platform": platform.platform(), "sampling": "temperature=0, seed=0",
              "context": 1024, "batch": 128, "threads": 2, "max_tokens": 96,
              "runner_sha256": sha(args.runner), "script_sha256": sha(Path(__file__)),
              "models": {"baseline": {"sha256": sha(args.baseline), "bytes": args.baseline.stat().st_size},
                         "candidate": {"sha256": sha(args.candidate), "bytes": args.candidate.stat().st_size}},
              "system_prompt": system, "cases": []}
    for i, text in enumerate(cases):
        prompt_file = work / f"{i:02d}.txt"
        prompt_file.write_text(f"<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n"
                               f"Пауза после реплики: 10000 мс.\nРеплика: {text}<|im_end|>\n"
                               "<|im_start|>assistant\n", encoding="utf-8", newline="\n")
        pair = {"input": text, "case_id": i}
        # Alternate order; independent processes prevent candidate memory carryover.
        order = [("baseline", args.baseline), ("candidate", args.candidate)]
        if i % 2:
            order.reverse()
        for name, path in order:
            pair[name] = complete(args.runner, path, prompt_file)
        pair["raw_completion_identical"] = pair["baseline"]["text"] == pair["candidate"]["text"]
        output["cases"].append(pair)
        print(json.dumps({"case_id": i, "identical": pair["raw_completion_identical"],
                          "baseline_rss_mb": pair["baseline"]["host_peak_rss_bytes"] / 1e6,
                          "candidate_rss_mb": pair["candidate"]["host_peak_rss_bytes"] / 1e6}), flush=True)
    output["all_raw_completions_identical"] = all(row["raw_completion_identical"] for row in output["cases"])
    args.out.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({"out": str(args.out), "all_identical": output["all_raw_completions_identical"]}))


if __name__ == "__main__":
    main()
