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


def _request_type_from_text(text: str) -> str:
    lowered = text.lstrip().lower()
    if lowered.startswith("change:"):
        return "change"
    return "exploration"


def _build_prompt(request_text: str, *, spec_path: str, plan_path: str) -> str:
    request_type = _request_type_from_text(request_text)
    return (
        "You are High (planner-only).\n"
        "Hard rules:\n"
        "- Do NOT run commands.\n"
        "- Do NOT read or quote repository files.\n"
        "- Do NOT propose patches/diffs.\n"
        "- Output MUST be valid JSON only (no markdown).\n\n"
        "Task:\n"
        "Given the user's request, produce TWO JSON artifacts in a single JSON object:\n"
        "{ \"spec\": <object>, \"plan\": <object> }\n\n"
        f"Request type: {request_type}\n"
        f"User request:\n{request_text}\n\n"
        "Spec requirements:\n"
        "- spec.format = \"darelwasl/spec\"\n"
        "- spec.version = 1\n"
        "- spec.created_at = ISO8601 UTC (e.g. 2026-01-27T12:00:00Z)\n"
        f"- spec.request_type = \"{request_type}\"\n"
        "- spec.goal = 1 sentence\n"
        "- spec.policy_requirement = {\"mode\":\"policy-first\",\"notes\":\"\"}\n"
        "- spec.scope.allowed_paths = array of path prefixes (min 1)\n"
        "- spec.proofs.commands = array of shell commands (min 1)\n"
        "- spec.constraints.non_goals = array (can be empty)\n\n"
        "Plan requirements:\n"
        "- plan.format = \"darelwasl/plan\"\n"
        "- plan.version = 1\n"
        "- plan.created_at = ISO8601 UTC\n"
        f"- plan.spec_path = \"{spec_path}\"\n"
        "- plan.steps = array (can be empty).\n"
        "- Every step MUST include:\n"
        "  - id (kebab-case)\n"
        "  - role: high|low|medium|tester\n"
        "  - kind: policy_create|execute\n"
        "  - title\n"
        "  - depends_on (array)\n"
        "  - operation_type (string)\n"
        "  - allowed_paths (array of path prefixes)\n"
        "  - proof_commands (array of shell commands)\n"
        "- If kind=policy_create: include creates_policy (path like policies/<name>.md)\n"
        "- If kind=execute: include policies (array of policies/<name>.md; must exist OR be created by a depends_on policy_create step)\n"
        "- Prefer a minimal, parallelizable plan: split into small steps; use depends_on and optional parallel_group.\n\n"
        "Output a single JSON object exactly. No extra keys at top-level besides spec and plan.\n"
        f"(The orchestrator will write spec to {spec_path} and plan to {plan_path}.)\n"
    )


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(prog="high_intake")
    p.add_argument("--root", required=True, help="Repo root")
    p.add_argument("--log", required=True, help="Log path")
    p.add_argument("--json", required=True, help="Result JSON path")
    p.add_argument("--request", required=True, help="User request text (including prefix like exploration:)")
    p.add_argument("--spec-path", default="target/spec.json", help="Spec path to embed into plan.spec_path")
    p.add_argument("--plan-path", default="target/plan.json", help="Plan path (for prompt only)")
    args = p.parse_args(argv)

    root = Path(args.root).resolve()
    log_path = Path(args.log).resolve()
    json_path = Path(args.json).resolve()
    log_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.parent.mkdir(parents=True, exist_ok=True)

    last_message_path = json_path.with_suffix(".last.json")
    prompt = _build_prompt(args.request, spec_path=args.spec_path, plan_path=args.plan_path)

    model = os.environ.get("CODEX_HIGH_INTAKE_MODEL", "").strip()
    cmd = [
        "codex",
        "exec",
        "-s",
        "read-only",
        "--color",
        "never",
        "--output-last-message",
        str(last_message_path),
    ]
    if model:
        cmd += ["-m", model]
    cmd += [prompt]

    started_at = _utc_iso()
    start = time.time()
    with log_path.open("wb") as log:
        status_proc = subprocess.run(["codex", "login", "status"], cwd=str(root), stdout=log, stderr=subprocess.STDOUT)
        if status_proc.returncode != 0:
            result = {
                "subagent": "high/intake",
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
    payload: dict | None = None

    if status == "pass":
        try:
            raw = _read_trimmed(last_message_path)
            payload = json.loads(raw)
            if not (isinstance(payload, dict) and "spec" in payload and "plan" in payload):
                status = "fail"
                error = "Expected JSON object with keys: spec, plan."
        except Exception as e:
            status = "fail"
            error = f"Failed to parse LLM JSON output: {e}"

    result: dict = {
        "subagent": "high/intake",
        "status": status,
        "command": cmd,
        "cwd": str(root),
        "exit_code": proc.returncode,
        "started_at": started_at,
        "finished_at": finished_at,
        "duration_ms": int((end - start) * 1000),
        "log_path": str(log_path),
        "last_message_path": str(last_message_path),
        "spec_path": args.spec_path,
        "plan_path": args.plan_path,
    }
    if error:
        result["error"] = error
    if payload and status == "pass":
        result["spec"] = payload.get("spec")
        result["plan"] = payload.get("plan")

    json_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return 0 if status == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

