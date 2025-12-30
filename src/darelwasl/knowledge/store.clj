(ns darelwasl.knowledge.store
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [darelwasl.db :as db]
            [darelwasl.knowledge.util :as kutil])
  (:import (java.net URI)))

(defn- ensure-conn
  [conn]
  (when-not conn
    {:error {:status 500 :message "Database not ready"}}))

(defn- find-entity
  [db attr value]
  (ffirst (d/q '[:find ?e :in $ ?attr ?value :where [?e ?attr ?value]] db attr value)))

(defn find-instruments-by-name
  [db name]
  (map first
       (d/q '[:find ?e
              :in $ ?name
              :where [?e :instrument/name ?name]]
            db name)))

(defn- assoc-some
  [m k v]
  (if (some? v) (assoc m k v) m))

(def ^:private max-doc-text 3000)
(def ^:private max-span-text 1000)

(defn- truncate-text
  [text]
  (when (seq text)
    (if (> (count text) max-doc-text)
      (str (subs text 0 max-doc-text) "...")
      text)))

(defn- truncate-span
  [text]
  (when (seq text)
    (if (> (count text) max-span-text)
      (str (subs text 0 max-span-text) "...")
      text)))

(defn ensure-url!
  [conn {:keys [normalized original status]}]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            existing (find-entity db :url/normalized normalized)
            uri (URI. normalized)
            tx (cond-> {:url/normalized normalized
                        :url/original (or original normalized)
                        :url/status status
                        :url/last-seen-at (kutil/now-inst)
                        :entity/type :entity.type/url}
                 (some? (.getHost uri)) (assoc :url/host (.getHost uri))
                 (some? (.getPath uri)) (assoc :url/path (or (.getPath uri) "/"))
                 existing (assoc :db/id existing))]
        (db/transact! conn {:tx-data [tx]})
        (or existing (find-entity (d/db conn) :url/normalized normalized)))))

(defn ensure-blob!
  [conn {:keys [sha256 mime size uri]}]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            existing (find-entity db :blob/sha256 sha256)
            tx (cond-> (-> {:blob/sha256 sha256
                            :blob/created-at (kutil/now-inst)
                            :entity/type :entity.type/blob}
                           (assoc-some :blob/mime mime)
                           (assoc-some :blob/size-bytes size)
                           (assoc-some :blob/uri uri))
                 existing (assoc :db/id existing))]
        (when-not existing
          (db/transact! conn {:tx-data [tx]}))
        (or existing (find-entity (d/db conn) :blob/sha256 sha256)))))

(defn record-fetch!
  [conn {:keys [fetch-id status headers url blob run error]}]
  (or (ensure-conn conn)
      (let [tx (cond-> (-> {:fetch/id fetch-id
                            :fetch/at (kutil/now-inst)
                            :fetch/status (or status 0)
                            :fetch/url url
                            :fetch/run run
                            :entity/type :entity.type/fetch}
                           (assoc-some :fetch/headers (when headers (pr-str headers)))
                           (assoc-some :fetch/blob blob)
                           (assoc-some :fetch/error error)))]
        (db/transact! conn {:tx-data [tx]})
        fetch-id)))

(defn ensure-doc!
  [conn {:keys [doc-id title text doc-type source org language repr url blob instrument-version issued-at effective-at publication-at status source-links]}]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            existing (find-entity db :doc/id doc-id)
            tx (cond-> (-> {:doc/id doc-id
                            :doc/last-seen-at (kutil/now-inst)
                            :entity/type :entity.type/doc}
                           (assoc-some :doc/title title)
                   (assoc-some :doc/text (truncate-text text))
                           (assoc-some :doc/type doc-type)
                           (assoc-some :doc/source source)
                           (assoc-some :doc/org org)
                           (assoc-some :doc/language language)
                           (assoc-some :doc/representation repr)
                           (assoc-some :doc/url url)
                           (assoc-some :doc/blob blob)
                           (assoc-some :doc/instrument-version instrument-version)
                           (assoc-some :doc/issued-at issued-at)
                           (assoc-some :doc/effective-at effective-at)
                           (assoc-some :doc/publication-at publication-at)
                           (assoc-some :doc/status status)
                           (assoc-some :doc/source-links source-links))
                 existing (assoc :db/id existing))]
        (db/transact! conn {:tx-data [tx]})
        doc-id)))

