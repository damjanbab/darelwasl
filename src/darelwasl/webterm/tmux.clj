(ns darelwasl.webterm.tmux
  (:require [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream)
           (java.util.concurrent TimeUnit)))

(defn- run-proc
  [{:keys [cmd timeout-ms]}]
  (let [pb (ProcessBuilder. ^java.util.List cmd)
        _ (.redirectErrorStream pb false)
        proc (.start pb)
        out (ByteArrayOutputStream.)
        err (ByteArrayOutputStream.)]
    (future (with-open [is (.getInputStream proc)] (.transferTo is out)))
    (future (with-open [is (.getErrorStream proc)] (.transferTo is err)))
    (let [ok (.waitFor proc (long timeout-ms) TimeUnit/MILLISECONDS)]
      (when-not ok
        (.destroyForcibly proc)
        (throw (ex-info "process timeout" {:cmd cmd :timeout-ms timeout-ms})))
      {:exit (.exitValue proc)
       :out (.toString out "UTF-8")
       :err (.toString err "UTF-8")})))

(defn tmux
  [{:keys [tmux-bin]} & args]
  (run-proc {:cmd (into [tmux-bin] args)
             :timeout-ms 6000}))

(defn list-sessions
  [cfg]
  (let [{:keys [exit out]} (tmux cfg "list-sessions" "-F" "#S")]
    (if (zero? exit)
      (->> (str/split-lines (or out ""))
           (map str/trim)
           (remove str/blank?)
           (set))
      #{})))

(defn session-name
  [{:keys [tmux-prefix]} n]
  (str tmux-prefix n))

(defn ensure-session!
  [cfg n]
  (let [name (session-name cfg n)
        {:keys [exit err]} (tmux cfg "has-session" "-t" name)]
    (when (not (zero? exit))
      (let [{:keys [exit err]} (tmux cfg "new-session" "-d" "-s" name "-c" (:workdir cfg))]
        (when-not (zero? exit)
          (throw (ex-info "tmux new-session failed" {:stderr err :session name})))))
    (tmux cfg "set-option" "-t" name "history-limit" (str (:tmux-history-limit cfg)))
    nil))

(defn kill-session!
  [cfg n]
  (let [name (session-name cfg n)
        {:keys [exit err]} (tmux cfg "kill-session" "-t" name)]
    (when (and (not (zero? exit))
               (not (str/includes? (or err "") "can't find session")))
      (throw (ex-info "tmux kill-session failed" {:stderr err :session name}))))
  nil)

(defn start-codex!
  [cfg n]
  (let [name (session-name cfg n)
        {:keys [exit err]} (tmux cfg "send-keys" "-t" name "codex" "C-m")]
    (when-not (zero? exit)
      (throw (ex-info "tmux send-keys failed" {:stderr err :session name}))))
  nil)

(defn capture-history
  [cfg n lines]
  (let [name (session-name cfg n)
        clamped (-> lines (max 10) (min 200000))
        {:keys [exit out err]} (tmux cfg "capture-pane" "-p" "-t" name "-S" (str "-" clamped))]
    (when-not (zero? exit)
      (throw (ex-info "tmux capture-pane failed" {:stderr err :session name})))
    out))

(defn clear-terminal!
  [cfg n]
  (let [name (session-name cfg n)]
    (ensure-session! cfg n)
    (tmux cfg "clear-history" "-t" name)
    (tmux cfg "send-keys" "-t" name "clear" "C-m"))
  nil)

