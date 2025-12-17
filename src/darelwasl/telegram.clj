(ns darelwasl.telegram
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.tasks :as tasks])
  (:import (java.util UUID)))

(def ^:private default-timeout-ms 3000)

(def ^:private allowed-commands
  #{:start :help :tasks :task :stop})

(defn webhook-enabled?
  [cfg]
  (true? (:webhook-enabled? cfg)))

(defn commands-enabled?
  [cfg]
  (true? (:commands-enabled? cfg)))

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
      (try
        (let [resp (http/post (bot-url cfg path)
                              {:headers {"Content-Type" "application/json"}
                               :body (json/write-str payload)
                               :socket-timeout timeout
                               :conn-timeout timeout
                               :as :json})]
          (if (true? (get-in resp [:body :ok]))
            {:result (get-in resp [:body :result])}
            {:error (or (get-in resp [:body :description])
                        "Telegram API error")
             :details (select-keys (:body resp) [:description :error_code])}))
        (catch Exception e
          (log/warn e "Telegram API request failed" {:path path})
          {:error "Telegram API request failed"})))))

(defn send-message!
  "Send a Telegram message to chat-id. Returns {:telegram/message-id ...} or {:error ...}."
  [cfg {:keys [chat-id text parse-mode message-key]}]
  (cond
    (str/blank? chat-id) {:error "Missing chat id"}
    (str/blank? text) {:error "Missing message text"}
    (str/blank? message-key) {:error "Missing message key for idempotency"}
    :else
    (let [payload (cond-> {:chat_id chat-id
                           :text text}
                    parse-mode (assoc :parse_mode (name parse-mode)))
          resp (request-json cfg "sendMessage" payload)]
      (if-let [err (:error resp)]
        resp
        {:telegram/message-id (get-in resp [:result :message_id])}))))

(defn set-webhook!
  [cfg {:keys [webhook-url secret-token]}]
  (cond
    (str/blank? webhook-url) {:error "Missing webhook URL"}
    (str/blank? secret-token) {:error "Missing webhook secret token"}
    :else
    (request-json cfg "setWebhook" {:url webhook-url
                                    :secret_token secret-token
                                    :allowed_updates ["message"]})))

(defn ensure-link-token!
  "Generate and persist a new link token for the given user id. Returns {:token \"...\"} or {:error ...}."
  [state user-id]
  (if-let [conn (ensure-conn state)]
    (try
      (let [token (str (UUID/randomUUID))]
        (d/transact conn {:tx-data [[:db/add [:user/id user-id] :user/telegram-link-token token]]})
        {:token token})
      (catch Exception e
        (log/warn e "Failed to create Telegram link token" {:user-id user-id})
        {:error "Unable to create link token"}))
    {:error "No database connection"}))

(defn- user-by-link-token
  [db token]
  (when-not (str/blank? token)
    (-> (d/q '[:find (pull ?u [:user/id :user/username :user/name :user/telegram-link-token :user/telegram-chat-id])
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
            (let [existing (user-by-chat-id db chat-id)
                  tx (cond-> []
                       existing (conj [:db/retract [:user/id (:user/id existing)] :user/telegram-chat-id (:user/telegram-chat-id existing)])
                       (:user/telegram-link-token user) (conj [:db/retract [:user/id (:user/id user)] :user/telegram-link-token (:user/telegram-link-token user)])
                       true (conj [:db/add [:user/id (:user/id user)] :user/telegram-chat-id chat-id]))]
              (d/transact conn {:tx-data tx})
              {:user (assoc user :user/telegram-chat-id chat-id)})))
        (catch Exception e
          (log/warn e "Failed to bind Telegram chat" {:chat-id chat-id})
          {:error "Unable to bind chat"}))
      {:error "No database connection"})))

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
            (d/transact conn {:tx-data [[:db/retract [:user/id (:user/id user)] :user/telegram-chat-id chat-id]]})
            {:status :ok
             :user user})))
      (catch Exception e
        (log/warn e "Failed to unbind Telegram chat" {:chat-id chat-id})
        {:error "Unable to unbind chat"}))
    {:error "No database connection"}))

