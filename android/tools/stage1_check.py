"""Stage 1 (audio) acceptance scenarios on the connected phone.

Usage: python tools/stage1_check.py <scenario> [args]

  segments          record 20 s, pause 5 s, record 15 s: two segments with correct events
  forcestop         record 30 s, am force-stop: the dangling file must open; resume closes it
  kill [fg|bg]      record 20 s, SIGKILL the process (like the system does) with the app on
                    screen or in the background: does the START_STICKY service come back
                    and keep recording?
  long [minutes]    one segment for N minutes (default 10) while the app is swiped from
                    recents, the screen is off, and the stock camera is open
  manual [seconds]  record N seconds (default 60) for a manual check: speak, or call the phone

Each scenario creates its own lecture, finishes it, pulls the folder and runs check_lecture.
"""
import json
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_lecture  # noqa: E402
import phone  # noqa: E402

FFPROBE = check_lecture.find_ffprobe()


def step(msg):
    print(f"-- [{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def text_of(tag):
    node = phone.wait(tag)
    return (node.get("text") or node.get("content-desc")
            or " ".join(n.get("text") or n.get("content-desc") or "" for n in node.iter("node")).strip())


def close_unfinished():
    """Finishes lectures left open by earlier (failed) runs. Only for the test phone."""
    for _ in range(10):
        phone.wait("start_button")
        if phone.find("resume_0") is None:
            return
        phone.tap("resume_0")
        phone.wait("lecture_subject")
        step(f"closing leftover {text_of('lecture_subject')} {text_of('lecture_title')}")
        finish()


def new_lecture():
    """Starts a lecture from the home screen; returns its folder name on the phone."""
    phone.stop()
    phone.launch()
    close_unfinished()
    before = set(phone.ls().split())
    phone.tap("subject_chip_1")  # Термех: keeps test lectures apart from the first subject
    phone.tap("start_button")
    phone.wait("lecture_subject")
    new = sorted(set(phone.ls().split()) - before)
    assert len(new) == 1, new
    step(f"lecture {new[0]}")
    return new[0]


def start_recording():
    phone.tap("audio_button")
    deadline = time.time() + 10
    while not text_of("recording_timer").startswith("Идёт запись"):
        assert time.time() < deadline, f"recording did not start: {text_of('recording_timer')!r}"
        time.sleep(0.5)
    step(f"recording: {text_of('recording_timer')}")


def stop_recording():
    phone.tap("audio_button")
    phone.tap("confirm_stop")
    deadline = time.time() + 10
    while text_of("recording_timer") != "Запись не идёт":
        assert time.time() < deadline, "recording did not stop"
        time.sleep(0.5)
    step("recording stopped")


def finish(kind="lecture"):
    phone.tap("menu_button")
    phone.tap("menu_save_exit")
    phone.tap(f"kind_{kind}")
    phone.tap("confirm_finish")
    phone.wait("start_button")
    step("lecture finished")


def ensure_in_app():
    """Brings the app back to the front (after home, camera, screen off)."""
    if not phone.screen_on():
        phone.key("KEYCODE_WAKEUP")
        time.sleep(1)
    if phone.keyguard_showing():
        phone.shell("wm dismiss-keyguard", check=False)
        time.sleep(1)
        if phone.keyguard_showing():
            print(">>> The phone is locked with a PIN/pattern: unlock it by hand (waiting up to 5 min)", flush=True)
            deadline = time.time() + 300
            while phone.keyguard_showing():
                assert time.time() < deadline, "phone stayed locked"
                time.sleep(2)
    phone.launch()
    time.sleep(1.5)


def pull(folder, fresh=True):
    dest = phone.ROOT / "pulled" / "stage1"
    if fresh:
        shutil.rmtree(dest, ignore_errors=True)
    lectures = phone.pull(dest)
    path = lectures / folder
    if not path.exists():  # renamed at lecture_end
        candidates = [p for p in lectures.iterdir() if p.name.startswith(folder[:15])]
        path = max(candidates, key=lambda p: p.stat().st_mtime)
    return path


def events_of(path):
    out = []
    for line in (path / "events.jsonl").read_text(encoding="utf-8").splitlines():
        try:
            out.append(json.loads(line))
        except ValueError:
            out.append({"e": "<torn>", "raw": line})
    return out


def validate(path):
    report = check_lecture.check_folder(path, FFPROBE)
    report.print()
    evs = events_of(path)
    for ev in evs:
        if ev["e"].startswith("audio") or ev["e"] in ("recovered", "<torn>"):
            print("   ", {k: v for k, v in ev.items() if k not in ("ts", "boot")})
    return report, evs


def segment_durations(evs):
    """{file: (logged_seconds, dur_ms)} from audio_start/audio_stop pairs."""
    starts, out = {}, {}
    for ev in evs:
        if ev["e"] == "audio_start":
            starts[ev["file"]] = ev
        elif ev["e"] == "audio_stop" and ev["file"] in starts:
            s = starts[ev["file"]]
            out[ev["file"]] = ((ev["mono"] - s["mono"]) / 1000, ev.get("dur_ms"), ev["reason"])
    return out


# --- scenarios ------------------------------------------------------------------------------

def scenario_segments():
    folder = new_lecture()
    start_recording()
    time.sleep(20)
    stop_recording()
    time.sleep(5)
    start_recording()
    time.sleep(15)
    stop_recording()
    finish()
    report, evs = validate(pull(folder))
    durs = segment_durations(evs)
    print("    segments:", durs)
    assert list(durs) == ["audio/001.ogg", "audio/002.ogg"], durs
    # UI dumps add seconds around every tap, so only lower bounds hold for wall time; the real
    # check is that the file length matches the log (audio_start is written just before the
    # recorder starts, so the file is a little shorter).
    for (logged, dur_ms, reason), expected in zip(durs.values(), (20, 15)):
        assert reason == "user"
        assert logged >= expected, (logged, expected)
        assert dur_ms and 0 <= logged - dur_ms / 1000 < 2, (dur_ms, logged)
    assert not report.errors
    print("SEGMENTS PASSED")


def scenario_forcestop():
    folder = new_lecture()
    start_recording()
    time.sleep(30)
    phone.stop()
    step("force-stopped")
    assert phone.pid() is None
    # The raw dangling file, before the app touches anything.
    raw = pull(folder)
    dangling = raw / "audio" / "001.ogg"
    duration, detail = check_lecture.probe_audio(FFPROBE, dangling)
    print(f"    dangling file after force-stop: {duration}s, {detail}, {dangling.stat().st_size} bytes")
    print(f"    volume: {check_lecture.volume(FFPROBE, dangling)}")
    phone.launch()
    phone.tap("resume_0")
    phone.wait("lecture_subject")
    finish()
    report, evs = validate(pull(folder))
    durs = segment_durations(evs)
    print("    segments:", durs)
    logged, dur_ms, reason = durs["audio/001.ogg"]
    assert reason == "recovered" and dur_ms is not None and dur_ms > 20_000, durs
    assert duration is not None and duration > 20, "file must open after force-stop"
    assert not report.errors
    print("FORCESTOP PASSED")


def scenario_kill(where="fg"):
    folder = new_lecture()
    start_recording()
    time.sleep(20)
    if where == "bg":
        phone.key("KEYCODE_HOME")
        time.sleep(3)
        step("app in background")
    phone.clear_log()
    old = phone.kill_process()
    step(f"killed pid {old}")
    for _ in range(60):  # sticky restarts can take a while
        time.sleep(2)
        if phone.pid() and phone.pid() != old:
            break
    new = phone.pid()
    step(f"process after kill: {new}")
    time.sleep(15)
    print(phone.log(400))
    ensure_in_app()
    timer = text_of("recording_timer") if phone.find("recording_timer") is not None else "<home screen>"
    step(f"screen after kill: {timer}")
    if phone.find("resume_0") is not None:
        phone.tap("resume_0")
        phone.wait("lecture_subject")
    if text_of("recording_timer") != "Запись не идёт":
        time.sleep(5)
        stop_recording()
    finish()
    report, evs = validate(pull(folder))
    print("    segments:", segment_durations(evs))
    print("KILL SCENARIO DONE (an experiment: read the log above)")


def swipe_from_recents():
    phone.key("KEYCODE_APP_SWITCH")
    time.sleep(2)
    root = phone.dump()
    card = None
    for node in phone.nodes(root):
        label = (node.get("text") or "") + (node.get("content-desc") or "")
        if "Конспект" in label:
            card = node
            break
    if card is None:
        print("    recents: app card not found; visible:", [n.get("text") or n.get("content-desc") for n in phone.nodes(root) if n.get("text") or n.get("content-desc")])
        phone.key("KEYCODE_HOME")
        return False
    x1, y1, x2, y2 = phone._bounds(card)
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    phone.swipe(cx, cy, cx - 900, cy, 250)  # MIUI: swipe a card sideways to remove it
    time.sleep(1.5)
    still = any("Конспект" in ((n.get("text") or "") + (n.get("content-desc") or "")) for n in phone.nodes())
    phone.key("KEYCODE_HOME")
    return not still


def scenario_long(minutes=10.0):
    total = minutes * 60
    folder = new_lecture()
    start_recording()
    t0 = time.time()
    pid0 = phone.pid()

    step("home + swipe the app out of recents")
    phone.key("KEYCODE_HOME")
    time.sleep(2)
    removed = swipe_from_recents()
    step(f"removed from recents: {removed}; pid {pid0} -> {phone.pid()}")
    time.sleep(max(0, total * 0.3 - (time.time() - t0)))

    step("screen off")
    phone.key("KEYCODE_SLEEP")
    time.sleep(max(0, total * 0.6 - (time.time() - t0)))
    phone.key("KEYCODE_WAKEUP")
    time.sleep(1)
    step(f"screen on; pid {phone.pid()}")

    step("stock camera open")
    phone.shell("am start -a android.media.action.IMAGE_CAPTURE", check=False)
    time.sleep(max(0, total * 0.85 - (time.time() - t0)))
    phone.key("KEYCODE_HOME")
    step(f"camera closed; pid {phone.pid()}")
    time.sleep(max(0, total - (time.time() - t0)))

    ensure_in_app()
    wall = time.time() - t0
    stop_recording()
    finish()
    report, evs = validate(pull(folder))
    durs = segment_durations(evs)
    print(f"    wall clock {wall:.0f}s, segments: {durs}")
    assert len(durs) == 1, "the recording must be one uninterrupted segment"
    logged, dur_ms, reason = next(iter(durs.values()))
    assert reason == "user"
    # wall is measured between UI dumps, so it is a lower bound for the segment length;
    # the file must match the log.
    assert logged >= wall, (logged, wall)
    assert dur_ms and 0 <= logged - dur_ms / 1000 < 2, (dur_ms, logged)
    assert not report.errors
    print("LONG PASSED")


def scenario_manual(seconds=60.0):
    folder = new_lecture()
    start_recording()
    print(f">>> Recording {seconds:.0f} s: talk near the phone, or call it now.", flush=True)
    time.sleep(seconds)
    ensure_in_app()
    if text_of("recording_timer") != "Запись не идёт":
        stop_recording()
    finish()
    validate(pull(folder))
    print("MANUAL DONE: listen to pulled/stage1/Lectures/" + folder)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    scenarios = {
        "segments": scenario_segments,
        "forcestop": scenario_forcestop,
        "kill": scenario_kill,  # kill [fg|bg]
        "long": lambda *a: scenario_long(*map(float, a)),
        "manual": lambda *a: scenario_manual(*map(float, a)),
    }
    scenarios[argv[1]](*argv[2:])
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main(sys.argv))
