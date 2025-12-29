(ns darelwasl.knowledge.query
  (:require [clojure.string :as str]
            [datomic.client.api :as d]))

(defn- parse-date
  [value]
  (try
    (when (seq (str value))
      (-> (java.time.LocalDate/parse (str value))
          (.atStartOfDay (java.time.ZoneId/systemDefault))
          (.toInstant)
          java.util.Date/from))
    (catch Exception _ nil)))

(defn- within-range?
  [d from to]
  (and (or (nil? from) (and d (not (.before d from))))
       (or (nil? to) (and d (not (.after d to))))))

(defn- authority-band->min
  [band]
  (case band
    :law 80
    :regulation 60
    :guidance 40
    :draft 0
    nil))

(defn- authority-band->max
  [band]
  (case band
    :law 100
    :regulation 79
    :guidance 59
    :draft 20
    nil))

(defn- pull-doc
  [db eid]
  (d/pull db
          [:doc/id :doc/title :doc/type :doc/source :doc/org :doc/language
           :doc/issued-at :doc/effective-at :doc/publication-at :doc/last-seen-at
           :doc/source-links
           {:doc/instrument-version
            [:instrument.version/id
             {:instrument.version/instrument
              [:instrument/id :instrument/name :instrument/authority-rank :instrument/org :instrument/type :instrument/topics]}]}]
          eid))

(defn- has-decisions?
  [db instrument-id]
  (boolean
   (ffirst
    (d/q '[:find ?d
           :in $ ?inst
           :where [?d :decision/cited-instruments ?inst]]
         db [:instrument/id instrument-id]))))

(defn search-docs
  [db {:keys [q doc-type org language topic authority-band issued-from issued-to effective-from effective-to publication-from publication-to has-decisions? limit]}]
  (let [limit (or limit 50)
        q (some-> q str/trim)
        q-lc (some-> q str/lower-case)
        hits (if (seq q-lc)
               (map first
                    (d/q '[:find ?doc
                           :in $ ?q
                           :where [?span :doc.span/text-search ?text]
                                  [(clojure.string/includes? ?text ?q)]
                                  [?span :doc.span/doc ?doc]]
                         db q-lc))
               (map first
                    (d/q '[:find ?e
                           :where [?e :doc/id _]] db)))
        docs (map #(pull-doc db %) hits)
        band-min (authority-band->min authority-band)
        band-max (authority-band->max authority-band)
        issued-from (parse-date issued-from)
        issued-to (parse-date issued-to)
        effective-from (parse-date effective-from)
        effective-to (parse-date effective-to)
        publication-from (parse-date publication-from)
        publication-to (parse-date publication-to)]
    (->> docs
         (filter (fn [doc]
                   (let [doc-type-ok (if doc-type (= doc-type (:doc/type doc)) true)
                         org-ok (if org (= org (:doc/org doc)) true)
                         lang-ok (if language (= language (:doc/language doc)) true)
                         inst (get-in doc [:doc/instrument-version :instrument.version/instrument])
                         rank (:instrument/authority-rank inst)
                         rank-ok (if (and band-min band-max rank)
                                   (<= band-min rank band-max)
                                   true)
                         topic-ok (if (seq topic)
                                    (let [topics (set (map name (:instrument/topics inst)))]
                                      (some #(str/includes? % (str/lower-case topic))
                                            (map str/lower-case topics)))
                                    true)
                         issued (:doc/issued-at doc)
                         effective (:doc/effective-at doc)
                         publication (:doc/publication-at doc)
                         issued-ok (within-range? issued issued-from issued-to)
                         effective-ok (within-range? effective effective-from effective-to)
                         publication-ok (within-range? publication publication-from publication-to)
                         decisions-ok (if (true? has-decisions?)
                                        (and inst (has-decisions? db (:instrument/id inst)))
                                        true)]
                     (and doc-type-ok org-ok lang-ok topic-ok rank-ok issued-ok effective-ok publication-ok decisions-ok))))
         (map (fn [doc]
                (let [inst (get-in doc [:doc/instrument-version :instrument.version/instrument])]
                  {:doc/id (:doc/id doc)
                   :doc/title (:doc/title doc)
                   :doc/type (:doc/type doc)
                   :doc/org (:doc/org doc)
                   :doc/source (:doc/source doc)
                   :doc/language (:doc/language doc)
                   :doc/issued-at (:doc/issued-at doc)
                   :doc/effective-at (:doc/effective-at doc)
                   :doc/publication-at (:doc/publication-at doc)
                   :doc/last-seen-at (:doc/last-seen-at doc)
                   :doc/source-link-count (count (:doc/source-links doc))
                   :instrument/id (:instrument/id inst)
                   :instrument/name (:instrument/name inst)
                   :instrument/authority-rank (:instrument/authority-rank inst)})))
         (take limit)
         vec)))

(defn instrument-detail
  [db instrument-id]
  (when-let [eid (ffirst (d/q '[:find ?e :in $ ?id :where [?e :instrument/id ?id]] db instrument-id))]
    (let [instrument (d/pull db
                             [:instrument/id :instrument/name :instrument/type :instrument/authority-rank
                              :instrument/org :instrument/jurisdiction :instrument/languages :instrument/status]
                             eid)
          versions (d/q '[:find ?v
                          :in $ ?inst
                          :where [?v :instrument.version/instrument ?inst]]
                        db [:instrument/id instrument-id])
          version-entities (map first versions)
          version-data (map (fn [vid]
                              (d/pull db
                                      [:instrument.version/id :instrument.version/label :instrument.version/amended-at
                                       :instrument.version/status
                                       {:instrument.version/docs [:doc/id :doc/title :doc/type :doc/url :doc/blob]}]
                                      vid))
                            version-entities)]
      {:instrument instrument
       :versions (vec version-data)})))

(defn instrument-sections
  [db version-id]
  (let [vid (ffirst (d/q '[:find ?e :in $ ?id :where [?e :instrument.version/id ?id]] db version-id))]
    (when vid
      (let [sections (map first (d/q '[:find ?s
                                       :in $ ?v
                                       :where [?s :section/version ?v]]
                                     db vid))
            data (map (fn [sid]
                        (d/pull db [:section/id :section/title :section/number :section/order :section/level
                                    :section/text
                                    {:section/parent [:section/id]}]
                                sid))
                      sections)]
        (vec (sort-by :section/order data))))))

(defn decision-detail
  [db decision-id]
  (when-let [eid (ffirst (d/q '[:find ?e :in $ ?id :where [?e :decision/id ?id]] db decision-id))]
    (d/pull db [:decision/id :decision/date :decision/outcome :decision/authority
                {:decision/doc [:doc/id :doc/title :doc/url]}
                {:decision/cited-instruments [:instrument/id :instrument/name]}
                {:decision/cited-sections [:section/id :section/number :section/title]}]
            eid)))

(defn list-sources
  [db]
  (let [runs (d/q '[:find ?r ?started ?finished ?status ?metrics
                    :where [?r :crawl.run/id _]
                           [?r :crawl.run/started-at ?started]
                           [?r :crawl.run/status ?status]
                           (or-join [?r ?finished] [?r :crawl.run/finished-at ?finished] [(ground nil) ?finished])
                           (or-join [?r ?metrics] [?r :crawl.run/source-metrics ?metrics] [(ground nil) ?metrics])]
                  db)]
    (map (fn [[rid started finished status metrics]]
           {:crawl.run/id rid
            :crawl.run/started-at started
            :crawl.run/finished-at finished
            :crawl.run/status status
            :crawl.run/source-metrics metrics})
         runs)))
