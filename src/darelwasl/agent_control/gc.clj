(ns darelwasl.agent-control.gc
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import (java.io File)
           (java.time Instant)
           (java.util.concurrent Executors TimeUnit)))

(defn- data-dir []
  (or (some-> (System/getenv "AGENT_CONTROL_DATA_DIR") str/trim not-empty)
      "data/agent-control"))

(defn- runs-dir []
  (io/file (data-dir) "runs"))

(defn- run-path [run-id]
  (io/file (runs-dir) run-id "run.json"))

(defn- read-json [^File file]
  (try
    (when (.exists file)
      (json/read-str (slurp file) :key-fn keyword))
    (catch Exception e
      (log/warn e "agent-control gc: failed to read json" {:path (.getPath file)})
      nil)))

(defn- write-json! [^File file data]
  (let [tmp (io/file (str (.getPath file) ".tmp"))
        parent (.getParentFile file)]
    (when (and parent (not (.exists parent)))
      (.mkdirs parent))
    (spit tmp (json/write-str data :escape-slash false))
    (.renameTo tmp file)))

(defn- parse-instant [s]
  (try
    (when (and s (string? s) (not (str/blank? s)))
      (Instant/parse s))
    (catch Exception _ nil)))

(defn- expired-run?
  [run]
  (let [status (or (:status run) "")
        expires (get-in run [:preview :expires_at])
        exp (parse-instant expires)]
    (and exp
         (.isAfter (Instant/now) exp)
         (contains? #{"waiting_review" "previewing" "changes_requested"} status))))

(defn- stop-preview!
  [run-id]
  (let [cmd ["bash" "-lc" (str "scripts/preview stop " (pr-str run-id))]
        pb (ProcessBuilder. ^java.util.List cmd)]
    (.directory pb (io/file (System/getProperty "user.dir")))
    (.redirectErrorStream pb true)
    (let [p (.start pb)
          code (.waitFor p)]
      (when-not (zero? code)
        (log/warn "agent-control gc: scripts/preview stop failed" {:run-id run-id :exit code})))))

(defn gc-once!
  "Stop/garbage expired runs (6h window) by calling scripts/preview stop and marking the run as trashed."
  []
  (let [dir (runs-dir)]
    (when (.exists ^File dir)
      (doseq [^File d (or (.listFiles ^File dir) [])]
        (when (.isDirectory d)
          (let [run-id (.getName d)
                path (io/file d "run.json")
                run (read-json path)]
            (when (and run (expired-run? run))
              (try
                (log/info "agent-control gc: expiring run" {:run-id run-id :expires-at (get-in run [:preview :expires_at])})
                (stop-preview! run-id)
                (write-json! path (-> run
                                      (assoc :status "trashed")
                                      (assoc :trashed_at (.toString (Instant/now)))))
                (catch Exception e
                  (log/warn e "agent-control gc: failed to expire run" {:run-id run-id}))))))))))

(defn start-loop!
  "Start a background GC loop. Returns {:executor ...} to be passed to stop-loop!."
  ([] (start-loop! {:interval-ms 60000}))
  ([{:keys [interval-ms]}]
   (let [enabled? (not= "false" (some-> (System/getenv "AGENT_CONTROL_GC_ENABLED") str/lower-case str/trim))
         interval-ms (long (or interval-ms 60000))]
     (if-not enabled?
       (do (log/info "agent-control gc: disabled via AGENT_CONTROL_GC_ENABLED=false")
           nil)
       (let [exec (Executors/newSingleThreadScheduledExecutor)]
         (.scheduleAtFixedRate exec
                               (fn []
                                 (try
                                   (gc-once!)
                                   (catch Exception e
                                     (log/warn e "agent-control gc: loop tick failed"))))
                               interval-ms
                               interval-ms
                               TimeUnit/MILLISECONDS)
         (log/info "agent-control gc: started" {:interval-ms interval-ms})
         {:executor exec})))))

(defn stop-loop!
  [{:keys [executor]}]
  (when executor
    (try
      (.shutdownNow executor)
      (catch Exception _ nil))))

