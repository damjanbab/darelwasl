(ns darelwasl.clients
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.db :as db]
            [darelwasl.entity :as entity]
            [darelwasl.provenance :as prov]
            [darelwasl.schema :as schema]
            [darelwasl.validation :as v]
            [darelwasl.workspace :as workspace])
  (:import (java.time Instant)
           (java.time.format DateTimeFormatter)
           (java.util Date UUID)))

(def ^:private iso-formatter DateTimeFormatter/ISO_INSTANT)

(def ^:private client-schema-entry
  (delay (some #(when (= (:id %) :cap/schema/client) %) (schema/read-registry))))

(def ^:private allowed-statuses
  (delay (or (some-> @client-schema-entry (get-in [:enums :client/status]) set)
             #{:lead :active :waiting :closed})))

(def ^:private allowed-channels
  (delay (or (some-> @client-schema-entry (get-in [:enums :client/channel]) set)
             #{:whatsapp :phone :email})))

(def ^:private default-list-limit 50)
(def ^:private max-list-limit 500)

(def ^:private param-value v/param-value)
(def ^:private normalize-string v/normalize-string)
(def ^:private normalize-enum v/normalize-enum)
(def ^:private normalize-uuid v/normalize-uuid)
(def ^:private normalize-long v/normalize-long)

(def default-client-id
  (UUID/fromString "20000000-0000-0000-0000-000000000001"))

(def default-client-name "Inbox")

(def ^:private client-pull-pattern
  [:client/id
   :entity/ref
   :entity/type
   :client/name
   :client/phone
   :client/email
   :client/channel
   :client/status
   :client/notes
   :client/workspace
   :person/roles])

(def ^:private client-task-pull-pattern
  [:task/id
   :task/title
   :task/description
   :task/status
   :task/priority
   :task/due-date
   :task/pending-reason
   :task/archived?
   :fact/created-at
   {:task/assignee [:user/id :user/username :user/name]}])

(defn- error
  [status message & [details]]
  {:error {:status status
           :message message
           :details details}})

(defn- ensure-conn
  [conn]
  (when-not conn
    (error 500 "Database not ready")))

(defn- workspace-id
  [value]
  (workspace/resolve-id value))

(defn- format-inst
  [^Date inst]
  (when inst
    (.format iso-formatter (.toInstant inst))))

(defn- updated-at
  [db eid]
  (-> (d/q '[:find (max ?inst)
             :in $ ?e
             :where [?e _ _ ?tx]
                    [?tx :db/txInstant ?inst]]
           db eid)
      ffirst))

(defn- present-task
  [task]
  (when (map? task)
    (-> task
        (update :task/due-date format-inst)
        (update :task/updated-at format-inst)
        (update :fact/created-at format-inst))))

(defn- present-client
  [client]
  (when (map? client)
    client))

(defn- resolve-client-id
  [db client-id]
  (entity/resolve-id db :client/id client-id "client id"))

(defn- client-eid
  [db client-id workspace]
  (when client-id
    (let [{cid :value} (resolve-client-id db client-id)]
      (when cid
        (ffirst (d/q '[:find ?e
                       :in $ ?id ?workspace
                       :where [?e :client/id ?id]
                              [?e :client/workspace ?workspace]]
                     db cid workspace))))))

(defn- client-eids
  [db workspace]
  (map first
       (d/q '[:find ?e
              :in $ ?workspace
              :where [?e :client/id _]
                     [?e :client/workspace ?workspace]]
            db workspace)))

(defn- pull-client
  [db eid]
  (d/pull db client-pull-pattern eid))

(defn client-by-id
  "Return the client map for a UUID or entity ref string, or nil when missing."
  [db client-id workspace]
  (let [workspace (workspace-id workspace)]
    (when-let [eid (client-eid db client-id workspace)]
      (pull-client db eid))))

(defn- normalize-optional-string
  [value label]
  (let [{:keys [value error]} (normalize-string value label {:required false :allow-blank? true})]
    (cond
      error {:error error}
      (str/blank? (str value)) {:value nil}
      :else {:value value})))

(defn- normalize-list-params
  [params]
  (let [{status :value status-err :error} (normalize-enum (param-value params :status) @allowed-statuses "status")
        {limit :value limit-err :error} (normalize-long (param-value params :limit) "limit")
        {offset :value offset-err :error} (normalize-long (param-value params :offset) "offset")
        limit (or limit default-list-limit)
        offset (or offset 0)]
    (cond
      status-err (error 400 status-err)
      limit-err (error 400 limit-err)
      offset-err (error 400 offset-err)
      (or (nil? limit) (<= limit 0)) (error 400 (str "Invalid limit; must be between 1 and " max-list-limit))
      (> limit max-list-limit) (error 400 (str "Limit too high; max " max-list-limit))
      (or (nil? offset) (neg? offset)) (error 400 "Invalid offset; must be 0 or greater")
      :else {:filters {:status status}
             :limit limit
             :offset offset})))

(defn- task-eids-for-client
  [db client-eid workspace]
  (map first
       (d/q '[:find ?t
              :in $ ?client ?workspace
             :where [?t :task/client ?client]
                     [?t :fact/workspace ?workspace]]
            db client-eid workspace)))

(defn- pull-client-task
  [db eid]
  (when-let [task (d/pull db client-task-pull-pattern eid)]
    (assoc task :task/updated-at (updated-at db eid))))

(defn- next-action-task
  [db client-eid workspace]
  (let [tasks (->> (task-eids-for-client db client-eid workspace)
                   (map #(pull-client-task db %))
                   (remove nil?)
                   (remove :task/archived?)
                   (remove #(= :done (:task/status %))))]
    (when (seq tasks)
      (let [sorted (sort-by (fn [task]
                              [(or (:task/due-date task) (Date. Long/MAX_VALUE))
                               (or (:task/updated-at task) (:fact/created-at task))])
                            tasks)
            task (first sorted)]
        (present-task task)))))

(defn list-clients
  "List clients with optional status filter."
  ([conn params] (list-clients conn params nil))
  ([conn params workspace]
   (or (ensure-conn conn)
       (let [{:keys [filters error limit offset]} (normalize-list-params params)]
         (if error
           {:error error}
           (let [db (d/db conn)
                 workspace (workspace-id workspace)
                 status-filter (:status filters)
                 eids (->> (client-eids db workspace)
                           (map #(pull-client db %))
                           (remove nil?)
                           (filter (fn [client]
                                     (or (nil? status-filter)
                                         (= status-filter (:client/status client))))))
                 sorted (sort-by (comp str/lower-case str :client/name) eids)
                 total (count sorted)
                 bounded-offset (min offset (max 0 (- total limit)))
                 paged (->> sorted
                            (drop bounded-offset)
                            (take limit)
                            (map (fn [client]
                                   (let [eid (client-eid db (:client/id client) workspace)
                                         next-task (when eid (next-action-task db eid workspace))]
                                     (cond-> (present-client client)
                                       next-task (assoc :client/next-task next-task)))))
                            vec)]
             {:clients paged
              :pagination {:total total
                           :limit limit
                           :offset bounded-offset
                           :page (inc (quot bounded-offset limit))
                           :returned (count paged)}}))))))

(defn fetch-client
  "Fetch a single client by UUID or :entity/ref, plus task history."
  ([conn client-id] (fetch-client conn client-id nil))
  ([conn client-id workspace]
   (or (ensure-conn conn)
       (let [db (d/db conn)
             workspace (workspace-id workspace)
             {cid :value id-err :error} (resolve-client-id db client-id)]
         (cond
           id-err (error 400 id-err)
           (nil? cid) (error 400 "Client id is required")
           :else
           (if-let [eid (client-eid db cid workspace)]
             (let [client (pull-client db eid)
                   tasks (->> (task-eids-for-client db eid workspace)
                              (map #(pull-client-task db %))
                              (remove nil?)
                              (map present-task)
                              (sort-by :task/updated-at #(compare %2 %1))
                              vec)
                   next-task (next-action-task db eid workspace)]
               {:client (cond-> (present-client client)
                          next-task (assoc :client/next-task next-task))
                :tasks tasks})
             (error 404 "Client not found")))))))

(defn ensure-default-client!
  "Ensure the Inbox client exists for the workspace."
  [conn workspace]
  (let [db (d/db conn)
        workspace (workspace-id workspace)]
    (if-let [eid (client-eid db default-client-id workspace)]
      (pull-client db eid)
      (let [base {:client/id default-client-id
                  :entity/type :entity.type/person
                  :client/name default-client-name
                  :client/status :active
                  :client/workspace workspace
                  :person/roles #{:role/client}}
            base (entity/with-ref db base)
            tx-data [(prov/enrich-tx base (prov/provenance {:actor/type :actor.type/system
                                                            :actor/workspace workspace}
                                                           :adapter/system))]]
        (try
          (db/transact! conn {:tx-data tx-data})
          (pull-client (d/db conn) (client-eid (d/db conn) default-client-id workspace))
          (catch Exception e
            (log/error e "Failed to create default client")
            nil))))))

(defn create-client!
  [conn params actor]
  (or (ensure-conn conn)
      (let [workspace (workspace/actor-workspace actor)
            {name :value name-err :error} (normalize-string (param-value params :client/name)
                                                            "Name"
                                                            {:required true
                                                             :allow-blank? false})
            {phone :value phone-err :error} (normalize-optional-string (param-value params :client/phone) "Phone")
            {email :value email-err :error} (normalize-optional-string (param-value params :client/email) "Email")
            {notes :value notes-err :error} (normalize-optional-string (param-value params :client/notes) "Notes")
            {channel :value channel-err :error} (normalize-enum (param-value params :client/channel) @allowed-channels "channel")
            {status :value status-err :error} (normalize-enum (param-value params :client/status) @allowed-statuses "status")
            status (or status :lead)]
        (cond
          name-err (error 400 name-err)
          phone-err (error 400 phone-err)
          email-err (error 400 email-err)
          notes-err (error 400 notes-err)
          channel-err (error 400 channel-err)
          status-err (error 400 status-err)
          :else
          (let [db (d/db conn)
                base {:client/id (UUID/randomUUID)
                      :entity/type :entity.type/person
                      :client/name name
                      :client/status status
                      :client/workspace (workspace-id workspace)
                      :person/roles #{:role/client}}
                base (cond-> base
                       phone (assoc :client/phone phone)
                       email (assoc :client/email email)
                       channel (assoc :client/channel channel)
                       notes (assoc :client/notes notes))
                base (entity/with-ref db base)
                tx-prov (prov/provenance actor)
                tx-data [(prov/enrich-tx base tx-prov)]]
            (try
              (db/transact! conn {:tx-data tx-data})
              (let [db-after (d/db conn)
                    eid (client-eid db-after (:client/id base) (workspace-id workspace))
                    client (when eid (pull-client db-after eid))]
                (if client
                  (do
                    (log/infof "AUDIT client-create user=%s client=%s"
                               (or (:user/username actor) (:user/id actor) "system")
                               (:client/id base))
                    {:client (present-client client)})
                  (error 500 "Client not available after create")))
              (catch Exception e
                (log/error e "Failed to create client")
                (error 500 "Unable to create client" {:exception (.getMessage e)}))))))))

(defn update-client!
  [conn client-id params actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            workspace (workspace/actor-workspace actor)
            {cid :value id-err :error} (resolve-client-id db client-id)
            {name :value name-err :error} (normalize-string (param-value params :client/name)
                                                            "Name"
                                                            {:required false
                                                             :allow-blank? false})
            {phone :value phone-err :error} (normalize-optional-string (param-value params :client/phone) "Phone")
            {email :value email-err :error} (normalize-optional-string (param-value params :client/email) "Email")
            {notes :value notes-err :error} (normalize-optional-string (param-value params :client/notes) "Notes")
            {channel :value channel-err :error} (normalize-enum (param-value params :client/channel) @allowed-channels "channel")
            {status :value status-err :error} (normalize-enum (param-value params :client/status) @allowed-statuses "status")]
        (cond
          id-err (error 400 id-err)
          name-err (error 400 name-err)
          phone-err (error 400 phone-err)
          email-err (error 400 email-err)
          notes-err (error 400 notes-err)
          channel-err (error 400 channel-err)
          status-err (error 400 status-err)
          :else
          (if-let [eid (client-eid db cid (workspace-id workspace))]
            (let [current (pull-client db eid)
                  updates (cond-> []
                            name (conj [:db/add [:client/id cid] :client/name name])
                            (some? phone) (conj [:db/add [:client/id cid] :client/phone phone])
                            (some? email) (conj [:db/add [:client/id cid] :client/email email])
                            (some? notes) (conj [:db/add [:client/id cid] :client/notes notes])
                            (some? channel) (conj [:db/add [:client/id cid] :client/channel channel])
                            (some? status) (conj [:db/add [:client/id cid] :client/status status]))
                  roles (set (or (:person/roles current) #{}))
                  updates (if (contains? roles :role/client)
                            updates
                            (conj (vec updates) [:db/add [:client/id cid] :person/roles :role/client]))]
              (if (empty? updates)
                (error 400 "No updates provided")
                (try
                  (db/transact! conn {:tx-data updates})
                  (let [db-after (d/db conn)
                        client (pull-client db-after eid)]
                    (when actor
                      (log/infof "AUDIT client-update user=%s client=%s"
                                 (or (:user/username actor) (:user/id actor) "system")
                                 cid))
                    {:client (present-client client)})
                  (catch Exception e
                    (log/error e "Failed to update client")
                    (error 500 "Unable to update client" {:exception (.getMessage e)})))))
            (error 404 "Client not found"))))))
