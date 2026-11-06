"""One-clip WER and RTF.

WER is word Levenshtein over normalized transcript text.
RTF is wall_ms / duration_ms only when both numbers are supplied.
Missing measurements are the token ``pending``. This module never fills them in.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import sys
import unicodedata
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

HEADER = [
    "test_id",
    "clip_id",
    "timestamp_utc",
    "commit",
    "file_name",
    "duration_ms",
    "wall_ms",
    "rtf",
    "wer",
    "speakers_hyp",
    "speakers_ref",
    "notes",
]

PENDING = "pending"


def normalize_text(text: str) -> str:
    """Casefold and turn Unicode punctuation into spaces.

    Hyphens are punctuation, so they split tokens. Letters and digits stay.
    """
    chars: list[str] = []
    for ch in text.casefold():
        if unicodedata.category(ch).startswith("P"):
            chars.append(" ")
        else:
            chars.append(ch)
    return " ".join("".join(chars).split())


def tokenize(text: str) -> list[str]:
    normalized = normalize_text(text)
    if not normalized:
        return []
    return normalized.split(" ")


def word_error_counts(reference: str, hypothesis: str) -> tuple[int, int] | None:
    """Return ``(edit_distance, reference_length)``.

    ``None`` means the reference has no tokens, so WER is undefined.
    """
    ref = tokenize(reference)
    hyp = tokenize(hypothesis)
    if not ref:
        return None
    return (_levenshtein(ref, hyp), len(ref))


def word_error_rate(reference: str, hypothesis: str) -> float | None:
    """WER as a fraction, or ``None`` when the reference is empty."""
    counts = word_error_counts(reference, hypothesis)
    if counts is None:
        return None
    distance, ref_len = counts
    return distance / ref_len


def _levenshtein(ref: list[str], hyp: list[str]) -> int:
    prev = list(range(len(hyp) + 1))
    for i, ref_tok in enumerate(ref, start=1):
        cur = [i]
        for j, hyp_tok in enumerate(hyp, start=1):
            insert = cur[j - 1] + 1
            delete = prev[j] + 1
            replace = prev[j - 1] + (ref_tok != hyp_tok)
            cur.append(min(insert, delete, replace))
        prev = cur
    return prev[-1]


def load_transcript(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"invalid JSON: {path}: {exc}") from exc
    except OSError as exc:
        raise SystemExit(f"cannot read {path}: {exc}") from exc
    require_transcript(data, path)
    return data


def require_transcript(data: object, path: Path) -> None:
    """Accept the mandatory ``docs/JSON.md`` shape. Extra keys are ignored."""
    if not isinstance(data, dict) or "transcript" not in data:
        raise SystemExit(f"{path}: missing object field 'transcript'")
    rows = data["transcript"]
    if not isinstance(rows, list):
        raise SystemExit(f"{path}: 'transcript' must be an array")
    for index, row in enumerate(rows):
        where = f"{path}: transcript[{index}]"
        if not isinstance(row, dict):
            raise SystemExit(f"{where} must be an object")
        if not isinstance(row.get("speaker_id"), str):
            raise SystemExit(f"{where}.speaker_id must be a string")
        if not isinstance(row.get("text"), str):
            raise SystemExit(f"{where}.text must be a string")
        for key in ("start_ms", "end_ms"):
            value = row.get(key)
            if isinstance(value, bool) or not isinstance(value, int):
                raise SystemExit(f"{where}.{key} must be an integer")
        if row["start_ms"] < 0 or row["end_ms"] < row["start_ms"]:
            raise SystemExit(f"{where} timestamps must satisfy 0 <= start_ms <= end_ms")


def concatenate_text(data: dict) -> str:
    return " ".join(row["text"] for row in data["transcript"])


def speaker_count(data: dict) -> int:
    seen: list[str] = []
    for row in data["transcript"]:
        speaker = row["speaker_id"]
        if speaker not in seen:
            seen.append(speaker)
    return len(seen)


def format_ratio(numerator: int, denominator: int) -> str:
    """Decimal ratio with up to 6 places. Does not invent a denominator."""
    if denominator <= 0:
        raise ValueError("denominator must be positive")
    quant = (Decimal(numerator) / Decimal(denominator)).quantize(
        Decimal("0.000001"), rounding=ROUND_HALF_UP
    )
    text = format(quant, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text


def rtf_value(wall_ms: int | None, duration_ms: int | None) -> str:
    if wall_ms is None or duration_ms is None:
        return PENDING
    return format_ratio(wall_ms, duration_ms)


def wer_value(reference: str, hypothesis: str) -> str:
    counts = word_error_counts(reference, hypothesis)
    if counts is None:
        return PENDING
    distance, ref_len = counts
    return format_ratio(distance, ref_len)


def parse_optional_ms(raw: str | None, flag: str) -> int | None:
    if raw is None:
        return None
    try:
        value = int(raw, 10)
    except ValueError as exc:
        raise SystemExit(f"{flag} must be an integer number of milliseconds") from exc
    if flag == "--duration-ms" and value <= 0:
        raise SystemExit(
            "--duration-ms must be > 0 when provided; refusing to invent RTF"
        )
    if flag == "--wall-ms" and value < 0:
        raise SystemExit("--wall-ms must be >= 0 when provided")
    return value


def build_row(args: argparse.Namespace, hyp: dict, gold: dict) -> dict[str, str]:
    duration = parse_optional_ms(args.duration_ms, "--duration-ms")
    wall = parse_optional_ms(args.wall_ms, "--wall-ms")
    return {
        "test_id": args.test_id or "",
        "clip_id": args.clip_id or "",
        "timestamp_utc": args.timestamp_utc or "",
        "commit": args.commit or "",
        "file_name": args.file_name or "",
        "duration_ms": "" if duration is None else str(duration),
        "wall_ms": "" if wall is None else str(wall),
        "rtf": rtf_value(wall, duration),
        "wer": wer_value(concatenate_text(gold), concatenate_text(hyp)),
        "speakers_hyp": str(speaker_count(hyp)),
        "speakers_ref": str(speaker_count(gold)),
        "notes": args.notes or "",
    }


def format_csv(row: dict[str, str]) -> str:
    buffer = io.StringIO()
    writer = csv.DictWriter(buffer, fieldnames=HEADER, lineterminator="\n")
    writer.writeheader()
    writer.writerow(row)
    return buffer.getvalue()


def append_csv(path: Path, row: dict[str, str]) -> None:
    existing = path.read_text(encoding="utf-8") if path.exists() else ""
    data_lines = [line for line in existing.splitlines() if line and not line.startswith("#")]
    if data_lines and data_lines[0] != ",".join(HEADER):
        raise SystemExit(f"{path}: header does not match the harness columns")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8", newline="") as handle:
        if existing and not existing.endswith("\n"):
            handle.write("\n")
        writer = csv.DictWriter(handle, fieldnames=HEADER, lineterminator="\n")
        if not data_lines:
            writer.writeheader()
        writer.writerow(row)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Print one WER/RTF CSV row for a hypothesis JSON and a gold JSON. "
            "Omitted duration or wall time leaves rtf pending. "
            "An empty reference leaves wer pending. No number is invented."
        )
    )
    parser.add_argument("--hyp", required=True, type=Path, help="hypothesis transcript JSON")
    parser.add_argument("--gold", required=True, type=Path, help="gold transcript JSON")
    parser.add_argument("--duration-ms", default=None, help="measured audio duration in ms")
    parser.add_argument("--wall-ms", default=None, help="measured wall time in ms")
    parser.add_argument("--clip-id", default="")
    parser.add_argument("--test-id", default="")
    parser.add_argument("--timestamp-utc", default="")
    parser.add_argument("--commit", default="")
    parser.add_argument("--file-name", default="")
    parser.add_argument("--notes", default="")
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
        help="also append the data row to this CSV; stdout is always printed",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    row = build_row(args, load_transcript(args.hyp), load_transcript(args.gold))
    sys.stdout.write(format_csv(row))
    if args.out is not None:
        append_csv(args.out, row)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
