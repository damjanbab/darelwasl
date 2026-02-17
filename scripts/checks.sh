#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_PID=""
DATOMIC_TMP=""

usage() {
  cat <<'EOF'
Usage: scripts/checks.sh [all|governance|query|map|registries|schema|actions|app-smoke|views|action-contracts|import|docs]

Commands:
  governance       Repo housekeeping invariants (skills/policies scaffolding)
  query            Catalog-backed query tool + docs protocol present
  map              Validate repo mapping DAG engine (no network)
  registries       Registry presence + field checks + EDN/fixture parse
  schema           Registries + schema load into temp Datomic
  actions          Registries + schema + action contract harness
  app-smoke|views  Full stack smoke: registries + schema + actions + headless UI flow
  import           Run land-registry importer against the provided CSV in a temp DB
  docs             Regenerate system/catalog docs and fail on drift
  all              Runs governance, docs, registries, schema, actions, import, and app smoke
EOF
}

check_agents_md_playbooks() {
  echo "Checking AGENTS.md playbooks format..."
  local f="$ROOT/AGENTS.md"
  if [ ! -s "$f" ]; then
    echo "Missing or empty: $f"
    exit 1
  fi

  if ! grep -q "^## Playbooks" "$f"; then
    echo "AGENTS.md missing required section: ## Playbooks"
    exit 1
  fi

  if ! grep -q "^## Unknown / Triage" "$f"; then
    echo "AGENTS.md missing required section: ## Unknown / Triage"
    exit 1
  fi

  if ! grep -q "^### Playbook template" "$f"; then
    echo "AGENTS.md missing required section: ### Playbook template"
    exit 1
  fi

  local playbook_count
  playbook_count="$(grep -c "^### Playbook:" "$f" || true)"
  if [ "${playbook_count:-0}" -lt 1 ]; then
    echo "AGENTS.md must define at least one playbook (### Playbook: ...)"
    exit 1
  fi

  # Ensure every playbook has the same minimal shape so it remains a reliable entrypoint.
  awk '
    function reset_flags() { when=0; start=0; policies=0; proof=0; next_flag=0; }
    function fail(msg) { print msg; ok=0; }
    function check_section() {
      if (!when) fail("Playbook missing: - When:");
      if (!start) fail("Playbook missing: - Start:");
      if (!policies) fail("Playbook missing: - Policies:");
      if (!proof) fail("Playbook missing: - Proof:");
      if (!next_flag) fail("Playbook missing: - Next:");
    }
    BEGIN { ok=1; in_pb=0; reset_flags(); }
    /^### Playbook:/ {
      if (in_pb) check_section();
      reset_flags();
      in_pb=1;
      next;
    }
    /^## / {
      if (in_pb) { check_section(); in_pb=0; }
      next;
    }
    {
      if (!in_pb) next;
      if ($0 ~ /^- When:/) when=1;
      if ($0 ~ /^- Start:/) start=1;
      if ($0 ~ /^- Policies:/) policies=1;
      if ($0 ~ /^- Proof:/) proof=1;
      if ($0 ~ /^- Next:/) next_flag=1;
    }
    END { if (in_pb) check_section(); exit(ok ? 0 : 1); }
  ' "$f" || exit 1

  echo "AGENTS.md playbooks look consistent."
}

