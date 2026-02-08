;; Client document pack (proposal/invoice/receipt/status report) data + rendering.
(ns darelwasl.documents
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.db :as db]
            [darelwasl.entity :as entity]
            [darelwasl.files :as files]
            [darelwasl.provenance :as prov]
            [darelwasl.validation :as v]
            [darelwasl.workspace :as workspace])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.security MessageDigest)
           (java.time Instant)
           (java.time.format DateTimeFormatter)
           (java.util Date UUID)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def ^:private iso-formatter DateTimeFormatter/ISO_INSTANT)
(def ^:private param-value v/param-value)
(def ^:private normalize-string v/normalize-string)
(def ^:private normalize-uuid v/normalize-uuid)
(def ^:private normalize-enum v/normalize-enum)

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
  [v label {:keys [required]}]
  (let [raw (if (sequential? v) (first v) v)]
    (cond
      (and required (nil? raw)) {:error (str label " is required")}
      (nil? raw) {:value nil}
      :else
      (if-let [d (parse-inst raw)]
        {:value d}
        {:error (str "Invalid " label)}))))

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
                           :in $ ?id ?workspace
                           :where [?e :client/id ?id]
                                  [?e :client/workspace ?workspace]]
                         db cid workspace))
       :id cid})))

(def ^:private client-pull
  [:client/id :client/name :client/phone :client/email :client/workspace :entity/ref])

(def ^:private doc-pack-pull
  [:doc.pack/id
   :doc.pack/key
   {:doc.pack/client client-pull}
   :doc.pack/workspace
   :doc.pack/company-name
   :doc.pack/company-address
   :doc.pack/company-email
   :doc.pack/company-phone
   :doc.pack/currency
   :doc.pack/services-included
   :doc.pack/payment-plan
   :doc.pack/status-notes
   :doc.pack/updated-at
   :entity/ref])

(def ^:private invoice-pull
  [:invoice/id
   :invoice/key
   {:invoice/client client-pull}
   {:invoice/agreement [:agreement/id :agreement/number :entity/ref]}
   {:invoice/plan-item [:plan.item/id :plan.item/label :entity/ref]}
   :invoice/workspace
   :invoice/number
   :invoice/title
   :invoice/description
   :invoice/issued-at
   :invoice/due-at
   :invoice/currency
   :invoice/total-amount
   :invoice/status
   :entity/ref])

(def ^:private payment-pull
  [:payment/id
   {:payment/client client-pull}
   {:payment/invoice [:invoice/id :invoice/number :entity/ref]}
   {:payment/agreement [:agreement/id :agreement/number :entity/ref]}
   {:payment/plan-item [:plan.item/id :plan.item/label :entity/ref]}
   :payment/workspace
   :payment/paid-at
   :payment/currency
   :payment/amount
   :payment/method
   :payment/reference
   :payment/note
   :entity/ref])

(def ^:private agreement-pull
  [:agreement/id
   :agreement/key
   {:agreement/client client-pull}
   :agreement/workspace
   :agreement/number
   :agreement/title
   :agreement/terms
   :agreement/status
   :agreement/effective-at
   :agreement/accepted-at
   :agreement/accepted-by
   :agreement/delivery-channels
   :agreement/delivery-email
   :agreement/delivery-phone
   :agreement/delivery-telegram-chat-id
   :agreement/electronic-consent?
   :agreement/consented-at
   :entity/ref])

(def ^:private plan-item-pull
  [:plan.item/id
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
   :entity/ref])

(defn- present-doc-pack
  [pack]
  (when (map? pack)
    (-> pack
        (update :doc.pack/updated-at format-inst))))

(defn- present-invoice
  [inv]
  (when (map? inv)
    (-> inv
        (update :invoice/issued-at format-inst)
        (update :invoice/due-at format-inst))))

(defn- present-payment
  [p]
  (when (map? p)
    (-> p
        (update :payment/paid-at format-inst))))

(defn- present-agreement
  [a]
  (when (map? a)
    (-> a
        (update :agreement/effective-at format-inst)
        (update :agreement/accepted-at format-inst)
        (update :agreement/consented-at format-inst))))

(defn- present-plan-item
  [it]
  (when (map? it)
    (-> it
        (update :plan.item/due-at format-inst))))

(defn- doc-pack-key
  [workspace client-id]
  (str (workspace-id workspace) "::" (str client-id)))

(defn- doc-pack-eid-by-key
  [db key]
  (ffirst (d/q '[:find ?e
                 :in $ ?k
                 :where [?e :doc.pack/key ?k]]
               db key)))

(defn read-doc-pack
  "Read the current doc pack for a given client. Returns {:doc-pack .. :invoices .. :payments ..}."
  [conn {:keys [client-id]} actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            ws (workspace/actor-workspace actor)
            {ceid :eid cid :id c-err :error} (client-eid db client-id ws)]
        (cond
          c-err c-err
          (nil? ceid) (error 404 "Client not found")
          :else
          (let [key (doc-pack-key ws cid)
                peid (doc-pack-eid-by-key db key)
                pack (when peid (d/pull db doc-pack-pull peid))
                invoices (->> (d/q '[:find ?e
                                     :in $ ?c ?ws
                                     :where [?e :invoice/client ?c]
                                            [?e :invoice/workspace ?ws]]
                                   db ceid ws)
                              (map first)
                              (map #(d/pull db invoice-pull %))
                              (remove nil?)
                              (map present-invoice)
                              (sort-by :invoice/issued-at #(compare %2 %1))
                              vec)
                payments (->> (d/q '[:find ?e
                                     :in $ ?c ?ws
                                     :where [?e :payment/client ?c]
                                            [?e :payment/workspace ?ws]]
                                   db ceid ws)
                              (map first)
                              (map #(d/pull db payment-pull %))
                              (remove nil?)
                              (map present-payment)
                              (sort-by :payment/paid-at #(compare %2 %1))
                              vec)]
            {:doc-pack (present-doc-pack pack)
             :invoices invoices
             :payments payments})))))

