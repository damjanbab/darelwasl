#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path


def _utc_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _read_trimmed(path: Path) -> str:
    return path.read_text(encoding="utf-8").strip()


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(prog="llm_smoke")
    p.add_argument("--root", required=True, help="Repo root")
    p.add_argument("--log", required=True, help="Log path")
    p.add_argument("--json", required=True, help="Result JSON path")
    args = p.parse_args(argv)

    root = Path(args.root).resolve()
    log_path = Path(args.log).resolve()
    json_path = Path(args.json).resolve()
    log_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.parent.mkdir(parents=True, exist_ok=True)

    last_message_path = json_path.with_suffix(".last.txt")
    marker = "LLM_SMOKE_OK"

    prompt = (
        "You are running a connectivity smoke test.\n"
        "Do not run any commands.\n"
        "Do not read or modify any files.\n"
        f"Reply with exactly this single line and nothing else:\n{marker}\n"
    )

    model = os.environ.get("CODEX_LLM_SMOKE_MODEL", "").strip()
    cmd = ["codex", "exec", "-s", "read-only", "--color", "never", "--output-last-message", str(last_message_path)]
    if model:
        cmd += ["-m", model]
    cmd += [prompt]

    started_at = _utc_iso()
    start = time.time()
    with log_path.open("wb") as log:
        status_proc = subprocess.run(["codex", "login", "status"], cwd=str(root), stdout=log, stderr=subprocess.STDOUT)
        if status_proc.returncode != 0:
            result = {
                "subagent": "tester/llm-smoke",
                "status": "error",
                "error": "codex login status failed; run `codex login status` for details.",
                "command": cmd,
                "cwd": str(root),
                "exit_code": status_proc.returncode,
                "started_at": started_at,
                "finished_at": _utc_iso(),
                "duration_ms": 0,
                "log_path": str(log_path),
            }
            json_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            return 2

        proc = subprocess.run(cmd, cwd=str(root), stdout=log, stderr=subprocess.STDOUT)
    end = time.time()
    finished_at = _utc_iso()

    status = "pass" if proc.returncode == 0 else "fail"
    error: str | None = None
    if status == "pass":
        try:
            last = _read_trimmed(last_message_path)
            if last != marker:
                status = "fail"
                error = f"Unexpected last message. Expected exactly '{marker}', got: {last!r}"
        except Exception as e:
            status = "error"
            error = f"Failed to read last message: {e}"

    result = {
        "subagent": "tester/llm-smoke",
        "status": status,
        "command": cmd,
        "cwd": str(root),
        "exit_code": proc.returncode,
        "started_at": started_at,
        "finished_at": finished_at,
        "duration_ms": int((end - start) * 1000),
        "log_path": str(log_path),
        "last_message_path": str(last_message_path),
    }
    if error:
        result["error"] = error
    json_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    return 0 if status == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

