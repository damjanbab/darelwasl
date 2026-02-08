(in-ns 'darelwasl.telegram)

(comment "Telegram update extraction + top-level handler.")

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

(declare handle-callback)

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
                                           (if (get-pending-client! chat-id)
                                             (handle-pending-client-message state chat-user chat-id text)
                                               (if (get-pending-next-action! chat-id)
                                                 (handle-pending-next-action-message state chat-user chat-id text)
                                               (if (get-report-card-session! chat-id)
                                                 (handle-report-card-message state chat-user chat-id text)
                                                 (if (get-docs-session! chat-id)
                                                   (handle-docs-message state chat-user chat-id text)
                                                   (if (get-services-session! chat-id)
                                                     (handle-services-message state chat-user chat-id text)
                                                     (handle-freeform-message state chat-user chat-id text))))))))
                                       {:text "Chat not linked. Use /start <token> to link."})))
                  {:keys [text tasks task task-list link-task]} response]
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
                       (when link-task
                         (prompt-link-client! state chat-id link-task))
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
                                                        :message-key (str "update-" update-id "-" (or (some-> command name) "text"))
                                                        :reply-markup (:reply-markup response)})]
                       (if (:error send-res)
                         send-res
                         {:status :handled
                          :telegram/command command
                          :telegram/message-id (:telegram/message-id send-res)}))
                :else {:status :handled}))))))))
