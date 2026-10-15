"""Validate a lecture folder pulled from the phone.

Usage: python tools/check_lecture.py <lecture_dir> [<lecture_dir> ...]

Checks lecture.json, every line of events.jsonl (envelope, known types, seq order),
references between events and files, and audio files with ffprobe when it is available.
Exit code 1 if any folder has errors. Warnings (e.g. a torn last line after a crash)
do not fail the check.
"""
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

TS_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$")

# Required body fields per event type.
EVENT_FIELDS = {
    "lecture_start": {"v", "subject", "title"},
    "meta": set(),
    "audio_start": {"file"},
    "audio_stop": {"file", "reason"},
    "audio_silenced": {"file"},
    "audio_unsilenced": {"file"},
    "photo": {"file", "type", "batch"},
    "obsolete": {"file"},
    "restored": {"file"},
    "retype": {"file", "type"},
    "redraw": {"file", "on"},
    "note": {"text"},
    "note_removed": {"note"},
    "mark": set(),
    "unmark": {"mark"},
    "recovered": set(),
    "lecture_end": set(),
}
# Edits the archive screen may append to a finished lecture.
LATER_EDITS = {"meta", "obsolete", "restored", "retype", "redraw", "note_removed", "unmark"}

ENUMS = {
    "reason": {"user", "error", "recovered"},
    "type": {"board", "notebook", "slide"},
    "kind": {"lecture", "seminar"},
}


def find_ffprobe():
    found = shutil.which("ffprobe")
    if found:
        return found
    packages = Path(os.environ.get("LOCALAPPDATA", "")) / "Microsoft" / "WinGet" / "Packages"
    for candidate in packages.glob("Gyan.FFmpeg*/*/bin/ffprobe.exe"):
        return str(candidate)
    return None


def probe_audio(ffprobe, path):
    """Returns (duration_seconds, description) or (None, error)."""
    result = subprocess.run(
        [ffprobe, "-v", "error", "-show_entries", "format=duration:stream=codec_name,sample_rate,channels",
         "-of", "json", str(path)],
        capture_output=True, text=True, encoding="utf-8",
    )
    if result.returncode != 0:
        return None, result.stderr.strip() or f"ffprobe exit {result.returncode}"
    info = json.loads(result.stdout or "{}")
    streams = info.get("streams", [])
    if not streams:
        return None, "no audio stream"
    duration = info.get("format", {}).get("duration")
    if duration is None:
        return None, "unknown duration"
    desc = ", ".join(f"{s.get('codec_name')} {s.get('sample_rate')}Hz x{s.get('channels')}" for s in streams)
    return float(duration), desc


