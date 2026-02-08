(in-ns 'darelwasl.telegram)

(comment "Task UI + helpers for Telegram.")

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
  [chat-user {:keys [title desc client-id]}]
  (cond-> {:task/title title
           :task/description (or desc (str "Captured via chat: " title))
           :task/status :todo
           :task/priority :medium
           :task/assignee (:user/id chat-user)}
    client-id (assoc :task/client client-id)))

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

(defn- client-cancel-inline-keyboard
  [task-id]
  {:inline_keyboard
   [[(inline-button "Cancel" (str "task:client:cancel:" task-id))]]})

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
       (inline-button "Delete task" (str "task:delete:" id))]
      [(inline-button "Tasks" "filter:refresh")
       (inline-button (if archived? "Unarchive" "Archive")
                      (str "task:archive:" id ":" (if archived? "false" "true")))]
      [(inline-button "Refresh" (str "task:view:" id))]]}))

(defn- status-label
  [status]
  (let [label (or (some-> status name) "unknown")
        icon (get status-emoji status "⚪️")]
    (str icon " " label)))

(defn- format-task-line
  [task pending-reason]
  (let [status (status-label (:task/status task))
        due (or (:task/due-date task) "none")
        title (or (:task/title task) "Untitled task")
        client-name (get-in task [:task/client :client/name])]
    (str "- " status " " title " (due " due ")"
         (when client-name
           (str " · " client-name))
         (when pending-reason
           (str " · Reason: " (truncate-text pending-reason 80))))))

(defn- tasks-summary-text
  [tasks pending-reasons]
  (if (empty? tasks)
    "You have no tasks assigned."
    (str "Your tasks:\n"
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
          assignee (get-in task [:task/assignee :user/username] "n/a")
          client-name (get-in task [:task/client :client/name])]
      (str (or (:task/title task) "Untitled task") "\n"
           "Status: " status "\n"
           (when client-name (str "Client: " client-name "\n"))
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
  [state chat-id task {:keys [reply-to-message-id]}]
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

(defn- ensure-default-client-id
  [state workspace]
  (when-let [conn (ensure-conn state)]
    (or (:client/id (clients/ensure-default-client! conn workspace))
        clients/default-client-id)))

(defn- prompt-link-client!
  [state chat-id task]
  (let [cfg (get-in state [:config :telegram])
        task-id (str (:task/id task))
        title (or (:task/title task) "Task")
        prompt (str "Link to client for:\n" title)
        keyboard (link-client-inline-keyboard task-id)]
    (send-message! cfg {:chat-id chat-id
                        :text prompt
                        :message-key (str "task-link-client-" task-id "-" (System/currentTimeMillis))
                        :reply-markup keyboard})))

(defn- prompt-client-pick!
  [state chat-id task-id]
  (let [cfg (get-in state [:config :telegram])
        conn (ensure-conn state)
        workspace nil
        res (clients/list-clients conn {:limit 6} workspace)
        client-list (or (:clients res) [])
        prompt (if (seq client-list)
                 "Pick a client:"
                 "No clients available yet. Create one?")
        keyboard (client-pick-inline-keyboard task-id client-list)]
    (send-message! cfg {:chat-id chat-id
                        :text prompt
                        :message-key (str "client-pick-" task-id "-" (System/currentTimeMillis))
                        :reply-markup keyboard})))

(defn- list-user-tasks
  [conn user-id {:keys [status archived limit] :or {limit tasks-list-limit}}]
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
  (let [res (list-user-tasks conn user-id {:limit tasks-list-limit
                                           :archived :all})]
    (when-not (:error res)
      (->> (:tasks res)
           (filter #(= (:task/id %) task-id))
           first))))

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

