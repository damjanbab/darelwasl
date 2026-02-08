;; Service case tracking (contract-driven steps) + public portal token utilities.
(ns darelwasl.service-cases
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.contracts :as contracts]
            [darelwasl.db :as db]
            [darelwasl.entity :as entity]
            [darelwasl.provenance :as prov]
            [darelwasl.validation :as v]
            [darelwasl.workspace :as workspace])
  (:import (java.security SecureRandom)
           (java.time Instant)
           (java.time.format DateTimeFormatter)
           (java.util Date UUID)))

(def ^:private iso-formatter DateTimeFormatter/ISO_INSTANT)

(defn- format-inst
  [^Date inst]
  (when inst
    (.format iso-formatter (.toInstant inst))))

(defn- now-inst
  []
  (Date/from (Instant/now)))

(defn- error
  [status message & [details]]
  {:error {:status status
           :message message
           :details details}})

(defn- ensure-conn
  [conn]
  (when-not conn
    (error 500 "Database not ready")))

(def ^:private normalize-uuid v/normalize-uuid)
(def ^:private normalize-string v/normalize-string)
(def ^:private param-value v/param-value)

(def ^:private case-pull
  [:service.case/id
   :entity/ref
   :entity/type
   {:service.case/client [:client/id :client/name :entity/ref]}
   {:service.case/assignee [:user/id :user/username :user/name]}
   :service.case/service
   :service.case/title
   :service.case/lifecycle
   :service.case/lifecycle-reason
   :service.case/contract-key
   :service.case/contract-version
   :service.case/public-max-phase
   :service.case/public-current-phase
   :service.case/archived?
   :service.case/created-at
   :service.case/updated-at
   :service.case/workspace])

(def ^:private step-pull
  [:service.case.step/id
   :entity/ref
   :entity/type
   {:service.case.step/case [:service.case/id :entity/ref]}
   :service.case.step/step
   :service.case.step/order
   :service.case.step/owner
   :service.case.step/status
   :service.case.step/reason
   :service.case.step/public-phase
   :service.case.step/public-label
   :service.case.step/public-next-action
   :service.case.step/internal-label
   :service.case.step/updated-at
   :service.case.step/workspace])

(defn- present-step
  [step]
  (when (map? step)
    (-> step
        (update :service.case.step/updated-at format-inst)
        (select-keys [:service.case.step/id
                      :service.case.step/step
                      :service.case.step/order
                      :service.case.step/owner
                      :service.case.step/status
                      :service.case.step/reason
                      :service.case.step/public-phase
                      :service.case.step/public-label
                      :service.case.step/public-next-action
                      :service.case.step/internal-label
                      :service.case.step/updated-at
                      :entity/ref]))))

(defn- present-case
  [c]
  (when (map? c)
    (-> c
        (update :service.case/created-at format-inst)
        (update :service.case/updated-at format-inst)
        (select-keys [:service.case/id
                      :service.case/service
                      :service.case/title
                      :service.case/lifecycle
                      :service.case/lifecycle-reason
                      :service.case/contract-key
                      :service.case/contract-version
                      :service.case/public-max-phase
                      :service.case/public-current-phase
                      :service.case/archived?
                      :service.case/created-at
                      :service.case/updated-at
                      :service.case/workspace
                      :service.case/client
                      :service.case/assignee
                      :entity/ref]))))

(defn- workspace-id
  [value]
  (workspace/resolve-id value))

(defn- resolve-client-id
  [db client-id]
  (entity/resolve-id db :client/id client-id "client id"))

(defn- client-eid
  [db client-id workspace]
  (let [{cid :value err :error} (resolve-client-id db client-id)]
    (cond
      err {:error (error 400 err)}
      (nil? cid) {:error (error 400 "client/id is required")}
      :else
      {:eid (ffirst (d/q '[:find ?e
                           :in $ ?id ?ws
                           :where [?e :client/id ?id]
                                  [?e :client/workspace ?ws]]
                         db cid workspace))
       :id cid})))

(defn- case-eid
  [db case-id workspace]
  (when case-id
    (ffirst (d/q '[:find ?e
                   :in $ ?id ?ws
                   :where [?e :service.case/id ?id]
                          [?e :service.case/workspace ?ws]]
                 db case-id workspace))))

