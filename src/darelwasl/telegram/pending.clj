(in-ns 'darelwasl.telegram)

(comment "Pending flows + freeform message handlers.")

(defn- client-next-action-inline-keyboard
  [client-id]
  {:inline_keyboard
   [[(inline-button "Call client" (str "client:action:" client-id ":call"))
     (inline-button "Request docs" (str "client:action:" client-id ":docs"))]
    [(inline-button "Follow up" (str "client:action:" client-id ":followup"))
     (inline-button "Schedule meeting" (str "client:action:" client-id ":meeting"))]
    [(inline-button "Custom..." (str "client:action:" client-id ":custom"))
     (inline-button "Dismiss" (str "client:action:" client-id ":dismiss"))]]})

(defn- client-action-cancel-inline-keyboard
  [client-id]
  {:inline_keyboard
   [[(inline-button "Cancel" (str "client:action:" client-id ":dismiss"))]]})

(def ^:private pending-reason-options
  [{:id :client-waiting :label "Waiting on client"}
   {:id :docs :label "Need documents"}
   {:id :schedule :label "Scheduling"}
   {:id :payment :label "Payment pending"}
   {:id :custom :label "Custom..."}])

(defn- pending-reason-text
  [reason-id]
  (let [rid (cond
              (keyword? reason-id) (name reason-id)
              (string? reason-id) reason-id
              :else nil)]
    (or (some (fn [{:keys [id label]}]
                (when (= (name id) rid) label))
              pending-reason-options)
        rid)))

(def ^:private pending-followup-options
  [{:id :tomorrow :label "Tomorrow"}
   {:id :in-3-days :label "In 3 days"}
   {:id :next-week :label "Next week"}
   {:id :pick-date :label "Pick date"}])

(defn- pending-reason-inline-keyboard
  [task-id]
  (let [buttons (map (fn [{:keys [id label]}]
                       (inline-button label (str "pending:reason:" task-id ":" (name id))))
                     pending-reason-options)
        rows (->> buttons
                  (partition-all 2)
                  (mapv vec))]
    {:inline_keyboard
     (conj rows [(inline-button "Cancel" (str "pending:cancel:" task-id))])}))

(defn- pending-followup-inline-keyboard
  [task-id]
  (let [buttons (map (fn [{:keys [id label]}]
                       (inline-button label (str "pending:followup:" task-id ":" (name id))))
                     pending-followup-options)
        rows (->> buttons
                  (partition-all 2)
                  (mapv vec))]
    {:inline_keyboard
     (conj rows [(inline-button "Cancel" (str "pending:cancel:" task-id))])}))

(defn- pending-edit-inline-keyboard
  [task-id]
  {:inline_keyboard
   [[(inline-button "Cancel" (str "task:edit:cancel:" task-id))]]})

(defn- prompt-next-action!
  [state chat-id client]
  (let [cfg (get-in state [:config :telegram])
        client-id (str (:client/id client))
        name (or (:client/name client) "Client")
        prompt (str "Next action for " name "?")
        keyboard (client-next-action-inline-keyboard client-id)]
    (send-message! cfg {:chat-id chat-id
                        :text prompt
                        :message-key (str "client-next-action-" client-id "-" (System/currentTimeMillis))
                        :reply-markup keyboard})))

(defn- handle-freeform-message
  [state chat-user chat-id text]
  (let [cfg (get-in state [:config :telegram])
        prompt (str "Create from text?\n\n" text)
        send-res (send-message! cfg {:chat-id chat-id
                                     :text prompt
                                     :message-key (str "capture-" (System/currentTimeMillis))
                                     :reply-markup (capture-inline-keyboard "pending")})]
    (if-let [err (:error send-res)]
      (send-message! cfg {:chat-id chat-id
                          :text (str "Unable to capture: " err)
                          :message-key (str "capture-error-" (System/currentTimeMillis))})
      (let [message-id (:telegram/message-id send-res)]
        (save-capture! chat-id message-id {:user chat-user
                                           :text text})
        (edit-message! cfg {:chat-id chat-id
                            :message-id message-id
                            :text prompt
                            :reply-markup (capture-inline-keyboard message-id)})))))

(defn- handle-pending-reason-message
  [state _chat-user chat-id text]
  (let [cfg (get-in state [:config :telegram])
        pending (get-pending-reason! chat-id)]
    (when pending
      (let [task-id (:task-id pending)
            stage (:stage pending)
            trimmed (some-> text str/trim)]
        (cond
          (#{:followup-date :followup-date-picker} stage)
          (let [{:keys [text quicks]} (:picker pending)
                text (or text "Pick the follow-up date:")
                quicks (or quicks [{:id :tomorrow :label "Tomorrow"}
                                   {:id :in-3-days :label "In 3 days"}
                                   {:id :next-week :label "Next week"}])]
            (send-message! cfg {:chat-id chat-id
                                :text "Use the buttons to choose a date."
                                :message-key (str "pending-followup-date-click-" (System/currentTimeMillis))
                                :reply-markup (date-picker-inline-keyboard {:month (LocalDate/now (ZoneId/systemDefault))
                                                                            :quicks quicks
                                                                            :extra-rows [[(inline-button "Cancel" (str "pending:cancel:" task-id))]]})}))

          (= stage :followup)
          (send-message! cfg {:chat-id chat-id
                              :text "Use the buttons to choose a follow-up option."
                              :message-key (str "pending-followup-button-" (System/currentTimeMillis))
                              :reply-markup (pending-followup-inline-keyboard task-id)})

          :else
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Pending reason cannot be blank. Reply with a reason or tap Cancel."
                                :message-key (str "pending-reason-empty-" (System/currentTimeMillis))})
            (do
              (save-pending-reason! chat-id (assoc pending :stage :followup :reason trimmed))
              (send-message! cfg {:chat-id chat-id
                                  :text "When should I follow up?"
                                  :message-key (str "pending-followup-" (System/currentTimeMillis))
                                  :reply-markup (pending-followup-inline-keyboard task-id)}))))))))

