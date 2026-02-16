#!/usr/bin/env python3
"""
Source-of-truth for the code.haloeddepth.com web terminal picker + Lab UI.

Deploy (on the host):
  scripts/webterm-ui.sh install
  scripts/webterm-ui.sh restart
"""

from __future__ import annotations

import base64
import html
import json
import os
import shutil
import subprocess
from typing import Final
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

TMUX = os.environ.get("DW_TMUX_BIN", "tmux")
PREFIX = os.environ.get("DW_TMUX_PREFIX", "codex")
COUNT = int(os.environ.get("DW_TERMINAL_COUNT", "32"))
WORKDIR = os.environ.get("DW_WORKDIR", "/opt/darelwasl")

PUBLIC_BASE_PATH = os.environ.get("DW_PUBLIC_BASE_PATH", "").strip()
if PUBLIC_BASE_PATH and not PUBLIC_BASE_PATH.startswith("/"):
    PUBLIC_BASE_PATH = "/" + PUBLIC_BASE_PATH
PUBLIC_BASE_PATH = PUBLIC_BASE_PATH.rstrip("/")

LAB_STABLE_SESSION = int(os.environ.get("DW_LAB_SESSION_STABLE", os.environ.get("DW_LAB_SESSION", "7")))
LAB_CANARY_SESSION = int(os.environ.get("DW_LAB_SESSION_CANARY", str(LAB_STABLE_SESSION + 1)))
LAB_DIR = os.environ.get("DW_LAB_DIR", "/opt/darelwasl/tmp/lab")
LAB_MAX_UPLOAD_BYTES = int(os.environ.get("DW_LAB_MAX_UPLOAD_BYTES", str(50 * 1024 * 1024)))
LAB_DEFAULT_HISTORY_LINES = int(os.environ.get("DW_LAB_HISTORY_LINES", "20000"))

TMUX_HISTORY_LIMIT = int(os.environ.get("DW_TMUX_HISTORY_LIMIT", "50000"))


def _utc_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _tmux(*args: str, timeout_s: float = 3.0) -> subprocess.CompletedProcess:
    return subprocess.run([TMUX, *args], text=True, capture_output=True, timeout=timeout_s)


def list_sessions() -> set[str]:
    proc = _tmux("list-sessions", "-F", "#S")
    if proc.returncode != 0:
        return set()
    return {line.strip() for line in proc.stdout.splitlines() if line.strip()}


def session_name(n: int) -> str:
    return f"{PREFIX}{n}"


def ensure_session(n: int) -> None:
    name = session_name(n)
    if name in list_sessions():
        _tmux("set-option", "-t", name, "history-limit", str(TMUX_HISTORY_LIMIT))
        return
    proc = _tmux("new-session", "-d", "-s", name, "-c", WORKDIR, timeout_s=8.0)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or "tmux new-session failed")
    _tmux("set-option", "-t", name, "history-limit", str(TMUX_HISTORY_LIMIT))


def kill_session(n: int) -> None:
    name = session_name(n)
    proc = _tmux("kill-session", "-t", name)
    if proc.returncode != 0:
        if "can't find session" in (proc.stderr or ""):
            return
        raise RuntimeError(proc.stderr.strip() or "tmux kill-session failed")


def start_codex(n: int) -> None:
    name = session_name(n)
    proc = _tmux("send-keys", "-t", name, "codex", "C-m")
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or "tmux send-keys failed")


def next_available() -> int | None:
    sessions = list_sessions()
    for n in range(1, COUNT + 1):
        if session_name(n) not in sessions:
            return n
    return None


def xterm_url(n: int) -> str:
    return f"/xterm/?arg={session_name(n)}"


def ui_url(path: str) -> str:
    if not path:
        path = "/"
    if path.startswith("http://") or path.startswith("https://"):
        return path
    if not path.startswith("/"):
        path = "/" + path
    if not PUBLIC_BASE_PATH:
        return path
    if path == "/":
        return PUBLIC_BASE_PATH + "/"
    return PUBLIC_BASE_PATH + path


def _clamp_session(n: int) -> int:
    if n < 1:
        return 1
    if n > COUNT:
        return COUNT
    return n


def _cookie_value(headers, name: str) -> str | None:
    cookie = headers.get("Cookie") or headers.get("cookie") or ""
    if not cookie:
        return None
    for part in cookie.split(";"):
        part = part.strip()
        if not part or "=" not in part:
            continue
        k, v = part.split("=", 1)
        if k.strip() == name:
            return v.strip()
    return None


def _lab_session_from_request(*, handler: BaseHTTPRequestHandler, qs: dict) -> int:
    raw = (qs.get("session") or [""])[0]
    if raw:
        try:
            return _clamp_session(int(raw))
        except Exception:
            return LAB_STABLE_SESSION
    c = _cookie_value(handler.headers, "dw_lab_session")
    if c:
        try:
            return _clamp_session(int(c))
        except Exception:
            return LAB_STABLE_SESSION
    return LAB_STABLE_SESSION


def _lab_session_name(n: int) -> str:
    return session_name(n)


def _lab_root(n: int) -> str:
    return os.path.join(LAB_DIR, _lab_session_name(n))


def _lab_inbox_dir(n: int) -> str:
    return os.path.join(_lab_root(n), "inbox")


def _lab_outbox_dir(n: int) -> str:
    return os.path.join(_lab_root(n), "outbox")


def ensure_lab_dirs(n: int) -> None:
    os.makedirs(_lab_inbox_dir(n), exist_ok=True)
    os.makedirs(_lab_outbox_dir(n), exist_ok=True)


def _safe_name(name: str | None, default: str) -> str:
    if not name:
        return default
    base = os.path.basename(str(name)).strip()
    if not base or base in {".", ".."}:
        return default
    cleaned = []
    for ch in base:
        if ch.isalnum() or ch in {".", "_", "-", " "}:
            cleaned.append(ch)
        else:
            cleaned.append("_")
    out = " ".join("".join(cleaned).strip().split())
    if not out:
        return default
    if len(out) > 200:
        out = out[:200].rstrip()
    return out


def _unique_path(directory: str, filename: str) -> str:
    base, ext = os.path.splitext(filename)
    candidate = filename
    idx = 1
    while os.path.exists(os.path.join(directory, candidate)):
        candidate = f"{base}-{idx}{ext}"
        idx += 1
    return os.path.join(directory, candidate)


