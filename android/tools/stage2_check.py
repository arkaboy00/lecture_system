"""Stage 2 (main screen) acceptance scenarios on the connected phone.

Usage: python tools/stage2_check.py <scenario>

  grid     two audio segments + 3 photos; obsolete marks from the grid and the full-screen
           viewer, undo via snackbar, the segment strip, renaming the lecture
  delete   delete an unfinished lecture from the home screen (cancel first, then confirm)
"""
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import phone  # noqa: E402
from stage1_check import finish, new_lecture, pull, start_recording, step, stop_recording, text_of, validate  # noqa: E402
from stage3_check import shoot, wait_enabled, wait_text  # noqa: E402


def wait_status(fragment, timeout=10):
    deadline = time.time() + timeout
    while fragment not in (value := text_of("status_line")):
        assert time.time() < deadline, f"status {value!r} lacks {fragment!r}"
        time.sleep(0.5)


def replace_text(tag, text):
    """ASCII only: `input text` cannot type Cyrillic."""
    phone.tap(tag)
    phone.key("KEYCODE_MOVE_END")
    phone.shell("input keyevent " + " ".join(["KEYCODE_DEL"] * 40))
    phone.shell(f"input text {text}")


def scenario_grid():
    folder = new_lecture()
    step("two short segments")
    start_recording()
    time.sleep(3)
    stop_recording()
    time.sleep(3)
    start_recording()

    step("three photos, all kept")
    shoot(3)
    wait_text("frame_count", "Кадров: 3")
    wait_enabled("continue_button")
    phone.tap("continue_button")
    phone.tap("frame_check_0")
    phone.tap("frame_check_1")
    phone.tap("save_photos")
    phone.wait("photo_2", 15)
    stop_recording()

    step("mark photo 1 obsolete, then unmark it")
    phone.tap("photo_check_1")
    phone.wait("snackbar")
    wait_status("неакт. 1")
    # Checked = relevant: marking obsolete removes the check.
    assert phone.find("photo_check_1").get("content-desc") == "Не выбран"
    assert phone.find("photo_check_0").get("content-desc") == "Выбран"
    phone.tap("photo_check_1")
    wait_status("фото 3")
    assert "неакт" not in text_of("status_line")

    step("mark photo 0 and undo from the snackbar")
    phone.tap("photo_check_0")
    time.sleep(1)  # the snackbar slides in: bounds from a dump taken mid-animation miss the button
    phone.tap("snackbar_undo")
    time.sleep(1)
    assert "неакт" not in text_of("status_line"), text_of("status_line")

    step("mark photo 2 from the full-screen viewer")
    phone.tap("photo_2")
    phone.wait("photo_viewer")
    phone.tap("photo_viewer_check")
    time.sleep(1)
    phone.tap("photo_viewer_close")
    wait_status("неакт. 1")

    step("segment strip")
    phone.tap("segments_toggle")
    for tag in ("segment_0", "gap_0", "segment_1"):
        phone.wait(tag)
    print("    strip:", text_of("segments_toggle"))
    phone.tap("segments_toggle")

    step("rename the title")
    phone.tap("menu_button")
    phone.tap("menu_rename")
    replace_text("rename_title", "Renamed")
    phone.tap("confirm_rename")
    wait_text("lecture_title", "Renamed")

    finish()
    path = pull(folder)
    report, evs = validate(path)
    marks = [(e["e"], e["file"]) for e in evs if e["e"] in ("obsolete", "restored")]
    print("    marks:", marks)
    photos = [e["file"] for e in evs if e["e"] == "photo"]
    assert marks == [
        ("obsolete", photos[1]), ("restored", photos[1]),
        ("obsolete", photos[0]), ("restored", photos[0]),
        ("obsolete", photos[2]),
    ], marks
    assert all((path / p).exists() for p in photos), "obsolete photos are not deleted"
    meta = json.loads((path / "lecture.json").read_text(encoding="utf-8"))
    assert meta["title"] == "Renamed" and path.name.endswith("_Renamed"), (meta, path.name)
    assert any(e["e"] == "meta" and e.get("title") == "Renamed" for e in evs)
    assert not report.errors
    print("GRID PASSED")


def scenario_delete():
    folder = new_lecture()
    start_recording()
    time.sleep(5)
    stop_recording()
    phone.stop()
    phone.launch()
    phone.wait("unfinished_0")
    print("    card:", text_of("unfinished_0"))

    step("delete, then cancel")
    phone.tap("delete_0")
    phone.wait("confirm_delete")
    print("    dialog:", " | ".join(t for t in phone.texts() if t))
    phone.tap("cancel_delete")
    time.sleep(1)
    assert folder in phone.ls().split()

    step("delete and confirm")
    phone.tap("delete_0")
    phone.tap("confirm_delete")
    deadline = time.time() + 10
    while folder in phone.ls().split():
        assert time.time() < deadline, "folder still on the phone"
        time.sleep(0.5)
    assert phone.find("unfinished_0") is None
    print("DELETE PASSED")


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    {"grid": scenario_grid, "delete": scenario_delete}[argv[1]]()
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main(sys.argv))
