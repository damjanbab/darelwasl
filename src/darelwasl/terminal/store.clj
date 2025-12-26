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

 (defn load-store
   [data-dir]
   (let [dir (ensure-dir! data-dir)
         file (io/file dir "sessions.edn")
         ports-file (io/file dir "ports.edn")
         data (read-edn file)
         sessions (or (:sessions data) {})
         reservations (read-ports ports-file)]
     {:dir dir
      :file file
      :ports-file ports-file
      :sessions (atom sessions)
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
   (write-edn! (:file store) {:sessions @(:sessions store)})
   session)

 (defn delete-session!
   [store session-id]
   (swap! (:sessions store) dissoc session-id)
   (write-edn! (:file store) {:sessions @(:sessions store)})
   true)

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
