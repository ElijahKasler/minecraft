#!/usr/bin/env python3
"""
Eaglercraft + MWC Proxy + Minecraft Server GUI Launcher

Starts / stops all three components from a single tkinter window:
  - Minecraft 1.8.8 server   (java -jar server.jar --nogui,  port 25565)
  - MWC proxy                (bun dist/app.js,                port 2344)
  - Eaglercraft desktop client  (gradlew target_lwjgl_desktop:eaglercraftDebugRuntime)

No external dependencies — uses only the Python standard library.
"""

import os
import sys
import subprocess
import threading
import queue
import tkinter as tk
from tkinter import ttk, scrolledtext, messagebox

ROOT = os.path.dirname(os.path.abspath(__file__))
SERVER_DIR = os.path.join(ROOT, "server")
PROXY_DIR = os.path.join(ROOT, "mwc-proxy")
EAGLER_DIR = os.path.join(ROOT, "eaglercraft")

SERVER_CMD = ["java", "-jar", "server.jar", "--nogui"]
PROXY_CMD = ["bun", "dist/app.js"]
EAGLER_CMD = ["gradlew", "target_lwjgl_desktop:eaglercraftDebugRuntime"]

ENV_SERVER = os.environ.copy()
ENV_PROXY = os.environ.copy()
ENV_PROXY["PORT"] = "2344"
ENV_PROXY["DISABLE_SIGNAL"] = "1"

PORT_MAP = {"server": 25565, "proxy": 2344}

COMPONENT_LABELS = {
    "server": "Minecraft 1.8.8 Server",
    "proxy": "MWC Proxy",
    "client": "Eaglercraft Desktop Client",
}

COMPONENT_PORTS = {
    "server": "25565",
    "proxy": "2344",
    "client": "foreground",
}


def free_port(port: int):
    """On Windows, find and kill any process already listening on *port*."""
    if sys.platform != "win32":
        return
    try:
        result = subprocess.run(
            ["netstat", "-ano"],
            capture_output=True, text=True, check=True,
        )
        pids = set()
        for line in result.stdout.splitlines():
            if f":{port} " in line or f":{port}\t" in line:
                parts = [p for p in line.split() if p]
                if len(parts) >= 5 and parts[1].endswith(f":{port}"):
                    pid = parts[-1]
                    if pid.isdigit() and pid != "0":
                        pids.add(int(pid))
        for pid in pids:
            try:
                subprocess.run(
                    ["taskkill", "/PID", str(pid), "/F"],
                    capture_output=True, check=False,
                )
                print(f"[port-cleanup] killed PID {pid} on port {port}")
            except Exception:
                pass
    except Exception:
        pass


class LauncherApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("Eaglercraft Launcher — Server / Proxy / Client")
        self.root.geometry("900x600")
        self.root.minsize(700, 450)

        self.procs = {"server": None, "proxy": None, "client": None}
        self._queues = {}
        self.threads = {}

        self._build_ui()
        self._poll_queues()

    # ---------- UI ----------
    def _build_ui(self):
        top = ttk.Frame(self.root, padding=8)
        top.pack(fill=tk.X)

        self.status_vars = {}
        self.status_labels = {}
        self.start_btns = {}
        self.stop_btns = {}

        comps = [
            ("server", COMPONENT_LABELS["server"], SERVER_DIR, COMPONENT_PORTS["server"]),
            ("proxy", COMPONENT_LABELS["proxy"], PROXY_DIR, COMPONENT_PORTS["proxy"]),
            ("client", COMPONENT_LABELS["client"], EAGLER_DIR, COMPONENT_PORTS["client"]),
        ]

        for key, label, cwd, port in comps:
            row = ttk.Frame(top)
            row.pack(fill=tk.X, pady=3)

            ttk.Label(row, text=label, width=34, anchor=tk.W).pack(side=tk.LEFT)

            sv = tk.StringVar(value="STOPPED")
            self.status_vars[key] = sv
            lbl = ttk.Label(row, textvariable=sv, width=10, anchor=tk.CENTER,
                            foreground="#888888")
            lbl.pack(side=tk.LEFT, padx=6)
            self.status_labels[key] = lbl

            sb = ttk.Button(row, text="Start", width=10,
                            command=lambda k=key: self.start_component(k))
            sb.pack(side=tk.LEFT, padx=3)
            self.start_btns[key] = sb

            eb = ttk.Button(row, text="Stop", width=10,
                            command=lambda k=key: self.stop_component(k),
                            state=tk.DISABLED)
            eb.pack(side=tk.LEFT, padx=3)
            self.stop_btns[key] = eb

        # Global buttons
        g = ttk.Frame(top)
        g.pack(fill=tk.X, pady=(10, 0))
        ttk.Button(g, text="Start All", width=14,
                   command=self.start_all).pack(side=tk.LEFT, padx=5)
        ttk.Button(g, text="Stop All", width=14,
                   command=self.stop_all).pack(side=tk.LEFT, padx=5)
        ttk.Button(g, text="Open Multiplayer Help", width=22,
                   command=self.show_help).pack(side=tk.LEFT, padx=5)

        # Separate log area per component
        nb = ttk.Notebook(self.root)
        nb.pack(fill=tk.BOTH, expand=True, padx=8, pady=8)

        self.log_texts = {}
        log_colors = {
            "server": "#1e3a3a",   # dark teal
            "proxy": "#3a1e1e",    # dark red
            "client": "#3a3a1e",   # dark olive
        }

        for key in ("server", "proxy", "client"):
            frame = ttk.Frame(nb)
            nb.add(frame, text=COMPONENT_LABELS[key])
            txt = scrolledtext.ScrolledText(frame, wrap=tk.WORD,
                                            font=("Consolas", 9),
                                            background="#111111",
                                            foreground="#dddddd",
                                            insertbackground="#dddddd")
            txt.pack(fill=tk.BOTH, expand=True, padx=4, pady=4)
            self.log_texts[key] = txt
            self._log(key, f"{COMPONENT_LABELS[key]} ready.\n")

    # ---------- logging ----------
    def _log(self, key: str, text: str):
        txt = self.log_texts[key]
        txt.insert(tk.END, text + "\n")
        txt.see(tk.END)

    def _log_proc(self, key: str, text: str):
        self._log(key, text)

    # ---------- queue polling ----------
    def _poll_queues(self):
        for key, q in self._queues.items():
            try:
                while True:
                    line = q.get_nowait()
                    self._log_proc(key, line.rstrip("\n"))
            except Exception:
                pass
        self.root.after(100, self._poll_queues)

    # ---------- helpers ----------
    def _is_running(self, key: str) -> bool:
        p = self.procs.get(key)
        return p is not None and p.poll() is None

    def _set_status(self, key: str, running: bool):
        sv = self.status_vars[key]
        sb = self.start_btns[key]
        eb = self.stop_btns[key]
        lbl = self.status_labels[key]
        if running:
            sv.set("RUNNING")
            sb.state(["disabled"])
            eb.state(["!disabled"])
            lbl.configure(foreground="#00aa00")
        else:
            sv.set("STOPPED")
            sb.state(["!disabled"])
            eb.state(["disabled"])
            lbl.configure(foreground="#888888")

    # ---------- component control ----------
    def start_component(self, key: str):
        if self._is_running(key):
            return

        if key == "server":
            cwd = SERVER_DIR
            cmd = SERVER_CMD
            env = ENV_SERVER
        elif key == "proxy":
            cwd = PROXY_DIR
            cmd = PROXY_CMD
            env = ENV_PROXY
        else:
            cwd = EAGLER_DIR
            cmd = EAGLER_CMD
            env = os.environ.copy()

        # Free the port if something is already bound to it.
        port = PORT_MAP.get(key)
        if port is not None:
            free_port(port)

        try:
            if sys.platform == "win32":
                # On Windows, .bat/.cmd files need shell=True or cmd /c.
                cmd_str = " ".join(f'"{c}"' if " " in c else c for c in cmd)
                proc = subprocess.Popen(
                    cmd_str,
                    cwd=cwd,
                    env=env,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    bufsize=1,
                    shell=True,
                )
            else:
                proc = subprocess.Popen(
                    cmd,
                    cwd=cwd,
                    env=env,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    bufsize=1,
                )
        except FileNotFoundError as e:
            missing = getattr(e, "filename", str(e))
            messagebox.showerror("Start failed",
                                 f"Could not start {key}:\n{missing}\n\n"
                                 f"Command: {' '.join(cmd)}\n"
                                 f"Working dir: {cwd}")
            return
        except Exception as e:
            messagebox.showerror("Start failed", str(e))
            return

        self.procs[key] = proc
        self._set_status(key, True)
        self._log_proc(key, f"started (pid {proc.pid}) in {cwd}")

        q = queue.Queue()
        self._queues[key] = q
        t = threading.Thread(target=self._reader, args=(key, proc, q),
                             daemon=True)
        self.threads[key] = t
        t.start()

        threading.Thread(target=self._watcher, args=(key, proc), daemon=True).start()

    def stop_component(self, key: str):
        proc = self.procs.get(key)
        if proc is None:
            return
        if proc.poll() is not None:
            self._set_status(key, False)
            return
        try:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
        except Exception as e:
            self._log_proc(key, f"stop error: {e}")
        self.procs[key] = None
        self._set_status(key, False)
        self._log_proc(key, "stopped")

    def start_all(self):
        for k in ("server", "proxy", "client"):
            if not self._is_running(k):
                self.start_component(k)
                self.root.after(500)

    def stop_all(self):
        for k in ("client", "proxy", "server"):
            if self._is_running(k):
                self.stop_component(k)

    # ---------- reader / watcher ----------
    def _reader(self, key: str, proc, q):
        try:
            for line in iter(proc.stdout.readline, ""):
                if not line:
                    break
                q.put(line)
        except Exception:
            pass

    def _watcher(self, key: str, proc):
        proc.wait()
        self.root.after(0, lambda: self._proc_exited(key))

    def _proc_exited(self, key: str):
        if self.procs.get(key) is not None:
            self.procs[key] = None
            self._set_status(key, False)
            self._log_proc(key, "process exited")

    # ---------- misc ----------
    def show_help(self):
        msg = (
            "How to connect:\n"
            "1. Start the server (port 25565) — make sure eula.txt = true.\n"
            "2. Start the MWC proxy (port 2344).\n"
            "3. Start the Eaglercraft client.\n"
            "4. In-game: Multiplayer → 'Vanilla via Proxy...' → localhost:25565.\n"
            "5. Press J in an inventory to open JEI."
        )
        messagebox.showinfo("Connection help", msg)


