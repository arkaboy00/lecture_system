"""Pulls lecture folders from the phone (Lecture Capture sync protocol v1).

The phone serves while its sync is on; this side finds it, pairs once with a 6-digit code,
downloads what is missing or changed, verifies SHA-256, confirms each complete lecture and
tells the phone it is done. Every network step is retried; an interrupted download resumes
from the bytes already on disk (`.part`).
"""
import hashlib
import http.client
import json
import os
import socket
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

DISCOVERY_PORT = 47811
HTTP_PORT = 47812
DISCOVER_MAGIC = b"LECTURE-SYNC-DISCOVER 1"
CHUNK = 1 << 16


class SyncError(Exception):
    pass


class Cancelled(SyncError):
    pass


class PhoneNotFound(SyncError):
    """Retryable inside a run: the phone may be reconnecting to Wi-Fi."""


def _local_prefixes():
    try:
        ips = [ip for ip in socket.gethostbyname_ex(socket.gethostname())[2] if not ip.startswith("127.")]
    except OSError:
        ips = []
    return " или ".join(sorted({ip.rsplit(".", 1)[0] + "." for ip in ips})) or "адреса ноутбука"


@dataclass
class Report:
    downloaded: list = field(default_factory=list)
    skipped: int = 0
    bytes: int = 0
    complete_lectures: list = field(default_factory=list)
    incomplete_lectures: list = field(default_factory=list)


class Events:
    """Callbacks to the UI; all optional."""

    def status(self, text): pass
    def progress(self, done_bytes, total_bytes): pass
    def log(self, text): pass
    def ask_code(self):
        """Return the 6-digit code shown on the phone, or None to give up."""
        return None


def discover(timeout=1.5, attempts=3):
    """Broadcasts the magic word; returns (host, hello dict) of the first phone that answers."""
    targets = {"255.255.255.255"}
    try:
        for ip in socket.gethostbyname_ex(socket.gethostname())[2]:
            if not ip.startswith("127."):
                targets.add(ip.rsplit(".", 1)[0] + ".255")
    except OSError:
        pass
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.settimeout(timeout)
        for _ in range(attempts):
            for t in targets:
                try:
                    s.sendto(DISCOVER_MAGIC, (t, DISCOVERY_PORT))
                except OSError:
                    pass
            try:
                data, (host, _) = s.recvfrom(1024)
                return host, json.loads(data.decode("utf-8"))
            except (socket.timeout, ValueError):
                continue
    return None, None


class Phone:
    def __init__(self, host, port, token=None, timeout=20):
        self.host, self.port, self.token, self.timeout = host, port, token, timeout

    def _url(self, path):
        return f"http://{self.host}:{self.port}{path}"

    def _request(self, method, path, body=None, headers=None):
        data = json.dumps(body).encode("utf-8") if body is not None else (b"" if method == "POST" else None)
        req = urllib.request.Request(self._url(path), data=data, method=method)
        if self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        return urllib.request.urlopen(req, timeout=self.timeout)

    def json(self, method, path, body=None):
        with self._request(method, path, body) as r:
            return json.loads(r.read().decode("utf-8"))

    def hello(self):
        return self.json("GET", "/hello")

    def pair(self, code, client):
        return self.json("POST", "/pair", {"code": code, "client": client})["token"]

    def open_file(self, path, offset=0):
        headers = {"Range": f"bytes={offset}-"} if offset else {}
        return self._request("GET", "/file?path=" + urllib.parse.quote(path, safe=""), headers=headers)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


