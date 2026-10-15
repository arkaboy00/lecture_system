# Lecture Desktop

Laptop side of [Lecture Capture](../lecture_capture): pulls lecture folders from the phone
over the home Wi-Fi (`lecture_sync`) and processes them (`lecture_process`): speech
recognition now, notes for Obsidian next.

Sync is Python 3 standard library only (tkinter window). Nothing runs in the background: the window
is the process, and the phone-side sync service stops itself when this app reports "done".

## Use

1. On the phone: Lecture Capture → «Синхр.» → «Начать синхронизацию».
2. On the laptop: desktop icon «Лекции» → «Синхронизировать», then «Обработать».
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

`process` (default) = `transcribe` + the notes input; the window's «Обработать» does the
same. Without folders: every finished lecture in the inbox. Output goes to
`~/Documents/Lectures_work/<folder>/` (`work` in the config); the lecture folder itself is
never written to:

- `transcript.json` — machine-readable result (below);
- `notes_input/` — what Claude Code reads to write the notes, rebuilt on every run:
  `transcript.md` (speech in paragraphs with clock times, `[ФОТО n · time · доска]` markers
  where the shutter was pressed, breaks in the recording), `photos/` (relevant photos only,
  upright, ≤ 2000 px: board formulas stay readable), `info.json` (names, kind, date, photo list).

`transcript.json`: lecture names and kind, audio segments, photos (obsolete marked), and
recognized pieces with word timings, all in seconds since `lecture_start` on one timeline
(`lecture_process/lecture.py` explains how phone times map onto it).

Recognition: faster-whisper `large-v3` on the GPU (RTX 5070 Laptop: model load ~4 s,
~20-45x realtime; the first run downloads ~3 GB to the Hugging Face cache). Russian, with a
prompt of the subject's terms from `glossary/<Subject>.txt` (edit freely). No VAD: Silero
dropped real quiet speech on the test recordings, so the whole audio is recognized and
`asr.clean` drops what Whisper invents on silence (subtitle credits, echoes of the prompt,
repetition loops); pieces the model doubts are kept and marked «(?)». Raw model output is cached per audio file in
`<work>/<folder>/asr/`, so changing the filters or re-running after a sync is instant.

## Notes (Claude Code)

The notes are written by Claude Code with the skill in `claude_skill/konspekt/SKILL.md`
(install or update it with `powershell -File install_skill.ps1`, which copies it to
`~/.claude/skills/konspekt`). In Claude Code: `/konspekt` or «конспект по последней
лекции» / «по всем новым». The skill reads `notes_input/` (text and every photo), writes
`<vault>/<subject>/<date> <title>.md` and copies the photos it embeds to
`<subject>/attachments/<date> <title>/`. Vault: `~/Documents/Конспекты` (`vault` in the
config); open the folder as a vault in Obsidian.

`python -m lecture_process status` lists processed lectures and their notes: the note's
front matter keeps `источник` (lecture folder) and `версия_входа` (`input_version` of the
notes input), so a lecture changed by a later sync shows as «конспект устарел». An
existing note is never overwritten; the skill writes an «(обновлено …)» copy next to it.

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
