"""What "Обработать" does, shared by the console and the window."""
from pathlib import Path

from lecture_sync.config import Config

from . import lecture as lec
from .package import build
from .transcribe import transcribe

DEFAULT_WORK = Path.home() / "Documents" / "Lectures_work"
DEFAULT_VAULT = Path.home() / "Documents" / "Конспекты"


def work_root(config: Config) -> Path:
    return Path(config.get("work") or DEFAULT_WORK)


def vault_root(config: Config) -> Path:
    """The Obsidian vault the notes go to (`vault` in the config)."""
    return Path(config.get("vault") or DEFAULT_VAULT)


def find_notes(vault: Path):
    """{lecture folder: (note path, input version)} from the notes' front matter."""
    from .vault import notes
    return {f["источник"]: (md, f.get("версия_входа", "")) for items in notes(vault).values() for md, f in items}


def status(config: Config):
    """Processed lectures and their notes: state is `new` (no note), `changed` (the note was
    written from an older input) or `done`. Oldest first."""
    import json
    notes = find_notes(vault_root(config))
    out = []
    work = work_root(config)
    if not work.is_dir():
        return out
    for d in sorted(work.iterdir()):
        info_path = d / "notes_input" / "info.json"
        if not info_path.is_file():
            continue
        info = json.loads(info_path.read_text(encoding="utf-8"))
        note, version = notes.get(info["folder"], (None, None))
        state = "new" if note is None else ("done" if version == info.get("input_version") else "changed")
        out.append({"folder": info["folder"], "subject": info["subject"], "title": info["title"],
                    "kind": info["kind"], "part": info.get("part", 1), "date": info["date"], "state": state,
                    "notes_input": str(d / "notes_input"), "note": str(note) if note else None})
    return out


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


def refresh_vault(config: Config):
    """Vault settings (once), a folder per subject, and the generated contents pages."""
    from . import vault
    from .transcribe import GLOSSARY_DIR
    subjects = sorted(p.stem for p in GLOSSARY_DIR.glob("*.txt"))
    root = vault_root(config)
    vault.setup(root, subjects)
    vault.write_indexes(root, subjects, [r for r in status(config) if r["state"] != "done"])
    return root


def course_slides(config: Config, transcript_path: Path, log=print):
    """slides.md for the notes step from the subject's PDFs in `<vault>/<subject>/Материалы/`:
    the sections this lecture covered, as text. (None, []) when there are none."""
    import json
    from . import slides
    from .transcribe import lecture_terms
    transcript = json.loads(Path(transcript_path).read_text(encoding="utf-8"))
    pdfs = slides.materials(vault_root(config), transcript["subject"])
    if not pdfs:
        return None, []
    terms = lecture_terms(Path(transcript_path).parent)
    parts, found = [], []
    for pdf in pdfs:
        data = slides.index(pdf, work_root(config) / "_sources")
        sections = slides.match_lecture(data, transcript["pieces"], terms)
        if sections:
            parts.append(f"# {pdf.name}\n\n" + slides.pages_text(data, sections))
            found += [{"pdf": pdf.name, "title": s["title"], "first": s["first"], "last": s["last"]} for s in sections]
            log(f"  сборник {pdf.name}: " + ", ".join(s["title"] for s in sections))
    if not parts:
        return None, []
    head = ("Текст слайдов из материалов курса, похожих на эту лекцию по её словам и терминам из тетради\n"
            "(подобраны автоматически, без модели: часть разделов может не относиться к делу).\n"
            "Ссылки [[файл.pdf#page=N]] открывают страницу в Obsidian.\n\n")
    return head + "\n".join(parts), found


def process(folder, config: Config, recognizer: LazyRecognizer, log=print) -> Path:
    """Speech → transcript.json → notes_input/ (+ slides.md from course materials). Returns
    the notes_input folder."""
    transcript = transcribe(folder, work_root(config), recognizer, log)
    md, sections = course_slides(config, transcript, log)
    return build(transcript, folder, log, slides_md=md, slides_found=sections)
