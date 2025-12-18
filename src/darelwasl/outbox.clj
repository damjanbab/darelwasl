(ns darelwasl.outbox
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d])
  (:import (java.util Date UUID)))

(def ^:private default-max-attempts 5)

(defn- now [] (Date.))

(defn- ensure-conn
  [conn]
  (when-not conn
    {:error {:status 500 :message "Database not ready"}}))

(defn- parse-edn
  [s]
  (when (string? s)
    (try (edn/read-string s)
         (catch Exception _ nil))))

(defn decode-payload
  "Decode an EDN payload string to a Clojure value."
  [s]
  (parse-edn s))

(defn enqueue!
  "Enqueue an outbox job. Returns {:outbox/id ...} or {:error ...}.
   Options: :integration (keyword, required), :payload (map, required),
   :dedupe-key (string, optional), :available-at (Date, optional)."
  [conn {:keys [integration payload dedupe-key available-at]}]
  (or (ensure-conn conn)
      (cond
        (nil? integration) {:error {:status 400 :message "Missing integration"}}
        (not (keyword? integration)) {:error {:status 400 :message "Invalid integration"}}
        (not (map? payload)) {:error {:status 400 :message "Payload must be a map"}}
        (and dedupe-key (str/blank? (str dedupe-key))) {:error {:status 400 :message "Invalid dedupe key"}}
        :else
        (let [id (UUID/randomUUID)
              now (now)
              dedupe (some-> dedupe-key str/trim)
              tx {:outbox/id id
                  :entity/type :entity.type/outbox
                  :outbox/integration integration
                  :outbox/status :pending
                  :outbox/payload (pr-str payload)
                  :outbox/attempts 0
                  :outbox/available-at (or available-at now)
                  :outbox/created-at now
                  :outbox/updated-at now}
              tx (cond-> tx dedupe (assoc :outbox/dedupe-key dedupe))]
          (try
            (d/transact conn {:tx-data [tx]})
            {:outbox/id id}
            (catch Exception e
              (let [data (ex-data e)
                    unique? (or (= (:db/error data) :db.error/unique-conflict)
                                (some-> e .getMessage (str/includes? "unique")))]
                (if (and unique? dedupe)
                  (let [existing (ffirst (d/q '[:find (pull ?e [:outbox/id :outbox/status :outbox/payload
                                                                :outbox/dedupe-key :outbox/integration
                                                                :outbox/attempts :outbox/available-at
                                                                :outbox/updated-at])
                                                :in $ ?k
                                                :where [?e :outbox/dedupe-key ?k]]
                                              (d/db conn) dedupe))]
                    (if existing
                      {:outbox/id (:outbox/id existing)
                       :outbox existing}
                      {:error {:status 500 :message "Unique conflict on outbox dedupe-key"}}))
                  (do
                    (log/warn e "Failed to enqueue outbox message")
                    {:error {:status 500 :message "Failed to enqueue outbox message"}})))))))))

(defn- backoff-ms
  [attempts]
  (let [base 2000
        capped (* 5 60 1000)] ; 5 minutes cap
    (min capped (+ base (* (Math/pow 2 attempts) 200)))))

(defn mark-success!
  [conn outbox-id]
  (or (ensure-conn conn)
      (try
        (let [now (now)]
          (d/transact conn {:tx-data [[:db/add [:outbox/id outbox-id] :outbox/status :succeeded]
                                      [:db/add [:outbox/id outbox-id] :outbox/updated-at now]]})
          {:status :ok})
        (catch Exception e
          (log/warn e "Failed to mark outbox success" {:outbox/id outbox-id})
          {:error {:status 500 :message "Failed to mark outbox success"}}))))

(defn mark-failure!
  [conn outbox-id message attempts max-attempts]
  (or (ensure-conn conn)
      (let [max-attempts (or max-attempts default-max-attempts)
            attempts (or attempts 0)
            next-attempt (inc attempts)
            now (now)
            retry? (< next-attempt max-attempts)
            next-available (Date. (+ (.getTime now) (backoff-ms attempts)))]
        (try
          (d/transact conn {:tx-data (cond-> [[:db/add [:outbox/id outbox-id] :outbox/attempts next-attempt]
                                              [:db/add [:outbox/id outbox-id] :outbox/last-error (or message "unknown error")]
                                              [:db/add [:outbox/id outbox-id] :outbox/updated-at now]
                                              [:db/add [:outbox/id outbox-id] :outbox/status (if retry? :pending :failed)]]
                                       retry? (conj [:db/add [:outbox/id outbox-id] :outbox/available-at next-available]))})
          {:status (if retry? :retry :failed)}
          (catch Exception e
            (log/warn e "Failed to mark outbox failure" {:outbox/id outbox-id})
            {:error {:status 500 :message "Failed to mark outbox failure"}})))))

(defn claim-one!
  "Attempt to claim one pending job for a worker-id. Returns pulled entity (with :outbox/id) or nil."
  [conn worker-id integration]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            now (now)
            candidates (d/q '[:find ?e ?id ?avail
                              :in $ ?now ?integration
                              :where [?e :outbox/status :pending]
                                     [?e :outbox/available-at ?avail]
                                     [(<= ?avail ?now)]
                                     [?e :outbox/id ?id]
                                     [?e :outbox/integration ?integration]]
                            db now integration)
            sorted (sort-by #(nth % 2) candidates)]
        (loop [cands sorted]
          (when-let [[eid oid _] (first cands)]
            (let [result (try
                           (let [tx {:tx-data [[:db.fn/cas eid :outbox/status :pending :processing]
                                               [:db/add eid :outbox/locked-at now]
                                               [:db/add eid :outbox/worker worker-id]
                                               [:db/add eid :outbox/updated-at now]]}
                                 tx-res (d/transact conn tx)
                                 db-after (:db-after tx-res)]
                             (d/pull db-after [:outbox/id :outbox/integration :outbox/payload
                                               :outbox/status :outbox/attempts :outbox/dedupe-key
                                               :outbox/available-at :outbox/locked-at :outbox/worker]
                                     eid))
                           (catch Exception e
                             (log/debug e "CAS failed when claiming outbox entry" {:outbox/id oid})
                             ::conflict))]
              (if (= result ::conflict)
                (recur (rest cands))
                result)))))))
