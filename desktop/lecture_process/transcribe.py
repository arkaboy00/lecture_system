"""Stage 1 of processing: speech of one lecture → `<work>/<folder>/transcript.json`.

The raw lecture folder is never written to: the phone owns it and sync mirrors it. Raw results
per audio file are cached in `<work>/<folder>/asr/` by (file size, model, prompt, hotwords), so a
lecture that grew (a later sync) only recognizes the new segments.
"""
import json
import os
from dataclasses import asdict
from pathlib import Path

from . import lecture as lec
from .asr import MODEL, USE_VAD, clean, from_dict, unsure

FORMAT = 1
GLOSSARY_DIR = Path(__file__).resolve().parent.parent / "glossary"


def glossary(subject):
    """Terms of a subject (glossary/<Subject>.txt, one per line, # comments)."""
    path = GLOSSARY_DIR / f"{subject.strip()}.txt"
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return []
    return [t.strip() for t in lines if t.strip() and not t.lstrip().startswith("#")]


def prompt_for(lecture):
    """Whisper's initial prompt: the subject."""
    return f"Лекция по предмету «{lecture.subject}»."


TERMS_FILE = "terms.txt"
# Whisper keeps the first max_length // 2 - 1 = 223 tokens of hotwords and cuts the rest
# (Russian terms take ~2.1-2.5 chars per token). 600 chars slightly overfills it on purpose.
HOTWORDS_MAX = 600
# The lecture's terms get about half, like the measured winner (notebook terms ~123 tokens,
# then the glossary); with more of them the glossary's general terms fell out.
LECTURE_MAX = 300


def lecture_terms(work: Path):
    """Terms of this very lecture (`<work>/<folder>/terms.txt`, one per line), written by the
    notes skill from the notebook and board photos before a second recognition pass."""
    try:
        lines = (work / TERMS_FILE).read_text(encoding="utf-8").splitlines()
    except OSError:
        return []
    return [t.strip() for t in lines if t.strip() and not t.lstrip().startswith("#")]


def hotwords_for(lecture, work: Path):
    """Put into every window's prompt: they steer the spelling of rare words. The lecture's
    own terms in their natural order (notebook first), then the subject glossary.

    Measured 2026-10-15 on Материаловедение, 26 + 18 min segments: this mix beat glossary
    only (key terms 47 → 67-74, doubtful pieces 14 → 2-5) and notebook terms only (which
    lost half the text where the lecture was about the glossary's phases). Reordering the
    terms "rarest first" and the glossary "most misheard first" made it worse (doubtful 28),
    so order is kept as written. Runs differ a little: Whisper samples on hard windows.
    """
    out, used, seen = [], 0, set()

    def add(term, limit):
        nonlocal used
        key = " ".join(w[:8] for w in term.lower().split())
        if key in seen or used + len(term) + 2 > limit:
            return False
        seen.add(key)
        out.append(term)
        used += len(term) + 2
        return True

    for term in lecture_terms(work):
        if not add(term, LECTURE_MAX):
            break  # keep the written order: no smaller term jumps the queue
    for term in glossary(lecture.subject):
        add(term, HOTWORDS_MAX)
    return ", ".join(out) or None


def _write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    os.replace(tmp, path)


def transcribe(folder, work_root, recognizer, log=print):
    """Recognizes every audio segment of the lecture and writes transcript.json. Returns its path.

    [recognizer] is called only when a segment is not cached (loading the model takes seconds).
    """
    lecture = lec.load(folder)
    work = Path(work_root) / lecture.name
    prompt, hotwords = prompt_for(lecture), hotwords_for(lecture, work)
    pieces = []
    for seg in lecture.segments:
        audio = lecture.dir / seg.file
        if not audio.is_file():
            log(f"  нет файла {seg.file}")
            continue
        cache = work / "asr" / (Path(seg.file).stem + ".json")  # raw model output
        key = {"size": audio.stat().st_size, "model": MODEL, "prompt": prompt, "hotwords": hotwords, "raw": 1, "vad": USE_VAD}
        try:
            cached = json.loads(cache.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            cached = None
        if cached and cached.get("key") == key:
            result = cached["pieces"]
        else:
            model = recognizer()
            log(f"  распознаю {seg.file}…")
            result = [asdict(p) for p in model.transcribe(audio, prompt, hotwords)]
            _write_json(cache, {"key": key, "pieces": result})
        offset = seg.start - lecture.start
        for piece in clean([from_dict(d) for d in result], f"{prompt} {hotwords or ''}"):
            p = asdict(piece)
            pieces.append({
                "unsure": unsure(piece),
                "t0": round(offset + p["start"], 2),
                "t1": round(offset + p["end"], 2),
                "file": seg.file,
                "text": p["text"],
                "words": [[round(offset + w["start"], 2), round(offset + w["end"], 2), w["text"], round(w["prob"], 2)]
                          for w in p["words"]],
            })
    pieces.sort(key=lambda p: p["t0"])
    out = work / "transcript.json"
    _write_json(out, {
        "v": FORMAT,
        "folder": lecture.name,
        "subject": lecture.subject,
        "title": lecture.title,
        "kind": lecture.kind,
        "ended": lecture.ended,
        "started_at": lecture.started_at.isoformat() if lecture.started_at else None,
        "model": MODEL,
        "segments": [{"file": s.file, "t0": round(s.start - lecture.start, 2), "length": s.length, "reason": s.reason,
                      "silenced": [[round(a - lecture.start, 2), None if b is None else round(b - lecture.start, 2)]
                                   for a, b in s.silenced]}
                     for s in lecture.segments],
        "photos": [{"file": p.file, "type": p.type, "batch": p.batch, "t": round(p.time - lecture.start, 2),
                    "ts": p.ts, "obsolete": p.obsolete} for p in lecture.photos],
        "problems": lecture.problems,
        "pieces": pieces,
    })
    return out
