(ns darelwasl.site.http
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.actions :as actions]
            [darelwasl.content :as content]
            [darelwasl.documents :as documents]
            [darelwasl.files :as files]
            [darelwasl.outbox :as outbox]
            [darelwasl.service-cases :as service-cases]
            [darelwasl.site.templates :as templates]
            [darelwasl.workspace :as workspace]
            [ring.util.codec :as codec]
            [ring.util.response :as resp]))

(defn- verify-facts
  [db workspace-id {:keys [document] :as verification}]
  (when (and (:document/valid? verification) (map? document))
    (let [ws (or (get-in document [:document/client :client/workspace])
                 (workspace/resolve-id workspace-id))
          doc-type (:document/type document)
          subject-type (:document/subject-type document)
          subject-id (:document/subject-id document)
          base [[:document (or (:entity/ref document) "—")]
                [:type (some-> doc-type name)]
                [:issued (or (:document/issued-at document) "—")]]
          pull (fn [pattern eid]
                 (when eid (d/pull db pattern eid)))]
      (case subject-type
        :invoice
        (let [eid (ffirst (d/q '[:find ?e
                                 :in $ ?id ?ws
                                 :where [?e :invoice/id ?id]
                                        [?e :invoice/workspace ?ws]]
                               db subject-id ws))
              inv (pull [:invoice/number :invoice/total-amount :invoice/currency :invoice/due-at] eid)]
          (cond-> base
            (:invoice/number inv) (conj [:invoice (str (:invoice/number inv))])
            (:invoice/total-amount inv) (conj [:amount (str (:invoice/total-amount inv) " " (or (:invoice/currency inv) ""))])
            (:invoice/due-at inv) (conj [:due (str (:invoice/due-at inv))])))

        :payment
        (let [eid (ffirst (d/q '[:find ?e
                                 :in $ ?id ?ws
                                 :where [?e :payment/id ?id]
                                        [?e :payment/workspace ?ws]]
                               db subject-id ws))
              pay (pull [:payment/amount :payment/currency :payment/paid-at :payment/reference] eid)]
          (cond-> base
            (:payment/amount pay) (conj [:amount (str (:payment/amount pay) " " (or (:payment/currency pay) ""))])
            (:payment/paid-at pay) (conj [:paid (str (:payment/paid-at pay))])
            (:payment/reference pay) (conj [:reference (str (:payment/reference pay))])))

        :agreement
        (let [eid (ffirst (d/q '[:find ?e
                                 :in $ ?id ?ws
                                 :where [?e :agreement/id ?id]
                                        [?e :agreement/workspace ?ws]]
                               db subject-id ws))
              agr (pull [:agreement/number :agreement/title :agreement/accepted-at] eid)]
          (cond-> base
            (:agreement/number agr) (conj [:agreement (str (:agreement/number agr))])
            (:agreement/title agr) (conj [:title (str (:agreement/title agr))])
            (:agreement/accepted-at agr) (conj [:accepted (str (:agreement/accepted-at agr))])))

        ;; default subject types
        base))))

(def ^:private supported-lang-prefixes
  {"ar" :ar
   "ur" :ur})

