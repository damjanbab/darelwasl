(ns darelwasl.site.http
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.content :as content]
            [darelwasl.documents :as documents]
            [darelwasl.files :as files]
            [darelwasl.outbox :as outbox]
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
          (enqueue-lead-notifications! conn (:config state) lead))))
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

                       (and (= method :get) (= path "/verify"))
                       (let [ref (some-> (or (get query "ref") (get query "document")) str str/trim)
                             code (some-> (or (get query "code") (get query "verification")) str str/trim)
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