def _require_outbox_path(n: int, name: str) -> str | None:
    if not name:
        return None
    if name != os.path.basename(name):
        return None
    if name in {".", ".."}:
        return None
    if os.sep in name or (os.altsep and os.altsep in name):
        return None
    ensure_lab_dirs(n)
    outbox = os.path.realpath(_lab_outbox_dir(n))
    path = os.path.realpath(os.path.join(outbox, name))
    try:
        if os.path.commonpath([outbox, path]) != outbox:
            return None
    except Exception:
        return None
    return path


_PDF_EXTS: Final[set[str]] = {".pdf"}
_TEXT_EXTS: Final[set[str]] = {".txt", ".md", ".markdown", ".log", ".json", ".edn", ".csv"}
_IMAGE_TYPES: Final[dict[str, str]] = {
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".gif": "image/gif",
    ".webp": "image/webp",
    ".svg": "image/svg+xml",
}


def _guess_content_type(name: str) -> str:
    ext = os.path.splitext(name.lower())[1]
    if ext in _PDF_EXTS:
        return "application/pdf"
    if ext in _IMAGE_TYPES:
        return _IMAGE_TYPES[ext]
    if ext in _TEXT_EXTS:
        return "text/plain; charset=utf-8"
    return "application/octet-stream"


def _list_dir_files(directory: str) -> list[dict]:
    items: list[dict] = []
    for name in os.listdir(directory):
        path = os.path.join(directory, name)
        try:
            st = os.stat(path)
        except FileNotFoundError:
            continue
        if not os.path.isfile(path):
            continue
        items.append({"name": name, "size_bytes": st.st_size, "mtime_ms": int(st.st_mtime * 1000)})
    items.sort(key=lambda x: x.get("mtime_ms", 0), reverse=True)
    return items


def _capture_history(n: int, *, lines: int) -> str:
    name = session_name(n)
    if lines < 10:
        lines = 10
    if lines > 200_000:
        lines = 200_000
    proc = _tmux("capture-pane", "-p", "-t", name, "-S", f"-{lines}", timeout_s=6.0)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or "tmux capture-pane failed")
    return proc.stdout


def _read_body(handler: BaseHTTPRequestHandler, *, max_bytes: int) -> bytes:
    length = int(handler.headers.get("Content-Length") or "0")
    if length <= 0:
        return b""
    if length > max_bytes:
        raise ValueError("body too large")
    return handler.rfile.read(length)


def _parse_json(body: bytes) -> dict | None:
    if not body:
        return None
    try:
        obj = json.loads(body.decode("utf-8"))
    except Exception:
        return None
    return obj if isinstance(obj, dict) else None


