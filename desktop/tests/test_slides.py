import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from lecture_process import slides
from lecture_process.transcribe import HOTWORDS_MAX, LECTURE_SHARE, hotwords_for

SECTIONS = {
    "Лекция 1_Кристаллизация": "Кристаллизация расплава. Зародыш кристаллизации, переохлаждение, дендрит. " * 3,
    "Лекция 2_Стали": "Классификация сталей. Вредные примеси: сера вызывает красноломкость, фосфор хладноломкость. Раскислители. " * 3,
    "Лекция 3_Термообработка": "Отжиг, закалка, отпуск. Критическая скорость охлаждения, мартенсит, троостит, сорбит. " * 3,
}


FONT = r"C:\Windows\Fonts\arial.ttf"


def make_pdf(path: Path):
    import pymupdf
    doc = pymupdf.open()
    toc = []
    for title, text in SECTIONS.items():
        for k in range(2):
            page = doc.new_page()
            # The built-in PDF fonts have no Cyrillic: embed a system font.
            page.insert_font(fontname="F0", fontfile=FONT)
            page.insert_textbox(pymupdf.Rect(40, 40, 560, 800), f"{title}\n{text}", fontname="F0", fontsize=9)
            if k == 0:
                toc.append([1, title, doc.page_count])
    doc.set_toc(toc)
    doc.save(path)


class SlidesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        cls.pdf = cls.tmp / "Сборник.pdf"
        make_pdf(cls.pdf)
        cls.data = slides.index(cls.pdf, cls.tmp / "cache")

    def test_index_reads_outline_and_text(self):
        self.assertEqual(6, len(self.data["pages"]))
        self.assertEqual([(1, 2), (3, 4), (5, 6)], [(s["first"], s["last"]) for s in self.data["sections"]])
        self.assertIn("красноломкость", self.data["pages"][2])
        # Cached: the second call does not reopen the PDF.
        self.assertEqual(self.data, slides.index(self.pdf, self.tmp / "cache"))

    def test_match_by_transcript_windows_and_terms(self):
        pieces = [
            {"t0": 0, "t1": 10, "text": "сегодня про стали, сера даёт красноломкость, фосфор хладноломкость"},
            {"t0": 1300, "t1": 1310, "text": "теперь закалка и отпуск, мартенсит и троостит"},
        ]
        found = [s["title"] for s in slides.match_lecture(self.data, pieces, limit=2)]
        self.assertEqual(["Лекция 2_Стали", "Лекция 3_Термообработка"], found)
        found = [s["title"] for s in slides.match_lecture(self.data, pieces[:1], ["раскислители"], limit=1)]
        self.assertEqual(["Лекция 2_Стали"], found)

    def test_pages_text_links_pages(self):
        md = slides.pages_text(self.data, self.data["sections"][1:2])
        self.assertIn("[[Сборник.pdf#page=3]]", md)
        self.assertIn("Лекция 2_Стали (стр. 3–4)", md)


class HotwordsTest(unittest.TestCase):
    def test_lecture_terms_first_within_budget(self):
        work = Path(tempfile.mkdtemp())
        (work / "terms.txt").write_text("# comment\nкрасноломкость\nхладноломкость\n" + "\n".join(f"термин{i:03d}" for i in range(100)), encoding="utf-8")
        hot = hotwords_for(SimpleNamespace(subject="Нет такого предмета"), work)
        self.assertTrue(hot.startswith("красноломкость, хладноломкость"))
        self.assertLessEqual(len(hot), LECTURE_SHARE)
        self.assertLessEqual(len(hot), HOTWORDS_MAX)


if __name__ == "__main__":
    unittest.main()
