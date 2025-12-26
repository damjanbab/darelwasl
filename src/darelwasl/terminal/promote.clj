(ns darelwasl.terminal.promote
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.db :as db]))

(def ^:private snapshot-file "data-snapshot.edn")
(def ^:private promote-file "data-promote.edn")
(def ^:private tx-log-file "data-tx-log.edn")

(defn- normalize-path
  [path]
  (when path
    (.getAbsolutePath (io/file path))))

(defn- read-edn-file
  [file label]
  (when (.exists file)
    (try
      (edn/read-string (slurp file))
      (catch Exception e
        (throw (ex-info (str "Invalid " label " file")
                        {:file (.getPath file)}
                        e))))))

(defn- load-snapshot!
  [logs-dir]
  (let [file (io/file logs-dir snapshot-file)
        snapshot (read-edn-file file "snapshot")]
    (when-not snapshot
      (throw (ex-info "Session snapshot missing"
                      {:status 404
                       :message "Session snapshot missing"})))
    snapshot))

(defn- load-promote-state
  [logs-dir]
  (let [file (io/file logs-dir promote-file)
        state (read-edn-file file "promote state")]
    (merge {:applied-lines 0
            :last-promoted-at nil}
           (or state {}))))

(defn- write-promote-state!
  [logs-dir state]
  (spit (io/file logs-dir promote-file) (pr-str state)))

(defn- load-tx-log
  [logs-dir]
  (let [file (io/file logs-dir tx-log-file)]
    (if-not (.exists file)
      []
      (with-open [reader (io/reader file)]
        (->> (line-seq reader)
             (remove str/blank?)
             (map-indexed
              (fn [idx line]
                (try
                  (edn/read-string line)
                  (catch Exception e
                    (throw (ex-info "Invalid tx log entry"
                                    {:line (inc idx)
                                     :file (.getPath file)}
                                    e))))))
             vec)))))

(defn- pending-entries
  [entries applied-lines]
  (when (> applied-lines (count entries))
    (throw (ex-info "Promote state exceeds tx log"
                    {:applied-lines applied-lines
                     :entries (count entries)})))
  (subvec (vec entries) applied-lines))

(defn- lookup-ref?
  [value]
  (and (vector? value)
       (= 2 (count value))
       (keyword? (first value))))

(defn- tempid?
  [value]
  (and (number? value) (neg? value)))

(defn- unique-identity-attrs
  [db]
  (set (d/q '[:find [?ident ...]
              :where [?e :db/unique :db.unique/identity]
                     [?e :db/ident ?ident]]
            db)))

(defn- tx-entity-refs
  [identity-attrs tx]
  (cond
    (map? tx)
    (let [refs (cond-> []
                 (:db/id tx) (conj (:db/id tx)))]
      (reduce (fn [acc [k v]]
                (if (and (contains? identity-attrs k)
                         (some? v))
                  (conj acc [k v])
                  acc))
              refs
              tx))

    (and (sequential? tx) (keyword? (first tx)))
    (case (first tx)
      (:db/add :db/retract :db/retractEntity) [(second tx)]
      [])

    :else []))

(defn- resolve-entity-id
  [db ref]
  (cond
    (tempid? ref) {:status :tempid}
    (number? ref) (if (d/pull db [:db/id] ref)
                    {:status :ok :eid ref}
                    {:status :missing})
    (lookup-ref? ref) (if-let [eid (d/q '[:find ?e .
                                         :in $ ?a ?v
                                         :where [?e ?a ?v]]
                                       db (first ref) (second ref))]
                        {:status :ok :eid eid}
                        {:status :missing})
    :else {:status :skip}))

(defn- entity-changed-since?
  [db eid since-inst]
  (boolean
   (seq
    (d/q '[:find ?tx
           :in $ ?e ?since
           :where [?e _ _ ?tx ?added]
                  [?tx :db/txInstant ?inst]
                  [(> ?inst ?since)]]
         (d/history db)
         eid
         since-inst))))

(defn- detect-conflicts
  [db snapshot entries]
  (let [identity-attrs (unique-identity-attrs db)
        refs (->> entries
                  (mapcat :tx-data)
                  (mapcat #(tx-entity-refs identity-attrs %))
                  (remove nil?)
                  distinct)
        since-inst (get-in snapshot [:datomic :tx-inst])
        errors (atom [])
        conflicts (atom [])]
    (doseq [ref refs]
      (let [{:keys [status eid]} (resolve-entity-id db ref)]
        (cond
          (= status :missing)
          (when (number? ref)
            (swap! errors conj {:ref ref :reason "Numeric entity id missing in main DB"}))

          (= status :ok)
          (when (and since-inst (entity-changed-since? db eid since-inst))
            (swap! conflicts conj {:ref ref :eid eid})))
        (when (and (= status :ok) (nil? since-inst))
          (swap! errors conj {:ref ref :reason "Snapshot missing tx instant"}))))
    {:errors @errors
     :conflicts @conflicts}))

(defn- file-create-entries
  [entries]
  (->> entries
       (mapcat :tx-data)
       (filter map?)
       (keep (fn [tx]
               (when (and (:file/id tx) (:file/storage-path tx))
                 {:file/id (:file/id tx)
                  :file/storage-path (:file/storage-path tx)})))
       distinct))

(defn- file-delete-ids
  [entries]
  (->> entries
       (mapcat :tx-data)
       (keep (fn [tx]
               (when (and (sequential? tx)
                          (= :db/retractEntity (first tx)))
                 (let [ref (second tx)]
                   (when (and (lookup-ref? ref) (= :file/id (first ref)))
                     {:file/id (second ref)})))))
       distinct))

(defn- ensure-dir!
  [path]
  (let [dir (io/file path)]
    (when-not (.exists dir)
      (.mkdirs dir))
    dir))

(defn- copy-file!
  [from to]
  (let [src (io/file from)
        dest (io/file to)]
    (when-not (.exists src)
      (throw (ex-info "File missing in session storage"
                      {:path (.getPath src)})))
    (ensure-dir! (.getParentFile dest))
    (java.nio.file.Files/copy (.toPath src) (.toPath dest)
                              (into-array java.nio.file.StandardCopyOption
                                          [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                                           java.nio.file.StandardCopyOption/COPY_ATTRIBUTES]))))

(defn- file-storage-path
  [db file-id]
  (d/q '[:find ?path .
         :in $ ?id
         :where [?e :file/id ?id]
                [?e :file/storage-path ?path]]
       db file-id))

