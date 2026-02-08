(in-ns 'darelwasl.telegram)

(comment "Core Telegram transport + chat binding helpers.")

(def ^:private default-timeout-ms 3000)

(def ^:private allowed-commands
  #{:start :help :tasks :task :stop :new :edit :note :note-edit :docs})

(defn- present-string?
  [v]
  (and (string? v) (not (str/blank? v))))

(def ^:private max-capture-preview 10)
(def ^:private max-capture-cards 5)

(def ^:private status-emoji
  {:todo "🔵"
   :in-progress "🟡"
   :pending "🔴"
   :done "🟢"})

(def ^:private tasks-list-limit 200)

(defn- inline-button
  [text data]
  {:text text
   :callback_data data})

(defn webhook-enabled?
  [cfg]
  (true? (:webhook-enabled? cfg)))

(defn polling-enabled?
  [cfg]
  (true? (:polling-enabled? cfg)))

(defn commands-enabled?
  [cfg]
  (true? (:commands-enabled? cfg)))

(defn notifications-enabled?
  [cfg]
  (true? (:notifications-enabled? cfg)))

(defn- bot-url
  [cfg path]
  (when-let [token (:bot-token cfg)]
    (str "https://api.telegram.org/bot" token "/" path)))

(defn- ensure-conn
  [state]
  (get-in state [:db :conn]))

(defn- request-json
  [cfg path payload]
  (if (str/blank? (:bot-token cfg))
    {:error "Telegram bot token not configured"}
    (let [timeout (:http-timeout-ms cfg default-timeout-ms)]
      (letfn [(parse-json-body [body]
                (try
                  (cond
                    (string? body) (json/read-str body :key-fn keyword)
                    (map? body) body
                    :else nil)
                  (catch Exception _ nil)))
              (parse-int* [v]
                (when (and v (not (str/blank? (str v))))
                  (try
                    (Long/parseLong (str/trim (str v)))
                    (catch Exception _ nil))))
              (retry-after-ms [resp]
                (when-let [raw (get-in resp [:headers "retry-after"])]
                  (when-let [secs (parse-int* raw)]
                    (* 1000 secs))))
              (base-backoff-ms [attempt]
                (+ (* attempt 300)
                   (rand-int 200)))
              (should-retry? [resp]
                (let [status (:status resp)]
                  (or (= status 429)
                      (and (number? status) (<= 500 status)))))
              (attempt! [attempt]
                (try
                  (let [resp (http/post (bot-url cfg path)
                                        {:headers {"Content-Type" "application/json"
                                                   "Accept" "application/json"}
                                         :body (json/write-str payload)
                                         :socket-timeout timeout
                                         :conn-timeout timeout
                                         :throw-exceptions false
                                         :as :text})
                        body (parse-json-body (:body resp))]
                    (cond
                      (true? (:ok body))
                      {:result (:result body)}

                      (and (< attempt 3) (should-retry? resp))
                      (let [sleep-ms (or (retry-after-ms resp)
                                         (base-backoff-ms attempt))]
                        (log/warnf "Telegram API transient error; retrying path=%s status=%s attempt=%s sleep_ms=%s"
                                   path (:status resp) attempt sleep-ms)
                        (Thread/sleep sleep-ms)
                        (attempt! (inc attempt)))

                      :else
                      {:error (or (:description body)
                                  "Telegram API error")
                       :details (select-keys body [:description :error_code])
                       :status (:status resp)}))
                  (catch Exception e
                    (if (< attempt 3)
                      (do
                        (log/warn e "Telegram API request failed; retrying" {:path path :attempt attempt})
                        (Thread/sleep (base-backoff-ms attempt))
                        (attempt! (inc attempt)))
                      (do
                        (log/warn e "Telegram API request failed" {:path path})
                        {:error "Telegram API request failed"})))))]
        (attempt! 1)))))

(defn get-updates!
  "Fetch Telegram updates. Returns {:updates [...], :next-offset n} or {:error ...}."
  [cfg {:keys [offset limit timeout-ms]}]
  (let [timeout-secs (when (number? timeout-ms)
                       (max 0 (long (Math/ceil (/ timeout-ms 1000.0)))))
        payload (cond-> {}
                  (some? offset) (assoc :offset (long offset))
                  (some? limit) (assoc :limit (long limit))
                  (some? timeout-secs) (assoc :timeout timeout-secs))
        resp (request-json cfg "getUpdates" payload)]
    (if-let [err (:error resp)]
      resp
      (let [updates (vec (or (:result resp) []))
            last-id (when (seq updates)
                      (apply max (map :update_id updates)))
            next-offset (when last-id (inc (long last-id)))]
        {:updates updates
         :next-offset next-offset}))))

(defn send-message!
  "Send a Telegram message to chat-id. Returns {:telegram/message-id ...} or {:error ...}."
  [cfg {:keys [chat-id text parse-mode message-key reply-markup]}]
  (cond
    (str/blank? chat-id) {:error "Missing chat id"}
    (str/blank? text) {:error "Missing message text"}
    (str/blank? message-key) {:error "Missing message key for idempotency"}
    :else
    (let [payload (cond-> {:chat_id chat-id
                           :text text}
                    parse-mode (assoc :parse_mode (name parse-mode))
                    reply-markup (assoc :reply_markup reply-markup))
          resp (request-json cfg "sendMessage" payload)]
      (if-let [err (:error resp)]
        resp
        {:telegram/message-id (get-in resp [:result :message_id])}))))

(defn edit-message!
  "Edit an existing Telegram message text (and optional keyboard). Returns {:telegram/message-id ...} or {:error ...}."
  [cfg {:keys [chat-id message-id text parse-mode reply-markup]}]
  (cond
    (str/blank? chat-id) {:error "Missing chat id"}
    (nil? message-id) {:error "Missing message id"}
    (str/blank? text) {:error "Missing message text"}
    :else
    (let [payload (cond-> {:chat_id chat-id
                           :message_id message-id
                           :text text}
                    parse-mode (assoc :parse_mode (name parse-mode))
                    reply-markup (assoc :reply_markup reply-markup))
          resp (request-json cfg "editMessageText" payload)]
      (if-let [err (:error resp)]
        resp
        {:telegram/message-id (get-in resp [:result :message_id])}))))

(defn send-document-file!
  "Send a local file (document) to chat-id via Telegram. Best-effort. Returns {:telegram/message-id ...} or {:error ...}."
  [cfg {:keys [chat-id file filename caption]}]
  (cond
    (str/blank? chat-id) {:error "Missing chat id"}
    (nil? file) {:error "Missing file"}
    :else
    (let [url (bot-url cfg "sendDocument")
          timeout (:http-timeout-ms cfg default-timeout-ms)
          payload {:multipart (cond-> [{:name "chat_id" :content chat-id}
                                       {:name "document" :content file :filename (or filename (.getName ^java.io.File file))}]
                                (present-string? caption)
                                (conj {:name "caption" :content caption}))}
          resp (try
                 (http/post url (merge payload
                                       {:socket-timeout timeout
                                        :conn-timeout timeout
                                        :throw-exceptions false
                                        :as :text}))
                 (catch Exception e
                   (log/warn e "Telegram sendDocument failed")
                   {:error "Telegram sendDocument failed"}))]
      (if (:error resp)
        resp
        (let [body (try
                     (json/read-str (or (:body resp) "") :key-fn keyword)
                     (catch Exception _ nil))]
          (if (and (map? body) (true? (:ok body)))
            {:telegram/message-id (get-in body [:result :message_id])}
            {:error (or (:description body) "Telegram sendDocument error")
             :details (select-keys body [:description :error_code])
             :status (:status resp)}))))))

(defn answer-callback!
  [cfg {:keys [callback-id text show-alert?]}]
  (when (not (str/blank? callback-id))
    (request-json cfg "answerCallbackQuery" (cond-> {:callback_query_id callback-id}
                                              (some? text) (assoc :text text)
                                              show-alert? (assoc :show_alert true)))))

(defn set-webhook!
  [cfg {:keys [webhook-url secret-token]}]
  (cond
    (str/blank? webhook-url) {:error "Missing webhook URL"}
    (str/blank? secret-token) {:error "Missing webhook secret token"}
    :else
    (request-json cfg "setWebhook" {:url webhook-url
                                    :secret_token secret-token
                                    :allowed_updates ["message" "callback_query"]})))

(defn auto-set-webhook!
  "If enabled and configured, call setWebhook on startup."
  [cfg]
  (let [{:keys [webhook-enabled? auto-set-webhook? webhook-base-url webhook-secret]} cfg]
    (when (and webhook-enabled? auto-set-webhook? (present-string? webhook-base-url))
      (let [url (str (str/replace (str/trim webhook-base-url) #"/+$" "") "/api/telegram/webhook")]
        (log/info "Setting Telegram webhook" {:url url})
        (let [res (set-webhook! cfg {:webhook-url url
                                     :secret-token webhook-secret})]
          (when-let [err (:error res)]
            (log/error "Failed to set Telegram webhook" {:error err :url url})
            res))))))

(defn- log-telegram-message!
  "Persist a minimal telegram message fact with provenance. Direction is :inbound or :outbound."
  [state {:keys [chat-id from-id text update-id message-id direction]}]
  (when-let [conn (ensure-conn state)]
    (let [prov (prov/provenance {:actor/type :integration
                                 :integration/id :integration/telegram}
                                :adapter/telegram)
          trimmed (when text
                    (let [s (str text)]
                      (if (> (count s) 2000)
                        (subs s 0 2000)
                        s)))
          base {:telegram.message/id (UUID/randomUUID)
                :entity/type :entity.type/telegram-message
                :telegram.message/chat-id (str chat-id)
                :telegram.message/update-id update-id
                :telegram.message/direction direction
                :telegram.message/created-at (Date.)}
          tx (cond-> base
               (some? from-id) (assoc :telegram.message/from-id (long from-id))
               (some? trimmed) (assoc :telegram.message/text trimmed)
               (some? message-id) (assoc :telegram.message/message-id (long message-id)))
          tx (prov/enrich-tx tx prov)]
      (try
        (db/transact! conn {:tx-data [tx]})
        (catch Exception e
          (log/warn e "Failed to log Telegram message" {:chat-id chat-id :direction direction})))
      nil)))

(defn ensure-link-token!
  "Generate and persist a new link token for the given user id. Returns {:token \"...\"} or {:error ...}."
  [state user-id]
  (if-let [conn (ensure-conn state)]
    (try
      (let [token (str (UUID/randomUUID))]
        (db/transact! conn {:tx-data [[:db/add [:user/id user-id] :user/telegram-link-token token]
                                      [:db/add [:user/id user-id] :user/telegram-link-token-created-at (java.util.Date.)]]})
        {:token token})
      (catch Exception e
        (log/warn e "Failed to create Telegram link token" {:user-id user-id})
        {:error "Unable to create link token"}))
    {:error "No database connection"}))

(defn- user-by-link-token
  [db token]
  (when-not (str/blank? token)
    (-> (d/q '[:find (pull ?u [:user/id
                               :user/username
                               :user/name
                               :user/telegram-link-token
                               :user/telegram-link-token-created-at
                               :user/telegram-chat-id])
               :in $ ?token
               :where [?u :user/telegram-link-token ?token]]
             db token)
        ffirst)))

(defn- user-by-chat-id
  [db chat-id]
  (when-not (str/blank? chat-id)
    (-> (d/q '[:find (pull ?u [:user/id :user/username :user/name :user/telegram-chat-id])
               :in $ ?chat
               :where [?u :user/telegram-chat-id ?chat]]
             db chat-id)
        ffirst)))

(defn- user-by-telegram-user-id
  [db telegram-user-id]
  (when telegram-user-id
    (-> (d/q '[:find (pull ?u [:user/id :user/username :user/name :user/telegram-user-id :user/telegram-chat-id])
               :in $ ?tid
               :where [?u :user/telegram-user-id ?tid]]
             db telegram-user-id)
        ffirst)))