(defn upsert-doc-pack!
  "Create/update doc pack for a client. Inputs are optional except client-id.
  Returns {:doc-pack ..}."
  [conn input actor]
  (or (ensure-conn conn)
      (let [body (or input {})
            ws (workspace/actor-workspace actor)
            db (d/db conn)
            {client-id :value c-err :error} (normalize-uuid (param-value body :client/id) "client id")
            company (normalize-text (param-value body :doc.pack/company-name))
            currency (normalize-text (or (param-value body :doc.pack/currency) (param-value body :currency)))
            services (normalize-text (param-value body :doc.pack/services-included))
            plan (normalize-text (param-value body :doc.pack/payment-plan))
            status-notes (normalize-text (param-value body :doc.pack/status-notes))
            address (normalize-text (param-value body :doc.pack/company-address))
            email (normalize-text (param-value body :doc.pack/company-email))
            phone (normalize-text (param-value body :doc.pack/company-phone))]
        (cond
          c-err (error 400 c-err)
          (nil? client-id) (error 400 "client/id is required")
          :else
          (let [{ceid :eid ceid-err :error} (client-eid db client-id ws)]
            (cond
              ceid-err ceid-err
              (nil? ceid) (error 404 "Client not found")
              :else
              (let [key (doc-pack-key ws client-id)
                    existing-eid (doc-pack-eid-by-key db key)
                    pack-id (or (some->> existing-eid (d/pull db [:doc.pack/id]) :doc.pack/id)
                                (UUID/randomUUID))
                    base {:doc.pack/id pack-id
                          :entity/type :entity.type/doc-pack
                          :doc.pack/key key
                          :doc.pack/client [:client/id client-id]
                          :doc.pack/workspace ws
                          :doc.pack/updated-at (now-inst)}
                    base (cond-> base
                           company (assoc :doc.pack/company-name company)
                           address (assoc :doc.pack/company-address address)
                           email (assoc :doc.pack/company-email email)
                           phone (assoc :doc.pack/company-phone phone)
                           currency (assoc :doc.pack/currency currency)
                           services (assoc :doc.pack/services-included services)
                           plan (assoc :doc.pack/payment-plan plan)
                           status-notes (assoc :doc.pack/status-notes status-notes))
                    base (entity/with-ref db base)
                    tx-prov (prov/provenance actor)
                    tx (prov/enrich-tx base tx-prov)]
                (try
                  (let [tx-res (db/transact! conn {:tx-data [tx]})
                        db-after (:db-after tx-res)
                        eid (doc-pack-eid-by-key db-after key)
                        pack (when eid (d/pull db-after doc-pack-pull eid))]
                    {:doc-pack (present-doc-pack pack)})
                  (catch Exception e
                    (log/error e "Failed to upsert doc pack")
                    (error 500 "Failed to save doc pack"))))))))))

(defn list-invoices
  [conn {:keys [client-id]} actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            ws (workspace/actor-workspace actor)
            {ceid :eid cid :id c-err :error} (client-eid db client-id ws)]
        (cond
          c-err c-err
          (nil? ceid) (error 404 "Client not found")
          :else
          (let [invoices (->> (d/q '[:find ?e
                                     :in $ ?c ?ws
                                     :where [?e :invoice/client ?c]
                                            [?e :invoice/workspace ?ws]]
                                   db ceid ws)
                              (map first)
                              (map #(d/pull db invoice-pull %))
                              (remove nil?)
                              (map present-invoice)
                              (sort-by :invoice/issued-at #(compare %2 %1))
                              vec)]
            {:client/id cid
             :invoices invoices})))))

(def ^:private allowed-invoice-status
  #{:draft :sent :paid :void})

(defn create-invoice!
  [conn input actor]
  (or (ensure-conn conn)
      (let [body (or input {})
            ws (workspace/actor-workspace actor)
            db (d/db conn)
            {client-id :value c-err :error} (normalize-uuid (param-value body :client/id) "client id")
            agreement-id-raw (param-value body :agreement/id)
            plan-item-id-raw (param-value body :plan.item/id)
            {agreement-id :value agreement-id-err :error} (when agreement-id-raw
                                                           (entity/resolve-id db :agreement/id agreement-id-raw "agreement id"))
            {plan-item-id :value plan-item-id-err :error} (when plan-item-id-raw
                                                           (entity/resolve-id db :plan.item/id plan-item-id-raw "plan item id"))
            number (normalize-text (param-value body :invoice/number))
            title (normalize-text (param-value body :invoice/title))
            desc (normalize-text (param-value body :invoice/description))
            currency (normalize-text (or (param-value body :invoice/currency) (param-value body :currency)))
            amount (normalize-money (or (param-value body :invoice/total-amount) (param-value body :total)))
            {status :value status-err :error} (normalize-enum (param-value body :invoice/status) allowed-invoice-status "invoice status")
            {issued-at :value issued-at-err :error} (normalize-inst (or (param-value body :invoice/issued-at) (now-inst))
                                                                    "invoice/issued-at"
                                                                    {:required true})
            {due-at :value due-at-err :error} (normalize-inst (param-value body :invoice/due-at) "invoice/due-at" {:required false})]
        (cond
          c-err (error 400 c-err)
          (nil? client-id) (error 400 "client/id is required")
          agreement-id-err (error 400 agreement-id-err)
          plan-item-id-err (error 400 plan-item-id-err)
          (not (present-str? number)) (error 400 "invoice/number is required")
          (nil? amount) (error 400 "invoice/total-amount is required")
          status-err (error 400 status-err)
          issued-at-err (error 400 issued-at-err)
          due-at-err (error 400 due-at-err)
          :else
          (let [{ceid :eid ceid-err :error} (client-eid db client-id ws)]
            (cond
              ceid-err ceid-err
              (nil? ceid) (error 404 "Client not found")
              :else
              (let [agreement-eid (when agreement-id
                                    (ffirst (d/q '[:find ?e
                                                   :in $ ?id ?ws
                                                   :where [?e :agreement/id ?id]
                                                          [?e :agreement/workspace ?ws]]
                                                 db agreement-id ws)))
                    agreement-client-id (when agreement-eid
                                          (ffirst (d/q '[:find ?cid
                                                         :in $ ?e
                                                         :where [?e :agreement/client ?c]
                                                                [?c :client/id ?cid]]
                                                       db agreement-eid)))
                    plan-item-eid (when plan-item-id
                                    (ffirst (d/q '[:find ?e
                                                   :in $ ?id ?ws
                                                   :where [?e :plan.item/id ?id]
                                                          [?e :plan.item/workspace ?ws]]
                                                 db plan-item-id ws)))
                    plan-item-agreement-id (when plan-item-eid
                                             (ffirst (d/q '[:find ?aid
                                                            :in $ ?e
                                                            :where [?e :plan.item/agreement ?a]
                                                                   [?a :agreement/id ?aid]]
                                                          db plan-item-eid)))]
                (cond
                  (and agreement-id (nil? agreement-eid))
                  (error 404 "Agreement not found")

                  (and agreement-client-id (not= client-id agreement-client-id))
                  (error 400 "Agreement client mismatch")

                  (and plan-item-id (nil? plan-item-eid))
                  (error 404 "Plan item not found")

                  (and agreement-id plan-item-agreement-id (not= agreement-id plan-item-agreement-id))
                  (error 400 "Plan item agreement mismatch")

                  :else
                  (let [resolved-agreement-id (or agreement-id plan-item-agreement-id)
                        inv-id (UUID/randomUUID)
                        key (str ws "::" number)
                        base {:invoice/id inv-id
                              :entity/type :entity.type/invoice
                              :invoice/key key
                              :invoice/client [:client/id client-id]
                              :invoice/workspace ws
                              :invoice/number number
                              :invoice/issued-at issued-at
                              :invoice/currency (or currency "SAR")
                              :invoice/total-amount amount
                              :invoice/status (or status :draft)}
                        base (cond-> base
                               title (assoc :invoice/title title)
                               desc (assoc :invoice/description desc)
                               due-at (assoc :invoice/due-at due-at)
                               resolved-agreement-id (assoc :invoice/agreement [:agreement/id resolved-agreement-id])
                               plan-item-id (assoc :invoice/plan-item [:plan.item/id plan-item-id]))
                        base (entity/with-ref db base)
                        tx-prov (prov/provenance actor)
                        tx (prov/enrich-tx base tx-prov)]
                    (try
                      (let [tx-res (db/transact! conn {:tx-data [tx]})
                            db-after (:db-after tx-res)
                            eid (ffirst (d/q '[:find ?e
                                               :in $ ?id ?ws
                                               :where [?e :invoice/id ?id]
                                                      [?e :invoice/workspace ?ws]]
                                             db-after inv-id ws))
                            inv (when eid (d/pull db-after invoice-pull eid))]
                        {:invoice (present-invoice inv)})
                      (catch Exception e
                        (log/error e "Failed to create invoice")
                        (error 500 "Failed to create invoice"
                               {:exception (.getMessage e)
                                :data (when (instance? clojure.lang.ExceptionInfo e) (ex-data e))}))))))))))))