(defn- normalize-base-path
  [base-path]
  (let [b (str/trim (str (or base-path "")))]
    (cond
      (or (str/blank? b) (= b "/")) ""
      (str/starts-with? b "/") (str/replace b #"/+$" "")
      :else (str "/" (str/replace b #"/+$" "")))))

(defn- with-base
  [base-path href]
  (let [base (normalize-base-path base-path)
        h (str (or href ""))]
    (cond
      (str/blank? base) h
      (str/blank? h) h
      (str/starts-with? h base) h
      (str/starts-with? h "/") (str base h)
      :else (str base "/" h))))

(defn- strip-trailing-slash
  [raw-path]
  (if (and raw-path (not= raw-path "/") (str/ends-with? raw-path "/"))
    (subs raw-path 0 (dec (count raw-path)))
    raw-path))

(defn- parse-lang-prefix
  "Returns {:lang <kw|nil> :prefix <string> :path <string>}."
  [clean-path]
  (let [parts (->> (str/split (or clean-path "/") #"/")
                   (remove str/blank?))
        [first-seg & more] parts
        lang (get supported-lang-prefixes first-seg)]
    (if lang
      {:lang lang
       :prefix (str "/" first-seg)
       :path (str "/" (str/join "/" more))}
      {:lang nil
       :prefix ""
       :path clean-path})))

(defn- static-path?
  [path]
  (or (str/starts-with? path "/css/")
      (str/starts-with? path "/images/")
      (str/starts-with? path "/js/")
      (= path "/robots.txt")
      (= path "/sitemap.xml")
      (= path "/logo.svg")
      (= path "/logo.jpg")
      (= path "/preview-annotate.js")))

(defn- decode-form-body
  [request]
  (try
    (when-let [body (:body request)]
      (let [raw (slurp body)]
        (codec/form-decode (or raw ""))))
    (catch Exception e
      (log/warn e "Failed to decode site form body")
      {})))

(defn- append-lead!
  [lead]
  (try
    (let [line (str (pr-str lead) "\n")]
      (.mkdirs (java.io.File. "data"))
      (spit "data/public-leads.edn" line :append true))
    (catch Exception e
      (log/warn e "Failed to persist public lead"))))

(defn- admin-chat-ids
  "Returns a vector of Telegram chat ids for users with :role/admin."
  [db]
  (->> (d/q '[:find ?chat
              :where [?u :user/roles :role/admin]
                     [?u :user/telegram-chat-id ?chat]]
            db)
       (map first)
       (map str)
       (remove str/blank?)
       distinct
       vec))

(defn- lead->notification-text
  [lead]
  (let [v (fn [k] (let [s (some-> (get lead k) str str/trim)]
                   (if (str/blank? (str s)) "-" s)))]
    (str "New consultation lead\n\n"
         "Lead ID: " (v :lead/id) "\n"
         "Created: " (v :lead/created-at) "\n"
         "Language: " (v :lead/lang) "\n\n"
         "Name: " (v :lead/contact-name) "\n"
         "Company: " (v :lead/company-name) "\n"
         "Primary interest: " (v :lead/primary-interest) "\n"
         "Document readiness: " (v :lead/docs-status) "\n"
         "Preferred contact: " (v :lead/preferred-contact) "\n\n"
         "Goal:\n" (v :lead/goal) "\n\n"
         "Activities:\n" (v :lead/activities) "\n\n"
         "Ownership: " (v :lead/ownership) "\n"
         "Residency: " (v :lead/residency) "\n"
         "Target start month: " (v :lead/start-month) "\n"
         "Preferred language: " (v :lead/preferred-lang) "\n\n"
         "Email: " (v :lead/email) "\n"
         "Phone: " (v :lead/phone) "\n\n"
         "IP: " (v :lead/ip) "\n"
         "User-Agent: " (v :lead/user-agent))))

(defn- enqueue-lead-notifications!
  [conn config lead]
  (try
    (let [lead-id (str (:lead/id lead))
          text (lead->notification-text lead)
          email-to (or (some-> (get-in config [:site :lead-email-to]) str str/trim not-empty)
                       "admin@darelwasl.com")]
      (when-let [err (:error (outbox/enqueue! conn {:integration :integration/email
                                                    :payload {:to email-to
                                                              :subject "New consultation lead"
                                                              :text text}
                                                    :dedupe-key (str "public-lead:" lead-id ":email")}))]
        (log/warn "Lead email enqueue failed" {:error err}))
      (doseq [chat-id (admin-chat-ids (d/db conn))]
        (let [dedupe (str "public-lead:" lead-id ":tg:" chat-id)]
          (when-let [err (:error (outbox/enqueue! conn {:integration :integration/telegram
                                                        :payload {:chat-id chat-id
                                                                  :text text
                                                                  :message-key dedupe}
                                                        :dedupe-key dedupe}))]
            (log/warn "Lead Telegram enqueue failed" {:chat-id chat-id :error err})))))
    (catch Exception e
      (log/warn e "Failed to enqueue lead notifications"))))

(defn- portal-security-headers
  [response]
  (-> response
      (resp/header "Cache-Control" "no-store")
      (resp/header "Pragma" "no-cache")
      (resp/header "Referrer-Policy" "no-referrer")
      (resp/header "X-Robots-Tag" "noindex, nofollow, noarchive")))

(defn- enqueue-portal-access-email!
  [conn lead portal-url]
  (let [email (some-> (:lead/email lead) str str/trim not-empty)]
    (when email
      (let [lead-id (str (:lead/id lead))
            subject "Your Dar El Wasl client portal link"
            text (str "Your secure client portal is ready.\n\n"
                      "Portal link:\n" portal-url "\n\n"
                      "You can view your status and download issued documents from there.\n\n"
                      "Important: do not share this link. It grants access to your portal.\n\n"
                      "If you did not request this, you can ignore this email.\n\n"
                      "Reference: " lead-id)
            dedupe (str "public-portal:" lead-id ":email")]
        (outbox/enqueue! conn {:integration :integration/email
                               :payload {:to email
                                         :subject subject
                                         :text text}
                               :dedupe-key dedupe})))))

(declare user-id-by-username system-actor request-public-base-url)

;; Portal is intentionally view/download only. Reschedule requests were removed.

(defn- system-actor
  [workspace-id]
  {:actor/type :actor.type/system
   :actor/surface :surface/http
   :actor/workspace (workspace/resolve-id workspace-id)})

(defn- user-id-by-username
  [db username]
  (when-not (str/blank? (str username))
    (ffirst (d/q '[:find ?uid
                   :in $ ?u
                   :where [?e :user/username ?u]
                          [?e :user/id ?uid]]
                 db (str/trim (str username))))))

(def ^:private staff-token-valid-days 30)

(defn- staff-client-by-magic-link
  [db client-ref token]
  (let [client-ref (some-> client-ref str str/trim)
        token (some-> token str str/trim)
        eid (when-not (str/blank? (str client-ref))
              (ffirst (d/q '[:find ?e
                             :in $ ?ref
                             :where [?e :entity/ref ?ref]
                                    [?e :client/id _]]
                           db client-ref)))]
    (when eid
      (let [c (d/pull db [:client/id
                          :client/name
                          :client/workspace
                          :client/staff-magic-token
                          :client/staff-magic-token-created-at
                          :client/portal-token
                          :client/portal-token-created-at
                          :entity/ref]
                     eid)
            ok? (= token (some-> (:client/staff-magic-token c) str str/trim))
            created (:client/staff-magic-token-created-at c)
            max-age-seconds (* 86400 staff-token-valid-days)
            fresh? (try
                     (if (instance? java.util.Date created)
                       (let [age (/ (- (.toEpochMilli (java.time.Instant/now)) (.getTime ^java.util.Date created)) 1000.0)]
                         (<= age max-age-seconds))
                       false)
                     (catch Exception _ false))]
        (when (and ok? fresh?)
          c)))))

(defn- portal-document-download-response
  [conn storage-dir {:keys [client-ref token doc-ref download?]}]
  (let [client-ref (some-> client-ref codec/url-decode str str/trim)
        token (some-> token codec/url-decode str str/trim)
        doc-ref (some-> doc-ref codec/url-decode str str/trim)]
    (cond
      (str/blank? client-ref) nil
      (str/blank? token) nil
      (str/blank? doc-ref) nil
      :else
      (let [portal (service-cases/public-portal-read conn {:client-ref client-ref :token token})]
        (when (and (map? portal) (not (:error portal)))
          (let [db (d/db conn)
                ws (get-in portal [:client :client/workspace])
                client-id (get-in portal [:client :client/id])
                doc-eid (ffirst (d/q '[:find ?d
                                       :in $ ?ref ?ws
                                       :where [?d :entity/ref ?ref]
                                              [?d :document/workspace ?ws]]
                                     db doc-ref ws))
                doc (when doc-eid (d/pull db [:document/type
                                              {:document/client [:client/id]}
                                              {:document/file [:file/id]}]
                                          doc-eid))
                ok? (= client-id (get-in doc [:document/client :client/id]))
                file-id (get-in doc [:document/file :file/id])
                file-res (when (and ok? file-id) (files/fetch-file conn file-id ws))
                {:file/keys [storage-path mime name]} (:file file-res)
                path (when (and (string? storage-path) (not (str/blank? storage-path)))
                       (.getPath (io/file storage-dir storage-path)))]
            (when (and ok? path (.exists (io/file path)))
                  (let [filename (or name "document.pdf")
                        disp (if download?
                               "attachment"
                               "inline")]
                    (-> (resp/file-response path)
                        (resp/content-type (or mime "application/pdf"))
                        (resp/header "Content-Disposition" (str disp "; filename=\"" filename "\""))))))))))))

(defn- client-id-by-email
  [db workspace-id email]
  (when-not (str/blank? (str email))
    (ffirst (d/q '[:find ?id
                   :in $ ?ws ?email
                   :where [?e :client/workspace ?ws]
                          [?e :client/email ?email]
                          [?e :client/id ?id]]
                 db (workspace/resolve-id workspace-id) (str/trim (str email))))))

(defn- client-id-by-phone
  [db workspace-id phone]
  (when-not (str/blank? (str phone))
    (ffirst (d/q '[:find ?id
                   :in $ ?ws ?phone
                   :where [?e :client/workspace ?ws]
                          [?e :client/phone ?phone]
                          [?e :client/id ?id]]
                 db (workspace/resolve-id workspace-id) (str/trim (str phone))))))