(defn- parse-command
  [text]
  (when (and text (str/starts-with? text "/"))
    (let [[cmd & args] (-> text str/trim (str/split #"\s+"))
          cmd (some-> cmd (str/replace #"^/" "") keyword)]
      (when (allowed-commands cmd)
        {:command cmd
         :args args}))))

(defn- extract-update
  [update]
  (let [message (or (:message update) (get update "message"))
        update-id (or (:update_id update) (get update "update_id"))
        chat-id (or (get-in message [:chat :id])
                    (get-in message ["chat" "id"]))
        text (or (:text message) (get message "text"))
        {:keys [command args]} (parse-command text)]
    {:update-id update-id
     :chat-id (some-> chat-id str)
     :text text
     :command command
     :args args}))

(defn- format-task-line
  [task]
  (let [id-frag (some-> (:task/id task) str (subs 0 8))
        status (name (:task/status task))
        due (or (:task/due-date task) "none")]
    (str "- [" status "] " (:task/title task) " (id " id-frag ", due " due ")")))

(defn- tasks-summary-text
  [tasks]
  (if (empty? tasks)
    "You have no tasks assigned."
    (str "Your top tasks:\n" (str/join "\n" (map format-task-line tasks)))))

(defn- task-detail-text
  [task]
  (if-not task
    "Task not found or not assigned to you."
    (let [id-str (str (:task/id task))
          status (name (:task/status task))
          due (or (:task/due-date task) "none")
          assignee (get-in task [:task/assignee :user/username] "n/a")]
      (str "Task " id-str "\n"
           (:task/title task) "\n"
           "Status: " status "\n"
           "Due: " due "\n"
           "Assignee: " assignee))))

(defn- list-user-tasks
  [conn user-id limit]
  (tasks/list-tasks conn {:filters {:assignee user-id
                                    :archived :all}
                          :limit limit
                          :offset 0}))

(defn- find-user-task
  [conn user-id task-id]
  (let [res (list-user-tasks conn user-id 100)]
    (when-not (:error res)
      (->> (:tasks res)
           (filter #(= (:task/id %) task-id))
           first))))

(defn- handle-command
  [state chat-id {:keys [command args]}]
  (let [cfg (get-in state [:config :telegram])
        conn (ensure-conn state)
        db (when conn (d/db conn))
        chat-user (when db (user-by-chat-id db chat-id))]
    (case command
      :help {:text "Commands: /start <link-token>, /help, /tasks, /task <id>, /stop.\nLink chat with /start using a token from the app. Notifications require flags on."}
      :start (let [token (first args)
                   res (bind-chat! state {:token token :chat-id chat-id})]
               (if-let [err (:error res)]
                 {:text (str "Cannot link chat: " err)}
                 {:text (str "Chat linked to " (get-in res [:user :user/username]) ". Notifications remain gated by flags.")}))
      :stop (let [res (unbind-chat! state chat-id)]
              (if-let [err (:error res)]
                {:text (str "Cannot stop: " err)}
                {:text "Chat unlinked. Notifications stopped."}))
      :tasks (if-not chat-user
               {:text "Chat not linked. Use /start <token> from the app to link."}
               (let [resp (list-user-tasks conn (:user/id chat-user) 5)]
                 (if-let [err (:error resp)]
                   {:text (str "Unable to list tasks: " (:message err))}
                   {:text (tasks-summary-text (:tasks resp))})))
      :task (if-not chat-user
              {:text "Chat not linked. Use /start <token> from the app to link."}
              (let [raw (first args)
                    task-id (when raw (try (UUID/fromString (str/trim raw)) (catch Exception _ nil)))]
                (cond
                  (nil? task-id) {:text "Invalid task id. Use /task <uuid>."}
                  :else
                  (let [task (find-user-task conn (:user/id chat-user) task-id)]
                    {:text (task-detail-text task)}))))
      {:text "Unknown command. Send /help for available commands."})))

(defn handle-update
  "Process a Telegram update payload. Returns {:status ...} or {:error ...}."
  [state update]
  (let [cfg (get-in state [:config :telegram])]
    (cond
      (not (webhook-enabled? cfg)) {:status :ignored :reason :webhook-disabled}
      (not (commands-enabled? cfg)) {:status :ignored :reason :commands-disabled}
      :else
      (let [{:keys [update-id chat-id command] :as parsed} (extract-update update)]
        (cond
          (nil? update-id) {:error "Missing update id"}
          (str/blank? chat-id) {:error "Missing chat id"}
          (nil? command) {:status :ignored :reason :unsupported-command}
          :else
          (let [{:keys [text]} (handle-command state chat-id parsed)
                send-res (send-message! cfg {:chat-id chat-id
                                             :text (or text "No response.")
                                             :message-key (str "update-" update-id "-" (name command))})]
            (if (:error send-res)
              send-res
              {:status :handled
               :telegram/command command
               :telegram/message-id (:telegram/message-id send-res)})))))))