(defn update-invoice!
  [conn invoice-id input actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            ws (workspace/actor-workspace actor)
            {iid :value iid-err :error} (entity/resolve-id db :invoice/id invoice-id "invoice id")
            body (or input {})
            {status :value status-err :error} (normalize-enum (param-value body :invoice/status) allowed-invoice-status "invoice status")
            title (normalize-text (param-value body :invoice/title))
            desc (normalize-text (param-value body :invoice/description))
            due-at (param-value body :invoice/due-at)]
        (cond
          iid-err (error 400 iid-err)
          (nil? iid) (error 400 "invoice/id is required")
          status-err (error 400 status-err)
          :else
          (let [eid (ffirst (d/q '[:find ?e
                                   :in $ ?id ?ws
                                   :where [?e :invoice/id ?id]
                                          [?e :invoice/workspace ?ws]]
                                 db iid ws))]
            (if-not eid
              (error 404 "Invoice not found")
              (let [tx-prov (prov/provenance actor)
                    tx-data (->> [[:db/add eid :invoice/title title]
                                  [:db/add eid :invoice/description desc]
                                  [:db/add eid :invoice/due-at due-at]
                                  [:db/add eid :invoice/status status]]
                                 (remove (fn [[_ _ _ v]] (nil? v)))
                                 (map #(prov/enrich-tx % tx-prov))
                                 vec)]
                (try
                  (when (seq tx-data)
                    (db/transact! conn {:tx-data tx-data}))
                  (let [db-after (d/db conn)
                        inv (d/pull db-after invoice-pull eid)]
                    {:invoice (present-invoice inv)})
                  (catch Exception e
                    (log/error e "Failed to update invoice")
                    (error 500 "Failed to update invoice"))))))))))

(def ^:private allowed-payment-method
  #{:cash :transfer :card :other})

(defn create-payment!
  [conn input actor]
  (or (ensure-conn conn)
      (let [body (or input {})
            ws (workspace/actor-workspace actor)
            db (d/db conn)
            {client-id :value c-err :error} (normalize-uuid (param-value body :client/id) "client id")
            agreement-id-raw (param-value body :agreement/id)
            plan-item-id-raw (param-value body :plan.item/id)
            {agreement-id :value agreement-id-err :error} (when agreement-id-raw
                                                           (entity/resolve-id db :agreement/id agreement-id-raw "agreement id"))
            {plan-item-id :value plan-item-id-err :error} (when plan-item-id-raw
                                                           (entity/resolve-id db :plan.item/id plan-item-id-raw "plan item id"))
            invoice-id (param-value body :invoice/id)
            {iid :value iid-err :error} (when invoice-id (entity/resolve-id db :invoice/id invoice-id "invoice id"))
            amount (normalize-money (or (param-value body :payment/amount) (param-value body :amount)))
            currency (normalize-text (or (param-value body :payment/currency) (param-value body :currency)))
            {method :value method-err :error} (normalize-enum (param-value body :payment/method) allowed-payment-method "payment method")
            {paid-at :value paid-at-err :error} (normalize-inst (param-value body :payment/paid-at) "payment/paid-at" {:required true})
            reference (normalize-text (param-value body :payment/reference))
            note (normalize-text (param-value body :payment/note))]
        (cond
          c-err (error 400 c-err)
          (nil? client-id) (error 400 "client/id is required")
          agreement-id-err (error 400 agreement-id-err)
          plan-item-id-err (error 400 plan-item-id-err)
          iid-err (error 400 iid-err)
          (nil? amount) (error 400 "payment/amount is required")
          method-err (error 400 method-err)
          paid-at-err (error 400 paid-at-err)
          :else
          (let [{ceid :eid ceid-err :error} (client-eid db client-id ws)]
            (cond
              ceid-err ceid-err
              (nil? ceid) (error 404 "Client not found")
              :else
              (let [agreement-eid (when agreement-id
                                    (ffirst (d/q '[:find ?e
                                                   :in $ ?id ?ws
                                                   :where [?e :agreement/id ?id]
                                                          [?e :agreement/workspace ?ws]]
                                                 db agreement-id ws)))
                    agreement-client-id (when agreement-eid
                                          (ffirst (d/q '[:find ?cid
                                                         :in $ ?e
                                                         :where [?e :agreement/client ?c]
                                                                [?c :client/id ?cid]]
                                                       db agreement-eid)))
                    plan-item-eid (when plan-item-id
                                    (ffirst (d/q '[:find ?e
                                                   :in $ ?id ?ws
                                                   :where [?e :plan.item/id ?id]
                                                          [?e :plan.item/workspace ?ws]]
                                                 db plan-item-id ws)))
                    plan-item-agreement-id (when plan-item-eid
                                             (ffirst (d/q '[:find ?aid
                                                            :in $ ?e
                                                            :where [?e :plan.item/agreement ?a]
                                                                   [?a :agreement/id ?aid]]
                                                          db plan-item-eid)))]
                (cond
                  (and agreement-id (nil? agreement-eid)) (error 404 "Agreement not found")
                  (and agreement-client-id (not= client-id agreement-client-id)) (error 400 "Agreement client mismatch")
                  (and plan-item-id (nil? plan-item-eid)) (error 404 "Plan item not found")
                  (and agreement-id plan-item-agreement-id (not= agreement-id plan-item-agreement-id)) (error 400 "Plan item agreement mismatch")
                  :else
                  (let [resolved-agreement-id (or agreement-id plan-item-agreement-id)
                        pay-id (UUID/randomUUID)
                        base {:payment/id pay-id
                              :entity/type :entity.type/payment
                              :payment/client [:client/id client-id]
                              :payment/workspace ws
                              :payment/paid-at paid-at
                              :payment/currency (or currency "SAR")
                              :payment/amount amount
                              :payment/method (or method :transfer)}
                        base (cond-> base
                               iid (assoc :payment/invoice [:invoice/id iid])
                               reference (assoc :payment/reference reference)
                               note (assoc :payment/note note)
                               resolved-agreement-id (assoc :payment/agreement [:agreement/id resolved-agreement-id])
                               plan-item-id (assoc :payment/plan-item [:plan.item/id plan-item-id]))
                        base (entity/with-ref db base)
                        tx-prov (prov/provenance actor)
                        tx (prov/enrich-tx base tx-prov)]
                    (try
                      (let [tx-res (db/transact! conn {:tx-data [tx]})
                            db-after (:db-after tx-res)
                            eid (ffirst (d/q '[:find ?e
                                               :in $ ?id ?ws
                                               :where [?e :payment/id ?id]
                                                      [?e :payment/workspace ?ws]]
                                             db-after pay-id ws))
                            p (when eid (d/pull db-after payment-pull eid))]
                        {:payment (present-payment p)})
                      (catch Exception e
                        (log/error e "Failed to create payment")
                        (error 500 "Failed to create payment"
                               {:exception (.getMessage e)
                                :data (when (instance? clojure.lang.ExceptionInfo e) (ex-data e))}))))))))))))

