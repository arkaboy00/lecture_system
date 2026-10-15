# Lecture Desktop

Laptop side of [Lecture Capture](../lecture_capture): pulls lecture folders from the phone
over the home Wi-Fi (`lecture_sync`) and processes them (`lecture_process`): speech
recognition now, notes for Obsidian next.

Sync is Python 3 standard library only (tkinter window). Nothing runs in the background: the window
is the process, and the phone-side sync service stops itself when this app reports "done".

## Use

1. On the phone: Lecture Capture → «Синхр.» → «Начать синхронизацию».
2. On the laptop: desktop icon «Лекции — синхронизация» → «Синхронизировать».
   The first time, enter the 6-digit code shown on the phone.

Lectures land in `~/Documents/Lectures/<folder>` (change `inbox` in
`%APPDATA%\LectureDesktop\config.json`). Only lectures with `ended` in `lecture.json` are
complete; a lecture still being recorded is pulled as far as it goes.

Console mode (used by tests and for debugging):

```
python -m lecture_sync --cli [--code 123456] [--inbox DIR] [--config FILE]
```

Desktop shortcut: `powershell -File install_shortcut.ps1`.

## Processing

```
py -3.14 -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt
.venv\Scripts\python -m lecture_process transcribe [folder ...]
```

Without folders: every finished lecture in the inbox. Output goes to
`~/Documents/Lectures_work/<folder>/transcript.json` (`work` in the config); the lecture
folder itself is never written to.

`transcript.json`: lecture names and kind, audio segments, photos (obsolete marked), and
recognized pieces with word timings, all in seconds since `lecture_start` on one timeline
(`lecture_process/lecture.py` explains how phone times map onto it).

Recognition: faster-whisper `large-v3` on the GPU (RTX 5070 Laptop: model load ~4 s,
~20-45x realtime; the first run downloads ~3 GB to the Hugging Face cache). Russian, with a
prompt of the subject's terms from `glossary/<Subject>.txt` (edit freely). Silero VAD skips
silence; `asr.clean` drops what Whisper invents on silence: subtitle credits, echoes of the
prompt, repetition loops. Raw model output is cached per audio file in
`<work>/<folder>/asr/`, so changing the filters or re-running after a sync is instant.

## Protocol

Defined by the phone (`lecture_capture/core/.../sync/SyncProtocol.kt`): UDP discovery on
47811, HTTP on 47812; `/hello`, `/pair`, `/manifest`, `/file` (Range), `/ack`, `/done`.
Downloads go to `<file>.part`, resume with `Range`, are verified by SHA-256 and then renamed.
Network errors are retried for about a minute with rediscovery, so a phone reconnecting to
Wi-Fi does not break the run.

## Tests

```
python -m unittest discover -s tests
```

`test_process.py`: the timeline (mono vs wall clock, reboots, later edits, torn lines) and
the cleanup filters. `test_client.py`, against a fake phone (stdlib HTTP server): pairing, resume after a dropped connection,
skipping unchanged files, grown files, a folder renamed on the phone, lost pairing, phone
missing during retries, cancel.
