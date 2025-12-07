#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_PID=""
DATOMIC_TMP=""

usage() {
  cat <<'EOF'
Usage: scripts/checks.sh [all|registries|schema|actions|app-smoke|views|action-contracts]

Commands:
  registries       Registry presence + field checks + EDN/fixture parse
  schema           Registries + schema load into temp Datomic
  actions          Registries + schema + action contract harness
  app-smoke|views  Full stack smoke: registries + schema + actions + headless UI flow
  all              Runs registries, schema, actions, and app smoke
EOF
}

check_registries() {
  echo "Checking registry files exist and are non-empty..."
  local missing=0
  for f in schema actions views tooling theme; do
    local path="$ROOT/registries/$f.edn"
    if [ ! -s "$path" ]; then
      echo "Missing or empty registry: $path"
      missing=1
    fi
  done
  if [ "$missing" -ne 0 ]; then
    echo "Registry check failed."
    exit 1
  fi
  echo "Registry presence check passed."
}

require_keys() {
  local file="$1"; shift
  local missing=0
  for key in "$@"; do
    if ! grep -q "$key" "$file"; then
      echo "Missing key '$key' in $file"
      missing=1
    fi
  done
  if [ "$missing" -ne 0 ]; then
    echo "Field check failed for $file"
    exit 1
  fi
}

check_clojure_available() {
  if ! command -v clojure >/dev/null 2>&1; then
    echo "clojure command not found. Install Clojure CLI to run checks."
    exit 1
  fi
}

check_node_available() {
  if ! command -v node >/dev/null 2>&1; then
    echo "node command not found. Install Node.js to run frontend checks."
    exit 1
  fi
  if ! command -v npm >/dev/null 2>&1; then
    echo "npm command not found. Install npm to run frontend checks."
    exit 1
  fi
}

ensure_playwright_browser() {
  local attempt=1
  while [[ $attempt -le 2 ]]; do
    if (cd "$ROOT" && npx playwright install chromium); then
      return 0
    fi
    echo "Playwright install attempt $attempt failed; retrying..."
    attempt=$((attempt + 1))
    sleep 3
  done
  echo "Failed to install Playwright Chromium after retries."
  exit 1
}

check_registry_fields() {
  echo "Checking required fields in registries..."
  require_keys "$ROOT/registries/schema.edn" ":id" ":version" ":attributes" ":invariants" ":history" ":compatibility"
  require_keys "$ROOT/registries/actions.edn" ":id" ":version" ":inputs" ":outputs" ":side-effects" ":adapter" ":audit" ":idempotency" ":contracts" ":compatibility"
  require_keys "$ROOT/registries/views.edn" ":id" ":version" ":data" ":actions" ":ux" ":compatibility"
  require_keys "$ROOT/registries/tooling.edn" ":id" ":version" ":invocation" ":scope" ":determinism" ":enforces"
  require_keys "$ROOT/registries/theme.edn" ":id" ":version" ":colors" ":typography" ":spacing" ":radius" ":shadows" ":motion" ":compatibility"
  echo "Registry field checks passed."
}

check_edn_parse() {
  check_clojure_available
  echo "Parsing EDN registries and validating fixtures..."
  ROOT_DIR="$ROOT" clojure -M - <<'CLJ'
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.set :as set]
         '[clojure.string :as str])

(defn read-edn! [path]
  (try
    (with-open [r (java.io.PushbackReader. (io/reader path))]
      (let [data (edn/read r)]
        (println "Parsed" path)
        data))
    (catch Exception e
      (println "Failed" path ":" (.getMessage e))
      (System/exit 1))))

