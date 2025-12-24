(ns darelwasl.terminal.session
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
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

(defn- present-env
  [value]
  (when (and (string? value) (not (str/blank? value)))
    value))

 (defn- update-manifest!
   [dir f]
   (let [file (io/file dir "manifest.edn")
         current (when (.exists file)
                   (try
                     (read-string (slurp file))
                     (catch Exception _ nil)))
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
   [sessions]
   (->> sessions
        (mapcat (fn [session]
                  (vals (:ports session))))
        (filter number?)
        set))

 (defn- allocate-ports
   [cfg sessions]
   (let [start (:port-range-start cfg)
         end (:port-range-end cfg)
         used (used-ports sessions)]
     (loop [port start]
       (when (> (+ port 1) end)
         (throw (ex-info "No available port block" {:start start :end end})))
       (let [block [port (inc port)]]
         (if (and (every? port-free? block)
                  (empty? (set/intersection used (set block))))
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
(def ^:private input-submit-delay-ms 200)
(def ^:private agents-resource "terminal/AGENTS.md")

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
  [repo-dir]
  (when-let [resource (io/resource agents-resource)]
    (spit (io/file repo-dir "AGENTS.md") (slurp resource))
    (let [exclude-file (io/file repo-dir ".git" "info" "exclude")]
      (when (.exists exclude-file)
        (let [current (slurp exclude-file)]
          (when-not (str/includes? current "AGENTS.md")
            (spit exclude-file (str current "\nAGENTS.md\n"))))))))

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

 (defn- update-log-closed!
   [logs-dir session-id]
   (let [dir (io/file logs-dir session-id)]
     (when (.exists dir)
       (update-manifest! dir (fn [m] (assoc (or m {}) :closed-at (now-ms)))))))

 (defn reconcile-orphaned-sessions!
   [store cfg]
   (let [work-dir (:work-dir cfg)
         logs-dir (:logs-dir cfg)
         tmux-prefix (:tmux-prefix cfg)
         stored-ids (set (keys @(:sessions store)))]
     (doseq [{:keys [id dir]} (list-session-dirs work-dir)]
       (when-not (contains? stored-ids id)
         (let [tmux-session (tmux/session-name tmux-prefix id)]
           (if (tmux/running? tmux-session)
             (log/warn "Skipping orphaned session with active tmux" {:id id :tmux tmux-session})
             (do
               (run! ["rm" "-rf" (.getPath ^java.io.File dir)])
               (update-log-closed! logs-dir id)
               (log/info "Deleted orphaned session dir" {:id id}))))))))

 (defn create-session!
   [store cfg {:keys [name]}]
   (let [id (str (UUID/randomUUID))
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
         telegram-dev-token (present-env (get env "TELEGRAM_DEV_BOT_TOKEN"))
         telegram-dev-secret (present-env (get env "TELEGRAM_DEV_WEBHOOK_SECRET"))
         telegram-dev-base-url (present-env (get env "TELEGRAM_DEV_WEBHOOK_BASE_URL"))
         telegram-dev-webhook-enabled (present-env (get env "TELEGRAM_DEV_WEBHOOK_ENABLED"))
         telegram-dev-commands-enabled (present-env (get env "TELEGRAM_DEV_COMMANDS_ENABLED"))
         telegram-dev-notifications-enabled (present-env (get env "TELEGRAM_DEV_NOTIFICATIONS_ENABLED"))
         telegram-dev-timeout (present-env (get env "TELEGRAM_DEV_HTTP_TIMEOUT_MS"))
         telegram-dev-ttl (present-env (get env "TELEGRAM_DEV_LINK_TOKEN_TTL_MS"))
         work-root (ensure-dir! (:work-dir cfg))
         logs-root (ensure-dir! (:logs-dir cfg))
         session-root (io/file work-root id)
         repo-dir (io/file session-root "repo")
         datomic-dir (io/file session-root "datomic")
         chat-file (io/file session-root "chat.log")
         env-file (io/file session-root "session.env")
         askpass-file (io/file session-root "git-askpass.sh")
         logs-dir (io/file logs-root id)
         ports (allocate-ports cfg (store/list-sessions store))
         auto-start-app? (true? (:auto-start-app? cfg))
         auto-start-site? (true? (:auto-start-site? cfg))
         site-enabled? auto-start-site?
         ports (cond-> ports
                 (not site-enabled?) (assoc :site nil))
         tmux-session (tmux/session-name (:tmux-prefix cfg) id)
         branch (str "terminal/" (subs id 0 8))
         now (now-ms)]
     (.mkdirs session-root)
     (.mkdirs datomic-dir)
     (.mkdirs logs-dir)
     (write-manifest! logs-dir {:id id
                                :name session-name
                                :created-at now
                                :ports ports})
     (run! ["git" "clone" (:repo-url cfg) (.getPath repo-dir)])
     (run! ["git" "checkout" "-b" branch] {:dir repo-dir})
     (run! ["git" "config" "user.name" git-name] {:dir repo-dir})
     (run! ["git" "config" "user.email" git-email] {:dir repo-dir})
     (write-agents! repo-dir)
     (when github-token
       (write-askpass! askpass-file github-token))
     (let [base-env {"APP_HOST" "0.0.0.0"
                     "APP_PORT" (:app ports)
                     "DATOMIC_STORAGE_DIR" (.getPath datomic-dir)
                     "DATOMIC_SYSTEM" "darelwasl"
                     "DATOMIC_DB_NAME" "darelwasl"
                     "ALLOW_FIXTURE_SEED" "true"
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
                          telegram-dev-token (assoc "TELEGRAM_BOT_TOKEN" telegram-dev-token
                                                    "TELEGRAM_WEBHOOK_ENABLED" (or telegram-dev-webhook-enabled "false")
                                                    "TELEGRAM_COMMANDS_ENABLED" (or telegram-dev-commands-enabled "true")
                                                    "TELEGRAM_NOTIFICATIONS_ENABLED" (or telegram-dev-notifications-enabled "false"))
                          telegram-dev-secret (assoc "TELEGRAM_WEBHOOK_SECRET" telegram-dev-secret)
                          telegram-dev-base-url (assoc "TELEGRAM_WEBHOOK_BASE_URL" telegram-dev-base-url)
                          telegram-dev-timeout (assoc "TELEGRAM_HTTP_TIMEOUT_MS" telegram-dev-timeout)
                          telegram-dev-ttl (assoc "TELEGRAM_LINK_TOKEN_TTL_MS" telegram-dev-ttl))]
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
                    :status :running
                    :created-at now
                    :updated-at now
                    :ports ports
                    :repo-dir (.getPath repo-dir)
                    :datomic-dir (.getPath datomic-dir)
                    :work-dir (.getPath session-root)
                    :logs-dir (.getPath logs-dir)
                    :chat-log (.getPath chat-file)
                    :env-file (.getPath env-file)
                    :tmux tmux-session
                    :branch branch
                    :auto-start-app? auto-start-app?
                    :auto-start-site? auto-start-site?}]
       (store/upsert-session! store session))))

 (defn present-session
   [session]
   (let [running (tmux/running? (:tmux session))
         status (cond
                  (= :complete (:status session)) :complete
                  running :running
                  :else :idle)]
     (-> session
         (assoc :running? running
                :status status)
         (dissoc :repo-dir :datomic-dir :work-dir :env-file :chat-log))))

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

(defn app-ready?
  [session]
  (when-let [port (get-in session [:ports :app])]
    (port-open? "127.0.0.1" port 200)))

(defn resume-session!
  [store cfg session]
  (when (= :complete (:status session))
    (throw (ex-info "Session already completed" {:id (:id session)})))
  (when (tmux/running? (:tmux session))
    (throw (ex-info "Session already running" {:id (:id session)})))
  (doseq [[label path] [[:repo-dir (:repo-dir session)]
                        [:env-file (:env-file session)]
                        [:chat-log (:chat-log session)]]]
    (when (or (nil? path) (not (.exists (io/file path))))
      (throw (ex-info "Session files missing; cannot resume"
                      {:id (:id session) :missing label}))))
  (let [auto-start-app? (get session :auto-start-app? (:auto-start-app? cfg))
        auto-start-site? (get session :auto-start-site? (:auto-start-site? cfg))
        site-enabled? (and auto-start-site?
                           (some? (get-in session [:ports :site])))]
    (tmux/start! (:tmux session) (:repo-dir session) (:env-file session) (:codex-command cfg))
    (tmux/pipe-output! (:tmux session) (:chat-log session))
    (when auto-start-app?
      (start-app-window! session))
    (when site-enabled?
      (start-site-window! session))
    (let [now (now-ms)
          next-session (assoc session
                              :status :running
                              :updated-at now)]
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
        next-session (assoc session :updated-at now)]
    (update-manifest! (:logs-dir session)
                      (fn [m]
                        (assoc (or m {})
                               :app-restarted-at now)))
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
        (complete-session! store session)
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
   (store/delete-session! store (:id session))
   true)
