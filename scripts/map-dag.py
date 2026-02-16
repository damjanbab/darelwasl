#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DAG_PATH = REPO_ROOT / "scripts" / "map" / "dag.json"
DEFAULT_NO_COLLAB_DAG_PATH = REPO_ROOT / "scripts" / "map" / "dag.no-collab.json"


def _utc_compact() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def _read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _write_json(path: Path, obj: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _run(cmd: list[str], *, cwd: Path, out_path: Path | None = None, err_path: Path | None = None, env: dict[str, str] | None = None) -> int:
    out_f = open(out_path, "wb") if out_path else subprocess.DEVNULL
    err_f = open(err_path, "wb") if err_path else subprocess.DEVNULL
    try:
        proc = subprocess.run(cmd, cwd=str(cwd), stdout=out_f, stderr=err_f, env=env)
        return int(proc.returncode)
    finally:
        if out_path:
            out_f.close()
        if err_path:
            err_f.close()


def _require_file(path: Path, *, label: str) -> None:
    if not path.exists():
        raise ValueError(f"Missing {label}: {path}")
    if not path.is_file():
        raise ValueError(f"Not a file {label}: {path}")
    if path.stat().st_size <= 0:
        raise ValueError(f"Empty {label}: {path}")


def _toposort(nodes: dict[str, "Node"]) -> list[str]:
    deps: dict[str, set[str]] = {nid: set(n.depends_on) for nid, n in nodes.items()}
    ready = sorted([nid for nid, d in deps.items() if not d])
    out: list[str] = []
    while ready:
        nid = ready.pop(0)
        out.append(nid)
        for other in sorted(deps.keys()):
            if nid in deps[other]:
                deps[other].discard(nid)
                if not deps[other] and other not in out and other not in ready:
                    ready.append(other)
                    ready.sort()
    if len(out) != len(nodes):
        remaining = sorted([nid for nid in nodes.keys() if nid not in out])
        raise ValueError(f"DAG has cycles or missing deps; remaining: {remaining}")
    return out


@dataclass(frozen=True)
class Node:
    id: str
    title: str
    depends_on: list[str]
    prompt_file: Path


def load_dag(path: Path) -> tuple[dict[str, Any], dict[str, Node]]:
    dag = _read_json(path)
    if dag.get("format") != "darelwasl/map-dag":
        raise ValueError(f"Unsupported dag format: {dag.get('format')!r}")
    if int(dag.get("version", 0)) != 1:
        raise ValueError(f"Unsupported dag version: {dag.get('version')!r}")

    raw_nodes = dag.get("nodes")
    if not isinstance(raw_nodes, list) or not raw_nodes:
        raise ValueError("DAG must include non-empty nodes[]")

    nodes: dict[str, Node] = {}
    for raw in raw_nodes:
        if not isinstance(raw, dict):
            raise ValueError("Each node must be an object")
        nid = raw.get("id")
        if not isinstance(nid, str) or not nid.strip():
            raise ValueError("Node missing id")
        if nid in nodes:
            raise ValueError(f"Duplicate node id: {nid}")
        title = raw.get("title") if isinstance(raw.get("title"), str) else nid
        depends_on = raw.get("depends_on", [])
        if not isinstance(depends_on, list) or not all(isinstance(x, str) for x in depends_on):
            raise ValueError(f"Node {nid}: depends_on must be array of strings")
        prompt_file = raw.get("prompt_file")
        if not isinstance(prompt_file, str) or not prompt_file.strip():
            raise ValueError(f"Node {nid}: missing prompt_file")
        pf = (REPO_ROOT / prompt_file).resolve()
        nodes[nid] = Node(id=nid, title=title, depends_on=depends_on, prompt_file=pf)

    for nid, node in nodes.items():
        for dep in node.depends_on:
            if dep not in nodes:
                raise ValueError(f"Node {nid}: unknown dependency {dep}")

    _toposort(nodes)
    return dag, nodes


def validate_dag(dag_path: Path) -> None:
    _require_file(dag_path, label="dag")
    _dag, nodes = load_dag(dag_path)
    for node in nodes.values():
        _require_file(node.prompt_file, label=f"prompt_file for {node.id}")
        text = node.prompt_file.read_text(encoding="utf-8")
        if "{{RUN_DIR}}" not in text:
            raise ValueError(f"Prompt file missing {{RUN_DIR}} template: {node.prompt_file}")


def _snapshot_repo(run_dir: Path) -> dict[str, str]:
    inputs = run_dir / "inputs"
    inputs.mkdir(parents=True, exist_ok=True)

    def write_text(rel: str, cmd: list[str]) -> str:
        out = inputs / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        rc = _run(cmd, cwd=REPO_ROOT, out_path=out, err_path=inputs / f"{rel}.err")
        if rc != 0:
            raise RuntimeError(f"Snapshot command failed ({rc}): {' '.join(cmd)}")
        return str(out.relative_to(run_dir)).replace("\\", "/")

    paths: dict[str, str] = {}
    paths["git_commit"] = write_text("repo/git-commit.txt", ["git", "rev-parse", "HEAD"])
    paths["git_status"] = write_text("repo/git-status.txt", ["git", "status", "--porcelain"])
    paths["tracked_files"] = write_text("repo/tracked-files.txt", ["git", "ls-files"])

    for rel in ["AGENTS.md", "scripts/checks.sh", "docs/system.generated.md", "docs/catalog.edn"]:
        src = (REPO_ROOT / rel).resolve()
        if src.exists() and src.is_file():
            dest = inputs / "snapshots" / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, dest)
            paths[f"snapshot:{rel}"] = str(dest.relative_to(run_dir)).replace("\\", "/")

    # Registry snapshots for stable mapping even if repo changes later.
    reg_dir = REPO_ROOT / "registries"
    if reg_dir.exists():
        dest_dir = inputs / "snapshots" / "registries"
        dest_dir.mkdir(parents=True, exist_ok=True)
        for p in sorted(reg_dir.glob("*.edn")):
            if p.is_file():
                shutil.copyfile(p, dest_dir / p.name)
        paths["snapshot:registries"] = str(dest_dir.relative_to(run_dir)).replace("\\", "/")

    return paths


