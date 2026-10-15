"""Stage 7 (lecture archive) acceptance scenarios on the connected phone.

Usage: python tools/stage7_check.py <scenario>

  list     sorting, grouping by subject, collapsing a section, subject filter
  edit     photo mark, kind and names of a finished lecture (events, lecture.json, folder
           rename), segment list
  folder   folder dialog: path, "open in" chooser
  delete   double confirmation (buttons swap places), sync status line, folder erased
  resume   an unfinished lecture opened from the archive continues recording

Works on finished lectures copied into /sdcard/Lectures from /sdcard/Lectures_tests (test
phone only): `python tools/stage7_check.py seed` copies them.
"""
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import phone  # noqa: E402
from stage1_check import finish, new_lecture, step, text_of  # noqa: E402
from stage2_check import replace_text  # noqa: E402

ROOT = "/sdcard/Lectures"
SEED = [
    "2026-10-15_0133_Термех_Лекция-1",
    "2026-10-14_2356_Сопромат_Renamed",
    "2026-10-14_2244_Термех_Лекция-1",
    "2026-10-14_2203_Сопромат_Лекция-1",
    "2026-10-15_0033_Сопромат_Лекция-13",
    "2026-10-15_0003_Термех_Renamed",
]


def seed():
    for name in SEED:
        phone.shell(f"[ -d '{ROOT}/{name}' ] || cp -r '/sdcard/Lectures_tests/{name}' '{ROOT}/'")


def open_archive():
    phone.stop()
    phone.launch()
    phone.tap("archive_open")
    phone.wait("archive_sort")


def items():
    """Lecture folders of the visible archive cards, top to bottom."""
    found = [(phone._bounds(n)[1], n.get("resource-id")[len("archive_item_"):])
             for n in phone.nodes() if (n.get("resource-id") or "").startswith("archive_item_")]
    return [name for _, name in sorted(found)]


def sections():
    found = [(phone._bounds(n)[1], n.get("resource-id")[len("archive_section_"):])
             for n in phone.nodes() if (n.get("resource-id") or "").startswith("archive_section_")]
    return [name for _, name in sorted(found)]


def set_switch(tag, on):
    node = phone.wait(tag)
    if (node.get("checked") == "true") != on:
        phone.tap(tag)
        time.sleep(0.5)


def sort_newest(newest):
    for _ in range(2):
        label = text_of("archive_sort")
        if ("новые" in label) == newest:
            return
        phone.tap("archive_sort")
        time.sleep(0.5)
    raise AssertionError(f"sort button stuck at {label!r}")


def events(folder):
    out = phone.shell(f"cat '{ROOT}/{folder}/events.jsonl'")
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def meta(folder):
    return json.loads(phone.shell(f"cat '{ROOT}/{folder}/lecture.json'"))


def folders():
    return set(phone.ls().split())


def scenario_list():
    seed()
    open_archive()
    phone.tap("archive_filter_all") if phone.find("archive_filter_all") is not None else None
    set_switch("archive_group", False)

    step("newest first, then oldest first")
    sort_newest(True)
    top = items()
    assert top and top == sorted(top, reverse=True), top
    sort_newest(False)
    top = items()
    assert top == sorted(top), top
    sort_newest(True)

    step("grouped by subject; collapse one section")
    set_switch("archive_group", True)
    assert sections()[:2] == ["Сопромат", "Термех"], sections()
    phone.tap("archive_section_Сопромат")
    time.sleep(0.5)
    assert not any("Сопромат" in name for name in items()), items()
    phone.tap("archive_section_Сопромат")
    time.sleep(0.5)
    assert any("Сопромат" in name for name in items()), items()

    step("filter: only Термех, then all")
    chips = {text_of(n.get("resource-id")): n.get("resource-id")
             for n in phone.nodes() if (n.get("resource-id") or "").startswith("archive_filter_") and n.get("resource-id") != "archive_filter_all"}
    phone.tap(chips["Термех"])
    time.sleep(0.5)
    assert sections() == ["Термех"], sections()
    assert items() and all("Термех" in name for name in items()), items()
    set_switch("archive_group", False)
    assert all("Термех" in name for name in items()), items()
    phone.tap("archive_filter_all")
    time.sleep(0.5)

    step("settings are remembered")
    phone.stop()
    open_archive()
    assert not sections(), "grouping was off when the app closed"
    step("list: OK")


