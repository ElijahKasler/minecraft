#!/usr/bin/env python3
"""
Eaglercraft + MWC Proxy + Minecraft Server GUI Launcher

Starts / stops all components from a single tkinter window:
  - Minecraft 1.8.8 server   (java -jar server.jar --nogui,  port 25565)
  - ViaProxy                 (java -jar ViaProxy.jar config viaproxy.yml, port 25568)
                             protocol translation so the 1.8 client can join
                             1.7 → latest servers
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
VIA_PROXY_DIR = os.path.join(ROOT, "tools", "viaproxy")

JAVA_HOME_LOCAL = os.path.join(ROOT, "tools", "temurin8")
_JAVA8 = os.path.join(JAVA_HOME_LOCAL, "bin", "java.exe")
SERVER_JAVA = _JAVA8 if os.path.isfile(_JAVA8) else "java"

# ViaProxy (protocol translation) needs Java 17+; we ship the Java 17 build.
VIA_PROXY_JAR = os.path.join(VIA_PROXY_DIR, "ViaProxy.jar")
VIA_PROXY_YML = os.path.join(VIA_PROXY_DIR, "viaproxy.yml")
VIA_PROXY_JAVA = "java"
VIA_PROXY_BIND = "127.0.0.1:25568"
VIA_PROXY_TARGET = "127.0.0.1:25565"

SERVER_CMD = [SERVER_JAVA, "-Xms1G", "-Xmx1G", "-jar", "server.jar", "--nogui"]
PROXY_CMD = ["bun", "dist/app.js"]
EAGLER_CMD = ["gradlew", "target_lwjgl_desktop:eaglercraftDebugRuntime"]
VIA_PROXY_CMD = [
    VIA_PROXY_JAVA,
    "-DskipUpdateCheck=1",
    "-DignoreSystemRequirements=1",
    "-jar",
    "ViaProxy.jar",
    "config",
    "viaproxy.yml",
]

ENV_SERVER = os.environ.copy()
ENV_PROXY = os.environ.copy()
ENV_PROXY["PORT"] = "2344"
ENV_PROXY["DISABLE_SIGNAL"] = "1"
ENV_VIA_PROXY = os.environ.copy()
ENV_VIA_PROXY["VP_RUN_DIR"] = VIA_PROXY_DIR

PORT_MAP = {"server": 25565, "proxy": 2344, "viaproxy": 25568}

COMPONENT_LABELS = {
    "server": "Minecraft 1.8.8 Server",
    "proxy": "MWC Proxy",
    "viaproxy": "ViaProxy (Version Translator)",
    "client": "Eaglercraft Desktop Client",
}

COMPONENT_PORTS = {
    "server": "25565",
    "proxy": "2344",
    "viaproxy": "25568",
    "client": "foreground",
}


def write_viaproxy_config(target: str, bind: str):
    """Rewrite bind-address / target-address in tools/viaproxy/viaproxy.yml.

    Creates the file if it does not exist yet so ViaProxy can start headless
    on first run (otherwise ViaProxy only generates a config and exits).
    """
    def set_key(lines, key, value):
        out = []
        found = False
        for ln in lines:
            if ln.lstrip().startswith(key + ":"):
                out.append(f"{key}: {value}\n")
                found = True
            else:
                out.append(ln)
        if not found:
            out.append(f"{key}: {value}\n")
        return out

    try:
        if os.path.isfile(VIA_PROXY_YML):
            with open(VIA_PROXY_YML, "r", encoding="utf-8") as f:
                lines = f.readlines()
        else:
            lines = []
        lines = set_key(lines, "bind-address", bind)
        lines = set_key(lines, "target-address", target)
        with open(VIA_PROXY_YML, "w", encoding="utf-8") as f:
            f.writelines(lines)
    except Exception as e:
        print(f"[viaproxy] could not write config: {e}")


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

        self.procs = {"server": None, "proxy": None, "viaproxy": None, "client": None}
        self._queues = {}
        self.threads = {}
        self.viaproxy_target_var = None
        self.viaproxy_bind_var = None

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
            ("viaproxy", COMPONENT_LABELS["viaproxy"], VIA_PROXY_DIR, COMPONENT_PORTS["viaproxy"]),
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

        # ViaProxy settings (used when starting the version translator)
        vp = ttk.LabelFrame(top, text="ViaProxy — join servers of other versions (1.7 to latest)",
                            padding=6)
        vp.pack(fill=tk.X, pady=(8, 0))
        ttk.Label(vp, text="Translate to (host:port):").grid(row=0, column=0, sticky=tk.W)
        self.viaproxy_target_var = tk.StringVar(value=VIA_PROXY_TARGET)
        ttk.Entry(vp, textvariable=self.viaproxy_target_var, width=32).grid(
            row=0, column=1, sticky=tk.W, padx=(6, 16))
        ttk.Label(vp, text="Listen (host:port):").grid(row=0, column=2, sticky=tk.W)
        self.viaproxy_bind_var = tk.StringVar(value=VIA_PROXY_BIND)
        ttk.Entry(vp, textvariable=self.viaproxy_bind_var, width=20).grid(
            row=0, column=3, sticky=tk.W, padx=(6, 0))
        ttk.Label(vp, foreground="#888888",
                  text="Then join the server in-game as:  proxy://127.0.0.1:25568").grid(
            row=1, column=0, columnspan=4, sticky=tk.W, pady=(4, 0))

        # Separate log area per component
        nb = ttk.Notebook(self.root)
        nb.pack(fill=tk.BOTH, expand=True, padx=8, pady=8)

        self.log_texts = {}
        log_colors = {
            "server": "#1e3a3a",   # dark teal
            "proxy": "#3a1e1e",    # dark red
            "client": "#3a3a1e",   # dark olive
        }

        for key in ("server", "proxy", "viaproxy", "client"):
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
        elif key == "viaproxy":
            cwd = VIA_PROXY_DIR
            cmd = VIA_PROXY_CMD
            env = ENV_VIA_PROXY
            target = (self.viaproxy_target_var.get() or VIA_PROXY_TARGET).strip()
            bind = (self.viaproxy_bind_var.get() or VIA_PROXY_BIND).strip()
            write_viaproxy_config(target, bind)
            self._log_proc("viaproxy", f"translating -> {target} (listening on {bind})")
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
        for k in ("server", "viaproxy", "proxy", "client"):
            if not self._is_running(k):
                self.start_component(k)
                self.root.after(500)

    def stop_all(self):
        for k in ("client", "proxy", "viaproxy", "server"):
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
            "How to connect (same version, straight to the server):\n"
            "1. Start the server (port 25565) — make sure eula.txt = true.\n"
            "2. Start the MWC proxy (port 2344).\n"
            "3. Start the Eaglercraft client.\n"
            "4. In-game: Multiplayer → 'Vanilla via Proxy...' → localhost:25565.\n"
            "5. Press J in an inventory to open JEI.\n"
            "\n"
            "How to join servers of OTHER versions (1.7 → latest):\n"
            "1. Set 'Translate to (host:port)' to the real server you want to join.\n"
            "2. Start the 'ViaProxy (Version Translator)' component.\n"
            "3. Start the MWC proxy and the client as usual.\n"
            "4. Join in-game as: proxy://127.0.0.1:25568\n"
            "   (or whatever 'Listen' address you set — ViaProxy translates the\n"
            "    protocol so this 1.8 client can play on newer/older servers.)"
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

    if not os.path.isfile(VIA_PROXY_JAR):
        print(f"[launcher] Note: ViaProxy jar not found at {VIA_PROXY_JAR}; "
              f"the version-translator component will not work.")

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