(defn- fetch-case*
  [db case-id workspace]
  (when-let [eid (case-eid db case-id workspace)]
    (d/pull db case-pull eid)))

(defn- fetch-steps*
  [db case-id workspace]
  (let [rows (d/q '[:find ?e
                    :in $ ?id ?ws
                    :where [?c :service.case/id ?id]
                           [?c :service.case/workspace ?ws]
                           [?s :service.case.step/case ?c]
                           [?s :service.case.step/workspace ?ws]
                           [?s :service.case.step/order ?order]]
                  db case-id workspace)
        eids (map first rows)]
    (->> eids
         (map #(d/pull db step-pull %))
         (sort-by (fn [s] (or (:service.case.step/order s) Long/MAX_VALUE)))
         (mapv present-step))))

(defn- derive-lifecycle
  [steps]
  (let [statuses (set (map :service.case.step/status steps))
        step-by-id (into {} (map (juxt :service.case.step/step identity)) steps)
        done? (fn [id] (= :done (:service.case.step/status (get step-by-id id))))]
    (cond
      (contains? statuses :rejected) :s7/rejected
      (contains? statuses :blocked) :s8/blocked
      (contains? statuses :action-required) :s5/action-required
      (contains? statuses :under-review) :s4/under-review
      (contains? statuses :submitted) :s3/submitted
      (and (contains? step-by-id :step/documents) (done? :step/documents)) :s2/documents-complete
      (and (contains? step-by-id :step/eligibility) (done? :step/eligibility)) :s1/eligibility-validated
      :else :s0/intake)))

