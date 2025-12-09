(ns darelwasl.land
  (:require [clojure.string :as str]
            [datomic.client.api :as d]))

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
  (d/q '[:find ?person-id ?parcel-id ?share ?area ?order ?pos
         :where
         [?o :ownership/person ?person]
         [?o :ownership/parcel ?parcel]
         [?person :person/id ?person-id]
         [?parcel :parcel/id ?parcel-id]
         [?o :ownership/share ?share]
         [(get-else $ ?o :ownership/area-share-m2 0.0) ?area]
         [(get-else $ ?o :ownership/list-order nil) ?order]
         [(get-else $ ?o :ownership/position-in-list nil) ?pos]]
       db))

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
                                     ownerships)]
    {:persons (map (fn [[pid person]]
                     (let [owns (get ownerships-by-person pid)
                           parcels-owned (set (map :parcel/id owns))
                           total-area (reduce + 0 (keep :ownership/area-share-m2 owns))]
                       (assoc person
                              :person/parcel-count (count parcels-owned)
                              :person/owned-area-m2 total-area
                              :person/ownerships owns)))
                   persons)
     :parcels (map (fn [[parcel-id parcel]]
                     (let [owners (get ownerships-by-parcel parcel-id)
                           owner-count (count owners)
                           share-total (reduce + 0 (keep :ownership/share owners))]
                       (assoc parcel
                              :parcel/owners owners
                              :parcel/owner-count owner-count
                              :parcel/share-total share-total)))
                   parcels)}))

(defn people
  [conn {:keys [q sort]}]
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
        sorter (case (keyword sort)
                 :area (fn [a b] (> (:person/owned-area-m2 a) (:person/owned-area-m2 b)))
                 :parcels (fn [a b] (> (:person/parcel-count a) (:person/parcel-count b)))
                 nil)]
    (->> filtered
         (sort-by (fn [p]
                    (case (keyword sort)
                      :area (- (:person/owned-area-m2 p))
                      :parcels (- (:person/parcel-count p))
                      (:person/name p))))
         vec)))

(defn person-detail
  [conn person-id]
  (let [db (d/db conn)
        base {:persons (pull-persons db)
              :parcels (pull-parcels db)
              :ownerships (pull-ownerships db)}
        {:keys [persons parcels]} (attach-ownership base)
        person (some #(when (= (:person/id %) person-id) %) persons)]
    (when person
      (let [owned (map (fn [o]
                         (merge o (get parcels (:parcel/id o))))
                       (:person/ownerships person))]
        (assoc person :person/ownerships owned)))))

(defn parcels
  [conn {:keys [cadastral-id parcel-number min-area max-area completeness sort]}]
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
                 filtered)]
    (vec sorted)))

(defn parcel-detail
  [conn parcel-id]
  (let [db (d/db conn)
        base {:persons (pull-persons db)
              :parcels (pull-parcels db)
              :ownerships (pull-ownerships db)}
        {:keys [persons parcels]} (attach-ownership base)
        parcel (some #(when (= (:parcel/id %) parcel-id) %) parcels)]
    (when parcel
      (let [owners (map (fn [o] (merge o (get persons (:person/id o))))
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