def _now_stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def lab_page(*, sess: int, message: str | None = None) -> bytes:
    ensure_session(LAB_STABLE_SESSION)
    ensure_lab_dirs(LAB_STABLE_SESSION)
    if LAB_CANARY_SESSION != LAB_STABLE_SESSION:
        ensure_session(LAB_CANARY_SESSION)
        ensure_lab_dirs(LAB_CANARY_SESSION)

    ensure_session(sess)
    ensure_lab_dirs(sess)

    msg_html = f"<div class='toast ok'>{html.escape(message)}</div>" if message else ""
    sname = _lab_session_name(sess)
    iframe_src = xterm_url(sess)
    if PUBLIC_BASE_PATH == "/canary":
        other_ui_label = "Stable UI"
        other_ui_href = f"/lab?session={sess}"
    else:
        other_ui_label = "Canary UI"
        other_ui_href = f"/canary/lab?session={sess}"

    body = f"""<!doctype html>
<html>
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width,initial-scale=1" />
  <title>Lab · {html.escape(sname)}</title>
  <style>
    :root {{
      --bg: #0b1220;
      --panel: #0f1b33;
      --panel2: #101a2e;
      --border: rgba(255,255,255,0.10);
      --text: rgba(255,255,255,0.92);
      --muted: rgba(255,255,255,0.70);
      --muted2: rgba(255,255,255,0.55);
      --accent: #7dd3fc;
      --accent2: #a78bfa;
      --ok: #22c55e;
      --warn: #f59e0b;
      --err: #ef4444;
      --mono: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
      --sans: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
    }}
    * {{ box-sizing: border-box; }}
    html, body {{ margin: 0; padding: 0; background: radial-gradient(1200px 600px at 20% -10%, rgba(167,139,250,0.22), transparent),
                                 radial-gradient(900px 500px at 90% 0%, rgba(125,211,252,0.18), transparent),
                                 var(--bg); color: var(--text); font-family: var(--sans); }}
    a {{ color: var(--accent); text-decoration: none; }}
    a:hover {{ text-decoration: underline; }}
    .wrap {{ padding: 18px 18px 28px; max-width: 1560px; margin: 0 auto; }}
    .topbar {{ display:flex; align-items:center; gap:12px; flex-wrap:wrap; }}
    .title {{ display:flex; align-items:baseline; gap:10px; }}
    h1 {{ margin:0; font-size: 18px; letter-spacing: 0.2px; }}
    .chip {{ font-family: var(--mono); font-size: 12px; padding: 3px 8px; border-radius: 999px; background: rgba(255,255,255,0.06); border: 1px solid var(--border); color: var(--muted); }}
    .spacer {{ flex:1; }}
    .btn {{ display:inline-flex; align-items:center; gap:8px; padding: 7px 10px; border-radius: 10px; border: 1px solid var(--border); background: rgba(255,255,255,0.06); color: var(--text); cursor: pointer; }}
    .btn:hover {{ background: rgba(255,255,255,0.10); }}
    .btn:active {{ transform: translateY(1px); }}
    .btn.primary {{ border-color: rgba(125,211,252,0.40); background: rgba(125,211,252,0.12); }}
    .muted {{ color: var(--muted); }}
    .toast {{ margin-top: 12px; padding: 10px 12px; border-radius: 12px; border: 1px solid var(--border); background: rgba(34,197,94,0.10); color: rgba(255,255,255,0.92); }}
    .toast.ok {{ border-color: rgba(34,197,94,0.35); }}
    .grid {{ margin-top: 14px; display:grid; grid-template-columns: 1.3fr 0.7fr; gap: 14px; align-items:start; }}
    @media (max-width: 1180px) {{ .grid {{ grid-template-columns: 1fr; }} }}
    .card {{ border: 1px solid var(--border); border-radius: 14px; background: linear-gradient(180deg, rgba(255,255,255,0.06), rgba(255,255,255,0.03)); }}
    .card .hd {{ padding: 12px 12px 0; display:flex; align-items:center; justify-content:space-between; gap: 10px; }}
    .card .bd {{ padding: 12px; }}
    h2 {{ margin: 0; font-size: 14px; letter-spacing: 0.2px; }}
    .sub {{ font-size: 12px; color: var(--muted2); }}
    iframe {{ width:100%; height: 68vh; border: 1px solid var(--border); border-radius: 12px; background: #050a14; }}
    code {{ font-family: var(--mono); font-size: 12px; background: rgba(255,255,255,0.07); padding: 2px 5px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.08); }}
    ul {{ margin: 0; padding-left: 18px; }}
    li {{ margin: 7px 0; }}
    .row {{ display:flex; gap:8px; align-items:center; flex-wrap:wrap; }}
    input[type="text"], input[type="number"], textarea {{
      width: 100%;
      background: rgba(0,0,0,0.20);
      border: 1px solid var(--border);
      color: var(--text);
      border-radius: 12px;
      padding: 10px 10px;
      outline: none;
      font-family: var(--sans);
    }}
    textarea {{ min-height: 96px; resize: vertical; font-family: var(--mono); font-size: 12px; line-height: 1.35; }}
    input::placeholder, textarea::placeholder {{ color: rgba(255,255,255,0.45); }}
    .split {{ display:grid; grid-template-columns: 1fr 1fr; gap: 10px; }}
    @media (max-width: 780px) {{ .split {{ grid-template-columns: 1fr; }} }}
    .err {{ color: rgba(255,255,255,0.92); padding: 10px 12px; border-radius: 12px; border: 1px solid rgba(239,68,68,0.35); background: rgba(239,68,68,0.10); }}
    pre {{
      margin: 0;
      padding: 10px 12px;
      border-radius: 12px;
      border: 1px solid var(--border);
      background: rgba(0,0,0,0.28);
      color: rgba(255,255,255,0.92);
      font-family: var(--mono);
      font-size: 12px;
      line-height: 1.4;
      overflow: auto;
      max-height: 44vh;
      white-space: pre;
    }}
    .kvs {{ display:grid; grid-template-columns: 1fr; gap: 6px; }}
	    .kv {{ display:flex; justify-content: space-between; gap: 10px; font-family: var(--mono); font-size: 12px; color: var(--muted); }}
	    .kv b {{ font-weight: 600; color: rgba(255,255,255,0.88); }}
	    .pill {{ font-family: var(--mono); font-size: 11px; padding: 2px 8px; border-radius: 999px; border: 1px solid var(--border); color: var(--muted); }}
	    .filelist {{ list-style: none; padding: 0; margin: 10px 0 0; }}
	    .filelist li {{ display:flex; gap: 10px; align-items: center; justify-content: space-between; border: 1px solid var(--border); border-radius: 12px; padding: 8px 10px; background: rgba(255,255,255,0.04); }}
	    .filemeta {{ display:flex; flex-direction: column; gap: 2px; min-width: 0; }}
	    .filename {{ font-family: var(--mono); font-size: 12px; color: rgba(255,255,255,0.92); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 52vw; }}
	    @media (min-width: 1180px) {{ .filename {{ max-width: 26vw; }} }}
	    .filesub {{ font-size: 12px; color: var(--muted2); }}
	    .fileactions {{ display:flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }}
	    .btn.small {{ padding: 6px 8px; border-radius: 10px; font-size: 12px; }}

	    .viewer-overlay {{ position: fixed; inset: 0; display: none; background: rgba(0,0,0,0.55); z-index: 50; }}
	    .viewer-overlay.open {{ display: block; }}
	    .viewer-sheet {{
	      position: absolute;
	      top: 0; bottom: 0; right: 0;
	      width: 100%;
	      background: rgba(11,18,32,0.96);
	      border-left: 1px solid var(--border);
	      backdrop-filter: blur(6px);
	      -webkit-backdrop-filter: blur(6px);
	      display:flex;
	      flex-direction: column;
	    }}
	    @media (min-width: 980px) {{ .viewer-sheet {{ width: 50vw; }} }}
	    .viewer-hd {{ padding: 12px 12px; display:flex; align-items:center; justify-content: space-between; gap: 10px; border-bottom: 1px solid var(--border); background: rgba(255,255,255,0.04); }}
	    .viewer-title {{ font-family: var(--mono); font-size: 12px; color: rgba(255,255,255,0.92); overflow:hidden; text-overflow: ellipsis; white-space: nowrap; }}
	    .viewer-bd {{ padding: 12px; overflow: auto; }}
	    .paper {{
	      background: #ffffff;
	      color: #111827;
	      border-radius: 14px;
	      box-shadow: 0 22px 60px rgba(0,0,0,0.45);
	      border: 1px solid rgba(0,0,0,0.08);
	      overflow: hidden;
	      min-height: 70vh;
	    }}
	    .paper iframe {{ width: 100%; height: 76vh; border: 0; border-radius: 0; background: #fff; }}
	    .paper img {{ display:block; width: 100%; height: auto; }}
	    .paper pre {{ max-height: none; border: 0; border-radius: 0; background: transparent; color: #111827; font-size: 13px; line-height: 1.45; }}

	    body.term-max .grid {{ grid-template-columns: 1fr; }}
	    body.term-max #exchange-card {{ display: none; }}
	    body.term-max iframe {{ height: 84vh; }}
	  </style>
</head>
<body>
  <div class="wrap">
    <div class="topbar">
      <div class="title">
        <h1>Lab</h1>
        <span class="chip">stable {LAB_STABLE_SESSION}</span>
        <span class="chip">canary {LAB_CANARY_SESSION}</span>
        <span class="chip">active <span id="active-session">{sess}</span></span>
        <span class="chip"><code id="active-tmux">{html.escape(sname)}</code></span>
      </div>
      <span class="muted">Upload → inbox · Download ← outbox · History via tmux · Canary-safe upgrades</span>
      <span class="spacer"></span>
	      <a class="btn" href="{ui_url("/")}" rel="noreferrer">Terminals</a>
	      <a class="btn" href="{html.escape(other_ui_href)}" rel="noreferrer">{html.escape(other_ui_label)}</a>
	      <button class="btn" id="toggle-terminal" type="button">Maximize terminal</button>
	      <a class="btn" id="open-terminal" href="{iframe_src}" target="_blank" rel="noreferrer">Open terminal</a>
	      <a class="btn primary" id="start-codex" href="{ui_url(f"/codex?n={sess}")}" target="_blank" rel="noreferrer">Start codex</a>
	    </div>

    {msg_html}

	    <div class="grid">
		      <div class="card" id="exchange-card">
        <div class="hd">
          <div>
            <h2>Terminal</h2>
            <div class="sub">This is the live ttyd terminal attached to tmux <code id="active-tmux-sub">{html.escape(sname)}</code>.</div>
          </div>
        </div>
        <div class="bd">
          <iframe id="term" src="{iframe_src}" title="terminal"></iframe>
          <div class="kvs" style="margin-top: 10px;">
            <div class="kv"><span>inbox</span><b><code>tmp/lab/<span id="active-tmux-path">{html.escape(sname)}</span>/inbox</code></b></div>
            <div class="kv"><span>outbox</span><b><code>tmp/lab/<span id="active-tmux-path2">{html.escape(sname)}</span>/outbox</code></b></div>
          </div>
        </div>
      </div>

	      <div class="card">
	        <div class="hd">
	          <div>
	            <h2>Exchange</h2>
	            <div class="sub">Files (outbox) + clipboard + history. Outbox is the shared “library” (agent outputs land here).</div>
	          </div>
	          <div class="row">
	            <button class="btn" id="use-stable" type="button">Use stable</button>
	            <button class="btn" id="use-canary" type="button">Use canary</button>
	            <button class="btn" id="refresh-all" type="button">Refresh</button>
	          </div>
	        </div>
	        <div class="bd">
	          <div class="split">
	            <div>
	              <h2 style="margin-bottom:8px;">Add file</h2>
	              <form id="upload-form" action="{ui_url("/api/lab/upload?dir=outbox")}" method="post" enctype="multipart/form-data">
	                <div class="row">
	                  <input type="file" name="file" required />
	                  <select id="upload-dir" class="btn" style="padding: 7px 10px;">
	                    <option value="outbox" selected>To outbox</option>
	                    <option value="inbox">To inbox</option>
	                  </select>
	                  <button class="btn primary" type="submit">Upload</button>
	                </div>
	                <div class="sub" style="margin-top:6px;">Max upload: {LAB_MAX_UPLOAD_BYTES // (1024 * 1024)} MB.</div>
	              </form>
	            </div>
            <div>
              <h2 style="margin-bottom:8px;">Paste → file</h2>
              <div class="row">
                <input id="paste-name" type="text" placeholder="filename (optional, e.g. note.txt)" />
              </div>
              <div style="margin-top:8px;">
                <textarea id="paste-content" placeholder="Paste text here (logs, JSON, notes)…"></textarea>
              </div>
              <div class="row" style="margin-top:8px;">
                <button class="btn" id="paste-inbox" type="button">Save to inbox</button>
                <button class="btn primary" id="paste-outbox" type="button">Save to outbox</button>
                <span class="pill" id="paste-status"></span>
              </div>
            </div>
          </div>

          <hr style="border:none;border-top:1px solid var(--border);margin:14px 0" />

	          <div class="split">
	            <div>
	              <div class="row" style="justify-content: space-between;">
	                <h2 style="margin:0;">Outbox (library)</h2>
	                <span class="sub" id="outbox-status"></span>
	              </div>
	              <ul id="outbox" class="filelist"></ul>
	            </div>
	            <div>
	              <div class="row" style="justify-content: space-between;">
	                <h2 style="margin:0;">Inbox (list only)</h2>
	                <span class="sub" id="inbox-status"></span>
	              </div>
	              <ul id="inbox" class="filelist"></ul>
	            </div>
	          </div>

          <hr style="border:none;border-top:1px solid var(--border);margin:14px 0" />

          <div class="row" style="justify-content: space-between;">
            <div>
              <h2 style="margin:0;">History</h2>
              <div class="sub">Captured from tmux scrollback (independent of the iframe scroll).</div>
            </div>
            <div class="row">
              <input id="hist-lines" type="number" min="200" max="200000" value="{LAB_DEFAULT_HISTORY_LINES}" style="width: 140px;" />
              <button class="btn" id="hist-refresh" type="button">Capture</button>
              <button class="btn" id="hist-save-outbox" type="button">Save to outbox</button>
            </div>
          </div>
          <div id="hist-error" style="margin-top:10px;"></div>
          <pre id="history">(loading…)</pre>
          <div class="sub" style="margin-top:8px;">Tip: use browser find (Ctrl/⌘+F) inside the history box.</div>
        </div>
      </div>
	    </div>
	  </div>

	  <div id="viewer-overlay" class="viewer-overlay" aria-hidden="true">
	    <div class="viewer-sheet">
	      <div class="viewer-hd">
	        <div class="viewer-title" id="viewer-title"></div>
	        <div class="row">
	          <a class="btn small" id="viewer-download" href="#" rel="noreferrer">Download</a>
	          <button class="btn small" id="viewer-copy" type="button">Copy ref</button>
	          <button class="btn small primary" id="viewer-close" type="button">Close</button>
	        </div>
	      </div>
	      <div class="viewer-bd" id="viewer-body"></div>
	    </div>
	  </div>

	  <script>
    function fmtKB(n) {{
      const kb = Math.round((n || 0) / 1024);
      return kb + " KB";
    }}

    async function jget(url) {{
      const resp = await fetch(url, {{cache: "no-store"}});
      if (!resp.ok) throw new Error("HTTP " + resp.status);
      return await resp.json();
    }}

    const UI_PREFIX = {json.dumps(PUBLIC_BASE_PATH)};
    const TMUX_PREFIX = {json.dumps(PREFIX)};
    const STABLE_SESSION = {LAB_STABLE_SESSION};
    const CANARY_SESSION = {LAB_CANARY_SESSION};
    let activeSession = STABLE_SESSION;

    function tmuxName(n) {{
      return TMUX_PREFIX + String(n);
    }}

	    function setActiveSession(n) {{
	      const nn = Number(n);
	      if (!Number.isFinite(nn) || nn < 1 || nn > {COUNT}) return;
	      activeSession = nn;
	      document.cookie = "dw_lab_session=" + encodeURIComponent(String(nn)) + "; Path=/; Max-Age=31536000; SameSite=Lax";
	      const tn = tmuxName(nn);
	      document.getElementById("active-session").textContent = String(nn);
	      document.getElementById("active-tmux").textContent = tn;
	      document.getElementById("active-tmux-sub").textContent = tn;
      document.getElementById("active-tmux-path").textContent = tn;
      document.getElementById("active-tmux-path2").textContent = tn;
      document.getElementById("term").src = "/xterm/?arg=" + encodeURIComponent(tn);
      document.getElementById("open-terminal").href = "/xterm/?arg=" + encodeURIComponent(tn);
      document.getElementById("start-codex").href = UI_PREFIX + "/codex?n=" + encodeURIComponent(String(nn));
    }}

    function apiUrl(path) {{
      const full = UI_PREFIX + path;
      const sep = full.includes("?") ? "&" : "?";
      return full + sep + "session=" + encodeURIComponent(String(activeSession));
    }}

	    async function loadInbox() {{
	      const status = document.getElementById("inbox-status");
	      const list = document.getElementById("inbox");
	      status.textContent = "Loading…";
	      list.innerHTML = "";
	      const data = await jget(apiUrl("/api/lab/inbox"));
	      const items = (data.inbox || []);
	      status.textContent = items.length ? (items.length + " file(s)") : "Empty.";
	      for (const it of items) {{
	        const li = document.createElement("li");
	        const meta = document.createElement("div");
	        meta.className = "filemeta";
	        const name = document.createElement("div");
	        name.className = "filename";
	        name.textContent = it.name;
	        const sub = document.createElement("div");
	        sub.className = "filesub";
	        sub.textContent = fmtKB(it.size_bytes);
	        meta.appendChild(name);
	        meta.appendChild(sub);
	        li.appendChild(meta);
	        list.appendChild(li);
	      }}
	    }}

	    function extLower(name) {{
	      const s = String(name || "").toLowerCase();
	      const i = s.lastIndexOf(".");
	      return i >= 0 ? s.slice(i) : "";
	    }}

	    function isPreviewable(name) {{
	      const ext = extLower(name);
	      return ext === ".pdf" || [".png",".jpg",".jpeg",".gif",".webp",".svg",".txt",".md",".markdown",".log",".json",".edn",".csv"].includes(ext);
	    }}

	    function viewUrl(name) {{
	      return apiUrl("/api/lab/outbox/view?name=" + encodeURIComponent(String(name || "")));
	    }}

	    function downloadUrl(name) {{
	      return apiUrl("/api/lab/outbox/download?name=" + encodeURIComponent(String(name || "")));
	    }}

	    function setViewerOpen(open) {{
	      const overlay = document.getElementById("viewer-overlay");
	      if (!overlay) return;
	      if (open) overlay.classList.add("open");
	      else overlay.classList.remove("open");
	    }}

	    async function openViewer(name) {{
	      const title = document.getElementById("viewer-title");
	      const body = document.getElementById("viewer-body");
	      const dl = document.getElementById("viewer-download");
	      const copy = document.getElementById("viewer-copy");
	      if (!title || !body || !dl || !copy) return;
	      const url = viewUrl(name);
	      title.textContent = String(name || "");
	      body.innerHTML = "";
	      dl.href = downloadUrl(name);
	      copy.onclick = async () => {{
	        try {{
	          await navigator.clipboard.writeText("outbox/" + String(name || ""));
	        }} catch (e) {{}}
	      }};

	      setViewerOpen(true);
	      const ext = extLower(name);
	      const paper = document.createElement("div");
	      paper.className = "paper";
	      body.appendChild(paper);
	      if (ext === ".pdf") {{
	        const iframe = document.createElement("iframe");
	        iframe.src = url;
	        iframe.title = String(name || "pdf");
	        paper.appendChild(iframe);
	        return;
	      }}
	      if ([".png",".jpg",".jpeg",".gif",".webp",".svg"].includes(ext)) {{
	        const img = document.createElement("img");
	        img.src = url;
	        img.alt = String(name || "image");
	        paper.appendChild(img);
	        return;
	      }}
	      const pre = document.createElement("pre");
	      pre.textContent = "(loading…)";
	      paper.appendChild(pre);
	      try {{
	        const resp = await fetch(url, {{cache: "no-store"}});
	        if (!resp.ok) throw new Error("HTTP " + resp.status);
	        pre.textContent = await resp.text();
	      }} catch (e) {{
	        pre.textContent = "Failed to load: " + (e && e.message ? e.message : String(e));
	      }}
	    }}

	    function closeViewer() {{
	      setViewerOpen(false);
	    }}

	    async function loadOutbox() {{
	      const status = document.getElementById("outbox-status");
	      const list = document.getElementById("outbox");
	      status.textContent = "Loading…";
      list.innerHTML = "";
      const data = await jget(apiUrl("/api/lab/outbox"));
	      const items = (data.outbox || []);
	      status.textContent = items.length ? (items.length + " file(s)") : "Empty.";
	      for (const it of items) {{
	        const li = document.createElement("li");
	        const meta = document.createElement("div");
	        meta.className = "filemeta";
	        const name = document.createElement("div");
	        name.className = "filename";
	        name.textContent = it.name;
	        const sub = document.createElement("div");
	        sub.className = "filesub";
	        sub.textContent = fmtKB(it.size_bytes);
	        meta.appendChild(name);
	        meta.appendChild(sub);

	        const actions = document.createElement("div");
	        actions.className = "fileactions";

	        const btnView = document.createElement("button");
	        btnView.className = "btn small";
	        btnView.type = "button";
	        btnView.textContent = isPreviewable(it.name) ? "View" : "Open";
	        btnView.addEventListener("click", () => openViewer(it.name));

	        const a = document.createElement("a");
	        a.className = "btn small";
	        a.textContent = "Download";
	        a.href = downloadUrl(it.name);
	        a.rel = "noreferrer";

	        const btnCopy = document.createElement("button");
	        btnCopy.className = "btn small";
	        btnCopy.type = "button";
	        btnCopy.textContent = "Copy ref";
	        btnCopy.addEventListener("click", async () => {{
	          try {{
	            await navigator.clipboard.writeText("outbox/" + String(it.name || ""));
	          }} catch (e) {{}}
	        }});

	        actions.appendChild(btnView);
	        actions.appendChild(a);
	        actions.appendChild(btnCopy);

	        li.appendChild(meta);
	        li.appendChild(actions);
	        list.appendChild(li);
	      }}
	    }}

	    async function pasteTo(dir) {{
	      const name = document.getElementById("paste-name").value || "";
	      const content = document.getElementById("paste-content").value || "";
	      const status = document.getElementById("paste-status");
	      status.textContent = "Saving…";
	      try {{
	        const resp = await fetch(apiUrl("/api/lab/paste"), {{
	          method: "POST",
	          headers: {{"Content-Type": "application/json"}},
	          body: JSON.stringify({{dir, name, content}})
	        }});
        if (!resp.ok) throw new Error("HTTP " + resp.status);
        const data = await resp.json();
        status.textContent = "Saved: " + data.name;
        await refreshAll();
      }} catch (e) {{
        status.textContent = "Failed: " + (e && e.message ? e.message : String(e));
      }}
    }}

	    async function loadHistory() {{
	      const box = document.getElementById("history");
	      const err = document.getElementById("hist-error");
	      err.innerHTML = "";
	      box.textContent = "(capturing…)";
	      const lines = Number(document.getElementById("hist-lines").value || "{LAB_DEFAULT_HISTORY_LINES}") || {LAB_DEFAULT_HISTORY_LINES};
	      try {{
	        const data = await jget(apiUrl("/api/lab/history?lines=" + encodeURIComponent(lines)));
	        const text = (data.text || "");
	        box.textContent = text || "(empty)";
	        box.scrollTop = box.scrollHeight;
	      }} catch (e) {{
        box.textContent = "";
        err.innerHTML = "<div class='err'>Failed to capture history: " + (e && e.message ? e.message : String(e)) + "</div>";
      }}
    }}

	    async function saveHistoryToOutbox() {{
	      const lines = Number(document.getElementById("hist-lines").value || "{LAB_DEFAULT_HISTORY_LINES}") || {LAB_DEFAULT_HISTORY_LINES};
	      const data = await jget(apiUrl("/api/lab/history?lines=" + encodeURIComponent(lines)));
	      const stamp = (data.captured_at || "").replaceAll(":", "").replaceAll("-", "");
	      const filename = "lab-history-" + tmuxName(activeSession) + "-" + (stamp || "capture") + ".txt";
	      const resp = await fetch(apiUrl("/api/lab/paste"), {{
	        method: "POST",
	        headers: {{"Content-Type": "application/json"}},
	        body: JSON.stringify({{dir: "outbox", name: filename, content: data.text || ""}})
	      }});
      if (!resp.ok) throw new Error("HTTP " + resp.status);
      await loadOutbox();
    }}

    async function refreshAll() {{
      await Promise.all([loadInbox(), loadOutbox()]);
    }}

	    document.getElementById("refresh-all").addEventListener("click", refreshAll);
	    document.getElementById("paste-inbox").addEventListener("click", () => pasteTo("inbox"));
	    document.getElementById("paste-outbox").addEventListener("click", () => pasteTo("outbox"));
	    document.getElementById("hist-refresh").addEventListener("click", loadHistory);
	    document.getElementById("hist-save-outbox").addEventListener("click", saveHistoryToOutbox);
	    document.getElementById("viewer-close").addEventListener("click", closeViewer);
	    document.getElementById("viewer-overlay").addEventListener("click", (e) => {{
	      if (e.target && e.target.id === "viewer-overlay") closeViewer();
	    }});
	    document.addEventListener("keydown", (e) => {{
	      if (e.key === "Escape") closeViewer();
	    }});

	    const uploadDir = document.getElementById("upload-dir");
	    const uploadForm = document.getElementById("upload-form");
	    if (uploadDir && uploadForm) {{
	      uploadDir.addEventListener("change", () => {{
	        const dir = uploadDir.value === "inbox" ? "inbox" : "outbox";
	        uploadForm.action = UI_PREFIX + "/api/lab/upload?dir=" + encodeURIComponent(dir);
	      }});
	    }}

	    const toggleTerm = document.getElementById("toggle-terminal");
	    if (toggleTerm) {{
	      const sync = () => {{
	        const on = document.body.classList.contains("term-max");
	        toggleTerm.textContent = on ? "Show files" : "Maximize terminal";
	      }};
	      toggleTerm.addEventListener("click", () => {{
	        document.body.classList.toggle("term-max");
	        sync();
	      }});
	      sync();
	    }}
	    document.getElementById("use-stable").addEventListener("click", async () => {{
	      setActiveSession(STABLE_SESSION);
	      await refreshAll();
	      await loadHistory();
	    }});
	    document.getElementById("use-canary").addEventListener("click", async () => {{
	      setActiveSession(CANARY_SESSION);
	      await refreshAll();
	      await loadHistory();
	    }});

	    (async () => {{
	      const c = (document.cookie || "").split(";").map(s => s.trim()).find(s => s.startsWith("dw_lab_session="));
	      if (c) {{
	        const v = c.split("=", 2)[1] || "";
	        const n = Number(v);
	        if (Number.isFinite(n)) setActiveSession(n);
	      }}
	      await refreshAll();
	      await loadHistory();
	    }})();
	  </script>
</body>
</html>"""
    return body.encode("utf-8")


