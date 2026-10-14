"""Stage 3 (photos) acceptance scenarios on the connected phone.

Usage: python tools/stage3_check.py <scenario>

  batch    record audio; shoot 3 frames, keep the 1st and 3rd as notebook; shoot 1 more as a
           second batch; check files, events, batch numbers, shutter times, JPEG size, and that
           audio kept recording while the camera was open
  cancel   shoot 2 frames and cancel from the camera; shoot 1 and cancel from the catalog
  crash    shoot 2 frames, force-stop in the catalog, relaunch: frames survive and can be saved
"""
import json
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_lecture  # noqa: E402
import phone  # noqa: E402
from stage1_check import (  # noqa: E402
    FFPROBE, events_of, finish, new_lecture, pull, start_recording, step, stop_recording, text_of, validate,
)


def pending_on_phone(folder):
    return phone.shell(f"ls /sdcard/Lectures/{folder}/.pending", check=False).split()


def wait_text(tag, expected, timeout=15):
    deadline = time.time() + timeout
    while (value := text_of(tag)) != expected:
        assert time.time() < deadline, f"{tag}: {value!r} != {expected!r}"
        time.sleep(0.5)


def wait_enabled(tag, timeout=15):
    deadline = time.time() + timeout
    while phone.wait(tag).get("enabled") != "true":
        assert time.time() < deadline, f"{tag} stays disabled"
        time.sleep(0.5)


def shoot(n):
    """Opens the camera (if needed) and takes n frames; returns host times of the presses."""
    if phone.find("shutter_button") is None:
        phone.tap("photo_button")
    phone.wait("shutter_button", 15)
    time.sleep(2)  # let autofocus settle
    presses = []
    for _ in range(n):
        presses.append(time.time())
        phone.tap("shutter_button")
        time.sleep(1.5)
    return presses


def jpeg_size(path):
    out = subprocess.run(
        [FFPROBE, "-v", "error", "-show_entries", "stream=width,height", "-of", "csv=p=0", str(path)],
        capture_output=True, text=True,
    ).stdout.strip()
    w, h = map(int, out.split(",")[:2])
    return w, h


def scenario_batch():
    folder = new_lecture()
    start_recording()

    step("batch 1: three frames")
    shoot(3)
    wait_text("frame_count", "Кадров: 3")
    wait_enabled("continue_button")
    phone.tap("continue_button")
    phone.wait("frame_0")
    assert phone.find("frame_check_2").get("content-desc") == "Выбран", "last frame selected by default"
    assert phone.find("frame_check_0").get("content-desc") == "Не выбран"
    phone.tap("frame_check_0")
    step("open frame 1 full screen")
    phone.tap("frame_1")
    phone.wait("viewer_check")
    time.sleep(2)
    phone.tap("viewer_close")
    phone.tap("type_notebook")
    phone.tap("save_photos")
    phone.wait("audio_button")
    step(f"saved; status: {text_of('status_line')}")

    step("batch 2: one frame, type defaults to the previous batch")
    shoot(1)
    wait_text("frame_count", "Кадров: 1")
    wait_enabled("continue_button")
    phone.tap("continue_button")
    phone.wait("frame_0")
    phone.tap("save_photos")
    phone.wait("audio_button")

    stop_recording()
    finish()
    path = pull(folder)
    report, evs = validate(path)
    photos = [e for e in evs if e["e"] == "photo"]
    for p in photos:
        w, h = jpeg_size(path / p["file"])
        print(f"    {p['file']}: batch {p['batch']} {p['type']} mono {p['mono']} {w}x{h} "
              f"{(path / p['file']).stat().st_size // 1024} KB")
    assert [p["batch"] for p in photos] == [1, 1, 2], photos
    assert all(p["type"] == "notebook" for p in photos), photos
    a_start = next(e for e in evs if e["e"] == "audio_start")
    a_stop = next(e for e in evs if e["e"] == "audio_stop")
    assert all(a_start["mono"] < p["mono"] < a_stop["mono"] for p in photos), "shot while recording"
    assert photos[0]["mono"] < photos[1]["mono"], "shutter order"
    # The photo event is written at "Save" but stamped at the shutter: it must be older than
    # the line written just before it.
    first_photo_line = evs.index(photos[0])
    assert photos[0]["seq"] == evs[first_photo_line - 1]["seq"] + 1
    assert not [e for e in evs if e["e"].startswith("audio_silenced")], "camera must not silence audio"
    assert len([e for e in evs if e["e"] == "audio_start"]) == 1, "one uninterrupted segment"
    assert not list((path / ".pending").iterdir()), "pending emptied"
    assert len(list((path / "photos").iterdir())) == 3, "unselected frame deleted"
    assert not report.errors
    print("BATCH PASSED")


def scenario_cancel():
    folder = new_lecture()
    shoot(2)
    wait_text("frame_count", "Кадров: 2")
    phone.tap("cancel_camera")
    phone.tap("confirm_cancel_batch")
    phone.wait("photo_button")
    time.sleep(1)
    assert pending_on_phone(folder) == [], pending_on_phone(folder)
    step("cancelled from the camera")

    shoot(1)
    wait_enabled("continue_button")
    phone.tap("continue_button")
    phone.tap("cancel_batch")
    phone.tap("confirm_cancel_batch")
    phone.wait("photo_button")
    time.sleep(1)
    assert pending_on_phone(folder) == [], pending_on_phone(folder)
    step("cancelled from the catalog")
    finish()
    report, evs = validate(pull(folder))
    assert not [e for e in evs if e["e"] == "photo"]
    assert not report.errors
    print("CANCEL PASSED")


def scenario_crash():
    folder = new_lecture()
    shoot(2)
    wait_enabled("continue_button")
    phone.tap("continue_button")
    phone.wait("frame_1")
    phone.stop()
    step(f"force-stopped in the catalog; pending on phone: {pending_on_phone(folder)}")
    phone.launch()
    phone.tap("resume_0")
    phone.wait("pending_banner")
    step(f"banner: {text_of('pending_banner')}")
    phone.tap("pending_sort")
    phone.wait("frame_1")
    phone.tap("frame_check_0")  # keep both
    phone.tap("save_photos")
    phone.wait("audio_button")
    finish()
    report, evs = validate(pull(folder))
    photos = [e for e in evs if e["e"] == "photo"]
    assert len(photos) == 2, photos
    assert photos[0]["mono"] < evs[[e["e"] for e in evs].index("recovered")]["mono"], "shutter time kept across the crash"
    assert not report.errors
    print("CRASH PASSED")


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    {"batch": scenario_batch, "cancel": scenario_cancel, "crash": scenario_crash}[argv[1]]()
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main(sys.argv))