(defn- ensure-intake-client!
  [state lead]
  (let [conn (get-in state [:db :conn])
        db (when conn (d/db conn))
        ws (workspace/default-id)
        email (:lead/email lead)
        phone (:lead/phone lead)
        contact-name (some-> (:lead/contact-name lead) str str/trim not-empty)
        company-name (some-> (:lead/company-name lead) str str/trim not-empty)
        existing (or (client-id-by-email db ws email)
                     (client-id-by-phone db ws phone))]
    (if existing
      {:client/id existing}
      (let [name (or (some-> phone str str/trim not-empty)
                     (some-> email str str/trim not-empty)
                     "Website lead")
            display-name (or company-name contact-name name "Website lead")
            input {:client/name display-name
                   :client/status :lead
                   :client/channel (cond
                                    (not (str/blank? phone)) :whatsapp
                                    (not (str/blank? email)) :email
                                    :else :whatsapp)
                   :client/phone (when-not (str/blank? phone) phone)
                   :client/email (when-not (str/blank? email) email)
                   :client/notes (str "Source: website consultation\n"
                                      "Name: " (or contact-name "-") "\n"
                                      "Company: " (or company-name "-") "\n"
                                      "Primary interest: " (or (:lead/primary-interest lead) "-") "\n"
                                      "Document readiness: " (or (:lead/docs-status lead) "-") "\n"
                                      "Preferred contact: " (or (:lead/preferred-contact lead) "-") "\n"
                                      "Goal: " (or (:lead/goal lead) "-") "\n\n"
                                      "Activities: " (or (:lead/activities lead) "-") "\n"
                                      "Ownership: " (or (:lead/ownership lead) "-") "\n"
                                      "Residency: " (or (:lead/residency lead) "-") "\n"
                                      "Target start month: " (or (:lead/start-month lead) "-") "\n"
                                      "Preferred language: " (or (:lead/preferred-lang lead) "-"))}
            res (actions/execute! state {:action/id :cap/action/client-create
                                        :actor (system-actor ws)
                                        :input input})
            err (:error res)
            client (get-in res [:result :client])]
        (if err
          {:error err}
          {:client/id (:client/id client)})))))