def _render_prompt(prompt_file: Path, *, run_dir: Path) -> str:
    text = prompt_file.read_text(encoding="utf-8")
    return text.replace("{{RUN_DIR}}", str(run_dir))

def _extract_usage_from_jsonl(jsonl_path: Path) -> dict[str, Any] | None:
    if not jsonl_path.exists() or not jsonl_path.is_file():
        return None
    usage: dict[str, Any] | None = None
    for raw in jsonl_path.read_text(encoding="utf-8", errors="replace").splitlines():
        raw = raw.strip()
        if not raw:
            continue
        try:
            obj = json.loads(raw)
        except Exception:
            continue
        if isinstance(obj, dict) and obj.get("type") == "turn.completed":
            u = obj.get("usage")
            if isinstance(u, dict):
                usage = u
    return usage


def _codex_exec_node(node: Node, *, run_dir: Path, codex_defaults: dict[str, Any]) -> dict[str, Any]:
    nodes_dir = run_dir / "nodes" / node.id
    logs_dir = run_dir / "logs"
    nodes_dir.mkdir(parents=True, exist_ok=True)
    logs_dir.mkdir(parents=True, exist_ok=True)

    prompt = _render_prompt(node.prompt_file, run_dir=run_dir)
    prompt_path = nodes_dir / "prompt.txt"
    prompt_path.write_text(prompt, encoding="utf-8")

    jsonl_path = logs_dir / f"{node.id}.jsonl"
    err_path = logs_dir / f"{node.id}.stderr.log"
    last_path = nodes_dir / "last.txt"
    result_path = nodes_dir / "result.json"

    enable_features = codex_defaults.get("enable_features", [])
    if not isinstance(enable_features, list):
        enable_features = []
    sandbox = codex_defaults.get("sandbox", "read-only")
    color = codex_defaults.get("color", "never")
    require_subagents = codex_defaults.get("require_subagents", True)

    cmd = ["codex", "exec", "--json", "--color", str(color), "-s", str(sandbox), "--output-last-message", str(last_path)]
    for feat in enable_features:
        cmd += ["--enable", str(feat)]
    cmd += [prompt]

    env = os.environ.copy()
    env["DARELWASL_MAP_RUN_DIR"] = str(run_dir)
    started_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    start = time.time()
    rc = _run(cmd, cwd=REPO_ROOT, out_path=jsonl_path, err_path=err_path, env=env)
    end = time.time()
    finished_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

    raw_last = last_path.read_text(encoding="utf-8").strip() if last_path.exists() else ""
    parsed: Any | None = None
    parse_error: str | None = None
    if raw_last:
        try:
            parsed = json.loads(raw_last)
        except Exception as e:
            parse_error = f"last message is not valid JSON: {e}"

    jsonl_text = jsonl_path.read_text(encoding="utf-8", errors="replace") if jsonl_path.exists() else ""
    used_subagents = ("\"tool\":\"spawn_agent\"" in jsonl_text) or ("\"tool\": \"spawn_agent\"" in jsonl_text)
    usage = _extract_usage_from_jsonl(jsonl_path)

    status = "pass"
    if rc != 0:
        status = "fail"
    if parse_error:
        status = "fail"
    if require_subagents and not used_subagents:
        status = "fail"
        if parse_error:
            parse_error += "; "
        parse_error = (parse_error or "") + "no spawn_agent events detected (sub-agents not used)"

    if isinstance(parsed, (dict, list)):
        _write_json(result_path, parsed)

    meta = {
        "id": node.id,
        "title": node.title,
        "status": status,
        "exit_code": rc,
        "started_at": started_at,
        "finished_at": finished_at,
        "duration_ms": int((end - start) * 1000),
        "paths": {
            "prompt": str(prompt_path.relative_to(run_dir)).replace("\\", "/"),
            "jsonl": str(jsonl_path.relative_to(run_dir)).replace("\\", "/"),
            "stderr": str(err_path.relative_to(run_dir)).replace("\\", "/"),
            "last": str(last_path.relative_to(run_dir)).replace("\\", "/"),
            "result": str(result_path.relative_to(run_dir)).replace("\\", "/") if result_path.exists() else None,
        },
        "used_subagents": used_subagents,
        "required_subagents": bool(require_subagents),
        "usage": usage,
        "parse_error": parse_error,
    }
    _write_json(nodes_dir / "meta.json", meta)
    return meta


