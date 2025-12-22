(ns darelwasl.terminal.session
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.terminal.store :as store]
            [darelwasl.terminal.tmux :as tmux])
  (:import (java.io RandomAccessFile)
           (java.net ServerSocket)
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

 (defn- write-env!
   [file env-map]
   (let [lines (for [[k v] env-map]
                 (str k "=" v))]
     (spit file (str/join "\n" lines))))

 (defn- protocol-lines
   []
   (when-let [resource (io/resource "terminal/protocol.txt")]
     (->> (slurp resource)
          (str/split-lines)
          (remove str/blank?)
          vec)))

 (defn- append-chat!
   [chat-file text]
   (spit chat-file (str text "\n") :append true))

(def ^:private ansi-csi-re #"\u001B\\[[0-?]*[ -/]*[@-~]")
(def ^:private ansi-osc-re #"\u001B\][^\u0007]*(?:\u0007|\u001B\\)")

(defn- sanitize-output
  [text]
  (-> text
      (str/replace "\r" "")
      (str/replace ansi-osc-re "")
      (str/replace ansi-csi-re "")
      (str/replace "\u001B(B" "")))

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

 (defn create-session!
   [store cfg {:keys [name]}]
   (let [id (str (UUID/randomUUID))
         session-name (or (some-> name str/trim not-empty)
                          (str "session-" (subs id 0 8)))
         work-root (ensure-dir! (:work-dir cfg))
         logs-root (ensure-dir! (:logs-dir cfg))
         session-root (io/file work-root id)
         repo-dir (io/file session-root "repo")
         datomic-dir (io/file session-root "datomic")
         chat-file (io/file session-root "chat.log")
         env-file (io/file session-root "session.env")
         logs-dir (io/file logs-root id)
         ports (allocate-ports cfg (store/list-sessions store))
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
     (write-env! env-file {"APP_HOST" "0.0.0.0"
                           "SITE_HOST" "0.0.0.0"
                           "APP_PORT" (:app ports)
                           "SITE_PORT" (:site ports)
                           "DATOMIC_STORAGE_DIR" (.getPath datomic-dir)
                           "DATOMIC_SYSTEM" "darelwasl"
                           "DATOMIC_DB_NAME" "darelwasl"
                           "ALLOW_FIXTURE_SEED" "true"
                           "TERMINAL_SESSION_ID" id
                           "TERMINAL_LOG_DIR" (.getPath logs-dir)})
     (tmux/start! tmux-session (.getPath repo-dir) (.getPath env-file) (:codex-command cfg))
     (tmux/pipe-output! tmux-session (.getPath chat-file))
     (doseq [line (protocol-lines)]
       (tmux/send! tmux-session line))
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
                    :branch branch}]
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
   (tmux/send! (:tmux session) text)
   true)

 (defn output-since
   [session cursor max-bytes]
   (read-output (:chat-log session) cursor max-bytes))

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
