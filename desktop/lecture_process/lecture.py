"""Reads a lecture folder written by the phone (see lecture_capture/docs/SPEC.md, section 4)
into one timeline: audio segments and photos with times in seconds on a common scale.

Times: `mono` (ms since boot) is exact within one boot, `ts` (wall clock, 1 s) is the only
link between boots. Every boot gets an anchor (the wall time of its first event minus its
mono), so a stamp maps to `anchor + mono`. Within a boot this is as exact as mono; across
reboots it is as good as the wall clock, which is all the phone can offer.
"""
import json
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path

EVENTS = "events.jsonl"
META = "lecture.json"


@dataclass
class Segment:
    file: str
    start: float           # seconds on the lecture timeline
    end: float | None      # None: no audio_stop (the recording was cut off)
    dur_ms: int | None     # measured from the file by the phone, when known
    reason: str | None = None
    silenced: list = field(default_factory=list)  # [(start, end|None)] on the timeline

    @property
    def length(self) -> float | None:
        if self.dur_ms is not None:
            return self.dur_ms / 1000
        return None if self.end is None else self.end - self.start


@dataclass
class Photo:
    file: str
    type: str              # board | notebook | slide (the latest retype wins)
    batch: int
    time: float            # shutter moment on the timeline
    ts: str = ""           # the phone's wall clock at the shutter (as in the file name)
    obsolete: bool = False
    redraw: bool = False   # the user asked for the drawings to be redrawn properly


@dataclass
class Note:
    seq: int
    time: float            # when the note field was opened on the phone
    ts: str
    text: str


@dataclass
class Mark:
    """A press of the "important" button; what was said just before it matters."""
    seq: int
    time: float
    ts: str


@dataclass
class Lecture:
    dir: Path
    subject: str
    title: str
    kind: str              # lecture | seminar
    ended: bool
    start: float           # timeline origin: lecture_start
    started_at: datetime | None
    segments: list
    photos: list
    problems: list         # unreadable lines and inconsistencies
    notes: list = field(default_factory=list)   # withdrawn ones are left out
    marks: list = field(default_factory=list)   # withdrawn ones are left out
    part: int = 1          # part of a lecture recorded in several folders (pairs with a break)

    @property
    def name(self):
        return self.dir.name

    def clock(self, t: float) -> datetime | None:
        """Wall-clock time of a timeline point (local time of the phone)."""
        if self.started_at is None:
            return None
        return self.started_at + timedelta(seconds=t - self.start)

    def relevant_photos(self):
        return [p for p in self.photos if not p.obsolete]


def read_events(path: Path):
    """Events in file order; a torn or broken line is skipped and reported."""
    events, problems = [], []
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as e:
        return [], [f"{path.name}: {e}"]
    for n, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        try:
            ev = json.loads(line)
            if not isinstance(ev, dict) or "e" not in ev:
                raise ValueError("no event type")
            events.append(ev)
        except ValueError as e:
            problems.append(f"line {n}: {e}")
    return events, problems


class _Clock:
    """`ts` is cut to whole seconds, so `ts - mono` of one event is up to 1 s below the true
    anchor. The largest value over the boot's events is the tightest estimate; values more
    than 2 s off the first one are wall-clock jumps (time sync) and are ignored."""

    def __init__(self, events):
        self.anchors = {}
        first = {}
        for ev in events:
            if "ts" not in ev or "mono" not in ev:
                continue
            boot, value = ev.get("boot"), _epoch(ev["ts"]) - ev["mono"] / 1000
            first.setdefault(boot, value)
            if abs(value - first[boot]) <= 2:
                self.anchors[boot] = max(self.anchors.get(boot, value), value)

    def __call__(self, ev) -> float:
        anchor = self.anchors.get(ev.get("boot"))
        if anchor is None or "mono" not in ev:
            return _epoch(ev["ts"])
        return anchor + ev["mono"] / 1000


