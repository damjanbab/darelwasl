(ns darelwasl.automations
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.clients :as clients])
  (:import (java.io PushbackReader)))

(def default-registry-path "registries/automations.edn")

(defn read-registry
  ([] (read-registry default-registry-path))
  ([path]
   (let [file (io/file path)]
     (when-not (.exists file)
       [])
     (with-open [r (PushbackReader. (io/reader file))]
       (edn/read r)))))

(defn- enabled?
  [automation]
  (true? (:enabled automation)))

(defn- trigger-matches?
  [event trigger]
  (= (:event/type event) (:event/type trigger)))

(defn matching-automations
  [automations event]
  (->> automations
       (filter enabled?)
       (filter (fn [a]
                 (some #(trigger-matches? event %) (:triggers a))))))

(defn- automation-actor
  [automation]
  {:actor/type :actor.type/automation
   :automation/id (:id automation)
   :actor/surface :surface/automation})

(defn- safe-read-edn-map
  [raw]
  (when (string? raw)
    (try
      (let [v (edn/read-string raw)]
        (when (map? v) v))
      (catch Exception _ nil))))

(def ^:private budget->label
  {:low "Low"
   :medium "Medium"
   :high "High"
   :skip "—"})

(def ^:private proposal-services-by-id
  {;; Licensing + setup
   :service/entrepreneur-license
   ["Business plan and pre-application support"
    "License issuance support and coordination"
    "Commercial Registration (CR) setup support"
    "Post-setup registrations checklist and execution plan"
    "Stakeholder coordination and document tracking"]

   :service/general-investment-license
   ["Foreign entity documentation checklist and tracking"
    "License submission support and follow-up"
    "Commercial Registration (CR) setup support"
    "Post-setup registrations checklist and execution plan"
    "Stakeholder coordination and document tracking"]

   :service/gcc-national-license
   ["Eligibility confirmation and requirements checklist"
    "Commercial Registration (CR) direct issuance support"
    "Post-setup registrations checklist and execution plan"
    "Labor/employee portal readiness plan"
    "Stakeholder coordination and document tracking"]

   :service/rhq-license
   ["Group documentation requirements checklist (3 entities)"
    "Attestation/translation tracking and submission readiness"
    "License submission support and follow-up"
    "Commercial Registration (CR) setup support"
    "Post-setup registrations checklist and execution plan"]

   ;; Other services
   :service/pro-services
   ["Portal setup and ongoing government services support"
    "Document collection and tracking"
    "Recurring compliance coordination"
    "Task-based execution with status updates"]

   :service/trademark-registration
   ["Class selection and filing readiness checklist"
    "Application filing coordination"
    "Follow-up tracking and action-required handling"
    "Status updates and handover"]

   :service/uk-company-formation
   ["Incorporation requirements checklist"
    "Company formation coordination and filings"
    "Handover pack and next-steps guidance"]

   :service/us-company-formation
   ["Incorporation requirements checklist"
    "Company formation coordination and filings"
    "Handover pack and next-steps guidance"]

   :service/attestation-pakistan
   ["Document checklist and validation"
    "Attestation coordination and tracking"
    "Handover pack and next-steps guidance"]
   :service/attestation-india
   ["Document checklist and validation"
    "Attestation coordination and tracking"
    "Handover pack and next-steps guidance"]
   :service/attestation-oman
   ["Document checklist and validation"
    "Attestation coordination and tracking"
    "Handover pack and next-steps guidance"]
   :service/attestation-uae
   ["Document checklist and validation"
    "Attestation coordination and tracking"
    "Handover pack and next-steps guidance"]

   :service/premium-residency
   ["Eligibility and documentation checklist"
    "Application coordination and tracking"
    "Status updates and handover"]

   :service/investor-pr
   ["Eligibility and documentation checklist"
    "Application coordination and tracking"
    "Status updates and handover"]

   :service/company-liquidation
   ["Scope confirmation and closure plan"
    "Portal closures and final compliance checklist"
    "Clearance tracking and final handover"]})

(def ^:private service-title-by-id
  {:service/entrepreneur-license "Entrepreneur License (MISA)"
   :service/general-investment-license "General Investment License"
   :service/gcc-national-license "GCC National Registration"
   :service/rhq-license "Regional HQ (RHQ) License"
   :service/pro-services "PRO / Government Services"
   :service/trademark-registration "Trademark Registration"
   :service/uk-company-formation "UK Company Formation"
   :service/us-company-formation "US Company Formation"
   :service/attestation-pakistan "Pakistan Company Attestation & Documentation"
   :service/attestation-india "India Company Attestation & Documentation"
   :service/attestation-oman "Oman Company Attestation & Documentation"
   :service/attestation-uae "UAE Company Attestation & Documentation"
   :service/premium-residency "Saudi Premium Residency"
   :service/investor-pr "Investor Permanent Residency (PR)"
   :service/company-liquidation "Company Deregistration & Liquidation"})

(def ^:private service-client-title-by-id
  {:service/entrepreneur-license "Entrepreneur License"
   :service/general-investment-license "General Investment License"
   :service/gcc-national-license "GCC National Registration"
   :service/rhq-license "Regional HQ License"
   :service/pro-services "PRO / Government Services"
   :service/trademark-registration "Trademark Registration"
   :service/uk-company-formation "UK Company Formation"
   :service/us-company-formation "USA Company Formation"
   :service/attestation-pakistan "Pakistan Attestation & Documentation"
   :service/attestation-india "India Attestation & Documentation"
   :service/attestation-oman "Oman Attestation & Documentation"
   :service/attestation-uae "UAE Attestation & Documentation"
   :service/premium-residency "Saudi Premium Residency"
   :service/investor-pr "Investor Permanent Residency"
   :service/company-liquidation "Company Deregistration & Liquidation"})

(defn- client-facing-service-title
  [service-id]
  (or (get service-client-title-by-id service-id)
      (get service-title-by-id service-id)
      (name service-id)))

(def ^:private proposal-requirements-by-id
  {:service/entrepreneur-license
   ["Passport copy"
    "Proof of address"
    "Business activity brief (1–2 paragraphs)"
    "Shareholder details"
    "Preferred company name(s)"]

   :service/general-investment-license
   ["Foreign company registration documents"
    "Company ownership structure"
    "Audited financial statements (if applicable)"
    "Attestation/translation readiness (if applicable)"
    "Shareholder details"]

   :service/gcc-national-license
   ["GCC identity"
    "Shareholder details"
    "Preferred company name(s)"
    "Business activity selection"]

   :service/rhq-license
   ["Documents for 3 foreign entities (group)"
    "Attested corporate documents (where required)"
    "Arabic translations (where required)"
    "Group ownership structure"]

   :default
   ["Client identification details"
    "Scope confirmation"
    "Documents required for filings (as applicable)"]})

(defn- render-bullets
  [lines]
  (let [lines (->> (or lines [])
                   (map #(some-> % str str/trim))
                   (remove str/blank?)
                   vec)]
    (if (seq lines)
      (str/join "\n" (map (fn [l] (str "• " l)) lines))
      "")))

(defn- default-services-included
  [service-id objective client-notes]
  (let [bullets (or (get proposal-services-by-id service-id)
                    ["Requirements checklist and document tracking"
                     "Submission readiness support"
                     "Task-based execution with status updates"
                     "Handover and next steps"])
        reqs (or (get proposal-requirements-by-id service-id)
                 (get proposal-requirements-by-id :default)
                 [])
        payload {:objective (or objective "—")
                 :service {:id (subs (str service-id) 1)
                           :title (or (get service-title-by-id service-id) (name service-id))}
                 :deliverables (vec bullets)
                 :requirements (vec reqs)
                 :clientNotes (when (and (string? client-notes) (not (str/blank? (str/trim client-notes))))
                                (str/trim client-notes))}]
    (json/write-str payload)))

(defn- normalize-money
  [v]
  (cond
    (number? v) (double v)
    (string? v) (let [s (-> v str/trim (str/replace #"[^0-9.+-]" ""))]
                  (when-not (str/blank? s)
                    (try (double (Double/parseDouble s)) (catch Exception _ nil))))
    :else nil))

(defn- present-str
  [v]
  (let [s (some-> v str str/trim)]
    (when-not (str/blank? s) s)))

(defn- valid-service-id?
  [v]
  (and (keyword? v) (not= v :skip)))

(defn- validate-pricing
  [pricing]
  (when (map? pricing)
    (let [model (:model pricing)
          currency (or (present-str (:currency pricing)) "SAR")]
      (case model
        :fixed (when-let [total (normalize-money (:total pricing))]
                 {:model :fixed :currency currency :total total})
        :range (let [mn (normalize-money (:min pricing))
                     mx (normalize-money (:max pricing))]
                 (when (and (number? mn) (number? mx) (<= mn mx))
                   {:model :range :currency currency :min mn :max mx}))
        :custom (when-let [notes (present-str (:pricing-notes pricing))]
                  {:model :custom :currency currency :pricing-notes notes})
        nil))))

(defn- validate-deposit
  [deposit]
  (when (map? deposit)
    (let [typ (:type deposit)
          n (normalize-money (:value deposit))]
      (case typ
        :amount (when (and (number? n) (pos? n)) {:type :amount :value n})
        :percent (when (and (number? n) (<= 0 n 100)) {:type :percent :value n})
        nil))))

(defn- validate-milestones
  [milestones]
  (let [items (vec (or milestones []))
        valid (->> items
                   (keep (fn [m]
                           (when (map? m)
                             (let [label (present-str (:label m))
                                   typ (:type m)
                                   n (normalize-money (:value m))]
                               (case typ
                                 :amount (when (and label (number? n) (pos? n)) {:label label :type :amount :value n})
                                 :percent (when (and label (number? n) (<= 0 n 100)) {:label label :type :percent :value n})
                                 nil))))))
        valid (vec valid)]
    (when (= (count valid) (count items))
      valid)))

(defn- build-payment-plan
  [{:keys [pricing deposit milestones validity-days currency]}]
  (let [payload {:currency (or (present-str currency) "SAR")
                 :validityDays (long (or validity-days 15))
                 :pricing pricing
                 :deposit deposit
                 :milestones (vec (or milestones []))}]
    (json/write-str payload)))

(defn- missing-proposal-inputs
  [{:keys [service-id objective pricing milestones]}]
  (->> [(when-not (valid-service-id? service-id) "Service")
        (when-not (present-str objective) "Objective")
        (when-not pricing "Pricing model")
        (when (empty? (or milestones [])) "Milestones (≥ 1 required)")]
       (remove nil?)
       vec))

(defn- task-assignee-id
  [db task-id]
  (ffirst (d/q '[:find ?uid
                 :in $ ?tid
                 :where [?t :task/id ?tid]
                        [?t :task/assignee ?u]
                        [?u :user/id ?uid]]
               db task-id)))

(defn- user-id-by-username
  [db username]
  (when (and (string? username) (not (str/blank? (str/trim username))))
    (ffirst (d/q '[:find ?uid
                   :in $ ?un
                   :where [?u :user/username ?un]
                          [?u :user/id ?uid]]
                 db (str/trim username)))))

(defn- pull-client-notes
  [db client-id ws]
  (ffirst (d/q '[:find ?notes
                 :in $ ?cid ?ws
                 :where [?c :client/id ?cid]
                        [?c :client/workspace ?ws]
                        [(get-else $ ?c :client/notes \"\") ?notes]]
               db client-id ws)))

(defn- report-card-onboarding
  [state automation event]
  (let [conn (get-in state [:db :conn])
        payload (:event/payload event)
        typ (:report.card/type payload)
        ws (:workspace/id payload)
        task-id (:task/id payload)
        client-id (:client/id payload)
        fields-str (:report.card/fields payload)
        fields (safe-read-edn-map fields-str)]
    (when (and conn (contains? #{:report.card.type/onboarding :report.card.type/consultation} typ) task-id client-id (map? fields))
      (let [dead? (true? (or (:onboarding/dead-lead? fields)
                             (:dead-lead? fields)
                             (:lead/dead? fields)))
            reason (some-> (or (:onboarding/dead-lead-reason fields)
                               (:dead-lead/reason fields)
                               (:dead-lead-reason fields))
                           str
                           str/trim
                           not-empty)
            assignee-id (try
                          (let [db (d/db conn)]
                            (or (task-assignee-id db task-id)
                                (user-id-by-username db (or (some-> (get-in state [:config :site :intake-assignee-username]) str str/trim not-empty)
                                                            "huda"))))
                          (catch Exception _ nil))
            objective (present-str (:offer/objective fields))
            service-id (:service/id fields)
            client-notes (present-str (:notes/client-visible fields))
            internal-notes (present-str (:notes/internal fields))
            pricing (validate-pricing (:pricing/model fields))
            deposit (validate-deposit (:payment/deposit fields))
            milestones (validate-milestones (:payment/milestones fields))
            validity-days (let [v (:pricing/validity-days fields)]
                            (when (number? v) (long v)))
            base-actor (assoc (automation-actor automation) :actor/workspace ws)]
        (if dead?
          (let [existing (try
                           (pull-client-notes (d/db conn) client-id ws)
                           (catch Exception _ nil))
                suffix (str "Lead closed from onboarding report card.\n"
                            (when reason (str "Reason: " reason "\n"))
                            (when internal-notes (str "Notes: " internal-notes "\n")))
                next-notes (->> [(some-> existing str str/trim not-empty)
                                 (str/trim suffix)]
                                (remove nil?)
                                (str/join "\n\n"))]
            [{:action/id :cap/action/client-update
              :actor base-actor
              :input {:client/id client-id
                      :client/status :closed
                      :client/notes next-notes}}])
          (let [missing (missing-proposal-inputs {:service-id service-id
                                                  :objective objective
                                                  :pricing pricing
                                                  :milestones milestones})
                needs-followup? (seq missing)
                followup-desc (str "Proposal inputs are incomplete.\n\nMissing:\n"
                                   (str/join "\n" (map (fn [x] (str "- " x)) missing))
                                   "\n\nOpen the client onboarding task and submit the onboarding report card again with complete pricing + milestones.")
            deliver-desc (str "A proposal is ready to be delivered to the client.\n\n"
                                  "How to retrieve the PDF:\n"
                                  "• Telegram: /docs → pick client → Generate proposal\n"
                                  "• The proposal is immutable and re-generating will return the same file when unchanged.\n\n"
                                  "Next: deliver the proposal and record the response using the Proposal response report card.")]
            (if needs-followup?
              (cond-> [{:action/id :cap/action/task-create
                        :actor base-actor
                        :input {:task/title "Complete proposal inputs"
                                :task/description followup-desc
                                :task/status :todo
                                :task/priority :high
                                :task/client client-id
                                :task/automation-key (str "proposal-inputs:" (or (:report.card/automation-key payload) task-id))}}]
                assignee-id
                (update-in [0 :input] assoc :task/assignee assignee-id))
              (let [services-json (default-services-included service-id objective client-notes)
                    payment-json (build-payment-plan {:pricing pricing
                                                     :deposit deposit
                                                     :milestones milestones
                                                     :validity-days validity-days
                                                     :currency (or (:currency pricing) "SAR")})]
                (cond-> [{:action/id :cap/action/doc-pack-upsert
                          :actor base-actor
                          :input {:client/id client-id
                                  :doc.pack/company-name "Dar El Wasl"
                                  :doc.pack/services-included services-json
	                                  :doc.pack/payment-plan payment-json}}
                         {:action/id :cap/action/proposal-generate
                          :actor base-actor
                          :input {:client/id client-id}}
	                         {:action/id :cap/action/task-create
	                          :actor base-actor
                          :input {:task/title "Deliver proposal"
                                  :task/description deliver-desc
                                  :task/status :todo
                                  :task/priority :medium
                                  :task/client client-id
                                  :task/report-card-type :report.card.type/proposal-response
                                  :task/automation-key (str "proposal-delivery:" (or (:report.card/automation-key payload) task-id))}}]
                  assignee-id
                  (update-in [2 :input] assoc :task/assignee assignee-id))))))))))

(defn- telegram-onboarding-task
  [_state {:keys [id] :as automation} event]
  (let [user-id (get-in event [:event/payload :user/id])]
    (when (and user-id (not (str/blank? (str user-id))))
      [{:action/id :cap/action/task-create
        :actor (automation-actor automation)
        :input {:task/title "Telegram linked — try /tasks"
                :task/description "You successfully linked Telegram. Try: /tasks and /new <title> | <desc>."
                :task/status :todo
                :task/priority :low
                :task/client clients/default-client-id
                :task/assignee user-id
                :task/automation-key (str (name id) ":" user-id)}}])))

(defn- task-telegram-notify
  [state automation event]
  (let [conn (get-in state [:db :conn])
        task-id (get-in event [:event/payload :task/id])
        event-type (:event/type event)]
    (when (and conn task-id)
      (try
        (let [db (d/db conn)
              task (ffirst (d/q '[:find (pull ?t [:task/id :task/title :task/status :task/due-date
                                                   {:task/client [:client/name]}
                                                   {:task/assignee [:user/id]}])
                                 :in $ ?tid
                                 :where [?t :task/id ?tid]]
                               db task-id))
              assignee-id (get-in task [:task/assignee :user/id])]
          (when assignee-id
            (let [title (or (:task/title task) "Task")
                  status (some-> (:task/status task) name)
                  due (or (some-> (:task/due-date task) str) "—")
                  client (or (get-in task [:task/client :client/name]) "—")
                  short-id (subs (str task-id) 0 8)
                  header (case event-type
                           :task/assigned "Task assigned"
                           :task/status-changed "Task status updated"
                           :task/due-changed "Task due date updated"
                           "Task updated")
                  text (str header " (" short-id ")\n"
                            title "\n"
                            "Client: " client "\n"
                            "Status: " (or status "—") "\n"
                            "Due: " due)
                  message-key (str "task:" (name event-type) ":" task-id ":" (or status "na") ":" due)]
              [{:action/id :cap/action/telegram-notify
                :actor (automation-actor automation)
                :input {:user/id assignee-id
                        :telegram/text text
                        :telegram/message-key message-key}}])))
        (catch Exception e
          (log/warn e "Failed to derive task telegram notification")
          [])))))

(def ^:private handlers
  {:cap/automation.handler/telegram-onboarding-task telegram-onboarding-task
   :cap/automation.handler/report-card-onboarding report-card-onboarding
   :cap/automation.handler/task-telegram-notify task-telegram-notify})

(defn derive-invocations
  "Return a vector of action invocations for this event. Rules are code-first
  for now: the registry selects a handler, and handlers emit action invocations."
  [state event]
  (let [automations (read-registry)
        matches (matching-automations automations event)]
    (->> matches
         (mapcat (fn [automation]
                   (let [handler-id (:handler automation)
                         handler (get handlers handler-id)]
                     (cond
                       (nil? handler)
                       (do
                         (log/warn "Missing automation handler" {:handler handler-id :automation (:id automation)})
                         [])

                       :else
                       (try
                         (or (handler state automation event) [])
                         (catch Exception e
                           (log/warn e "Automation handler crashed" {:automation (:id automation)})
                           []))))))
         (remove nil?)
         vec)))
