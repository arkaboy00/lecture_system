"""python -m lecture_sync            → window
python -m lecture_sync --cli [--code 123456] [--inbox DIR] [--config FILE]   → console run
"""
import argparse
import sys

from .client import Cancelled, Events, SyncError, Syncer
from .config import Config


class ConsoleEvents(Events):
    def __init__(self, code):
        self.code = code
        self._last = -1

    def status(self, text):
        print(f"== {text}", flush=True)

    def log(self, text):
        print(f"   {text}", flush=True)

    def progress(self, done, total):
        pct = int(done * 100 / total) if total else 100
        if pct // 10 != self._last // 10:
            self._last = pct
            print(f"   {pct}%", flush=True)

    def ask_code(self):
        return self.code or input("Код с телефона: ")


def main(argv=None):
    p = argparse.ArgumentParser(prog="lecture_sync")
    p.add_argument("--cli", action="store_true")
    p.add_argument("--code")
    p.add_argument("--inbox")
    p.add_argument("--config")
    args = p.parse_args(argv)
    config = Config(args.config)
    if args.inbox:
        config["inbox"] = args.inbox
    if not args.cli:
        from .gui import run_gui
        return run_gui(config)
    try:
        report = Syncer(config, ConsoleEvents(args.code)).run()
    except Cancelled:
        print("== остановлено")
        return 1
    except SyncError as e:
        print(f"== ошибка: {e}")
        return 1
    print(f"== готово: скачано {len(report.downloaded)} файлов ({report.bytes / 1048576:.1f} МБ), "
          f"без изменений {report.skipped}")
    print(f"   завершённые лекции: {report.complete_lectures}")
    if report.incomplete_lectures:
        print(f"   ещё идут / не полностью: {report.incomplete_lectures}")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
