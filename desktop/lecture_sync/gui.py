"""Small window: one button to pull lectures from the phone. Placeholder UI; the processing
pipeline (speech recognition, notes) will be added to this app later.
The process lives only while the window is open."""
import os
import queue
import threading
import tkinter as tk
from tkinter import messagebox, simpledialog, ttk

from .client import Cancelled, Events, SyncError, Syncer


class GuiEvents(Events):
    """Called from the worker thread; everything goes through a queue to the Tk thread."""

    def __init__(self, app):
        self.app = app

    def status(self, text):
        self.app.post(lambda: self.app.status.set(text))

    def log(self, text):
        self.app.post(lambda: self.app.add_log(text))

    def progress(self, done, total):
        self.app.post(lambda: self.app.set_progress(done, total))

    def ask_code(self):
        answer = {}
        ready = threading.Event()

        def ask():
            answer["code"] = simpledialog.askstring(
                "Сопряжение", "Введи 6-значный код с экрана синхронизации на телефоне:", parent=self.app.root)
            ready.set()

        self.app.post(ask)
        ready.wait()
        return answer.get("code")


class App:
    def __init__(self, config):
        self.config = config
        self.root = tk.Tk()
        self.root.title("Лекции: синхронизация")
        self.root.geometry("560x420")
        self.queue = queue.Queue()
        self.cancel = threading.Event()
        self.worker = None

        frame = ttk.Frame(self.root, padding=12)
        frame.pack(fill="both", expand=True)
        self.status = tk.StringVar(value="Включи синхронизацию на телефоне и нажми кнопку.")
        ttk.Label(frame, textvariable=self.status, wraplength=520).pack(anchor="w")
        self.bar = ttk.Progressbar(frame, mode="determinate", maximum=1000)
        self.bar.pack(fill="x", pady=8)

        buttons = ttk.Frame(frame)
        buttons.pack(fill="x")
        self.start_button = ttk.Button(buttons, text="Синхронизировать", command=self.start)
        self.start_button.pack(side="left")
        self.stop_button = ttk.Button(buttons, text="Остановить", command=self.stop, state="disabled")
        self.stop_button.pack(side="left", padx=8)
        ttk.Button(buttons, text="Открыть папку", command=self.open_inbox).pack(side="right")

        ttk.Label(frame, text=f"Папка: {config['inbox']}", foreground="gray").pack(anchor="w", pady=(8, 0))
        self.log = tk.Text(frame, height=14, wrap="word", state="disabled")
        self.log.pack(fill="both", expand=True, pady=(4, 0))

        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(100, self.pump)

    def post(self, fn):
        self.queue.put(fn)

    def pump(self):
        while not self.queue.empty():
            self.queue.get()()
        self.root.after(100, self.pump)

    def add_log(self, text):
        self.log.configure(state="normal")
        self.log.insert("end", text + "\n")
        self.log.see("end")
        self.log.configure(state="disabled")

    def set_progress(self, done, total):
        self.bar["value"] = 1000 * done / total if total else 1000
        if total:
            self.status.set(f"Скачано {done / 1048576:.1f} из {total / 1048576:.1f} МБ")

    def start(self):
        self.cancel.clear()
        self.start_button.configure(state="disabled")
        self.stop_button.configure(state="normal")
        self.bar["value"] = 0
        self.worker = threading.Thread(target=self.work, daemon=True)
        self.worker.start()

    def work(self):
        events = GuiEvents(self)
        try:
            report = Syncer(self.config, events, self.cancel).run()
            text = (f"Готово: скачано {len(report.downloaded)} файлов ({report.bytes / 1048576:.1f} МБ), "
                    f"без изменений {report.skipped}. Завершённых лекций: {len(report.complete_lectures)}.")
            events.status(text)
            for folder in report.complete_lectures:
                events.log(f"✓ {folder}")
            for folder in report.incomplete_lectures:
                events.log(f"… {folder} (ещё идёт или не полностью)")
        except Cancelled:
            events.status("Остановлено. Скачанное сохранено, остальное докачается в следующий раз.")
        except SyncError as e:
            events.status(f"Ошибка: {e}")
        except Exception as e:  # keep the window alive on anything unexpected
            events.status(f"Неожиданная ошибка: {e!r}")
        finally:
            self.post(lambda: (self.start_button.configure(state="normal"), self.stop_button.configure(state="disabled")))

    def stop(self):
        self.cancel.set()

    def open_inbox(self):
        os.makedirs(self.config["inbox"], exist_ok=True)
        os.startfile(self.config["inbox"])

    def close(self):
        if self.worker and self.worker.is_alive():
            if not messagebox.askyesno("Синхронизация идёт", "Остановить и закрыть?"):
                return
            self.cancel.set()
        self.root.destroy()

    def run(self):
        self.root.mainloop()


def run_gui(config):
    App(config).run()
    return 0