(defn ensure-doc-spans!
  [conn doc-id spans]
  (or (ensure-conn conn)
      (let [tx-data (->> spans
                         (map (fn [{:keys [page idx text]}]
                                {:doc.span/id (kutil/name-uuid (str doc-id ":" page ":" idx))
                                 :doc.span/doc [:doc/id doc-id]
                                 :doc.span/page page
                                 :doc.span/idx idx
                                 :doc.span/text (truncate-span text)
                                 :doc.span/text-search (some-> text truncate-span str/lower-case)
                                 :entity/type :entity.type/doc-span}))
                         vec)]
        (when (seq tx-data)
          (db/transact! conn {:tx-data tx-data}))
        tx-data)))

(defn ensure-instrument!
  [conn {:keys [instrument-id name doc-type rank org jurisdiction languages topics status]}]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            existing (find-entity db :instrument/id instrument-id)
            tx (cond-> {:instrument/id instrument-id
                        :instrument/name name
                        :instrument/type doc-type
                        :instrument/authority-rank rank
                        :instrument/org org
                        :instrument/jurisdiction jurisdiction
                        :instrument/languages languages
                        :instrument/status status
                        :entity/type :entity.type/instrument}
                 (seq topics) (assoc :instrument/topics topics)
                 existing (assoc :db/id existing))]
        (db/transact! conn {:tx-data [tx]})
        instrument-id)))

(defn ensure-instrument-version!
  [conn {:keys [version-id instrument doc-id label status amended-at]}]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            existing (find-entity db :instrument.version/id version-id)
            tx (cond-> {:instrument.version/id version-id
                        :instrument.version/instrument instrument
                        :instrument.version/label label
                        :instrument.version/docs [doc-id]
                        :instrument.version/status status
                        :entity/type :entity.type/instrument-version}
                 (some? amended-at) (assoc :instrument.version/amended-at amended-at)
                 existing (assoc :db/id existing))]
        (db/transact! conn {:tx-data [tx]})
        version-id)))

(defn store-sections!
  [conn version-id doc-id sections]
  (or (ensure-conn conn)
      (let [tx-data (->> sections
                         (map (fn [{:keys [title number level order text spans parent-id]}]
                                {:section/id (kutil/name-uuid (str version-id ":" order))
                                 :section/version [:instrument.version/id version-id]
                                 :section/parent (when parent-id [:section/id parent-id])
                                 :section/title title
                                 :section/number number
                                 :section/level level
                                 :section/order order
                                 :section/text text
                                 :section/spans (map (fn [{:keys [page idx]}]
                                                       [:doc.span/id (kutil/name-uuid (str doc-id ":" page ":" idx))])
                                                     spans)
                                 :entity/type :entity.type/section}))
                         vec)]
        (when (seq tx-data)
          (db/transact! conn {:tx-data tx-data}))
        tx-data)))

(defn store-xrefs!
  [conn xrefs doc-id from-instrument]
  (or (ensure-conn conn)
      (let [tx-data (->> xrefs
                         (map-indexed
                          (fn [idx {:keys [text span ref]}]
                            {:xref/id (kutil/name-uuid (str doc-id ":xref:" idx))
                             :xref/from-instrument from-instrument
                             :xref/text text
                             :xref/doc [:doc/id doc-id]
                             :xref/evidence (when span
                                              [[:doc.span/id (kutil/name-uuid (str doc-id ":" (:page span) ":" (:idx span))) ]])
                             :xref/status :xref.status/unresolved
                             :entity/type :entity.type/xref}))
                         vec)]
        (when (seq tx-data)
          (db/transact! conn {:tx-data tx-data}))
        tx-data)))