(defn list-payments
  [conn {:keys [client-id]} actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            ws (workspace/actor-workspace actor)
            {ceid :eid cid :id c-err :error} (client-eid db client-id ws)]
        (cond
          c-err c-err
          (nil? ceid) (error 404 "Client not found")
          :else
          (let [payments (->> (d/q '[:find ?e
                                     :in $ ?c ?ws
                                     :where [?e :payment/client ?c]
                                            [?e :payment/workspace ?ws]]
                                   db ceid ws)
                              (map first)
                              (map #(d/pull db payment-pull %))
                              (remove nil?)
                              (map present-payment)
                              (sort-by :payment/paid-at #(compare %2 %1))
                              vec)]
            {:client/id cid
             :payments payments})))))

(defn- temp-dir!
  []
  (-> (Files/createTempDirectory "documents-" (make-array FileAttribute 0))
      (.toFile)))

(defn- write-json!
  [path data]
  (with-open [w (io/writer path)]
    (.write w (json/write-str data
                              :key-fn (fn [k]
                                        (cond
                                          (keyword? k) (subs (str k) 1)
                                          (string? k) k
                                          :else (str k)))))))

(defn- render-pdf!
  [{:keys [type payload renderer]}]
  (let [dir (temp-dir!)
        input-path (.getPath (io/file dir "input.json"))
        out-path (.getPath (io/file dir (str (name type) ".pdf")))]
    (write-json! input-path payload)
    (case (or renderer :node-playwright)
      :stub
      (do
        (spit out-path "%PDF-1.4\n%stub\n1 0 obj<</Type/Catalog>>endobj\nxref\n0 2\n0000000000 65535 f \n0000000015 00000 n \ntrailer<</Size 2/Root 1 0 R>>\nstartxref\n0\n%%EOF\n")
        {:ok true})

      :node-playwright
      (let [res (sh/sh "node" "scripts/documents-pdf.js"
                       "--type" (name type)
                       "--input" input-path
                       "--out" out-path)]
        (when-not (zero? (:exit res))
          (throw (ex-info "Document PDF render failed"
                          {:type type
                           :exit (:exit res)
                           :out (:out res)
                           :err (:err res)}))))

      (throw (ex-info "Unknown documents renderer"
                      {:renderer renderer})))
    {:dir dir
     :pdf-file (io/file out-path)}))

(defn- cleanup-dir!
  [dir]
  (try
    (doseq [entry (reverse (file-seq dir))]
      (when (.isFile entry)
        (.delete entry)))
    (.delete dir)
    (catch Exception _)))

(defn- store-pdf!
  [conn pdf-file {:keys [filename slug]} actor storage-dir]
  (let [upload {:filename filename
                :content-type "application/pdf"
                :tempfile pdf-file
                :size (.length ^java.io.File pdf-file)}]
    (files/create-file! conn {:file upload :slug slug} actor storage-dir)))

(declare base-context
         client-eid
         pull-client!
         pull-doc-pack!
         pull-agreement!
         plan-items-for-agreement
         invoices-for-client
         payments-for-client
         tasks-summary-for-client)

(def ^:private documents-template "pdf")
(def ^:private verification-alg :hmac-sha256/v1)

(defn- documents-config
  [state]
  (let [cfg (get-in state [:config :documents] {})]
    {:template-version (or (:template-version cfg) "pdf-v3-2026-02-07")
     :verify-base-url (some-> (or (:verify-base-url cfg) "https://www.darelwasl.com") str str/trim)
     :verify-secret (:verify-secret cfg)
     :renderer (or (:renderer cfg) :node-playwright)}))

(defn- normalize-json-key
  [k]
  (cond
    (keyword? k) (subs (str k) 1)
    (string? k) k
    :else (str k)))

(defn- canonical-json-value
  [v]
  (cond
    (keyword? v) (subs (str v) 1)
    (uuid? v) (str v)
    (instance? Date v) (format-inst v)
    (map? v) (into (sorted-map)
                   (map (fn [[k vv]]
                          [(normalize-json-key k) (canonical-json-value vv)]))
                   v)
    (sequential? v) (mapv canonical-json-value v)
    :else v))

(defn- canonical-json-str
  [v]
  (json/write-str (canonical-json-value v)))

(defn- sha256-hex
  [^String s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.getBytes s "UTF-8")]
    (.update digest bytes)
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(def ^:private base32-alphabet
  (vec "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"))

(defn- base32-encode
  "RFC 4648 base32 (no padding)."
  [^bytes bs]
  (let [len (alength bs)
        out (StringBuilder.)]
    (loop [i 0
           buffer 0
           bits 0]
      (cond
        (and (= i len) (zero? bits))
        (.toString out)

        (< bits 5)
        (if (< i len)
          (let [b (bit-and 255 (aget bs i))]
            (recur (inc i) (bit-or (bit-shift-left buffer 8) b) (+ bits 8)))
          (let [idx (bit-and 31 (bit-shift-left buffer (- 5 bits)))]
            (.append out ^String (str (nth base32-alphabet idx)))
            (recur i 0 0)))

        :else
        (let [shift (- bits 5)
              idx (bit-and 31 (bit-shift-right buffer shift))
              buffer (bit-and buffer (dec (bit-shift-left 1 shift)))
              bits shift]
          (.append out ^String (str (nth base32-alphabet idx)))
          (recur i buffer bits))))))

