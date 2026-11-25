"""Transcribe audiobooks into transcript files eppy imports.

    uv run transcribe.py <audio file or folder>

Writes <audio name without extension>.transcript.json next to each audio file, in the JSON FileTranscript.save
writes on the phone. A folder is searched recursively; audio whose transcript file is newer is skipped, and so are
folders holding a .nomedia or .ignore file. See ../../.scratch/pc-transcribe/spec.md.
"""
import json
import os
import re
import sys
import time
import zipfile
from html.parser import HTMLParser
from pathlib import Path

AUDIO = (".m4b", ".m4a", ".mp3", ".ogg", ".opus", ".flac")
SR = 16000
CHUNK_S = 30 * 60  # audio is decoded and transcribed in pieces of about this length, so a 57 h book fits in RAM
TAIL_S = 60  # segments ending in the last minute of a piece are redone with the next piece (no cut words)


def add_cuda_dlls():
    """Windows: CTranslate2 finds cuBLAS/cuDNN from the nvidia-*-cu12 wheels only if their bin folders are searched."""
    if os.name != "nt":
        return
    import importlib.util
    spec = importlib.util.find_spec("nvidia")
    for root in (spec.submodule_search_locations or []) if spec else []:
        for sub in Path(root).iterdir():
            b = sub / "bin"
            if b.is_dir():
                os.add_dll_directory(str(b))
                os.environ["PATH"] = str(b) + os.pathsep + os.environ["PATH"]


class Filter:
    """Segments -> [t0, t1, text, [[w0, w1, word], ...]] in ms, dropping what Transcriber.parse drops."""

    def __init__(self):
        self.prev = None

    def convert(self, segments, offset_ms=0):
        out = []
        for s in segments:
            text = s.text.strip()
            if not text or text == self.prev:
                continue
            if s.no_speech_prob > 0.6 and len(text) < 12:
                continue
            if text[0] == "[" and text[-1] == "]" or text[0] == "(" and text[-1] == ")":  # [Music]
                continue
            self.prev = text
            words = []
            for w in s.words or []:
                word = w.word.strip()
                if word:
                    words.append([offset_ms + ms(w.start), offset_ms + ms(w.end), word])
            out.append([offset_ms + ms(s.start), offset_ms + ms(s.end), text, words])
        return out


def ms(seconds):
    return int(round(seconds * 1000))


def pcm_pieces(path):
    """Yields the audio as 16 kHz mono float32 arrays of about CHUNK_S seconds each."""
    import av
    import numpy as np
    from faster_whisper.audio import _group_frames, _ignore_invalid_frames, _resample_frames

    resampler = av.audio.resampler.AudioResampler(format="s16", layout="mono", rate=SR)
    buf, n = [], 0
    with av.open(str(path), mode="r") as container:
        frames = _resample_frames(_group_frames(_ignore_invalid_frames(container.decode(audio=0)), 500000), resampler)
        for frame in frames:
            a = frame.to_ndarray().reshape(-1)
            buf.append(a)
            n += len(a)
            if n >= CHUNK_S * SR:
                yield np.concatenate(buf).astype(np.float32) / 32768.0
                buf, n = [], 0
    if buf:
        yield np.concatenate(buf).astype(np.float32) / 32768.0


# ---- names prompt, ported from TranscriptSync.namesPrompt (paragraphs split as in Xhtml.toParagraphs) -------------

BLOCK = {"p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "section", "article", "tr", "dd", "dt",
         "figcaption", "pre", "hr", "header", "footer", "aside", "body"}
SKIP = {"script", "style", "head", "title"}
WORD = re.compile(r"[^\W\d_](?:[^\W\d_]|['’-])*")  # \p{L}[\p{L}'’-]*
SENTENCE_END = re.compile(r"[.!?:;\"“”]\s*$")