(defn store-possible-same!
  [conn {:keys [from-id to-id]}]
  (or (ensure-conn conn)
      (let [xref-id (kutil/name-uuid (str "possible-same:" from-id ":" to-id))
            tx {:xref/id xref-id
                :xref/from-instrument [:instrument/id from-id]
                :xref/to-instrument [:instrument/id to-id]
                :xref/text "possible same instrument"
                :xref/status :xref.status/unresolved
                :entity/type :entity.type/xref}]
        (db/transact! conn {:tx-data [tx]})
        xref-id)))

(defn store-decision!
  [conn {:keys [decision-id doc-id date authority outcome cited-instruments cited-sections status]}]
  (or (ensure-conn conn)
      (let [tx {:decision/id decision-id
                :decision/doc doc-id
                :decision/date date
                :decision/authority authority
                :decision/outcome outcome
                :decision/cited-instruments cited-instruments
                :decision/cited-sections cited-sections
                :decision/status status
                :entity/type :entity.type/decision}]
        (db/transact! conn {:tx-data [tx]})
        decision-id)))

(defn create-crawl-run!
  [conn {:keys [run-id started-at status]}]
  (or (ensure-conn conn)
      (db/transact! conn {:tx-data [{:crawl.run/id run-id
                                     :crawl.run/started-at started-at
                                     :crawl.run/status status
                                     :entity/type :entity.type/crawl-run}]})))

(defn finish-crawl-run!
  [conn {:keys [run-id finished-at status metrics errors git-sha]}]
  (or (ensure-conn conn)
      (let [max-datomic-string 4000
            bounded-edn (fn [& candidates]
                          (loop [[candidate & rest] candidates]
                            (let [candidate (or candidate [])
                                  rendered (pr-str candidate)]
                              (cond
                                (<= (count rendered) max-datomic-string) rendered
                                (seq rest) (recur rest)
                                :else (pr-str [])))))
            sanitize-blocker (fn [blocker]
                               (when blocker
                                 (select-keys blocker [:type :reason :host])))
            sanitize-discovery-errors (fn [errs limit]
                                        (->> (or errs [])
                                             (map (fn [entry]
                                                    (select-keys entry [:url :status :error :error-type :attempts :dns])))
                                             (take limit)
                                             vec))
            sanitize-metrics (fn [entries limit]
                               (->> (or entries [])
                                    (map (fn [entry]
                                           (cond-> (select-keys entry [:adapter
                                                                       :source
                                                                       :status
                                                                       :blocker
                                                                       :last-attempt-at
                                                                       :last-success-at
                                                                       :metrics
                                                                       :mirror-for])
                                             (seq (:discovery-errors entry))
                                             (assoc :discovery-errors (sanitize-discovery-errors (:discovery-errors entry) limit))
                                             (:blocker entry)
                                             (update :blocker sanitize-blocker))))
                                    vec))
            sanitize-errors (fn [entries limit]
                              (->> (or entries [])
                                   (map (fn [entry]
                                          (-> entry
                                              (select-keys [:url :status :blocker])
                                              (update :blocker sanitize-blocker))))
                                   (take limit)
                                   vec))
            metrics-str (bounded-edn (sanitize-metrics metrics 5)
                                     (sanitize-metrics metrics 1)
                                     (mapv #(select-keys % [:adapter :source :status :blocker])
                                           (sanitize-metrics metrics 1))
                                     [])
            errors-str (bounded-edn (sanitize-errors errors 10)
                                    (sanitize-errors errors 3)
                                    [])
            tx (cond-> {:crawl.run/id run-id
                        :crawl.run/finished-at finished-at
                        :crawl.run/status status
                        :crawl.run/source-metrics metrics-str
                        :crawl.run/errors errors-str
                        :entity/type :entity.type/crawl-run}
                 (some? git-sha) (assoc :crawl.run/git-sha git-sha))]
        (db/transact! conn {:tx-data [tx]}))))
