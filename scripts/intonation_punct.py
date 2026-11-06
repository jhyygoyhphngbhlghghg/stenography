#!/usr/bin/env python3
"""Python mirror of Impl IntonationPunct.kt @ 552b142 (PR #7).

Hex.Team scoring lock (PR #4 @ 0824df64, cite only): Zipformer emits NONE,
so this path **must ADD** spoken-intonation `,` `.` `?` `!`.

Never strip hyp punct. Never rewrite existing commas to textbook placement
(commas = speaker intonation gold). е/ё does not count — fold ё→е.

GGUF candidate (GgufLlmPostPass) is merged here so the model cannot strip
marks or invent words. Missing weights → heuristic ADD only.
"""
from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Any

IMPL_SECOND_PASS_HEAD = "552b142898f3cf3a8bddb939f0864730dbafb261"

TERMINAL = frozenset(".?!\u2026")
PUNCT_CHARS = frozenset(".?!,;:\u2026\u2014\u2013")

_QUESTION_START = re.compile(
    r"^(кто|что|чего|чем|кому|где|куда|откуда|когда|почему|зачем|как|"
    r"какой|какая|какое|какие|сколько|разве|неужели)($|\s|[.,?!])",
    re.IGNORECASE,
)
_GREETING = re.compile(
    r"^(здравствуйте|добрый день|доброе утро|добрый вечер|привет)(?=\s+\S)",
    re.IGNORECASE,
)
_VOCATIVE = re.compile(
    r"(^|\s)(коллеги|друзья|ребята|господа)(?=\s+\S)",
    re.IGNORECASE,
)
_LEADING_FILLER = re.compile(
    r"^(ну|вот|да|так|значит|короче|слушай|ага|ладно|хорошо)(?=\s+\S)",
    re.IGNORECASE,
)
_PLEASE = re.compile(
    r"(?<=\S)\s+(пожалуйста)($|\s|[.,?!])",
    re.IGNORECASE,
)
_CONTENT_STRIP = re.compile(r"[^0-9a-zа-яе\s]+")
_WS = re.compile(r"\s+")

GGUF_SYSTEM = (
    "ASR (Zipformer) не ставит знаки. ДОБАВЬ пунктуацию устной речи: запятые, точки, ? !. "
    "Запятая = пауза/интонация говорящего, не школьное правило. "
    "НЕ удаляй уже стоящие знаки. НЕ переставляй чужие запятые. "
    "Не меняй слова. ё и е — один звук, пиши е. Верни только исправленную реплику."
)


def fold_yo(text: str) -> str:
    return (text or "").replace("ё", "е").replace("Ё", "Е")


def squeeze_space(text: str) -> str:
    t = (text or "").replace("\n", " ").replace("\t", " ")
    return _WS.sub(" ", t).strip()


def content_words(text: str) -> list[str]:
    t = fold_yo(text).lower()
    t = _CONTENT_STRIP.sub(" ", t)
    return [w for w in _WS.split(t) if w]


def _has_comma(text: str) -> bool:
    return any(ch == "," or ch == "\u2014" for ch in text)


def _punct_count(text: str) -> int:
    return sum(1 for ch in text if ch in PUNCT_CHARS)


def looks_like_question(text: str) -> bool:
    t = squeeze_space(text).lstrip("".join(PUNCT_CHARS))
    return bool(_QUESTION_START.search(t))


def add_spoken_commas(text: str) -> str:
    t = squeeze_space(text)
    if not t or _has_comma(t):
        return t
    t = _GREETING.sub(lambda m: m.group(0) + ",", t)
    t = _VOCATIVE.sub(lambda m: f"{m.group(1)}{m.group(2)},", t)
    for _ in range(3):
        t = _LEADING_FILLER.sub(
            lambda m: m.group(0) if m.group(0).endswith(",") else m.group(0) + ",",
            t,
        )
    t = _PLEASE.sub(lambda m: f", {m.group(1)}{m.group(2)}", t)
    return squeeze_space(t)


def restore_terminal(text: str, next_gap_ms: int = 10**18) -> str:
    t = squeeze_space(text)
    if not t:
        return t
    if t[-1] in TERMINAL:
        return t
    if next_gap_ms < 0:
        return t
    return f"{t}?" if looks_like_question(t) else f"{t}."


def _take_terminal_only(src: str, cand: str) -> str:
    if src and src[-1] in TERMINAL:
        return src
    if cand and cand[-1] in TERMINAL:
        return src + cand[-1]
    return src


def merge_candidate(original: str, candidate: str | None) -> str:
    src = squeeze_space(original)
    if not src:
        return src
    cand = squeeze_space(candidate or "")
    if not cand:
        return src
    if content_words(src) != content_words(cand):
        return src
    if _punct_count(cand) < _punct_count(src):
        return src
    if _has_comma(src):
        return _take_terminal_only(src, cand)
    return cand


def apply(original: str, candidate: str | None = None, next_gap_ms: int = 10**18) -> str:
    merged = merge_candidate(original, candidate)
    with_commas = merged if _has_comma(merged) else add_spoken_commas(merged)
    return fold_yo(restore_terminal(with_commas, next_gap_ms))


def punctuate_utterances(rows: list[dict[str, Any]], *, candidate_fn=None) -> list[dict[str, Any]]:
    """ADD spoken-intonation punct. Never strip. Optional GGUF candidate_fn(text, next_gap_ms)."""
    out = []
    n = len(rows)
    for i, u in enumerate(rows):
        nxt = rows[i + 1]["start_ms"] - u["end_ms"] if i + 1 < n else 10**18
        nxt = max(0, int(nxt)) if i + 1 < n else 10**18
        cand = None
        if candidate_fn is not None:
            try:
                cand = candidate_fn(u.get("text") or "", nxt)
            except Exception:
                cand = None
        text = apply(u.get("text") or "", cand, nxt)
        out.append({**u, "text": text})
    return out


def find_gguf(models: Path) -> Path | None:
    d = Path(models) / "llm"
    if not d.is_dir():
        return None
    hits = sorted(p for p in d.iterdir() if p.suffix.lower() == ".gguf" and p.stat().st_size > 0)
    return hits[0] if hits else None


def try_gguf_runner(gguf: Path | None):
    """Return (complete_fn, engine_name) or (None, reason). Host has no llama.cpp JNI."""
    if gguf is None or not Path(gguf).is_file() or Path(gguf).stat().st_size <= 0:
        return None, "gguf-missing"
    try:
        from llama_cpp import Llama  # type: ignore
    except ImportError:
        return None, "llama_cpp-not-installed"
    try:
        llm = Llama(
            model_path=str(gguf),
            n_ctx=1024,
            n_threads=max(1, min(4, os.cpu_count() or 2)),
            verbose=False,
        )
    except Exception as e:
        return None, f"llama_cpp-init-failed:{type(e).__name__}"

    def complete(text: str, next_gap_ms: int) -> str | None:
        prompt = f"Пауза после реплики: {min(int(next_gap_ms), 10_000)} мс.\nРеплика: {text}"
        out = llm.create_chat_completion(
            messages=[
                {"role": "system", "content": GGUF_SYSTEM},
                {"role": "user", "content": prompt},
            ],
            max_tokens=128,
            temperature=0.0,
        )
        return (out["choices"][0]["message"]["content"] or "").strip() or None

    return complete, "llama.cpp-python Qwen2-0.5B-Instruct Q4_0"
