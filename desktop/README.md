# Lecture Desktop

Laptop side of [Lecture Capture](../lecture_capture). For now: pulls lecture folders from the
phone over the home Wi-Fi. Later: speech recognition and note assembly will live here too.

Python 3 standard library only (tkinter window). Nothing runs in the background: the window
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

Against a fake phone (stdlib HTTP server): pairing, resume after a dropped connection,
skipping unchanged files, grown files, a folder renamed on the phone, lost pairing, phone
missing during retries, cancel.