(let [root (System/getenv "ROOT_DIR")
      registry-paths [(str root "/registries/schema.edn")
                      (str root "/registries/actions.edn")
                      (str root "/registries/views.edn")
                      (str root "/registries/tooling.edn")
                      (str root "/registries/theme.edn")]
      fixture-paths {:users (str root "/fixtures/users.edn")
                     :tasks (str root "/fixtures/tasks.edn")
                     :tags (str root "/fixtures/tags.edn")}
      _ (doseq [f registry-paths] (read-edn! f))
      fixtures (into {} (for [[k path] fixture-paths] [k (read-edn! path)]))
      users (:users fixtures)
      tasks (:tasks fixtures)
      tags (:tags fixtures)
      required-user-keys #{:user/id :user/username :user/name :user/password}
      missing-keys (seq (for [u users
                              :let [missing (set/difference required-user-keys (set (keys u)))]
                              :when (seq missing)]
                         {:user/username (:user/username u)
                          :missing missing}))
      user-ids (map :user/id users)
      duplicate-ids (seq (for [[id freq] (frequencies user-ids)
                               :when (> freq 1)]
                           id))
      duplicate-usernames (seq (for [[uname freq] (frequencies (map :user/username users))
                                     :when (> freq 1)]
                                 uname))
      invalid-users (seq (for [u users
                               :let [id (:user/id u)
                                     uname (:user/username u)
                                     pwd (:user/password u)]
                               :when (or (not (instance? java.util.UUID id))
                                         (not (string? uname))
                                         (str/blank? uname)
                                         (not (string? pwd))
                                         (str/blank? pwd))]
                          {:user/username uname :reason "Invalid id/username/password"}))
      user-id-set (set user-ids)
      required-tag-keys #{:tag/id :tag/name}
      tag-missing (seq (for [t tags
                             :let [missing (set/difference required-tag-keys (set (keys t)))]
                             :when (seq missing)]
                        {:tag t :missing missing}))
      tag-ids (map :tag/id tags)
      duplicate-tag-ids (seq (for [[id freq] (frequencies tag-ids)
                                   :when (> freq 1)]
                               id))
      invalid-tags (seq (for [t tags
                              :let [tid (:tag/id t)
                                    name (:tag/name t)]
                              :when (or (not (instance? java.util.UUID tid))
                                        (not (string? name))
                                        (str/blank? name))]
                         {:tag/id tid :reason "Invalid id/name"}))
      tag-id-set (set tag-ids)
      missing-assignees (seq (for [t tasks
                                   :let [assignee (:task/assignee t)]
                                   :when (not (contains? user-id-set assignee))]
                               {:task/id (:task/id t)
                                :task/assignee assignee}))
      task-tag-errors (seq
                       (for [t tasks
                             tag (:task/tags t)
                             :let [tid (cond
                                         (and (vector? tag) (= (first tag) :tag/id)) (second tag)
                                         (map? tag) (:tag/id tag)
                                         :else tag)
                                   valid? (instance? java.util.UUID tid)]
                             :when (or (not valid?)
                                       (not (contains? tag-id-set tid)))]
                         {:task/id (:task/id t)
                         :tag tid
                          :reason (if (not valid?)
                                    "Tag is not a UUID"
                                    "Tag not present in fixtures/tags.edn")}))]
  (when missing-keys
    (doseq [m missing-keys]
      (println "User fixture missing keys" m))
    (System/exit 1))
  (when duplicate-ids
    (println "Duplicate user IDs in fixtures:" duplicate-ids)
    (System/exit 1))
  (when duplicate-usernames
    (println "Duplicate usernames in fixtures:" duplicate-usernames)
    (System/exit 1))
  (when invalid-users
    (doseq [u invalid-users]
      (println "Invalid user fixture" u))
    (System/exit 1))
  (when tag-missing
    (doseq [m tag-missing]
      (println "Tag fixture missing keys" m))
    (System/exit 1))
  (when duplicate-tag-ids
    (println "Duplicate tag IDs in fixtures:" duplicate-tag-ids)
    (System/exit 1))
  (when invalid-tags
    (doseq [t invalid-tags]
      (println "Invalid tag fixture" t))
    (System/exit 1))
  (when missing-assignees
    (println "Task assignees missing in user fixtures:" missing-assignees)
    (System/exit 1))
  (when task-tag-errors
    (doseq [err task-tag-errors]
      (println "Task tag reference invalid" err))
    (System/exit 1))
  (println "User fixtures validated (count" (count users) ") and referenced by tasks.")
  (println "Tag fixtures validated (count" (count tags) ") and referenced by tasks."))
