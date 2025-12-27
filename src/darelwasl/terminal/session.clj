(ns darelwasl.terminal.session
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.terminal.commands :as commands]
            [darelwasl.terminal.store :as store]
            [darelwasl.terminal.tmux :as tmux])
  (:import (java.io RandomAccessFile)
           (java.net InetSocketAddress ServerSocket Socket)
           (java.util UUID)))

(defn- now-ms [] (System/currentTimeMillis))

(defn- ensure-dir!
  [path]
  (let [dir (io/file path)
        abs (.getAbsoluteFile dir)]
    (when-not (.exists abs)
      (.mkdirs abs))
    abs))

(defn- write-manifest!
  [dir data]
  (spit (io/file dir "manifest.edn") (pr-str data)))

(defn- read-manifest
  [dir]
  (let [file (io/file dir "manifest.edn")]
    (when (.exists file)
      (try
        (read-string (slurp file))
        (catch Exception _ nil)))))

(defn- present-env
  [value]
  (when (and (string? value) (not (str/blank? value)))
    value))

(defn- read-secret-file
  [path]
  (when (and path (not (str/blank? path)))
    (let [file (io/file path)]
      (when (.exists file)
        (some-> (slurp file) str/trim (not-empty))))))

(defn- truthy?
  [value]
  (contains? #{"1" "true" "yes" "y" "on"} (str/lower-case (str value))))

(defn- https-url?
  [value]
  (when value
    (str/starts-with? (str/lower-case value) "https://")))

(def ^:private allowed-webhook-ports
  #{80 88 443 8443})

(defn- webhook-port-allowed?
  [raw-url]
  (try
    (let [uri (java.net.URI. (str raw-url))
          scheme (some-> (.getScheme uri) str/lower-case)
          port (.getPort uri)]
      (and (= "https" scheme)
           (or (= -1 port)
               (contains? allowed-webhook-ports port))))
    (catch Exception _ false)))

(defn- ensure-port
  [base-url port]
  (let [trimmed (-> base-url str/trim (str/replace #"/+$" ""))]
    (if (re-find #":\\d+$" trimmed)
      trimmed
      (str trimmed ":" port))))

(defn- dev-webhook-base-url
  [cfg env app-port]
  (let [explicit (present-env (get env "TELEGRAM_DEV_WEBHOOK_BASE_URL"))
        raw (or explicit (present-env (:public-base-url cfg)))]
    (when raw
      (if explicit
        raw
        (ensure-port raw app-port)))))

(defn- update-manifest!
  [dir f]
  (let [file (io/file dir "manifest.edn")
        current (read-manifest dir)
        next (f (or current {}))]
    (write-manifest! dir next)))

(defn- port-free?
  [port]
  (try
    (with-open [socket (ServerSocket. port)]
      true)
    (catch Exception _ false)))

(defn- port-open?
  [host port timeout-ms]
  (try
    (with-open [socket (Socket.)]
      (.connect socket (InetSocketAddress. host port) timeout-ms)
      true)
    (catch Exception _ false)))

(defn- used-ports
  [sessions & [exclude-id]]
  (->> sessions
       (remove (fn [session]
                 (= (:id session) exclude-id)))
       (mapcat (fn [session]
                 (vals (:ports session))))
       (filter number?)
       set))

(defn- reserved-ports
  [store & [exclude-id]]
  (->> (store/port-reservations store)
       (remove (fn [[session-id _ports]]
                 (= (str session-id) (str exclude-id))))
       (mapcat (fn [[_ ports]]
                 (vals ports)))
       (filter number?)
       set))

(defn- allocate-ports
  [cfg store & [opts]]
  (let [{:keys [exclude-id avoid-ports]} (if (map? opts) opts {:exclude-id opts})
        avoid-set (cond
                    (map? avoid-ports) (->> (vals avoid-ports)
                                            (filter number?)
                                            set)
                    (sequential? avoid-ports) (->> avoid-ports
                                                   (filter number?)
                                                   set)
                    :else #{})
        start (:port-range-start cfg)
        end (:port-range-end cfg)
        used (used-ports (store/list-sessions store) exclude-id)
        reserved (reserved-ports store exclude-id)
        blocked (set/union used reserved avoid-set)]
    (loop [port start]
      (when (> (+ port 1) end)
        (throw (ex-info "No available port block" {:start start :end end})))
      (let [block [port (inc port)]]
        (if (and (every? port-free? block)
                 (empty? (set/intersection blocked (set block))))
          {:app port
           :site (inc port)}
          (recur (+ port 2)))))))

(defn- run!
  [args & [{:keys [dir]}]]
  (let [{:keys [exit out err]} (apply sh/sh (cond-> args dir (concat [:dir dir])))]
    (when-not (zero? exit)
      (throw (ex-info "Command failed" {:args args :exit exit :err err})))
    (when (seq err)
      (log/debug "Command stderr" {:args args :err err}))
    out))

(def ^:private ignored-porcelain-files
  #{"AGENTS.md"})

(defn- porcelain-path
  [line]
  (when-let [[_ raw] (re-find #"^.. (.+)$" line)]
    (let [path (-> raw
                   (str/replace #"^\"|\"$" "")
                   (str/replace #"\\\"" "\"")
                   (str/replace #"^.+ -> " "")
                   str/trim)]
      path)))

(defn- git-dirty?
  [repo-dir]
  (let [output (run! ["git" "status" "--porcelain"] {:dir repo-dir})
        lines (remove str/blank? (str/split-lines output))
        relevant (remove (fn [line]
                           (when-let [path (porcelain-path line)]
                             (contains? ignored-porcelain-files path)))
                         lines)]
    (boolean (seq relevant))))

(defn- git-remote-url
  [repo-dir]
  (str/trim (run! ["git" "config" "--get" "remote.origin.url"] {:dir repo-dir})))

(defn- git-latest-subject
  [repo-dir]
  (str/trim (run! ["git" "log" "-1" "--pretty=%s"] {:dir repo-dir})))

(defn- parse-github-repo
  [remote-url]
  (when-let [match (re-find #"github\.com[:/](.+)$" (str remote-url))]
    (let [path (-> (second match)
                   (str/replace #"\.git$" ""))
          [owner repo] (str/split path #"/" 2)]
      (when (and owner repo)
        {:owner owner :repo repo}))))

(defn- credential-fill
  [repo-dir host]
  (let [{:keys [exit out err]} (sh/sh "git" "credential" "fill"
                                      :dir repo-dir
                                      :in (str "protocol=https\nhost=" host "\n\n"))]
    (when-not (zero? exit)
      (throw (ex-info "Credential lookup failed" {:exit exit :err err})))
    (let [pairs (->> (str/split-lines out)
                     (map #(str/split % #"=" 2))
                     (filter #(= 2 (count %)))
                     (map (fn [[k v]] [(keyword k) v]))
                     (into {}))]
      (when (seq pairs) pairs))))

(defn- github-auth-token
  [repo-dir]
  (or (present-env (System/getenv "TERMINAL_GITHUB_TOKEN"))
      (present-env (System/getenv "GITHUB_TOKEN"))
      (present-env (System/getenv "GH_TOKEN"))
      (present-env (System/getenv "DARELWASL_GITHUB_TOKEN"))
      (when-let [creds (credential-fill repo-dir "github.com")]
        (let [password (present-env (:password creds))
              username (present-env (:username creds))
              oauth-token (present-env (:oauth-token creds))]
          (cond
            oauth-token oauth-token
            (and password (not= password "x-oauth-basic")) password
            (and username (= password "x-oauth-basic")) username
            (and username (re-find #"^(ghp_|github_pat_)" username)) username
            :else nil)))))

(defn- github-request
  [{:keys [token method url body]}]
  (let [resp (http/request (cond-> {:method method
                                    :url url
                                    :throw-exceptions false
                                    :headers {"Accept" "application/vnd.github+json"
                                              "User-Agent" "darelwasl-terminal"}
                                    :as :text}
                             token (assoc-in [:headers "Authorization"] (str "token " token))
                             body (assoc :content-type :json
                                         :body (json/write-str body))))
        status (:status resp)
        raw (:body resp)
        parsed (when (and raw (not (str/blank? raw)))
                 (try
                   (json/read-str raw :key-fn keyword)
                   (catch Exception _ nil)))]
    {:status status :body parsed}))

(defn- find-existing-pr
  [{:keys [token owner repo head]}]
  (let [url (str "https://api.github.com/repos/" owner "/" repo "/pulls?head=" head)
        resp (github-request {:token token :method :get :url url})]
    (when (<= 200 (:status resp) 299)
      (-> resp :body first :html_url))))

(defn- create-pr!
  [{:keys [repo-dir owner repo branch]}]
  (let [token (github-auth-token repo-dir)
        _ (when-not token
            (throw (ex-info "Missing GitHub credentials" {:host "github.com"})))
        title (or (not-empty (git-latest-subject repo-dir))
                  (str "Terminal session " branch))
        body {:title title
              :head branch
              :base "main"
              :body (str "Automated PR from terminal session `" branch "`.")}
        url (str "https://api.github.com/repos/" owner "/" repo "/pulls")
        resp (github-request {:token token :method :post :url url :body body})
        status (:status resp)
        pr-url (get-in resp [:body :html_url])]
    (cond
      (and (<= 200 status 299) pr-url) pr-url
      (= status 422) (or (find-existing-pr {:token token
                                            :owner owner
                                            :repo repo
                                            :head (str owner ":" branch)})
                         (throw (ex-info "Pull request already exists but could not be resolved"
                                         {:status status :body (:body resp)})))
      :else (throw (ex-info "Failed to create pull request"
                            {:status status :body (:body resp)})))))

(defn- write-env!
  [file env-map]
  (let [lines (for [[k v] env-map]
                (str k "=" v))]
    (spit file (str/join "\n" lines))))

(defn- read-env
  [file]
  (when (.exists (io/file file))
    (->> (str/split-lines (slurp file))
         (remove str/blank?)
         (map #(str/split % #"=" 2))
         (reduce (fn [acc [k v]]
                   (if (and k v)
                     (assoc acc k v)
                     acc))
                 {}))))

(defn- update-env!
  [file f]
  (let [current (or (read-env file) {})
        next (f current)]
    (write-env! file next)))

(defn- write-askpass!
  [file token]
  (let [script (str "#!/usr/bin/env bash\n"
                    "case \"$1\" in\n"
                    "  *Username*) echo \"x-access-token\" ;;\n"
                    "  *Password*) echo \"" token "\" ;;\n"
                    "  *) echo \"\" ;;\n"
                    "esac\n")]
    (spit file script)
    (.setExecutable (io/file file) true)))

(defn- append-chat!
  [chat-file text]
  (spit chat-file (str text "\n") :append true))

(def ^:private ansi-csi-re #"\u001B\[[0-?]*[ -/]*[@-~]")
(def ^:private ansi-osc-re #"\u001B\][^\u0007]*(?:\u0007|\u001B\\)")
(def ^:private ansi-single-re #"\u001B[@-Z\\-_]")
(def ^:private command-re #"@command\s+(\{[^\n]+\})")
(def ^:private input-submit-delay-ms 200)
(def ^:private session-type-default :feature)
(def ^:private session-type-aliases
  {"feature" :feature
   "build" :feature
   "bugfix" :bugfix
   "hotfix" :bugfix
   "bugfix/hotfix" :bugfix
   "research" :research
   "spike" :research
   "integrator" :integrator
   "integration" :integrator
   "ops" :ops
   "admin" :ops
   "main-ops" :main-ops
   "mainops" :main-ops
   "main" :main-ops
   "data" :main-ops})
(def ^:private agents-resources
  {:feature "terminal/AGENTS.md"
   :bugfix "terminal/AGENTS-bugfix.md"
   :research "terminal/AGENTS-research.md"
   :integrator "terminal/AGENTS-integrator.md"
   :ops "terminal/AGENTS-ops.md"
   :main-ops "terminal/AGENTS-main-ops.md"})

(def ^:private app-start-timeout-ms (* 5 60 1000))
(def ^:private app-restart-min-interval-ms 15000)

(defn- normalize-session-type
  [value]
  (let [text (cond
               (keyword? value) (name value)
               (string? value) value
               :else nil)
        text (some-> text str/trim str/lower-case)]
    (get session-type-aliases text session-type-default)))

(defn- dev-bot-active?
  [store exclude-id]
  (some (fn [session]
          (and (:telegram/dev-bot? session)
               (not= (:id session) exclude-id)
               (not= :complete (:status session))
               (tmux/running? (:tmux session))))
        (store/list-sessions store)))

(defn- agents-resource-for
  [session-type]
  (get agents-resources session-type (agents-resources session-type-default)))

(defn- read-github-token
  []
  (let [env (System/getenv)
        home (or (get env "HOME") (System/getProperty "user.home"))
        candidates (->> [home "/home/darelwasl" "/root"]
                        (remove nil?)
                        distinct)]
    (some (fn [base]
            (let [cred-file (io/file base ".git-credentials")]
              (when (.exists cred-file)
                (some->> (slurp cred-file)
                         (re-seq #"https://[^:]+:([^@]+)@github\.com")
                         first
                         second))))
          candidates)))

(defn- sanitize-output
  [text]
  (let [safe-text (or text "")]
    (-> safe-text
        (str/replace "\r" "")
        (str/replace ansi-osc-re "")
        (str/replace ansi-csi-re "")
        (str/replace ansi-single-re "")
        (str/replace "\u001B(B" "")
        (str/trimr))))

(defn- parse-command-json
  [text]
  (try
    (json/read-str text :key-fn keyword)
    (catch Exception _ nil)))

(defn- normalize-command
  [command]
  (let [id (or (:id command) (:command/id command))
        type (or (:type command) (:command/type command))
        input (or (:input command) (:command/input command) {})]
    (when (and id type)
      {:id (str id)
       :type (if (keyword? type) (name type) (str type))
       :input input})))

(defn- extract-commands
  [output]
  (let [matches (re-seq command-re (or output ""))]
    (->> matches
         (map second)
         (keep parse-command-json)
         (keep normalize-command)
         (reduce (fn [acc cmd]
                   (assoc acc (:id cmd) cmd))
                 {})
         vals
         vec)))

(defn- codex-idle?
  [session]
  (let [raw (tmux/capture-pane (:tmux session))
        text (sanitize-output raw)
        last-line (->> (str/split-lines text)
                       reverse
                       (drop-while str/blank?)
                       first)
        prompt? (boolean (and last-line (re-find #"^[›>].*" last-line)))
        context? (str/includes? (str/lower-case text) "100% context left")
        ready? (str/includes? (str/lower-case text) "to get started")]
    (or prompt? context? ready?)))

(defn- read-output
  [file cursor max-bytes]
  (let [f (io/file file)]
    (if-not (.exists f)
      {:cursor cursor :chunk ""}
      (with-open [raf (RandomAccessFile. f "r")]
        (let [size (.length raf)
              start (long (min cursor size))
              remaining (- size start)
              to-read (int (min remaining max-bytes))
              buf (byte-array to-read)]
          (.seek raf start)
          (.readFully raf buf)
          {:cursor (+ start to-read)
           :chunk (sanitize-output (String. buf "UTF-8"))})))))

(defn- capture-output
  [session max-bytes]
  (let [text (sanitize-output (tmux/capture-pane (:tmux session)))
        length (count text)
        trimmed (if (> length max-bytes)
                  (subs text (- length max-bytes))
                  text)]
    {:cursor length
     :chunk trimmed
     :mode :replace}))

(defn- write-agents!
  [repo-dir session-type]
  (let [resource-path (agents-resource-for session-type)
        resource (or (io/resource resource-path)
                     (io/resource (agents-resource-for session-type-default)))]
    (when resource
      (spit (io/file repo-dir "AGENTS.md") (slurp resource))
      (let [exclude-file (io/file repo-dir ".git" "info" "exclude")]
        (when (.exists exclude-file)
          (let [current (slurp exclude-file)]
            (when-not (str/includes? current "AGENTS.md")
              (spit exclude-file (str current "\nAGENTS.md\n")))))))))

(defn- start-app-window!
  [session]
  (tmux/new-window! (:tmux session)
                    "app"
                    (:repo-dir session)
                    (:env-file session)
                    "scripts/run-service.sh"))

(defn- start-site-window!
  [session]
  (tmux/new-window! (:tmux session)
                    "site"
                    (:repo-dir session)
                    (:env-file session)
                    "scripts/run-site.sh"))

(defn- list-session-dirs
  [work-dir]
  (let [root (ensure-dir! work-dir)
        entries (or (.listFiles root) [])]
    (->> entries
          (filter #(.isDirectory ^java.io.File %))
          (map (fn [dir]
                 {:id (.getName ^java.io.File dir)
                  :dir dir}))
          (sort-by :id))))

(defn- list-log-dirs
  [logs-dir]
  (let [root (ensure-dir! logs-dir)
        entries (or (.listFiles root) [])]
    (->> entries
         (filter #(.isDirectory ^java.io.File %))
         (map (fn [dir]
                {:id (.getName ^java.io.File dir)
                 :dir dir}))
         (sort-by :id))))

(def ^:private stale-idle-ms (* 15 60 1000))

(defn- chat-idle?
  [chat-file]
  (let [file (io/file chat-file)]
    (if (.exists file)
      (>= (- (now-ms) (.lastModified file)) stale-idle-ms)
      true)))

(defn- cleanup-eligible?
  [session]
  (let [tmux-running? (tmux/running? (:tmux session))
        app-port (get-in session [:ports :app])
        app-listening? (when app-port (port-open? "127.0.0.1" app-port 200))
        idle? (chat-idle? (:chat-log session))]
    (and (not tmux-running?)
         (not app-listening?)
         idle?)))

(defn- mark-session-stale!
  [logs-dir session-id cleanup?]
  (let [dir (io/file logs-dir session-id)
        now (now-ms)]
    (when (.exists dir)
      (update-manifest! dir (fn [m]
                              (assoc (or m {})
                                     :stale? true
                                     :stale-at now
                                     :cleanup-eligible? cleanup?))))))

(defn reconcile-orphaned-sessions!
  [store cfg]
  (let [work-dir (:work-dir cfg)
        logs-root (:logs-dir cfg)
        tmux-prefix (:tmux-prefix cfg)
        stored-ids (set (keys @(:sessions store)))]
    (doseq [{:keys [id dir]} (list-session-dirs work-dir)]
      (when-not (contains? stored-ids id)
        (let [tmux-session (tmux/session-name tmux-prefix id)
              repo-dir (io/file dir "repo")
              datomic-dir (io/file dir "datomic")
              files-dir (io/file dir "files")
              chat-file (io/file dir "chat.log")
              env-file (io/file dir "session.env")
              logs-dir (io/file logs-root id)
              manifest (read-manifest logs-dir)
              closed-at (:closed-at manifest)
              ports (or (:ports manifest) {})
              now (now-ms)
              session-name (or (:name manifest)
                               (str "session-" (subs id 0 8)))
              session-type (normalize-session-type (:type manifest))
              session {:id id
                       :name session-name
                       :type session-type
                       :status :stale
                       :created-at (or (:created-at manifest) now)
                       :updated-at now
                       :ports ports
                       :repo-dir (.getPath repo-dir)
                       :datomic-dir (.getPath datomic-dir)
                       :files-dir (.getPath files-dir)
                       :work-dir (.getPath dir)
                       :logs-dir (.getPath logs-dir)
                       :chat-log (.getPath chat-file)
                       :env-file (.getPath env-file)
                       :tmux tmux-session
                       :auto-start-app? (true? (:auto-start-app? cfg))
                       :auto-start-site? (true? (:auto-start-site? cfg))
                       :auto-run-commands? true
                       :command-ids #{}
                       :stale? true}
              cleanup? (cleanup-eligible? session)
              next-session (assoc session :cleanup-eligible? cleanup?)]
          (if closed-at
            (log/info "Skipping closed session on disk" {:id id :closed-at closed-at})
            (do
              (when (tmux/running? tmux-session)
                (log/warn "Orphaned session still has active tmux" {:id id :tmux tmux-session}))
              (store/upsert-session! store next-session)
              (mark-session-stale! logs-root id cleanup?)
              (log/info "Marked orphaned session as stale" {:id id :cleanup-eligible cleanup?}))))))))

(defn rebuild-port-reservations!
  [store cfg]
  (let [from-store (->> (store/list-sessions store)
                        (keep (fn [session]
                                (when (seq (:ports session))
                                  [(:id session) (:ports session)])))
                        (into {}))
        from-manifests (->> (list-log-dirs (:logs-dir cfg))
                            (keep (fn [{:keys [id dir]}]
                                    (when-let [manifest (read-manifest dir)]
                                      (when (and (nil? (:closed-at manifest))
                                                 (seq (:ports manifest)))
                                        [id (:ports manifest)]))))
                            (into {}))
        combined (reduce (fn [acc [id ports]]
                           (if (contains? acc id)
                             acc
                             (assoc acc id ports)))
                         from-store
                         from-manifests)]
    (store/set-port-reservations! store combined)))

(defn create-session!
  [store cfg {:keys [name type dev-bot?]}]
  (let [id (str (UUID/randomUUID))
        session-type (normalize-session-type type)
        session-name (or (some-> name str/trim not-empty)
                         (str "session-" (subs id 0 8)))
         env (System/getenv)
         git-name (or (present-env (get env "TERMINAL_GIT_NAME"))
                      (present-env (get env "GIT_AUTHOR_NAME"))
                      "darelwasl-bot")
         git-email (or (present-env (get env "TERMINAL_GIT_EMAIL"))
                       (present-env (get env "GIT_AUTHOR_EMAIL"))
                       "bot@darelwasl.local")
         openai-key (or (get env "TERMINAL_OPENAI_API_KEY")
                        (get env "OPENAI_API_KEY"))
         github-token (or (present-env (get env "TERMINAL_GITHUB_TOKEN"))
                          (present-env (get env "GITHUB_TOKEN"))
                          (present-env (get env "GH_TOKEN"))
                          (present-env (get env "DARELWASL_GITHUB_TOKEN"))
                          (read-github-token))
        telegram-dev-token (or (present-env (get env "TELEGRAM_DEV_BOT_TOKEN"))
                               (read-secret-file (present-env (get env "TELEGRAM_DEV_BOT_TOKEN_FILE")))
                               (read-secret-file ".secrets/telegram_bot_token_dev"))
        telegram-dev-secret (present-env (get env "TELEGRAM_DEV_WEBHOOK_SECRET"))
        telegram-dev-webhook-enabled (present-env (get env "TELEGRAM_DEV_WEBHOOK_ENABLED"))
        telegram-dev-polling-enabled (present-env (get env "TELEGRAM_DEV_POLLING_ENABLED"))
        telegram-dev-polling-interval (present-env (get env "TELEGRAM_DEV_POLLING_INTERVAL_MS"))
        telegram-dev-auto-bind-username (present-env (get env "TELEGRAM_DEV_AUTO_BIND_USERNAME"))
        telegram-dev-commands-enabled (present-env (get env "TELEGRAM_DEV_COMMANDS_ENABLED"))
        telegram-dev-notifications-enabled (present-env (get env "TELEGRAM_DEV_NOTIFICATIONS_ENABLED"))
        telegram-dev-timeout (present-env (get env "TELEGRAM_DEV_HTTP_TIMEOUT_MS"))
        telegram-dev-ttl (present-env (get env "TELEGRAM_DEV_LINK_TOKEN_TTL_MS"))
        dev-bot? (true? dev-bot?)
        work-root (ensure-dir! (:work-dir cfg))
        logs-root (ensure-dir! (:logs-dir cfg))
        session-root (io/file work-root id)
         repo-dir (io/file session-root "repo")
         datomic-dir (io/file session-root "datomic")
         files-dir (io/file session-root "files")
         chat-file (io/file session-root "chat.log")
         env-file (io/file session-root "session.env")
         askpass-file (io/file session-root "git-askpass.sh")
         logs-dir (io/file logs-root id)
         ports (allocate-ports cfg store)
         auto-start-app? (true? (:auto-start-app? cfg))
         auto-start-site? (true? (:auto-start-site? cfg))
         site-enabled? auto-start-site?
        ports (cond-> ports
                (not site-enabled?) (assoc :site nil))
        telegram-dev-base-url (dev-webhook-base-url cfg env (:app ports))
        webhook-explicit? (when telegram-dev-webhook-enabled
                            (truthy? telegram-dev-webhook-enabled))
        polling-explicit? (when telegram-dev-polling-enabled
                            (truthy? telegram-dev-polling-enabled))
        webhook-enabled? (cond
                           (true? webhook-explicit?)
                           (do
                             (when (and telegram-dev-base-url
                                        (not (webhook-port-allowed? telegram-dev-base-url)))
                               (log/warn "Dev bot webhook disabled: invalid port"
                                         {:url telegram-dev-base-url
                                          :allowed allowed-webhook-ports}))
                             (webhook-port-allowed? telegram-dev-base-url))
                           (false? webhook-explicit?) false
                           :else (webhook-port-allowed? telegram-dev-base-url))
        polling-enabled? (if (some? polling-explicit?)
                           polling-explicit?
                           (not webhook-enabled?))
        auto-bind-username (or telegram-dev-auto-bind-username "damjan")
        datomic-system "darelwasl"
        datomic-db "darelwasl"
        tmux-session (tmux/session-name (:tmux-prefix cfg) id)
        branch (str "terminal/" (subs id 0 8))
        now (now-ms)]
    (when dev-bot?
      (when-not telegram-dev-token
        (throw (ex-info "Dev bot is not configured on the server"
                        {:status 400 :message "Dev bot is not configured on the server"})))
      (when (dev-bot-active? store nil)
        (throw (ex-info "Dev bot already running in another session"
                        {:status 409 :message "Dev bot already running in another session"}))))
    (store/reserve-ports! store id ports)
    (try
      (.mkdirs session-root)
      (.mkdirs datomic-dir)
      (.mkdirs files-dir)
      (.mkdirs logs-dir)
      (write-manifest! logs-dir {:id id
                                 :name session-name
                                 :type session-type
                                 :created-at now
                                 :ports ports
                                 :datomic-dir (.getPath datomic-dir)
                                 :files-dir (.getPath files-dir)})
      (run! ["git" "clone" (:repo-url cfg) (.getPath repo-dir)])
      (run! ["git" "checkout" "-b" branch] {:dir repo-dir})
      (run! ["git" "config" "user.name" git-name] {:dir repo-dir})
      (run! ["git" "config" "user.email" git-email] {:dir repo-dir})
      (write-agents! repo-dir session-type)
      (when github-token
        (write-askpass! askpass-file github-token))
      (let [base-env {"APP_HOST" "0.0.0.0"
                      "APP_PORT" (:app ports)
                      "DATOMIC_STORAGE_DIR" (.getPath datomic-dir)
                      "DATOMIC_SYSTEM" datomic-system
                      "DATOMIC_DB_NAME" datomic-db
                      "FILES_STORAGE_DIR" (.getPath files-dir)
                      "ALLOW_FIXTURE_SEED" "false"
                      "TERMINAL_SESSION_ID" id
                      "TERMINAL_LOG_DIR" (.getPath logs-dir)
                      "TERMINAL_API_URL" (:base-url cfg)
                      "GIT_AUTHOR_NAME" git-name
                      "GIT_AUTHOR_EMAIL" git-email
                      "GIT_COMMITTER_NAME" git-name
                      "GIT_COMMITTER_EMAIL" git-email}
            base-env (cond-> base-env
                       site-enabled? (assoc "SITE_HOST" "0.0.0.0"
                                            "SITE_PORT" (:site ports))
                       openai-key (assoc "OPENAI_API_KEY" openai-key)
                       github-token (assoc "GITHUB_TOKEN" github-token
                                           "GH_TOKEN" github-token
                                           "GIT_ASKPASS" (.getPath askpass-file)
                                           "GIT_TERMINAL_PROMPT" "0"))
            telegram-env (cond-> {}
                           telegram-dev-token (assoc "TELEGRAM_DEV_BOT_TOKEN" telegram-dev-token)
                           dev-bot? (assoc "TELEGRAM_BOT_TOKEN" telegram-dev-token
                                           "TELEGRAM_WEBHOOK_ENABLED" (if webhook-enabled? "true" "false")
                                           "TELEGRAM_POLLING_ENABLED" (if polling-enabled? "true" "false")
                                           "TELEGRAM_AUTO_BIND_USERNAME" auto-bind-username
                                           "TELEGRAM_COMMANDS_ENABLED" (or telegram-dev-commands-enabled "true")
                                           "TELEGRAM_NOTIFICATIONS_ENABLED" (or telegram-dev-notifications-enabled "false"))
                           (and dev-bot? telegram-dev-polling-interval)
                           (assoc "TELEGRAM_POLLING_INTERVAL_MS" telegram-dev-polling-interval)
                           (and dev-bot? telegram-dev-secret) (assoc "TELEGRAM_WEBHOOK_SECRET" telegram-dev-secret)
                           (and dev-bot? telegram-dev-base-url) (assoc "TELEGRAM_WEBHOOK_BASE_URL" telegram-dev-base-url)
                           (and dev-bot? telegram-dev-timeout) (assoc "TELEGRAM_HTTP_TIMEOUT_MS" telegram-dev-timeout)
                           (and dev-bot? telegram-dev-ttl) (assoc "TELEGRAM_LINK_TOKEN_TTL_MS" telegram-dev-ttl))]
        (write-env! env-file (merge base-env telegram-env)))
      (tmux/start! tmux-session (.getPath repo-dir) (.getPath env-file) (:codex-command cfg))
      (tmux/pipe-output! tmux-session (.getPath chat-file))
      (when auto-start-app?
        (start-app-window! {:tmux tmux-session
                            :repo-dir (.getPath repo-dir)
                            :env-file (.getPath env-file)}))
      (when auto-start-site?
        (start-site-window! {:tmux tmux-session
                             :repo-dir (.getPath repo-dir)
                             :env-file (.getPath env-file)}))
      (let [session {:id id
                     :name session-name
                     :type session-type
                     :status :running
                     :created-at now
                     :updated-at now
                     :ports ports
                     :repo-dir (.getPath repo-dir)
                     :datomic-dir (.getPath datomic-dir)
                     :datomic-system datomic-system
                     :datomic-db datomic-db
                     :work-dir (.getPath session-root)
                     :logs-dir (.getPath logs-dir)
                     :files-dir (.getPath files-dir)
                     :chat-log (.getPath chat-file)
                     :env-file (.getPath env-file)
                     :tmux tmux-session
                     :branch branch
                     :auto-start-app? auto-start-app?
                     :auto-start-site? auto-start-site?
                     :auto-run-commands? true
                     :telegram/dev-bot? dev-bot?
                     :app-started-at (when auto-start-app? now)
                     :auto-continue-enabled? true
                     :command-ids #{}}]
        (store/upsert-session! store session))
      (catch Exception e
        (store/release-ports! store id)
        (throw e)))))

(defn present-session
  [session]
  (let [running (tmux/running? (:tmux session))
        stale? (true? (:stale? session))
        status (cond
                 (= :complete (:status session)) :complete
                 stale? :stale
                 running :running
                 :else :idle)]
    (-> session
        (assoc :running? running
               :status status)
         (dissoc :repo-dir :datomic-dir :datomic-system :datomic-db :work-dir :env-file :chat-log))))

(defn send-input!
  [session text]
  (when-not (tmux/running? (:tmux session))
    (throw (ex-info "Session not running" {:id (:id session)})))
  (append-chat! (:chat-log session) (str "> " text))
  (tmux/send-keys! (:tmux session) [text])
  (Thread/sleep input-submit-delay-ms)
  (tmux/send-keys! (:tmux session) ["Enter"])
  true)

(defn send-keys!
  [session keys]
  (when-not (tmux/running? (:tmux session))
    (throw (ex-info "Session not running" {:id (:id session)})))
  (tmux/send-keys! (:tmux session) keys)
  true)

(defn output-since
  [session cursor max-bytes]
  (if (tmux/running? (:tmux session))
    (capture-output session max-bytes)
    (read-output (:chat-log session) cursor max-bytes)))

(defn- app-health?
  [port]
  (try
    (let [resp (http/request {:method :get
                              :url (str "http://127.0.0.1:" port "/health")
                              :throw-exceptions false
                              :as :text
                              :socket-timeout 500
                              :conn-timeout 500})
          status (:status resp)
          body (:body resp)
          parsed (when (and body (not (str/blank? body)))
                   (json/read-str body :key-fn keyword))]
      (and (= 200 status)
           (= "darelwasl" (:service parsed))))
    (catch Exception _ false)))

(defn app-ready?
  [session]
  (when-let [port (get-in session [:ports :app])]
    (app-health? port)))

(defn claim-command!
  [store session command-id]
  (let [command-id (some-> command-id str/trim)
        seen (set (or (:command-ids session) #{}))]
    (cond
      (str/blank? command-id)
      {:status :error :message "Missing command id"}

      (contains? seen command-id)
      {:status :duplicate}

      :else
      (let [next-session (assoc session
                                :command-ids (conj seen command-id)
                                :updated-at (now-ms))]
        (store/upsert-session! store next-session)
        {:status :claimed}))))

(defn- command-error
  [status message & [details]]
  {:error {:status status
           :message message
           :details details}})

(declare normalize-command-type)

(defn- resolve-upload-path
  [session command]
  (let [input (:input command)
        raw-path (or (:path input) (get input "path"))
        path (when raw-path (str raw-path))
        repo-dir (:repo-dir session)]
    (if (and path repo-dir)
      (let [file (io/file path)
            file (if (.isAbsolute file) file (io/file repo-dir path))]
        (assoc-in command [:input :path] (.getPath file)))
      command)))

(defn- normalize-command
  [command]
  (let [command-type (normalize-command-type (:type command))]
    (if (= command-type "library.add")
      (-> command
          (assoc :type "file.upload")
          (update :input #(or % {})))
      command)))

(defn- normalize-command-type
  [value]
  (let [raw (cond
              (keyword? value) (name value)
              (string? value) value
              :else nil)]
    (some-> raw str/trim str/lower-case)))

(defn run-command!
  [state store session command actor]
  (let [command-id (:id command)
        claim (claim-command! store session command-id)]
    (case (:status claim)
      :claimed
      (let [command (normalize-command command)
            command-type (normalize-command-type (:type command))
            command (if (= command-type "file.upload")
                      (resolve-upload-path session command)
                      command)
            result (try
                     (commands/execute-command! state (:id session) command actor)
                     (catch Exception e
                       (log/warn e "Failed to execute terminal command" {:id command-id})
                       (command-error 500 "Command execution failed" (.getMessage e))))
            message (commands/command->message command result)]
        (try
          (if (tmux/running? (:tmux session))
            (send-input! session message)
            (append-chat! (:chat-log session) message))
          (catch Exception e
            (log/warn e "Failed to deliver command result" {:id command-id})))
        {:status :ok
         :result result})
      :duplicate {:status :duplicate}
      {:status :error
       :message (:message claim)})))

(defn auto-run-commands!
  [state store session output]
  (let [auto-run? (get session :auto-run-commands? true)
        commands (extract-commands output)]
    (when (and auto-run?
               (tmux/running? (:tmux session))
               (seq commands))
      (doseq [command commands]
        (run-command! state store session command nil)))))

(declare restart-app!)

(defn- apply-ports-to-env!
  [session ports]
  (let [site-port (:site ports)]
    (update-env! (:env-file session)
                 (fn [env]
                   (cond-> (assoc env "APP_PORT" (:app ports))
                     site-port (assoc "SITE_PORT" site-port)
                     (nil? site-port) (dissoc "SITE_PORT"))))))

(defn reallocate-ports!
  [store cfg session]
  (when-not (tmux/running? (:tmux session))
    (throw (ex-info "Session not running" {:id (:id session)})))
  (when-not (and (:env-file session) (.exists (io/file (:env-file session))))
    (throw (ex-info "Session env file missing" {:id (:id session)})))
  (let [session-id (:id session)
        auto-start-site? (get session :auto-start-site? (:auto-start-site? cfg))
        site-enabled? (and auto-start-site? (some? (get-in session [:ports :site])))
        next-ports (allocate-ports cfg store {:exclude-id session-id
                                              :avoid-ports (:ports session)})
        next-ports (cond-> next-ports
                     (not site-enabled?) (assoc :site nil))
        now (now-ms)]
    (store/reserve-ports! store session-id next-ports)
    (apply-ports-to-env! session next-ports)
    (tmux/kill-window! (:tmux session) "app")
    (start-app-window! session)
    (tmux/kill-window! (:tmux session) "site")
    (when site-enabled?
      (start-site-window! session))
    (let [next-session (assoc session
                              :ports next-ports
                              :updated-at now
                              :app-started-at now
                              :app-restart-attempted-at now)]
      (update-manifest! (:logs-dir session)
                        (fn [m]
                          (assoc (or m {})
                                 :ports next-ports
                                 :ports-reallocated-at now)))
      (store/upsert-session! store next-session))))

(defn ensure-app-running!
  [store cfg session]
  (let [tmux-session (:tmux session)
        app-port (get-in session [:ports :app])
        auto-start? (get session :auto-start-app? true)]
    (if (and auto-start? app-port (tmux/running? tmux-session))
      (let [now (now-ms)
            ready? (app-ready? session)
            listening? (when app-port (port-open? "127.0.0.1" app-port 200))
            window? (contains? (tmux/window-names tmux-session) "app")
            last-attempt (or (:app-restart-attempted-at session) 0)
            app-started-at (:app-started-at session)
            can-attempt? (>= (- now last-attempt) app-restart-min-interval-ms)]
        (cond
          ready? session
          (and can-attempt? listening? (not ready?))
          (do
            (reallocate-ports! store cfg session)
            (store/get-session store (:id session)))
          (and can-attempt? (not window?))
          (let [next-session (assoc session
                                    :app-started-at now
                                    :app-restart-attempted-at now
                                    :updated-at now)]
            (try
              (start-app-window! session)
              (store/upsert-session! store next-session)
              next-session
              (catch Exception _
                session)))
          (and can-attempt? window? (nil? app-started-at))
          (let [next-session (assoc session
                                    :app-started-at now
                                    :app-restart-attempted-at now
                                    :updated-at now)]
            (store/upsert-session! store next-session)
            next-session)
          (and can-attempt?
               window?
               app-started-at
               (>= (- now app-started-at) app-start-timeout-ms))
          (do
            (reallocate-ports! store cfg session)
            (store/get-session store (:id session)))
          :else session))
      session)))

(defn resume-session!
  [store cfg session]
  (when (= :complete (:status session))
    (throw (ex-info "Session already completed" {:id (:id session)})))
  (when (tmux/running? (:tmux session))
    (throw (ex-info "Session already running" {:id (:id session)})))
  (when (and (:telegram/dev-bot? session)
             (dev-bot-active? store (:id session)))
    (throw (ex-info "Dev bot already running in another session"
                    {:status 409 :message "Dev bot already running in another session"})))
  (doseq [[label path] [[:repo-dir (:repo-dir session)]
                        [:env-file (:env-file session)]
                        [:chat-log (:chat-log session)]]]
    (when (or (nil? path) (not (.exists (io/file path))))
      (throw (ex-info "Session files missing; cannot resume"
                      {:id (:id session) :missing label}))))
  (let [auto-start-app? (get session :auto-start-app? (:auto-start-app? cfg))
        auto-start-site? (get session :auto-start-site? (:auto-start-site? cfg))
        site-enabled? (and auto-start-site?
                           (some? (get-in session [:ports :site])))
        started-app? (atom false)]
    (tmux/start! (:tmux session) (:repo-dir session) (:env-file session) (:codex-command cfg))
    (tmux/pipe-output! (:tmux session) (:chat-log session))
    (when auto-start-app?
      (start-app-window! session)
      (reset! started-app? true))
    (when site-enabled?
      (start-site-window! session))
    (let [now (now-ms)
          next-session (assoc session
                              :status :running
                              :updated-at now
                              :app-started-at (when @started-app? now))]
      (update-manifest! (:logs-dir session)
                        (fn [m]
                          (assoc (or m {})
                                 :resumed-at now)))
      (store/upsert-session! store next-session))))

(defn restart-app!
  [store session]
  (when-not (tmux/running? (:tmux session))
    (throw (ex-info "Session not running" {:id (:id session)})))
  (when-not (get-in session [:ports :app])
    (throw (ex-info "Session has no app port" {:id (:id session)})))
  (tmux/kill-window! (:tmux session) "app")
  (start-app-window! session)
  (let [now (now-ms)
        next-session (assoc session
                            :updated-at now
                            :app-started-at now
                            :app-restart-attempted-at now)]
    (update-manifest! (:logs-dir session)
                      (fn [m]
                        (assoc (or m {})
                               :app-restarted-at now)))
    (store/upsert-session! store next-session)))

(defn interrupt-session!
  [store session]
  (when-not (tmux/running? (:tmux session))
    (throw (ex-info "Session not running" {:id (:id session)})))
  (when-not (codex-idle? session)
    (tmux/send-keys! (:tmux session) ["C-c"]))
  (let [now (now-ms)
        next-session (assoc session
                            :updated-at now
                            :auto-continue-enabled? false)]
    (update-manifest! (:logs-dir session)
                      (fn [m]
                        (assoc (or m {})
                               :interrupted-at now)))
    (store/upsert-session! store next-session)))

(declare complete-session!)

(defn verify-session!
  [store session]
  (let [repo-dir (:repo-dir session)
        branch (:branch session)
        dirty? (git-dirty? repo-dir)]
    (when dirty?
      (throw (ex-info "Uncommitted changes present" {:branch branch})))
    (run! ["git" "push" "-u" "origin" branch] {:dir repo-dir})
    (let [remote-url (git-remote-url repo-dir)
          {:keys [owner repo]} (parse-github-repo remote-url)]
      (when-not (and owner repo)
        (throw (ex-info "Unsupported remote URL" {:remote remote-url})))
      (let [pr-url (create-pr! {:repo-dir repo-dir
                                :owner owner
                                :repo repo
                                :branch branch})]
        (update-manifest! (:logs-dir session)
                          (fn [m]
                            (assoc (or m {})
                                   :verified-at (now-ms)
                                   :pr-url pr-url)))
        {:status "ok"
         :pr-url pr-url}))))

(defn complete-session!
  [store session]
  (tmux/kill! (:tmux session))
  (update-manifest! (:logs-dir session)
                    (fn [m]
                      (assoc m :closed-at (now-ms))))
   (doseq [path [(:repo-dir session)
                 (:datomic-dir session)
                 (:chat-log session)
                 (:env-file session)]]
     (when path
       (let [f (io/file path)]
         (when (.exists f)
           (if (.isDirectory f)
             (run! ["rm" "-rf" (.getPath f)])
             (.delete f))))))
  (store/release-ports! store (:id session))
  (store/delete-session! store (:id session))
  true)
