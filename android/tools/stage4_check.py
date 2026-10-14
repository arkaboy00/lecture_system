"""Stage 4 acceptance scenarios on the connected phone.

Usage: python tools/stage4_check.py <scenario>

  camera   zoom presets and slider, tap-to-focus brings the brightness slider, a zoomed shot
           keeps full resolution, zoom resets when the camera is reopened
           (pinch needs two fingers: adb cannot do it, check by hand)
  recover  force-stop while recording with unsorted frames and crash leftovers on disk;
           relaunch: lecture offered, audio closed as recovered, frames intact, leftovers gone
  battery  warning on the home screen, settings status flips after the battery exemption,
           vendor shortcut opens the system screen
"""
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import phone  # noqa: E402
from stage1_check import finish, new_lecture, pull, start_recording, step, text_of, validate  # noqa: E402
from stage3_check import jpeg_size, pending_on_phone, shoot, wait_enabled, wait_text  # noqa: E402


def tap_fraction(tag, fx, fy=0.5):
    x1, y1, x2, y2 = phone._bounds(phone.wait(tag))
    phone.tap_xy(int(x1 + (x2 - x1) * fx), int(y1 + (y2 - y1) * fy))


def zoom_value():
    return float(text_of("zoom_value").rstrip("×").replace(",", "."))


def scenario_camera():
    folder = new_lecture()
    phone.tap("photo_button")
    phone.wait("zoom_slider", 15)
    assert zoom_value() == 1.0, text_of("zoom_value")

    step("preset 2×")
    phone.tap("zoom_preset_2.0")
    time.sleep(0.5)
    assert zoom_value() == 2.0, text_of("zoom_value")

    step("slider near the right end")
    tap_fraction("zoom_slider", 0.85)
    time.sleep(0.5)
    print("    zoom after slider:", text_of("zoom_value"))
    assert zoom_value() > 4.0

    step("tap to focus → brightness slider")
    assert phone.find("exposure_slider") is None
    # The preview is an Android View inside Compose: its testTag is not in the dump, so tap
    # the middle of the content area.
    tap_fraction("android:id/content", 0.5, 0.4)
    phone.wait("exposure_slider")
    tap_fraction("exposure_slider", 0.8)
    time.sleep(0.5)
    print("    exposure:", text_of("exposure_value"))
    assert text_of("exposure_value").startswith("+")

    step("zoomed shot")
    shoot(1)
    wait_enabled("continue_button")
    phone.tap("continue_button")
    phone.tap("save_photos")
    phone.wait("audio_button")

    step("reopen camera: zoom back to 1×")
    phone.tap("photo_button")
    phone.wait("zoom_slider", 15)
    assert zoom_value() == 1.0, text_of("zoom_value")
    assert phone.find("exposure_slider") is None
    phone.tap("cancel_camera")
    phone.wait("audio_button")

    finish()
    path = pull(folder)
    report, evs = validate(path)
    photo = next(e for e in evs if e["e"] == "photo")
    size = jpeg_size(path / photo["file"])
    print("    zoomed photo:", size)
    assert max(size) >= 3264, "digital zoom must not shrink the saved frame"
    log = phone.adb("logcat", "-d", "-s", "LectureCapture")
    print("   ", [l for l in log.splitlines() if "zoom" in l][-1:])
    assert not report.errors
    print("CAMERA PASSED")


def scenario_recover():
    folder = new_lecture()
    start_recording()
    shoot(2)
    wait_text("frame_count", "Кадров: 2")
    remote = f"/sdcard/Lectures/{folder}"
    phone.shell(f"echo half > {remote}/.pending/189_1_2.jpg.part; echo '{{' > {remote}/lecture.json.tmp")
    time.sleep(2)
    phone.stop()
    step(f"force-stopped; pending: {pending_on_phone(folder)}")

    phone.launch()
    phone.wait("unfinished_0")
    card = text_of("unfinished_0")
    print("    card:", card)
    assert "аудио 0 мин" not in card, "an interrupted segment must count its file length"
    phone.tap("resume_0")
    phone.wait("pending_banner")
    pending = pending_on_phone(folder)
    tmp = phone.shell(f"[ -e {remote}/lecture.json.tmp ] && echo present || echo absent")
    print(f"    after resume: pending {pending}, lecture.json.tmp {tmp}")
    assert not any(p.endswith(".part") for p in pending) and len(pending) == 2, pending
    assert tmp == "absent"

    phone.tap("pending_sort")
    phone.wait("frame_1")
    phone.tap("frame_check_0")
    phone.tap("save_photos")
    phone.wait("audio_button")
    finish()
    report, evs = validate(pull(folder))
    stop = next(e for e in evs if e["e"] == "audio_stop")
    assert stop["reason"] == "recovered" and stop["dur_ms"] > 5000, stop
    assert len([e for e in evs if e["e"] == "photo"]) == 2
    assert not report.errors
    print("RECOVER PASSED")


def focused_activity():
    out = phone.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'", check=False)
    m = re.search(r"mCurrentFocus=.*? (\S+/\S+)\}", out)
    return m.group(1) if m else out


def scenario_battery():
    pkg = phone.PACKAGE
    phone.shell(f"dumpsys deviceidle whitelist -{pkg}", check=False)
    phone.stop()
    phone.launch()
    phone.wait("start_button")
    assert phone.find("battery_warning") is not None, "warning expected while optimized"
    phone.tap("battery_warning_open")
    print("    status:", text_of("battery_status"))
    assert text_of("battery_status").startswith("Включена")

    step("vendor shortcuts")
    for tag in ("battery_autostart", "battery_vendor"):
        if phone.find(tag) is None:
            continue
        phone.tap(tag)
        time.sleep(2)
        print(f"    {tag} → {focused_activity()}")
        phone.key("KEYCODE_BACK")
        time.sleep(1)
        phone.wait("battery_status")

    step("exempt from battery optimization (as the system dialog would)")
    phone.shell(f"dumpsys deviceidle whitelist +{pkg}")
    phone.key("KEYCODE_HOME")
    time.sleep(1)
    phone.launch()
    deadline = time.time() + 10
    while not text_of("battery_status").startswith("Отключена"):
        assert time.time() < deadline, text_of("battery_status")
        time.sleep(0.5)
    phone.tap("settings_back")
    phone.wait("start_button")
    assert phone.find("battery_warning") is None
    print("BATTERY PASSED")


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    {"camera": scenario_camera, "recover": scenario_recover, "battery": scenario_battery}[argv[1]]()
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main(sys.argv))
