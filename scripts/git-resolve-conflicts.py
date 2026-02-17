#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import re
import shlex
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path


def _utc_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _run(cmd: list[str], *, cwd: Path, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, cwd=str(cwd), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=check)


def _git_status_porcelain(wt: Path) -> str:
    return _run(["git", "status", "--porcelain"], cwd=wt, check=True).stdout or ""


def _changed_paths(wt: Path) -> set[str]:
    out: set[str] = set()
    for line in _git_status_porcelain(wt).splitlines():
        if not line.strip():
            continue
        path = line[3:].strip()
        if "->" in path:
            path = path.split("->", 1)[1].strip()
        out.add(path.replace("\\", "/"))
    return out


def _conflict_paths(wt: Path) -> list[str]:
    proc = subprocess.run(["git", "diff", "--name-only", "--diff-filter=U"], cwd=str(wt), text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    return [p.strip().replace("\\", "/") for p in (proc.stdout or "").splitlines() if p.strip()]


def _read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _has_conflict_markers(text: str) -> bool:
    return "<<<<<<<" in text or ">>>>>>>" in text or "\n=======" in text


def _default_model() -> str:
    return (os.environ.get("DARELWASL_WORK_MODEL") or "gpt-5.2-high").strip()


@dataclass(frozen=True)
class ResolveResult:
    status: str
    model: str
    duration_ms: int
    conflicting_files: list[str]


def _build_prompt(files: list[str]) -> str:
    flist = "\n".join(f"- {f}" for f in files)
    return (
        "You are a git conflict resolver.\n"
        "Task: resolve merge/rebase conflicts in the listed files.\n\n"
        "Hard rules:\n"
        "- Only edit the listed files.\n"
        "- Remove ALL git conflict markers (<<<<<<<, =======, >>>>>>>).\n"
        "- Preserve intent from both sides; prefer minimal, correct resolution.\n"
        "- Do NOT run any commands.\n\n"
        "Conflicting files:\n"
        f"{flist}\n"
    )


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(prog="git-resolve-conflicts")
    p.add_argument("--worktree", required=True, help="Path to git worktree in conflicted state (rebase/merge)")
    p.add_argument("--model", default=None, help="Codex model (default: env DARELWASL_WORK_MODEL or gpt-5.2-high)")
    p.add_argument("--files", default="", help="Comma-separated conflicting files (default: auto-detect via git)")
    args = p.parse_args(argv)

    wt = Path(args.worktree).resolve()
    model = (args.model or _default_model()).strip()

    files = [f.strip() for f in (args.files or "").split(",") if f.strip()]
    if not files:
        files = _conflict_paths(wt)
    if not files:
        print("No conflicting files detected.", file=sys.stderr)
        return 2

    # Snapshot current changed paths so we can enforce allowlist.
    before = _changed_paths(wt)
    allow = set(files)

    prompt = _build_prompt(files)
    started = time.time()
    proc = subprocess.run(
        [
            "codex",
            "exec",
            "--color",
            "never",
            "-s",
            "workspace-write",
            "-C",
            str(wt),
            "-m",
            model,
            prompt,
        ],
        cwd=str(wt),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    duration_ms = int((time.time() - started) * 1000)

    after = _changed_paths(wt)
    delta = sorted(after - before)
    disallowed = [p for p in delta if p not in allow]
    if disallowed:
        print("Conflict resolver touched disallowed files:", file=sys.stderr)
        for f in disallowed:
            print(f"- {f}", file=sys.stderr)
        return 1

    bad: list[str] = []
    for f in files:
        fp = (wt / f)
        if not fp.exists():
            continue
        try:
            if _has_conflict_markers(_read_text(fp)):
                bad.append(f)
        except Exception:
            bad.append(f)

    if bad:
        print("Conflict markers remain in:", file=sys.stderr)
        for f in bad:
            print(f"- {f}", file=sys.stderr)
        print("\nCodex output:\n" + (proc.stdout or ""), file=sys.stderr)
        return 1

    # Stage resolved files.
    _run(["git", "add", *files], cwd=wt, check=False)

    # Print result summary for logs.
    print(
        f"[resolve] ok model={model} files={len(files)} duration_ms={duration_ms} at={_utc_iso()}",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

