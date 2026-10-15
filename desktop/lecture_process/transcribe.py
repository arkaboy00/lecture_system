"""Stage 1 of processing: speech of one lecture → `<work>/<folder>/transcript.json`.

The raw lecture folder is never written to: the phone owns it and sync mirrors it. Raw results
per audio file are cached in `<work>/<folder>/asr/` by (file size, model, prompt), so a
lecture that grew (a later sync) only recognizes the new segments.
"""
import json
import os
from dataclasses import asdict
from pathlib import Path

from . import lecture as lec
from .asr import MODEL, clean, from_dict

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
    """Whisper's initial prompt: subject and its terms steer the spelling of rare words."""
    terms = glossary(lecture.subject)
    text = f"Лекция по предмету «{lecture.subject}»."
    if terms:
        text += " Термины: " + ", ".join(terms) + "."
    return text[:600]  # Whisper keeps ~224 tokens of prompt; the rest would be cut anyway


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
    prompt = prompt_for(lecture)
    pieces = []
    for seg in lecture.segments:
        audio = lecture.dir / seg.file
        if not audio.is_file():
            log(f"  нет файла {seg.file}")
            continue
        cache = work / "asr" / (Path(seg.file).stem + ".json")  # raw model output
        key = {"size": audio.stat().st_size, "model": MODEL, "prompt": prompt, "raw": 1}
        try:
            cached = json.loads(cache.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            cached = None
        if cached and cached.get("key") == key:
            result = cached["pieces"]
        else:
            model = recognizer()
            log(f"  распознаю {seg.file}…")
            result = [asdict(p) for p in model.transcribe(audio, prompt)]
            _write_json(cache, {"key": key, "pieces": result})
        offset = seg.start - lecture.start
        for p in map(asdict, clean([from_dict(d) for d in result], prompt)):
            pieces.append({
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
                    "obsolete": p.obsolete} for p in lecture.photos],
        "problems": lecture.problems,
        "pieces": pieces,
    })
    return out
