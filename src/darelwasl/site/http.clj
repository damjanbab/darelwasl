(ns darelwasl.site.http
  (:require [clojure.java.io :as io]
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
        existing (or (client-id-by-email db ws email)
                     (client-id-by-phone db ws phone))]
    (if existing
      {:client/id existing}
      (let [name (or (some-> phone str str/trim not-empty)
                     (some-> email str str/trim not-empty)
                     "Website lead")
            input {:client/name (str "Website lead · " name)
                   :client/status :lead
                   :client/channel (cond
                                    (not (str/blank? phone)) :whatsapp
                                    (not (str/blank? email)) :email
                                    :else :whatsapp)
                   :client/phone (when-not (str/blank? phone) phone)
                   :client/email (when-not (str/blank? email) email)
                   :client/notes (str "Activities: " (or (:lead/activities lead) "-") "\n"
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
  [state lead client-id]
  (let [conn (get-in state [:db :conn])
        db (when conn (d/db conn))
        ws (workspace/default-id)
        username (or (some-> (get-in state [:config :site :intake-assignee-username]) str str/trim not-empty)
                     "huda")
        assignee-id (user-id-by-username db username)
        actor (system-actor ws)
        lead-id (:lead/id lead)
        desc (str "New website consultation lead\n\n"
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
               :task/report-card-type :report.card.type/onboarding
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
        activities (str/trim (get params "activities" ""))
        email (str/trim (get params "email" ""))
        phone (str/trim (get params "phone" ""))
        ownership (str/trim (get params "ownership" ""))
        residency (str/trim (get params "residency" ""))
        start-month (str/trim (get params "start_month" ""))
        preferred-lang (str/trim (get params "lang" ""))
        ok? (and (not (str/blank? activities))
                 (or (not (str/blank? email))
                     (not (str/blank? phone))))
        qs (if ok?
             "sent=1"
             "error=1")
        location (with-base base-path (str prefix "/contact?" qs "#consultation"))]
    (when ok?
      (let [lead {:lead/id (java.util.UUID/randomUUID)
                  :lead/created-at (str (java.time.Instant/now))
                  :lead/source "public-site"
                  :lead/lang (or (some-> lang name) "en")
                  :lead/ip (:remote-addr request)
                  :lead/user-agent (get-in request [:headers "user-agent"])
                  :lead/activities activities
                  :lead/ownership ownership
                  :lead/residency residency
                  :lead/start-month start-month
                  :lead/email email
                  :lead/phone phone
                  :lead/preferred-lang preferred-lang}]
        (append-lead! lead)
        (when-let [conn (get-in state [:db :conn])]
          (enqueue-lead-notifications! conn (:config state) lead))
        (try
          (let [client-res (ensure-intake-client! state lead)]
            (when-let [cid (:client/id client-res)]
              (create-intake-task! state lead cid)))
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
      (let [response (cond
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
                            (or (= path "/portal")
                                (str/starts-with? path "/portal/")))
                       (let [[_ client-ref token] (re-matches #"/portal/([^/]+)/([^/]+)" path)
                             client-ref (some-> client-ref codec/url-decode str str/trim)
                             token (some-> token codec/url-decode str str/trim)
                             portal (when (and (not (str/blank? client-ref))
                                               (not (str/blank? token)))
                                      (service-cases/public-portal-read conn {:client-ref client-ref
                                                                             :token token}))]
                         (if (or (nil? portal) (:error portal))
                           (templates/public-not-found {:public-base-url public-base-url
                                                        :base-path base-path
                                                        :lang lang
                                                        :path path})
                           (templates/public-portal {:public-base-url public-base-url
                                                     :base-path base-path
                                                     :lang lang
                                                     :path (str prefix "/portal")
                                                     :portal portal})))

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
                         (cond
                           error
                           {:status 500
                            :headers {"Content-Type" "text/plain; charset=utf-8"}
                            :body (str "Content unavailable: " (:message error "unexpected error"))}

                           :else
                           (templates/public-route {:public-base-url public-base-url
                                                    :base-path base-path
                                                    :lang lang
                                                    :path path
                                                     :query query
                                                    :contact contact}))))
            dur-ms (/ (double (- (System/nanoTime) start)) 1e6)]
        (log/infof "site request path=%s status=%s dur=%.1fms"
                   clean-path
                   (:status response)
                   dur-ms)
        response)
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
