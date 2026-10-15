"""What "Обработать" does, shared by the console and the window."""
from pathlib import Path

from lecture_sync.config import Config

from . import lecture as lec
from .package import build
from .transcribe import transcribe

DEFAULT_WORK = Path.home() / "Documents" / "Lectures_work"


def work_root(config: Config) -> Path:
    return Path(config.get("work") or DEFAULT_WORK)


def lectures(config: Config, include_unfinished=False):
    """Lecture folders in the inbox, oldest first; unfinished ones only on request."""
    inbox = Path(config["inbox"])
    if not inbox.is_dir():
        return []
    dirs = sorted(d for d in inbox.iterdir() if d.is_dir() and (d / lec.EVENTS).is_file())
    return [d for d in dirs if include_unfinished or lec.load(d).ended]


class LazyRecognizer:
    """Loads the model on first use: a lecture whose audio is all cached never loads it."""

    def __init__(self, log=print):
        self.log, self.model = log, None

    def __call__(self):
        if self.model is None:
            from .asr import Recognizer
            self.log("  загрузка модели распознавания…")
            self.model = Recognizer()
        return self.model


def process(folder, config: Config, recognizer: LazyRecognizer, log=print) -> Path:
    """Speech → transcript.json → notes_input/. Returns the notes_input folder."""
    transcript = transcribe(folder, work_root(config), recognizer, log)
    return build(transcript, folder, log)
