"""Console entry point of processing (the window comes later).

    python -m lecture_process transcribe [folder ...]   default: every finished lecture in the inbox
"""
import argparse
import sys
import time
from pathlib import Path

from lecture_sync.config import Config

from . import lecture as lec
from .transcribe import transcribe

DEFAULT_WORK = Path.home() / "Documents" / "Lectures_work"


def main(argv=None):
    parser = argparse.ArgumentParser(prog="lecture_process")
    sub = parser.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("transcribe", help="speech → transcript.json")
    t.add_argument("folders", nargs="*")
    t.add_argument("--config")
    t.add_argument("--all", action="store_true", help="also unfinished lectures")
    args = parser.parse_args(argv)

    config = Config(args.config)
    inbox = Path(config["inbox"])
    work = Path(config.get("work") or DEFAULT_WORK)
    folders = [Path(f) if Path(f).is_dir() else inbox / f for f in args.folders] or sorted(
        d for d in inbox.iterdir() if d.is_dir() and (d / lec.EVENTS).is_file())

    loaded = []

    def recognizer():
        if not loaded:
            from .asr import Recognizer
            print("  загрузка модели распознавания…")
            loaded.append(Recognizer())
        return loaded[0]

    for folder in folders:
        lecture = lec.load(folder)
        if not lecture.ended and not args.all and not args.folders:
            print(f"-- {folder.name}: не завершена, пропускаю")
            continue
        print(f"== {folder.name}")
        started = time.time()
        out = transcribe(folder, work, recognizer)
        print(f"  готово за {time.time() - started:.0f} с → {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
