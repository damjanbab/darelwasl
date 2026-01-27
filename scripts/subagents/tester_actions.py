#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path


def _utc_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(prog="tester_actions")
    p.add_argument("--root", required=True, help="Repo root")
    p.add_argument("--log", required=True, help="Log path")
    p.add_argument("--json", required=True, help="Result JSON path")
    args = p.parse_args(argv)

    root = Path(args.root).resolve()
    log_path = Path(args.log).resolve()
    json_path = Path(args.json).resolve()
    log_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.parent.mkdir(parents=True, exist_ok=True)

    cmd = ["scripts/checks.sh", "actions"]
    started_at = _utc_iso()
    start = time.time()
    with log_path.open("wb") as log:
        proc = subprocess.run(cmd, cwd=str(root), stdout=log, stderr=subprocess.STDOUT)
    end = time.time()
    finished_at = _utc_iso()

    status = "pass" if proc.returncode == 0 else "fail"
    result = {
        "subagent": "tester/actions",
        "status": status,
        "command": cmd,
        "cwd": str(root),
        "exit_code": proc.returncode,
        "started_at": started_at,
        "finished_at": finished_at,
        "duration_ms": int((end - start) * 1000),
        "log_path": str(log_path),
    }
    json_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    if proc.returncode != 0:
        print(f"tester/actions failed (exit {proc.returncode}). Log: {log_path}", file=sys.stderr)
    return int(proc.returncode)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

