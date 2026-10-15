"""Desktop entry point (pythonw: no console window)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

# pythonw has no console: sys.stdout/stderr are None, and libraries that print progress
# (model download, CUDA warnings) would crash. Their output goes to a log file instead.
if sys.stdout is None or sys.stderr is None:
    from lecture_sync.config import CONFIG_DIR  # noqa: E402

    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    sys.stdout = sys.stderr = open(CONFIG_DIR / "window.log", "a", encoding="utf-8", buffering=1)

from lecture_sync.__main__ import main  # noqa: E402

sys.exit(main([]))