(defn- chat-id-by-user-id
  [db user-id]
  (when user-id
    (-> (d/q '[:find ?chat
               :in $ ?id
               :where [?u :user/id ?id]
                      [?u :user/telegram-chat-id ?chat]]
             db user-id)
        ffirst)))

(defn bind-chat!
  "Bind chat-id to the user that owns the link token; clears the token. Returns {:user user} or {:error ...}."
  [state {:keys [token chat-id]}]
  (cond
    (str/blank? token) {:error "Missing link token"}
    (str/blank? chat-id) {:error "Missing chat id"}
    :else
    (if-let [conn (ensure-conn state)]
      (try
        (let [db (d/db conn)
              user (user-by-link-token db token)]
          (if-not user
            {:error "Invalid or expired link token"}
            (let [issued-at (:user/telegram-link-token-created-at user)
                  ttl-ms (get-in state [:config :telegram :link-token-ttl-ms] 900000)
                  now-ms (System/currentTimeMillis)
                  issued-ms (when issued-at (.getTime ^java.util.Date issued-at))]
              (cond
                (nil? issued-ms) {:error "Invalid or expired link token"}
                (> (- now-ms issued-ms) ttl-ms) {:error "Invalid or expired link token"}
                :else
                (let [existing (user-by-chat-id db chat-id)
                      same-user? (and existing (= (:user/id existing) (:user/id user)))
                      already-linked? (or (= (:user/telegram-chat-id user) chat-id)
                                          (and same-user? (= (:user/telegram-chat-id existing) chat-id)))
                      retract-existing (when (and existing (not same-user?))
                                         [[:db/retract [:user/id (:user/id existing)]
                                           :user/telegram-chat-id
                                           (:user/telegram-chat-id existing)]])
                      retract-user-chat (when (and (:user/telegram-chat-id user) (not already-linked?))
                                          [[:db/retract [:user/id (:user/id user)]
                                            :user/telegram-chat-id
                                            (:user/telegram-chat-id user)]])
                      retract-token (cond-> []
                                      (:user/telegram-link-token user)
                                      (conj [:db/retract [:user/id (:user/id user)]
                                             :user/telegram-link-token
                                             (:user/telegram-link-token user)])
                                      (:user/telegram-link-token-created-at user)
                                      (conj [:db/retract [:user/id (:user/id user)]
                                             :user/telegram-link-token-created-at
                                             (:user/telegram-link-token-created-at user)]))
                      add-chat (when (not already-linked?)
                                 [[:db/add [:user/id (:user/id user)] :user/telegram-chat-id chat-id]])]
                  (doseq [tx [retract-existing retract-user-chat retract-token add-chat]]
                    (when (seq tx)
                      (db/transact! conn {:tx-data tx})))
                  {:user (assoc user :user/telegram-chat-id chat-id)})))))
        (catch Exception e
          (log/warn e "Failed to bind Telegram chat" {:chat-id chat-id})
          {:error "Unable to bind chat"}))
      {:error "No database connection"})))

