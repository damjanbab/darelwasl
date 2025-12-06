#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() {
  cat <<'EOF'
Usage: scripts/checks.sh [all|registries|schema|actions|views|app-smoke|action-contracts]

This is a starter harness. Extend each stub to run real checks (Datomic temp DB, action contract tests, headless app boot).
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
                     :tasks (str root "/fixtures/tasks.edn")}
      _ (doseq [f registry-paths] (read-edn! f))
      fixtures (into {} (for [[k path] fixture-paths] [k (read-edn! path)]))
      users (:users fixtures)
      tasks (:tasks fixtures)
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
      missing-assignees (seq (for [t tasks
                                   :let [assignee (:task/assignee t)]
                                   :when (not (contains? user-id-set assignee))]
                               {:task/id (:task/id t)
                                :task/assignee assignee}))]
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
  (when missing-assignees
    (println "Task assignees missing in user fixtures:" missing-assignees)
    (System/exit 1))
  (println "User fixtures validated (count" (count users) ") and referenced by tasks."))
CLJ
}

stub() {
  echo "TODO: implement $1"
}

target="${1:-all}"
case "$target" in
  registries) check_registries; check_registry_fields; check_edn_parse ;;
  schema) check_registries; check_registry_fields; check_edn_parse; stub "schema load into temp Datomic" ;;
  actions|action-contracts) check_registries; check_registry_fields; check_edn_parse; stub "action contract tests (fixtures, idempotency, audit)" ;;
  views) check_registries; check_registry_fields; check_edn_parse; stub "view registry integrity checks" ;;
  app-smoke) check_registries; check_registry_fields; check_edn_parse; stub "headless app boot / browser loader smoke" ;;
  all)
    check_registries
    check_registry_fields
    check_edn_parse
    stub "schema load into temp Datomic"
    stub "action contract tests"
    stub "view registry integrity checks"
    stub "headless app boot / browser loader smoke"
    ;;
  *)
    usage
    exit 1
    ;;
esac