def scenario_edit():
    seed()
    folder = "2026-10-14_2356_Сопромат_Renamed"
    open_archive()
    phone.tap(f"archive_item_{folder}")
    phone.wait("lecture_sync")
    before = len(events(folder))

    step("photo mark on a finished lecture")
    checked = phone.find("photo_check_0").get("content-desc")
    phone.tap("photo_check_0")
    time.sleep(1)
    last = events(folder)[-1]
    assert last["e"] in ("obsolete", "restored"), last
    phone.tap("photo_check_0")
    time.sleep(1)
    assert phone.find("photo_check_0").get("content-desc") == checked

    step("kind: seminar, then back")
    phone.tap("kind_seminar")
    time.sleep(1)
    assert meta(folder)["kind"] == "seminar", meta(folder)
    assert events(folder)[-1] == {**events(folder)[-1], "e": "meta", "kind": "seminar"}
    phone.tap("kind_lecture")
    time.sleep(1)
    assert meta(folder)["kind"] == "lecture"

    step("rename: the folder follows")
    phone.tap("menu_button")
    phone.tap("menu_rename")
    replace_text("rename_title", "Edited")
    phone.tap("confirm_rename")
    time.sleep(1.5)
    renamed = "2026-10-14_2356_Сопромат_Edited"
    assert renamed in folders() and folder not in folders(), folders()
    assert text_of("lecture_title") == "Edited"
    assert meta(renamed)["title"] == "Edited"
    evs = events(renamed)
    assert len(evs) == before + 5, (before, len(evs))
    assert all(e["seq"] == i + 1 for i, e in enumerate(evs))

    step("rename back so the scenario can be repeated")
    phone.tap("menu_button")
    phone.tap("menu_rename")
    replace_text("rename_title", "Renamed")
    phone.tap("confirm_rename")
    time.sleep(1.5)
    assert folder in folders()

    step("audio segments are listed")
    phone.wait("segments_toggle")
    phone.tap("lecture_back")
    phone.wait("archive_sort")
    step("edit: OK")


def scenario_folder():
    seed()
    open_archive()
    phone.tap("archive_folder")
    assert text_of("folder_path").endswith("/Lectures"), text_of("folder_path")
    phone.tap("folder_open")
    time.sleep(2)
    focus = phone.shell("dumpsys window | grep mCurrentFocus")
    step(f"after 'open in': {focus.strip()}")
    assert "Chooser" in focus or "Resolver" in focus or "documentsui" in focus, focus
    phone.key("KEYCODE_BACK")
    time.sleep(1)
    phone.tap(f"archive_item_{SEED[0]}")
    phone.tap("lecture_folder")
    assert text_of("folder_path").endswith(f"/Lectures/{SEED[0]}"), text_of("folder_path")
    phone.tap("folder_copy")
    phone.wait("lecture_back")
    step("folder: OK")


def scenario_delete():
    seed()
    folder = "2026-10-14_2203_Сопромат_Лекция-1"
    open_archive()
    phone.tap(f"archive_item_{folder}")
    phone.wait("lecture_sync")

    step("step 1 shows the sync status; cancel")
    phone.tap("menu_button")
    phone.tap("menu_delete")
    status = text_of("delete_sync_status")
    step(f"status: {status}")
    assert "ноутбук" in status or "НЕ синхронизирована" in status or "синхронизации" in status
    phone.tap("cancel_delete_1")

    step("step 2 swaps the buttons; cancel there")
    phone.tap("menu_button")
    phone.tap("menu_delete")
    confirm1 = phone._bounds(phone.wait("confirm_delete_1"))
    phone.tap("confirm_delete_1")
    confirm2 = phone._bounds(phone.wait("confirm_delete_2"))
    cancel2 = phone._bounds(phone.find("cancel_delete_2"))
    assert confirm2[0] < cancel2[0], (confirm2, cancel2)
    assert not (confirm2[0] <= (confirm1[0] + confirm1[2]) // 2 <= confirm2[2]), "confirm buttons overlap"
    phone.tap("cancel_delete_2")
    assert folder in folders()

    step("delete for real")
    phone.tap("menu_button")
    phone.tap("menu_delete")
    phone.tap("confirm_delete_1")
    phone.tap("confirm_delete_2")
    phone.wait("archive_sort")
    time.sleep(1)
    assert folder not in folders(), folders()
    assert phone.find(f"archive_item_{folder}") is None
    step("delete: OK (run seed to restore the copy)")


def scenario_resume():
    folder = new_lecture()
    phone.stop()  # the lecture stays unfinished, as after a crash
    phone.launch()
    phone.tap("archive_open")
    phone.wait("archive_sort")
    sort_newest(True)
    set_switch("archive_group", False)
    phone.tap(f"archive_item_{folder}")
    phone.tap("lecture_resume")
    phone.wait("audio_button")
    finish()
    assert events(folder)[-1]["e"] == "lecture_end"
    step("resume: OK")


def main(argv):
    scenarios = {
        "seed": seed, "list": scenario_list, "edit": scenario_edit, "folder": scenario_folder,
        "delete": scenario_delete, "resume": scenario_resume,
    }
    if len(argv) < 2 or argv[1] not in scenarios:
        print(__doc__)
        return 2
    scenarios[argv[1]]()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