CLJ
}

check_schema_load() {
  check_clojure_available
  echo "Loading schema into temp Datomic..."
  (cd "$ROOT" && clojure -M -m darelwasl.checks.schema)
}

check_actions() {
  check_clojure_available
  echo "Running action contract checks..."
  (cd "$ROOT" && clojure -M -m darelwasl.checks.actions)
}

cleanup_server() {
  if [[ -n "${SERVER_PID:-}" ]] && kill -0 "$SERVER_PID" >/dev/null 2>&1; then
    kill "$SERVER_PID" >/dev/null 2>&1 || true
    wait "$SERVER_PID" >/dev/null 2>&1 || true
  fi
  if [[ -n "${DATOMIC_TMP:-}" && -d "${DATOMIC_TMP}" ]]; then
    rm -rf "${DATOMIC_TMP}" || true
  fi
}

kill_port_if_listening() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    local pids
    pids=$(lsof -ti :"$port" || true)
    if [[ -n "$pids" ]]; then
      echo "Killing existing process on port $port ($pids)..."
      echo "$pids" | xargs kill >/dev/null 2>&1 || true
    fi
  fi
}

wait_for_health() {
  local url="$1"
  local attempts="${2:-20}"
  for ((i=1; i<=attempts; i++)); do
    if curl -sf "$url" >/dev/null 2>&1; then
      echo "Health check passed at ${url}"
      return 0
    fi
    sleep 1
  done
  echo "Server did not become healthy at ${url}"
  return 1
}

check_app_smoke() {
  check_clojure_available
  check_node_available
  if ! command -v curl >/dev/null 2>&1; then
    echo "curl command not found. Install curl to run app smoke."
    exit 1
  fi

  echo "Installing npm dependencies..."
  (cd "$ROOT" && npm install --no-progress --no-audit)

  echo "Ensuring Playwright Chromium is installed..."
  ensure_playwright_browser

  echo "Building frontend for smoke..."
  (cd "$ROOT" && npm run check)

  local host="${APP_HOST:-127.0.0.1}"
  local port="${APP_PORT:-3100}"
  local base_url="http://${host}:${port}"
  DATOMIC_TMP="$(mktemp -d "${ROOT}/.cpcache/datomic-smoke-XXXXXX")"

  echo "Seeding Datomic fixtures (:mem storage) for app smoke..."
  (cd "$ROOT" && DATOMIC_STORAGE_DIR="$DATOMIC_TMP" APP_HOST="$host" APP_PORT="$port" clojure -M:seed)

  echo "Starting backend server for app smoke on ${base_url}..."
  mkdir -p "$ROOT/.cpcache"
  kill_port_if_listening "$port"
  (cd "$ROOT" && DATOMIC_STORAGE_DIR="$DATOMIC_TMP" APP_HOST="$host" APP_PORT="$port" clojure -M:dev >"$ROOT/.cpcache/app-smoke.log" 2>&1) &
  SERVER_PID=$!
  trap cleanup_server EXIT

  wait_for_health "${base_url}/health" 30

  echo "Running headless app smoke..."
  (cd "$ROOT" && APP_URL="$base_url" node scripts/app-smoke.js)

  cleanup_server
  trap - EXIT
}

target="${1:-all}"
case "$target" in
  registries) check_registries; check_registry_fields; check_edn_parse ;;
  schema) check_registries; check_registry_fields; check_edn_parse; check_schema_load ;;
  actions|action-contracts) check_registries; check_registry_fields; check_edn_parse; check_schema_load; check_actions ;;
  app-smoke|views) check_registries; check_registry_fields; check_edn_parse; check_schema_load; check_actions; check_app_smoke ;;
  all)
    check_registries
    check_registry_fields
    check_edn_parse
    check_schema_load
    check_actions
    check_app_smoke
    ;;
  *)
    usage
    exit 1
    ;;
esac
