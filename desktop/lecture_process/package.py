"""Stage 2 of processing: transcript.json + photos → `<work>/<folder>/notes_input/`, the
folder Claude Code reads to write the notes:

  transcript.md   the speech in paragraphs with clock times; photo markers where the shutter
                  was pressed; breaks in the recording
  photos/         relevant photos only, upright, long side ≤ PHOTO_SIDE (formulas stay
                  readable, the model reads fewer pixels)
  info.json       names, kind, date, durations, the photo list

Everything here is derived and rebuilt from scratch on every run.
"""
import hashlib
import json
import os
import shutil
from datetime import datetime, timedelta
from pathlib import Path

PHOTO_SIDE = 2000
PARAGRAPH_PAUSE = 4.0    # s of silence that starts a new paragraph
PARAGRAPH_MAX = 90.0     # s: long monologues are still split for readability
BREAK_MIN = 3.0          # s between audio segments worth a line of its own
TYPE_RU = {"board": "доска", "notebook": "тетрадь", "slide": "слайд"}
OUT = "notes_input"


def _clock(started_at, t):
    return (started_at + timedelta(seconds=t)).strftime("%H:%M:%S") if started_at else _mmss(t)


def _mmss(t):
    t = int(max(t, 0))
    return f"{t // 3600}:{t % 3600 // 60:02d}:{t % 60:02d}" if t >= 3600 else f"{t // 60}:{t % 60:02d}"


def _duration(seconds):
    m = int(round(seconds / 60))
    if seconds < 60:
        return f"{int(seconds)} с"
    return f"{m // 60} ч {m % 60:02d} мин" if m >= 60 else f"{m} мин"


def _photo_clock(photo, started_at):
    """The phone's own shutter time (matches the original file name); the timeline estimate
    can be a fraction of a second early, which would show as the previous second."""
    if photo.get("ts"):
        return datetime.fromisoformat(photo["ts"]).strftime("%H:%M:%S")
    return _clock(started_at, photo["t"])


def _stamp_clock(item, started_at):
    """Phone clock of a note or mark (its own ts, like photos)."""
    if item.get("ts"):
        return datetime.fromisoformat(item["ts"]).strftime("%H:%M:%S")
    return _clock(started_at, item["t"])


def _photo_name(i, photo, started_at):
    return f"{i:02d}_{_photo_clock(photo, started_at).replace(':', '')}_{photo['type']}.jpg"


def _save_photo(src, dst):
    from PIL import Image, ImageOps
    with Image.open(src) as im:
        im = ImageOps.exif_transpose(im)
        im.thumbnail((PHOTO_SIDE, PHOTO_SIDE))
        im.convert("RGB").save(dst, "JPEG", quality=88)


def _timeline(transcript, photos):
    """Words, photo markers and recording breaks, in time order."""
    items = []
    for p in transcript["pieces"]:
        words = p["words"] or [[p["t0"], p["t1"], " " + p["text"], 1.0]]
        for i, (t0, t1, text, _) in enumerate(words):
            if i == 0 and p.get("unsure"):
                text = " (?)" + text
            items.append((t0, 1, ("word", t1, text)))
    for i, ph in enumerate(photos, 1):
        items.append((ph["t"], 0, ("photo", i, ph)))
    for note in transcript.get("notes", []):
        items.append((note["t"], 0, ("note", None, note)))
    for mark in transcript.get("marks", []):
        items.append((mark["t"], 0, ("mark", None, mark)))
    segs = transcript["segments"]
    for a, b in zip(segs, segs[1:]):
        end = a["t0"] + (a["length"] or 0)
        if b["t0"] - end >= BREAK_MIN:
            items.append((end, 0, ("break", b["t0"], a["reason"])))
    if segs and segs[-1]["reason"] in ("recovered", "error", None):
        last = segs[-1]
        items.append((last["t0"] + (last["length"] or 0), 0, ("cut", None, last["reason"])))
    return sorted(items, key=lambda x: (x[0], x[1]))


