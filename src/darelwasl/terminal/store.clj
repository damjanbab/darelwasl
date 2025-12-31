(ns darelwasl.terminal.store
   (:require [clojure.edn :as edn]
             [clojure.java.io :as io]
             [clojure.tools.logging :as log]))

 (defn- ensure-dir!
   [dir]
   (let [f (io/file dir)]
     (when-not (.exists f)
       (.mkdirs f))
     f))

 (defn- read-edn
   [file]
   (try
     (when (.exists file)
       (edn/read-string (slurp file)))
     (catch Exception e
       (log/warn e "Failed to read terminal store")
       nil)))

 (defn- write-edn!
   [file data]
   (let [tmp (io/file (str (.getPath file) ".tmp"))]
     (spit tmp (pr-str data))
     (.renameTo tmp file)))

 (defn- read-ports
   [file]
   (let [data (read-edn file)]
     (or (:reservations data)
         (:ports data)
         {})))

 (defn- write-ports!
   [file reservations]
   (write-edn! file {:reservations reservations}))

(defn- persist-store!
  [store]
  (write-edn! (:file store)
              {:sessions @(:sessions store)
               :agent-runs @(:agent-runs store)
               :specs @(:specs store)
               :plans @(:plans store)
               :artifacts @(:artifacts store)}))

(defn load-store
  [data-dir]
  (let [dir (ensure-dir! data-dir)
        file (io/file dir "sessions.edn")
        ports-file (io/file dir "ports.edn")
        data (read-edn file)
        sessions (or (:sessions data) {})
        agent-runs (or (:agent-runs data) {})
        specs (or (:specs data) {})
        plans (or (:plans data) {})
        artifacts (or (:artifacts data) {})
        reservations (read-ports ports-file)]
    {:dir dir
     :file file
     :ports-file ports-file
     :sessions (atom sessions)
     :agent-runs (atom agent-runs)
     :specs (atom specs)
     :plans (atom plans)
     :artifacts (atom artifacts)
     :ports (atom reservations)}))

 (defn list-sessions
   [store]
   (->> (vals @(:sessions store))
        (sort-by :created-at)
        vec))

 (defn get-session
   [store session-id]
   (get @(:sessions store) session-id))

(defn upsert-session!
  [store session]
  (swap! (:sessions store) assoc (:id session) session)
  (persist-store! store)
  session)

(defn delete-session!
  [store session-id]
  (swap! (:sessions store) dissoc session-id)
  (persist-store! store)
  true)

(defn list-agent-runs
  [store]
  (->> (vals @(:agent-runs store))
       (sort-by :created-at)
       vec))

(defn get-agent-run
  [store agent-run-id]
  (get @(:agent-runs store) agent-run-id))

(defn upsert-agent-run!
  [store agent-run]
  (swap! (:agent-runs store) assoc (:id agent-run) agent-run)
  (persist-store! store)
  agent-run)

(defn list-specs
  [store]
  (->> (vals @(:specs store))
       (sort-by :created-at)
       vec))

(defn get-spec
  [store spec-id]
  (get @(:specs store) spec-id))

(defn upsert-spec!
  [store spec]
  (swap! (:specs store) assoc (:id spec) spec)
  (persist-store! store)
  spec)

(defn list-plans
  [store]
  (->> (vals @(:plans store))
       (sort-by :created-at)
       vec))

(defn get-plan
  [store plan-id]
  (get @(:plans store) plan-id))

(defn upsert-plan!
  [store plan]
  (swap! (:plans store) assoc (:id plan) plan)
  (persist-store! store)
  plan)

(defn list-artifacts
  [store]
  (->> (vals @(:artifacts store))
       (sort-by :created-at)
       vec))

(defn get-artifact
  [store artifact-id]
  (get @(:artifacts store) artifact-id))

(defn upsert-artifact!
  [store artifact]
  (swap! (:artifacts store) assoc (:id artifact) artifact)
  (persist-store! store)
  artifact)

 (defn port-reservations
   [store]
   (or @(:ports store) {}))

 (defn set-port-reservations!
   [store reservations]
   (reset! (:ports store) reservations)
   (write-ports! (:ports-file store) reservations)
   reservations)

 (defn reserve-ports!
   [store session-id ports]
   (let [session-id (str session-id)]
     (swap! (:ports store) assoc session-id ports)
     (write-ports! (:ports-file store) @(:ports store))
     ports))

 (defn release-ports!
   [store session-id]
   (swap! (:ports store) dissoc (str session-id))
   (write-ports! (:ports-file store) @(:ports store))
   true)
