"""Client tests against a fake phone speaking the same protocol (stdlib only).

Run: python -m unittest discover -s tests
"""
import hashlib
import json
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from lecture_sync.client import Cancelled, Events, SyncError, Syncer  # noqa: E402
from lecture_sync.config import Config  # noqa: E402

FOLDER = "2026-10-15_1015_Сопромат_Лекция-1"


class FakePhone:
    """Serves `files` {path: bytes}. `drop_after` cuts the first download of a path after N bytes."""

    def __init__(self, files, ended=True, created="2026-10-15T10:15:00+03:00"):
        self.files = dict(files)
        self.folder = FOLDER
        self.ended, self.created = ended, created
        self.token = "t0ken"
        self.code = "123456"
        self.drop_after = {}
        self.ranges = []
        self.acks = []
        self.done = 0
        phone = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _json(self, obj, code=200):
                body = json.dumps(obj, ensure_ascii=False).encode()
                self.send_response(code)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _authorized(self):
                if self.headers.get("Authorization") != f"Bearer {phone.token}":
                    self._json({"error": "auth"}, 401)
                    return False
                return True

            def do_GET(self):
                url = urlparse(self.path)
                if url.path == "/hello":
                    return self._json({"device": "fake", "port": self.server.server_port, "paired": True})
                if not self._authorized():
                    return
                if url.path == "/manifest":
                    files = [{"path": p, "size": len(b), "sha256": hashlib.sha256(b).hexdigest()} for p, b in phone.files.items()]
                    return self._json({"v": 1, "lectures": [{"folder": phone.folder, "ended": phone.ended, "created": phone.created, "files": files}]})
                if url.path == "/file":
                    path = parse_qs(url.query)["path"][0]
                    data = phone.files[path]
                    start = 0
                    rng = self.headers.get("Range")
                    if rng:
                        start = int(rng.split("=")[1].rstrip("-"))
                        phone.ranges.append((path, start))
                    chunk = data[start:]
                    self.send_response(206 if rng else 200)
                    self.send_header("Content-Length", str(len(chunk)))
                    self.end_headers()
                    cut = phone.drop_after.pop(path, None)
                    if cut is not None:
                        self.wfile.write(chunk[:cut])
                        self.wfile.flush()
                        self.connection.shutdown(2)  # connection lost mid-file
                        return
                    self.wfile.write(chunk)

            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(length) or b"{}")
                if self.path == "/pair":
                    if body.get("code") != phone.code:
                        return self._json({"error": "code"}, 403)
                    return self._json({"token": phone.token})
                if not self._authorized():
                    return
                if self.path == "/ack":
                    phone.acks.append(body)
                elif self.path == "/done":
                    phone.done += 1
                self._json({"ok": True})

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    @property
    def port(self):
        return self.server.server_port

    def close(self):
        self.server.shutdown()


class CodeEvents(Events):
    def __init__(self, code="123456"):
        self.code = code
        self.asked = 0
        self.lines = []

    def ask_code(self):
        self.asked += 1
        return self.code

    def log(self, text):
        self.lines.append(text)


class SyncerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.audio = bytes(range(256)) * 4000  # ~1 MB
        self.phone = FakePhone({
            f"{FOLDER}/events.jsonl": b'{"e":"lecture_start"}\n',
            f"{FOLDER}/lecture.json": json.dumps({"created": "2026-10-15T10:15:00+03:00"}).encode(),
            f"{FOLDER}/audio/001.ogg": self.audio,
        })
        self.config = Config(self.tmp / "config.json")
        self.config.update(inbox=str(self.tmp / "inbox"), host="127.0.0.1", port=self.phone.port)

    def tearDown(self):
        self.phone.close()

    def run_sync(self, events=None, **kw):
        return Syncer(self.config, events or CodeEvents(), retries=3, **kw).run()

    def test_first_sync_pairs_downloads_acks_and_finishes(self):
        events = CodeEvents()
        report = self.run_sync(events)
        self.assertEqual(1, events.asked)
        self.assertEqual("t0ken", Config(self.tmp / "config.json")["token"], "token saved")
        self.assertEqual(self.audio, (self.tmp / "inbox" / FOLDER / "audio/001.ogg").read_bytes())
        self.assertEqual([FOLDER], report.complete_lectures)
        self.assertEqual(1, len(self.phone.acks))
        self.assertEqual(1, self.phone.done)

    def test_second_sync_downloads_nothing_new(self):
        self.run_sync()
        events = CodeEvents()
        report = self.run_sync(events)
        self.assertEqual(0, events.asked, "already paired")
        self.assertEqual([], report.downloaded)
        self.assertEqual(3, report.skipped)

    def test_dropped_connection_resumes_from_the_part_file(self):
        self.phone.drop_after[f"{FOLDER}/audio/001.ogg"] = 300_000
        events = CodeEvents()
        self.run_sync(events)
        self.assertEqual(self.audio, (self.tmp / "inbox" / FOLDER / "audio/001.ogg").read_bytes())
        resumed = [start for path, start in self.phone.ranges if path.endswith("001.ogg")]
        self.assertTrue(resumed and resumed[0] >= 65536, f"resumed with Range, not from zero: {resumed}")
        self.assertFalse(list((self.tmp / "inbox").rglob("*.part")))

    def test_grown_file_is_downloaded_again(self):
        self.run_sync()
        self.phone.files[f"{FOLDER}/events.jsonl"] += b'{"e":"lecture_end"}\n'
        report = self.run_sync()
        self.assertEqual([f"{FOLDER}/events.jsonl"], report.downloaded)

    def test_folder_renamed_on_the_phone_is_followed(self):
        self.run_sync()
        renamed = FOLDER.replace("Лекция-1", "Семинар-1")
        self.phone.files = {p.replace(FOLDER, renamed): b for p, b in self.phone.files.items()}
        self.phone.folder = renamed
        report = self.run_sync()
        inbox = self.tmp / "inbox"
        self.assertTrue((inbox / renamed / "audio/001.ogg").exists())
        self.assertFalse((inbox / FOLDER).exists())
        self.assertEqual([], report.downloaded, "renamed, not downloaded again")

    def test_wrong_code_and_lost_pairing(self):
        with self.assertRaises(SyncError):
            self.run_sync(CodeEvents("000000"))
        self.run_sync()
        self.phone.token = "new-token"  # the phone forgot us (reinstall)
        self.phone.code = "654321"
        events = CodeEvents("654321")
        self.run_sync(events)
        self.assertEqual(1, events.asked)
        self.assertEqual("new-token", self.config["token"])

    def test_unreachable_phone_is_an_error_not_a_crash(self):
        self.config.update(host="127.0.0.1", port=1)
        import lecture_sync.client as client_module
        real = client_module.discover
        client_module.discover = lambda **kw: (None, None)
        try:
            with self.assertRaises(SyncError):
                self.run_sync()
        finally:
            client_module.discover = real

    def test_phone_missing_during_retry_does_not_abort(self):
        """Wi-Fi drops mid-file: rediscovery fails for a while, then the phone is back."""
        self.phone.drop_after[f"{FOLDER}/audio/001.ogg"] = 200_000
        import lecture_sync.client as client_module
        real_discover, real_hello = client_module.discover, client_module.Phone.hello
        calls = {"n": 0}

        def flaky_hello(phone_self):
            calls["n"] += 1
            if 2 <= calls["n"] <= 3:  # first rediscoveries: the phone is offline
                raise OSError("network unreachable")
            return real_hello(phone_self)

        client_module.discover = lambda **kw: (None, None)
        client_module.Phone.hello = flaky_hello
        try:
            self.run_sync()
        finally:
            client_module.discover, client_module.Phone.hello = real_discover, real_hello
        self.assertEqual(self.audio, (self.tmp / "inbox" / FOLDER / "audio/001.ogg").read_bytes())

    def test_cancel_stops_cleanly(self):
        cancel = threading.Event()
        cancel.set()
        with self.assertRaises(Cancelled):
            self.run_sync(cancel=cancel)


if __name__ == "__main__":
    unittest.main()