def render(transcript, photo_names):
    """transcript.md text. [photo_names]: published name per photo index (1-based)."""
    started = datetime.fromisoformat(transcript["started_at"]) if transcript.get("started_at") else None
    photos = [p for p in transcript["photos"] if not p["obsolete"]]
    kind = "Семинар" if transcript["kind"] == "seminar" else "Лекция"
    if transcript.get("part", 1) > 1:
        kind += f", часть {transcript['part']}"
    audio = sum(s["length"] or 0 for s in transcript["segments"])
    types = {}
    for p in photos:
        types[TYPE_RU.get(p["type"], p["type"])] = types.get(TYPE_RU.get(p["type"], p["type"]), 0) + 1

    out = [f"# {transcript['subject']} — {transcript['title']}", ""]
    head = [kind]
    if started:
        head.append(started.strftime("%d.%m.%Y, %H:%M"))
    head.append(f"аудио {_duration(audio)} ({len(transcript['segments'])} сегм.)")
    head.append(f"фото {len(photos)}" + (" (" + ", ".join(f"{k} {v}" for k, v in types.items()) + ")" if photos else ""))
    if transcript.get("notes"):
        head.append(f"заметок {len(transcript['notes'])}")
    if transcript.get("marks"):
        head.append(f"отметок {len(transcript['marks'])}")
    out += [" · ".join(head), ""]
    if not transcript.get("ended"):
        out += ["**Лекция не завершена на телефоне: запись может быть неполной.**", ""]
    out += [
        "Время в квадратных скобках — по часам телефона. Текст распознан автоматически (Whisper):",
        "термины и формулы на слух искажаются, сверяйся с фото доски. «(?)» перед фразой — модель",
        "не уверена, что это речь (шум, далёкий голос): такие куски могут быть выдумкой.",
        "[ЗАМЕТКА] — текст, набранный на телефоне (время — когда поле открыли); [ОТМЕЧЕНО КНОПКОЙ] —",
        "в этот момент было сказано что-то важное, смотреть речь перед отметкой.",
        "",
        "## Запись",
        "",
    ]

    para, para_start, last_end = [], None, None

    def flush():
        nonlocal para, para_start
        if para:
            out.extend([f"[{_clock(started, para_start)}] " + "".join(para).strip(), ""])
        para, para_start = [], None

    for t, _, (what, extra, data) in _timeline(transcript, photos):
        if what == "word":
            if para and (t - last_end > PARAGRAPH_PAUSE or t - para_start > PARAGRAPH_MAX):
                flush()
            if para_start is None:
                para_start = t
            para.append(data)
            last_end = extra
        elif what == "photo":
            flush()
            ph = data
            flags = " · перерисовать" if ph.get("redraw") else ""
            out.extend([f"[ФОТО {extra} · {_photo_clock(ph, started)} · {TYPE_RU.get(ph['type'], ph['type'])}{flags} · photos/{photo_names[extra]}]", ""])
        elif what == "note":
            flush()
            out.extend([f"[ЗАМЕТКА · {_stamp_clock(data, started)}] {data['text']}", ""])
        elif what == "mark":
            flush()
            out.extend([f"[ОТМЕЧЕНО КНОПКОЙ · {_stamp_clock(data, started)}]", ""])
        elif what == "break":
            flush()
            out.extend([f"— запись остановлена с {_clock(started, t)} до {_clock(started, extra)} ({_duration(extra - t)}) —", ""])
        elif what == "cut":
            flush()
            out.extend([f"— запись оборвалась в {_clock(started, t)} (сбой на телефоне) —", ""])
    flush()
    if not transcript["pieces"]:
        out.extend(["(речь не распознана)", ""])
    return "\n".join(out)


def build(transcript_path, raw_folder, log=print, slides_md=None, slides_found=()):
    """Writes notes_input/ next to transcript.json (+ slides.md when course materials
    matched). Returns its path."""
    transcript_path = Path(transcript_path)
    transcript = json.loads(transcript_path.read_text(encoding="utf-8"))
    started = datetime.fromisoformat(transcript["started_at"]) if transcript.get("started_at") else None
    out = transcript_path.parent / OUT
    tmp = transcript_path.parent / (OUT + ".tmp")
    shutil.rmtree(tmp, ignore_errors=True)
    (tmp / "photos").mkdir(parents=True)

    photos = [p for p in transcript["photos"] if not p["obsolete"]]
    names, listed = {}, []
    for i, ph in enumerate(photos, 1):
        names[i] = _photo_name(i, ph, started)
        src = Path(raw_folder) / ph["file"]
        if src.is_file():
            _save_photo(src, tmp / "photos" / names[i])
        else:
            log(f"  нет файла {ph['file']}")
        listed.append({"n": i, "file": f"photos/{names[i]}", "original": ph["file"], "type": ph["type"],
                       "redraw": bool(ph.get("redraw")), "time": _photo_clock(ph, started), "t": ph["t"]})

    text = render(transcript, names)
    (tmp / "transcript.md").write_text(text, encoding="utf-8")
    if slides_md:
        (tmp / "slides.md").write_text(slides_md, encoding="utf-8")
    # Notes record the version they were written from; a later sync that changes the input
    # (more audio, other photo marks, new names) makes the note "changed" in `status`.
    version = hashlib.sha256((text + json.dumps([p["original"] for p in listed])).encode()).hexdigest()[:12]
    audio = sum(s["length"] or 0 for s in transcript["segments"])
    info = {
        "folder": transcript["folder"],
        "source": str(Path(raw_folder).resolve()),
        "subject": transcript["subject"],
        "title": transcript["title"],
        "kind": transcript["kind"],
        "part": transcript.get("part", 1),
        "ended": transcript["ended"],
        "date": started.strftime("%Y-%m-%d") if started else None,
        "start": started.strftime("%H:%M") if started else None,
        "audio_seconds": round(audio),
        "segments": len(transcript["segments"]),
        "photos": listed,
        "obsolete_photos": len(transcript["photos"]) - len(photos),
        "notes": len(transcript.get("notes", [])),
        "marks": len(transcript.get("marks", [])),
        "asr_model": transcript["model"],
        "input_version": version,
        "slides": list(slides_found),
        "problems": transcript["problems"],
    }
    (tmp / "info.json").write_text(json.dumps(info, ensure_ascii=False, indent=1), encoding="utf-8")
    if out.exists():
        shutil.rmtree(out)
    os.replace(tmp, out)
    return out