(defn bind-chat-for-user!
  "Bind chat-id to a known user entity (no token)."
  [state {:keys [user chat-id]}]
  (cond
    (nil? user) {:error "Missing user"}
    (str/blank? chat-id) {:error "Missing chat id"}
    :else
    (if-let [conn (ensure-conn state)]
      (try
        (let [db (d/db conn)
              existing (user-by-chat-id db chat-id)
              same-user? (and existing (= (:user/id existing) (:user/id user)))
              already-linked? (or (= (:user/telegram-chat-id user) chat-id)
                                  (and same-user? (= (:user/telegram-chat-id existing) chat-id)))
              retract-existing (when (and existing (not same-user?))
                                 [[:db/retract [:user/id (:user/id existing)]
                                   :user/telegram-chat-id
                                   (:user/telegram-chat-id existing)]])
              retract-user-chat (when (and (:user/telegram-chat-id user) (not already-linked?))
                                  [[:db/retract [:user/id (:user/id user)]
                                    :user/telegram-chat-id
                                    (:user/telegram-chat-id user)]])
              add-chat (when (not already-linked?)
                         [[:db/add [:user/id (:user/id user)] :user/telegram-chat-id chat-id]])]
          (doseq [tx [retract-existing retract-user-chat add-chat]]
            (when (seq tx)
              (db/transact! conn {:tx-data tx})))
          {:user (assoc user :user/telegram-chat-id chat-id)})
        (catch Exception e
          (log/warn e "Failed to bind Telegram chat (auto)" {:chat-id chat-id})
          {:error "Unable to bind chat"}))
      {:error "No database connection"})))

