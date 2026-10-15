**English** | [Русский](README.ru.md)

# Lecture System

Records university lectures on the phone and turns them into notes in Obsidian.

```
phone (android/)                      laptop (desktop/)                       Obsidian vault
record audio, photos of the  ─Wi-Fi─▶ sync → speech recognition (GPU) → ─────▶ notes written by
board / notebook / slides,            notes input (text + photos +             Claude Code
notes, "important" marks              course slides)                           (/konspekt skill)
```

The app's interface and the notes are in Russian; code, comments and commit messages are
in English.

Author: [arkaboy00](https://github.com/arkaboy00)

## Parts

| Folder | What | Stack |
|---|---|---|
| [`android/`](android/README.md) | Lecture Capture, the phone app: records a "lecture draft" into a folder with an append-only event log | Kotlin, Jetpack Compose, CameraX |
| [`desktop/`](desktop/README.md) | Laptop side: Wi-Fi sync with the phone, speech recognition, input for the notes, the Obsidian vault, the Claude Code skill | Python 3.14, faster-whisper, PyMuPDF, tkinter |
| [`docs/`](docs) | [SPEC.md](docs/SPEC.md): requirements and the lecture folder format (the contract between the two parts); [IDEAS.md](docs/IDEAS.md): ideas and proposals; [ROADMAP.md](docs/ROADMAP.md): what is done and what comes next | Russian |

## How a lecture goes

1. **Phone.** Start a lecture (subject, title), record audio, take photos of the board,
   notebook and slides. Mark important moments with the star and type notes when needed.
   «Сохранить и выйти» finishes the lecture; after a break, «Часть 2» continues it.
2. **Laptop, at home.** Desktop icon «Лекции» → «Синхронизировать» (the phone shows
   «Синхр.» → «Начать синхронизацию»), then «Обработать»: speech recognition on the GPU,
   about 15 minutes for two hours of audio.
3. **Notes.** In Claude Code: `/konspekt` (or «конспект по последней лекции»). It reads
   the photos, re-recognizes the speech with the lecture's own terms, writes the note into
   the vault `~/Documents/Конспекты`, and lists what to review for tests in
   `<subject>/Контроль/`.

## Principles

- Raw data is never edited: the phone only appends events, the laptop writes into its own
  work folder.
- Losing lecture speech is worse than keeping noise: doubtful pieces are kept and marked.
- Nothing runs in the background: the sync service and the laptop window start on demand
  and exit when done.
- Every stage starts with a plan and goes ahead only after the author's «ок».
