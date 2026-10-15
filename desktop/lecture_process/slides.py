"""Course materials (a PDF of the lecturer's slides) as a local, token-free helper.

A PDF dropped into `<vault>/<subject>/Материалы/` is indexed once (page texts and the
outline; cached in `<work>/_sources/`). For a lecture the index gives:
  - the sections that match what was said (BM25 over section texts vs. the transcript),
  - rare terms of those sections: extra hints for speech recognition,
  - the text of the matched pages: a compact `slides.md` for the notes step, so the model
    reads a few dozen pages of text instead of a 500-page file.
Nothing here sends anything anywhere.
"""
import hashlib
import json
import math
import re
from collections import Counter
from pathlib import Path

MATERIALS = "Материалы"
WORD = re.compile(r"[а-яёa-z][а-яёa-z\-]{2,}", re.IGNORECASE)

# Frequent Russian words and slide boilerplate that say nothing about the topic.
STOP = set("""
это как что так для при или его она они оно эти этот эта под над без все всё был была были быть
есть может можно который которая которые которых также более менее очень если только того этого
том той тем чем где когда после между через свой своей своих один одна одно два две три лекция
рис рисунок таблица пример примеры виды вид основные основной следующие является являются
""".split())


def _stem(word: str) -> str:
    """Crude Russian stemming: inflections differ at the end, a 6-letter prefix is enough to
    match "перлита" with "перлит" and "эвтектоидной" with "эвтектоидная"."""
    return word.lower().replace("ё", "е")[:6]


def tokens(text: str):
    return [_stem(w) for w in WORD.findall(text) if w.lower() not in STOP]


def materials(vault: Path, subject: str):
    folder = vault / subject / MATERIALS
    return sorted(folder.glob("*.pdf")) if folder.is_dir() else []


def index(pdf: Path, cache_dir: Path) -> dict:
    """Page texts and outline sections of [pdf], cached by its size and mtime."""
    st = pdf.stat()
    key = hashlib.sha256(f"{pdf.name}|{st.st_size}|{st.st_mtime_ns}".encode()).hexdigest()[:16]
    cache = cache_dir / f"{pdf.stem}.{key}.json"
    if cache.is_file():
        return json.loads(cache.read_text(encoding="utf-8"))
    import pymupdf
    with pymupdf.open(pdf) as doc:
        pages = [doc[i].get_text("text") for i in range(doc.page_count)]
        toc = [(title.strip(), page) for level, title, page in doc.get_toc() if level == 1 and page > 0]
    sections = []
    for i, (title, start) in enumerate(toc):
        end = (toc[i + 1][1] - 1) if i + 1 < len(toc) else len(pages)
        if end >= start:
            sections.append({"title": title, "first": start, "last": end})
    if not sections:  # no outline: one section per 10 pages
        sections = [{"title": f"стр. {s + 1}–{min(s + 10, len(pages))}", "first": s + 1, "last": min(s + 10, len(pages))}
                    for s in range(0, len(pages), 10)]
    data = {"pdf": pdf.name, "pages": pages, "sections": sections}
    cache_dir.mkdir(parents=True, exist_ok=True)
    for old in cache_dir.glob(f"{pdf.stem}.*.json"):
        old.unlink()
    cache.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return data


def _section_text(data, s):
    return "\n".join(data["pages"][s["first"] - 1:s["last"]])


class Matcher:
    """Ranks the file's sections against some text by its *distinctive* words only: words
    found in few sections (BM25 over the whole noisy transcript let long sections win on
    common words). Score = sum over shared words of log(1 + count in text) * idf * sqrt(share
    of the word in the section)."""

    def __init__(self, data: dict, max_section_df=5):
        self.data = data
        self.docs = [Counter(tokens(_section_text(data, s))) for s in data["sections"]]
        self.df = Counter(t for d in self.docs for t in d)
        self.n = len(self.docs)
        self.max_df = max_section_df

    def rank(self, text: str):
        query = Counter(t for t in tokens(text) if 0 < self.df[t] <= self.max_df)
        scored = []
        for d, sec in zip(self.docs, self.data["sections"]):
            total = sum(d.values()) or 1
            score = sum(math.log(1 + qf) * math.log(self.n / self.df[t]) * math.sqrt(d[t] / total)
                        for t, qf in query.items() if d[t])
            scored.append((score, sec))
        return sorted(scored, key=lambda x: -x[0])


def match_lecture(data: dict, pieces, lecture_terms=(), window=1200, limit=4):
    """Sections a lecture covered, in the file's order. A lecture often spans several
    sections (two periods: diagram, then steels, then heat treatment), so the transcript is
    ranked in 20-minute windows (best section of each) plus as a whole; the lecture's own
    terms (from the notebook photos: short and clean) are the strongest single query."""
    m = Matcher(data)
    votes = Counter()
    if lecture_terms:
        for rank, (score, sec) in enumerate(m.rank(" ".join(lecture_terms))[:2]):
            if score > 0:
                votes[sec["first"]] += 3 - rank
    whole = " ".join(p["text"] for p in pieces)
    for rank, (score, sec) in enumerate(m.rank(whole)[:2]):
        if score > 0:
            votes[sec["first"]] += 2 - rank
    if pieces:
        start, end = pieces[0]["t0"], pieces[-1]["t1"]
        t = start
        while t < end:
            text = " ".join(p["text"] for p in pieces if t <= p["t0"] < t + window)
            best = m.rank(text)[0] if text else (0, None)
            if best[0] > 0:
                votes[best[1]["first"]] += 1
            t += window
    chosen = [first for first, _ in votes.most_common(limit)]
    return [sec for sec in data["sections"] if sec["first"] in chosen]


def terms(data: dict, sections, limit=40):
    """Distinctive words of [sections] as spelled on the slides: frequent there and found in
    few other sections of the file (so "сталь" in a steels-only file does not qualify, but
    "красноломкость" does). Hints for speech recognition."""
    chosen = {s["first"] for s in sections}
    per_section = [Counter(_stem(w) for w in WORD.findall(_section_text(data, s)) if len(w) >= 5)
                   for s in data["sections"]]
    section_df = Counter(t for c in per_section for t in c)
    n = len(per_section)
    inside, spelled = Counter(), {}
    for s in sections:
        for w in WORD.findall(_section_text(data, s)):
            if len(w) < 5 or w.lower() in STOP:
                continue
            st = _stem(w)
            inside[st] += 1
            spelled.setdefault(st, Counter())[w.lower()] += 1
    rare = [t for t in inside if inside[t] >= 2 and section_df[t] <= max(2, n // 6)]
    ranked = sorted(rare, key=lambda t: -(math.log(1 + inside[t]) * math.log(n / section_df[t])))
    return [spelled[t].most_common(1)[0][0] for t in ranked[:limit]]


def pages_text(data: dict, sections, max_chars=60_000):
    """Markdown with the text of the matched sections, page by page (for slides.md). Page
    headings link into the PDF in the vault (`[[file.pdf#page=N]]`)."""
    out, used = [], 0
    for s in sections:
        out.append(f"## {s['title']} (стр. {s['first']}–{s['last']})\n")
        for p in range(s["first"], s["last"] + 1):
            text = re.sub(r"\n{2,}", "\n", data["pages"][p - 1]).strip()
            if not text or len(text) < 15:
                text = "(на странице только рисунок)"
            block = f"### стр. {p} · [[{data['pdf']}#page={p}]]\n{text}\n"
            if used + len(block) > max_chars:
                out.append("…(дальше обрезано)\n")
                return "\n".join(out)
            out.append(block)
            used += len(block)
    return "\n".join(out)
