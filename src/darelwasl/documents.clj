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
           (java.time Instant)
           (java.time.format DateTimeFormatter)
           (java.util Date UUID)))

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
   :payment/workspace
   :payment/paid-at
   :payment/currency
   :payment/amount
   :payment/method
   :payment/reference
   :payment/note
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
            number (normalize-text (param-value body :invoice/number))
            title (normalize-text (param-value body :invoice/title))
            desc (normalize-text (param-value body :invoice/description))
            currency (normalize-text (or (param-value body :invoice/currency) (param-value body :currency)))
            amount (normalize-money (or (param-value body :invoice/total-amount) (param-value body :total)))
            {status :value status-err :error} (normalize-enum (param-value body :invoice/status) allowed-invoice-status "invoice status")
            issued-at (or (param-value body :invoice/issued-at) (now-inst))
            due-at (param-value body :invoice/due-at)]
        (cond
          c-err (error 400 c-err)
          (nil? client-id) (error 400 "client/id is required")
          (not (present-str? number)) (error 400 "invoice/number is required")
          (nil? amount) (error 400 "invoice/total-amount is required")
          status-err (error 400 status-err)
          :else
          (let [{ceid :eid ceid-err :error} (client-eid db client-id ws)]
            (cond
              ceid-err ceid-err
              (nil? ceid) (error 404 "Client not found")
              :else
              (let [inv-id (UUID/randomUUID)
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
                           due-at (assoc :invoice/due-at due-at))
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
                            :data (when (instance? clojure.lang.ExceptionInfo e) (ex-data e))}))))))))))

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
            invoice-id (param-value body :invoice/id)
            {iid :value iid-err :error} (when invoice-id (entity/resolve-id db :invoice/id invoice-id "invoice id"))
            amount (normalize-money (or (param-value body :payment/amount) (param-value body :amount)))
            currency (normalize-text (or (param-value body :payment/currency) (param-value body :currency)))
            {method :value method-err :error} (normalize-enum (param-value body :payment/method) allowed-payment-method "payment method")
            paid-at (or (param-value body :payment/paid-at) (now-inst))
            reference (normalize-text (param-value body :payment/reference))
            note (normalize-text (param-value body :payment/note))]
        (cond
          c-err (error 400 c-err)
          (nil? client-id) (error 400 "client/id is required")
          iid-err (error 400 iid-err)
          (nil? amount) (error 400 "payment/amount is required")
          method-err (error 400 method-err)
          :else
          (let [{ceid :eid ceid-err :error} (client-eid db client-id ws)]
            (cond
              ceid-err ceid-err
              (nil? ceid) (error 404 "Client not found")
              :else
              (let [pay-id (UUID/randomUUID)
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
                           note (assoc :payment/note note))
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
                            :data (when (instance? clojure.lang.ExceptionInfo e) (ex-data e))}))))))))))

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
  [{:keys [type payload]}]
  (let [dir (temp-dir!)
        input-path (.getPath (io/file dir "input.json"))
        out-path (.getPath (io/file dir (str (name type) ".pdf")))]
    (write-json! input-path payload)
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
  (let [conn (get-in state [:db :conn])
        storage-dir (get-in state [:config :files :storage-dir])
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
                  payload-base (merge (base-context doc-pack client)
                                      {:generatedAt (.format iso-formatter (Instant/now))
                                       :docType (name type)})]
              (try
                (case type
                  :proposal
                  (let [payload (assoc payload-base
                                       :servicesIncluded (or (:doc.pack/services-included doc-pack) "")
                                       :paymentPlan (or (:doc.pack/payment-plan doc-pack) "")
                                       :invoices invoices)
                        {:keys [dir pdf-file]} (render-pdf! {:type :proposal :payload payload})
                        filename (str "proposal-" (str/lower-case (str/replace (or (:client/name client) "client") #"[^a-z0-9]+" "-")) ".pdf")
                        res (store-pdf! conn pdf-file {:filename filename
                                                       :slug (normalize-text (param-value body :file/slug))}
                                        actor storage-dir)]
                    (cleanup-dir! dir)
                    res)

                  :status-report
                  (let [tasks (tasks-summary-for-client db ceid ws)
                        payload (assoc payload-base
                                       :statusNotes (or (:doc.pack/status-notes doc-pack) "")
                                       :invoices invoices
                                       :payments payments
                                       :tasks tasks)
                        {:keys [dir pdf-file]} (render-pdf! {:type :status-report :payload payload})
                        filename (str "status-report-" (str/lower-case (str/replace (or (:client/name client) "client") #"[^a-z0-9]+" "-")) ".pdf")
                        res (store-pdf! conn pdf-file {:filename filename
                                                       :slug (normalize-text (param-value body :file/slug))}
                                        actor storage-dir)]
                    (cleanup-dir! dir)
                    res)

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
                                                  vec)
                                payload (assoc payload-base
                                               :invoice inv
                                               :payments inv-payments)
                                {:keys [dir pdf-file]} (render-pdf! {:type :invoice :payload payload})
                                filename (str "invoice-" (or (:invoice/number inv) (subs (str iid) 0 8)) ".pdf")
                                res (store-pdf! conn pdf-file {:filename filename
                                                               :slug (normalize-text (param-value body :file/slug))}
                                                actor storage-dir)]
                            (cleanup-dir! dir)
                            res)))))

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
                                        (when inv-eid (present-invoice (d/pull db invoice-pull inv-eid)))))
                                payload (assoc payload-base
                                               :payment payment
                                               :invoice inv)
                                {:keys [dir pdf-file]} (render-pdf! {:type :receipt :payload payload})
                                filename (str "receipt-" (subs (str pid) 0 8) ".pdf")
                                res (store-pdf! conn pdf-file {:filename filename
                                                               :slug (normalize-text (param-value body :file/slug))}
                                                actor storage-dir)]
                            (cleanup-dir! dir)
                            res)))))

                  (error 400 "Unknown document type" {:type type}))
                (catch Exception e
                  (log/error e "Document generation failed" {:type type})
                  (error 500 "Document generation failed" {:type type}))))))))))