def _parse_multipart_file(body: bytes, boundary: bytes) -> tuple[str, bytes] | None:
    if not boundary:
        return None
    delimiter = b"--" + boundary
    parts = body.split(delimiter)
    for part in parts:
        if not part:
            continue
        if part.startswith(b"--"):
            continue
        if part.startswith(b"\r\n"):
            part = part[2:]
        if part.endswith(b"\r\n"):
            part = part[:-2]
        header_blob, sep, content = part.partition(b"\r\n\r\n")
        if not sep:
            continue
        headers = {}
        for line in header_blob.split(b"\r\n"):
            if b":" not in line:
                continue
            k, v = line.split(b":", 1)
            headers[k.strip().lower()] = v.strip()
        disp = headers.get(b"content-disposition", b"").decode("utf-8", "replace")
        if "form-data" not in disp.lower():
            continue
        params = {}
        for seg in disp.split(";")[1:]:
            seg = seg.strip()
            if "=" not in seg:
                continue
            k, v = seg.split("=", 1)
            v = v.strip()
            if len(v) >= 2 and v[0] == '"' and v[-1] == '"':
                v = v[1:-1]
            params[k.strip().lower()] = v
        if params.get("name") != "file":
            continue
        filename = params.get("filename") or "upload"
        return filename, content
    return None


