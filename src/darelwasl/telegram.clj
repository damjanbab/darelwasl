(ns darelwasl.telegram
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.actions :as actions]
            [darelwasl.db :as db]
            [darelwasl.outbox :as outbox]
            [darelwasl.events :as events]
            [darelwasl.users :as users]
            [darelwasl.tasks :as tasks]
            [darelwasl.provenance :as prov])
  (:import (java.util UUID Date)))

(def ^:private default-timeout-ms 3000)

(def ^:private allowed-commands
  #{:start :help :tasks :task :stop :new :edit :note :note-edit})

(defn- present-string?
  [v]
  (and (string? v) (not (str/blank? v))))

(def ^:private capture-ttl-ms
  (* 15 60 1000))

(defonce pending-captures
  (atom {}))

(defonce pending-reasons
  (atom {}))

(defonce pending-edits
  (atom {}))
(def ^:private max-capture-preview 10)
(def ^:private max-capture-cards 5)
(def ^:private status-emoji
  {:todo "🔵"
   :in-progress "🟡"
   :pending "🔴"
   :done "🟢"})
(declare ensure-conn)
(declare bind-chat-for-user!)

(defn- prune-captures!
  []
  (let [cutoff (- (System/currentTimeMillis) capture-ttl-ms)]
    (swap! pending-captures
           (fn [entries]
             (into {}
                   (filter (fn [[_ v]]
                             (let [ts (:created-at v 0)]
                               (>= ts cutoff))))
                   entries)))))

(defn- prune-pending-reasons!
  []
  (let [cutoff (- (System/currentTimeMillis) capture-ttl-ms)]
    (swap! pending-reasons
           (fn [entries]
             (into {}
                   (filter (fn [[_ v]]
                             (let [ts (:created-at v 0)]
                               (>= ts cutoff))))
                   entries)))))

(defn- prune-pending-edits!
  []
  (let [cutoff (- (System/currentTimeMillis) capture-ttl-ms)]
    (swap! pending-edits
           (fn [entries]
             (into {}
                   (filter (fn [[_ v]]
                             (let [ts (:created-at v 0)]
                               (>= ts cutoff))))
                   entries)))))

(defn- capture-key
  [chat-id message-id]
  (str chat-id ":" message-id))
(defn- normalize-task-item
  [item]
  (let [trimmed (-> item str str/trim)
        cleaned (str/replace trimmed #"^(?:[-*•])\s+" "")]
    (when-not (str/blank? cleaned)
      cleaned)))

(defn- parse-task-entry
  [item]
  (let [[title desc] (if (str/includes? item "|")
                       (map str/trim (str/split item #"\|" 2))
                       [item nil])
        title (when-not (str/blank? title) title)
        desc (when-not (str/blank? desc) desc)]
    (when title
      {:title title
       :desc desc})))

(defn- parse-task-entries
  [text]
  (let [raw (str/trim (or text ""))
        items (->> (str/split raw #"\r?\n")
                   (map normalize-task-item)
                   (remove nil?)
                   vec)
        items (if (> (count items) 1)
                items
                (let [semi (->> (str/split raw #"\s*;\s*")
                                (map normalize-task-item)
                                (remove nil?)
                                vec)]
                  (if (> (count semi) 1) semi items)))]
    (->> items
         (map parse-task-entry)
         (remove nil?)
         vec)))

(defn- capture-summary
  [entries]
  (let [titles (map :title entries)
        preview (take max-capture-preview titles)
        remainder (- (count titles) (count preview))]
    (str "Capture multiple tasks:\n"
         (str/join "\n" (map #(str "- " %) preview))
         (when (pos? remainder)
           (str "\n- ...and " remainder " more"))
         "\n\nSave these tasks?")))

(defn- task-body
  [chat-user {:keys [title desc]}]
  {:task/title title
   :task/description (or desc (str "Captured via chat: " title))
   :task/status :todo
   :task/priority :medium
   :task/assignee (:user/id chat-user)})

(defn- parse-task-command
  [rest]
  (let [raw (str/trim (or rest ""))
        [id-str body] (str/split raw #"\s+" 2)
        task-id (when (and id-str (not (str/blank? id-str)))
                  (try
                    (UUID/fromString id-str)
                    (catch Exception _ nil)))
        body (some-> body str/trim)]
    {:task-id task-id
     :body body}))
(defn- save-capture!
  [chat-id message-id payload]
  (prune-captures!)
  (swap! pending-captures assoc (capture-key chat-id message-id) (assoc payload :created-at (System/currentTimeMillis))))

(defn- take-capture!
  [chat-id message-id]
  (prune-captures!)
  (let [k (capture-key chat-id message-id)
        value (get @pending-captures k)]
    (swap! pending-captures dissoc k)
    value))

(defn- pending-reason-key
  [chat-id]
  (str chat-id))

(defn- save-pending-reason!
  [chat-id payload]
  (prune-pending-reasons!)
  (swap! pending-reasons assoc (pending-reason-key chat-id) (assoc payload :created-at (System/currentTimeMillis))))

(defn- get-pending-reason!
  [chat-id]
  (prune-pending-reasons!)
  (get @pending-reasons (pending-reason-key chat-id)))

(defn- pending-edit-key
  [chat-id]
  (str chat-id))

(defn- save-pending-edit!
  [chat-id payload]
  (prune-pending-edits!)
  (swap! pending-edits assoc (pending-edit-key chat-id) (assoc payload :created-at (System/currentTimeMillis))))

(defn- get-pending-edit!
  [chat-id]
  (prune-pending-edits!)
  (get @pending-edits (pending-edit-key chat-id)))

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

(defn- take-pending-reason!
  [chat-id]
  (prune-pending-reasons!)
  (let [k (pending-reason-key chat-id)
        value (get @pending-reasons k)]
    (swap! pending-reasons dissoc k)
    value))

(defn- take-pending-edit!
  [chat-id]
  (prune-pending-edits!)
  (let [k (pending-edit-key chat-id)
        value (get @pending-edits k)]
    (swap! pending-edits dissoc k)
    value))

(defn- truncate-text
  [s max-len]
  (if (and (string? s) (pos? max-len) (> (count s) max-len))
    (let [trim-len (max 0 (- max-len 3))]
      (str (subs s 0 trim-len) "..."))
    s))

(defn- latest-pending-reason
  [db task-id]
  (when (and db task-id)
    (let [rows (seq (d/q '[:find ?body ?created
                           :in $ ?tid
                           :where [?t :task/id ?tid]
                                  [?n :note/subject ?t]
                                  [?n :note/type :note.type/pending-reason]
                                  [?n :note/body ?body]
                                  [?n :note/created-at ?created]]
                         db task-id))]
      (some->> rows
               (sort-by second #(compare %2 %1))
               ffirst))))

(defn- pending-reason-for-task
  [db task]
  (when (and (= :pending (:task/status task))
             (:task/id task))
    (latest-pending-reason db (:task/id task))))

(declare inline-button)

(def ^:private list-ttl-ms
  (* 30 60 1000))

(defonce task-list-state
  (atom {}))

(defn- prune-task-lists!
  []
  (let [cutoff (- (System/currentTimeMillis) list-ttl-ms)]
    (swap! task-list-state
           (fn [entries]
             (into {}
                   (filter (fn [[_ v]]
                             (let [ts (:created-at v 0)]
                               (>= ts cutoff))))
                   entries)))))

(defn- task-list-key
  [chat-id message-id]
  (str chat-id ":" message-id))

(defn- save-task-list!
  [chat-id message-id filters]
  (prune-task-lists!)
  (swap! task-list-state assoc (task-list-key chat-id message-id) (assoc filters :created-at (System/currentTimeMillis))))

(defn- get-task-list!
  [chat-id message-id]
  (prune-task-lists!)
  (get @task-list-state (task-list-key chat-id message-id)))

(defn- capture-inline-keyboard
  [message-id]
  {:inline_keyboard
   [[(inline-button "Create task" (str "capture:create:" message-id))
     (inline-button "Dismiss" (str "capture:cancel:" message-id))]]})

(defn- pending-reason-inline-keyboard
  [task-id]
  {:inline_keyboard
   [[(inline-button "Cancel" (str "pending:cancel:" task-id))]]})

(defn- pending-edit-inline-keyboard
  [task-id]
  {:inline_keyboard
   [[(inline-button "Cancel" (str "task:edit:cancel:" task-id))]]})

(defn- inline-button [text data]
  {:text text
   :callback_data data})

(defn- task-inline-keyboard
  [task]
  (let [id (str (:task/id task))
        archived? (:task/archived? task)]
    {:inline_keyboard
     [[(inline-button "🔵 Todo" (str "task:status:" id ":todo"))
       (inline-button "🟡 In progress" (str "task:status:" id ":in-progress"))]
      [(inline-button "🔴 Pending" (str "task:status:" id ":pending"))
       (inline-button "🟢 Done" (str "task:status:" id ":done"))]
      [(inline-button "Edit title" (str "task:edit:title:" id))
       (inline-button "Edit desc" (str "task:edit:desc:" id))]
      [(inline-button "Add note" (str "task:note:add:" id))
       (inline-button "Edit note" (str "task:note:edit:" id))]
      [(inline-button "Delete note" (str "task:note:delete:" id))
       (inline-button (if archived? "Unarchive" "Archive")
                      (str "task:archive:" id ":" (if archived? "false" "true")))]
      [(inline-button "Refresh" (str "task:view:" id))]]}))

(defn webhook-enabled?
  [cfg]
  (true? (:webhook-enabled? cfg)))

(defn polling-enabled?
  [cfg]
  (true? (:polling-enabled? cfg)))

(defn- auto-bind-user
  [state db chat-id]
  (let [username (get-in state [:config :telegram :auto-bind-username])]
    (when (and (present-string? username) db)
      (when-let [user (users/user-by-username db username)]
        (when-let [res (bind-chat-for-user! state {:user user :chat-id chat-id})]
          (:user res))))))

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

(defn- parse-command
  [text]
  (when text
    (let [trimmed (str/trim text)]
      (when (str/starts-with? trimmed "/")
        (let [[raw-cmd rest] (str/split trimmed #"\s+" 2)
              raw-cmd (subs raw-cmd 1)
              cmd (some-> raw-cmd
                          (str/split #"@" 2)
                          first
                          str/lower-case
                          keyword)
              rest (some-> rest str/trim)]
          (when (and cmd (allowed-commands cmd))
            {:command cmd
             :rest (when-not (str/blank? rest) rest)}))))))

(defn- extract-update
  [update]
  (let [message (or (:message update) (get update "message"))
        callback (:callback_query update)
        update-id (or (:update_id update) (get update "update_id"))
        chat-id (or (get-in message [:chat :id])
                    (get-in message ["chat" "id"])
                    (get-in callback [:message :chat :id])
                    (get-in callback ["message" "chat" "id"]))
        message-id (or (get-in message [:message_id])
                       (get-in message ["message_id"])
                       (get-in callback [:message :message_id])
                       (get-in callback ["message" "message_id"]))
        from-id (or (get-in message [:from :id])
                    (get-in message ["from" "id"])
                    (get-in callback [:from :id])
                    (get-in callback ["from" "id"]))
        from-username (or (get-in message [:from :username])
                          (get-in message ["from" "username"])
                          (get-in callback [:from :username])
                          (get-in callback ["from" "username"]))
        text (or (:text message) (get message "text"))
        {:keys [command rest]} (parse-command text)]
    {:update-id update-id
     :chat-id (some-> chat-id str)
     :message-id message-id
     :from-id from-id
     :from-username from-username
     :text text
     :command command
     :rest rest
     :callback (when callback
                 {:callback-id (or (:id callback) (get callback "id"))
                  :data (or (:data callback) (get callback "data"))
                  :message-id (or (get-in callback [:message :message_id])
                                  (get-in callback ["message" "message_id"]))
                  :chat-id chat-id})}))

(defn- status-label
  [status]
  (let [label (or (some-> status name) "unknown")
        icon (get status-emoji status "⚪️")]
    (str icon " " label)))

(defn- format-task-line
  [task pending-reason]
  (let [status (status-label (:task/status task))
        due (or (:task/due-date task) "none")
        title (or (:task/title task) "Untitled task")]
    (str "- " status " " title " (due " due ")"
         (when pending-reason
           (str " · Reason: " (truncate-text pending-reason 80))))))

(defn- tasks-summary-text
  [tasks pending-reasons]
  (if (empty? tasks)
    "You have no tasks assigned."
    (str "Your top tasks:\n"
         (str/join "\n"
                   (map (fn [task]
                          (format-task-line task (get pending-reasons (:task/id task))))
                        tasks)))))

(defn- tasks-filter-rows
  [{:keys [status archived]}]
  (let [status-label (fn [label key]
                       (if (= status key) (str label " ✅") label))
        arch-label (fn [label key]
                     (if (= archived key) (str label " ✅") label))]
    [[(inline-button (status-label "All" nil) "filter:status:all")
      (inline-button (status-label "🔵 Todo" :todo) "filter:status:todo")
      (inline-button (status-label "🟡 In prog" :in-progress) "filter:status:in-progress")]
     [(inline-button (status-label "🔴 Pending" :pending) "filter:status:pending")
      (inline-button (status-label "🟢 Done" :done) "filter:status:done")]
     [(inline-button (arch-label "Active" :active) "filter:archived:active")
      (inline-button (arch-label "All" :all) "filter:archived:all")
      (inline-button (arch-label "Archived" :archived) "filter:archived:archived")]
     [(inline-button "Refresh" "filter:refresh")]]))

(defn- task-open-button
  [task]
  (let [id (str (:task/id task))
        title (or (:task/title task) "Task")
        label (if (> (count title) 22) (str (subs title 0 19) "…") title)
        status (get status-emoji (:task/status task))]
    (inline-button (str (when status (str status " ")) label) (str "task:view:" id))))

(defn- tasks-list-keyboard
  [tasks filters]
  {:inline_keyboard
   (vec (concat
         (tasks-filter-rows filters)
         (mapv (fn [task] [(task-open-button task)]) tasks)))})

(defn- task-detail-text
  [task pending-reason]
  (if-not task
    "Task not found or not assigned to you."
    (let [status (status-label (:task/status task))
          due (or (:task/due-date task) "none")
          assignee (get-in task [:task/assignee :user/username] "n/a")]
      (str (or (:task/title task) "Untitled task") "\n"
           "Status: " status "\n"
           (when pending-reason (str "Pending reason: " (truncate-text pending-reason 120) "\n"))
           "Due: " due "\n"
           "Assignee: " assignee))))

(defn- task-notification-text
  [event title status due actor-name]
  (case event
    :task/created (str "New task assigned by " actor-name ":\n" title "\nStatus: " status "\nDue: " due)
    :task/assigned (str "Task assigned by " actor-name ":\n" title "\nStatus: " status "\nDue: " due)
    :task/status-changed (str "Task status updated by " actor-name ":\n" title "\nStatus: " status "\nDue: " due)
    :task/due-changed (str "Task due date updated by " actor-name ":\n" title "\nStatus: " status "\nDue: " due)
    (str "Task update:\n" title "\nStatus: " status "\nDue: " due)))

(defn- send-task-card!
  [state chat-id task {:keys [reply-to-message-id] :as opts}]
    (let [cfg (get-in state [:config :telegram])
          conn (ensure-conn state)
          db (when conn (d/db conn))
          pending-reason (pending-reason-for-task db task)
          text (task-detail-text task pending-reason)
          keyboard (task-inline-keyboard task)
          message-key (str "task-card:" (:task/id task) ":" (System/currentTimeMillis))]
      (if reply-to-message-id
        (edit-message! cfg {:chat-id chat-id
                            :message-id reply-to-message-id
                            :text text
                            :reply-markup keyboard})
        (send-message! cfg {:chat-id chat-id
                            :text text
                            :message-key message-key
                            :reply-markup keyboard}))))

(defn- list-user-tasks
  [conn user-id {:keys [status archived limit] :or {limit 5}}]
  (let [archived (case archived
                   :archived true
                   :all :all
                   :active false
                   false)
        params (cond-> {:assignee user-id
                        :archived archived
                        :limit limit
                        :offset 0
                        :sort :updated
                        :order :desc}
                 status (assoc :status status))]
    (tasks/list-tasks conn params)))

(defn- find-user-task
  [conn user-id task-id]
  (let [res (list-user-tasks conn user-id 100)]
    (when-not (:error res)
      (->> (:tasks res)
           (filter #(= (:task/id %) task-id))
           first))))

(defn- handle-freeform-message
  [state chat-user chat-id text]
  (let [body {:task/title text
              :task/description (str "Captured via chat: " text)
              :task/status :todo
              :task/priority :medium
              :task/assignee (:user/id chat-user)}
        cfg (get-in state [:config :telegram])
        prompt (str "Capture:\n" text "\n\nSave as a task?")
        send-res (send-message! cfg {:chat-id chat-id
                                     :text prompt
                                     :message-key (str "capture-" (System/currentTimeMillis))
                                     :reply-markup (capture-inline-keyboard "pending")})]
    (if-let [err (:error send-res)]
      (send-message! cfg {:chat-id chat-id
                          :text (str "Unable to capture: " err)
                          :message-key (str "capture-error-" (System/currentTimeMillis))})
      (let [message-id (:telegram/message-id send-res)]
        (save-capture! chat-id message-id {:body body
                                           :user chat-user
                                           :text text})
        (edit-message! cfg {:chat-id chat-id
                            :message-id message-id
                            :text prompt
                                           :reply-markup (capture-inline-keyboard message-id)})))))

(defn- handle-pending-reason-message
  [state chat-user chat-id text]
  (let [cfg (get-in state [:config :telegram])
        pending (get-pending-reason! chat-id)]
    (when pending
      (if (str/blank? text)
        (send-message! cfg {:chat-id chat-id
                            :text "Pending reason cannot be blank. Reply with a reason or tap Cancel."
                            :message-key (str "pending-reason-empty-" (System/currentTimeMillis))})
        (let [task-id (:task-id pending)
              res (actions/execute! state {:action/id :cap/action/task-set-status
                                           :actor (actions/actor-from-telegram chat-user)
                                           :input {:task/id task-id
                                                   :task/status :pending
                                                   :note/body text}})]
          (if-let [err (:error res)]
            (send-message! cfg {:chat-id chat-id
                                :text (str "Unable to set pending: " (:message err))
                                :message-key (str "pending-reason-error-" (System/currentTimeMillis))})
            (do
              (take-pending-reason! chat-id)
              (send-task-card! state chat-id (get-in res [:result :task]) {}))))))))

(defn- edit-prompt-text
  [edit-type title]
  (case edit-type
    :edit-title (str "Send the new title for:\n" title)
    :edit-desc (str "Send the new description for:\n" title)
    :note-add (str "Send a new note for:\n" title)
    :note-edit (str "Send the updated note for your latest comment on:\n" title)
    (str "Send the update for:\n" title)))

(defn- handle-pending-edit-message
  [state chat-user chat-id text]
  (let [cfg (get-in state [:config :telegram])
        pending (get-pending-edit! chat-id)]
    (when pending
      (if (str/blank? text)
        (send-message! cfg {:chat-id chat-id
                            :text "Reply with a value or tap Cancel."
                            :message-key (str "edit-empty-" (System/currentTimeMillis))})
        (let [{:keys [task-id type]} pending
              action (case type
                       :edit-title {:action/id :cap/action/task-update
                                    :input {:task/id task-id
                                            :task/title text}}
                       :edit-desc {:action/id :cap/action/task-update
                                   :input {:task/id task-id
                                           :task/description text}}
                       :note-add {:action/id :cap/action/task-add-note
                                  :input {:task/id task-id
                                          :note/body text}}
                       :note-edit {:action/id :cap/action/task-edit-note
                                   :input {:task/id task-id
                                           :note/body text}}
                       nil)]
          (if-not action
            (do
              (take-pending-edit! chat-id)
              (send-message! cfg {:chat-id chat-id
                                  :text "Unknown edit action."
                                  :message-key (str "edit-unknown-" (System/currentTimeMillis))}))
            (let [res (actions/execute! state (assoc action :actor (actions/actor-from-telegram chat-user)))]
              (if-let [err (:error res)]
                (do
                  (take-pending-edit! chat-id)
                  (send-message! cfg {:chat-id chat-id
                                      :text (str "Unable to update: " (:message err))
                                      :message-key (str "edit-error-" (System/currentTimeMillis))}))
                (do
                  (take-pending-edit! chat-id)
                  (cond
                    (#{:edit-title :edit-desc} type)
                    (send-task-card! state chat-id (get-in res [:result :task]) {})

                    (= :note-add type)
                    (send-message! cfg {:chat-id chat-id
                                        :text "Note added to task."
                                        :message-key (str "note-add-" (System/currentTimeMillis))})

                    (= :note-edit type)
                    (send-message! cfg {:chat-id chat-id
                                        :text "Latest note updated."
                                        :message-key (str "note-edit-" (System/currentTimeMillis))})

                    :else
                    (send-message! cfg {:chat-id chat-id
                                        :text "Update saved."
                                        :message-key (str "edit-ok-" (System/currentTimeMillis))})))))))))))

(defn- start-pending-edit!
  [state chat-id chat-user task-id edit-type message-id]
  (let [cfg (get-in state [:config :telegram])
        conn (ensure-conn state)
        task (find-user-task conn (:user/id chat-user) task-id)]
    (if-not task
      (send-message! cfg {:chat-id chat-id
                          :text "Task not found."
                          :message-key (str "edit-task-missing-" (or message-id (System/currentTimeMillis)))})
      (let [title (or (:task/title task) "Task")
            prompt (edit-prompt-text edit-type title)
            send-res (send-message! cfg {:chat-id chat-id
                                         :text prompt
                                         :message-key (str "edit-prompt-" (name edit-type) "-" task-id "-" (System/currentTimeMillis))
                                         :reply-markup (pending-edit-inline-keyboard task-id)})]
        (if-let [err (:error send-res)]
          send-res
          (do
            (take-pending-reason! chat-id)
            (save-pending-edit! chat-id {:task-id task-id
                                         :type edit-type
                                         :user chat-user})
            send-res))))))

(declare parse-callback)

(defn- handle-callback
  [state chat-id {:keys [message-id callback-id data]}]
  (let [conn (ensure-conn state)
        db (when conn (d/db conn))
        chat-user (when db (user-by-chat-id db chat-id))
        parsed (parse-callback data)
        cfg (get-in state [:config :telegram])]
    (when callback-id
      (answer-callback! cfg {:callback-id callback-id}))
    (case (:type parsed)
      :capture/create (let [capture (take-capture! chat-id message-id)
                            body (:body capture)
                            actor (actions/actor-from-telegram (:user capture))]
                        (if (and capture actor)
                          (let [res (actions/execute! state {:action/id :cap/action/task-create
                                                             :actor actor
                                                             :input body})]
                            (if-let [err (:error res)]
                              (send-message! cfg {:chat-id chat-id
                                                  :text (str "Unable to create task: " (:message err))
                                                  :message-key (str "capture-create-error-" message-id)})
                              (send-task-card! state chat-id (get-in res [:result :task]) {:reply-to-message-id message-id})))
                          (edit-message! cfg {:chat-id chat-id
                                              :message-id message-id
                                              :text "Capture expired."
                                              :reply-markup {:inline_keyboard []}})))
      :capture/cancel (do
                        (take-capture! chat-id message-id)
                        (edit-message! cfg {:chat-id chat-id
                                            :message-id message-id
                                            :text "Capture dismissed."
                                            :reply-markup {:inline_keyboard []}}))
      :pending/cancel (do
                         (take-pending-reason! chat-id)
                         (edit-message! cfg {:chat-id chat-id
                                             :message-id message-id
                                             :text "Pending reason cancelled."
                                             :reply-markup {:inline_keyboard []}}))
      :task/edit-cancel (do
                          (take-pending-edit! chat-id)
                          (edit-message! cfg {:chat-id chat-id
                                              :message-id message-id
                                              :text "Edit cancelled."
                                              :reply-markup {:inline_keyboard []}}))
      :task/edit-title (let [tid (:task-id parsed)
                             task-id (try (UUID/fromString tid) (catch Exception _ nil))]
                         (if (and chat-user task-id)
                           (start-pending-edit! state chat-id chat-user task-id :edit-title message-id)
                           (send-message! cfg {:chat-id chat-id
                                               :text "Cannot edit task."
                                               :message-key (str "task-edit-title-error-" (System/currentTimeMillis))})))
      :task/edit-desc (let [tid (:task-id parsed)
                            task-id (try (UUID/fromString tid) (catch Exception _ nil))]
                        (if (and chat-user task-id)
                          (start-pending-edit! state chat-id chat-user task-id :edit-desc message-id)
                          (send-message! cfg {:chat-id chat-id
                                              :text "Cannot edit task."
                                              :message-key (str "task-edit-desc-error-" (System/currentTimeMillis))})))
      :task/note-add (let [tid (:task-id parsed)
                           task-id (try (UUID/fromString tid) (catch Exception _ nil))]
                       (if (and chat-user task-id)
                         (start-pending-edit! state chat-id chat-user task-id :note-add message-id)
                         (send-message! cfg {:chat-id chat-id
                                             :text "Cannot add note."
                                             :message-key (str "task-note-add-error-" (System/currentTimeMillis))})))
      :task/note-edit (let [tid (:task-id parsed)
                            task-id (try (UUID/fromString tid) (catch Exception _ nil))]
                        (if (and chat-user task-id)
                          (start-pending-edit! state chat-id chat-user task-id :note-edit message-id)
                          (send-message! cfg {:chat-id chat-id
                                              :text "Cannot edit note."
                                              :message-key (str "task-note-edit-error-" (System/currentTimeMillis))})))
      :task/note-delete (let [tid (:task-id parsed)
                              task-id (try (UUID/fromString tid) (catch Exception _ nil))]
                          (if (and chat-user task-id)
                            (let [res (actions/execute! state {:action/id :cap/action/task-delete-note
                                                               :actor (actions/actor-from-telegram chat-user)
                                                               :input {:task/id task-id}})]
                              (if-let [err (:error res)]
                                (send-message! cfg {:chat-id chat-id
                                                    :text (str "Unable to delete note: " (:message err))
                                                    :message-key (str "task-note-delete-error-" (System/currentTimeMillis))})
                                (send-message! cfg {:chat-id chat-id
                                                    :text "Latest note deleted."
                                                    :message-key (str "task-note-delete-" (System/currentTimeMillis))})))
                            (send-message! cfg {:chat-id chat-id
                                                :text "Cannot delete note."
                                                :message-key (str "task-note-delete-invalid-" (System/currentTimeMillis))})))
      :tasks/filter (let [filters (or (get-task-list! chat-id message-id)
                                      {:status nil :archived :active})
                          new-filters (case (:filter parsed)
                                        :status (assoc filters :status (:value parsed))
                                        :archived (assoc filters :archived (:value parsed))
                                        :refresh filters
                                        filters)]
                      (if chat-user
                        (let [resp (list-user-tasks conn (:user/id chat-user) new-filters)
                              tasks (:tasks resp)
                              pending-reasons (into {}
                                                    (keep (fn [task]
                                                            (when-let [reason (pending-reason-for-task db task)]
                                                              [(:task/id task) reason])))
                                                    tasks)
                              text (tasks-summary-text tasks pending-reasons)
                              header (str "Tasks"
                                          (when-let [status (:status new-filters)]
                                            (str " • " (name status)))
                                          (case (:archived new-filters)
                                            :archived " • archived"
                                            :all " • all"
                                            ""))
                              body (if (seq tasks) (str header "\n" text) (str header "\nNo tasks found."))
                              keyboard (tasks-list-keyboard tasks new-filters)]
                          (save-task-list! chat-id message-id new-filters)
                          (edit-message! cfg {:chat-id chat-id
                                              :message-id message-id
                                              :text body
                                              :reply-markup keyboard}))
                        (send-message! cfg {:chat-id chat-id
                                            :text "Chat not linked."
                                            :message-key (str "tasks-filter-unlinked-" message-id)})))
      :task/view (let [tid (:task-id parsed)
                       task-id (try (UUID/fromString tid) (catch Exception _ nil))]
                   (if (and chat-user task-id)
                     (if-let [task (find-user-task conn (:user/id chat-user) task-id)]
                       (send-task-card! state chat-id task {:reply-to-message-id message-id})
                       (send-message! cfg {:chat-id chat-id
                                           :text "Task not found."
                                           :message-key (str "task-not-found-" tid)}))
                     (send-message! cfg {:chat-id chat-id
                                         :text "Cannot view task."
                                         :message-key (str "task-view-error-" (System/currentTimeMillis))})))
      :task/status (let [tid (:task-id parsed)
                         task-id (try (UUID/fromString tid) (catch Exception _ nil))
                         new-status (keyword (:value parsed))]
                     (if (and chat-user task-id new-status)
                       (if (= new-status :pending)
                         (if-let [task (find-user-task conn (:user/id chat-user) task-id)]
                           (let [title (or (:task/title task) "Task")
                                 prompt (str "Send a pending reason for:\n" title)
                                 send-res (send-message! cfg {:chat-id chat-id
                                                              :text prompt
                                                              :message-key (str "pending-reason-" tid)
                                                              :reply-markup (pending-reason-inline-keyboard tid)})]
                             (when-not (:error send-res)
                               (take-pending-edit! chat-id)
                               (save-pending-reason! chat-id {:task-id task-id
                                                              :user chat-user}))
                             send-res)
                           (send-message! cfg {:chat-id chat-id
                                               :text "Task not found."
                                               :message-key (str "pending-task-missing-" tid)}))
                         (let [res (actions/execute! state {:action/id :cap/action/task-set-status
                                                            :actor (actions/actor-from-telegram chat-user)
                                                            :input {:task/id task-id
                                                                    :task/status new-status}})]
                           (if-let [err (:error res)]
                             (send-message! cfg {:chat-id chat-id
                                                 :text (str "Unable to update status: " (:message err))
                                                 :message-key (str "task-status-error-" tid)})
                             (send-task-card! state chat-id (get-in res [:result :task]) {:reply-to-message-id message-id}))))
                       (send-message! cfg {:chat-id chat-id
                                           :text "Invalid status action."
                                           :message-key (str "task-status-invalid-" (System/currentTimeMillis))})))
      :task/archive (let [tid (:task-id parsed)
                          task-id (try (UUID/fromString tid) (catch Exception _ nil))
                          archived? (= "true" (:value parsed))]
                      (if (and chat-user task-id (some? archived?))
                        (let [res (actions/execute! state {:action/id :cap/action/task-archive
                                                           :actor (actions/actor-from-telegram chat-user)
                                                           :input {:task/id task-id
                                                                   :task/archived? archived?}})]
                          (if-let [err (:error res)]
                            (send-message! cfg {:chat-id chat-id
                                                :text (str "Unable to update archive: " (:message err))
                                                :message-key (str "task-archive-error-" tid)})
                            (send-task-card! state chat-id (get-in res [:result :task]) {:reply-to-message-id message-id})))
                        (send-message! cfg {:chat-id chat-id
                                            :text "Invalid archive action."
                                            :message-key (str "task-archive-invalid-" (System/currentTimeMillis))})))
      nil)))
(defn- parse-callback
  [data]
  (when (present-string? data)
    (let [parts (str/split data #":")]
      (case (first parts)
        "filter"
        (case (second parts)
          "status" {:type :tasks/filter
                    :filter :status
                    :value (when-let [v (nth parts 2 nil)]
                             (when-not (= v "all") (keyword v)))}
          "archived" {:type :tasks/filter
                      :filter :archived
                      :value (keyword (or (nth parts 2 nil) "active"))}
          "refresh" {:type :tasks/filter
                     :filter :refresh
                     :value nil}
          nil)
        "capture"
        (case (second parts)
          "create" {:type :capture/create}
          "cancel" {:type :capture/cancel}
          nil)
        "pending"
        (case (second parts)
          "cancel" {:type :pending/cancel
                    :task-id (nth parts 2 nil)}
          nil)
        "task"
        (case (second parts)
          "status" {:type :task/status
                    :task-id (nth parts 2 nil)
                    :value (nth parts 3 nil)}
          "archive" {:type :task/archive
                     :task-id (nth parts 2 nil)
                     :value (nth parts 3 nil)}
          "view" {:type :task/view
                  :task-id (nth parts 2 nil)}
          "edit" (case (nth parts 2 nil)
                   "title" {:type :task/edit-title
                            :task-id (nth parts 3 nil)}
                   "desc" {:type :task/edit-desc
                           :task-id (nth parts 3 nil)}
                   "cancel" {:type :task/edit-cancel
                             :task-id (nth parts 3 nil)}
                   nil)
          "note" (case (nth parts 2 nil)
                   "add" {:type :task/note-add
                          :task-id (nth parts 3 nil)}
                   "edit" {:type :task/note-edit
                           :task-id (nth parts 3 nil)}
                   "delete" {:type :task/note-delete
                             :task-id (nth parts 3 nil)}
                   nil)
          nil)
        nil))))

(defn- handle-command
  [state chat-id {:keys [command rest text from-id]}]
  (let [cfg (get-in state [:config :telegram])
        conn (ensure-conn state)
        db (when conn (d/db conn))
        chat-user (when db (user-by-chat-id db chat-id))
        chat-user (or chat-user
                      (when (and db from-id)
                        (when-let [auto-user (user-by-telegram-user-id db (long from-id))]
                          (when-let [res (bind-chat-for-user! state {:user auto-user :chat-id chat-id})]
                            (:user res))))
                      (auto-bind-user state db chat-id))]
    (case command
      :help {:text (str "Commands: /start <link-token>, /help, /tasks, /task <uuid>, "
                        "/new <title> [| description], /edit <task-id> <title> [| description], "
                        "/note <task-id> <comment>, /note-edit <task-id> <comment>, /stop.\n"
                        "Link chat with /start using a token from the app. Notifications require flags on.")}
      :start (let [token (or (some-> rest (str/split #"\s+" 2) first)
                             (some->> text
                                      (re-matches #"^/start(?:@[A-Za-z0-9_]+)?\s+(.*)$")
                                      second
                                      str/trim))
                   res (bind-chat! state {:token token :chat-id chat-id})]
               (if-let [err (:error res)]
                 (if (= err "Missing link token")
                   {:text "Missing link token. Generate one in the app (POST /api/telegram/link-token) and send: /start <token>."}
                   {:text (str "Cannot link chat: " err)})
                 (do
                   (let [user (:user res)
                         event (events/new-event {:event/type :telegram/linked
                                                  :event/source "telegram"
                                                  :event/payload {:user/id (:user/id user)
                                                                  :chat-id chat-id}
                                                  :actor (actions/actor-from-telegram user)})]
                     (when-not (:error event)
                       (actions/apply-event! state event)))
                   {:text (str "Chat linked to " (get-in res [:user :user/username]) ". Notifications remain gated by flags.")})))
      :stop (let [res (unbind-chat! state chat-id)]
              (if-let [err (:error res)]
                {:text (str "Cannot stop: " err)}
                {:text "Chat unlinked. Notifications stopped."}))
      :tasks (if-not chat-user
               {:text "Chat not linked. Use /start <token> from the app to link."}
               (let [filters {:status nil :archived :active}
                     resp (list-user-tasks conn (:user/id chat-user) filters)
                     tasks (:tasks resp)
                     pending-reasons (into {}
                                           (keep (fn [task]
                                                   (when-let [reason (pending-reason-for-task db task)]
                                                     [(:task/id task) reason])))
                                           tasks)]
                 (if-let [err (:error resp)]
                   {:text (str "Unable to list tasks: " (:message err))}
                   {:task-list {:tasks (:tasks resp)
                                :filters filters
                                :pending-reasons pending-reasons}})))
      :task (if-not chat-user
              {:text "Chat not linked. Use /start <token> from the app to link."}
              (let [raw (some-> rest (str/split #"\s+" 2) first)
                    task-id (when raw (try (UUID/fromString (str/trim raw)) (catch Exception _ nil)))]
                (cond
                  (nil? task-id) {:text "Invalid task id. Use /task <uuid>."}
                  :else
                  (let [task (find-user-task conn (:user/id chat-user) task-id)]
                    {:task task}))))
      :edit (if-not chat-user
              {:text "Chat not linked. Use /start <token> from the app to link."}
              (let [{:keys [task-id body]} (parse-task-command rest)]
                (cond
                  (nil? task-id) {:text "Usage: /edit <task-id> <title> [| description]"}
                  (str/blank? body) {:text "Usage: /edit <task-id> <title> [| description]"}
                  :else
                  (let [[title desc] (if (str/includes? body "|")
                                       (map str/trim (str/split body #"\|" 2))
                                       [body nil])
                        title (when-not (str/blank? title) title)
                        desc (when-not (str/blank? desc) desc)
                        input (cond-> {:task/id task-id}
                                title (assoc :task/title title)
                                desc (assoc :task/description desc))]
                    (if (and (nil? title) (nil? desc))
                      {:text "Provide a new title and/or description to edit the task."}
                      (let [action-res (actions/execute! state {:action/id :cap/action/task-update
                                                                :actor (actions/actor-from-telegram chat-user)
                                                                :input input})]
                        (if-let [err (:error action-res)]
                          {:text (str "Unable to edit task: " (:message err))}
                          {:task (get-in action-res [:result :task])})))))))
      :note (if-not chat-user
              {:text "Chat not linked. Use /start <token> from the app to link."}
              (let [{:keys [task-id body]} (parse-task-command rest)]
                (cond
                  (nil? task-id) {:text "Usage: /note <task-id> <comment>"}
                  (str/blank? body) {:text "Usage: /note <task-id> <comment>"}
                  :else
                  (let [action-res (actions/execute! state {:action/id :cap/action/task-add-note
                                                            :actor (actions/actor-from-telegram chat-user)
                                                            :input {:task/id task-id
                                                                    :note/body body}})]
                    (if-let [err (:error action-res)]
                      {:text (str "Unable to add note: " (:message err))}
                      {:text "Note added to task."})))))
      :note-edit (if-not chat-user
                   {:text "Chat not linked. Use /start <token> from the app to link."}
                   (let [{:keys [task-id body]} (parse-task-command rest)]
                     (cond
                       (nil? task-id) {:text "Usage: /note-edit <task-id> <comment>"}
                       (str/blank? body) {:text "Usage: /note-edit <task-id> <comment>"}
                       :else
                       (let [action-res (actions/execute! state {:action/id :cap/action/task-edit-note
                                                                 :actor (actions/actor-from-telegram chat-user)
                                                                 :input {:task/id task-id
                                                                         :note/body body}})]
                         (if-let [err (:error action-res)]
                           {:text (str "Unable to edit note: " (:message err))}
                           {:text "Latest note updated."})))))
      :new (if-not chat-user
             {:text "Chat not linked. Use /start <token> from the app to link."}
             (let [raw (str/trim (or rest ""))
                   [title desc] (if (str/includes? raw "|")
                                  (map str/trim (str/split raw #"\|" 2))
                                  [(str/trim raw) nil])
                   title (when-not (str/blank? title) title)
                   desc (when-not (str/blank? desc) desc)]
               (cond
                 (nil? title) {:text "Usage: /new <title> [| description]"}
                 :else
                 (let [body {:task/title title
                             :task/description (or desc (str "Created via Telegram: " title))
                             :task/status :todo
                             :task/priority :medium
                             :task/assignee (:user/id chat-user)}
                       action-res (actions/execute! state {:action/id :cap/action/task-create
                                                          :actor (actions/actor-from-telegram chat-user)
                                                          :input body})]
                   (if-let [err (:error action-res)]
                     {:text (str "Unable to create task: " (:message err))}
                     {:task (get-in action-res [:result :task])})))))
      {:text "Unknown command. Send /help for available commands."})))

(defn notify-task-event!
  "Best-effort notification helper. Does nothing unless notifications are enabled and assignee has a chat id mapping."
  [state {:keys [event task actor]}]
  (let [cfg (get-in state [:config :telegram])]
    (when (and (notifications-enabled? cfg) task)
      (when-let [conn (ensure-conn state)]
        (try
          (let [db (d/db conn)
                assignee-id (get-in task [:task/assignee :user/id])
                chat-id (chat-id-by-user-id db assignee-id)]
            (when (and (not (str/blank? chat-id))
                       (not (str/blank? (:bot-token cfg))))
              (let [task-id (str (:task/id task))
                    title (:task/title task)
                    status-key (or (some-> (:task/status task) name) "unknown")
                    status (status-label (:task/status task))
                    due (or (:task/due-date task) "none")
                    actor-name (or (:user/username actor)
                                   (:user/name actor)
                                   (some-> (:user/id actor) str)
                                   "system")
                    text (task-notification-text event title status due actor-name)
                    message-key (case event
                                  :task/created (str "task-created:" task-id)
                                  :task/assigned (str "task-assigned:" task-id ":" (or (some-> assignee-id str) "none"))
                                  :task/status-changed (str "task-status:" task-id ":" status-key)
                                  :task/due-changed (str "task-due:" task-id ":" due)
                                  (str "task-update:" task-id))]
                (when-let [err (:error (outbox/enqueue! conn {:integration :integration/telegram
                                                              :payload {:chat-id chat-id
                                                                        :text text
                                                                        :message-key message-key}
                                                              :dedupe-key message-key}))]
                  (log/warn "Telegram notification enqueue failed" {:event event :error err})))))
          (catch Exception e
            (log/warn e "Telegram notification failed")))))))

(defn handle-update
  "Process a Telegram update payload. Returns {:status ...} or {:error ...}."
  [state update]
  (let [cfg (get-in state [:config :telegram])]
    (cond
      (and (not (webhook-enabled? cfg))
           (not (polling-enabled? cfg))) {:status :ignored :reason :webhook-disabled}
      (not (commands-enabled? cfg)) {:status :ignored :reason :commands-disabled}
      :else
      (let [{:keys [update-id chat-id command callback text] :as parsed} (extract-update update)]
        (cond
          (nil? update-id) {:error "Missing update id"}
          (str/blank? chat-id) {:error "Missing chat id"}
          (and (nil? command) (nil? callback) (str/blank? text)) {:status :ignored :reason :unsupported-command}
          :else
          (do
            (log-telegram-message! state {:chat-id chat-id
                                          :from-id (:from-id parsed)
                                          :text (or text (some-> command name))
                                          :update-id update-id
                                          :message-id (:message-id parsed)
                                          :direction :inbound})
            (let [response (cond
                           callback (handle-callback state chat-id callback)
                           command (handle-command state chat-id parsed)
                           :else (let [conn (ensure-conn state)
                                       db (when conn (d/db conn))
                                       chat-user (when db (user-by-chat-id db chat-id))
                                       chat-user (or chat-user
                                                     (when (and db (:from-id parsed))
                                                       (when-let [auto-user (user-by-telegram-user-id db (long (:from-id parsed)))]
                                                         (when-let [res (bind-chat-for-user! state {:user auto-user :chat-id chat-id})]
                                                           (:user res))))
                                                     (auto-bind-user state db chat-id))]
                                   (if chat-user
                                     (if (get-pending-edit! chat-id)
                                       (handle-pending-edit-message state chat-user chat-id text)
                                       (if (get-pending-reason! chat-id)
                                         (handle-pending-reason-message state chat-user chat-id text)
                                         (handle-freeform-message state chat-user chat-id text)))
                                     {:text "Chat not linked. Use /start <token> to link."})))
                {:keys [text tasks task task-list]} response]
            (cond
              task-list (let [{:keys [tasks filters pending-reasons]} task-list
                              header (str "Tasks"
                                          (when-let [status (:status filters)]
                                            (str " • " (name status)))
                                          (case (:archived filters)
                                            :archived " • archived"
                                            :all " • all"
                                            ""))
                              body (if (seq tasks)
                                     (str header "\n" (tasks-summary-text tasks pending-reasons))
                                     (str header "\nNo tasks found."))
                              send-res (send-message! cfg {:chat-id chat-id
                                                           :text body
                                                           :message-key (str "task-list-" update-id)
                                                           :reply-markup (tasks-list-keyboard tasks filters)})]
                          (when-let [mid (:telegram/message-id send-res)]
                            (save-task-list! chat-id mid filters))
                          {:status :handled})
              task (do
                     (send-task-card! state chat-id task {})
                     {:status :handled})
              tasks (do
                      (doseq [t tasks]
                        (send-task-card! state chat-id t {}))
                      (when (empty? tasks)
                        (send-message! cfg {:chat-id chat-id
                                            :text "No tasks found."
                                            :message-key (str "tasks-empty-" update-id)}))
                      {:status :handled})
              text (let [send-res (send-message! cfg {:chat-id chat-id
                                                      :text text
                                                      :message-key (str "update-" update-id "-" (or (some-> command name) "text"))})]
                     (if (:error send-res)
                       send-res
                       {:status :handled
                        :telegram/command command
                        :telegram/message-id (:telegram/message-id send-res)}))
              :else {:status :handled}))))))))
