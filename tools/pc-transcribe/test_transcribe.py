"""Self-check without a GPU: uv run python -m unittest"""
import json
import tempfile
import unittest
import zipfile
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


CHAPTER = """<?xml version="1.0"?><html><head><title>Tobin Mara Ilsa</title><style>p{}</style></head><body>
<h1>Tobin and Mara and Ilsa</h1>
<p>Then Tobin saw Mara. He told Mara and Ilsa that Tobin was fine; Tobin agreed.</p>
<p>The NASA man met Tobin, Mara and Ilsa at the Bridge. Later Ilsa went to the Bridge.<br/>Wren said &ldquo;Hi&rdquo; to Mara and Ilsa, and then Wren and Jet met Wren&rsquo;s friend at the Bridge.</p>
<p>A bridge is a road. We met Jet and Jet and Jet at Al&#8217;s place with Bo, Bo and Bo.</p>
</body></html>"""


class NamesPromptTest(unittest.TestCase):
    def test_tiny_epub(self):
        with tempfile.TemporaryDirectory() as d:
            epub = Path(d) / "book.epub"
            with zipfile.ZipFile(epub, "w") as z:
                z.writestr("mimetype", "application/epub+zip")
                z.writestr("OEBPS/ch1.xhtml", CHAPTER)
            # Mara 5, Ilsa 4, Tobin 3 (sentence starts and the heading don't count); Wren only twice
            # mid-sentence; Bridge also written as "bridge"; NASA all caps; Bo too short; Jet 4 times.
            self.assertEqual(transcribe.book_prompt(Path(d)), "Mara, Ilsa, Jet, Tobin.")

    def test_no_epub(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertIsNone(transcribe.book_prompt(Path(d)))

    def test_length_limit(self):
        names = [f"Nam{chr(97 + i // 26)}{chr(97 + i % 26)}" for i in range(100)]  # Namaa, Namab, ...
        paras = [(" ".join(f"x {n}" for _ in range(3)), False) for n in names]
        p = transcribe.names_prompt(paras)
        self.assertLessEqual(len(p), 381)
        self.assertTrue(p.endswith("."))
        self.assertTrue(p.startswith("Namaa, Namab, "))
        self.assertEqual(len(p), 7 * 54 - 2 + 1)  # 54 five-letter names fit in 380 characters


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



class TimelineTest(unittest.TestCase):
    def test_container_time_from_frame_timestamps(self):
        # 48 kHz frames of 1024 samples; at the join the container plays only 24 of one frame's samples.
        frames, t = [], 0
        for i in range(6):
            frames.append(NS(time=t / 48000, samples=1024, sample_rate=48000))
            t += 24 if i == 2 else 1024
        tl = transcribe.Timeline()
        self.assertEqual(len(list(tl.frames(frames))), 6)
        self.assertEqual(tl.at, [0.0, 1024 * 3 * 1000 / 48000])  # one breakpoint, at the frame after the join
        self.assertEqual(tl(10), 10)                              # before the join: unchanged
        self.assertEqual(tl(128), 107)                            # end: 6144 decoded samples, 5144 in the container
        self.assertEqual(tl.segs([[0, 100, "x", [[0, 100, "x"]]]]), [[0, 79, "x", [[0, 79, "x"]]]])


class AuditTest(unittest.TestCase):
    def test_verdicts_and_snippets(self):
        import audit_skips as a
        self.assertEqual(a.found_share("The cat sat. The dog!", a.norm("the dog the bird")), 0.75)
        self.assertEqual([a.verdict(x) for x in (0.9, 0.8, 0.6, 0.5, 0.1)],
                         ["false mark", "false mark", "unclear", "probably skipped", "probably skipped"])
        self.assertEqual(a.ends(" ".join(str(i) for i in range(30)), 3), "0 1 2 … 27 28 29")
        self.assertEqual(a.hms(3725.9), "1:02:05")
        t = {"segs": [[0, 20000, "a b", [[0, 1, "a"], [1, 2, "b"]]], [0, 20000, "x", [[0, 1, "w"]] * 40]]}
        self.assertEqual(len(a.sparse_segments(t)), 1)


class LibraryTest(unittest.TestCase):
    def test_skip_rule_and_walk(self):
        import os
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            (d / "b1").mkdir()
            (d / "b2").mkdir()
            (d / "skip").mkdir()
            old, new = d / "b1" / "Old.m4b", d / "b2" / "New.MP3"
            for p in (old, new, d / "skip" / "x.m4a", d / "b1" / "cover.jpg"):
                p.write_bytes(b"")
            (d / "skip" / ".ignore").write_bytes(b"")
            self.assertEqual(transcribe.find_audio(d), [old, new])
            self.assertEqual(transcribe.find_audio(old), [old])
            # Older transcript file: redone. Newer one: skipped.
            for audio, t_age in ((old, -100), (new, +100)):
                t = transcribe.transcript_path(audio)
                t.write_text("{}")
                a = audio.stat().st_mtime
                os.utime(t, (a + t_age, a + t_age))
            self.assertTrue(transcribe.needs_work(old))
            self.assertFalse(transcribe.needs_work(new))
            self.assertTrue(transcribe.needs_work(d / "skip" / "x.m4a"))  # no transcript file at all


if __name__ == "__main__":
    unittest.main()