def volume(ffprobe, path):
    """(mean_db, max_db) via ffmpeg volumedetect; decoding the whole file also proves it is readable."""
    ffmpeg = str(Path(ffprobe).with_name("ffmpeg" + Path(ffprobe).suffix))
    result = subprocess.run(
        [ffmpeg, "-hide_banner", "-nostats", "-i", str(path), "-af", "volumedetect", "-f", "null", "-"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    mean = re.search(r"mean_volume: (-?[\d.]+|-inf) dB", result.stderr)
    peak = re.search(r"max_volume: (-?[\d.]+|-inf) dB", result.stderr)
    if not mean or not peak:
        return None
    to_float = lambda m: float("-inf") if m.group(1) == "-inf" else float(m.group(1))
    return to_float(mean), to_float(peak)


class Report:
    def __init__(self, name):
        self.name = name
        self.errors, self.warnings, self.info = [], [], []

    def error(self, msg): self.errors.append(msg)
    def warn(self, msg): self.warnings.append(msg)
    def note(self, msg): self.info.append(msg)

    def print(self):
        status = "FAIL" if self.errors else "OK"
        print(f"== {self.name}: {status} ({len(self.errors)} errors, {len(self.warnings)} warnings)")
        for m in self.errors: print(f"  ERROR {m}")
        for m in self.warnings: print(f"  warn  {m}")
        for m in self.info: print(f"  info  {m}")


def check_meta(folder, rep):
    path = folder / "lecture.json"
    if not path.exists():
        rep.error("lecture.json missing")
        return None
    try:
        meta = json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:
        rep.error(f"lecture.json unreadable: {e}")
        return None
    for key in ("v", "subject", "title", "kind", "created"):
        if key not in meta:
            rep.error(f"lecture.json: no '{key}'")
    if meta.get("kind") not in ENUMS["kind"]:
        rep.error(f"lecture.json: bad kind {meta.get('kind')!r}")
    if (folder / "lecture.json.tmp").exists():
        rep.warn("lecture.json.tmp left over (crash during metadata write)")
    return meta


def read_events(folder, rep):
    path = folder / "events.jsonl"
    if not path.exists():
        rep.error("events.jsonl missing")
        return []
    raw = path.read_bytes()
    if raw and not raw.endswith(b"\n"):
        rep.warn("events.jsonl does not end with a newline (crash during write?)")
    lines = [(i + 1, l) for i, l in enumerate(raw.decode("utf-8").split("\n")) if l.strip()]
    parsed = []
    for n, line in lines:
        try:
            ev = json.loads(line)
            parsed.append((n, line, ev if isinstance(ev, dict) else None))
        except ValueError:
            parsed.append((n, line, None))
    events = []
    for idx, (n, line, ev) in enumerate(parsed):
        where = f"line {n}"
        if ev is None:
            # A torn line is expected after a crash: it is the last line, or the app wrote
            # `recovered` right after reopening. Anywhere else it is corruption.
            following = parsed[idx + 1][2] if idx + 1 < len(parsed) else None
            torn = following is None and idx == len(parsed) - 1 or (following or {}).get("e") == "recovered"
            (rep.warn if torn else rep.error)(f"{where}: unparsable{' (torn by a crash)' if torn else ''}: {line[:80]!r}")
            continue
        ev["_line"] = n
        check_envelope(ev, where, rep)
        events.append(ev)
    return events


def check_envelope(ev, where, rep):
    e = ev.get("e")
    if e not in EVENT_FIELDS:
        rep.error(f"{where}: unknown event {e!r}")
        return
    if list(ev.keys())[0] != "e":
        rep.warn(f"{where}: 'e' is not the first key")
    if not isinstance(ev.get("seq"), int):
        rep.error(f"{where}: bad seq {ev.get('seq')!r}")
    if not isinstance(ev.get("ts"), str) or not TS_RE.match(ev["ts"]):
        rep.error(f"{where}: bad ts {ev.get('ts')!r}")
    if not isinstance(ev.get("mono"), int):
        rep.error(f"{where}: bad mono {ev.get('mono')!r}")
    if "boot" in ev and not isinstance(ev["boot"], int):
        rep.error(f"{where}: bad boot {ev['boot']!r}")
    missing = EVENT_FIELDS[e] - ev.keys()
    if missing:
        rep.error(f"{where}: {e} without {sorted(missing)}")
    for key, allowed in ENUMS.items():
        if key in ev and ev[key] is not None and ev[key] not in allowed:
            rep.error(f"{where}: bad {key} {ev[key]!r}")


def check_sequence(events, rep):
    seqs = [ev["seq"] for ev in events if isinstance(ev.get("seq"), int)]
    for a, b in zip(seqs, seqs[1:]):
        if b != a + 1:
            rep.warn(f"seq jumps {a} -> {b}")
    if seqs and seqs[0] != 1:
        rep.error(f"first seq is {seqs[0]}, expected 1")
    if not events or events[0].get("e") != "lecture_start":
        rep.error("first event is not lecture_start")
    ends = [ev for ev in events if ev.get("e") == "lecture_end"]
    if len(ends) > 1:
        rep.error(f"{len(ends)} lecture_end events")
    if ends:
        after = [ev for ev in events[events.index(ends[-1]) + 1:] if ev.get("e") not in LATER_EDITS]
        if after:
            rep.error(f"events after lecture_end: {sorted({ev.get('e') for ev in after})}")
    return bool(ends)


def millis_between(a, b):
    if a.get("boot") == b.get("boot"):
        return b["mono"] - a["mono"]
    return (datetime.fromisoformat(b["ts"]) - datetime.fromisoformat(a["ts"])).total_seconds() * 1000


def check_audio(folder, events, ended, ffprobe, rep):
    open_segment, starts = None, {}
    for ev in events:
        e, f = ev.get("e"), ev.get("file")
        if e == "audio_start":
            if open_segment:
                rep.error(f"line {ev['_line']}: audio_start while {open_segment['file']} open")
            if f in starts:
                rep.error(f"line {ev['_line']}: {f} started twice")
            starts[f] = ev
            open_segment = ev
        elif e == "audio_stop":
            if not open_segment or open_segment["file"] != f:
                rep.error(f"line {ev['_line']}: audio_stop for {f} which is not open")
            else:
                starts[f]["_stop"] = ev
            open_segment = None
        elif e in ("audio_silenced", "audio_unsilenced") and f not in starts:
            rep.error(f"line {ev['_line']}: {e} for unknown {f}")
    if open_segment:
        (rep.error if ended else rep.warn)(f"{open_segment['file']} has no audio_stop")

    on_disk = {p.relative_to(folder).as_posix() for p in (folder / "audio").glob("*") if p.is_file()}
    for f in sorted(on_disk - starts.keys()):
        rep.warn(f"{f} on disk but not in events")
    for f, start in starts.items():
        path = folder / f
        if not path.exists():
            rep.error(f"{f} missing")
            continue
        size = path.stat().st_size
        logged = millis_between(start, start["_stop"]) / 1000 if "_stop" in start else None
        if not ffprobe:
            rep.note(f"{f}: {size} bytes (ffprobe not found)")
            continue
        duration, detail = probe_audio(ffprobe, path)
        if duration is None:
            rep.error(f"{f}: unreadable: {detail}")
            continue
        line = f"{f}: {duration:.1f}s, {size} bytes, {detail}"
        vol = volume(ffprobe, path)
        if vol is None:
            rep.warn(f"{f}: ffmpeg could not decode it for volumedetect")
        else:
            line += f", mean {vol[0]:.1f} dB, peak {vol[1]:.1f} dB"
            if vol[1] <= -90:
                rep.warn(f"{f}: digital silence (peak {vol[1]} dB) — microphone was silenced or dead")
        if logged is not None:
            line += f", log says {logged:.1f}s"
            if abs(duration - logged) > max(3.0, logged * 0.05):
                rep.warn(f"{f}: file {duration:.1f}s vs log {logged:.1f}s")
        rep.note(line)


def check_photos(folder, events, rep):
    photos = {}
    for ev in events:
        e, f = ev.get("e"), ev.get("file")
        if e == "photo":
            if f in photos:
                rep.error(f"line {ev['_line']}: duplicate photo {f}")
            photos[f] = ev
            if not (folder / f).exists():
                rep.error(f"{f} missing")
        elif e in ("obsolete", "restored", "retype", "redraw") and f not in photos:
            rep.error(f"line {ev['_line']}: {e} for unknown {f}")
    on_disk = {p.relative_to(folder).as_posix() for p in (folder / "photos").glob("*") if p.is_file()}
    for f in sorted(on_disk - photos.keys()):
        rep.warn(f"{f} on disk but not in events")
    pending = [p.name for p in (folder / ".pending").glob("*") if p.is_file()]
    if pending:
        rep.note(f".pending has {len(pending)} frames: {pending[:5]}")
    obsolete = sum(1 for ev in events if ev.get("e") == "obsolete") - sum(1 for ev in events if ev.get("e") == "restored")
    rep.note(f"photos: {len(photos)} (obsolete marks net {obsolete})")
    notes = {ev["seq"] for ev in events if ev.get("e") == "note"}
    marks = {ev["seq"] for ev in events if ev.get("e") == "mark"}
    for ev in events:
        if ev.get("e") == "note_removed" and ev.get("note") not in notes:
            rep.error(f"line {ev['_line']}: note_removed for unknown note {ev.get('note')}")
        if ev.get("e") == "unmark" and ev.get("mark") not in marks:
            rep.error(f"line {ev['_line']}: unmark for unknown mark {ev.get('mark')}")
    if notes or marks:
        rep.note(f"notes: {len(notes)}, marks: {len(marks)}")


def check_folder(folder, ffprobe):
    folder = Path(folder)
    rep = Report(folder.name)
    meta = check_meta(folder, rep)
    events = read_events(folder, rep)
    ended = check_sequence(events, rep)
    if meta is not None and bool(meta.get("ended")) != ended:
        rep.error(f"lecture.json ended={meta.get('ended')!r} but lecture_end {'present' if ended else 'absent'}")
    check_audio(folder, events, ended, ffprobe, rep)
    check_photos(folder, events, rep)
    kinds = {}
    for ev in events:
        kinds[ev.get("e")] = kinds.get(ev.get("e"), 0) + 1
    rep.note(f"{len(events)} events: {kinds}; ended={ended}")
    return rep


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    ffprobe = find_ffprobe()
    reports = [check_folder(p, ffprobe) for p in argv[1:]]
    for r in reports:
        r.print()
    return 1 if any(r.errors for r in reports) else 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main(sys.argv))
