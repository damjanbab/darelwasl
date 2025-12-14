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

(defn- person-summaries
  [db]
  (map (fn [[pid name addr parcel-count area]]
         {:person/id pid
          :person/name name
          :person/address addr
          :person/parcel-count parcel-count
          :person/owned-area-m2 area})
       (d/q '[:find ?pid ?name ?addr (count-distinct ?parcel) (sum ?area-share)
              :where [?p :person/id ?pid]
                     [?p :person/name ?name]
                     [?p :person/address ?addr]
                     [?o :ownership/person ?p]
                     [?o :ownership/parcel ?parcel]
                     [(get-else $ ?o :ownership/area-share-m2 0.0) ?area-share]]
            db)))

(defn- parcel-summaries
  [db]
  (map (fn [[pid cad-id cad-name number book addr area owners share]]
         {:parcel/id pid
          :parcel/cadastral-id cad-id
          :parcel/cadastral-name cad-name
          :parcel/number number
          :parcel/book-number book
          :parcel/address addr
          :parcel/area-m2 area
          :parcel/owner-count owners
          :parcel/share-total share})
       (d/q '[:find ?pid ?cad-id ?cad-name ?number ?book ?addr ?area
              (count ?o) (sum ?share)
              :where [?p :parcel/id ?pid]
                     [?p :parcel/cadastral-id ?cad-id]
                     [?p :parcel/cadastral-name ?cad-name]
                     [?p :parcel/number ?number]
                     [(get-else $ ?p :parcel/book-number "") ?book]
                     [(get-else $ ?p :parcel/address "") ?addr]
                     [(get-else $ ?p :parcel/area-m2 0.0) ?area]
                     [?o :ownership/parcel ?p]
                     [(get-else $ ?o :ownership/share 0.0) ?share]]
            db)))

(defn- person-ownerships
  [db person-id]
  (map (fn [[parcel-id share area order pos cad-id cad-name number book addr area-m2]]
         {:parcel/id parcel-id
          :ownership/share share
          :ownership/area-share-m2 area
          :ownership/list-order order
          :ownership/position-in-list pos
          :parcel/cadastral-id cad-id
          :parcel/cadastral-name cad-name
          :parcel/number number
          :parcel/book-number book
          :parcel/address addr
          :parcel/area-m2 area-m2})
       (d/q '[:find ?parcel-id ?share ?area-share ?order ?pos ?cad-id ?cad-name ?number ?book ?addr ?area
              :in $ ?pid
              :where [?p :person/id ?pid]
                     [?o :ownership/person ?p]
                     [?o :ownership/parcel ?parcel-e]
                     [?parcel-e :parcel/id ?parcel-id]
                     [(get-else $ ?o :ownership/share 0.0) ?share]
                     [(get-else $ ?o :ownership/area-share-m2 0.0) ?area-share]
                     [(get-else $ ?o :ownership/list-order nil) ?order]
                     [(get-else $ ?o :ownership/position-in-list nil) ?pos]
                     [(get-else $ ?parcel-e :parcel/cadastral-id "") ?cad-id]
                     [(get-else $ ?parcel-e :parcel/cadastral-name "") ?cad-name]
                     [(get-else $ ?parcel-e :parcel/number "") ?number]
                     [(get-else $ ?parcel-e :parcel/book-number "") ?book]
                     [(get-else $ ?parcel-e :parcel/address "") ?addr]
                     [(get-else $ ?parcel-e :parcel/area-m2 0.0) ?area]]
            db)))

(defn- parcel-owners
  [db parcel-id]
  (map (fn [[person-id name addr share area order pos]]
         {:person/id person-id
          :person/name name
          :person/address addr
          :ownership/share share
          :ownership/area-share-m2 area
          :ownership/list-order order
          :ownership/position-in-list pos})
       (d/q '[:find ?pid ?name ?addr ?share ?area ?order ?pos
              :in $ ?parcel-id
              :where [?parcel-e :parcel/id ?parcel-id]
                     [?o :ownership/parcel ?parcel-e]
                     [?o :ownership/person ?person-e]
                     [?person-e :person/id ?pid]
                     [?person-e :person/name ?name]
                     [(get-else $ ?person-e :person/address "") ?addr]
                     [(get-else $ ?o :ownership/share 0.0) ?share]
                     [(get-else $ ?o :ownership/area-share-m2 0.0) ?area]
                     [(get-else $ ?o :ownership/list-order nil) ?order]
                     [(get-else $ ?o :ownership/position-in-list nil) ?pos]]
            db)))

