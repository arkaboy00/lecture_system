"""Console entry point of processing.

    python -m lecture_process process [folder ...]      speech + notes_input/ (default command)
    python -m lecture_process transcribe [folder ...]   speech → transcript.json only

Without folders: every finished lecture in the inbox (--all: unfinished too).
"""
import argparse
import sys
import time
from pathlib import Path

from lecture_sync.config import Config

from .pipeline import LazyRecognizer, lectures, process, work_root
from .transcribe import transcribe


def main(argv=None):
    parser = argparse.ArgumentParser(prog="lecture_process")
    parser.add_argument("cmd", nargs="?", choices=["process", "transcribe"], default="process")
    parser.add_argument("folders", nargs="*")
    parser.add_argument("--config")
    parser.add_argument("--all", action="store_true", help="also unfinished lectures")
    args = parser.parse_args(argv)

    config = Config(args.config)
    inbox = Path(config["inbox"])
    folders = [Path(f) if Path(f).is_dir() else inbox / f for f in args.folders] or lectures(config, args.all)
    recognizer = LazyRecognizer()
    for folder in folders:
        print(f"== {folder.name}")
        started = time.time()
        if args.cmd == "transcribe":
            out = transcribe(folder, work_root(config), recognizer)
        else:
            out = process(folder, config, recognizer)
        print(f"  готово за {time.time() - started:.0f} с → {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