def main():
    # Validate required files/dirs exist before opening the GUI.
    missing = []
    if not os.path.isdir(SERVER_DIR):
        missing.append(f"server/  ({SERVER_DIR})")
    elif not os.path.isfile(os.path.join(SERVER_DIR, "server.jar")):
        missing.append("server/server.jar")
    if not os.path.isdir(PROXY_DIR):
        missing.append(f"mwc-proxy/  ({PROXY_DIR})")
    elif not os.path.isfile(os.path.join(PROXY_DIR, "package.json")):
        missing.append("mwc-proxy/package.json")
    if not os.path.isdir(EAGLER_DIR):
        missing.append(f"eaglercraft/  ({EAGLER_DIR})")
    else:
        gw = "gradlew.bat" if sys.platform == "win32" else "gradlew"
        if not os.path.isfile(os.path.join(EAGLER_DIR, gw)):
            missing.append(f"eaglercraft/{gw}")

    if missing:
        root = tk.Tk()
        root.withdraw()
        messagebox.showerror(
            "Missing files",
            "The following required files/directories were not found:\n\n"
            + "\n".join(missing)
            + "\n\nMake sure you are running start_menu.py from the workspace root.\n"
            "If files are missing, run setup_multiplayer.py first to set up the project.",
        )
        return

    root = tk.Tk()
    app = LauncherApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()