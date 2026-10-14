"""Settings of the laptop side: where lectures go, the phone's last address, the pairing token."""
import json
import os
from pathlib import Path

CONFIG_DIR = Path(os.environ.get("APPDATA", Path.home())) / "LectureDesktop"
DEFAULT_INBOX = Path.home() / "Documents" / "Lectures"


class Config(dict):
    def __init__(self, path=None):
        super().__init__()
        self.path = Path(path) if path else CONFIG_DIR / "config.json"
        try:
            self.update(json.loads(self.path.read_text(encoding="utf-8")))
        except (OSError, ValueError):
            pass
        self.setdefault("inbox", str(DEFAULT_INBOX))
        self.setdefault("host", None)
        self.setdefault("port", None)
        self.setdefault("token", None)

    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(dict(self), ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(tmp, self.path)
