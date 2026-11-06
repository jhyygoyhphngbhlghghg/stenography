#!/usr/bin/env python3
"""IntonationPunct vs Impl 3c3f9ce (no GGUF required)."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

import intonation_punct as P  # noqa: E402


class Intonation(unittest.TestCase):
    def test_pinned_to_impl_3c3f9ce(self):
        self.assertEqual(P.IMPL_SECOND_PASS_HEAD, "3c3f9ce61f803d716276f95fa7e6098cc2fe7be4")

    def test_zipformer_none_gets_terminal_period(self):
        self.assertEqual("Добрый день.", P.apply("Добрый день"))

    def test_greeting_plus_vocative_gets_intonation_comma_and_period(self):
        out = P.apply("Здравствуйте коллеги сегодня")
        self.assertIn(",", out)
        self.assertTrue(out.endswith("."))
        self.assertIn("коллеги", out)

    def test_s1_like_turn_gets_multiple_spoken_commas_not_just_period(self):
        out = P.apply("Здравствуйте коллеги сегодня мы начинаем но это важно")
        self.assertGreaterEqual(out.count(","), 2, out)
        self.assertTrue(out.endswith("."))
        self.assertIn("но", out)

    def test_leading_filler_gets_spoken_comma(self):
        out = P.add_spoken_commas("ну вот послушайте")
        self.assertTrue(out.startswith("ну,"))
        self.assertIn("вот,", out)

    def test_never_strips_existing_commas(self):
        src = "Ну, вот, послушайте"
        self.assertEqual("Ну, вот, послушайте.", P.apply(src))

    def test_never_strips_existing_question_mark(self):
        self.assertEqual("кто здесь?", P.apply("кто здесь?"))

    def test_question_word_gets_question_mark_not_period(self):
        self.assertEqual("Кто здесь?", P.apply("Кто здесь"))

    def test_folds_yo_for_display(self):
        self.assertEqual("Елка.", P.apply("Ёлка"))
        self.assertEqual("елка", P.fold_yo("ёлка"))

    def test_punctless_hyp_accepts_gguf_intonation_comma(self):
        hyp = "Здравствуйте коллеги"
        cand = "Здравствуйте, коллеги."
        out = P.apply(hyp, cand)
        self.assertIn(",", out)
        self.assertTrue(out.endswith("."))
        self.assertNotIn("ё", out)

    def test_existing_comma_layout_not_rewritten_to_textbook(self):
        hyp = "Ну, вот послушайте"
        textbook = "Ну вот, послушайте."
        out = P.apply(hyp, textbook)
        self.assertIn("Ну, вот", out)
        self.assertNotIn("Ну вот,", out)

    def test_candidate_that_drops_comma_is_rejected(self):
        hyp = "Ну, хорошо"
        stripped = "Ну хорошо."
        out = P.merge_candidate(hyp, stripped)
        self.assertIn(",", out)

    def test_invented_words_rejected(self):
        hyp = "привет"
        out = P.apply(hyp, "Здравствуйте, коллеги!")
        self.assertTrue(out.startswith("привет") or out.startswith("Привет"))
        self.assertNotIn("коллеги", out)

    def test_punctless_zipformer_accepts_added_intonation_punct(self):
        u = {"speaker_id": "speaker_1", "start_ms": 0, "end_ms": 4000, "text": "Здравствуйте коллеги"}
        out = P.punctuate_utterances([u], candidate_fn=lambda t, g: "Здравствуйте, коллеги.")[0]
        self.assertIn(",", out["text"])
        self.assertTrue(out["text"].endswith("."))
        self.assertEqual("speaker_1", out["speaker_id"])


if __name__ == "__main__":
    unittest.main()
