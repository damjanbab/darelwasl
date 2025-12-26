(ns darelwasl.catalog
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d])
  (:import (java.io PushbackReader)))

(def ^:private catalog-path "docs/catalog.edn")
(def ^:private max-results 200)

(defn- read-catalog
  []
  (let [file (io/file catalog-path)]
    (when (.exists file)
      (with-open [r (PushbackReader. (io/reader file))]
        (edn/read r)))))

(defn- normalize-query
  [value]
  (let [raw (some-> value str/trim)]
    (when-not (str/blank? (str raw))
      (str/lower-case raw))))

(defn- includes?
  [hay needle]
  (and (string? hay)
       (string? needle)
       (str/includes? (str/lower-case hay) needle)))

(defn- matches-query?
  [entry q]
  (if-not q
    true
    (or (includes? (:id entry) q)
        (includes? (:name entry) q)
        (includes? (:path entry) q)
        (includes? (:source entry) q)
        (some #(includes? % q) (:tags entry)))))

(defn list-catalog
  [{:keys [q kind limit]}]
  (let [catalog (read-catalog)
        entries (or (:entries catalog) [])
        q (normalize-query q)
        kind (some-> kind str/trim not-empty keyword)
        limit (min max-results (or limit 60))
        filtered (cond->> entries
                   kind (filter #(= (:kind %) kind))
                   q (filter #(matches-query? % q)))]
    {:version (:version catalog)
     :entries (->> filtered (take limit) vec)}))

(defn find-entry
  [id]
  (let [catalog (read-catalog)
        entries (:entries catalog)]
    (first (filter #(= (:id %) id) entries))))

(defn- present-task
  [task]
  (let [ref (:entity/ref task)
        title (:task/title task)]
    {:id (str "data/task/" (:task/id task))
     :kind :data
     :entity/type :entity.type/task
     :ref ref
     :title title
     :context (cond-> {}
                ref (assoc :task/ref ref)
                (and (nil? ref) (:task/id task)) (assoc :task/id (:task/id task)))}))

(defn- present-file
  [file]
  (let [ref (:entity/ref file)
        label (or (:file/name file) (:file/slug file))]
    {:id (str "data/file/" (:file/id file))
     :kind :data
     :entity/type :entity.type/file
     :ref ref
     :title label
     :context (cond-> {}
                ref (assoc :file/ref ref)
                (and (nil? ref) (:file/id file)) (assoc :file/id (:file/id file)))}))

(defn- present-generic
  [entity]
  (let [etype (:entity/type entity)
        ref (:entity/ref entity)
        event-label (when-let [home (:betting.event/home-team entity)]
                      (if-let [away (:betting.event/away-team entity)]
                        (str home " vs " away)
                        home))
        label (or (:content.page/title entity)
                  (:content.block/title entity)
                  (:content.tag/name entity)
                  (:tag/name entity)
                  event-label
                  (:user/username entity)
                  (name etype))
        key (or ref (name etype))]
    {:id (str "data/entity/" key)
     :kind :data
     :entity/type etype
     :ref ref
     :title label
     :context {:text (str "Entity " (name etype) " " key)}}))

(defn- search-tasks
  [db q]
  (let [eids (d/q '[:find ?e
                   :where [?e :entity/type :entity.type/task]]
                 db)]
    (->> eids
         (map first)
         (map #(d/pull db [:task/id :task/title :task/description :entity/ref] %))
         (filter (fn [task]
                   (if-not q
                     true
                     (or (includes? (:entity/ref task) q)
                         (includes? (:task/title task) q)
                         (includes? (:task/description task) q)))))
         (map present-task))))

(defn- search-files
  [db q]
  (let [eids (d/q '[:find ?e
                   :where [?e :entity/type :entity.type/file]]
                 db)]
    (->> eids
         (map first)
         (map #(d/pull db [:file/id :file/name :file/slug :entity/ref] %))
         (filter (fn [file]
                   (if-not q
                     true
                     (or (includes? (:entity/ref file) q)
                         (includes? (:file/name file) q)
                         (includes? (:file/slug file) q)))))
         (map present-file))))

(defn- search-by-ref
  [db q]
  (when q
    (let [results (d/q '[:find ?e ?ref ?type
                         :in $ ?q
                         :where [?e :entity/ref ?ref]
                                [?e :entity/type ?type]
                                [(clojure.string/lower-case ?ref) ?ref-lower]
                                [(clojure.string/includes? ?ref-lower ?q)]]
                       db q)]
      (->> results
           (map first)
           (map #(d/pull db [:entity/type
                             :entity/ref
                             :tag/name
                             :content.tag/name
                             :content.page/title
                             :content.block/title
                             :betting.event/home-team
                             :betting.event/away-team
                             :user/username] %))
           (remove nil?)
           (map present-generic)))))

(defn search-data
  [db {:keys [q limit]}]
  (let [q (normalize-query q)
        limit (min max-results (or limit 50))
        entries (concat
                 (search-tasks db q)
                 (search-files db q)
                 (search-by-ref db q))]
    {:entries (->> entries
                   (remove nil?)
                   (sort-by #(or (:ref %) (:title %) (:id %)))
                   (distinct)
                   (take limit)
                   vec)}))
