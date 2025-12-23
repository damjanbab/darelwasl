(ns darelwasl.http.session-store
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [ring.middleware.session.store :refer [SessionStore]])
  (:import (java.util UUID)))

(defn- ensure-parent!
  [file]
  (let [parent (.getParentFile file)]
    (when (and parent (not (.exists parent)))
      (.mkdirs parent))
    file))

(defn- read-store
  [file]
  (try
    (when (.exists file)
      (edn/read-string (slurp file)))
    (catch Exception e
      (log/warn e "Failed to read session store")
      nil)))

(defn- write-store!
  [file data]
  (let [tmp (io/file (str (.getPath file) ".tmp"))]
    (spit tmp (pr-str data))
    (.renameTo tmp file)))

(defrecord FileSessionStore [file sessions lock]
  SessionStore
  (read-session [_ key]
    (get @sessions key))
  (write-session [_ key data]
    (let [session-key (or key (str (UUID/randomUUID)))]
      (swap! sessions assoc session-key data)
      (locking lock
        (write-store! file {:sessions @sessions}))
      session-key))
  (delete-session [_ key]
    (swap! sessions dissoc key)
    (locking lock
      (write-store! file {:sessions @sessions}))
    nil))

(defn file-session-store
  [path]
  (let [file (ensure-parent! (io/file path))
        data (read-store file)
        sessions (atom (or (:sessions data) {}))]
    (->FileSessionStore file sessions (Object.))))