check_governance() {
  echo "Checking repo housekeeping invariants..."

  local missing=0
  for f in "$ROOT/AGENTS.md" "$ROOT/skills/README.md" "$ROOT/policies/README.md" "$ROOT/agents/README.md" \
    "$ROOT/agents/website/AGENT.md" "$ROOT/agents/website/AGENT.json" \
    "$ROOT/agents/backend/AGENT.md" "$ROOT/agents/backend/AGENT.json" \
    "$ROOT/agents/app-ui/AGENT.md" "$ROOT/agents/app-ui/AGENT.json" \
    "$ROOT/agents/registries/AGENT.md" "$ROOT/agents/registries/AGENT.json" \
    "$ROOT/agents/ops-governance/AGENT.md" "$ROOT/agents/ops-governance/AGENT.json" \
    "$ROOT/scripts/query.sh" "$ROOT/scripts/playbook.sh" "$ROOT/scripts/work.sh" "$ROOT/scripts/work-prod.sh" \
    "$ROOT/scripts/agent-runner" "$ROOT/scripts/git-resolve-conflicts.py" "$ROOT/scripts/pr-merge.sh" \
    "$ROOT/scripts/webterm-ui.sh" "$ROOT/ops/webterm-ui/deps.edn" "$ROOT/ops/webterm-ui/run.sh" \
    "$ROOT/ops/webterm-ui/systemd/darelwasl-webterm-ui.service" "$ROOT/ops/webterm-ui/systemd/darelwasl-webterm-ui-canary.service" \
    "$ROOT/src/darelwasl/webterm/server.clj" "$ROOT/docs/work/README.md" "$ROOT/docs/ops/code-haloeddepth-com.md" \
    "$ROOT/scripts/hooks.sh" "$ROOT/.githooks/pre-push"; do
    if [ ! -s "$f" ]; then
      echo "Missing or empty: $f"
      missing=1
    fi
  done

  if [ -d "$ROOT/skills" ]; then
    local d
    for d in "$ROOT/skills"/*; do
      if [ -d "$d" ]; then
        if [ ! -s "$d/SKILL.md" ]; then
          echo "Missing SKILL.md in skill directory: $d"
          missing=1
        fi
      fi
    done
  fi

  if [ "$missing" -ne 0 ]; then
    echo "Governance checks failed."
    exit 1
  fi

  if [ ! -d "$ROOT/docs/work" ]; then
    echo "Missing directory: $ROOT/docs/work"
    exit 1
  fi

  check_agents_md_playbooks

  echo "Auditing work tracking (non-fatal)..."
  (cd "$ROOT" && scripts/work.sh audit --no-fetch)

  echo "Governance checks passed."
}

check_query() {
  check_clojure_available
  echo "Checking catalog-backed query tool..."

  if [ ! -s "$ROOT/scripts/query.sh" ]; then
    echo "Missing or empty: $ROOT/scripts/query.sh"
    exit 1
  fi

  if [ ! -s "$ROOT/docs/catalog.edn" ] || [ ! -s "$ROOT/docs/system.generated.md" ]; then
    echo "Generated docs missing. Run scripts/generate-docs.sh and commit the results."
    exit 1
  fi

  (cd "$ROOT" && scripts/query.sh --self-check)

  if ! grep -q "## Querying the Codebase (protocol)" "$ROOT/docs/system.generated.md"; then
    echo "Missing query protocol section in docs/system.generated.md. Run scripts/generate-docs.sh and commit the results."
    exit 1
  fi

  echo "Query checks passed."
}

check_map() {
  echo "Validating mapping DAG engine..."
  if ! command -v python3 >/dev/null 2>&1; then
    echo "python3 not found. Install Python 3 to run map checks."
    exit 1
  fi
  if [ ! -s "$ROOT/scripts/map-dag.py" ]; then
    echo "Missing or empty: $ROOT/scripts/map-dag.py"
    exit 1
  fi
  if [ ! -s "$ROOT/scripts/map/dag.json" ]; then
    echo "Missing or empty: $ROOT/scripts/map/dag.json"
    exit 1
  fi
  if [ ! -s "$ROOT/scripts/map/dag.no-collab.json" ]; then
    echo "Missing or empty: $ROOT/scripts/map/dag.no-collab.json"
    exit 1
  fi
  (cd "$ROOT" && python3 scripts/map-dag.py validate)
  (cd "$ROOT" && python3 scripts/map-dag.py validate --dag scripts/map/dag.no-collab.json)
  (cd "$ROOT" && python3 scripts/map-dag.py dry-run >/dev/null)
  (cd "$ROOT" && python3 scripts/map-dag.py dry-run --dag scripts/map/dag.no-collab.json >/dev/null)
  echo "Map checks passed."
}

check_registries() {
  echo "Checking registry files exist and are non-empty..."
  local missing=0
  for f in schema actions views integrations agents policies internal services contracts recipes tooling theme automations; do
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
  require_keys "$ROOT/registries/integrations.edn" ":id" ":version" ":external-system" ":contracts" ":auth" ":failure-modes" ":compatibility" ":adapter" ":related"
  require_keys "$ROOT/registries/agents.edn" ":id" ":version" ":allowed-paths" ":proofs" ":policies" ":routing"
  require_keys "$ROOT/registries/policies.edn" ":id" ":version" ":policy-path" ":enforces"
  require_keys "$ROOT/registries/internal.edn" ":id" ":version" ":var" ":stability" ":tags"
  require_keys "$ROOT/registries/services.edn" ":id" ":title"
  require_keys "$ROOT/registries/contracts.edn" ":id" ":version" ":service/id" ":steps"
  require_keys "$ROOT/registries/tooling.edn" ":id" ":version" ":invocation" ":scope" ":determinism" ":enforces"
  require_keys "$ROOT/registries/theme.edn" ":id" ":version" ":colors" ":typography" ":spacing" ":radius" ":shadows" ":motion" ":compatibility"
  require_keys "$ROOT/registries/automations.edn" ":id" ":version" ":enabled" ":triggers" ":handler"
  echo "Registry field checks passed."
}

check_docs() {
  check_clojure_available
  echo "Generating docs/catalog..."
  (cd "$ROOT" && scripts/generate-docs.sh)
  if ! git diff --quiet -- docs/system.generated.md docs/catalog.edn; then
    echo "Generated docs are out of date. Run scripts/generate-docs.sh and commit the results."
    git diff -- docs/system.generated.md docs/catalog.edn
    exit 1
  fi
  echo "Docs/catalog up to date."
}

check_edn_parse() {
  check_clojure_available
  echo "Parsing EDN registries and validating fixtures..."
  ROOT_DIR="$ROOT" clojure -M - <<'CLJ'
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.set :as set]
         '[clojure.string :as str]
         '[darelwasl.shared.block-types :as block-types])

(defn read-single-edn! [path]
  (try
    (with-open [r (java.io.PushbackReader. (io/reader path))]
      (let [first (edn/read {:eof ::eof} r)
            second (edn/read {:eof ::eof} r)]
        (cond
          (= first ::eof)
          (do (println "Failed" path ": empty EDN file")
              (System/exit 1))

          (not= second ::eof)
          (do (println "Failed" path ": trailing forms detected; registries must be single-form EDN")
              (System/exit 1))

          :else
          (do (println "Parsed" path)
              first))))
    (catch Exception e
      (println "Failed" path ":" (.getMessage e))
      (System/exit 1))))

(defn uuid? [x] (instance? java.util.UUID x))
(defn present-str? [s] (and (string? s) (not (str/blank? s))))
(defn lookup-id [ref expected-ident]
  (cond
    (and (vector? ref) (= (first ref) expected-ident)) (second ref)
    (uuid? ref) ref
    :else ref))

(let [root (System/getenv "ROOT_DIR")
      registry-paths [(str root "/registries/schema.edn")
                      (str root "/registries/actions.edn")
                      (str root "/registries/views.edn")
                      (str root "/registries/integrations.edn")
                      (str root "/registries/agents.edn")
                      (str root "/registries/policies.edn")
                      (str root "/registries/internal.edn")
                      (str root "/registries/services.edn")
                      (str root "/registries/contracts.edn")
                      (str root "/registries/recipes.edn")
                      (str root "/registries/tooling.edn")
                      (str root "/registries/theme.edn")
                      (str root "/registries/automations.edn")]
      fixture-paths {:users (str root "/fixtures/users.edn")
                     :clients (str root "/fixtures/clients.edn")
                     :tasks (str root "/fixtures/tasks.edn")
                     :tags (str root "/fixtures/tags.edn")
                     :content (str root "/fixtures/content.edn")
                     :betting (str root "/fixtures/betting.edn")}
      registries (into {} (map (fn [p] [p (read-single-edn! p)]) registry-paths))
      fixtures (into {} (for [[k path] fixture-paths] [k (read-single-edn! path)]))
      users (:users fixtures)
      clients (:clients fixtures)
      tasks (:tasks fixtures)
      tags (:tags fixtures)
      content (or (:content fixtures) {})
      content-tags (or (:tags content) [])
      content-pages (or (:pages content) [])
      content-blocks (or (:blocks content) [])
      betting (or (:betting fixtures) {})
      betting-events (or (:events betting) [])
      betting-bookmakers (or (:bookmakers betting) [])
      betting-quotes (or (:quotes betting) [])
      betting-bets (or (:bets betting) [])
      betting-facts (or (:facts betting) [])
      policies (get registries (str root "/registries/policies.edn"))
      agents (get registries (str root "/registries/agents.edn"))
      policy-ids (set (map :id (or policies [])))
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
      missing-policy-files (seq (for [p (or policies [])
                                      :let [policy-path (:policy-path p)
                                            f (when policy-path (io/file root policy-path))]
                                      :when (or (not (string? policy-path))
                                                (str/blank? policy-path)
                                                (not (.exists ^java.io.File f))
                                                (not (pos? (.length ^java.io.File f))))]
                                  {:id (:id p) :policy-path policy-path}))
      missing-policy-refs (seq (for [a (or agents [])
                                    pref (or (:policies a) [])
                                    :when (and pref (not (contains? policy-ids pref)))]
                                {:agent (:id a) :missing-policy pref}))
      invalid-users (seq (for [u users
                               :let [id (:user/id u)
                                     uname (:user/username u)
                                     pwd (:user/password u)]
                               :when (or (not (uuid? id))
                                         (not (string? uname))
                                         (str/blank? uname)
                                         (not (string? pwd))
                                         (str/blank? pwd))]
                          {:user/username uname :reason "Invalid id/username/password"}))
      user-id-set (set user-ids)
      required-client-keys #{:client/id :client/name}
      client-missing (seq (for [c clients
                                :let [missing (set/difference required-client-keys (set (keys c)))]
                                :when (seq missing)]
                            {:client/id (:client/id c)
                             :missing missing}))
      client-ids (map :client/id clients)
      duplicate-client-ids (seq (for [[id freq] (frequencies client-ids)
                                      :when (> freq 1)]
                                  id))
      invalid-clients (seq (for [c clients
                                 :let [cid (:client/id c)
                                       name (:client/name c)]
                                 :when (or (not (uuid? cid))
                                           (not (string? name))
                                           (str/blank? name))]
                             {:client/id cid :reason "Invalid id/name"}))
      client-id-set (set client-ids)
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
                              :when (or (not (uuid? tid))
                                        (not (string? name))
                                        (str/blank? name))]
                         {:tag/id tid :reason "Invalid id/name"}))
      tag-id-set (set tag-ids)
      missing-assignees (seq (for [t tasks
                                   :let [assignee (:task/assignee t)]
                                   :when (not (contains? user-id-set assignee))]
                               {:task/id (:task/id t)
                                :task/assignee assignee}))
      missing-clients (seq (for [t tasks
                                 :let [client-ref (:task/client t)
                                       cid (lookup-id client-ref :client/id)]
                                 :when (or (nil? cid) (not (contains? client-id-set cid)))]
                             {:task/id (:task/id t)
                              :task/client client-ref}))
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
                                    "Tag not present in fixtures/tags.edn")}))
      content-tag-ids (map :content.tag/id content-tags)
      duplicate-content-tag-ids (seq (for [[id freq] (frequencies content-tag-ids)
                                           :when (> freq 1)]
                                       id))
      content-tag-id-set (set content-tag-ids)
      content-tag-slugs (remove str/blank? (map :content.tag/slug content-tags))
      duplicate-content-tag-slugs (seq (for [[slug freq] (frequencies content-tag-slugs)
                                             :when (> freq 1)]
                                         slug))
      invalid-content-tags (seq (for [t content-tags
                                      :let [tid (:content.tag/id t)
                                            name (:content.tag/name t)
                                            slug (:content.tag/slug t)]
                                      :when (or (not (uuid? tid))
                                                (not (present-str? name))
                                                (and slug (not (present-str? slug))))]
                                  {:content.tag/id tid :reason "Invalid id/name/slug"}))
      required-page-keys #{:content.page/id :content.page/title :content.page/path}
      page-ids (map :content.page/id content-pages)
      page-id-set (set page-ids)
      duplicate-page-ids (seq (for [[id freq] (frequencies page-ids)
                                    :when (> freq 1)]
                                id))
      invalid-pages (seq (for [p content-pages
                               :let [pid (:content.page/id p)
                                     path (:content.page/path p)
                                     nav (:content.page/navigation-order p)
                                     page-tags (:content.page/tag p)]
                               :when (or (not (uuid? pid))
                                         (not (present-str? path))
                                         (and nav (not (number? nav)))
                                         (some #(not (contains? content-tag-id-set (lookup-id % :content.tag/id))) page-tags))]
                           {:content.page/id (:content.page/id p)
                            :reason "Invalid id/path/nav/tag"}))
      block-ids (map :content.block/id content-blocks)
      block-id-set (set block-ids)
      duplicate-block-ids (seq (for [[id freq] (frequencies block-ids)
                                     :when (> freq 1)]
                                 id))
      allowed-block-types block-types/allowed-block-type-set
      invalid-blocks (seq (for [b content-blocks
                                :let [bid (:content.block/id b)
                                      btype (:content.block/type b)
                                      page-ref (:content.block/page b)
                                      page-id (lookup-id page-ref :content.page/id)
                                      order (:content.block/order b)
                                      slug (:content.block/slug b)]
                                :when (or (not (uuid? bid))
                                          (not (keyword? btype))
                                          (not (contains? allowed-block-types btype))
                                          (and page-ref (not (contains? page-id-set page-id)))
                                          (and slug (not (present-str? slug)))
                                          (and order (not (number? order))))]
                            {:content.block/id (:content.block/id b)
                             :reason "Invalid id/type/page/order/slug"}))
      block-tag-errors (seq (for [b content-blocks
                                  tag-ref (:content.block/tag b)
                                  :let [tid (lookup-id tag-ref :content.tag/id)]
                                  :when (and tid (not (contains? content-tag-id-set tid)))]
                              {:content.block/id (:content.block/id b)
                               :tag tag-ref
                               :reason "Tag ref missing from content tag fixtures"}))
      page-block-ref-errors (seq
                             (for [p content-pages
                                   block-ref (:content.page/blocks p)
                                   :let [bid (lookup-id block-ref :content.block/id)
                                         block (some #(when (= (:content.block/id %) bid) %) content-blocks)
                                         block-page (lookup-id (:content.block/page block) :content.page/id)]
                                   :when (or (not (uuid? bid))
                                             (nil? block)
                                             (and block-page (not= block-page (:content.page/id p))))]
                               {:content.page/id (:content.page/id p)
                                :block block-ref
                                :reason "Block ref invalid or assigned to different page"}))
      betting-event-ids (map :betting.event/id betting-events)
      betting-event-id-set (set betting-event-ids)
      duplicate-betting-event-ids (seq (for [[id freq] (frequencies betting-event-ids)
                                             :when (> freq 1)]
                                         id))
      betting-external-ids (map :betting.event/external-id betting-events)
      duplicate-betting-external-ids (seq (for [[id freq] (frequencies betting-external-ids)
                                                :when (> freq 1)]
                                            id))
      invalid-betting-events (seq (for [e betting-events
                                        :let [eid (:betting.event/id e)
                                              ext (:betting.event/external-id e)
                                              commence (:betting.event/commence-time e)]
                                        :when (or (not (uuid? eid))
                                                  (not (present-str? ext))
                                                  (nil? commence))]
                                    {:betting.event/id eid
                                     :reason "Invalid id/external-id/commence-time"}))
      betting-bookmaker-ids (map :betting.bookmaker/id betting-bookmakers)
      betting-bookmaker-id-set (set betting-bookmaker-ids)
      duplicate-betting-bookmaker-ids (seq (for [[id freq] (frequencies betting-bookmaker-ids)
                                                 :when (> freq 1)]
                                             id))
      betting-bookmaker-keys (map :betting.bookmaker/key betting-bookmakers)
      duplicate-betting-bookmaker-keys (seq (for [[key freq] (frequencies betting-bookmaker-keys)
                                                  :when (> freq 1)]
                                              key))
      invalid-betting-bookmakers (seq (for [b betting-bookmakers
                                            :let [bid (:betting.bookmaker/id b)
                                                  key (:betting.bookmaker/key b)
                                                  title (:betting.bookmaker/title b)]
                                            :when (or (not (uuid? bid))
                                                      (not (present-str? key))
                                                      (not (present-str? title)))]
                                        {:betting.bookmaker/id bid
                                         :reason "Invalid id/key/title"}))
      betting-quote-ids (map :betting.quote/id betting-quotes)
      betting-quote-id-set (set betting-quote-ids)
      duplicate-betting-quote-ids (seq (for [[id freq] (frequencies betting-quote-ids)
                                             :when (> freq 1)]
                                         id))
      invalid-betting-quotes (seq (for [q betting-quotes
                                        :let [qid (:betting.quote/id q)
                                              event-id (lookup-id (:betting.quote/event q) :betting.event/id)
                                              bookmaker-id (lookup-id (:betting.quote/bookmaker q) :betting.bookmaker/id)
                                              odds (:betting.quote/odds-decimal q)
                                              implied (:betting.quote/implied-prob q)]
                                        :when (or (not (uuid? qid))
                                                  (not (contains? betting-event-id-set event-id))
                                                  (not (contains? betting-bookmaker-id-set bookmaker-id))
                                                  (not (number? odds))
                                                  (not (number? implied)))]
                                    {:betting.quote/id qid
                                     :reason "Invalid id/event/bookmaker/odds"}))
      betting-bet-ids (map :betting.bet/id betting-bets)
      betting-bet-id-set (set betting-bet-ids)
      duplicate-betting-bet-ids (seq (for [[id freq] (frequencies betting-bet-ids)
                                           :when (> freq 1)]
                                       id))
      invalid-betting-bets (seq (for [b betting-bets
                                      :let [bid (:betting.bet/id b)
                                            event-id (lookup-id (:betting.bet/event b) :betting.event/id)
                                            bookmaker-id (lookup-id (:betting.bet/bookmaker b) :betting.bookmaker/id)
                                            odds (:betting.bet/odds-decimal b)
                                            implied (:betting.bet/implied-prob b)]
                                      :when (or (not (uuid? bid))
                                                (not (contains? betting-event-id-set event-id))
                                                (and bookmaker-id (not (contains? betting-bookmaker-id-set bookmaker-id)))
                                                (and odds (not (number? odds)))
                                                (not (number? implied)))]
                                  {:betting.bet/id bid
                                   :reason "Invalid id/event/bookmaker/odds/implied-prob"}))
      betting-fact-ids (map :betting.fact/id betting-facts)
      duplicate-betting-fact-ids (seq (for [[id freq] (frequencies betting-fact-ids)
                                            :when (> freq 1)]
                                        id))
      invalid-betting-facts (seq (for [f betting-facts
                                       :let [fid (:betting.fact/id f)
                                             ftype (:betting.fact/type f)
                                             event-id (lookup-id (:betting.fact/event f) :betting.event/id)
                                             bet-id (lookup-id (:betting.fact/bet f) :betting.bet/id)
                                             quote-id (lookup-id (:betting.fact/quote f) :betting.quote/id)]
                                       :when (or (not (uuid? fid))
                                                 (not (keyword? ftype))
                                                 (and event-id (not (contains? betting-event-id-set event-id)))
                                                 (and bet-id (not (contains? betting-bet-id-set bet-id)))
                                                 (and quote-id (not (contains? betting-quote-id-set quote-id))))]
                                   {:betting.fact/id fid
                                    :reason "Invalid id/type/ref"}))]
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
  (when client-missing
    (doseq [m client-missing]
      (println "Client fixture missing keys" m))
    (System/exit 1))
  (when duplicate-client-ids
    (println "Duplicate client IDs in fixtures:" duplicate-client-ids)
    (System/exit 1))
  (when invalid-clients
    (doseq [c invalid-clients]
      (println "Invalid client fixture" c))
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
  (when missing-clients
    (println "Task clients missing in client fixtures:" missing-clients)
    (System/exit 1))
  (when task-tag-errors
    (doseq [err task-tag-errors]
      (println "Task tag reference invalid" err))
    (System/exit 1))
  (when duplicate-content-tag-ids
    (println "Duplicate content tag IDs in fixtures:" duplicate-content-tag-ids)
    (System/exit 1))
  (when duplicate-content-tag-slugs
    (println "Duplicate content tag slugs in fixtures:" duplicate-content-tag-slugs)
    (System/exit 1))
  (when invalid-content-tags
    (doseq [err invalid-content-tags]
      (println "Invalid content tag fixture" err))
    (System/exit 1))
  (when duplicate-page-ids
    (println "Duplicate content page IDs in fixtures:" duplicate-page-ids)
    (System/exit 1))
  (when invalid-pages
    (doseq [err invalid-pages]
      (println "Invalid content page fixture" err))
    (System/exit 1))
  (when duplicate-block-ids
    (println "Duplicate content block IDs in fixtures:" duplicate-block-ids)
    (System/exit 1))
  (when invalid-blocks
    (doseq [err invalid-blocks]
      (println "Invalid content block fixture" err))
    (System/exit 1))
  (when block-tag-errors
    (doseq [err block-tag-errors]
      (println "Content block tag reference invalid" err))
    (System/exit 1))
  (when page-block-ref-errors
    (doseq [err page-block-ref-errors]
      (println "Content page block reference invalid" err))
    (System/exit 1))
  (when duplicate-betting-event-ids
    (println "Duplicate betting event IDs in fixtures:" duplicate-betting-event-ids)
    (System/exit 1))
  (when duplicate-betting-external-ids
    (println "Duplicate betting event external IDs in fixtures:" duplicate-betting-external-ids)
    (System/exit 1))
  (when invalid-betting-events
    (doseq [err invalid-betting-events]
      (println "Invalid betting event fixture" err))
    (System/exit 1))
  (when duplicate-betting-bookmaker-ids
    (println "Duplicate betting bookmaker IDs in fixtures:" duplicate-betting-bookmaker-ids)
    (System/exit 1))
  (when duplicate-betting-bookmaker-keys
    (println "Duplicate betting bookmaker keys in fixtures:" duplicate-betting-bookmaker-keys)
    (System/exit 1))
  (when invalid-betting-bookmakers
    (doseq [err invalid-betting-bookmakers]
      (println "Invalid betting bookmaker fixture" err))
    (System/exit 1))
  (when duplicate-betting-quote-ids
    (println "Duplicate betting quote IDs in fixtures:" duplicate-betting-quote-ids)
    (System/exit 1))
  (when invalid-betting-quotes
    (doseq [err invalid-betting-quotes]
      (println "Invalid betting quote fixture" err))
    (System/exit 1))
  (when duplicate-betting-bet-ids
    (println "Duplicate betting bet IDs in fixtures:" duplicate-betting-bet-ids)
    (System/exit 1))
  (when invalid-betting-bets
    (doseq [err invalid-betting-bets]
      (println "Invalid betting bet fixture" err))
    (System/exit 1))
  (when duplicate-betting-fact-ids
    (println "Duplicate betting fact IDs in fixtures:" duplicate-betting-fact-ids)
    (System/exit 1))
  (when invalid-betting-facts
    (doseq [err invalid-betting-facts]
      (println "Invalid betting fact fixture" err))
    (System/exit 1))
  (when missing-policy-files
    (println "Policy registry references missing/empty files:" missing-policy-files)
    (System/exit 1))
  (when missing-policy-refs
    (println "Agent registry references unknown policy ids:" missing-policy-refs)
    (System/exit 1))
  (println "User fixtures validated (count" (count users) ") and referenced by tasks.")
  (println "Client fixtures validated (count" (count clients) ") and referenced by tasks.")
  (println "Tag fixtures validated (count" (count tags) ") and referenced by tasks.")
  (println "Content fixtures validated (tags" (count content-tags) ", pages" (count content-pages) ", blocks" (count content-blocks) ").")
  (println "Betting fixtures validated (events" (count betting-events)
           ", bookmakers" (count betting-bookmakers)
           ", quotes" (count betting-quotes)
           ", bets" (count betting-bets)
           ", facts" (count betting-facts) ")."))
CLJ
}

check_schema_load() {
  check_clojure_available
  echo "Loading schema into temp Datomic..."
  (cd "$ROOT" && clojure -M -m darelwasl.checks.schema)
  echo "Running migration/backfill check..."
  (cd "$ROOT" && clojure -M -m darelwasl.checks.migration)
}

check_actions() {
  check_clojure_available
  echo "Running action contract checks..."
  (cd "$ROOT" && \
    DOCUMENT_VERIFY_SECRET="${DOCUMENT_VERIFY_SECRET:-check-secret}" \
    DOCUMENT_RENDERER="${DOCUMENT_RENDERER:-stub}" \
    FILES_STORAGE_DIR="${FILES_STORAGE_DIR:-$ROOT/.cpcache/files-actions}" \
    clojure -M -m darelwasl.checks.actions)
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

  if [[ "${SKIP_NPM_INSTALL:-}" = "1" ]]; then
    echo "Skipping npm install (SKIP_NPM_INSTALL=1)..."
  else
    echo "Installing npm dependencies..."
    (cd "$ROOT" && npm install --no-progress --no-audit)
  fi

  echo "Ensuring Playwright Chromium is installed..."
  ensure_playwright_browser

  echo "Building frontend for smoke..."
  (cd "$ROOT" && npm run check)

  local host="${APP_HOST:-127.0.0.1}"
  local port="${APP_PORT:-3100}"
  local base_url="http://${host}:${port}"
  DATOMIC_TMP="$(mktemp -d "${ROOT}/.cpcache/datomic-smoke-XXXXXX")"

  echo "Seeding Datomic fixtures (:mem storage) for app smoke..."
  (cd "$ROOT" && DATOMIC_STORAGE_DIR="$DATOMIC_TMP" APP_HOST="$host" APP_PORT="$port" SITE_ENABLED=false clojure -M:seed)

  echo "Starting backend server for app smoke on ${base_url}..."
  mkdir -p "$ROOT/.cpcache"
  kill_port_if_listening "$port"
  (cd "$ROOT" && DATOMIC_STORAGE_DIR="$DATOMIC_TMP" APP_HOST="$host" APP_PORT="$port" SITE_ENABLED=false clojure -M:dev >"$ROOT/.cpcache/app-smoke.log" 2>&1) &
  SERVER_PID=$!
  trap cleanup_server EXIT

  wait_for_health "${base_url}/health" 90

  echo "Running headless app smoke..."
  (cd "$ROOT" && APP_URL="$base_url" node scripts/app-smoke.js)

  cleanup_server
  trap - EXIT
}

check_import() {
  check_clojure_available
  local file="${IMPORT_FILE:-$ROOT/data/land/hrib_parcele_upisane_osobe.csv}"
  if [ ! -f "$file" ]; then
    echo "Import data file not found at $file"
    exit 1
  fi
  echo "Running land registry import against $file (temp DB)..."
  (cd "$ROOT" && clojure -M:import --file "$file" --temp)
  echo "Checking importer idempotency..."
  (cd "$ROOT" && clojure -M -m darelwasl.checks.idempotency)
}

target="${1:-all}"
case "$target" in
  governance) check_governance ;;
  query) check_query ;;
  map) check_map ;;
  registries) check_registries; check_registry_fields; check_edn_parse ;;
  schema) check_registries; check_registry_fields; check_edn_parse; check_schema_load ;;
  actions|action-contracts) check_registries; check_registry_fields; check_edn_parse; check_schema_load; check_actions ;;
  app-smoke|views) check_registries; check_registry_fields; check_edn_parse; check_schema_load; check_docs; check_actions; check_app_smoke ;;
  docs) check_registries; check_registry_fields; check_edn_parse; check_docs; check_query ;;
  import) check_registries; check_registry_fields; check_edn_parse; check_schema_load; check_import ;;
  all)
    check_governance
    check_map
    check_registries
    check_registry_fields
    check_edn_parse
    check_schema_load
    check_docs
    check_query
    check_import
    check_actions
    check_app_smoke
    ;;
  *)
    usage
    exit 1
    ;;
esac