(defn- create-intake-task!
  [state lead client-id {:keys [portal-url consultation-url]}]
  (let [conn (get-in state [:db :conn])
        db (when conn (d/db conn))
        ws (workspace/default-id)
        username (or (some-> (get-in state [:config :site :intake-assignee-username]) str str/trim not-empty)
                     "huda")
        assignee-id (user-id-by-username db username)
        actor (system-actor ws)
        lead-id (:lead/id lead)
        desc (str "New website consultation lead\n\n"
                  "Links\n"
                  (when portal-url (str "- Client portal: " portal-url "\n"))
                  (when consultation-url (str "- Consultation PDF: " consultation-url "\n"))
                  "\n"
                  "Goal\n"
                  "- Run the consultation using the Consultation PDF as the client-facing worksheet\n"
                  "- Fill the consultation form link during the meeting to issue the proposal immediately\n"
                  "- Mark as dead lead if not viable\n\n"
                  "What to capture (minimum)\n"
                  "- Whether you contacted them\n"
                  "- Whether a call/meeting is scheduled (date/time)\n"
                  "- Primary service interest + budget signal\n"
                  "- Proposal inputs: objective, pricing model, deposit (optional), milestones (≥ 1)\n"
                  "- Any notes that affect scope or delivery\n\n"
                  "Lead details\n"
                  "Name: " (or (:lead/contact-name lead) "-") "\n"
                  "Company: " (or (:lead/company-name lead) "-") "\n"
                  "Primary interest: " (or (:lead/primary-interest lead) "-") "\n"
                  "Document readiness: " (or (:lead/docs-status lead) "-") "\n"
                  "Preferred contact: " (or (:lead/preferred-contact lead) "-") "\n\n"
                  "Goal:\n" (or (:lead/goal lead) "-") "\n\n"
                  "Activities:\n" (or (:lead/activities lead) "-") "\n\n"
                  "Ownership: " (or (:lead/ownership lead) "-") "\n"
                  "Residency: " (or (:lead/residency lead) "-") "\n"
                  "Start month: " (or (:lead/start-month lead) "-") "\n"
                  "Preferred language: " (or (:lead/preferred-lang lead) "-") "\n\n"
                  "Email: " (or (:lead/email lead) "-") "\n"
                  "Phone: " (or (:lead/phone lead) "-"))
        input {:task/title "Onboard consultation client"
               :task/description desc
               :task/status :todo
               :task/priority :medium
               :task/client client-id
               :task/assignee assignee-id
               :task/report-card-type :report.card.type/consultation
               :task/automation-key (str "site-lead:onboarding:" lead-id)}]
    (cond
      (nil? assignee-id) {:error {:status 500 :message (str "Assignee user not found: " username)}}
      :else
      (actions/execute! state {:action/id :cap/action/task-create
                               :actor actor
                               :input input}))))

