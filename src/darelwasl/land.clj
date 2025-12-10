(ns darelwasl.land
  (:require [clojure.string :as str]
            [datomic.client.api :as d]))

(def ^:private default-list-limit 25)
(def ^:private max-list-limit 200)

(defn- parse-int
  [v]
  (cond
    (nil? v) nil
    (integer? v) v
    (number? v) (int v)
    (string? v) (try
                  (Integer/parseInt (str/trim v))
                  (catch Exception _ nil))
    :else nil))

(defn normalize-pagination
  "Normalize limit/offset from params; returns {:limit ... :offset ...} or {:error {:status 400 :message ...}}."
  [params]
  (let [limit-raw (or (get params :limit) (get params "limit"))
        offset-raw (or (get params :offset) (get params "offset"))
        limit (or (parse-int limit-raw) default-list-limit)
        offset (or (parse-int offset-raw) 0)]
    (cond
      (or (nil? limit) (<= limit 0)) {:error {:status 400
                                              :message (str "Invalid limit; must be between 1 and " max-list-limit)}}
      (> limit max-list-limit) {:error {:status 400
                                        :message (str "Limit too high; max " max-list-limit)}}
      (or (nil? offset) (neg? offset)) {:error {:status 400
                                                :message "Invalid offset; must be 0 or greater"}}
      :else {:limit limit
             :offset offset})))

(defn- normalize-search [s]
  (some-> s str/lower-case str/trim))

(defn- pull-persons [db]
  (into {}
        (map (fn [[id name addr]]
               [id {:person/id id
                    :person/name name
                    :person/address addr}]))
        (d/q '[:find ?id ?name ?addr
               :where [?p :person/id ?id]
                      [?p :person/name ?name]
                      [?p :person/address ?addr]]
             db)))

(defn- pull-parcels [db]
  (into {}
        (map (fn [[id cad-id cad-name number book addr area]]
               [id {:parcel/id id
                    :parcel/cadastral-id cad-id
                    :parcel/cadastral-name cad-name
                    :parcel/number number
                    :parcel/book-number book
                    :parcel/address addr
                    :parcel/area-m2 area}]))
        (d/q '[:find ?id ?cad-id ?cad-name ?number ?book ?addr ?area
               :where [?p :parcel/id ?id]
                      [?p :parcel/cadastral-id ?cad-id]
                      [?p :parcel/cadastral-name ?cad-name]
                      [?p :parcel/number ?number]
                      [(get-else $ ?p :parcel/book-number "") ?book]
                      [(get-else $ ?p :parcel/address "") ?addr]
                      [(get-else $ ?p :parcel/area-m2 0.0) ?area]]
             db)))

(defn- pull-ownerships [db]
  (let [ownership-ids (map first (d/q '[:find ?o
                                        :where [?o :ownership/person _]]
                                      db))]
    (map (fn [oid]
           (let [o (d/pull db '[:ownership/share
                                :ownership/area-share-m2
                                :ownership/list-order
                                :ownership/position-in-list
                                {:ownership/person [:person/id]}
                                {:ownership/parcel [:parcel/id]}]
                            oid)]
             [(:person/id (:ownership/person o))
              (:parcel/id (:ownership/parcel o))
              (or (:ownership/share o) 0.0)
              (or (:ownership/area-share-m2 o) 0.0)
              (:ownership/list-order o)
              (:ownership/position-in-list o)]))
         ownership-ids)))

(defn- attach-ownership
  [{:keys [persons parcels ownerships]}]
  (let [ownerships-by-person (reduce (fn [acc [pid parcel-id share area order pos]]
                                       (update acc pid conj {:parcel/id parcel-id
                                                             :ownership/share share
                                                             :ownership/area-share-m2 area
                                                             :ownership/list-order order
                                                             :ownership/position-in-list pos}))
                                     {}
                                     ownerships)
        ownerships-by-parcel (reduce (fn [acc [pid parcel-id share area order pos]]
                                       (update acc parcel-id conj {:person/id pid
                                                                   :ownership/share share
                                                                   :ownership/area-share-m2 area
                                                                   :ownership/list-order order
                                                                   :ownership/position-in-list pos}))
                                     {}
                                     ownerships)
        persons-enriched (map (fn [[pid person]]
                                (let [owns (get ownerships-by-person pid)
                                      parcels-owned (set (map :parcel/id owns))
                                      total-area (reduce + 0 (keep :ownership/area-share-m2 owns))]
                                  (assoc person
                                         :person/parcel-count (count parcels-owned)
                                         :person/owned-area-m2 total-area
                                         :person/ownerships owns)))
                              persons)
        parcels-enriched (map (fn [[parcel-id parcel]]
                                (let [owners (get ownerships-by-parcel parcel-id)
                                      owner-count (count owners)
                                      share-total (reduce + 0 (keep :ownership/share owners))]
                                  (assoc parcel
                                         :parcel/owners owners
                                         :parcel/owner-count owner-count
                                         :parcel/share-total share-total)))
                              parcels)]
    {:persons persons-enriched
     :parcels parcels-enriched
     :persons-by-id persons
     :parcels-by-id parcels}))

