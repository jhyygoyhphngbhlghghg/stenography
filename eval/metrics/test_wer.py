"""Hand-checked WER cases. These are toy strings, not speech metrics."""

from __future__ import annotations

import csv
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from eval.metrics.wer_rtf import (
    HEADER,
    normalize_text,
    rtf_value,
    wer_value,
    word_error_counts,
    word_error_rate,
)

ROOT = Path(__file__).resolve().parents[2]


class WerFunctionTests(unittest.TestCase):
    def test_identical_pair(self) -> None:
        self.assertEqual(word_error_counts("кот сидит", "кот сидит"), (0, 2))
        self.assertEqual(word_error_rate("кот сидит", "кот сидит"), 0.0)

    def test_one_substitution_in_four(self) -> None:
        self.assertEqual(
            word_error_counts("кот сидит на окне", "кот лежит на окне"),
            (1, 4),
        )
        self.assertEqual(wer_value("кот сидит на окне", "кот лежит на окне"), "0.25")

    def test_one_deletion_in_three(self) -> None:
        self.assertEqual(word_error_counts("один два три", "один три"), (1, 3))
        self.assertEqual(wer_value("один два три", "один три"), "0.333333")

    def test_one_insertion_in_two(self) -> None:
        self.assertEqual(word_error_counts("один два", "один два три"), (1, 2))
        self.assertEqual(wer_value("один два", "один два три"), "0.5")

    def test_case_and_punctuation_normalize_away(self) -> None:
        self.assertEqual(normalize_text("Привет, мир!"), "привет мир")
        self.assertEqual(word_error_counts("Привет, мир!", "привет мир"), (0, 2))

    def test_hyphen_splits_and_yo_casefolds(self) -> None:
        self.assertEqual(normalize_text("Ёлка"), "ёлка")
        self.assertEqual(normalize_text("как-нибудь"), "как нибудь")

    def test_empty_reference_is_undefined_not_zero(self) -> None:
        self.assertIsNone(word_error_counts("", ""))
        self.assertIsNone(word_error_counts("", "что-то"))
        self.assertIsNone(word_error_counts("...", "..."))
        self.assertIsNone(word_error_rate("", "что-то"))
        self.assertEqual(wer_value("", "что-то"), "pending")

    def test_utterances_join_in_order(self) -> None:
        joined_ref = " ".join(["Кот сидит на окне", "Да, сидит"])
        joined_hyp = " ".join(["Кот лежит на окне", "Да, сидит"])
        self.assertEqual(word_error_counts(joined_ref, joined_hyp), (1, 6))
        self.assertEqual(wer_value(joined_ref, joined_hyp), "0.166667")


class RtfFunctionTests(unittest.TestCase):
    def test_missing_input_stays_pending(self) -> None:
        self.assertEqual(rtf_value(None, None), "pending")
        self.assertEqual(rtf_value(1000, None), "pending")
        self.assertEqual(rtf_value(None, 1000), "pending")

    def test_ratio_of_supplied_integers(self) -> None:
        self.assertEqual(rtf_value(1000, 2000), "0.5")
        self.assertEqual(rtf_value(0, 2000), "0")


class CliTests(unittest.TestCase):
    def test_toy_fixture_prints_wer_and_pending_rtf(self) -> None:
        proc = _run(
            "--hyp",
            "eval/fixtures/toy_hyp.json",
            "--gold",
            "eval/fixtures/toy_gold.json",
            "--clip-id",
            "toy",
            "--notes",
            "toy fixture; not a measured clip",
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        row = _one_row(proc.stdout)
        self.assertEqual(row["wer"], "0.166667")
        self.assertEqual(row["rtf"], "pending")
        self.assertEqual(row["duration_ms"], "")
        self.assertEqual(row["wall_ms"], "")
        self.assertEqual(row["speakers_hyp"], "2")
        self.assertEqual(row["speakers_ref"], "2")
        self.assertEqual(row["clip_id"], "toy")
        self.assertEqual(row["test_id"], "")
        self.assertEqual(row["commit"], "")

    def test_empty_gold_stub_does_not_score_zero(self) -> None:
        proc = _run(
            "--hyp",
            "eval/fixtures/toy_hyp.json",
            "--gold",
            "eval/gold/clip_01.gold.json",
            "--clip-id",
            "clip_01",
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        row = _one_row(proc.stdout)
        self.assertEqual(row["wer"], "pending")
        self.assertNotEqual(row["wer"], "0")
        self.assertEqual(row["speakers_ref"], "0")
        self.assertEqual(row["rtf"], "pending")

    def test_supplied_times_echo_and_divide(self) -> None:
        proc = _run(
            "--hyp",
            "eval/fixtures/toy_hyp.json",
            "--gold",
            "eval/fixtures/toy_gold.json",
            "--duration-ms",
            "2000",
            "--wall-ms",
            "1000",
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        row = _one_row(proc.stdout)
        self.assertEqual(row["duration_ms"], "2000")
        self.assertEqual(row["wall_ms"], "1000")
        self.assertEqual(row["rtf"], "0.5")

    def test_bad_duration_refuses_a_row(self) -> None:
        proc = _run(
            "--hyp",
            "eval/fixtures/toy_hyp.json",
            "--gold",
            "eval/fixtures/toy_gold.json",
            "--duration-ms",
            "0",
            "--wall-ms",
            "10",
        )
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(proc.stdout, "")
        self.assertIn("refusing to invent RTF", proc.stderr)

    def test_out_appends_without_a_second_header(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp) / "row.csv"
            args = [
                "--hyp",
                "eval/fixtures/toy_hyp.json",
                "--gold",
                "eval/fixtures/toy_gold.json",
                "--out",
                str(dest),
            ]
            first = _run(*args)
            second = _run(*args)
            self.assertEqual(first.returncode, 0, first.stderr)
            self.assertEqual(second.returncode, 0, second.stderr)
            lines = [line for line in dest.read_text(encoding="utf-8").splitlines() if line]
            self.assertEqual(lines[0], ",".join(HEADER))
            self.assertEqual(len(lines), 3)


class SkeletonTests(unittest.TestCase):
    def test_manifest_lists_three_empty_gold_files(self) -> None:
        manifest = json.loads((ROOT / "eval" / "manifest.json").read_text(encoding="utf-8"))
        clips = manifest["clips"]
        self.assertEqual([clip["clip_id"] for clip in clips], ["clip_01", "clip_02", "clip_03"])
        for clip in clips:
            gold = json.loads((ROOT / clip["gold"]).read_text(encoding="utf-8"))
            self.assertEqual(gold, {"transcript": []})
            self.assertTrue((ROOT / "eval" / "clips" / "README.md").is_file())


def _run(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, "-m", "eval.metrics.wer_rtf", *args],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )


def _one_row(stdout: str) -> dict[str, str]:
    rows = list(csv.DictReader(io.StringIO(stdout)))
    if len(rows) != 1:
        raise AssertionError(f"expected one data row, got {rows!r} from {stdout!r}")
    return rows[0]


if __name__ == "__main__":
    unittest.main()