(defn- hmac-sha256
  [^String secret ^String message]
  (let [mac (Mac/getInstance "HmacSHA256")
        key (SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256")]
    (.init mac key)
    (.doFinal mac (.getBytes message "UTF-8"))))

(defn- verification-code
  [secret document-ref payload-hash]
  (let [raw (hmac-sha256 secret (str document-ref ":" payload-hash))
        b32 (base32-encode raw)
        short (subs b32 0 (min 20 (count b32)))
        groups (->> (partition-all 4 short)
                    (map #(apply str %)))]
    (str/join "-" groups)))

(def ^:private document-pull
  [:document/id
   :document/key
   :document/workspace
   :document/type
   :document/template
   :document/template-version
   :document/issued-at
   :document/subject-type
   :document/subject-id
   :document/payload-hash
   :document/verification-code
   :document/verification-alg
   :entity/ref
   {:document/client client-pull}
   {:document/file [:file/id :file/name :file/slug :file/mime :file/size-bytes :file/checksum :file/created-at :entity/ref]}])

(defn- present-file-lite
  [file]
  (when (map? file)
    (let [file-id (:file/id file)
          slug (:file/slug file)]
      (-> file
          (assoc :file/url (str "/api/files/" file-id "/content"))
          (assoc :file/ref (when slug (str "file:" slug)))
          (select-keys [:file/id
                        :file/name
                        :file/slug
                        :file/mime
                        :file/size-bytes
                        :file/checksum
                        :file/created-at
	                        :file/url
	                        :file/ref
	                        :entity/ref])))))

(defn- present-document
  [doc]
  (when (map? doc)
    (-> doc
        (update :document/issued-at format-inst)
        (update :document/file present-file-lite)
        (select-keys [:document/id
                      :document/type
                      :document/template
                      :document/template-version
                      :document/issued-at
                      :document/subject-type
                      :document/subject-id
                      :document/payload-hash
                      :document/verification-code
                      :document/verification-alg
                      :document/file
                      :document/client
                      :entity/ref]))))

(defn- document-eid-by-key
  [db key]
  (ffirst (d/q '[:find ?e
                 :in $ ?k
                 :where [?e :document/key ?k]]
               db key)))

(defn- document-by-key
  [db key]
  (when-let [eid (document-eid-by-key db key)]
    (d/pull db document-pull eid)))

(defn- document-eid-by-ref
  [db ref workspace]
  (when (and (string? ref) (not (str/blank? ref)))
    (ffirst (d/q '[:find ?e
                   :in $ ?ref ?ws
                   :where [?e :entity/ref ?ref]
                          [?e :document/workspace ?ws]]
                 db (str/trim ref) workspace))))

(defn- document-by-ref
  [db ref workspace]
  (when-let [eid (document-eid-by-ref db ref workspace)]
    (d/pull db document-pull eid)))

(defn- latest-document
  [db {:keys [workspace type subject-type subject-id]}]
  (when (and workspace type subject-type subject-id)
    (let [rows (d/q '[:find ?e ?issued
                      :in $ ?ws ?type ?subject-type ?subject-id
                      :where [?e :document/workspace ?ws]
                             [?e :document/type ?type]
                             [?e :document/subject-type ?subject-type]
                             [?e :document/subject-id ?subject-id]
                             [?e :document/issued-at ?issued]]
                    db workspace type subject-type subject-id)
          sorted (sort-by second #(compare %2 %1) rows)]
      (when-let [[eid _] (first sorted)]
        (d/pull db document-pull eid)))))

(defn issue-document!
  "Issue an immutable document (or reuse an existing issued copy when unchanged).

  Returns {:document <presented-document> :file <presented-file>} or {:error ...}."
  [state {:keys [type input actor]}]
  (let [conn (get-in state [:db :conn])
        storage-dir (get-in state [:config :files :storage-dir])
        ws (workspace/actor-workspace actor)
        body (or input {})
        {client-id :value c-err :error} (normalize-uuid (param-value body :client/id) "client id")
        force? (boolean (param-value body :document/force?))
        {:keys [template-version verify-base-url verify-secret renderer]} (documents-config state)]
    (if-let [conn-err (ensure-conn conn)]
      conn-err
      (cond
        c-err (error 400 c-err)
        (nil? client-id) (error 400 "client/id is required")
        (not (present-str? verify-secret)) (error 500 "DOCUMENT_VERIFY_SECRET is required to issue documents")
        (str/blank? (str storage-dir)) (error 500 "File storage not configured")
        :else
        (let [db (d/db conn)
              client (pull-client! db client-id ws)]
          (if (:error client)
            client
            (let [{ceid :eid} (client-eid db client-id ws)
                  doc-pack (or (pull-doc-pack! db client-id ws)
                               {:doc.pack/company-name "Company"
                                :doc.pack/currency "SAR"
                                :doc.pack/services-included ""
                                :doc.pack/payment-plan ""
                                :doc.pack/status-notes ""})
                  invoices (invoices-for-client db ceid ws)
                  payments (payments-for-client db ceid ws)
                  base (merge (base-context doc-pack client)
                              {:docType (name type)})
                  subject (case type
                            :proposal
                            {:subject-type :client
                             :subject-id client-id
                             :domain-payload (assoc base
                                                    :servicesIncluded (or (:doc.pack/services-included doc-pack) "")
                                                    :paymentPlan (or (:doc.pack/payment-plan doc-pack) "")
                                                    :invoices invoices)
                             :filename (str "proposal-" (str/lower-case (str/replace (or (:client/name client) "client") #"[^a-z0-9]+" "-")) ".pdf")}

                            :status-report
                            (let [tasks (tasks-summary-for-client db ceid ws)]
                              {:subject-type :client
                               :subject-id client-id
                               :domain-payload (assoc base
                                                      :statusNotes (or (:doc.pack/status-notes doc-pack) "")
                                                      :invoices invoices
                                                      :payments payments
                                                      :tasks tasks)
                               :filename (str "status-report-" (str/lower-case (str/replace (or (:client/name client) "client") #"[^a-z0-9]+" "-")) ".pdf")})

                            :agreement
                            (let [{aid :value aid-err :error} (entity/resolve-id db :agreement/id (param-value body :agreement/id) "agreement id")]
                              (cond
                                aid-err (error 400 aid-err)
                                (nil? aid) (error 400 "agreement/id is required")
                                :else
                                (let [agreement (some-> (pull-agreement! db aid ws) present-agreement)]
                                  (cond
                                    (nil? agreement) (error 404 "Agreement not found")
                                    (not= client-id (get-in agreement [:agreement/client :client/id]))
                                    (error 400 "Agreement client mismatch")
                                    :else
                                    (let [items (plan-items-for-agreement db aid ws)]
                                      {:subject-type :agreement
                                       :subject-id aid
                                       :domain-payload (assoc base
                                                              :agreement agreement
                                                              :planItems items)
                                       :filename (str "agreement-" (or (:agreement/number agreement)
                                                                       (some-> (:entity/ref agreement) str/trim not-empty)
                                                                       (subs (str aid) 0 8))
                                                      ".pdf")})))))

                            :invoice
                            (let [{iid :value iid-err :error} (entity/resolve-id db :invoice/id (param-value body :invoice/id) "invoice id")]
                              (cond
                                iid-err (error 400 iid-err)
                                (nil? iid) (error 400 "invoice/id is required")
                                :else
                                (let [inv-eid (ffirst (d/q '[:find ?e
                                                             :in $ ?id ?ws
                                                             :where [?e :invoice/id ?id]
                                                                    [?e :invoice/workspace ?ws]]
                                                           db iid ws))
                                      inv (when inv-eid (present-invoice (d/pull db invoice-pull inv-eid)))]
                                  (if-not inv
                                    (error 404 "Invoice not found")
                                    (let [inv-payments (->> payments
                                                            (filter (fn [p] (= iid (get-in p [:payment/invoice :invoice/id]))))
                                                            vec)]
                                      {:subject-type :invoice
                                       :subject-id iid
                                       :domain-payload (assoc base
                                                              :invoice inv
                                                              :payments inv-payments)
                                       :filename (str "invoice-" (or (:invoice/number inv) (subs (str iid) 0 8)) ".pdf")})))))

                            :receipt
                            (let [{pid :value pid-err :error} (entity/resolve-id db :payment/id (param-value body :payment/id) "payment id")]
                              (cond
                                pid-err (error 400 pid-err)
                                (nil? pid) (error 400 "payment/id is required")
                                :else
                                (let [pay-eid (ffirst (d/q '[:find ?e
                                                             :in $ ?id ?ws
                                                             :where [?e :payment/id ?id]
                                                                    [?e :payment/workspace ?ws]]
                                                           db pid ws))
                                      payment (when pay-eid (present-payment (d/pull db payment-pull pay-eid)))]
                                  (if-not payment
                                    (error 404 "Payment not found")
                                    (let [invoice-id (get-in payment [:payment/invoice :invoice/id])
                                          inv (when invoice-id
                                                (let [inv-eid (ffirst (d/q '[:find ?e
                                                                             :in $ ?id ?ws
                                                                             :where [?e :invoice/id ?id]
                                                                                    [?e :invoice/workspace ?ws]]
                                                                           db invoice-id ws))]
                                                  (when inv-eid (present-invoice (d/pull db invoice-pull inv-eid)))))]
                                      {:subject-type :payment
                                       :subject-id pid
                                       :domain-payload (assoc base
                                                              :payment payment
                                                              :invoice inv)
                                       :filename (str "receipt-" (subs (str pid) 0 8) ".pdf")})))))

                            (error 400 "Unknown document type" {:type type}))]
              (if (:error subject)
                subject
                (let [{:keys [subject-type subject-id domain-payload filename]} subject
                      payload-hash (sha256-hex (canonical-json-str domain-payload))
                      base-key (str ws "::" (name type) "::" (name subject-type) "::" (str subject-id) "::" template-version "::" payload-hash)
                      document-key (if force?
                                     (str base-key "::force::" (UUID/randomUUID))
                                     base-key)]
                  (if-let [existing (when-not force? (document-by-key db document-key))]
                    (let [doc (present-document existing)]
                      {:document doc
                       :file (:document/file doc)})
                    (let [issued-at (now-inst)
                          issued-at-str (.format iso-formatter (.toInstant issued-at))
                          document-ref (entity/unique-ref db :entity.type/document)
                          code (verification-code verify-secret document-ref payload-hash)
                          base-url (some-> (or verify-base-url "") (str/replace #"/+$" ""))
                          enc (fn [s] (java.net.URLEncoder/encode (str s) "UTF-8"))
                          verify-url (when-not (str/blank? base-url)
                                       (str base-url "/verify?ref=" (enc document-ref) "&code=" (enc code)))
                          render-payload (assoc domain-payload
                                                :issuedAt issued-at-str
                                                :templateVersion template-version
                                                :documentRef document-ref
                                                :verificationCode code
                                                :verifyUrl verify-url)
                          {:keys [dir pdf-file]} (render-pdf! {:type type
                                                               :payload render-payload
                                                               :renderer renderer})
                          stored (store-pdf! conn pdf-file {:filename filename
                                                            :slug (normalize-text (param-value body :file/slug))}
                                             actor storage-dir)]
                      (cleanup-dir! dir)
                      (if-let [err (:error stored)]
                        {:error err}
                        (let [file (:file stored)
                              file-id (:file/id file)
                              doc-id (UUID/randomUUID)
                              base-tx {:document/id doc-id
                                       :entity/type :entity.type/document
                                       :entity/ref document-ref
                                       :document/key document-key
                                       :document/workspace ws
                                       :document/type type
                                       :document/template documents-template
                                       :document/template-version template-version
                                       :document/issued-at issued-at
                                       :document/client [:client/id client-id]
                                       :document/subject-type subject-type
                                       :document/subject-id subject-id
                                       :document/payload-hash payload-hash
                                       :document/file [:file/id file-id]
                                       :document/verification-code code
                                       :document/verification-alg verification-alg}
                              tx (prov/enrich-tx base-tx (prov/provenance actor))]
                          (try
                            (let [tx-res (db/transact! conn {:tx-data [tx]})
                                  db-after (:db-after tx-res)
                                  eid (ffirst (d/q '[:find ?e
                                                     :in $ ?id ?ws
                                                     :where [?e :document/id ?id]
                                                            [?e :document/workspace ?ws]]
                                                   db-after doc-id ws))
                                  doc (when eid (d/pull db-after document-pull eid))
                                  presented (present-document doc)]
                              {:document presented
                               :file (:document/file presented)})
                            (catch Exception e
                              (let [data (ex-data e)
                                    unique? (or (= (:db/error data) :db.error/unique-conflict)
                                                (some-> e .getMessage (str/includes? "unique")))]
                                (if unique?
                                  (do
                                    (when file-id
                                      (try
                                        (files/delete-file! conn file-id storage-dir actor)
                                        (catch Exception _)))
                                    (if-let [doc (document-by-key (d/db conn) document-key)]
                                      (let [presented (present-document doc)]
                                        {:document presented
                                         :file (:document/file presented)})
                                      (error 500 "Document issuance unique conflict but existing document not found")))
                                  (do
                                    (log/error e "Document issuance failed" {:type type})
                                    (error 500 "Document issuance failed" {:exception (.getMessage e)})))))))))))))))))))

(defn latest-document!
  "Fetch latest issued document for a subject/type. Returns {:document .. :file ..} or {:error ..}."
  [state {:keys [type input actor]}]
  (let [conn (get-in state [:db :conn])
        ws (workspace/actor-workspace actor)
        body (or input {})
        {client-id :value c-err :error} (normalize-uuid (param-value body :client/id) "client id")]
    (if-let [conn-err (ensure-conn conn)]
      conn-err
      (cond
        c-err (error 400 c-err)
        (nil? client-id) (error 400 "client/id is required")
        :else
        (let [db (d/db conn)
              subject (case type
                        (:proposal :status-report) {:subject-type :client :subject-id client-id}
                        :agreement (let [{aid :value aid-err :error} (entity/resolve-id db :agreement/id (param-value body :agreement/id) "agreement id")]
                                     (cond
                                       aid-err {:error (error 400 aid-err)}
                                       (nil? aid) {:error (error 400 "agreement/id is required")}
                                       :else {:subject-type :agreement :subject-id aid}))
                        :invoice (let [{iid :value iid-err :error} (entity/resolve-id db :invoice/id (param-value body :invoice/id) "invoice id")]
                                   (cond
                                     iid-err {:error (error 400 iid-err)}
                                     (nil? iid) {:error (error 400 "invoice/id is required")}
                                     :else {:subject-type :invoice :subject-id iid}))
                        :receipt (let [{pid :value pid-err :error} (entity/resolve-id db :payment/id (param-value body :payment/id) "payment id")]
                                   (cond
                                     pid-err {:error (error 400 pid-err)}
                                     (nil? pid) {:error (error 400 "payment/id is required")}
                                     :else {:subject-type :payment :subject-id pid}))
                        {:error (error 400 "Unknown document type" {:type type})})]
          (if-let [err (:error subject)]
            err
            (if-let [doc (latest-document db (merge {:workspace ws :type type} subject))]
              (let [presented (present-document doc)]
                {:document presented
                 :file (:document/file presented)})
              (error 404 "No issued document found"))))))))

(defn verify-document!
  "Verify a document ref and verification code pair. Returns {:document/valid? boolean :document map?}."
  [state {:keys [input actor]}]
  (let [conn (get-in state [:db :conn])
        ws (workspace/actor-workspace actor)
        body (or input {})
        ref (some-> (param-value body :document/ref) str)
        code (some-> (param-value body :document/verification-code) str)
        {:keys [verify-secret]} (documents-config state)]
    (if-let [conn-err (ensure-conn conn)]
      conn-err
      (cond
        (not (present-str? verify-secret)) (error 500 "DOCUMENT_VERIFY_SECRET is required to verify documents")
        (not (present-str? ref)) (error 400 "document/ref is required")
        (not (present-str? code)) (error 400 "document/verification-code is required")
        :else
        (let [db (d/db conn)
              doc (document-by-ref db ref ws)]
          (if-not doc
            {:document/valid? false}
            (let [payload-hash (:document/payload-hash doc)
                  expected (verification-code verify-secret (:entity/ref doc) payload-hash)
                  ok? (= (str/trim code) expected)]
              (cond-> {:document/valid? ok?}
                ok? (assoc :document (present-document doc))))))))))

(defn- ->instant
  [v]
  (cond
    (instance? Instant v) v
    (instance? Date v) (.toInstant ^Date v)
    (string? v) (try (Instant/parse (str/trim v)) (catch Exception _ nil))
    :else nil))

(defn- in-range?
  [^Instant v ^Instant from ^Instant to]
  (and (or (nil? from) (not (neg? (compare v from))))
       (or (nil? to) (not (pos? (compare v to))))))

(defn analytics-revenue-by-month
  "Sum payments by YYYY-MM, grouped by currency. Returns {:series [...]}."
  [conn {:keys [client-id from to]} actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            ws (workspace/actor-workspace actor)
            {cid :value cid-err :error} (when client-id (entity/resolve-id db :client/id client-id "client id"))
            from-i (->instant from)
            to-i (->instant to)]
        (cond
          cid-err (error 400 cid-err)
          :else
          (let [client-eid (when cid
                             (ffirst (d/q '[:find ?e
                                            :in $ ?id ?ws
                                            :where [?e :client/id ?id]
                                                   [?e :client/workspace ?ws]]
                                          db cid ws)))
                rows (d/q '[:find ?paid ?amt ?cur ?client
                            :in $ ?ws
                            :where [?p :payment/workspace ?ws]
                                   [?p :payment/paid-at ?paid]
                                   [?p :payment/amount ?amt]
                                   [?p :payment/currency ?cur]
                                   [?p :payment/client ?client]]
                          db ws)
                filtered (->> rows
                              (filter (fn [[paid _ _ client]]
                                        (and (or (nil? client-eid) (= client-eid client))
                                             (when-let [pi (->instant paid)]
                                               (in-range? pi from-i to-i))))))
                by-bucket (group-by (fn [[paid _ cur _]]
                                      (let [inst (->instant paid)
                                            ym (.toString (java.time.YearMonth/from (.atZone inst (java.time.ZoneId/systemDefault))))]
                                        [ym (str cur)]))
                                    filtered)
                series (->> by-bucket
                            (map (fn [[[ym cur] items]]
                                   {:month ym
                                    :currency cur
                                    :total (double (reduce (fn [acc [_ amt _ _]] (+ acc (double amt))) 0.0 items))}))
                            (sort-by (juxt :month :currency))
                            vec)]
            {:series series})))))

(defn analytics-outstanding-invoices
  "Compute outstanding per invoice and totals. Returns {:invoices [...] :totals {...}}."
  [conn {:keys [client-id as-of]} actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            ws (workspace/actor-workspace actor)
            {cid :value cid-err :error} (when client-id (entity/resolve-id db :client/id client-id "client id"))
            as-of-i (->instant as-of)]
        (cond
          cid-err (error 400 cid-err)
          :else
          (let [client-eid (when cid
                             (ffirst (d/q '[:find ?e
                                            :in $ ?id ?ws
                                            :where [?e :client/id ?id]
                                                   [?e :client/workspace ?ws]]
                                          db cid ws)))
                inv-eids (if client-eid
                           (d/q '[:find ?e
                                  :in $ ?ws ?client
                                  :where [?e :invoice/workspace ?ws]
                                         [?e :invoice/client ?client]]
                                db ws client-eid)
                           (d/q '[:find ?e
                                  :in $ ?ws
                                  :where [?e :invoice/workspace ?ws]]
                                db ws))
                invs (->> inv-eids
                          (map first)
                          (map #(d/pull db invoice-pull %))
                          (remove nil?)
                          (map present-invoice)
                          vec)
                pay-eids (if client-eid
                           (d/q '[:find ?e
                                  :in $ ?ws ?client
                                  :where [?e :payment/workspace ?ws]
                                         [?e :payment/client ?client]]
                                db ws client-eid)
                           (d/q '[:find ?e
                                  :in $ ?ws
                                  :where [?e :payment/workspace ?ws]]
                                db ws))
                pays (->> pay-eids
                          (map first)
                          (map #(d/pull db payment-pull %))
                          (remove nil?)
                          (filter (fn [p]
                                    (if-not as-of-i
                                      true
                                      (when-let [pi (->instant (:payment/paid-at p))]
                                        (not (pos? (compare pi as-of-i)))))))
                          (map present-payment)
                          vec)
                pays-by-invoice (group-by (fn [p] (get-in p [:payment/invoice :invoice/id])) pays)
                invoice-lines (->> invs
                                   (map (fn [inv]
                                          (let [iid (:invoice/id inv)
                                                total (double (or (:invoice/total-amount inv) 0.0))
                                                payments (get pays-by-invoice iid)
                                                paid (double (reduce (fn [acc p] (+ acc (double (or (:payment/amount p) 0.0)))) 0.0 payments))
                                                outstanding (max 0.0 (- total paid))]
                                            {:invoice/id iid
                                             :invoice/number (:invoice/number inv)
                                             :invoice/status (:invoice/status inv)
                                             :invoice/issued-at (:invoice/issued-at inv)
                                             :invoice/due-at (:invoice/due-at inv)
                                             :currency (:invoice/currency inv)
                                             :total total
                                             :paid paid
                                             :outstanding outstanding})))
                                   (sort-by :invoice/issued-at #(compare %2 %1))
                                   vec)
                totals {:total-invoiced (double (reduce + 0.0 (map :total invoice-lines)))
                        :total-paid (double (reduce + 0.0 (map :paid invoice-lines)))
                        :total-outstanding (double (reduce + 0.0 (map :outstanding invoice-lines)))}]
            {:invoices invoice-lines
             :totals totals})))))

(defn analytics-funnel
  "Count issued documents by type over a period. Returns {:counts {...}}."
  [conn {:keys [client-id from to]} actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            ws (workspace/actor-workspace actor)
            {cid :value cid-err :error} (when client-id (entity/resolve-id db :client/id client-id "client id"))
            from-i (->instant from)
            to-i (->instant to)]
        (cond
          cid-err (error 400 cid-err)
          :else
          (let [client-eid (when cid
                             (ffirst (d/q '[:find ?e
                                            :in $ ?id ?ws
                                            :where [?e :client/id ?id]
                                                   [?e :client/workspace ?ws]]
                                          db cid ws)))
                rows (d/q '[:find ?type ?issued ?client
                            :in $ ?ws
                            :where [?e :document/workspace ?ws]
                                   [?e :document/type ?type]
                                   [?e :document/issued-at ?issued]
                                   [?e :document/client ?client]]
                          db ws)
                filtered (->> rows
                              (filter (fn [[_ issued client]]
                                        (and (or (nil? client-eid) (= client-eid client))
                                             (when-let [ii (->instant issued)]
                                               (in-range? ii from-i to-i))))))
                counts (->> filtered
                            (map first)
                            (frequencies)
                            (into {} (map (fn [[k v]] [(keyword (name k)) v]))))]
            {:counts counts})))))