(defn people
  [conn {:keys [q sort limit offset]}]
  (let [db (d/db conn)
        summaries (person-summaries db)
        qn (normalize-search q)
        filtered (cond->> summaries
                   qn (filter (fn [{:person/keys [name address]}]
                                (let [haystack (-> (str (or name "") " " (or address ""))
                                                   str/lower-case)]
                                  (str/includes? haystack qn)))))
        sorted (->> filtered
                    (sort-by (fn [p]
                               (case (keyword sort)
                                 :area (- (:person/owned-area-m2 p))
                                 :parcels (- (:person/parcel-count p))
                                 (:person/name p))))
                    vec)
        l (or limit default-list-limit)
        total (count sorted)
        bounded-offset (min (or offset 0) (max 0 (- total l)))
        paged (->> sorted
                   (drop bounded-offset)
                   (take l)
                   vec)]
    {:people paged
     :pagination {:total total
                  :limit l
                  :offset bounded-offset
                  :page (inc (quot bounded-offset l))
                  :returned (count paged)}}))

(defn person-detail
  [conn person-id]
  (let [db (d/db conn)
        person (d/pull db [:person/id :person/name :person/address] [:person/id person-id])]
    (when person
      (let [owned (person-ownerships db person-id)]
        (assoc person
               :person/parcel-count (count (set (map :parcel/id owned)))
               :person/owned-area-m2 (reduce + 0 (keep :ownership/area-share-m2 owned))
               :person/ownerships owned)))))

(defn parcels
  [conn {:keys [cadastral-id parcel-number min-area max-area completeness sort limit offset]}]
  (let [db (d/db conn)
        summaries (parcel-summaries db)
        completeness-kw (some-> completeness keyword)
        min-area-num (when min-area (Double/parseDouble (str min-area)))
        max-area-num (when max-area (Double/parseDouble (str max-area)))
        filtered (cond->> summaries
                   cadastral-id (filter #(= cadastral-id (:parcel/cadastral-id %)))
                   parcel-number (filter #(= parcel-number (:parcel/number %)))
                   min-area-num (filter #(>= (:parcel/area-m2 %) min-area-num))
                   max-area-num (filter #(<= (:parcel/area-m2 %) max-area-num))
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
        l (or limit default-list-limit)
        total (count sorted)
        bounded-offset (min (or offset 0) (max 0 (- total l)))
        paged (->> sorted
                   (drop bounded-offset)
                   (take l))]
    {:parcels (vec paged)
     :pagination {:total total
                  :limit l
                  :offset bounded-offset
                  :page (inc (quot bounded-offset l))
                  :returned (count paged)}}))

(defn parcel-detail
  [conn parcel-id]
  (let [db (d/db conn)
        parcel (d/pull db [:parcel/id
                           :parcel/cadastral-id
                           :parcel/cadastral-name
                           :parcel/number
                           :parcel/book-number
                           :parcel/address
                           :parcel/area-m2]
                       [:parcel/id parcel-id])]
    (when parcel
      (let [owners (parcel-owners db parcel-id)
            share-total (reduce + 0 (keep :ownership/share owners))]
        (assoc parcel
               :parcel/owners owners
               :parcel/owner-count (count owners)
               :parcel/share-total share-total)))))

(defn stats
  [conn]
  (let [db (d/db conn)
        persons (person-summaries db)
        parcels (parcel-summaries db)
        total-area (reduce + 0 (map :parcel/area-m2 parcels))
        share-complete? (fn [p] (< (Math/abs (- (:parcel/share-total p 0.0) 1e-6))))]
    {:persons (count persons)
     :parcels (count parcels)
     :total-area-m2 total-area
     :share-complete (count (filter share-complete? parcels))
     :share-complete-pct (if (pos? (count parcels))
                           (* 100.0 (/ (count (filter share-complete? parcels)) (count parcels)))
                           0.0)
     :top-owners (->> persons
                      (sort-by (comp - :person/owned-area-m2))
                      (take 5)
                      vec)}))