(defn- handle-consultation-submit
  [state {:keys [base-path prefix lang]} request]
  (let [params (decode-form-body request)
        website (str/trim (get params "website" ""))
        spam? (not (str/blank? website))
        contact-name (str/trim (get params "contact_name" ""))
        company-name (str/trim (get params "company_name" ""))
        primary-interest (str/trim (get params "primary_interest" ""))
        goal (str/trim (get params "goal" ""))
        activities (str/trim (get params "activities" ""))
        email (str/trim (get params "email" ""))
        phone (str/trim (get params "phone" ""))
        ownership (str/trim (get params "ownership" ""))
        residency (str/trim (get params "residency" ""))
        start-month (str/trim (get params "start_month" ""))
        docs-status (str/trim (get params "docs_status" ""))
        preferred-contact (str/trim (get params "preferred_contact" ""))
        preferred-lang (str/trim (or (get params "preferred_lang")
                                     (get params "lang")
                                     ""))
        ok? (and (not (str/blank? primary-interest))
                 (not (str/blank? activities))
                 (or (not (str/blank? email))
                     (not (str/blank? phone))))
        qs (cond
             spam? "sent=1"
             (not ok?) "error=1"
             (not (str/blank? email)) "sent=1&portal=1"
             :else "sent=1")
        location (with-base base-path (str prefix "/contact?" qs "#consultation"))]
    ;; Honeypot: pretend success, do not persist.
    (when (and ok? (not spam?))
      (let [lead {:lead/id (java.util.UUID/randomUUID)
                  :lead/created-at (str (java.time.Instant/now))
                  :lead/source "public-site"
                  :lead/lang (or (some-> lang name) "en")
                  :lead/ip (:remote-addr request)
                  :lead/user-agent (get-in request [:headers "user-agent"])
                  :lead/contact-name contact-name
                  :lead/company-name company-name
                  :lead/primary-interest primary-interest
                  :lead/goal goal
                  :lead/activities activities
                  :lead/ownership ownership
                  :lead/residency residency
                  :lead/start-month start-month
                  :lead/docs-status docs-status
                  :lead/preferred-contact preferred-contact
                  :lead/email email
                  :lead/phone phone
                  :lead/preferred-lang preferred-lang}]
        (append-lead! lead)
        (when-let [conn (get-in state [:db :conn])]
          (enqueue-lead-notifications! conn (:config state) lead))
        (try
          (let [client-res (ensure-intake-client! state lead)]
            (when-let [cid (:client/id client-res)]
              (when-let [conn (get-in state [:db :conn])]
                (let [ws (workspace/default-id)
                      token-res (service-cases/ensure-client-portal-token! conn cid ws)
                      staff-res (service-cases/ensure-client-staff-magic-token! conn cid ws)
                      token (:portal/token token-res)
                      client-ref (:client/ref token-res)
                      staff-token (:staff/token staff-res)
                      portal-url (when (and token client-ref)
                                   (str (request-public-base-url request)
                                        (with-base base-path (str "/portal/" client-ref "/" token))))]
		                  ;; Store consultation brief + sections used by the consultation PDF.
		                  (let [brief (json/write-str {:contactName contact-name
		                                               :companyName company-name
		                                               :primaryInterest primary-interest
		                                               :goal goal
		                                               :activities activities
		                                               :ownership ownership
		                                               :residency residency
		                                               :startMonth start-month
		                                               :docsStatus docs-status
		                                               :preferredLang preferred-lang
		                                               :preferredContact preferred-contact
		                                               :email email
		                                               :phone phone})
		                        service-chip (case primary-interest
		                                       "saudi-setup" "Saudi setup"
		                                       "pro-ops" "Operations"
		                                       "trademark" "Trademark"
		                                       "attestation" "Attestation"
		                                       "Tailored")
		                        sections (json/write-str [{:type "hero"
		                                                  :title "CONSULTATION PACK"
		                                                  :refLine "Before your proposal"
		                                                  :subtitle "A focused meeting to align scope and milestones. Your proposal is issued to your portal today."
		                                                  :chips [service-chip "Clear scope" "Transparent fees" "Proposal today"]
		                                                  :backgroundSvg "saudi-hero"}
		                                                 {:type "icon-grid"
		                                                  :title "What we’ll cover"
		                                                  :items [{:icon "checklist" :label "Your goals" :desc "Clarify your objectives and setup preferences."}
		                                                          {:icon "documents" :label "Key details" :desc "Activities, ownership profile, and timeline."}
		                                                          {:icon "process" :label "Scope" :desc "Services included and expected deliverables."}
		                                                          {:icon "process" :label "Fees & milestones" :desc "Agree fees and the payment schedule."}]}])
		                        actor (system-actor ws)]
	                    (actions/execute! state {:action/id :cap/action/doc-pack-upsert
	                                             :actor actor
	                                             :input {:client/id cid
                                                     :doc.pack/company-name "Dar El Wasl"
                                                     :doc.pack/consultation-brief brief
                                                     :doc.pack/sections sections}})
                    (let [doc-res (actions/execute! state {:action/id :cap/action/document-issue
                                                          :actor actor
                                                          :input {:client/id cid
                                                                  :document/type :consultation}})
                          consultation-url (get-in doc-res [:result :file :file/url])
                          task-res (create-intake-task! state lead cid {:portal-url portal-url
                                                                        :consultation-url consultation-url})
                          task (get-in task-res [:result :task])
                          task-id (:task/id task)
                          assignee-id (get-in task [:task/assignee :user/id])
                          meet-url (when (and client-ref staff-token task-id)
                                     (str (request-public-base-url request)
                                          (with-base base-path (str "/meet/" client-ref "/" staff-token "?task=" task-id))))
                          updated-desc (str (or (:task/description task) "")
                                            "\n\nMeeting form (staff):\n"
                                            (or meet-url "-"))
                          _ (when (and task-id meet-url)
                              (actions/execute! state {:action/id :cap/action/task-update
                                                       :actor actor
                                                       :input {:task/id task-id
                                                               :task/title (:task/title task)
                                                               :task/description updated-desc
                                                               :task/priority (:task/priority task)}}))
                          _ (when (and assignee-id meet-url)
                              (actions/execute! state {:action/id :cap/action/telegram-notify
                                                       :actor actor
                                                       :input {:user/id assignee-id
                                                               :telegram/text (str "New consultation lead\n\n"
                                                                                   "Meeting form:\n" meet-url "\n\n"
                                                                                   (when portal-url (str "Client portal:\n" portal-url "\n\n"))
                                                                                   (when consultation-url (str "Consultation PDF:\n" consultation-url)))
                                                               :telegram/message-key (str "lead:" (str (:lead/id lead)))}}))]
                      (when portal-url
                        (enqueue-portal-access-email! conn lead portal-url))))))))
          (catch Exception e
            (log/warn e "Failed to create intake client/task")))))
    (when ok?
      (log/info "Public consultation lead received"
                {:lang lang
                 :email (when-not (str/blank? email) "<provided>")
                 :phone (when-not (str/blank? phone) "<provided>")}))
    (-> (resp/redirect location)
        (assoc :status 303))))