def run_dag(dag_path: Path, *, run_id: str | None = None) -> Path:
    dag, nodes = load_dag(dag_path)
    order = _toposort(nodes)
    codex_defaults = dag.get("defaults", {}).get("codex", {}) if isinstance(dag.get("defaults"), dict) else {}
    if not isinstance(codex_defaults, dict):
        codex_defaults = {}

    rid = run_id or _utc_compact()
    run_dir = (REPO_ROOT / "target" / "map" / rid).resolve()
    run_dir.mkdir(parents=True, exist_ok=True)

    manifest: dict[str, Any] = {
        "format": "darelwasl/map-run",
        "version": 1,
        "run_id": rid,
        "repo_root": str(REPO_ROOT),
        "started_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "dag_path": str(dag_path.relative_to(REPO_ROOT)).replace("\\", "/"),
        "node_order": order,
        "nodes": [],
        "snapshots": {},
    }
    _write_json(run_dir / "manifest.json", manifest)

    manifest["snapshots"] = _snapshot_repo(run_dir)
    _write_json(run_dir / "manifest.json", manifest)

    # Execute nodes in topo order (v0: sequential).
    for nid in order:
        node = nodes[nid]
        meta = _codex_exec_node(node, run_dir=run_dir, codex_defaults=codex_defaults)
        manifest["nodes"].append(meta)
        _write_json(run_dir / "manifest.json", manifest)
        if meta["status"] != "pass":
            raise RuntimeError(f"Node failed: {nid} (see {run_dir}/nodes/{nid}/meta.json)")

    manifest["finished_at"] = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    _write_json(run_dir / "manifest.json", manifest)
    return run_dir


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(prog="map-dag")
    sub = p.add_subparsers(dest="cmd", required=True)

    p_validate = sub.add_parser("validate", help="Validate DAG + prompt files")
    p_validate.add_argument("--dag", default=str(DEFAULT_DAG_PATH), help="Path to DAG JSON")

    p_dry = sub.add_parser("dry-run", help="Print topo order only")
    p_dry.add_argument("--dag", default=str(DEFAULT_DAG_PATH), help="Path to DAG JSON")

    p_run = sub.add_parser("run", help="Execute mapping DAG (runs codex exec per node)")
    p_run.add_argument("--dag", default=str(DEFAULT_DAG_PATH), help="Path to DAG JSON")
    p_run.add_argument("--run-id", help="Optional run id (defaults to UTC timestamp)")

    p_bench = sub.add_parser("bench", help="Benchmark collab vs no-collab mapping DAG")
    p_bench.add_argument("--dag-collab", default=str(DEFAULT_DAG_PATH), help="Collab DAG (subagents)")
    p_bench.add_argument("--dag-single", default=str(DEFAULT_NO_COLLAB_DAG_PATH), help="No-collab DAG (single agent)")
    p_bench.add_argument("--bench-id", help="Optional benchmark id (defaults to UTC timestamp)")

    args = p.parse_args(argv)

    try:
        if args.cmd == "validate":
            dag_path = Path(args.dag).resolve()
            validate_dag(dag_path)
            print(f"OK: validated {dag_path}")
            return 0

        if args.cmd == "dry-run":
            dag_path = Path(args.dag).resolve()
            _dag, nodes = load_dag(dag_path)
            order = _toposort(nodes)
            print("order:")
            for nid in order:
                node = nodes[nid]
                print(f"- {nid}: {node.title}")
            return 0

        if args.cmd == "run":
            dag_path = Path(args.dag).resolve()
            validate_dag(dag_path)
            run_dir = run_dag(dag_path, run_id=args.run_id)
            print(str(run_dir))
            return 0

        if args.cmd == "bench":
            dag_collab = Path(args.dag_collab).resolve()
            dag_single = Path(args.dag_single).resolve()
            validate_dag(dag_collab)
            validate_dag(dag_single)

            bench_id = args.bench_id or _utc_compact()
            out_dir = (REPO_ROOT / "target" / "map-bench" / bench_id).resolve()
            out_dir.mkdir(parents=True, exist_ok=True)

            rid_collab = f"{bench_id}-collab"
            rid_single = f"{bench_id}-single"

            t0 = time.time()
            run_collab = run_dag(dag_collab, run_id=rid_collab)
            t1 = time.time()
            run_single = run_dag(dag_single, run_id=rid_single)
            t2 = time.time()

            def load_manifest(run_dir: Path) -> dict[str, Any]:
                return _read_json(run_dir / "manifest.json")

            m_collab = load_manifest(run_collab)
            m_single = load_manifest(run_single)

            def totals(m: dict[str, Any]) -> dict[str, Any]:
                nodes = m.get("nodes", [])
                total_ms = sum(int(n.get("duration_ms") or 0) for n in nodes if isinstance(n, dict))
                tok = {"input_tokens": 0, "output_tokens": 0, "cached_input_tokens": 0}
                missing = 0
                for n in nodes:
                    u = n.get("usage") if isinstance(n, dict) else None
                    if not isinstance(u, dict):
                        missing += 1
                        continue
                    for k in list(tok.keys()):
                        v = u.get(k)
                        if isinstance(v, int):
                            tok[k] += v
                return {"nodes": len(nodes), "duration_ms": total_ms, "token_totals": tok, "missing_usage_nodes": missing}

            report = {
                "format": "darelwasl/map-bench",
                "version": 1,
                "bench_id": bench_id,
                "repo_root": str(REPO_ROOT),
                "started_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                "runs": {
                    "collab": {"dag": str(dag_collab.relative_to(REPO_ROOT)).replace("\\", "/"), "run_dir": str(run_collab), "totals": totals(m_collab)},
                    "single": {"dag": str(dag_single.relative_to(REPO_ROOT)).replace("\\", "/"), "run_dir": str(run_single), "totals": totals(m_single)},
                },
                "wall_clock_s": {"collab": round(t1 - t0, 3), "single": round(t2 - t1, 3), "total": round(t2 - t0, 3)},
                "notes": [
                    "Token totals are summed from codex --json turn.completed usage for each node (input_tokens/output_tokens/cached_input_tokens).",
                    "Collab run enables --enable collab and enforces spawn_agent events; single run disables collab and does not enforce spawn_agent.",
                ],
            }
            _write_json(out_dir / "report.json", report)
            print(str(out_dir))
            return 0

        raise ValueError(f"Unknown cmd: {args.cmd}")
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
