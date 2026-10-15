"""Stage 8 (markup) acceptance scenarios on the connected phone.

Usage: python tools/stage8_check.py <scenario>

  markup   one batch of 3 frames: batch type board, frame 2 set to slide and frame 3 flagged
           redraw in the full-screen view; a saved photo retyped and flagged; the star (one
           kept, one undone from the snackbar); a note whose time is when its field opened; a
           note draft that survives a force-stop
  archive  the lecture from `markup` in the archive: retype, redraw off, withdraw the mark
           and the note (later edits after lecture_end)
  part     "next part" on the home screen: same subject, "ч.2" title, part 2 in the log
  all      markup, archive, part

Each scenario ends with check_lecture on the pulled folder.
"""
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import phone  # noqa: E402
from stage1_check import finish, new_lecture, step, text_of, validate  # noqa: E402
from stage3_check import shoot, wait_enabled, wait_text  # noqa: E402

ROOT = "/sdcard/Lectures"
STATE = phone.ROOT / "pulled" / "stage8_folder.txt"


def events(folder):
    out = phone.shell(f"cat '{ROOT}/{folder}/events.jsonl'")
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def wait_event(folder, predicate, what, timeout=10):
    deadline = time.time() + timeout
    while True:
        found = [e for e in events(folder) if predicate(e)]
        if found:
            return found
        assert time.time() < deadline, f"no {what} in the log"
        time.sleep(0.5)


def check_pulled(folder):
    dest = phone.ROOT / "pulled" / "stage8"
    lectures = phone.pull(dest)
    report, _ = validate(lectures / folder)
    assert not report.errors, report.errors


def phone_mono():
    """SystemClock.elapsedRealtime() of the phone, ms (the `mono` of the log)."""
    return int(float(phone.shell("cat /proc/uptime").split()[0]) * 1000)


def type_note(text):
    phone.wait("note_input")
    phone.tap("note_input")
    phone.shell(f"input text {text}")


def scenario_markup():
    folder = new_lecture()

    step("batch of 3: all kept, board; frame 2 → slide, frame 3 → redraw")
    shoot(3)
    wait_text("frame_count", "Кадров: 3")
    wait_enabled("continue_button")
    phone.tap("continue_button")
    phone.wait("frame_0")
    phone.tap("frame_check_0")
    phone.tap("frame_check_1")
    phone.tap("type_board")
    phone.tap("frame_1")
    phone.tap("viewer_type_slide")
    phone.tap("viewer_close")
    phone.tap("frame_2")
    phone.tap("viewer_redraw")
    phone.tap("viewer_close")
    phone.tap("save_photos")
    phone.wait("audio_button")
    photos = wait_event(folder, lambda e: e["e"] == "photo", "photo")
    time.sleep(1)
    photos = [e for e in events(folder) if e["e"] == "photo"]
    assert [p["type"] for p in photos] == ["board", "slide", "board"], photos
    redraws = [e for e in events(folder) if e["e"] == "redraw"]
    assert [(r["file"], r["on"]) for r in redraws] == [(photos[2]["file"], True)], redraws

    step("saved photo 1: retype to notebook, redraw on")
    phone.tap("photo_0")
    phone.tap("viewer_type_notebook")
    phone.tap("viewer_redraw")
    time.sleep(1)
    phone.tap("photo_viewer_close")
    evs = events(folder)
    assert {"e": "retype", "file": photos[0]["file"], "type": "notebook"}.items() <= evs[-2].items(), evs[-2:]
    assert {"e": "redraw", "file": photos[0]["file"], "on": True}.items() <= evs[-1].items(), evs[-1:]

    step("star twice, the second one undone from the snackbar")
    phone.tap("mark_button")
    phone.wait("snackbar")
    phone.tap("mark_button")
    time.sleep(1)
    phone.tap("snackbar_undo")
    unmark = wait_event(folder, lambda e: e["e"] == "unmark", "unmark")
    marks = [e for e in events(folder) if e["e"] == "mark"]
    assert len(marks) == 2 and unmark[0]["mark"] == marks[1]["seq"], (marks, unmark)
    phone.wait("mark_0")
    assert phone.find("mark_1") is None, "the undone mark is not shown"

    step("note: typed slowly, its time is when the field opened")
    phone.tap("note_button")
    type_note("first%snote")
    time.sleep(4)
    phone.tap("note_save")
    note = wait_event(folder, lambda e: e["e"] == "note", "note")[0]
    assert note["text"] == "first note", note
    saved_mono = phone_mono()
    assert saved_mono - note["mono"] >= 4000, f"note stamped {saved_mono - note['mono']} ms before saving"
    phone.wait("note_0")

    step("note draft survives a force-stop")
    phone.tap("note_button")
    type_note("draft%snote")
    time.sleep(1)
    phone.stop()
    phone.launch()
    phone.tap("resume_0")
    phone.wait("note_input")
    assert "draft note" in (phone.find("note_input").get("text") or ""), phone.find("note_input").get("text")
    phone.tap("note_save")
    notes = wait_event(folder, lambda e: e["e"] == "note" and e["text"] == "draft note", "draft note")
    recovered = next(e for e in events(folder) if e["e"] == "recovered")
    assert notes[0]["mono"] < recovered["mono"], "draft keeps the moment its field opened (before the crash)"

    finish()
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(folder, encoding="utf-8")
    check_pulled(folder)
    print("MARKUP PASSED")