(defn- content-type-for-path
  [path]
  (let [p (str (or path ""))]
    (cond
      (str/ends-with? p ".css") "text/css; charset=utf-8"
      (str/ends-with? p ".js") "application/javascript; charset=utf-8"
      (str/ends-with? p ".svg") "image/svg+xml"
      (str/ends-with? p ".png") "image/png"
      (or (str/ends-with? p ".jpg") (str/ends-with? p ".jpeg")) "image/jpeg"
      (str/ends-with? p ".webp") "image/webp"
      (str/ends-with? p ".xml") "application/xml; charset=utf-8"
      (str/ends-with? p ".txt") "text/plain; charset=utf-8"
      :else nil)))

(defn- public-logo-response
  "Serve the logo from the file library when configured; otherwise return nil.
  This lets the public site pick up a new logo without a redeploy."
  [conn config]
  (let [file-id (some-> (get-in config [:site :logo-file-id]) str str/trim not-empty)
        storage-dir (get-in config [:files :storage-dir])]
    (when (and file-id (string? storage-dir) (not (str/blank? storage-dir)))
      (let [res (files/fetch-file conn file-id)
            {:file/keys [storage-path mime]} (:file res)
            safe-mime? (and (string? mime) (str/starts-with? mime "image/"))
            path (when (and safe-mime? (string? storage-path) (not (str/blank? storage-path)))
                   (.getPath (io/file storage-dir storage-path)))]
        (when (and path (.exists (io/file path)))
          (-> (resp/file-response path)
              (resp/content-type (or mime "application/octet-stream"))
              (resp/header "Cache-Control" "no-cache")))))))

(defn- maybe-no-cache
  "Avoid clients getting stuck on stale CSS/JS/logo during rapid iterations."
  [path response]
  (if (or (str/starts-with? path "/css/")
          (str/starts-with? path "/js/")
          (str/ends-with? path ".svg"))
    (resp/header response "Cache-Control" "no-cache")
    response))

(defn- request-public-base-url
  "Derives the public base URL from reverse-proxy headers."
  [request]
  (let [headers (into {} (for [[k v] (:headers request)] [(str/lower-case (name k)) v]))
        proto (or (get headers "x-forwarded-proto")
                  (some-> (:scheme request) name)
                  "http")
        host (or (get headers "x-forwarded-host")
                 (get headers "host")
                 (:server-name request)
                 "localhost")]
    (str proto "://" host)))

(defn- content-context
  [conn]
  (let [data (content/list-content-v2 conn)
        {:keys [error businesses contacts]} data]
    (if error
      {:error error}
      {:contact (templates/select-contact businesses contacts)})))

