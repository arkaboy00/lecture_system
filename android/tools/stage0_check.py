"""Stage 0 acceptance on the connected phone.

Creates a lecture through the UI, kills the app, resumes the lecture, finishes it as a
seminar, pulls the folder and validates it with check_lecture.py.
Assumes the app is installed and permissions are granted (phone.py install/grant).
"""
import json
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_lecture  # noqa: E402
import phone  # noqa: E402


def text_of(tag):
    node = phone.wait(tag)
    return node.get("text") or "".join(n.get("text") or "" for n in node.iter("node"))


def step(msg):
    print(f"-- {msg}", flush=True)


def main():
    before = set(phone.ls().split())
    phone.stop()
    phone.launch()

    step("home: pick first subject chip, title is suggested")
    phone.tap("subject_chip_0")
    time.sleep(0.5)
    subject = text_of("subject_input")
    title = text_of("title_input")
    print(f"   subject={subject!r} title={title!r}")
    assert subject and title.startswith("Лекция "), (subject, title)

    step("start lecture")
    phone.tap("start_button")
    phone.wait("lecture_subject")
    assert text_of("lecture_subject") == subject
    print(f"   status: {text_of('status_line')}")
    new = sorted(set(phone.ls().split()) - before)
    assert len(new) == 1, new
    folder = new[0]
    print(f"   folder: {folder}")

    step("force-stop while the lecture is open, relaunch")
    phone.stop()
    time.sleep(1)
    phone.launch()
    phone.wait("unfinished_0")
    phone.tap("resume_0")
    phone.wait("lecture_subject")

    step("menu → save and exit as seminar")
    phone.tap("menu_button")
    phone.tap("menu_save_exit")
    phone.tap("kind_seminar")
    phone.tap("confirm_finish")
    phone.wait("start_button")

    step("pull and validate")
    dest = phone.ROOT / "pulled"
    shutil.rmtree(dest, ignore_errors=True)
    lectures = phone.pull(dest)
    finished = [p for p in lectures.iterdir() if p.is_dir() and p.name.startswith(folder[:15])]
    assert finished, f"{folder} not found in {list(lectures.iterdir())}"
    target = max(finished, key=lambda p: p.stat().st_mtime)
    report = check_lecture.check_folder(target, check_lecture.find_ffprobe())
    report.print()
    events = [json.loads(l) for l in (target / "events.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    print("   events:", [e["e"] for e in events])
    print("   lecture.json:", json.loads((target / "lecture.json").read_text(encoding="utf-8")))
    assert [e["e"] for e in events] == ["lecture_start", "recovered", "lecture_end"]
    assert events[-1]["kind"] == "seminar"
    assert all(e["boot"] == events[0]["boot"] for e in events)
    assert not report.errors
    print("STAGE 0 CHECK PASSED")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