def _epoch(ts: str) -> float:
    return datetime.fromisoformat(ts).timestamp()


def load(folder) -> Lecture:
    folder = Path(folder)
    events, problems = read_events(folder / EVENTS)
    try:
        meta = json.loads((folder / META).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        meta = {}
    at = _Clock(events)

    subject, title = meta.get("subject", ""), meta.get("title", "")
    kind = None  # the last of lecture_end / meta with a kind wins (later edits)
    start, started_at, ended = None, None, False
    segments: dict[str, Segment] = {}
    photos: dict[str, Photo] = {}
    notes: dict[int, Note] = {}
    marks: dict[int, Mark] = {}
    part = 1

    for ev in events:
        e, t = ev["e"], at(ev)
        if e == "lecture_start":
            if start is None:
                start, started_at = t, datetime.fromisoformat(ev["ts"])
            subject, title = ev.get("subject", subject), ev.get("title", title)
            part = ev.get("part") or 1
        elif e == "meta":
            subject = ev.get("subject") or subject
            title = ev.get("title") or title
            kind = ev.get("kind") or kind
        elif e == "audio_start":
            if ev["file"] in segments:
                problems.append(f"seq {ev.get('seq')}: audio file {ev['file']} reused")
            segments[ev["file"]] = Segment(ev["file"], t, None, None)
        elif e == "audio_stop":
            seg = segments.get(ev["file"])
            if seg is None:
                problems.append(f"seq {ev.get('seq')}: audio_stop for unknown {ev['file']}")
                continue
            seg.end, seg.dur_ms, seg.reason = t, ev.get("dur_ms"), ev.get("reason")
        elif e == "audio_silenced":
            seg = segments.get(ev["file"])
            if seg:
                seg.silenced.append((t, None))
        elif e == "audio_unsilenced":
            seg = segments.get(ev["file"])
            if seg and seg.silenced and seg.silenced[-1][1] is None:
                seg.silenced[-1] = (seg.silenced[-1][0], t)
        elif e == "photo":
            photos[ev["file"]] = Photo(ev["file"], ev.get("type", "board"), ev.get("batch", 0), t, ev.get("ts", ""))
        elif e in ("obsolete", "restored", "retype", "redraw"):
            photo = photos.get(ev["file"])
            if photo is None:
                problems.append(f"seq {ev.get('seq')}: {e} for unknown {ev['file']}")
            elif e == "retype":
                photo.type = ev.get("type", photo.type)
            elif e == "redraw":
                photo.redraw = bool(ev.get("on"))
            else:
                photo.obsolete = e == "obsolete"
        elif e == "note":
            notes[ev.get("seq")] = Note(ev.get("seq"), t, ev.get("ts", ""), ev.get("text", ""))
        elif e == "mark":
            marks[ev.get("seq")] = Mark(ev.get("seq"), t, ev.get("ts", ""))
        elif e in ("note_removed", "unmark"):
            target = notes if e == "note_removed" else marks
            if target.pop(ev.get("note" if e == "note_removed" else "mark"), None) is None:
                problems.append(f"seq {ev.get('seq')}: {e} for an unknown entry")
        elif e == "lecture_end":
            ended = True
            kind = ev.get("kind") or kind

    if start is None:
        problems.append("no lecture_start")
        start = min([s.start for s in segments.values()] + [p.time for p in photos.values()], default=0.0)
    return Lecture(
        dir=folder,
        subject=subject,
        title=title,
        kind=kind or meta.get("kind") or "lecture",
        ended=ended or bool(meta.get("ended")),
        start=start,
        started_at=started_at,
        segments=sorted(segments.values(), key=lambda s: s.start),
        photos=sorted(photos.values(), key=lambda p: p.time),
        problems=problems,
        notes=sorted(notes.values(), key=lambda n: n.time),
        marks=sorted(marks.values(), key=lambda m: m.time),
        part=part,
    )