(defn handle-request
  [{:keys [db config]} request]
  (let [start (System/nanoTime)
        conn (:conn db)
        base-path (or (get-in config [:site :base-path]) "")
        raw-path (:uri request)
        query (codec/form-decode (or (:query-string request) ""))
        clean-path (strip-trailing-slash raw-path)
        {:keys [lang prefix path]} (parse-lang-prefix clean-path)
        method (:request-method request)
        public-base-url (request-public-base-url request)]
    (try
      (let [response
            (cond
              (and (= method :get) (= path "/logo.svg"))
              (or (public-logo-response conn config)
                  (let [static-resp (resp/file-response "logo.svg" {:root "public"})]
                    (if static-resp
                      (maybe-no-cache path (resp/content-type static-resp "image/svg+xml"))
                      (templates/public-not-found {:public-base-url public-base-url
                                                   :base-path base-path
                                                   :lang lang
                                                   :path path}))))

              (and (= method :post) (= path "/contact"))
              (handle-consultation-submit {:db db :config config}
                                          {:base-path base-path :prefix prefix :lang lang}
                                          request)

              (= path "/health")
              {:status 200
               :headers {"Content-Type" "text/plain; charset=utf-8"}
               :body "ok"}

	              (and (= method :get)
	                   (or (= path "/verify")
	                       (str/starts-with? path "/verify/")))
              (let [[_ ref-path code-path] (re-matches #"/verify/([^/]+)/([^/]+)" path)
                    ref (some-> (or ref-path
                                    (get query "ref")
                                    (get query "document"))
                                codec/url-decode
                                str
                                str/trim)
                    code (some-> (or code-path
                                     (get query "code")
                                     (get query "verification"))
                                 codec/url-decode
                                 str
                                 str/trim)
                    verification (when (and (not (str/blank? ref)) (not (str/blank? code)))
                                   (documents/verify-document! {:db db :config config}
                                                              {:input {:document/ref ref
                                                                       :document/verification-code code}
                                                               :actor nil}))
                    facts (when verification (verify-facts (d/db conn) (workspace/default-id) verification))
                    verification (cond
                                   (nil? verification) {:valid? nil}
                                   (:error verification) {:valid? false}
                                   :else {:valid? (:document/valid? verification)
                                          :facts facts})]
	                (templates/public-verify {:public-base-url public-base-url
	                                          :base-path base-path
	                                          :lang lang
	                                          :path (str prefix "/verify")
	                                          :query query
	                                          :verification verification}))

	              (and (= method :get)
	                   (str/starts-with? path "/portal/")
	                   (re-matches #"/portal/([^/]+)/([^/]+)/doc/([^/]+)" path))
		              (let [[_ client-ref token doc-ref] (re-matches #"/portal/([^/]+)/([^/]+)/doc/([^/]+)" path)
		                    download? (= "1" (get query "download"))
		                    storage-dir (get-in config [:files :storage-dir])
		                    dl (when (and conn (string? storage-dir) (not (str/blank? storage-dir)))
		                         (portal-document-download-response conn storage-dir {:client-ref client-ref
		                                                                              :token token
		                                                                              :doc-ref doc-ref
		                                                                              :download? download?}))]
		                (portal-security-headers
		                 (or dl
		                     (templates/public-not-found {:public-base-url public-base-url
		                                                  :base-path base-path
		                                                  :lang lang
	                                                  :path path}))))

	              (and (= method :get)
	                   (str/starts-with? path "/meet/"))
	              (let [[_ client-ref token] (re-matches #"/meet/([^/]+)/([^/]+)" path)
	                    task-id (some-> (get query "task") codec/url-decode str str/trim)
	                    db0 (when conn (d/db conn))
	                    staff-client (when (and db0 client-ref token)
	                                   (staff-client-by-magic-link db0 client-ref token))]
	                (portal-security-headers
	                 (if (and staff-client (not (str/blank? task-id)))
	                   (templates/staff-consultation-form {:public-base-url public-base-url
	                                                       :base-path base-path
	                                                       :lang lang
	                                                       :path path
	                                                       :client staff-client
	                                                       :task-id task-id})
	                   (templates/public-not-found {:public-base-url public-base-url
	                                                :base-path base-path
	                                                :lang lang
	                                                :path path}))))

	              (and (= method :post)
	                   (str/starts-with? path "/meet/"))
	              (let [[_ client-ref token] (re-matches #"/meet/([^/]+)/([^/]+)" path)
	                    params (decode-form-body request)
	                    task-id (some-> (get params "task_id") str str/trim)
	                    db0 (when conn (d/db conn))
	                    staff-client (when (and db0 client-ref token)
	                                   (staff-client-by-magic-link db0 client-ref token))]
	                (portal-security-headers
	                 (if-not (and staff-client (not (str/blank? task-id)))
	                   (templates/public-not-found {:public-base-url public-base-url
	                                                :base-path base-path
	                                                :lang lang
	                                                :path path})
	                   (let [ws (:client/workspace staff-client)
	                         actor (system-actor ws)
	                         client-id (:client/id staff-client)
	                         dead? (= "1" (get params "dead_lead"))
	                         dead-reason (some-> (get params "dead_reason") str str/trim not-empty)
	                         service-id (some-> (get params "service_id") str str/trim not-empty)
	                         service-id (when service-id (keyword service-id))
	                         objective (some-> (get params "objective") str str/trim not-empty)
	                         pricing-model (some-> (get params "pricing_model") str str/trim not-empty)
	                         currency (some-> (get params "currency") str str/trim not-empty)
	                         fixed-total (some-> (get params "fixed_total") str str/trim not-empty)
	                         range-min (some-> (get params "range_min") str str/trim not-empty)
	                         range-max (some-> (get params "range_max") str str/trim not-empty)
	                         custom-notes (some-> (get params "custom_notes") str str/trim not-empty)
	                         deposit-type (some-> (get params "deposit_type") str str/trim not-empty)
	                         deposit-value (some-> (get params "deposit_value") str str/trim not-empty)
	                         ml-labels (get params "milestone_label")
	                         ml-types (get params "milestone_type")
	                         ml-values (get params "milestone_value")
	                         ml-labels (if (sequential? ml-labels) ml-labels (when ml-labels [ml-labels]))
	                         ml-types (if (sequential? ml-types) ml-types (when ml-types [ml-types]))
	                         ml-values (if (sequential? ml-values) ml-values (when ml-values [ml-values]))
	                         milestones (->> (map vector ml-labels ml-types ml-values)
	                                         (map (fn [[l t v]]
	                                                {:label (some-> l str str/trim)
	                                                 :type (keyword (some-> t str str/trim))
	                                                 :value (some-> v str str/trim)}))
	                                         (filter (fn [m] (not (str/blank? (str (:label m))))))
	                                         vec)
	                         pricing (cond
	                                  (= pricing-model "fixed") {:model :fixed :currency (or currency "SAR") :total fixed-total}
	                                  (= pricing-model "range") {:model :range :currency (or currency "SAR") :min range-min :max range-max}
	                                  (= pricing-model "custom") {:model :custom :currency (or currency "SAR") :pricing-notes custom-notes}
	                                  :else nil)
	                         deposit (when (and deposit-type deposit-value (not= deposit-type "skip"))
	                                   {:type (keyword deposit-type) :value deposit-value})
	                         client-notes (some-> (get params "client_notes") str str/trim not-empty)
	                         internal-notes (some-> (get params "internal_notes") str str/trim not-empty)
	                         fields (cond-> {:service/id (or service-id :skip)
	                                         :offer/objective (or objective "")
	                                         :pricing/model (or pricing {})
	                                         :payment/milestones milestones}
	                                  deposit (assoc :payment/deposit deposit)
	                                  client-notes (assoc :notes/client-visible client-notes)
	                                  internal-notes (assoc :notes/internal internal-notes)
	                                  dead? (assoc :onboarding/dead-lead? true)
	                                  dead-reason (assoc :onboarding/dead-lead-reason dead-reason))
	                         res (actions/execute! {:db db :config config}
	                                              {:action/id :cap/action/report-card-submit
	                                               :actor actor
	                                               :input {:report.card/type :report.card.type/consultation
	                                                       :task/id task-id
	                                                       :client/id client-id
	                                                       :report.card/fields (pr-str fields)}})
	                         ok? (nil? (:error res))
	                         portal-token (:client/portal-token staff-client)
	                         portal-url (when (and portal-token client-ref)
	                                      (with-base base-path (str "/portal/" (str client-ref) "/" (str portal-token) "?submitted=" (if ok? "1" "0"))))]
	                     (-> (resp/redirect (or portal-url (with-base base-path "/")))
	                         (assoc :status 303))))))

	              (and (= method :get)
	                   (or (= path "/portal")
	                       (str/starts-with? path "/portal/")))
	              (let [[_ client-ref token] (re-matches #"/portal/([^/]+)/([^/]+)" path)
	                    client-ref (some-> client-ref codec/url-decode str str/trim)
                    token (some-> token codec/url-decode str str/trim)
                    portal (when (and (not (str/blank? client-ref))
                                      (not (str/blank? token)))
                             (service-cases/public-portal-read conn {:client-ref client-ref
                                                                    :token token}))]
                (portal-security-headers
                 (if (or (nil? portal) (:error portal))
                   (templates/public-not-found {:public-base-url public-base-url
                                                :base-path base-path
                                                :lang lang
                                                :path path})
                   (templates/public-portal {:public-base-url public-base-url
                                             :base-path base-path
                                             :lang lang
                                             :path (str prefix "/portal")
                                             :query query
                                             :client-ref client-ref
                                             :token token
                                             :portal portal}))))

              (and (= method :post)
                   (str/starts-with? path "/portal/"))
              (let [[_ client-ref token action] (re-matches #"/portal/([^/]+)/([^/]+)(?:/([^/]+))?" path)
                    client-ref (some-> client-ref codec/url-decode str str/trim)
                    token (some-> token codec/url-decode str str/trim)]
                (portal-security-headers
                 (templates/public-not-found {:public-base-url public-base-url
                                              :base-path base-path
                                              :lang lang
                                              :path path})))

              (static-path? path)
              (let [static-resp (resp/file-response (subs path 1) {:root "public"})]
                (if static-resp
                  (let [ctype (content-type-for-path path)
                        typed (if ctype
                                (resp/content-type static-resp ctype)
                                static-resp)]
                    (maybe-no-cache path typed))
                  (templates/public-not-found {:public-base-url public-base-url
                                               :base-path base-path
                                               :lang lang
                                               :path path})))

              :else
              (let [{:keys [error contact]} (content-context conn)]
                (if error
                  {:status 500
                   :headers {"Content-Type" "text/plain; charset=utf-8"}
                   :body (str "Content unavailable: " (:message error "unexpected error"))}
                  (templates/public-route {:public-base-url public-base-url
                                           :base-path base-path
                                           :lang lang
                                           :path path
                                           :query query
                                           :contact contact}))))]
        (let [dur-ms (/ (double (- (System/nanoTime) start)) 1e6)]
          (log/infof "site request path=%s status=%s dur=%.1fms"
                     clean-path
                     (:status response)
                     dur-ms)
          response))
      (catch Exception e
        (let [dur-ms (/ (double (- (System/nanoTime) start)) 1e6)]
          (log/error e (format "site request path=%s crashed after %.1fms" clean-path dur-ms))
          {:status 500
           :headers {"Content-Type" "text/plain; charset=utf-8"}
           :body "Site error"})))))

(defn app
  "Ring handler for the public site process."
  [state]
  (fn [request]
    (handle-request state request)))