(defn promote-session-data!
  [state session-id logs-dir]
  (let [conn (get-in state [:db :conn])
        term-cfg (get-in state [:config :terminal])
        main-files-dir (:main-files-dir term-cfg)]
    (when-not conn
      (throw (ex-info "Main DB connection missing"
                      {:status 500
                       :message "Main DB connection missing"})))
    (when (or (nil? logs-dir) (str/blank? (str logs-dir)))
      (throw (ex-info "Session logs directory missing"
                      {:status 400
                       :message "Session logs directory missing"})))
    (let [logs-dir (io/file logs-dir)
          snapshot (load-snapshot! logs-dir)
          promote-state (load-promote-state logs-dir)
          entries (load-tx-log logs-dir)
          applied-lines (long (or (:applied-lines promote-state) 0))
          pending (pending-entries entries applied-lines)
          main-files-dir (normalize-path main-files-dir)
          snap-datomic (:datomic snapshot)
          snap-files (:files snapshot)]
      (when (str/blank? (str main-files-dir))
        (throw (ex-info "Main files storage missing"
                        {:status 500
                         :message "Main files storage missing"})))
      (let [cfg-storage (normalize-path (:main-datomic-dir term-cfg))
            snap-storage (normalize-path (:storage-dir snap-datomic))]
        (when (and cfg-storage snap-storage (not= cfg-storage snap-storage))
          (throw (ex-info "Snapshot does not match main Datomic storage"
                          {:status 409
                           :message "Snapshot does not match main Datomic storage"}))))
      (when (and main-files-dir
                 (:storage-dir snap-files)
                 (not= main-files-dir (normalize-path (:storage-dir snap-files))))
        (throw (ex-info "Snapshot does not match main file storage"
                        {:status 409
                         :message "Snapshot does not match main file storage"})))
      (when (empty? pending)
        {:message "No session data to promote"
         :result {:session/id session-id
                  :applied-lines applied-lines
                  :pending-lines 0}})
      (let [db-now (d/db conn)
            conflict-check (detect-conflicts db-now snapshot pending)]
        (when (seq (:errors conflict-check))
          (throw (ex-info "Session data promotion failed"
                          {:status 409
                           :message "Session data promotion failed"
                           :details {:errors (:errors conflict-check)}})))
        (when (seq (:conflicts conflict-check))
          (throw (ex-info "Session data conflicts with main"
                          {:status 409
                           :message "Session data conflicts with main"
                           :details {:conflicts (:conflicts conflict-check)}})))
        (let [main-files-root (ensure-dir! main-files-dir)
              session-files-dir (get-in snapshot [:session :files-dir])
              creates (file-create-entries pending)
              deletes (file-delete-ids pending)
              delete-paths (->> deletes
                                (map (fn [{:file/keys [id]}]
                                       (when-let [path (file-storage-path db-now id)]
                                         {:file/id id :file/storage-path path})))
                                (remove nil?))
              apply-entry! (fn [{:keys [tx-data]}]
                             (when-not (seq tx-data)
                               (throw (ex-info "Tx log entry missing tx-data" {:status 400})))
                             (db/transact! conn {:tx-data tx-data}))]
          (when (and (seq creates) (str/blank? (str session-files-dir)))
            (throw (ex-info "Session files directory missing"
                            {:status 409
                             :message "Session files directory missing"})))
          (when (and session-files-dir (seq creates))
            (doseq [{:file/keys [storage-path]} creates]
              (when-not (.exists (io/file session-files-dir storage-path))
                (throw (ex-info "Session file missing for promotion"
                                {:status 409
                                 :message "Session file missing for promotion"
                                 :details {:path storage-path}})))))
          (doseq [entry pending]
            (apply-entry! entry))
          (when (and session-files-dir (seq creates))
            (doseq [{:file/keys [storage-path]} creates]
              (copy-file! (io/file session-files-dir storage-path)
                          (io/file main-files-root storage-path))))
          (when (seq delete-paths)
            (doseq [{:file/keys [storage-path]} delete-paths]
              (when storage-path
                (try
                  (io/delete-file (io/file main-files-root storage-path) true)
                  (catch Exception e
                    (log/warn e "Failed to delete file from main storage"
                              {:path storage-path})))))
          (let [next-lines (+ applied-lines (count pending))
                updated-state (assoc promote-state
                                     :applied-lines next-lines
                                     :last-promoted-at (System/currentTimeMillis))]
            (write-promote-state! logs-dir updated-state)
            {:message "Session data promoted"
             :result {:session/id session-id
                      :applied-lines next-lines
                      :promoted-lines (count pending)
                      :files-copied (count creates)
                      :files-deleted (count deletes)}})))))))
