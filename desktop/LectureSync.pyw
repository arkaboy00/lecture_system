"""Desktop entry point (pythonw: no console window)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from lecture_sync.__main__ import main  # noqa: E402

sys.exit(main([]))