(defn- handle-pending-client-message
  [state chat-user chat-id text]
  (let [cfg (get-in state [:config :telegram])
        pending (get-pending-client! chat-id)
        name (some-> text str/trim)]
    (when pending
      (if (str/blank? name)
        (send-message! cfg {:chat-id chat-id
                            :text "Client name cannot be blank. Reply with a name or tap Cancel."
                            :message-key (str "client-name-empty-" (System/currentTimeMillis))})
        (let [res (actions/execute! state {:action/id :cap/action/client-create
                                           :actor (actions/actor-from-telegram chat-user)
                                           :input {:client/name name}})
              client (get-in res [:result :client])
              task-id (:task-id pending)
              mode (:mode pending)]
          (take-pending-client! chat-id)
          (if-let [err (:error res)]
            (send-message! cfg {:chat-id chat-id
                                :text (str "Unable to create client: " (:message err))
                                :message-key (str "client-create-error-" (System/currentTimeMillis))})
            (if (and (= mode :link) task-id)
              (let [link-res (actions/execute! state {:action/id :cap/action/task-set-client
                                                      :actor (actions/actor-from-telegram chat-user)
                                                      :input {:task/id task-id
                                                              :task/client (:client/id client)}})]
                (if-let [link-err (:error link-res)]
                  (send-message! cfg {:chat-id chat-id
                                      :text (str "Unable to link client: " (:message link-err))
                                      :message-key (str "client-link-error-" (System/currentTimeMillis))})
                  (do
                    (send-message! cfg {:chat-id chat-id
                                        :text (str "Client linked: " name)
                                        :message-key (str "client-linked-" (System/currentTimeMillis))})
                    (send-task-card! state chat-id (get-in link-res [:result :task]) {}))))
              (do
                (send-message! cfg {:chat-id chat-id
                                    :text (str "Client created: " name)
                                    :message-key (str "client-created-" (System/currentTimeMillis))})
                (prompt-next-action! state chat-id client)))))))))

(defn- handle-pending-next-action-message
  [state chat-user chat-id text]
  (let [cfg (get-in state [:config :telegram])
        pending (get-pending-next-action! chat-id)
        title (some-> text str/trim)]
    (when pending
      (if (str/blank? title)
        (send-message! cfg {:chat-id chat-id
                            :text "Action cannot be blank. Reply with a title or tap Cancel."
                            :message-key (str "client-action-empty-" (System/currentTimeMillis))})
        (let [client-id (:client-id pending)
              desc (str "Next action for " (or (:client-name pending) "client"))
              body (task-body chat-user {:title title
                                         :desc desc
                                         :client-id client-id})
              res (actions/execute! state {:action/id :cap/action/task-create
                                           :actor (actions/actor-from-telegram chat-user)
                                           :input body})]
          (take-pending-next-action! chat-id)
          (if-let [err (:error res)]
            (send-message! cfg {:chat-id chat-id
                                :text (str "Unable to create task: " (:message err))
                                :message-key (str "client-action-error-" (System/currentTimeMillis))})
            (send-task-card! state chat-id (get-in res [:result :task]) {})))))))

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

