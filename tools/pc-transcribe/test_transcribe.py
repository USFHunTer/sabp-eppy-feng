"""Self-check without a GPU: uv run python -m unittest"""
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace as NS

import transcribe


def seg(start, end, text, nsp=0.0, words=None):
    if words is None:
        words = [NS(start=start, end=end, word=" " + w) for w in text.split()]
    return NS(start=start, end=end, text=text, no_speech_prob=nsp, words=words)


class FilterTest(unittest.TestCase):
    def test_converts_to_ms_and_strips_words(self):
        out = transcribe.Filter().convert([seg(1.0, 2.5, " Hello there", words=[
            NS(start=1.0, end=1.4, word=" Hello"), NS(start=1.4, end=1.5, word=" "), NS(start=1.5, end=2.5, word=" there")])],
            offset_ms=1000)
        self.assertEqual(out, [[2000, 3500, "Hello there", [[2000, 2400, "Hello"], [2500, 3500, "there"]]]])

    def test_drop_rules(self):
        f = transcribe.Filter()
        out = f.convert([
            seg(0, 1, "  "),                      # empty
            seg(1, 2, "One two."),
            seg(2, 3, "One two."),                # same as the previous kept segment
            seg(3, 4, "Hmm.", nsp=0.9),           # probably silence, and short
            seg(4, 5, "A long sentence here.", nsp=0.9),  # long enough to keep
            seg(5, 6, "[Music]"),
            seg(6, 7, "(laughs)"),
            seg(7, 8, "One two."),                # not the previous kept one any more
        ])
        self.assertEqual([s[2] for s in out], ["One two.", "A long sentence here.", "One two."])

    def test_dedup_spans_calls(self):
        f = transcribe.Filter()
        f.convert([seg(0, 1, "Same.")])
        self.assertEqual(f.convert([seg(1, 2, "Same.")]), [])


class WriteTest(unittest.TestCase):
    def test_writes_json_and_no_tmp(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "a.transcript.json"
            t = {"v": 1, "name": "a.m4b", "dur": 5, "covered": [[0, 5]], "segs": [[0, 5, "Café", [[0, 5, "Café"]]]]}
            transcribe.write(p, t)
            self.assertEqual(json.loads(p.read_text(encoding="utf-8")), t)
            self.assertEqual([x.name for x in Path(d).iterdir()], ["a.transcript.json"])

    def test_transcript_path(self):
        self.assertEqual(transcribe.transcript_path(Path("x/Book One.m4b")), Path("x/Book One.transcript.json"))


if __name__ == "__main__":
    unittest.main()
