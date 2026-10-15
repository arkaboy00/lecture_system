# Lecture Capture

Android app that records a "lecture draft" on the phone: audio segments, photos of the
board and notebook, and an append-only event log. Processing (speech recognition, notes
in Obsidian) happens on a laptop and is out of scope here. The only contract between the
two is the lecture folder format, described in [docs/SPEC.md](docs/SPEC.md) (Russian).

## Layout

- `core/` — pure Kotlin, no Android: event model and JSON codec, append-only `EventLog`
  (fsync per line), replay of `events.jsonl` into `LectureState`, `lecture.json`, naming,
  `LectureStore`. Unit tests run on the JVM.
- `app/` — Compose UI. `LectureSession` is the single process-wide writer of the open lecture.
- `tools/` — PC-side scripts:
  - `phone.py` — adb helper: install, grant permissions, tap by testTag, pull lecture folders.
  - `check_lecture.py` — validates a pulled lecture folder (log format, seq, file references, ffprobe).
  - `stage0_check.py` — stage 0 acceptance scenario on the connected phone.
  - `stage1_check.py` — audio scenarios: `segments`, `forcestop`, `kill [fg|bg]`, `long [min]`, `manual [s]`.
  - `stage2_check.py` … `stage8_check.py` — acceptance scenarios of the later stages.

Debug builds contain `DebugKillReceiver` (`app/src/debug`): `adb shell am broadcast -n
com.arka.lecturecapture/.DebugKillReceiver` SIGKILLs the app like the system's process killer
does, so START_STICKY recovery can be tested. `run-as kill` cannot see app processes and
`am kill` does not touch a process with a foreground service.

## Build and test

Gradle runs on the JDK bundled with Android Studio:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :core:test :app:assembleDebug
python tools\phone.py install
python tools\phone.py grant
python tools\stage0_check.py
```

Every interactive element has a `testTag`, exposed as `resource-id` in `uiautomator dump`
(popups and dialogs need `Modifier.exposeTestTags()` on their own, they are separate windows).

## Status

- Stage 0 (skeleton): done — tested on Poco M3, Android 12, MIUI 13.
- Stage 1 (audio): done — Ogg/Opus foreground-service recording, crash recovery, sticky
  restart verified on Poco M3 (results in docs/SPEC.md §12). Call silencing not verified (no SIM).
- Stage 3 (photos): done — CameraX capture to `.pending`, batch catalog, crash-safe save
  (docs/SPEC.md §12a). `tools/stage3_check.py batch|cancel|crash`.
- Stage 2 (main screen): done — photo grid (check = relevant), viewer, undo snackbar,
  segment strip, rename, delete unfinished lecture. `tools/stage2_check.py grid|delete`.
- Stage 4: done — camera zoom/exposure/focus ring, leftover cleanup, battery settings
  screen. `tools/stage4_check.py camera|recover|battery`.
- Stage 6 (sync): done — on-demand Wi-Fi sync service on the phone, laptop client in the
  sibling `lecture_desktop` repo (docs/SPEC.md §12d).
- Stage 7 (archive): done — all lectures with sort/group/filter, later edits of finished
  lectures, double-confirmed delete with sync status, "open folder in…".
  `tools/stage7_check.py list|edit|folder|delete|resume`.
- Stage 8 (markup): done — "slide" photo type and coloured frames, per-photo type and
  redraw flag, text notes, "important" star, next part of a lecture (docs/SPEC.md §12f).
  `tools/stage8_check.py markup|archive|part`.
- Next: stage 5 — manual acceptance on the Samsung S10e (checklist in docs/SPEC.md §9);
  the plan after it is in [docs/ROADMAP.md](docs/ROADMAP.md) (Russian).
