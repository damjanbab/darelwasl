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

 (defn load-store
   [data-dir]
   (let [dir (ensure-dir! data-dir)
         file (io/file dir "sessions.edn")
         data (read-edn file)
         sessions (or (:sessions data) {})]
     {:dir dir
      :file file
      :sessions (atom sessions)}))

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