(defn- auto-bind-user
  [state db chat-id]
  (let [username (get-in state [:config :telegram :auto-bind-username])]
    (when (and (present-string? username) db)
      (when-let [user (users/user-by-username db username)]
        (when-let [res (bind-chat-for-user! state {:user user :chat-id chat-id})]
          (:user res))))))

(defn recognize-user!
  "Set telegram user id for a given app user. Returns {:status :ok} or {:error ...}."
  [state user-id telegram-user-id]
  (if-let [conn (ensure-conn state)]
    (try
      (db/transact! conn {:tx-data [[:db/add [:user/id user-id] :user/telegram-user-id telegram-user-id]]})
      {:status :ok
       :user/id user-id
       :telegram/user-id telegram-user-id}
      (catch Exception e
        (log/warn e "Failed to set telegram user id" {:user-id user-id :telegram-user-id telegram-user-id})
        {:error "Unable to set telegram user id"}))
    {:error "No database connection"}))

(defn unbind-chat!
  "Clear chat binding for the given chat-id. Returns {:status :ok} or {:error ...}."
  [state chat-id]
  (if-let [conn (ensure-conn state)]
    (try
      (let [db (d/db conn)
            user (user-by-chat-id db chat-id)]
        (if-not user
          {:error "No chat binding found"}
          (do
            (db/transact! conn {:tx-data [[:db/retract [:user/id (:user/id user)] :user/telegram-chat-id chat-id]]})
            {:status :ok
             :user user})))
      (catch Exception e
        (log/warn e "Failed to unbind Telegram chat" {:chat-id chat-id})
        {:error "Unable to unbind chat"}))
    {:error "No database connection"}))