def scenario_archive():
    folder = STATE.read_text(encoding="utf-8").strip()
    phone.stop()
    phone.launch()
    phone.tap("archive_open")
    phone.wait("archive_sort")
    phone.tap(f"archive_item_{folder}")
    phone.wait("lecture_sync")
    before = events(folder)
    photos = [e for e in before if e["e"] == "photo"]

    step("finished lecture: photo 2 back to board, photo 1 redraw off")
    phone.tap("photo_1")
    phone.tap("viewer_type_board")
    time.sleep(1)
    phone.tap("photo_viewer_close")
    phone.tap("photo_0")
    phone.tap("viewer_redraw")
    time.sleep(1)
    phone.tap("photo_viewer_close")

    step("withdraw the mark and the first note")
    phone.tap("mark_0")
    phone.tap("mark_remove")
    time.sleep(1)
    phone.tap("note_0")
    assert text_of("note_text") == "first note"
    phone.tap("note_remove")
    time.sleep(1)
    assert phone.find("mark_0") is None
    assert text_of("note_0").endswith("draft note"), text_of("note_0")

    added = events(folder)[len(before):]
    first_mark = next(e for e in before if e["e"] == "mark")
    first_note = next(e for e in before if e["e"] == "note")
    expected = [
        {"e": "retype", "file": photos[1]["file"], "type": "board"},
        {"e": "redraw", "file": photos[0]["file"], "on": False},
        {"e": "unmark", "mark": first_mark["seq"]},
        {"e": "note_removed", "note": first_note["seq"]},
    ]
    assert len(added) == len(expected), added
    for got, want in zip(added, expected):
        assert want.items() <= got.items(), (got, want)
    check_pulled(folder)
    print("ARCHIVE PASSED")


def scenario_part():
    folder = new_lecture()
    title = text_of("lecture_title")
    finish()
    step("next part from the home screen")
    phone.wait("next_part")
    assert text_of("next_part_start") == "Часть 2", text_of("next_part_start")
    before = set(phone.ls().split())
    phone.tap("next_part_start")
    phone.wait("lecture_subject")
    assert text_of("lecture_title") == f"{title} ч.2", text_of("lecture_title")
    new = sorted(set(phone.ls().split()) - before)
    assert len(new) == 1 and new[0].endswith("-ч.2"), new
    start = events(new[0])[0]
    assert start["e"] == "lecture_start" and start["part"] == 2, start
    finish()
    phone.wait("next_part")
    assert text_of("next_part_start") == "Часть 3"
    check_pulled(new[0])
    print(f"PART PASSED ({folder} → {new[0]})")


def main(argv):
    scenarios = {"markup": scenario_markup, "archive": scenario_archive, "part": scenario_part}
    names = list(scenarios) if argv[1:] == ["all"] else argv[1:]
    if not names or any(n not in scenarios for n in names):
        print(__doc__)
        return 2
    for name in names:
        scenarios[name]()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
