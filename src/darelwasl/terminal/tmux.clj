(ns darelwasl.terminal.tmux
   (:require [clojure.java.shell :as sh]
             [clojure.string :as str]
             [clojure.tools.logging :as log]))

(defn- tmux-bin
  []
  (let [env (System/getenv)
        candidate (or (get env "TERMINAL_TMUX_BIN")
                      (get env "TMUX_BIN"))]
    (if (and candidate (not (str/blank? candidate))) candidate "tmux")))

 (defn- run-cmd
   [args & [{:keys [dir]}]]
   (let [cmd (tmux-bin)
         argv (vec (cons cmd args))
         {:keys [exit out err]} (apply sh/sh (cond-> argv dir (concat [:dir dir])))]
     (when-not (zero? exit)
       (throw (ex-info "Command failed" {:args argv :exit exit :err err})))
     (when (seq err)
       (log/debug "Command stderr" {:args argv :err err}))
     out))

 (defn session-name
   [prefix session-id]
   (str prefix "-" session-id))

 (defn running?
   [session]
   (try
     (let [{:keys [exit]} (apply sh/sh [(tmux-bin) "has-session" "-t" session])]
       (zero? exit))
     (catch Exception _ false)))

 (defn start!
   [session repo-dir env-file cmd]
   (let [shell-cmd (format "set -a; source %s; set +a; %s" env-file cmd)]
     (run-cmd ["new-session" "-d" "-s" session "-c" repo-dir "bash" "-lc" shell-cmd])))

 (defn pipe-output!
   [session log-file]
   (run-cmd ["pipe-pane" "-o" "-t" session (str "cat >> " log-file)]))

 (defn send!
   [session text]
   (run-cmd ["send-keys" "-t" session "--" text "Enter"]))

 (defn kill!
   [session]
   (when (running? session)
     (run-cmd ["kill-session" "-t" session])))