(defn- base-context
  [doc-pack client]
  {:company {:name (or (:doc.pack/company-name doc-pack) "—")
             :address (or (:doc.pack/company-address doc-pack) nil)
             :email (or (:doc.pack/company-email doc-pack) nil)
             :phone (or (:doc.pack/company-phone doc-pack) nil)
             :currency (or (:doc.pack/currency doc-pack) "SAR")}
   :client {:name (or (:client/name client) "—")
            :phone (:client/phone client)
            :email (:client/email client)
            :ref (:entity/ref client)}})

(defn- pull-client!
  [db client-id workspace]
  (let [{ceid :eid err :error} (client-eid db client-id workspace)]
    (cond
      err err
      (nil? ceid) (error 404 "Client not found")
      :else
      (d/pull db client-pull ceid))))

(defn- pull-doc-pack!
  [db client-id workspace]
  (let [key (doc-pack-key workspace client-id)
        peid (doc-pack-eid-by-key db key)]
    (when peid
      (d/pull db doc-pack-pull peid))))

(defn- agreement-eid
  [db agreement-id workspace]
  (when agreement-id
    (ffirst (d/q '[:find ?e
                   :in $ ?id ?ws
                   :where [?e :agreement/id ?id]
                          [?e :agreement/workspace ?ws]]
                 db agreement-id workspace))))

