(ns darelwasl.report-cards
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.db :as db]
            [darelwasl.entity :as entity]
            [darelwasl.provenance :as prov]
            [darelwasl.schema :as schema]
            [darelwasl.validation :as v]
            [darelwasl.workspace :as workspace])
  (:import (java.time Instant)
           (java.util Date UUID)))

(def ^:private report-schema-entry
  (delay (some #(when (= (:id %) :cap/schema/report-card) %) (schema/read-registry))))

(def ^:private allowed-types
  (delay (or (some-> @report-schema-entry (get-in [:enums :report.card/type]) set)
             #{:report.card.type/onboarding :report.card.type/proposal-response})))

(def ^:private param-value v/param-value)
(def ^:private normalize-uuid v/normalize-uuid)
(def ^:private normalize-enum v/normalize-enum)
(def ^:private normalize-string v/normalize-string)

(defn- error
  [status message & [details]]
  {:error {:status status
           :message message
           :details details}})

(defn- ensure-conn
  [conn]
  (when-not conn
    (error 500 "Database not ready")))

(defn- now-inst
  []
  (Date/from (Instant/now)))

(def ^:private pull-pattern
  [:report.card/id
   :entity/ref
   :entity/type
   :report.card/type
   {:report.card/client [:client/id :client/name :entity/ref]}
   {:report.card/task [:task/id :task/title :entity/ref]}
   :report.card/fields
   :report.card/submitted-at
   :report.card/created-at
   :report.card/updated-at
   :report.card/automation-key
   :report.card/workspace])

(defn- report-eid-by-key
  [db automation-key ws]
  (when (and (string? automation-key) (not (str/blank? (str/trim automation-key))))
    (ffirst (d/q '[:find ?e
                   :in $ ?k ?ws
                   :where [?e :report.card/automation-key ?k]
                          [?e :report.card/workspace ?ws]]
                 db (str/trim automation-key) ws))))

(defn submit!
  "Create/update a report card. Input expects:
  - :report.card/type keyword
  - :task/id uuid
  - :client/id uuid
  - :report.card/fields EDN string (must parse to a map)."
  [conn params actor]
  (or
   (ensure-conn conn)
   (let [db0 (d/db conn)
         ws (workspace/resolve-id (workspace/actor-workspace actor))
         {typ :value typ-err :error} (normalize-enum (param-value params :report.card/type) @allowed-types "report card type")
         {task-id :value task-err :error} (normalize-uuid (param-value params :task/id) "task id")
         {client-id :value client-err :error} (normalize-uuid (param-value params :client/id) "client id")
         {fields-str :value fields-err :error} (normalize-string (param-value params :report.card/fields)
                                                                 "fields"
                                                                 {:required true
                                                                  :allow-blank? false})
         fields-map (when (string? fields-str)
                      (try
                        (edn/read-string fields-str)
                        (catch Exception _ ::invalid)))]
     (cond
       typ-err (error 400 typ-err)
       task-err (error 400 task-err)
       client-err (error 400 client-err)
       fields-err (error 400 fields-err)
       (not (map? fields-map)) (error 400 "report.card/fields must be an EDN map string")
       :else
       (let [automation-key (str "report-card:" (name typ) ":" task-id)
             existing-eid (report-eid-by-key db0 automation-key ws)
             now (now-inst)
             tx-prov (prov/provenance actor)
             tx-map (if existing-eid
                      {:report.card/id (:report.card/id (d/pull db0 [:report.card/id] existing-eid))
                       :report.card/type typ
                       :report.card/client [:client/id client-id]
                       :report.card/task [:task/id task-id]
                       :report.card/fields (pr-str fields-map)
                       :report.card/submitted-at now
                       :report.card/updated-at now
                       :report.card/workspace ws
                       :report.card/automation-key automation-key}
                      (-> {:report.card/id (UUID/randomUUID)
                           :entity/type :entity.type/report-card
                           :report.card/type typ
                           :report.card/client [:client/id client-id]
                           :report.card/task [:task/id task-id]
                           :report.card/fields (pr-str fields-map)
                           :report.card/submitted-at now
                           :report.card/created-at now
                           :report.card/updated-at now
                           :report.card/workspace ws
                           :report.card/automation-key automation-key}
                          (entity/with-ref db0)))
             tx-data [(prov/enrich-tx tx-map tx-prov)]]
         (try
           (db/transact! conn {:tx-data tx-data})
           (let [db1 (d/db conn)
                 eid (or existing-eid (report-eid-by-key db1 automation-key ws))
                 card (when eid (d/pull db1 pull-pattern eid))]
             (if card
               {:report-card card}
               (error 500 "Report card not available after submit")))
           (catch Exception e
             (log/error e "Failed to submit report card")
             (error 500 "Unable to submit report card" {:exception (.getMessage e)}))))))))
