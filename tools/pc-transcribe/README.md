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

    uv run transcribe.py <audio file>

writes `<audio name without extension>.transcript.json` next to the audio file.

## Self-check (no GPU)

    uv run python -m unittest
