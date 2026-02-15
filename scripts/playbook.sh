#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AGENTS_MD="${AGENTS_MD:-$ROOT/AGENTS.md}"

usage() {
  cat <<'EOF'
Usage: scripts/playbook.sh <command> [args]

Commands:
  list                 List playbook ids + titles
  show <id>            Print the playbook section for <id>
  match <text>         Print matching playbook ids (best-effort)

Environment:
  AGENTS_MD=<path>     Override AGENTS.md path (default: repo root AGENTS.md)

Notes:
  - Playbooks are detected by headers like:
      ### Playbook: <id> — <title>
  - The template playbook (with <...>) is ignored.
EOF
}

require_agents_md() {
  if [ ! -s "$AGENTS_MD" ]; then
    echo "AGENTS.md not found or empty: $AGENTS_MD" >&2
    exit 2
  fi
}

list_playbooks() {
  require_agents_md
  awk '
    BEGIN { FS="—"; }
    /^### Playbook: / {
      line=$0
      sub(/^### Playbook: /,"",line)
      if (index(line,"<") > 0) next
      split(line, parts, "—")
      id=parts[1]
      title=parts[2]
      gsub(/[[:space:]]+$/,"",id)
      gsub(/^[[:space:]]+/,"",title)
      if (id != "" && title != "") print id "\t" title
    }
  ' "$AGENTS_MD"
}

show_playbook() {
  local id="${1:-}"
  if [ -z "$id" ]; then
    echo "show requires <id>" >&2
    exit 2
  fi
  require_agents_md

  awk -v "want=$id" '
    function is_playbook_header(line,   x) {
      return (line ~ /^### Playbook: /)
    }
    function header_id(line,   x) {
      x=line
      sub(/^### Playbook: /,"",x)
      split(x, parts, "—")
      gsub(/[[:space:]]+$/,"",parts[1])
      return parts[1]
    }
    BEGIN { printing=0; found=0; }
    {
      if (is_playbook_header($0)) {
        cur=header_id($0)
        if (printing) exit
        if (cur == want) { printing=1; found=1; }
      }
      if ($0 ~ /^## / && printing) exit
      if (printing) print $0
    }
    END {
      if (!found) exit 3
    }
  ' "$AGENTS_MD" || {
    rc=$?
    if [ "$rc" -eq 3 ]; then
      echo "Playbook not found: $id" >&2
      echo "Available:" >&2
      list_playbooks >&2 || true
      exit 3
    fi
    exit "$rc"
  }
}

match_playbooks() {
  local query="${1:-}"
  if [ -z "$query" ]; then
    echo "match requires <text>" >&2
    exit 2
  fi
  require_agents_md

  # Best-effort: match against header, id, title, and the "- When:" line within the playbook.
  awk -v "q=$query" '
    function lower(s) { for (i=1;i<=length(s);i++) { c=substr(s,i,1); out=out tolower(c) } tmp=out; out=""; return tmp }
    function starts_playbook(line) { return (line ~ /^### Playbook: /) }
    function playbook_id(line,   x) {
      x=line
      sub(/^### Playbook: /,"",x)
      if (index(x,"<") > 0) return ""
      split(x, parts, "—")
      gsub(/[[:space:]]+$/,"",parts[1])
      return parts[1]
    }
    function playbook_title(line,   x) {
      x=line
      sub(/^### Playbook: /,"",x)
      split(x, parts, "—")
      title=parts[2]
      gsub(/^[[:space:]]+/,"",title)
      return title
    }
    BEGIN {
      ql=lower(q)
      in_pb=0
      id=""
      title=""
      when=""
    }
    function emit_if_match() {
      if (id == "") return
      blob=lower(id " " title " " when)
      if (index(blob, ql) > 0) print id
    }
    /^### Playbook: / {
      if (in_pb) emit_if_match()
      id=playbook_id($0)
      title=playbook_title($0)
      when=""
      in_pb=(id != "")
      next
    }
    /^## / {
      if (in_pb) emit_if_match()
      in_pb=0
      next
    }
    {
      if (!in_pb) next
      if ($0 ~ /^- When:/) {
        w=$0
        sub(/^- When:[[:space:]]*/,"",w)
        when=w
      }
    }
    END { if (in_pb) emit_if_match() }
  ' "$AGENTS_MD"
}

cmd="${1:-}"
shift || true
case "$cmd" in
  list) list_playbooks ;;
  show) show_playbook "${1:-}" ;;
  match) match_playbooks "${1:-}" ;;
  -h|--help|help|"") usage ;;
  *)
    echo "Unknown command: $cmd" >&2
    usage >&2
    exit 2
    ;;
esac

