"""Console entry point of processing.

    python -m lecture_process process [folder ...]      speech + notes_input/ (default command)
    python -m lecture_process transcribe [folder ...]   speech → transcript.json only
    python -m lecture_process status [--json]           processed lectures and their notes
    python -m lecture_process index                     rebuild the vault's contents pages

Without folders: every finished lecture in the inbox (--all: unfinished too).
"""
import argparse
import json
import sys
import time
from pathlib import Path

from lecture_sync.config import Config

from .pipeline import LazyRecognizer, lectures, process, refresh_vault, status, vault_root, work_root
from .transcribe import transcribe


def main(argv=None):
    parser = argparse.ArgumentParser(prog="lecture_process")
    parser.add_argument("cmd", nargs="?", choices=["process", "transcribe", "status", "index"], default="process")
    parser.add_argument("folders", nargs="*")
    parser.add_argument("--config")
    parser.add_argument("--all", action="store_true", help="also unfinished lectures")
    parser.add_argument("--json", action="store_true", help="status as JSON (for the notes skill)")
    args = parser.parse_args(argv)

    config = Config(args.config)
    if args.cmd == "index":
        print(f"оглавления обновлены: {refresh_vault(config)}")
        return 0
    if args.cmd == "status":
        rows = status(config)
        if args.json:
            print(json.dumps({"work": str(work_root(config)), "vault": str(vault_root(config)), "lectures": rows},
                             ensure_ascii=False, indent=1))
        else:
            print(f"хранилище: {vault_root(config)}")
            labels = {"new": "нет конспекта", "changed": "конспект устарел", "done": "готово"}
            for r in rows:
                print(f"  {labels[r['state']]:17s} {r['folder']}")
        return 0
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
    if args.cmd == "process" and folders:
        refresh_vault(config)
    return 0


if __name__ == "__main__":
    sys.exit(main())
