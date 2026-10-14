"""adb helper for automated checks on the test phone.

Usage: python tools/phone.py <command> [args]

  info                      model, Android version, MIUI/One UI version
  install [apk]             install the debug APK (default: app/build/outputs/apk/debug/app-debug.apk)
  grant                     grant runtime permissions and all-files access
  launch | stop             start the app / am force-stop
  tags                      list visible testTags (resource-ids) with their bounds
  tap <tag>                 tap the element with this testTag
  wait <tag> [seconds]      wait until the element appears
  text                      print visible texts
  pull [dest]               copy /sdcard/Lectures to dest (default: pulled/)
  ls                        list lecture folders on the phone
  log [lines]               app log lines from logcat

The same functions are importable from scenario scripts.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.arka.lecturecapture"
ROOT = Path(__file__).resolve().parent.parent
REMOTE_LECTURES = "/sdcard/Lectures"
ADB = str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe")
if not Path(ADB).exists():
    ADB = "adb"


def adb(*args, check=True, binary=False):
    result = subprocess.run([ADB, *args], capture_output=True)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.decode(errors='replace').strip()}")
    return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")


def shell(cmd, check=True):
    return adb("shell", cmd, check=check).strip()


def sdk_int():
    return int(shell("getprop ro.build.version.sdk"))


def info():
    props = {
        "model": "ro.product.model",
        "android": "ro.build.version.release",
        "sdk": "ro.build.version.sdk",
        "miui": "ro.miui.ui.version.name",
        "oneui": "ro.build.version.oneui",
    }
    return {k: shell(f"getprop {v}") for k, v in props.items()}


def install(apk=None):
    apk = apk or str(ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk")
    return adb("install", "-r", "-g", apk)


def grant():
    perms = ["android.permission.RECORD_AUDIO", "android.permission.CAMERA"]
    sdk = sdk_int()
    if sdk >= 33:
        perms.append("android.permission.POST_NOTIFICATIONS")
    if sdk <= 29:
        perms += ["android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE"]
    out = []
    for p in perms:
        out.append(f"{p}: {shell(f'pm grant {PACKAGE} {p}', check=False) or 'ok'}")
    if sdk >= 30:
        out.append(f"MANAGE_EXTERNAL_STORAGE: {shell(f'appops set --uid {PACKAGE} MANAGE_EXTERNAL_STORAGE allow', check=False) or 'ok'}")
    return "\n".join(out)


def launch():
    return shell(f"monkey -p {PACKAGE} -c android.intent.category.LAUNCHER 1")


def stop():
    return shell(f"am force-stop {PACKAGE}")


def dump():
    """Returns the UI hierarchy root; retries because uiautomator fails during animations."""
    last = ""
    for _ in range(10):
        result = subprocess.run([ADB, "shell", "uiautomator dump /sdcard/ui.xml"], capture_output=True)
        last = (result.stdout + result.stderr).decode(errors="replace").strip()
        if "dumped to" in last:
            xml = adb("exec-out", "cat", "/sdcard/ui.xml", binary=True)
            try:
                return ET.fromstring(xml)
            except ET.ParseError as e:
                last = f"bad xml: {e}"
        time.sleep(1.0)
    raise RuntimeError(f"uiautomator dump failed: {last!r}")


def _bounds(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return x1, y1, x2, y2


def nodes(root=None):
    root = root if root is not None else dump()
    return list(root.iter("node"))


def find(tag, root=None):
    for node in nodes(root):
        if node.get("resource-id") == tag:
            return node
    return None


def tags():
    return [(n.get("resource-id"), n.get("enabled"), n.get("bounds")) for n in nodes() if n.get("resource-id")]


def texts():
    return [n.get("text") or n.get("content-desc") for n in nodes() if n.get("text") or n.get("content-desc")]


def tap_xy(x, y):
    shell(f"input tap {x} {y}")


def tap(tag, timeout=5.0):
    node = wait(tag, timeout)
    x1, y1, x2, y2 = _bounds(node)
    if x2 <= x1 or y2 <= y1:
        raise RuntimeError(f"'{tag}' is off screen (bounds {node.get('bounds')})")
    tap_xy((x1 + x2) // 2, (y1 + y2) // 2)
    return node


def wait(tag, timeout=10.0):
    deadline = time.time() + timeout
    while True:
        node = find(tag)
        if node is not None:
            return node
        if time.time() > deadline:
            raise TimeoutError(f"'{tag}' not on screen; visible: {[t for t, _, _ in tags()]}")
        time.sleep(0.5)


def key(name):
    shell(f"input keyevent {name}")


def swipe(x1, y1, x2, y2, ms=300):
    shell(f"input swipe {x1} {y1} {x2} {y2} {ms}")


def pid():
    return shell(f"pidof {PACKAGE}", check=False) or None


def screen_on():
    return "mWakefulness=Awake" in shell("dumpsys power | grep mWakefulness=", check=False)


def keyguard_showing():
    out = shell("dumpsys window | grep -E 'mDreamingLockscreen|isStatusBarKeyguard|mShowingLockscreen'", check=False)
    return "mShowingLockscreen=true" in out or "isStatusBarKeyguard=true" in out


def kill_process():
    """SIGKILL the app process like the low-memory killer / MIUI does (debug builds only:
    DebugKillReceiver kills its own process). Unlike am force-stop, this lets the system
    restart a START_STICKY service. run-as cannot see app processes, so it is not used."""
    p = pid()
    if p:
        shell(f"am broadcast -n {PACKAGE}/.DebugKillReceiver", check=False)
    return p


def clear_log():
    adb("logcat", "-c")


def ls():
    return shell(f"ls -1 {REMOTE_LECTURES}", check=False)


def pull(dest=None):
    dest = Path(dest or ROOT / "pulled")
    dest.mkdir(parents=True, exist_ok=True)
    adb("pull", REMOTE_LECTURES, str(dest))
    return dest / "Lectures"


def log(lines=200):
    pid = shell(f"pidof {PACKAGE}", check=False)
    out = adb("logcat", "-d", "-t", str(lines), "-v", "time", "LectureCapture:V", "AndroidRuntime:E", "*:S")
    return (f"pid={pid or 'not running'}\n") + out


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd, args = argv[1], argv[2:]
    if cmd == "info":
        print(info())
    elif cmd == "install":
        print(install(*args))
    elif cmd == "grant":
        print(grant())
    elif cmd == "launch":
        print(launch())
    elif cmd == "stop":
        print(stop())
    elif cmd == "tags":
        for t in tags():
            print(*t)
    elif cmd == "tap":
        tap(args[0])
    elif cmd == "wait":
        wait(args[0], float(args[1]) if len(args) > 1 else 10.0)
    elif cmd == "text":
        print("\n".join(texts()))
    elif cmd == "pull":
        print(pull(*args))
    elif cmd == "ls":
        print(ls())
    elif cmd == "log":
        print(log(int(args[0]) if args else 200))
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main(sys.argv))