def html_page(message: str | None = None) -> bytes:
    sessions = list_sessions()

    rows = []
    for n in range(1, COUNT + 1):
        name = session_name(n)
        exists = name in sessions
        status = "running" if exists else "empty"
        is_stable = n == LAB_STABLE_SESSION
        is_canary = n == LAB_CANARY_SESSION
        lab_marker = " (lab stable)" if is_stable else (" (lab canary)" if is_canary else "")
        lab_link = (
            f" | <a href=\"{ui_url(f"/lab?session={n}")}\" target=\"_blank\" rel=\"noreferrer\">lab</a>"
            if (is_stable or is_canary)
            else ""
        )
        row_style = " style=\"background:#fffbe6\"" if is_stable else (" style=\"background:#e6f4ff\"" if is_canary else "")
        rows.append(
            f"<tr{row_style}>"
            f"<td>{n}</td>"
            f"<td><code>{name}</code>{lab_marker}</td>"
            f"<td>{status}</td>"
            f"<td>"
            f"<a href=\"{xterm_url(n)}\" target=\"_blank\" rel=\"noreferrer\">open</a> | "
            f"<a href=\"{ui_url(f"/open?n={n}")}\">open+create</a> | "
            f"<a href=\"{ui_url(f"/codex?n={n}")}\">start codex</a> | "
            f"<a href=\"/t{n}\" target=\"_blank\" rel=\"noreferrer\">legacy</a> | "
            f"<a href=\"{ui_url(f"/kill?n={n}")}\" onclick=\"return confirm('Kill {name}?')\">kill</a>"
            f"{lab_link}"
            f"</td>"
            f"</tr>"
        )

    msg_html = f"<p style='color:#0a0'>{message}</p>" if message else ""

    html_doc = f"""<!doctype html>
<html>
<head>
  <meta charset=\"utf-8\" />
  <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\" />
  <title>Terminals</title>
  <style>
    body {{ font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; margin: 24px; }}
    .bar a {{ margin-right: 12px; }}
    table {{ border-collapse: collapse; width: 100%; max-width: 1050px; }}
    th, td {{ border: 1px solid #ddd; padding: 8px; }}
    th {{ background: #f6f6f6; text-align: left; }}
    code {{ background: #f2f2f2; padding: 2px 4px; border-radius: 4px; }}
  </style>
</head>
<body>
  <h1>Web terminals</h1>
  {msg_html}
  <div class=\"bar\">
    <a href=\"{ui_url("/new")}\">New terminal (next free)</a>
    <a href=\"{ui_url(f"/lab?session={LAB_STABLE_SESSION}")}\" target=\"_blank\" rel=\"noreferrer\">Lab (stable {LAB_STABLE_SESSION})</a>
    <a href=\"{ui_url(f"/lab?session={LAB_CANARY_SESSION}")}\" target=\"_blank\" rel=\"noreferrer\">Lab (canary {LAB_CANARY_SESSION})</a>
    <a href=\"{ui_url("/")}\">Refresh</a>
  </div>
  <p><b>Open</b> uses ttyd (xterm.js; good copy/paste + scrolling). <b>legacy</b> is the old shellinabox terminal.</p>
  <table>
    <thead><tr><th>#</th><th>tmux</th><th>status</th><th>actions</th></tr></thead>
    <tbody>
      {''.join(rows)}
    </tbody>
  </table>
</body>
</html>"""
    return html_doc.encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    def _send(
        self,
        status: int,
        body: bytes,
        content_type: str = "text/html; charset=utf-8",
        extra_headers: dict[str, str] | None = None,
    ) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        if extra_headers:
            for k, v in extra_headers.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _send_file(
        self,
        status: int,
        path: str,
        content_type: str,
        download_name: str,
        *,
        disposition: str = "attachment",
    ) -> None:
        st = os.stat(path)
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(st.st_size))
        disp = (disposition or "attachment").strip().lower()
        if disp not in {"attachment", "inline"}:
            disp = "attachment"
        self.send_header("Content-Disposition", f"{disp}; filename=\"{download_name}\"")
        self.end_headers()
        with open(path, "rb") as f:
            shutil.copyfileobj(f, self.wfile)

    def _redirect(self, location: str) -> None:
        self.send_response(302)
        self.send_header("Location", location)
        self.end_headers()

    def _send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self._send(status, body, "application/json; charset=utf-8")

    def do_GET(self):
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)

        try:
            if parsed.path == "/lab":
                sess = _lab_session_from_request(handler=self, qs=qs)
                body = lab_page(sess=sess, message=None)
                cookie = f"dw_lab_session={sess}; Path=/; Max-Age=31536000; SameSite=Lax"
                return self._send(200, body, extra_headers={"Set-Cookie": cookie})

            if parsed.path == "/api/sessions":
                sessions = list_sessions()
                data = [
                    {
                        "n": n,
                        "name": session_name(n),
                        "exists": session_name(n) in sessions,
                        "url": xterm_url(n),
                    }
                    for n in range(1, COUNT + 1)
                ]
                return self._send_json(200, {"count": COUNT, "sessions": data})

            if parsed.path == "/new":
                n = next_available()
                if n is None:
                    return self._send(409, b"No free terminals.\n", "text/plain; charset=utf-8")
                ensure_session(n)
                return self._redirect(xterm_url(n))

            if parsed.path == "/open":
                n = int((qs.get("n") or [""])[0] or "0")
                if not (1 <= n <= COUNT):
                    return self._send(400, b"bad n\n", "text/plain; charset=utf-8")
                ensure_session(n)
                return self._redirect(xterm_url(n))

            if parsed.path == "/codex":
                n = int((qs.get("n") or [""])[0] or "0")
                if not (1 <= n <= COUNT):
                    return self._send(400, b"bad n\n", "text/plain; charset=utf-8")
                ensure_session(n)
                start_codex(n)
                return self._redirect(xterm_url(n))

            if parsed.path == "/kill":
                n = int((qs.get("n") or [""])[0] or "0")
                if not (1 <= n <= COUNT):
                    return self._send(400, b"bad n\n", "text/plain; charset=utf-8")
                kill_session(n)
                body = html_page(f"Killed {session_name(n)}")
                return self._send(200, body)

            if parsed.path == "/api/lab/inbox":
                sess = _lab_session_from_request(handler=self, qs=qs)
                ensure_lab_dirs(sess)
                return self._send_json(
                    200,
                    {
                        "lab_session": sess,
                        "tmux_session": _lab_session_name(sess),
                        "inbox": _list_dir_files(_lab_inbox_dir(sess)),
                    },
                )

            if parsed.path == "/api/lab/outbox":
                sess = _lab_session_from_request(handler=self, qs=qs)
                ensure_lab_dirs(sess)
                return self._send_json(
                    200,
                    {
                        "lab_session": sess,
                        "tmux_session": _lab_session_name(sess),
                        "outbox": _list_dir_files(_lab_outbox_dir(sess)),
                    },
                )

            if parsed.path == "/api/lab/outbox/download":
                sess = _lab_session_from_request(handler=self, qs=qs)
                name = (qs.get("name") or [""])[0]
                path = _require_outbox_path(sess, name)
                if not path or not os.path.exists(path) or not os.path.isfile(path):
                    return self._send(404, b"not found\n", "text/plain; charset=utf-8")
                download_name = _safe_name(name, default="download")
                return self._send_file(200, path, "application/octet-stream", download_name, disposition="attachment")

            if parsed.path == "/api/lab/outbox/view":
                sess = _lab_session_from_request(handler=self, qs=qs)
                name = (qs.get("name") or [""])[0]
                path = _require_outbox_path(sess, name)
                if not path or not os.path.exists(path) or not os.path.isfile(path):
                    return self._send(404, b"not found\n", "text/plain; charset=utf-8")
                view_name = _safe_name(name, default="file")
                return self._send_file(
                    200,
                    path,
                    _guess_content_type(view_name),
                    view_name,
                    disposition="inline",
                )

            if parsed.path == "/api/lab/history":
                sess = _lab_session_from_request(handler=self, qs=qs)
                ensure_session(sess)
                lines = int((qs.get("lines") or [""])[0] or str(LAB_DEFAULT_HISTORY_LINES))
                text = _capture_history(sess, lines=lines)
                return self._send_json(
                    200,
                    {
                        "lab_session": sess,
                        "tmux_session": _lab_session_name(sess),
                        "captured_at": _utc_iso(),
                        "lines_requested": lines,
                        "text": text,
                    },
                )

            body = html_page(None)
            return self._send(200, body)
        except Exception as e:
            msg = (str(e) or "error").encode("utf-8")
            return self._send(500, msg + b"\n", "text/plain; charset=utf-8")

    def do_POST(self):
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)
        try:
            if parsed.path == "/api/lab/upload":
                sess = _lab_session_from_request(handler=self, qs=qs)
                content_length = int(self.headers.get("Content-Length") or "0")
                if content_length <= 0:
                    return self._send(400, b"missing body\n", "text/plain; charset=utf-8")
                if content_length > LAB_MAX_UPLOAD_BYTES:
                    return self._send(413, b"upload too large\n", "text/plain; charset=utf-8")

                ensure_lab_dirs(sess)
                dir_name = str((qs.get("dir") or ["inbox"])[0] or "inbox").strip().lower()
                if dir_name not in {"inbox", "outbox"}:
                    return self._send(400, b"bad dir\n", "text/plain; charset=utf-8")
                ctype = self.headers.get("Content-Type") or ""
                if "multipart/form-data" not in ctype:
                    return self._send(415, b"expected multipart/form-data\n", "text/plain; charset=utf-8")
                boundary = None
                for seg in ctype.split(";")[1:]:
                    seg = seg.strip()
                    if seg.startswith("boundary="):
                        boundary = seg.split("=", 1)[1].strip()
                        break
                if not boundary:
                    return self._send(400, b"missing boundary\n", "text/plain; charset=utf-8")
                boundary_b = boundary.encode("utf-8")

                body = self.rfile.read(content_length)
                parsed_file = _parse_multipart_file(body, boundary_b)
                if not parsed_file:
                    return self._send(400, b"missing file\n", "text/plain; charset=utf-8")

                filename, content = parsed_file
                upload_name = _safe_name(filename, default="upload")
                dest_dir = _lab_inbox_dir(sess) if dir_name == "inbox" else _lab_outbox_dir(sess)
                dest_path = _unique_path(dest_dir, upload_name)
                with open(dest_path, "wb") as out:
                    out.write(content)

                body = lab_page(sess=sess, message=f"Uploaded to {dir_name}: {os.path.basename(dest_path)}")
                return self._send(200, body)

            if parsed.path == "/api/lab/paste":
                sess = _lab_session_from_request(handler=self, qs=qs)
                ensure_lab_dirs(sess)
                body = _read_body(self, max_bytes=5 * 1024 * 1024)
                obj = _parse_json(body) or {}
                dir_name = str(obj.get("dir") or "inbox").strip().lower()
                if dir_name not in {"inbox", "outbox"}:
                    return self._send(400, b"bad dir\n", "text/plain; charset=utf-8")
                name = _safe_name(str(obj.get("name") or ""), default=f"paste-{_now_stamp()}.txt")

                content = obj.get("content")
                content_b64 = obj.get("content_b64")
                if isinstance(content_b64, str) and content_b64.strip():
                    try:
                        raw = base64.b64decode(content_b64.encode("utf-8"), validate=True)
                    except Exception:
                        return self._send(400, b"bad content_b64\n", "text/plain; charset=utf-8")
                else:
                    raw = str(content or "").encode("utf-8")

                dest_dir = _lab_inbox_dir(sess) if dir_name == "inbox" else _lab_outbox_dir(sess)
                dest_path = _unique_path(dest_dir, name)
                with open(dest_path, "wb") as out:
                    out.write(raw)
                return self._send_json(
                    200,
                    {"dir": dir_name, "name": os.path.basename(dest_path), "size_bytes": len(raw), "written_at": _utc_iso()},
                )

            return self._send(404, b"not found\n", "text/plain; charset=utf-8")

        except ValueError as e:
            msg = (str(e) or "error").encode("utf-8")
            return self._send(413, msg + b"\n", "text/plain; charset=utf-8")
        except Exception as e:
            msg = (str(e) or "error").encode("utf-8")
            return self._send(500, msg + b"\n", "text/plain; charset=utf-8")


def main() -> None:
    host = os.environ.get("DW_LISTEN_HOST", "127.0.0.1")
    port = int(os.environ.get("DW_LISTEN_PORT", "7682"))
    httpd = HTTPServer((host, port), Handler)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