(defn people
  [conn {:keys [q sort limit offset]}]
  (let [db (d/db conn)
        base {:persons (pull-persons db)
              :parcels (pull-parcels db)
              :ownerships (pull-ownerships db)}
        {:keys [persons]} (attach-ownership base)
        qn (normalize-search q)
        filtered (cond->> persons
                   qn (filter (fn [{:keys [person/name person/address]}]
                                (let [haystack (str/lower-case (str name " " address))]
                                  (str/includes? haystack qn))))) 
        sorted (->> filtered
                    (sort-by (fn [p]
                               (case (keyword sort)
                                 :area (- (:person/owned-area-m2 p))
                                 :parcels (- (:person/parcel-count p))
                                 (:person/name p))))
                    vec)
        total (count sorted)
        bounded-offset (min (or offset 0) (max 0 (- total (or limit default-list-limit))))
        paged (->> sorted
                   (drop bounded-offset)
                   (take (or limit default-list-limit))
                   vec)]
    {:people paged
     :pagination {:total total
                  :limit (or limit default-list-limit)
                  :offset bounded-offset}}))

(defn person-detail
  [conn person-id]
  (let [db (d/db conn)
        base {:persons (pull-persons db)
              :parcels (pull-parcels db)
              :ownerships (pull-ownerships db)}
        {:keys [persons parcels-by-id]} (attach-ownership base)
        person (some #(when (= (:person/id %) person-id) %) persons)]
    (when person
      (let [owned (map (fn [o]
                         (merge o (get parcels-by-id (:parcel/id o))))
                       (:person/ownerships person))]
        (assoc person :person/ownerships owned)))))

(defn parcels
  [conn {:keys [cadastral-id parcel-number min-area max-area completeness sort limit offset]}]
  (let [db (d/db conn)
        base {:persons (pull-persons db)
              :parcels (pull-parcels db)
              :ownerships (pull-ownerships db)}
        {:keys [parcels]} (attach-ownership base)
        completeness-kw (some-> completeness keyword)
        filtered (cond->> parcels
                   cadastral-id (filter #(= cadastral-id (:parcel/cadastral-id %)))
                   parcel-number (filter #(= parcel-number (:parcel/number %)))
                   min-area (filter #(>= (:parcel/area-m2 %) (Double/parseDouble (str min-area))))
                   max-area (filter #(<= (:parcel/area-m2 %) (Double/parseDouble (str max-area))))
                   completeness-kw (filter (fn [p]
                                             (let [complete? (< (Math/abs (- (:parcel/share-total p 0.0) 1.0)) 1e-6)]
                                               (case completeness-kw
                                                 :complete complete?
                                                 :incomplete (not complete?)
                                                 true)))))
        sorted (case (keyword sort)
                 :area (sort-by (comp - :parcel/area-m2) filtered)
                 :owners (sort-by (comp - :parcel/owner-count) filtered)
                 filtered)
        total (count sorted)
        bounded-offset (min (or offset 0) (max 0 (- total (or limit default-list-limit))))
        paged (->> sorted
                   (drop bounded-offset)
                   (take (or limit default-list-limit)))]
    {:parcels (vec paged)
     :pagination {:total total
                  :limit (or limit default-list-limit)
                  :offset bounded-offset}}))

(defn parcel-detail
  [conn parcel-id]
  (let [db (d/db conn)
        base {:persons (pull-persons db)
              :parcels (pull-parcels db)
              :ownerships (pull-ownerships db)}
        {:keys [persons-by-id parcels]} (attach-ownership base)
        parcel (some #(when (= (:parcel/id %) parcel-id) %) parcels)]
    (when parcel
      (let [owners (map (fn [o] (merge o (get persons-by-id (:person/id o))))
                        (:parcel/owners parcel))]
        (assoc parcel :parcel/owners owners)))))

(defn stats
  [conn]
  (let [db (d/db conn)
        base {:persons (pull-persons db)
              :parcels (pull-parcels db)
              :ownerships (pull-ownerships db)}
        {:keys [persons parcels]} (attach-ownership base)
        total-area (reduce + 0 (map :parcel/area-m2 parcels))
        share-complete? (fn [p] (< (Math/abs (- (:parcel/share-total p 0.0) 1.0)) 1e-6))
        complete-count (count (filter share-complete? parcels))]
    {:persons (count persons)
     :parcels (count parcels)
     :total-area-m2 total-area
     :share-complete complete-count
     :share-complete-pct (if (pos? (count parcels))
                           (* 100.0 (/ complete-count (count parcels)))
                           0.0)
     :top-owners (->> persons
                      (sort-by (comp - :person/owned-area-m2))
                      (take 5)
                      vec)}))
