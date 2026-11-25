"""Transcribe an audio file into a transcript file eppy imports.

    uv run transcribe.py <audio file>

Writes <audio name without extension>.transcript.json next to the audio, in the JSON FileTranscript.save writes
on the phone. See ../../.scratch/pc-transcribe/spec.md.
"""
import json
import os
import sys
import time
from pathlib import Path

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


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    audio = Path(argv[1])
    model = load_model()
    start = time.time()
    t = transcribe_file(model, audio)
    took = time.time() - start
    print(f"{audio.name}: {len(t['segs'])} segments, {t['dur'] / 3.6e6:.2f} h of audio in {took / 60:.1f} min "
          f"({t['dur'] / 1000 / max(took, 1e-9):.0f}x)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
