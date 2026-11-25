# pc-transcribe

Transcribes audiobooks on a PC with an NVIDIA GPU into **transcript files** that eppy imports when the Read tab
opens. See `../../.scratch/pc-transcribe/spec.md` and `../../docs/adr/0001-pc-transcribes-phone-matches.md`.

## Setup (Windows)

1. Install [uv](https://docs.astral.sh/uv/) and an NVIDIA driver recent enough for CUDA 12.
2. In this folder: `uv sync`. That installs faster-whisper and the CUDA 12 cuBLAS/cuDNN 9 runtime as pip wheels
   (`nvidia-cublas-cu12`, `nvidia-cudnn-cu12`); no CUDA Toolkit install is needed. `transcribe.py` adds their DLL
   folders itself. `pyproject.toml` asks uv for versions that ship Windows wheels, since some releases are Linux-only.
3. The first run downloads the Whisper large-v3 model (about 3 GB) from Hugging Face into `~/.cache/huggingface`.

Tested with Python 3.13, uv 0.11, an RTX 4090 and driver 621.14: about 115x real time (one hour of audio in about
30 seconds).

## Use

    uv run transcribe.py <audio file or folder>

- A folder is searched recursively for `.m4b .m4a .mp3 .ogg .opus .flac`. Folders holding a `.nomedia` or `.ignore`
  file are left out (handy for backups).
- Each audio file gets `<audio name without extension>.transcript.json` next to it. An audio file whose transcript file
  is newer than the audio is skipped, so running it again only does new or changed books.
- With an `.epub` in the audio's folder, the tool builds the same names prompt the phone builds (capitalised words the
  book never writes in lower case) and prints it once per book; it helps Whisper spell the names. No EPUB (or a PDF):
  no prompt.
- One progress line per file, then its speed. A file that fails (won't decode, ...) is reported and the run goes on;
  the exit code is 1 if any failed. Ctrl+C stops it; the file in progress gets no transcript file (it's written to a
  `.tmp` and renamed only when complete). There is no resume inside a file.
- The model loads once per run. Audio is decoded and transcribed in 30-minute pieces, so a 57-hour book needs no more
  memory than a short one.

Then copy the `.transcript.json` files into the book's folder on the phone, next to its audio files, and open the
book's Read tab: eppy imports them, shows "Imported transcript for N file(s)", switches transcription on for the book
and shows 100%. The phone only imports a file that names one of the book's audio files, matches its length within
2 seconds and is complete; anything else is noted in Settings → Diagnostics. A newer copy is imported again.

## The transcript file

`{"v":1, "name":"<audio file name>", "dur":<ms>, "covered":[[0,<dur>]], "segs":[[t0,t1,text,[[w0,w1,word],...]],...]}`,
UTF-8, times in integer milliseconds from the start of that audio file: the format the phone stores its own
transcripts in (`FileTranscript.save`). Segments the phone would drop as hallucinations are dropped here too (empty,
repeated, short with a high no-speech probability, or all in `[...]` / `(...)`).

## Self-check (no GPU)

    uv run python -m unittest
