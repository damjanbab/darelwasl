;; Agreements + structured payment plans.
(ns darelwasl.agreements
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.db :as db]
            [darelwasl.entity :as entity]
            [darelwasl.provenance :as prov]
            [darelwasl.validation :as v]
            [darelwasl.workspace :as workspace])
  (:import (java.time Instant)
           (java.time.format DateTimeFormatter)
           (java.util Date UUID)))

(def ^:private iso-formatter DateTimeFormatter/ISO_INSTANT)
(def ^:private param-value v/param-value)
(def ^:private normalize-uuid v/normalize-uuid)
(def ^:private normalize-long v/normalize-long)
(def ^:private normalize-enum v/normalize-enum)

(defn- normalize-enum-set
  [value allowed label]
  (let [raw (cond
              (nil? value) nil
              (set? value) (seq value)
              (sequential? value) (seq value)
              :else [value])]
    (if (nil? raw)
      {:value nil}
      (loop [items raw
             acc #{}]
        (if (empty? items)
          {:value acc}
          (let [{kw :value err :error} (normalize-enum (first items) allowed label)]
            (if err
              {:error err}
              (recur (rest items) (conj acc kw)))))))))

(defn- error
  [status message & [details]]
  {:error {:status status
           :message message
           :details details}})

(defn- ensure-conn
  [conn]
  (when-not conn
    (error 500 "Database not ready")))

(defn- present-str?
  [v]
  (and (string? v) (not (str/blank? v))))

(defn- normalize-text
  [v]
  (let [s (some-> v str str/trim)]
    (when-not (str/blank? s) s)))

(defn- normalize-money
  [v]
  (cond
    (number? v) (double v)
    (string? v) (let [s (str/trim v)
                      s (str/replace s #"[^0-9.+-]" "")]
                  (when-not (str/blank? s)
                    (try
                      (double (Double/parseDouble s))
                      (catch Exception _ nil))))
    :else nil))

(defn- format-inst
  [^Date inst]
  (when inst
    (.format iso-formatter (.toInstant inst))))

(defn- now-inst
  []
  (Date/from (Instant/now)))

(defn- parse-inst
  "Parse an instant-like value into a java.util.Date.
  Accepts:
  - java.util.Date
  - java.time.Instant
  - ISO-8601 string (Instant/parse)
  - date-only string (YYYY-MM-DD), treated as start-of-day UTC."
  [v]
  (cond
    (nil? v) nil
    (instance? Date v) v
    (instance? Instant v) (Date/from ^Instant v)
    (string? v) (let [s (str/trim v)]
                  (cond
                    (str/blank? s) nil
                    (re-matches #"\d{4}-\d{2}-\d{2}" s)
                    (try
                      (let [d (java.time.LocalDate/parse s)
                            i (.toInstant (.atStartOfDay d (java.time.ZoneOffset/UTC)))]
                        (Date/from i))
                      (catch Exception _ nil))
                    :else
                    (try
                      (Date/from (Instant/parse s))
                      (catch Exception _ nil))))
    :else nil))

(defn- normalize-inst
  [v label {:keys [required default]}]
  (let [raw (if (sequential? v) (first v) v)
        raw (if (and (nil? raw) (some? default)) default raw)]
    (cond
      (and required (nil? raw)) {:error (str label " is required")}
      (nil? raw) {:value nil}
      :else
      (if-let [d (parse-inst raw)]
        {:value d}
        {:error (str "Invalid " label)}))))

(def ^:private allowed-agreement-status
  #{:draft :proposed :accepted :void})

(def ^:private allowed-delivery-channels
  #{:email :whatsapp :telegram :paper})

(def ^:private plan-item-kind
  #{:installment :milestone :recurring})

(def ^:private plan-item-invoice-on
  #{:accepted :due :manual})

(def ^:private plan-item-invoice-status
  #{:draft :sent})

(def ^:private agreement-pull
  [:agreement/id
   :agreement/key
   :agreement/workspace
   :agreement/number
   :agreement/title
   :agreement/terms
   :agreement/status
   :agreement/proposed-at
   :agreement/proposed-by
   :agreement/effective-at
   :agreement/accepted-at
   :agreement/accepted-by
   :agreement/client-company
   :agreement/client-representative
   :agreement/our-representative
   :agreement/our-recipient
   :agreement/delivery-channels
   :agreement/delivery-email
   :agreement/delivery-phone
   :agreement/delivery-telegram-chat-id
   :agreement/electronic-consent?
   :agreement/consented-at
   :entity/ref
   {:agreement/client [:client/id :client/name :client/phone :client/email :entity/ref]}])

(def ^:private plan-item-pull
  [:plan.item/id
   :plan.item/workspace
   :plan.item/kind
   :plan.item/label
   :plan.item/amount
   :plan.item/currency
   :plan.item/due-at
   :plan.item/invoice-on
   :plan.item/invoice-status
   :plan.item/index
   :plan.item/active?
   :plan.item/recurrence
   :entity/ref
   {:plan.item/agreement [:agreement/id :entity/ref]}])

(declare resolve-agreement-id
         resolve-plan-item-id)

(defn pull-agreement
  "Return raw agreement pull map (no presentation), or nil."
  [db agreement-id actor]
  (let [ws (workspace/actor-workspace actor)
        {:keys [value error]} (resolve-agreement-id db agreement-id)]
    (when (and value (nil? error))
      (when-let [eid (ffirst (d/q '[:find ?e
                                   :in $ ?id ?ws
                                   :where [?e :agreement/id ?id]
                                          [?e :agreement/workspace ?ws]]
                                 db value ws))]
        (d/pull db agreement-pull eid)))))

(defn plan-items-for-agreement
  "Return raw plan item pull maps (no presentation), sorted by index then due date."
  [db agreement-id actor]
  (let [ws (workspace/actor-workspace actor)
        {:keys [value error]} (resolve-agreement-id db agreement-id)]
    (when (and value (nil? error))
      (when-let [agreement-eid (ffirst (d/q '[:find ?e
                                             :in $ ?id ?ws
                                             :where [?e :agreement/id ?id]
                                                    [?e :agreement/workspace ?ws]]
                                           db value ws))]
        (->> (d/q '[:find ?e ?idx ?due
                    :in $ ?a ?ws
                    :where [?e :plan.item/agreement ?a]
                           [?e :plan.item/workspace ?ws]
                           [(get-else $ ?e :plan.item/active? true) ?active]
                           [(get-else $ ?e :plan.item/index 999999) ?idx]
                           [?e :plan.item/due-at ?due]
                           [(= true ?active)]]
                  db agreement-eid ws)
             (sort-by (fn [[_ idx due]] [(long idx) due]))
             (map first)
             (map #(d/pull db plan-item-pull %))
             (remove nil?)
             vec)))))

(defn- present-agreement
  [a]
  (when (map? a)
    (-> a
        (update :agreement/proposed-at format-inst)
        (update :agreement/effective-at format-inst)
        (update :agreement/accepted-at format-inst)
        (update :agreement/consented-at format-inst)
        (select-keys [:agreement/id
                      :agreement/number
                      :agreement/title
                      :agreement/terms
                      :agreement/status
                      :agreement/proposed-at
                      :agreement/proposed-by
                      :agreement/effective-at
                      :agreement/accepted-at
                      :agreement/accepted-by
                      :agreement/client-company
                      :agreement/client-representative
                      :agreement/our-representative
                      :agreement/our-recipient
                      :agreement/delivery-channels
                      :agreement/delivery-email
                      :agreement/delivery-phone
                      :agreement/delivery-telegram-chat-id
                      :agreement/electronic-consent?
                      :agreement/consented-at
                      :agreement/client
                      :entity/ref]))))

(defn- present-plan-item
  [it]
  (when (map? it)
    (-> it
        (update :plan.item/due-at format-inst)
        (select-keys [:plan.item/id
                      :plan.item/kind
                      :plan.item/label
                      :plan.item/amount
                      :plan.item/currency
                      :plan.item/due-at
                      :plan.item/invoice-on
                      :plan.item/invoice-status
                      :plan.item/index
                      :plan.item/active?
                      :plan.item/recurrence
                      :entity/ref]))))

(defn- resolve-agreement-id
  [db agreement-id]
  (entity/resolve-id db :agreement/id agreement-id "agreement id"))

(defn- resolve-plan-item-id
  [db plan-item-id]
  (entity/resolve-id db :plan.item/id plan-item-id "plan item id"))

(defn- agreement-eid
  [db agreement-id ws]
  (let [{aid :value err :error} (resolve-agreement-id db agreement-id)]
    (cond
      err {:error (error 400 err)}
      (nil? aid) {:error (error 400 "agreement/id is required")}
      :else
      {:eid (ffirst (d/q '[:find ?e
                           :in $ ?id ?ws
                           :where [?e :agreement/id ?id]
                                  [?e :agreement/workspace ?ws]]
                         db aid ws))
       :id aid})))

(defn create-agreement!
  [conn input actor]
  (or (ensure-conn conn)
	    (let [body (or input {})
	          ws (workspace/actor-workspace actor)
	          db (d/db conn)
	          {client-id :value c-err :error} (normalize-uuid (param-value body :client/id) "client id")
	          number (normalize-text (param-value body :agreement/number))
	          title (normalize-text (param-value body :agreement/title))
	          terms (normalize-text (param-value body :agreement/terms))
            client-company (normalize-text (param-value body :agreement/client-company))
            client-rep (normalize-text (param-value body :agreement/client-representative))
            our-rep (normalize-text (param-value body :agreement/our-representative))
            our-recipient (normalize-text (param-value body :agreement/our-recipient))
	          {effective-at :value effective-at-err :error} (normalize-inst (param-value body :agreement/effective-at)
	                                                                        "agreement/effective-at"
	                                                                        {:required false})
	          {channels :value chan-err :error} (normalize-enum-set (param-value body :agreement/delivery-channels)
	                                                               allowed-delivery-channels
	                                                               "delivery channels")
	          delivery-email (normalize-text (param-value body :agreement/delivery-email))
	          delivery-phone (normalize-text (param-value body :agreement/delivery-phone))
	          delivery-chat (normalize-text (param-value body :agreement/delivery-telegram-chat-id))
	          electronic-consent? (param-value body :agreement/electronic-consent?)
	          {consented-at :value consented-at-err :error} (normalize-inst (param-value body :agreement/consented-at)
	                                                                        "agreement/consented-at"
	                                                                        {:required false})]
	      (cond
	        c-err (error 400 c-err)
	        (nil? client-id) (error 400 "client/id is required")
	        (not (present-str? title)) (error 400 "agreement/title is required")
	        (not (present-str? terms)) (error 400 "agreement/terms is required")
	        effective-at-err (error 400 effective-at-err)
	        chan-err (error 400 chan-err)
	        consented-at-err (error 400 consented-at-err)
	        :else
          (let [client-eid (ffirst (d/q '[:find ?e
                                          :in $ ?id ?ws
                                          :where [?e :client/id ?id]
                                                 [?e :client/workspace ?ws]]
                                        db client-id ws))]
            (if-not client-eid
              (error 404 "Client not found")
              (let [agreement-id (UUID/randomUUID)
                    key (str ws "::" (str client-id) "::" (or number (subs (str agreement-id) 0 8)))
                    base {:agreement/id agreement-id
                          :agreement/key key
                          :entity/type :entity.type/agreement
                          :agreement/client [:client/id client-id]
                          :agreement/workspace ws
                          :agreement/status :draft
                          :agreement/title title
                          :agreement/terms terms}
                    base (cond-> base
                           number (assoc :agreement/number number)
                           effective-at (assoc :agreement/effective-at effective-at)
                           client-company (assoc :agreement/client-company client-company)
                           client-rep (assoc :agreement/client-representative client-rep)
                           our-rep (assoc :agreement/our-representative our-rep)
                           our-recipient (assoc :agreement/our-recipient our-recipient)
                           (seq channels) (assoc :agreement/delivery-channels (set channels))
                           delivery-email (assoc :agreement/delivery-email delivery-email)
                           delivery-phone (assoc :agreement/delivery-phone delivery-phone)
                           delivery-chat (assoc :agreement/delivery-telegram-chat-id delivery-chat)
                           (some? electronic-consent?) (assoc :agreement/electronic-consent? (boolean electronic-consent?))
                           consented-at (assoc :agreement/consented-at consented-at))
                    base (entity/with-ref db base)
                    tx-prov (prov/provenance actor)
                    tx (prov/enrich-tx base tx-prov)]
                (try
                  (let [tx-res (db/transact! conn {:tx-data [tx]})
                        db-after (:db-after tx-res)
                        eid (ffirst (d/q '[:find ?e
                                           :in $ ?id ?ws
                                           :where [?e :agreement/id ?id]
                                                  [?e :agreement/workspace ?ws]]
                                         db-after agreement-id ws))
                        pulled (when eid (d/pull db-after agreement-pull eid))]
                    {:agreement (present-agreement pulled)})
                  (catch Exception e
                    (log/error e "Failed to create agreement")
                    (error 500 "Failed to create agreement"))))))))))

(defn update-agreement!
  [conn agreement-id input actor]
  (or (ensure-conn conn)
	    (let [body (or input {})
	          ws (workspace/actor-workspace actor)
	          db (d/db conn)
	          {:keys [eid id error]} (agreement-eid db agreement-id ws)
	          number (normalize-text (param-value body :agreement/number))
	          title (normalize-text (param-value body :agreement/title))
	          terms (normalize-text (param-value body :agreement/terms))
            client-company (normalize-text (param-value body :agreement/client-company))
            client-rep (normalize-text (param-value body :agreement/client-representative))
            our-rep (normalize-text (param-value body :agreement/our-representative))
            our-recipient (normalize-text (param-value body :agreement/our-recipient))
	          {effective-at :value effective-at-err :error} (normalize-inst (param-value body :agreement/effective-at)
	                                                                        "agreement/effective-at"
	                                                                        {:required false})
	          {status :value status-err :error} (normalize-enum (param-value body :agreement/status) allowed-agreement-status "agreement status")
            {channels :value chan-err :error} (normalize-enum-set (param-value body :agreement/delivery-channels)
                                                                 allowed-delivery-channels
                                                                 "delivery channels")
            delivery-email (normalize-text (param-value body :agreement/delivery-email))
	          delivery-phone (normalize-text (param-value body :agreement/delivery-phone))
	          delivery-chat (normalize-text (param-value body :agreement/delivery-telegram-chat-id))
	          electronic-consent? (param-value body :agreement/electronic-consent?)
	          {consented-at :value consented-at-err :error} (normalize-inst (param-value body :agreement/consented-at)
	                                                                        "agreement/consented-at"
	                                                                        {:required false})]
	      (cond
	        error error
	        (nil? eid) (error 404 "Agreement not found")
	        status-err (error 400 status-err)
	        effective-at-err (error 400 effective-at-err)
	        chan-err (error 400 chan-err)
	        consented-at-err (error 400 consented-at-err)
	        :else
          (let [tx-prov (prov/provenance actor)
                tx-data (->> [[:db/add eid :agreement/number number]
                              [:db/add eid :agreement/title title]
                              [:db/add eid :agreement/terms terms]
                              [:db/add eid :agreement/effective-at effective-at]
                              [:db/add eid :agreement/status status]
                              [:db/add eid :agreement/client-company client-company]
                              [:db/add eid :agreement/client-representative client-rep]
                              [:db/add eid :agreement/our-representative our-rep]
                              [:db/add eid :agreement/our-recipient our-recipient]
                              [:db/add eid :agreement/delivery-channels (when (seq channels) (set channels))]
                              [:db/add eid :agreement/delivery-email delivery-email]
                              [:db/add eid :agreement/delivery-phone delivery-phone]
                              [:db/add eid :agreement/delivery-telegram-chat-id delivery-chat]
                              [:db/add eid :agreement/electronic-consent? (when (some? electronic-consent?) (boolean electronic-consent?))]
                              [:db/add eid :agreement/consented-at consented-at]]
                             (remove (fn [[_ _ v]] (nil? v)))
                             (map #(prov/enrich-tx % tx-prov))
                             vec)]
            (try
              (when (seq tx-data)
                (db/transact! conn {:tx-data tx-data}))
              (let [db-after (d/db conn)
                    pulled (d/pull db-after agreement-pull eid)]
                {:agreement (present-agreement pulled)})
              (catch Exception e
                (log/error e "Failed to update agreement" {:agreement/id id})
                (error 500 "Failed to update agreement"))))))))

(defn list-agreements
  [conn {:keys [client-id]} actor]
  (or (ensure-conn conn)
      (let [ws (workspace/actor-workspace actor)
            db (d/db conn)
            {cid :value c-err :error} (normalize-uuid client-id "client id")]
        (cond
          c-err (error 400 c-err)
          (nil? cid) (error 400 "client/id is required")
          :else
          (let [client-eid (ffirst (d/q '[:find ?e
                                          :in $ ?id ?ws
                                          :where [?e :client/id ?id]
                                                 [?e :client/workspace ?ws]]
                                        db cid ws))]
            (if-not client-eid
              (error 404 "Client not found")
              (let [agreements (->> (d/q '[:find ?e ?accepted
                                           :in $ ?c ?ws
                                           :where [?e :agreement/client ?c]
                                                  [?e :agreement/workspace ?ws]
                                                  [(get-else $ ?e :agreement/accepted-at nil) ?accepted]]
                                         db client-eid ws)
                                    (sort-by second #(compare %2 %1))
                                    (map first)
                                    (map #(d/pull db agreement-pull %))
                                    (remove nil?)
                                    (map present-agreement)
                                    vec)]
                {:agreements agreements})))))))

(defn create-plan-item!
  [conn input actor]
  (or (ensure-conn conn)
	    (let [body (or input {})
	          ws (workspace/actor-workspace actor)
	          db (d/db conn)
	          {agreement-id :value a-err :error} (normalize-uuid (param-value body :agreement/id) "agreement id")
	          {kind :value kind-err :error} (normalize-enum (param-value body :plan.item/kind) plan-item-kind "plan item kind")
	          label (normalize-text (param-value body :plan.item/label))
	          amount (normalize-money (param-value body :plan.item/amount))
	          currency (normalize-text (param-value body :plan.item/currency))
	          {due-at :value due-at-err :error} (normalize-inst (param-value body :plan.item/due-at)
	                                                            "plan.item/due-at"
	                                                            {:required true})
	          {invoice-on :value on-err :error} (normalize-enum (param-value body :plan.item/invoice-on) plan-item-invoice-on "invoice-on")
	          {invoice-status :value st-err :error} (normalize-enum (param-value body :plan.item/invoice-status) plan-item-invoice-status "invoice status")
	          {idx :value idx-err :error} (normalize-long (param-value body :plan.item/index) "index")
	          active? (param-value body :plan.item/active?)
	          recurrence (normalize-text (param-value body :plan.item/recurrence))]
	      (cond
	        a-err (error 400 a-err)
	        (nil? agreement-id) (error 400 "agreement/id is required")
	        kind-err (error 400 kind-err)
	        (not (present-str? label)) (error 400 "plan.item/label is required")
	        (nil? amount) (error 400 "plan.item/amount is required")
	        due-at-err (error 400 due-at-err)
	        on-err (error 400 on-err)
	        st-err (error 400 st-err)
	        idx-err (error 400 idx-err)
	        :else
          (let [agreement-eid (ffirst (d/q '[:find ?e
                                             :in $ ?id ?ws
                                             :where [?e :agreement/id ?id]
                                                    [?e :agreement/workspace ?ws]]
                                           db agreement-id ws))]
            (if-not agreement-eid
              (error 404 "Agreement not found")
              (let [plan-id (UUID/randomUUID)
                    base {:plan.item/id plan-id
                          :entity/type :entity.type/payment-plan-item
                          :plan.item/agreement [:agreement/id agreement-id]
                          :plan.item/workspace ws
                          :plan.item/kind kind
                          :plan.item/label label
                          :plan.item/amount amount
                          :plan.item/currency (or currency "SAR")
                          :plan.item/due-at due-at
                          :plan.item/invoice-on invoice-on
                          :plan.item/invoice-status (or invoice-status :sent)
                          :plan.item/active? (if (some? active?) (boolean active?) true)}
                    base (cond-> base
                           (some? idx) (assoc :plan.item/index idx)
                           recurrence (assoc :plan.item/recurrence recurrence))
                    base (entity/with-ref db base)
                    tx-prov (prov/provenance actor)
                    tx (prov/enrich-tx base tx-prov)]
                (try
                  (let [tx-res (db/transact! conn {:tx-data [tx]})
                        db-after (:db-after tx-res)
                        eid (ffirst (d/q '[:find ?e
                                           :in $ ?id ?ws
                                           :where [?e :plan.item/id ?id]
                                                  [?e :plan.item/workspace ?ws]]
                                         db-after plan-id ws))
                        pulled (when eid (d/pull db-after plan-item-pull eid))]
                    {:plan-item (present-plan-item pulled)})
                  (catch Exception e
                    (log/error e "Failed to create plan item")
                    (error 500 "Failed to create plan item"))))))))))

(defn update-plan-item!
  [conn plan-item-id input actor]
  (or (ensure-conn conn)
      (let [body (or input {})
            ws (workspace/actor-workspace actor)
            db (d/db conn)
            {:keys [value error]} (resolve-plan-item-id db plan-item-id)
            eid (when value
                  (ffirst (d/q '[:find ?e
                                 :in $ ?id ?ws
                                 :where [?e :plan.item/id ?id]
                                        [?e :plan.item/workspace ?ws]]
                               db value ws)))
            {kind :value kind-err :error} (normalize-enum (param-value body :plan.item/kind) plan-item-kind "plan item kind")
            label (normalize-text (param-value body :plan.item/label))
	          amount (let [v (param-value body :plan.item/amount)]
	                   (when-not (nil? v) (normalize-money v)))
	          currency (normalize-text (param-value body :plan.item/currency))
	          {due-at :value due-at-err :error} (normalize-inst (param-value body :plan.item/due-at)
	                                                                   "plan.item/due-at"
	                                                                   {:required false})
	          {invoice-on :value on-err :error} (normalize-enum (param-value body :plan.item/invoice-on) plan-item-invoice-on "invoice-on")
	          {invoice-status :value st-err :error} (normalize-enum (param-value body :plan.item/invoice-status) plan-item-invoice-status "invoice status")
	          {idx :value idx-err :error} (normalize-long (param-value body :plan.item/index) "index")
	          active? (param-value body :plan.item/active?)
	          recurrence (normalize-text (param-value body :plan.item/recurrence))]
	      (cond
          error (error 400 error)
          (nil? value) (error 400 "plan.item/id is required")
	        (nil? eid) (error 404 "Plan item not found")
	        kind-err (error 400 kind-err)
	        (and (contains? body :plan.item/label) (not (present-str? label))) (error 400 "plan.item/label cannot be empty")
	        (and (contains? body :plan.item/amount) (nil? amount)) (error 400 "plan.item/amount invalid")
	        due-at-err (error 400 due-at-err)
	        on-err (error 400 on-err)
	        st-err (error 400 st-err)
	        idx-err (error 400 idx-err)
	        :else
          (let [tx-prov (prov/provenance actor)
                tx-data (->> [[:db/add eid :plan.item/kind kind]
                              [:db/add eid :plan.item/label label]
                              [:db/add eid :plan.item/amount amount]
                              [:db/add eid :plan.item/currency currency]
                              [:db/add eid :plan.item/due-at due-at]
                              [:db/add eid :plan.item/invoice-on invoice-on]
                              [:db/add eid :plan.item/invoice-status invoice-status]
                              [:db/add eid :plan.item/index idx]
                              [:db/add eid :plan.item/active? (when (some? active?) (boolean active?))]
                              [:db/add eid :plan.item/recurrence recurrence]]
                             (remove (fn [[_ _ v]] (nil? v)))
                             (map #(prov/enrich-tx % tx-prov))
                             vec)]
            (try
              (when (seq tx-data)
                (db/transact! conn {:tx-data tx-data}))
              (let [db-after (d/db conn)
                    pulled (d/pull db-after plan-item-pull eid)]
                {:plan-item (present-plan-item pulled)})
              (catch Exception e
                (log/error e "Failed to update plan item")
                (error 500 "Failed to update plan item"))))))))

(defn delete-plan-item!
  [conn plan-item-id actor]
  (or (ensure-conn conn)
      (let [ws (workspace/actor-workspace actor)
            db (d/db conn)
            {:keys [value error]} (resolve-plan-item-id db plan-item-id)
            eid (when value
                  (ffirst (d/q '[:find ?e
                                 :in $ ?id ?ws
                                 :where [?e :plan.item/id ?id]
                                        [?e :plan.item/workspace ?ws]]
                               db value ws)))]
        (cond
          error (error 400 error)
          (nil? value) (error 400 "plan.item/id is required")
          (nil? eid) (error 404 "Plan item not found")
          :else
          (let [tx-prov (prov/provenance actor)
                tx (prov/enrich-tx [:db/add eid :plan.item/active? false] tx-prov)]
            (try
              (db/transact! conn {:tx-data [tx]})
              {:plan.item/id value}
              (catch Exception e
                (log/error e "Failed to delete plan item")
                (error 500 "Failed to delete plan item"))))))))

(defn list-plan-items
  [conn {:keys [agreement-id]} actor]
  (or (ensure-conn conn)
      (let [ws (workspace/actor-workspace actor)
            db (d/db conn)
            {aid :value a-err :error} (normalize-uuid agreement-id "agreement id")]
        (cond
          a-err (error 400 a-err)
          (nil? aid) (error 400 "agreement/id is required")
          :else
          (let [agreement-eid (ffirst (d/q '[:find ?e
                                             :in $ ?id ?ws
                                             :where [?e :agreement/id ?id]
                                                    [?e :agreement/workspace ?ws]]
                                           db aid ws))]
            (if-not agreement-eid
	              (error 404 "Agreement not found")
	              (let [items (->> (d/q '[:find ?e ?active ?idx ?due
	                                      :in $ ?a ?ws
	                                      :where [?e :plan.item/agreement ?a]
	                                             [?e :plan.item/workspace ?ws]
	                                             [(get-else $ ?e :plan.item/active? true) ?active]
	                                             [(get-else $ ?e :plan.item/index 999999) ?idx]
	                                             [?e :plan.item/due-at ?due]]
	                                    db agreement-eid ws)
	                               (sort-by (fn [[_ active idx due]]
	                                          [(if (= true active) 0 1) (long idx) due]))
	                               (map first)
	                               (map #(d/pull db plan-item-pull %))
	                               (remove nil?)
	                               (map present-plan-item)
	                               vec)]
	                {:plan-items items})))))))

(defn accept-agreement!
  "Mark agreement accepted; does not generate invoices here (actions layer orchestrates)."
  [conn agreement-id input actor]
  (or (ensure-conn conn)
	    (let [body (or input {})
	          ws (workspace/actor-workspace actor)
	          db (d/db conn)
	          {:keys [eid id error]} (agreement-eid db agreement-id ws)
            current-status (when eid (:agreement/status (d/pull db [:agreement/status] eid)))
	          {accepted-at :value accepted-at-err :error} (normalize-inst (param-value body :agreement/accepted-at)
	                                                                      "agreement/accepted-at"
	                                                                      {:required false
	                                                                       :default (now-inst)})
	          accepted-by (normalize-text (param-value body :agreement/accepted-by))
	          {channels :value chan-err :error} (normalize-enum-set (param-value body :agreement/delivery-channels)
	                                                               allowed-delivery-channels
	                                                               "delivery channels")
            delivery-email (normalize-text (param-value body :agreement/delivery-email))
	          delivery-phone (normalize-text (param-value body :agreement/delivery-phone))
	          delivery-chat (normalize-text (param-value body :agreement/delivery-telegram-chat-id))
	          electronic-consent? (param-value body :agreement/electronic-consent?)
	          {consented-at :value consented-at-err :error} (normalize-inst (param-value body :agreement/consented-at)
	                                                                        "agreement/consented-at"
	                                                                        {:required false})]
	      (cond
	        error error
	        (nil? eid) (error 404 "Agreement not found")
            (not= :proposed current-status) (error 400 "Agreement must be proposed before acceptance")
	        accepted-at-err (error 400 accepted-at-err)
	        chan-err (error 400 chan-err)
	        consented-at-err (error 400 consented-at-err)
	        :else
          (let [tx-prov (prov/provenance actor)
                tx-data (->> [[:db/add eid :agreement/status :accepted]
                              [:db/add eid :agreement/accepted-at accepted-at]
                              [:db/add eid :agreement/accepted-by accepted-by]
                              [:db/add eid :agreement/delivery-channels (when (seq channels) (set channels))]
                              [:db/add eid :agreement/delivery-email delivery-email]
                              [:db/add eid :agreement/delivery-phone delivery-phone]
                              [:db/add eid :agreement/delivery-telegram-chat-id delivery-chat]
                              [:db/add eid :agreement/electronic-consent? (when (some? electronic-consent?) (boolean electronic-consent?))]
                              [:db/add eid :agreement/consented-at consented-at]]
                             (remove (fn [[_ _ v]] (nil? v)))
                             (map #(prov/enrich-tx % tx-prov))
                             vec)]
            (try
              (when (seq tx-data)
                (db/transact! conn {:tx-data tx-data}))
              (let [db-after (d/db conn)
                    pulled (d/pull db-after agreement-pull eid)]
                {:agreement (present-agreement pulled)})
              (catch Exception e
                (log/error e "Failed to accept agreement" {:agreement/id id})
                (error 500 "Failed to accept agreement"))))))))

(defn propose-agreement!
  "Mark agreement as proposed (proposal issued/sent)."
  [conn agreement-id input actor]
  (or (ensure-conn conn)
      (let [body (or input {})
            ws (workspace/actor-workspace actor)
            db (d/db conn)
            {:keys [eid id error]} (agreement-eid db agreement-id ws)
            current-status (when eid (:agreement/status (d/pull db [:agreement/status] eid)))
            {proposed-at :value proposed-at-err :error} (normalize-inst (param-value body :agreement/proposed-at)
                                                                        "agreement/proposed-at"
                                                                        {:required false
                                                                         :default (now-inst)})
            proposed-by (normalize-text (param-value body :agreement/proposed-by))]
        (cond
          error error
          (nil? eid) (error 404 "Agreement not found")
          (= :accepted current-status) (error 400 "Agreement already accepted")
          (= :void current-status) (error 400 "Agreement is void")
          proposed-at-err (error 400 proposed-at-err)
          :else
          (let [tx-prov (prov/provenance actor)
                tx-data (->> [[:db/add eid :agreement/status :proposed]
                              [:db/add eid :agreement/proposed-at proposed-at]
                              [:db/add eid :agreement/proposed-by proposed-by]]
                             (remove (fn [[_ _ v]] (nil? v)))
                             (map #(prov/enrich-tx % tx-prov))
                             vec)]
            (try
              (when (seq tx-data)
                (db/transact! conn {:tx-data tx-data}))
              (let [db-after (d/db conn)
                    pulled (d/pull db-after agreement-pull eid)]
                {:agreement (present-agreement pulled)})
              (catch Exception e
                (log/error e "Failed to propose agreement" {:agreement/id id})
                (error 500 "Failed to propose agreement"))))))))