class Paragraphs(HTMLParser):
    """XHTML -> [(text, is_heading)]."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.out, self.buf, self.heading, self.skip = [], [], False, 0

    def handle_starttag(self, tag, attrs):
        self._tag(tag, False)

    def handle_startendtag(self, tag, attrs):
        if tag in BLOCK:
            self._flush()
            self.heading = False
        elif tag == "br":
            self.buf.append(" ")

    def handle_endtag(self, tag):
        self._tag(tag, True)

    def _tag(self, tag, closing):
        if tag in SKIP:
            self.skip = max(0, self.skip - 1) if closing else self.skip + 1
        elif self.skip:
            pass
        elif tag == "br":
            self.buf.append(" ")
        elif tag in BLOCK:
            self._flush()
            self.heading = not closing and len(tag) == 2 and tag[0] == "h" and tag[1].isdigit()

    def handle_data(self, data):
        if not self.skip:
            self.buf.append(data)

    def _flush(self):
        s = " ".join("".join(self.buf).split())
        if s:
            self.out.append((s, self.heading))
        self.buf = []

    def close(self):
        super().close()
        self._flush()


def epub_paragraphs(epub):
    with zipfile.ZipFile(epub) as z:
        for n in z.namelist():  # shortcut: zip order, not spine order; only ties in the prompt depend on it
            if n.lower().endswith((".xhtml", ".html", ".htm")):
                p = Paragraphs()
                p.feed(z.read(n).decode("utf-8", errors="replace"))
                p.close()
                yield from p.out


def names_prompt(paragraphs):
    """Names and other capitalised words that the book never writes in lower case."""
    caps, lower = {}, set()
    for text, heading in paragraphs:
        if heading:
            continue
        last_end, sentence_start = 0, True
        for m in WORD.finditer(text):
            if SENTENCE_END.search(text[last_end:m.start()]):
                sentence_start = True
            w = m.group()
            last_end = m.end()
            if w[0].islower():
                lower.add(w.lower())
            elif not sentence_start and len(w) > 2 and w != w.upper():
                caps[w] = caps.get(w, 0) + 1
            sentence_start = False
    names = sorted((w for w, n in caps.items() if n >= 3 and w.lower() not in lower), key=lambda w: -caps[w])
    s = ""
    for w in names:
        if len(s) + len(w) > 380:
            break
        s += (", " if s else "") + w
    return s + "." if s else None


def book_prompt(folder):
    """The names prompt from the first .epub in the folder, or None."""
    epubs = sorted(folder.glob("*.epub"))
    return names_prompt(epub_paragraphs(epubs[0])) if epubs else None


def transcript_path(audio):
    return audio.with_name(audio.stem + ".transcript.json")


def transcribe_file(model, audio, prompt=None, progress=None):
    """Transcribes one audio file and writes its transcript file. Returns the transcript dict."""
    import numpy as np

    flt = Filter()
    segs = []
    carry = np.zeros(0, dtype=np.float32)
    offset = 0  # samples before carry[0]
    pieces = pcm_pieces(audio)
    piece = next(pieces, None)
    while piece is not None:
        nxt = next(pieces, None)
        buf = np.concatenate([carry, piece])
        last = nxt is None
        found, _ = model.transcribe(buf, language="en", word_timestamps=True, vad_filter=True,
                                    initial_prompt=prompt, batch_size=16)
        found = list(found)
        cut = len(buf)
        if not last:
            # Keep segments that end before the tail; the tail starts again in the next piece.
            limit = len(buf) / SR - TAIL_S
            keep = [s for s in found if s.end <= limit]
            cut = int(keep[-1].end * SR) if keep else int(limit * SR)
            found = keep
        segs += flt.convert(found, ms(offset / SR))
        if progress and segs:
            progress(segs[-1][1])
        carry = buf[cut:]
        offset += cut
        piece = nxt
    dur = ms((offset + len(carry)) / SR)
    t = {"v": 1, "name": audio.name, "dur": dur, "covered": [[0, dur]], "segs": segs}
    write(transcript_path(audio), t)
    return t


def write(path, t):
    tmp = path.with_name(path.name + ".tmp")
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(t, f, ensure_ascii=False, separators=(",", ":"))
        os.replace(tmp, path)
    finally:
        tmp.unlink(missing_ok=True)


def load_model():
    add_cuda_dlls()
    from faster_whisper import BatchedInferencePipeline, WhisperModel
    return BatchedInferencePipeline(model=WhisperModel("large-v3", device="cuda", compute_type="float16"))


def probe_ms(audio):
    """Container duration for the progress line only (the transcript's dur comes from the decoded audio)."""
    import av
    try:
        with av.open(str(audio)) as c:
            return max(1, (c.duration or 0) // 1000)
    except Exception:
        return 1


def find_audio(path):
    if path.is_file():
        return [path]
    out = []
    for root, dirs, files in os.walk(path):
        if ".nomedia" in files or ".ignore" in files:
            dirs.clear()
            continue
        dirs.sort()
        out += [Path(root) / f for f in sorted(files) if f.lower().endswith(AUDIO)]
    return out


def needs_work(audio):
    t = transcript_path(audio)
    return not (t.is_file() and t.stat().st_mtime > audio.stat().st_mtime)


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    path = Path(argv[1])
    if not path.exists():
        print(f"Not found: {path}")
        return 2
    todo = [a for a in find_audio(path) if needs_work(a)]
    print(f"{len(todo)} audio file(s) to transcribe")
    if not todo:
        return 0
    model = load_model()
    prompts, failed = {}, 0
    try:
        for audio in todo:
            if audio.parent not in prompts:
                prompts[audio.parent] = book_prompt(audio.parent)
                print(f"{audio.parent.name}: names prompt: {prompts[audio.parent]}")
            start = time.time()
            total = probe_ms(audio)

            def progress(t_ms, start=start, audio=audio, total=total):
                print(f"\r{audio.name}: {100 * t_ms / total:.0f}%, {time.time() - start:.0f} s", end="", flush=True)

            try:
                t = transcribe_file(model, audio, prompts[audio.parent], progress)
                took = time.time() - start
                print(f"\r{audio.name}: 100%, {t['dur'] / 3.6e6:.2f} h in {took / 60:.1f} min "
                      f"({t['dur'] / 1000 / max(took, 1e-9):.0f}x), {len(t['segs'])} segments")
            except Exception as e:  # one bad file doesn't stop the library
                failed += 1
                print(f"\n{audio.name}: FAILED: {e!r}")
    except KeyboardInterrupt:
        print("\nStopped. The file in progress has no transcript file.")
        return 130
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