(defn- pull-agreement!
  [db agreement-id workspace]
  (when-let [eid (agreement-eid db agreement-id workspace)]
    (d/pull db agreement-pull eid)))

(defn- plan-items-for-agreement
  [db agreement-id workspace]
  (when-let [aeid (agreement-eid db agreement-id workspace)]
    (->> (d/q '[:find ?e ?idx ?due
                :in $ ?a ?ws
                :where [?e :plan.item/agreement ?a]
                       [?e :plan.item/workspace ?ws]
                       [(get-else $ ?e :plan.item/active? true) ?active]
                       [(get-else $ ?e :plan.item/index 999999) ?idx]
                       [?e :plan.item/due-at ?due]
                       [(= true ?active)]]
              db aeid workspace)
         (sort-by (fn [[_ idx due]] [(long idx) due]))
         (map first)
         (map #(d/pull db plan-item-pull %))
         (remove nil?)
         (map present-plan-item)
         vec)))

(defn- invoices-for-client
  [db client-eid workspace]
  (->> (d/q '[:find ?e
              :in $ ?c ?ws
              :where [?e :invoice/client ?c]
                     [?e :invoice/workspace ?ws]]
            db client-eid workspace)
       (map first)
       (map #(d/pull db invoice-pull %))
       (remove nil?)
       (map present-invoice)
       (sort-by :invoice/issued-at #(compare %2 %1))
       vec))

(defn- payments-for-client
  [db client-eid workspace]
  (->> (d/q '[:find ?e
              :in $ ?c ?ws
              :where [?e :payment/client ?c]
                     [?e :payment/workspace ?ws]]
            db client-eid workspace)
       (map first)
       (map #(d/pull db payment-pull %))
       (remove nil?)
       (map present-payment)
       (sort-by :payment/paid-at #(compare %2 %1))
       vec))

(defn- tasks-summary-for-client
  [db client-eid workspace]
  (let [tasks (->> (d/q '[:find ?e
                          :in $ ?c ?ws
                          :where [?e :task/client ?c]
                                 [?e :fact/workspace ?ws]]
                        db client-eid workspace)
                   (map first)
                   (map #(d/pull db [:task/id :task/title :task/status :task/priority :task/archived? :entity/ref] %))
                   (remove nil?))
        by-status (group-by :task/status (remove :task/archived? tasks))]
    {:done (->> (:done by-status) (map #(select-keys % [:task/title :task/status :entity/ref])) vec)
     :in-progress (->> (:in-progress by-status) (map #(select-keys % [:task/title :task/status :entity/ref])) vec)
     :pending (->> (:pending by-status) (map #(select-keys % [:task/title :task/status :entity/ref])) vec)
     :todo (->> (:todo by-status) (map #(select-keys % [:task/title :task/status :entity/ref])) vec)}))

(defn generate-document!
  "Generate one of :proposal, :invoice, :receipt, :status-report as a PDF and store it.
  Inputs:
  - :client/id (required for all)
  - :invoice/id (required for :invoice)
  - :payment/id (required for :receipt)
  - :statement/slug optional file slug
  Returns {:file <presented-file>}."
  [state {:keys [type input actor]}]
  (let [res (issue-document! state {:type type :input input :actor actor})]
    (if-let [err (:error res)]
      {:error err}
      {:file (:file res)})))