class Syncer:
    """One sync run. `cancel` (threading.Event) stops it between chunks."""

    # 8 attempts with 1-2-4-8-10-10-10 s pauses plus discovery: about a minute of patience,
    # enough for a phone to reconnect to Wi-Fi.
    def __init__(self, config, events=None, cancel=None, retries=8, client_name=None):
        self.config = config
        self.events = events or Events()
        self.cancel = cancel or threading.Event()
        self.retries = retries
        self.client_name = client_name or socket.gethostname()
        self.phone = None

    # --- plumbing ---------------------------------------------------------------------------

    def _check_cancel(self):
        if self.cancel.is_set():
            raise Cancelled("остановлено")

    def _retry(self, what, action):
        """Runs action(); on network errors waits, re-finds the phone and tries again."""
        delay = 1
        for attempt in range(1, self.retries + 1):
            self._check_cancel()
            try:
                return action()
            except urllib.error.HTTPError:
                raise
            except PhoneNotFound:
                if attempt >= min(self.retries, 3):  # the first search gives up sooner
                    raise
                self.events.log("Телефон пока не отвечает, ищу ещё…")
                continue
            except (urllib.error.URLError, OSError, TimeoutError, http.client.HTTPException) as e:
                if attempt == self.retries:
                    raise SyncError(f"{what}: нет связи с телефоном ({e})")
                self.events.log(f"{what}: обрыв связи ({e}), повтор через {delay} с")
                for _ in range(delay * 10):
                    self._check_cancel()
                    time.sleep(0.1)
                delay = min(delay * 2, 10)
                try:
                    self._locate(quiet=True)
                except PhoneNotFound:
                    pass  # phone still offline (Wi-Fi reconnecting): keep retrying

    def _locate(self, quiet=False):
        cfg = self.config
        if cfg.get("host"):
            try:
                hello = Phone(cfg["host"], cfg.get("port", HTTP_PORT), timeout=3).hello()
                self.phone = Phone(cfg["host"], hello.get("port", HTTP_PORT), cfg.get("token"))
                return hello
            except (urllib.error.URLError, OSError, ValueError):
                pass
        if not quiet:
            self.events.status("Ищу телефон в сети…")
        host, hello = discover()
        if not host:
            raise PhoneNotFound(
                "Телефон не найден. Включи синхронизацию в приложении на телефоне и проверь, что он "
                f"в той же сети Wi-Fi: адрес на экране телефона должен начинаться с {_local_prefixes()} "
                "(телефон мог сам переключиться на другую сеть, например гостевую).")
        cfg["host"], cfg["port"] = host, hello.get("port", HTTP_PORT)
        self.phone = Phone(host, cfg["port"], cfg.get("token"))
        return hello

    # --- the run ----------------------------------------------------------------------------

    def run(self):
        cfg = self.config
        inbox = Path(cfg["inbox"])
        inbox.mkdir(parents=True, exist_ok=True)
        hello = self._retry("поиск телефона", self._locate)
        self.events.log(f"Телефон: {hello.get('device')} ({cfg['host']}:{cfg['port']})")

        manifest = None
        for _ in range(2):
            if not cfg.get("token"):
                self._pair()
            try:
                manifest = self._retry("список файлов", lambda: self.phone.json("GET", "/manifest"))
                break
            except urllib.error.HTTPError as e:
                if e.code != 401:
                    raise SyncError(f"телефон ответил {e.code}")
                self.events.log("Телефон не узнал ноутбук — нужно сопряжение заново")
                cfg["token"] = None
        if manifest is None:
            raise SyncError("не удалось сопрячь")
        self.config.save()

        report = Report()
        index = _Index(inbox)
        todo = []
        for lecture in manifest["lectures"]:
            self._adopt_renamed(inbox, lecture)
            for f in lecture["files"]:
                if index.matches(f):
                    report.skipped += 1
                else:
                    todo.append((lecture, f))
        total = sum(f["size"] for _, f in todo)
        self.events.status(f"Нужно скачать: {len(todo)} файлов, {total / 1048576:.1f} МБ")
        self.events.progress(0, total)

        done_bytes = 0
        bad = set()
        for lecture, f in todo:
            got = self._download(inbox, f, lambda n: self.events.progress(done_bytes + n, total))
            done_bytes += f["size"]
            if got:
                index.remember(f)
                report.downloaded.append(f["path"])
                report.bytes += f["size"]
            else:
                bad.add(lecture["folder"])
        index.save()

        for lecture in manifest["lectures"]:
            folder = lecture["folder"]
            if folder in bad:
                report.incomplete_lectures.append(folder)
                continue
            self._retry("подтверждение", lambda: self.phone.json("POST", "/ack", {"folder": folder, "files": lecture["files"]}))
            (report.complete_lectures if lecture["ended"] else report.incomplete_lectures).append(folder)
        self._retry("завершение", lambda: self.phone.json("POST", "/done"))
        return report

    def _pair(self):
        code = self.events.ask_code()
        if not code:
            raise SyncError("сопряжение отменено")
        try:
            token = self._retry("сопряжение", lambda: self.phone.pair(code.strip(), self.client_name))
        except urllib.error.HTTPError as e:
            raise SyncError("неверный код" if e.code == 403 else f"сопряжение: телефон ответил {e.code}")
        self.config["token"] = token
        self.phone.token = token
        self.config.save()
        self.events.log("Сопряжение выполнено")

    def _adopt_renamed(self, inbox, lecture):
        """The phone renames a folder at lecture_end when the title changed: follow it locally."""
        target = inbox / lecture["folder"]
        if target.exists() or not lecture.get("created"):
            return
        for d in inbox.iterdir():
            meta = d / "lecture.json"
            if d.is_dir() and meta.exists():
                try:
                    if json.loads(meta.read_text(encoding="utf-8")).get("created") == lecture["created"]:
                        d.rename(target)
                        self.events.log(f"Папка переименована на телефоне: {d.name} → {target.name}")
                        return
                except (ValueError, OSError):
                    continue

    def _download(self, inbox, f, on_bytes):
        """Downloads one file with resume and SHA-256 check. False if it changed meanwhile."""
        dest = inbox / f["path"]
        part = dest.with_name(dest.name + ".part")
        dest.parent.mkdir(parents=True, exist_ok=True)
        for attempt in range(2):
            def fetch():
                offset = part.stat().st_size if part.exists() else 0
                if offset > f["size"]:
                    part.unlink()
                    offset = 0
                if offset == f["size"]:
                    return
                with self.phone.open_file(f["path"], offset) as r, open(part, "ab" if r.status == 206 else "wb") as out:
                    written = offset if r.status == 206 else 0
                    while True:
                        self._check_cancel()
                        block = r.read(CHUNK)
                        if not block:
                            break
                        out.write(block)
                        written += len(block)
                        on_bytes(min(written, f["size"]))
                if written < f["size"]:
                    # The connection closed early: retry, resuming from what is in .part.
                    raise ConnectionError(f"обрыв на {written} из {f['size']} байт")
            try:
                self._retry(f["path"], fetch)
            except urllib.error.HTTPError as e:
                if e.code == 416:  # the .part is already complete
                    pass
                elif e.code == 404:
                    self.events.log(f"{f['path']}: исчез на телефоне")
                    return False
                else:
                    raise SyncError(f"{f['path']}: телефон ответил {e.code}")
            if sha256_of(part) == f["sha256"]:
                os.replace(part, dest)
                return True
            # Changed after the manifest (a lecture still recording) or corrupted: start over once.
            part.unlink()
        self.events.log(f"{f['path']}: изменился во время синхронизации, заберу в следующий раз")
        return False


class _Index:
    """Remembers verified files (size, mtime, sha) so unchanged files are not re-hashed."""

    def __init__(self, inbox):
        self.path = inbox / ".sync_index.json"
        try:
            self.data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.data = {}
        self.inbox = inbox

    def matches(self, f):
        local = self.inbox / f["path"]
        if not local.exists() or local.stat().st_size != f["size"]:
            return False
        entry = self.data.get(f["path"])
        st = local.stat()
        if entry and entry["size"] == st.st_size and entry["mtime"] == st.st_mtime:
            return entry["sha256"] == f["sha256"]
        same = sha256_of(local) == f["sha256"]
        if same:
            self.remember(f)
        return same

    def remember(self, f):
        st = (self.inbox / f["path"]).stat()
        self.data[f["path"]] = {"size": st.st_size, "mtime": st.st_mtime, "sha256": f["sha256"]}

    def save(self):
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data, ensure_ascii=False, indent=1), encoding="utf-8")
        os.replace(tmp, self.path)
