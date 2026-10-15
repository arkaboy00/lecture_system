import json
import tempfile
import unittest
from pathlib import Path

from lecture_process import lecture as lec
from lecture_process.asr import Piece, Word, clean, collapse_repeats
from lecture_process.pipeline import status
from lecture_process import vault as vault_mod


def stamp(ts, mono, boot=7):
    return {"ts": ts, "mono": mono, "boot": boot}


def write_lecture(folder, events, meta=None):
    folder.mkdir(parents=True)
    lines = [json.dumps({"e": e, "seq": i + 1, **st, **body}, ensure_ascii=False) for i, (e, st, body) in enumerate(events)]
    (folder / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")
    if meta is not None:
        (folder / "lecture.json").write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")
    return folder


class LectureTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def test_timeline_follows_mono_not_wall_clock(self):
        # The wall clock jumps back 5 minutes mid-lecture (time sync); mono keeps the order.
        folder = write_lecture(self.tmp / "L", [
            ("lecture_start", stamp("2026-10-06T10:00:00+03:00", 1_000_000), {"subject": "Термех", "title": "Лекция 1"}),
            ("audio_start", stamp("2026-10-06T10:00:10+03:00", 1_010_000), {"file": "audio/001.ogg"}),
            ("photo", stamp("2026-10-06T09:55:40+03:00", 1_040_500), {"file": "photos/1.jpg", "type": "board", "batch": 1}),
            ("audio_stop", stamp("2026-10-06T09:56:10+03:00", 1_070_000), {"file": "audio/001.ogg", "reason": "user", "dur_ms": 59_900}),
            ("lecture_end", stamp("2026-10-06T09:56:20+03:00", 1_080_000), {"kind": "seminar"}),
        ])
        lecture = lec.load(folder)
        self.assertTrue(lecture.ended)
        self.assertEqual("seminar", lecture.kind)
        seg, photo = lecture.segments[0], lecture.photos[0]
        self.assertAlmostEqual(10.0, seg.start - lecture.start)
        self.assertAlmostEqual(40.5, photo.time - lecture.start)
        self.assertAlmostEqual(59.9, seg.length)
        self.assertEqual("10:00:40", lecture.clock(photo.time).strftime("%H:%M:%S"))

    def test_reboot_falls_back_to_wall_clock(self):
        folder = write_lecture(self.tmp / "L", [
            ("lecture_start", stamp("2026-10-06T10:00:00+03:00", 500_000, boot=7), {"subject": "S", "title": "T"}),
            ("audio_start", stamp("2026-10-06T10:00:05+03:00", 505_000, boot=7), {"file": "audio/001.ogg"}),
            # Phone rebooted: mono starts again near zero, a new boot count.
            ("recovered", stamp("2026-10-06T10:20:00+03:00", 9_000, boot=8), {}),
            ("audio_stop", stamp("2026-10-06T10:10:05+03:00", 1_105_000, boot=7), {"file": "audio/001.ogg", "reason": "recovered", "dur_ms": 600_000}),
            ("audio_start", stamp("2026-10-06T10:20:01+03:00", 10_000, boot=8), {"file": "audio/002.ogg"}),
        ])
        lecture = lec.load(folder)
        self.assertEqual(["audio/001.ogg", "audio/002.ogg"], [s.file for s in lecture.segments])
        self.assertAlmostEqual(1201.0, lecture.segments[1].start - lecture.start)
        self.assertIsNone(lecture.segments[1].end)
        self.assertFalse(lecture.ended)

    def test_marks_renames_and_later_kind(self):
        folder = write_lecture(self.tmp / "L", [
            ("lecture_start", stamp("2026-10-06T10:00:00+03:00", 0), {"subject": "Сопромат", "title": "Лекция 1"}),
            ("photo", stamp("2026-10-06T10:00:01+03:00", 1000), {"file": "photos/a.jpg", "type": "board", "batch": 1}),
            ("photo", stamp("2026-10-06T10:00:02+03:00", 2000), {"file": "photos/b.jpg", "type": "notebook", "batch": 1}),
            ("obsolete", stamp("2026-10-06T10:00:03+03:00", 3000), {"file": "photos/a.jpg"}),
            ("obsolete", stamp("2026-10-06T10:00:04+03:00", 4000), {"file": "photos/b.jpg"}),
            ("restored", stamp("2026-10-06T10:00:05+03:00", 5000), {"file": "photos/b.jpg"}),
            ("meta", stamp("2026-10-06T10:00:06+03:00", 6000), {"title": "Лекция 2"}),
            ("lecture_end", stamp("2026-10-06T10:00:07+03:00", 7000), {"kind": "lecture"}),
            # Edited later on the archive screen.
            ("meta", stamp("2026-10-07T12:00:00+03:00", 99_000, boot=9), {"kind": "seminar"}),
        ], meta={"subject": "Сопромат", "title": "Лекция 2", "kind": "seminar", "ended": "x"})
        lecture = lec.load(folder)
        self.assertEqual("Лекция 2", lecture.title)
        self.assertEqual("seminar", lecture.kind)
        self.assertEqual(["photos/b.jpg"], [p.file for p in lecture.relevant_photos()])
        self.assertEqual([], lecture.problems)

    def test_torn_line_is_skipped(self):
        folder = write_lecture(self.tmp / "L", [
            ("lecture_start", stamp("2026-10-06T10:00:00+03:00", 0), {"subject": "S", "title": "T"}),
        ])
        with open(folder / "events.jsonl", "a", encoding="utf-8") as f:
            f.write('{"e":"photo","seq":2,"ts":"2026-10-0')
        lecture = lec.load(folder)
        self.assertEqual(1, len(lecture.problems))
        self.assertEqual("S", lecture.subject)


class MarkupTest(unittest.TestCase):
    """Stage 8 of the app: photo type and redraw flag, notes, marks, lecture parts."""

    def test_flags_notes_marks_and_part(self):
        tmp = Path(tempfile.mkdtemp())
        folder = write_lecture(tmp / "L", [
            ("lecture_start", stamp("2026-10-06T10:00:00+03:00", 0), {"subject": "Сопромат", "title": "Лекция 1 ч.2", "part": 2}),
            ("photo", stamp("2026-10-06T10:00:10+03:00", 10_000), {"file": "photos/a.jpg", "type": "board", "batch": 1}),
            ("redraw", stamp("2026-10-06T10:00:11+03:00", 11_000), {"file": "photos/a.jpg", "on": True}),
            ("note", stamp("2026-10-06T10:00:20+03:00", 20_000), {"text": "формула на обороте"}),
            ("note", stamp("2026-10-06T10:00:25+03:00", 25_000), {"text": "ошибка"}),
            ("mark", stamp("2026-10-06T10:00:30+03:00", 30_000), {}),
            ("mark", stamp("2026-10-06T10:00:31+03:00", 31_000), {}),
            ("unmark", stamp("2026-10-06T10:00:32+03:00", 32_000), {"mark": 7}),
            ("lecture_end", stamp("2026-10-06T10:00:40+03:00", 40_000), {"kind": "lecture"}),
            # Later edits on the archive screen.
            ("retype", stamp("2026-10-07T12:00:00+03:00", 99_000, boot=9), {"file": "photos/a.jpg", "type": "slide"}),
            ("note_removed", stamp("2026-10-07T12:00:01+03:00", 100_000, boot=9), {"note": 5}),
        ])
        lecture = lec.load(folder)
        self.assertEqual(2, lecture.part)
        self.assertEqual(("slide", True), (lecture.photos[0].type, lecture.photos[0].redraw))
        self.assertEqual(["формула на обороте"], [n.text for n in lecture.notes])
        self.assertEqual([6], [m.seq for m in lecture.marks])
        self.assertEqual([], lecture.problems)

        from lecture_process.package import render
        transcript = {
            "subject": lecture.subject, "title": lecture.title, "kind": "lecture", "part": 2, "ended": True,
            "started_at": "2026-10-06T10:00:00+03:00", "segments": [],
            "photos": [{"file": "photos/a.jpg", "type": "slide", "batch": 1, "t": 10.0, "ts": "2026-10-06T10:00:10+03:00",
                        "obsolete": False, "redraw": True}],
            "notes": [{"t": 20.0, "ts": "2026-10-06T10:00:20+03:00", "text": "формула на обороте"}],
            "marks": [{"t": 30.0, "ts": "2026-10-06T10:00:30+03:00"}],
            "pieces": [{"t0": 28.0, "t1": 29.5, "text": "это будет на экзамене", "words": [], "unsure": False}],
        }
        text = render(transcript, {1: "01_100010_slide.jpg"})
        self.assertIn("Лекция, часть 2", text)
        self.assertIn("[ФОТО 1 · 10:00:10 · слайд · перерисовать · photos/01_100010_slide.jpg]", text)
        self.assertIn("[ЗАМЕТКА · 10:00:20] формула на обороте", text)
        self.assertLess(text.index("это будет на экзамене"), text.index("[ОТМЕЧЕНО КНОПКОЙ · 10:00:30]"))


def words(text, start=0.0):
    return [Word(start + i, start + i + 0.5, " " + w, 0.9) for i, w in enumerate(text.split())]


class CleanTest(unittest.TestCase):
    def test_repetition_loop_collapses(self):
        out = collapse_repeats(words("и вот в том числе и в том числе и в том числе и в том числе и всё"))
        self.assertEqual("и вот в том числе и всё", "".join(w.text for w in out).strip())

    def test_double_repeat_is_kept(self):
        text = "да да это так"
        self.assertEqual(text, "".join(w.text for w in collapse_repeats(words(text))).strip())

    def test_subtitle_credits_and_silence_dropped(self):
        pieces = [
            Piece(0, 30, "Субтитры сделал DimaTorzok", 0.9, -0.5, words("Субтитры сделал DimaTorzok")),
            Piece(30, 60, "Продолжение следует...", 0.9, -0.3, words("Продолжение следует...")),
            Piece(60, 62, "Эпюра изгибающих моментов", 0.1, -0.2, words("Эпюра изгибающих моментов")),
            Piece(62, 64, "мм", 0.8, -1.2, words("мм")),
            Piece(64, 66, "Спасибо за внимание", 0.2, -0.3, words("Спасибо за внимание")),
        ]
        self.assertEqual(["Эпюра изгибающих моментов", "Спасибо за внимание"], [p.text for p in clean(pieces)])

    def test_prompt_echo_dropped(self):
        prompt = "Лекция по предмету «Сопромат». Термины: эпюра, изгибающий момент."
        pieces = [
            Piece(0, 5, "Термины по предмету «Сопромат»", 0.5, -0.6, words("Термины по предмету «Сопромат»")),
            Piece(0, 5, "Лекция по предмету «Сопромат».", 0.5, -0.6, words("Лекция по предмету «Сопромат».")),
            Piece(5, 9, "Строим эпюру", 0.1, -0.2, words("Строим эпюру")),
            Piece(9, 11, "Изгибающий момент.", 0.1, -0.2, words("Изгибающий момент.")),
        ]
        self.assertEqual(["Строим эпюру", "Изгибающий момент."], [p.text for p in clean(pieces, prompt)])


class StatusTest(unittest.TestCase):
    def test_states_from_note_front_matter(self):
        tmp = Path(tempfile.mkdtemp())
        work, vault = tmp / "work", tmp / "vault"
        for folder, version in (("A", "v1"), ("B", "v2"), ("C", "v3")):
            (work / folder / "notes_input").mkdir(parents=True)
            (work / folder / "notes_input" / "info.json").write_text(json.dumps(
                {"folder": folder, "subject": "S", "title": folder, "kind": "lecture", "date": "2026-10-15",
                 "input_version": version}), encoding="utf-8")
        (vault / "S").mkdir(parents=True)
        (vault / "S" / "a.md").write_text("---\nпредмет: S\nисточник: A\nверсия_входа: v1\n---\n# A\n", encoding="utf-8")
        (vault / "S" / "b.md").write_text("---\nисточник: B\nверсия_входа: old\n---\n", encoding="utf-8")
        (vault / "S" / "plain.md").write_text("# no front matter\nисточник: C\n", encoding="utf-8")
        rows = status({"work": str(work), "vault": str(vault)})
        self.assertEqual({"A": "done", "B": "changed", "C": "new"}, {r["folder"]: r["state"] for r in rows})


class VaultTest(unittest.TestCase):
    def test_contents_pages(self):
        vault = Path(tempfile.mkdtemp()) / "v"
        vault_mod.setup(vault, ["Термех", "Сопромат"])
        self.assertTrue((vault / ".obsidian" / "app.json").is_file())
        for date, title, topic in (("2026-10-01", "Лекция 1", "Растяжение | сжатие"), ("2026-10-08", "Лекция 2", "Кручение")):
            (vault / "Сопромат" / f"{date} {title}.md").write_text(
                f"---\nпредмет: Сопромат\nтип: лекция\nдата: {date}\nназвание: {title}\nтема: {topic}\n"
                f"источник: {date}_x\nверсия_входа: v\n---\n# {title}\n", encoding="utf-8")
        waiting = [{"subject": "Термех", "title": "Лекция 1", "date": "2026-10-02", "state": "new"}]
        vault_mod.write_indexes(vault, ["Термех", "Сопромат"], waiting)

        page = (vault / "Сопромат" / "Сопромат.md").read_text(encoding="utf-8")
        rows = [line for line in page.splitlines() if line.startswith("| 0")]
        self.assertEqual(2, len(rows))
        self.assertIn("[[2026-10-08 Лекция 2\\|Лекция 2]]", rows[0], "newest first")
        self.assertIn("Растяжение \\| сжатие", rows[1], "a pipe in a cell is escaped")
        self.assertIn("Конспектов пока нет", (vault / "Термех" / "Термех.md").read_text(encoding="utf-8"))
        home = (vault / "Главная.md").read_text(encoding="utf-8")
        self.assertIn("[[Сопромат/Сопромат|Сопромат]] — конспектов: 2, последний 08.10.2026", home)
        self.assertIn("Термех — Лекция 1", home)
        # Contents pages are not notes: they must not show up as lectures.
        self.assertEqual({"Сопромат"}, set(vault_mod.notes(vault)))


if __name__ == "__main__":
    unittest.main()