(defn- derive-public-phase
  "Derive a phase marker based on any started work (not-started) or completion.
  Conservative: only advances when steps move out of :not-started."
  [steps]
  (let [ranked (->> steps
                    (remove #(= :not-started (:service.case.step/status %)))
                    (map :service.case.step/public-phase)
                    (remove nil?))]
    (or (when (seq ranked)
          (last (sort-by contracts/public-phase-rank ranked)))
        :public.phase/onboarding)))

(defn- max-phase
  [a b]
  (let [ra (contracts/public-phase-rank a)
        rb (contracts/public-phase-rank b)]
    (if (>= ra rb) a b)))

(defn list-services
  []
  (contracts/read-services))

(defn create-case!
  [conn params actor]
  (or (ensure-conn conn)
      (let [db0 (d/db conn)
            ws (workspace-id (workspace/actor-workspace actor))
            {client-id :value client-err :error} (normalize-uuid (param-value params :client/id) "client id")
            service-id (param-value params :service/id)
            service-id (cond
                         (keyword? service-id) service-id
                         (string? service-id) (let [s (str/trim service-id)]
                                                (cond
                                                  (str/blank? s) nil
                                                  (str/starts-with? s ":") (keyword (subs s 1))
                                                  :else (keyword s)))
                         :else nil)
            {title :value title-err :error} (normalize-string (param-value params :service.case/title) "title" {:required false
                                                                                                               :allow-blank? false})
            {assignee-id :value assignee-err :error} (normalize-uuid (param-value params :service.case/assignee) "assignee id")
            services (contracts/services-index)
            service (get services service-id)
            contract (contracts/latest-contract-for-service service-id)]
        (cond
          client-err (error 400 client-err)
          (nil? client-id) (error 400 "client/id is required")
          (nil? service) (error 400 "Unknown service" {:service/id service-id})
          title-err (error 400 title-err)
          assignee-err (error 400 assignee-err)
          (nil? contract) (error 400 "No contract defined for service yet" {:service/id service-id})
          :else
          (let [{:keys [eid error]} (client-eid db0 client-id ws)]
            (if error
              error
              (let [case-id (UUID/randomUUID)
                    now (now-inst)
                    base-title (or title (:title service) (name service-id))
                    contract-key (:id contract)
                    contract-version (or (:version contract) "v0")
                    initial-phase :public.phase/onboarding
                    case-tx {:service.case/id case-id
                             :entity/type :entity.type/service-case
                             :service.case/client [:client/id client-id]
                             :service.case/service service-id
                             :service.case/title base-title
                             :service.case/lifecycle :s0/intake
                             :service.case/contract-key contract-key
                             :service.case/contract-version contract-version
                             :service.case/public-max-phase initial-phase
                             :service.case/public-current-phase initial-phase
                             :service.case/archived? false
                             :service.case/created-at now
                             :service.case/updated-at now
                             :service.case/workspace ws}
                    case-tx (cond-> case-tx
                              assignee-id (assoc :service.case/assignee [:user/id assignee-id]))
                    case-tx (entity/with-ref db0 case-tx)
                    steps (or (:steps contract) [])
                    step-txs (mapv (fn [{:keys [id order owner public internal]}]
                                     (let [public-phase (:phase public)
                                           public-label (:label public)
                                           public-next-action (:next-action public)
                                           internal-label (or (:label internal) public-label (name id))
                                           tx {:service.case.step/id (UUID/randomUUID)
                                               :entity/type :entity.type/service-case-step
                                               :service.case.step/case [:service.case/id case-id]
                                               :service.case.step/step id
                                               :service.case.step/order (long (or order 0))
                                               :service.case.step/owner (or owner :internal)
                                               :service.case.step/status :not-started
                                               :service.case.step/public-phase (or public-phase :public.phase/onboarding)
                                               :service.case.step/public-label (or public-label (name id))
                                               :service.case.step/public-next-action public-next-action
                                               :service.case.step/internal-label internal-label
                                               :service.case.step/updated-at now
                                               :service.case.step/workspace ws}
                                           tx (entity/with-ref db0 tx)]
                                       (-> tx
                                           (cond-> (nil? public-next-action) (dissoc :service.case.step/public-next-action))
                                           (cond-> (nil? public-label) (assoc :service.case.step/public-label (name id))))))
                                   steps)
                    tx-prov (prov/provenance actor)
                    tx-data (vec (concat
                                  [(prov/enrich-tx case-tx tx-prov)]
                                  (map #(prov/enrich-tx % tx-prov) step-txs)))
                    tx-res (try
                             (db/transact! conn {:tx-data tx-data})
                             (catch Exception e
                               (log/error e "Failed to create service case")
                               {:error e}))]
                (if (:error tx-res)
                  (error 500 "Unable to create service case")
                  (let [db1 (d/db conn)
                        created (fetch-case* db1 case-id ws)
                        steps' (fetch-steps* db1 case-id ws)]
                    {:service.case (present-case created)
                     :steps steps'})))))))))))

(defn list-cases
  [conn params actor]
  (or (ensure-conn conn)
      (let [db0 (d/db conn)
            ws (workspace-id (workspace/actor-workspace actor))
            {client-id :value client-err :error} (normalize-uuid (param-value params :client/id) "client id")]
        (cond
          client-err (error 400 client-err)
          (nil? client-id) (error 400 "client/id is required")
          :else
          (let [rows (d/q '[:find ?e ?created
                            :in $ ?cid ?ws
                            :where [?c :client/id ?cid]
                                   [?c :client/workspace ?ws]
                                   [?e :service.case/client ?c]
                                   [?e :service.case/workspace ?ws]
                                   [?e :service.case/created-at ?created]]
                          db0 client-id ws)
                eids (->> rows (sort-by second) reverse (map first) vec)
                items (->> eids
                           (map #(d/pull db0 case-pull %))
                           (map present-case)
                           vec)]
            {:service.cases items})))))

(defn read-case
  [conn params actor]
  (or (ensure-conn conn)
      (let [db0 (d/db conn)
            ws (workspace-id (workspace/actor-workspace actor))
            {case-id :value case-err :error} (normalize-uuid (param-value params :service.case/id) "service case id")]
        (cond
          case-err (error 400 case-err)
          (nil? case-id) (error 400 "service.case/id is required")
          :else
          (if-let [c (fetch-case* db0 case-id ws)]
            {:service.case (present-case c)
             :steps (fetch-steps* db0 case-id ws)}
            (error 404 "Service case not found"))))))

(defn set-step-status!
  [conn params actor]
  (or (ensure-conn conn)
      (let [db0 (d/db conn)
            ws (workspace-id (workspace/actor-workspace actor))
            {case-id :value case-err :error} (normalize-uuid (param-value params :service.case/id) "service case id")
            step-id (param-value params :service.case.step/step)
            status (param-value params :service.case.step/status)
            reason (some-> (param-value params :service.case.step/reason) str str/trim)
            reason (when-not (str/blank? reason) reason)]
        (cond
          case-err (error 400 case-err)
          (nil? case-id) (error 400 "service.case/id is required")
          (not (keyword? step-id)) (error 400 "service.case.step/step must be a keyword")
          (not (keyword? status)) (error 400 "service.case.step/status must be a keyword")
          (and (contains? #{:action-required :blocked :rejected} status) (nil? reason))
          (error 400 "Reason is required for this status")
          :else
          (let [case-eid (case-eid db0 case-id ws)]
            (if-not case-eid
              (error 404 "Service case not found")
              (let [step-eid (ffirst (d/q '[:find ?s
                                            :in $ ?cid ?ws ?step
                                            :where [?c :service.case/id ?cid]
                                                   [?c :service.case/workspace ?ws]
                                                   [?s :service.case.step/case ?c]
                                                   [?s :service.case.step/workspace ?ws]
                                                   [?s :service.case.step/step ?step]]
                                          db0 case-id ws step-id))]
                (if-not step-eid
                  (error 404 "Service case step not found" {:step step-id})
                  (let [now (now-inst)
                        tx-prov (prov/provenance actor)
                        existing-reason (:service.case.step/reason (d/pull db0 [:service.case.step/reason] step-eid))
                        tx-data (cond-> [[:db/add step-eid :service.case.step/status status]
                                         [:db/add step-eid :service.case.step/updated-at now]
                                         [:db/add case-eid :service.case/updated-at now]]
                                  (contains? #{:action-required :blocked :rejected} status)
                                  (conj [:db/add step-eid :service.case.step/reason reason])
                                  (and (not (contains? #{:action-required :blocked :rejected} status))
                                       (some? existing-reason))
                                  (conj [:db/retract step-eid :service.case.step/reason existing-reason]))
                        _ (try
                            (db/transact! conn {:tx-data tx-data})
                            (catch Exception e
                              (log/error e "Failed to update service case step")
                              nil))
                        db1 (d/db conn)
                        steps (fetch-steps* db1 case-id ws)
                        lifecycle (derive-lifecycle steps)
                        derived-phase (derive-public-phase steps)
                        existing-max (:service.case/public-max-phase (fetch-case* db1 case-id ws))
                        new-max (max-phase (or existing-max :public.phase/onboarding) derived-phase)
                        existing-life-reason (:service.case/lifecycle-reason (fetch-case* db1 case-id ws))
                        updates (cond-> [[:db/add [:service.case/id case-id] :service.case/lifecycle lifecycle]
                                         [:db/add [:service.case/id case-id] :service.case/public-current-phase derived-phase]
                                         [:db/add [:service.case/id case-id] :service.case/public-max-phase new-max]]
                                  (contains? #{:s5/action-required :s8/blocked :s7/rejected} lifecycle)
                                  (conj [:db/add [:service.case/id case-id] :service.case/lifecycle-reason (or reason "Action required")])
                                  (and (not (contains? #{:s5/action-required :s8/blocked :s7/rejected} lifecycle))
                                       (some? existing-life-reason))
                                  (conj [:db/retract [:service.case/id case-id] :service.case/lifecycle-reason existing-life-reason]))]
                    (try
                      (db/transact! conn {:tx-data (remove nil? updates)})
                      (catch Exception e
                        (log/warn e "Failed to update derived case fields")))
                    (let [db2 (d/db conn)]
                      {:service.case (present-case (fetch-case* db2 case-id ws))
                       :steps (fetch-steps* db2 case-id ws)}))))))))))))

(def ^:private token-alphabet "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")

(defn- random-token
  []
  (let [r (SecureRandom.)
        out (StringBuilder.)]
    (dotimes [_ 32]
      (.append out ^String (str (nth token-alphabet (.nextInt r (count token-alphabet))))))
    (.toString out)))

(defn ensure-client-portal-token!
  [conn client-id workspace]
  (or (ensure-conn conn)
      (let [db0 (d/db conn)
            ws (workspace-id workspace)
            {cid :value err :error} (resolve-client-id db0 client-id)]
        (cond
          err {:error (error 400 err)}
          (nil? cid) {:error (error 400 "client/id is required")}
          :else
          (let [eid (ffirst (d/q '[:find ?e
                                   :in $ ?id ?ws
                                   :where [?e :client/id ?id]
                                          [?e :client/workspace ?ws]]
                                 db0 cid ws))
                client (when eid (d/pull db0 [:client/id :client/portal-token :entity/ref] eid))]
            (cond
              (nil? eid) {:error (error 404 "Client not found")}
              (not (str/blank? (str (:client/portal-token client))))
              {:client/id cid
               :client/ref (:entity/ref client)
               :portal/token (:client/portal-token client)}
              :else
              (let [token (random-token)
                    now (now-inst)]
                (try
                  (db/transact! conn {:tx-data [[:db/add eid :client/portal-token token]
                                                [:db/add eid :client/portal-token-created-at now]]})
                  {:client/id cid
                   :client/ref (:entity/ref client)
                   :portal/token token}
                  (catch Exception e
                    (log/warn e "Failed to issue client portal token")
                    {:error (error 500 "Failed to issue portal token")})))))))))

(defn portal-link
  [state params actor]
  (let [conn (get-in state [:db :conn])
        ws (workspace/actor-workspace actor)
        base (some-> (or (get-in state [:config :portal :base-url])
                         (get-in state [:config :documents :verify-base-url])
                         "https://www.darelwasl.com")
                     str
                     (str/replace #"/+$" ""))
        client-id (or (param-value params :client/id) (param-value params "client/id"))
        res (ensure-client-portal-token! conn client-id ws)]
    (if-let [err (:error res)]
      err
      {:portal/url (str base "/portal/" (:client/ref res) "/" (:portal/token res))
       :client/ref (:client/ref res)})))

(defn public-portal-read
  "Used by the public site. Verifies a client ref + token pair and returns
  {:client ... :service.cases ...} or {:error ...}."
  [conn {:keys [client-ref token]}]
  (or (ensure-conn conn)
      (let [db0 (d/db conn)
            token (some-> token str str/trim)
            client-ref (some-> client-ref str str/trim)]
        (cond
          (str/blank? client-ref) (error 400 "Missing client ref")
          (str/blank? token) (error 400 "Missing token")
          :else
          (let [client-id (entity/lookup-id-by-ref db0 :client/id client-ref)
                client (when client-id
                         (ffirst (d/q '[:find (pull ?c [:client/id :client/name :entity/ref :client/portal-token :client/workspace])
                                        :in $ ?id
                                        :where [?c :client/id ?id]]
                                      db0 client-id)))
                ok? (= token (:client/portal-token client))
                ws (:client/workspace client)]
            (if-not (and client ok?)
              (error 404 "Portal not found")
              (let [cases (d/q '[:find ?e ?created
                                 :in $ ?cid ?ws
                                 :where [?c :client/id ?cid]
                                        [?c :client/workspace ?ws]
                                        [?e :service.case/client ?c]
                                        [?e :service.case/workspace ?ws]
                                        [?e :service.case/created-at ?created]]
                               db0 client-id ws)
                    eids (->> cases (sort-by second) reverse (map first) vec)
                    items0 (->> eids
                                (map #(d/pull db0 case-pull %))
                                (map present-case)
                                vec)
                    case-ids (map :service.case/id items0)
                    next-actions (into {}
                                       (for [cid case-ids]
                                         (let [actions (d/q '[:find ?action
                                                             :in $ ?cid ?ws
                                                             :where [?c :service.case/id ?cid]
                                                                    [?c :service.case/workspace ?ws]
                                                                    [?s :service.case.step/case ?c]
                                                                    [?s :service.case.step/workspace ?ws]
                                                                    [?s :service.case.step/owner :client]
                                                                    [?s :service.case.step/status :waiting-client]
                                                                    [?s :service.case.step/public-next-action ?action]]
                                                           db0 cid ws)]
                                           [cid (->> actions
                                                     (map first)
                                                     (map str)
                                                     (map str/trim)
                                                     (remove str/blank?)
                                                     distinct
                                                     vec)])))
                    items (mapv (fn [c]
                                  (assoc c :public/next-actions (get next-actions (:service.case/id c) [])))
                                items0)]
                {:client (select-keys client [:client/id :client/name :entity/ref])
                 :service.cases items}))))))))
