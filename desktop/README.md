**English** | [Русский](README.ru.md)

# Lecture Desktop

Laptop side of the [Lecture System](../README.md): pulls lecture folders from the phone
([`android/`](../android/README.md)) over the home Wi-Fi (`lecture_sync`), processes them
(`lecture_process`: speech recognition, input for the notes, the Obsidian vault), and holds
the Claude Code skill that writes the notes (`claude_skill/konspekt`).

Nothing runs in the background: the window is the process, and the phone-side sync service
stops itself when this app reports "done".

Author: [arkaboy00](https://github.com/arkaboy00)

## Use

1. On the phone: Lecture Capture → «Синхр.» → «Начать синхронизацию».
2. On the laptop: desktop icon «Лекции» → «Синхронизировать», then «Обработать».
   The first time, enter the 6-digit code shown on the phone.
3. In Claude Code: `/konspekt` (see "Notes" below).

Lectures land in `~/Documents/Lectures/<folder>` (change `inbox` in
`%APPDATA%\LectureDesktop\config.json`). Only lectures with `ended` in `lecture.json` are
complete; a lecture still being recorded is pulled as far as it goes.

Console mode (used by tests and for debugging):

```
python -m lecture_sync --cli [--code 123456] [--inbox DIR] [--config FILE]
```

## Setup (from this folder)

```
py -3.14 -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt
powershell -File install_shortcut.ps1
powershell -File install_skill.ps1
```

The shortcut «Лекции» runs `LectureSync.pyw` with the venv's `pythonw`; the skill script
copies `claude_skill\konspekt` to `~/.claude/skills/konspekt` (run it again after editing
the skill). Sync itself needs only the Python standard library.

## Processing

```
.venv\Scripts\python -m lecture_process [process|transcribe|status [--json]|index] [folder ...] [--all]
```

`process` (default) = `transcribe` + the course slides + the notes input; the window's
«Обработать» does the same. Without folders: every finished lecture in the inbox. Output
goes to `~/Documents/Lectures_work/<folder>/` (`work` in the config); the lecture folder
itself is never written to:

- `transcript.json` — machine-readable result: names, kind, part, audio segments, photos
  (type, obsolete and redraw flags), notes, marks, and recognized pieces with word timings,
  all in seconds since `lecture_start` on one timeline (`lecture_process/lecture.py`
  explains how phone times map onto it);
- `notes_input/` — what Claude Code reads to write the notes, rebuilt on every run:
  `transcript.md` (speech in paragraphs with clock times, `[ФОТО n · time · доска]` markers
  where the shutter was pressed, the user's `[ЗАМЕТКА]` notes and `[ОТМЕЧЕНО КНОПКОЙ]`
  marks from the phone, breaks in the recording), `photos/` (relevant photos only, upright,
  ≤ 2000 px: board formulas stay readable), `info.json` (names, kind, part, date, photo list
  with type and the redraw flag), `slides.md` (matched course slides, if any).

Recognition: faster-whisper `large-v3` on the GPU (RTX 5070 Laptop: model load ~4 s, two
hours of audio in about 15 minutes; the first run downloads ~3 GB to the Hugging Face
cache). No VAD: Silero dropped real quiet speech, so the whole audio is recognized and
`asr.clean` drops what Whisper invents on silence (subtitle credits, echoes of the prompt,
repetition loops); pieces the model doubts are kept and marked «(?)». Raw model output is
cached per audio file in `<work>/<folder>/asr/`, so changing the filters or re-running after
a sync is instant.

## Lecture terms and course materials

- **Hotwords** go into every 30-second window of Whisper (an initial prompt reaches only the
  first one). First `<work>/<folder>/terms.txt`: this lecture's own terms in the order they
  were written (notebook photos first), up to ~300 characters, written by the konspekt skill
  from the photos; then the subject glossary `glossary/<Subject>.txt`, 600 characters in
  total. Changing them re-recognizes the lecture on the next `process` (cache key). On
  2026-10-15 (Материаловедение, 2 h) this raised the recognized text from 54 to 85 thousand
  characters and cut doubtful pieces from 86 to 51; sorting the terms by rarity made it worse.
- **Course PDFs** in `<vault>/<subject>/Материалы/` (`lecture_process/slides.py`): indexed
  once (page texts + outline, cached in `<work>/_sources/`), matched to a lecture by its
  distinctive words in 20-minute windows plus `terms.txt`; the matched sections' text goes
  to `notes_input/slides.md` with `[[file.pdf#page=N]]` links. No model involved.

## Notes (Claude Code)

The notes are written by Claude Code with the skill in `claude_skill/konspekt/SKILL.md`.
In Claude Code: `/konspekt` or «конспект по последней лекции» / «по всем новым». The skill
reads every photo once and writes `terms.txt`, runs a second recognition pass, reads
`notes_input/`, writes `<vault>/<subject>/<date> <title>.md` (summary, sections, unclear
places, the full text folded) with photos and slide renders in folded callouts, and adds
what to review for tests to `<subject>/Контроль/РК-1.md`, `РК-2.md`, `Экзамен.md`,
`Разобрать.md`. Vault: `~/Documents/Конспекты` (`vault` in the config); open the folder as a
vault in Obsidian.

Vault layout (`lecture_process/vault.py`): `Главная.md` (subjects, latest notes, lectures
waiting for notes) and `<subject>/<subject>.md` (the subject's notes, Контроль files, course
materials) are generated from the notes' front matter by `python -m lecture_process index`,
which also runs after «Обработать» and after every note the skill writes; do not edit them.
Obsidian settings (`.obsidian/app.json`: attachments next to the note, links follow renames)
are written once when missing.

`python -m lecture_process status` lists processed lectures and their notes: the note's
front matter keeps `источник` (lecture folder) and `версия_входа` (`input_version` of the
notes input), so a lecture changed by a later sync shows as «конспект устарел». An
existing note is never overwritten; the skill writes an «(обновлено …)» copy next to it.

## Protocol

Defined by the phone (`android/core/.../sync/SyncProtocol.kt`): UDP discovery on 47811,
HTTP on 47812; `/hello`, `/pair`, `/manifest`, `/file` (Range), `/ack`, `/done`. Downloads
go to `<file>.part`, resume with `Range`, are verified by SHA-256 and then renamed. Network
errors are retried for about a minute with rediscovery, so a phone reconnecting to Wi-Fi
does not break the run.

## Tests

```
.venv\Scripts\python -m unittest discover -s tests
```

- `test_process.py`: the timeline (mono vs wall clock, reboots, later edits, torn lines),
  stage 8 events (photo type and redraw, notes, marks, parts), the cleanup filters, note
  status and the vault pages.
- `test_slides.py`: PDF index and section matching on a generated PDF, the hotword order
  and budget.
- `test_client.py`, against a fake phone (stdlib HTTP server): pairing, resume after a
  dropped connection, skipping unchanged files, grown files, a folder renamed on the phone,
  lost pairing, phone missing during retries, cancel.
