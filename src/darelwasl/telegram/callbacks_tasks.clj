(in-ns 'darelwasl.telegram)

(comment "Callback handlers: tasks/capture/pending/client/filter.")

(defmethod
 handle-callback-dispatch
 :capture/cancel
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (do
   (take-capture! chat-id message-id)
   (edit-message!
    cfg
    {:chat-id chat-id,
     :message-id message-id,
     :text "Capture dismissed.",
     :reply-markup {:inline_keyboard []}}))))

(defmethod
 handle-callback-dispatch
 :capture/client
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (let
   [capture
    (take-capture! chat-id message-id)
    actor
    (actions/actor-from-telegram (:user capture))
    name
    (some-> (:text capture) str/trim)]
   (if
    (and capture actor (present-string? name))
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/client-create,
        :actor actor,
        :input #:client{:name name}})]
     (if-let
      [err (:error res)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to create client: " (:message err)),
        :message-key (str "capture-client-error-" message-id)})
      (do
       (edit-message!
        cfg
        {:chat-id chat-id,
         :message-id message-id,
         :text (str "Client created: " name),
         :reply-markup {:inline_keyboard []}})
       (prompt-next-action!
        state
        chat-id
        (get-in res [:result :client])))))
    (edit-message!
     cfg
     {:chat-id chat-id,
      :message-id message-id,
      :text "Capture expired.",
      :reply-markup {:inline_keyboard []}})))))

(defmethod
 handle-callback-dispatch
 :capture/task
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (let
   [capture
    (take-capture! chat-id message-id)
    actor
    (actions/actor-from-telegram (:user capture))]
   (if
    (and capture actor)
    (let
     [default-client
      (ensure-default-client-id state (:actor/workspace actor))
      body
      (task-body
       (:user capture)
       {:title (:text capture), :desc nil, :client-id default-client})
      res
      (actions/execute!
       state
       {:action/id :cap/action/task-create,
        :actor actor,
        :input body})]
     (if-let
      [err (:error res)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to create task: " (:message err)),
        :message-key (str "capture-create-error-" message-id)})
      (let
       [task (get-in res [:result :task])]
       (send-task-card!
        state
        chat-id
        task
        {:reply-to-message-id message-id})
       (prompt-link-client! state chat-id task))))
    (edit-message!
     cfg
     {:chat-id chat-id,
      :message-id message-id,
      :text "Capture expired.",
      :reply-markup {:inline_keyboard []}})))))

(defmethod
 handle-callback-dispatch
 :client/action
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (let
   [cid
    (:client-id parsed)
    client-id
    (try (UUID/fromString (str cid)) (catch Exception _ nil))
    action-key
    (:value parsed)
    client
    (when (and client-id db) (clients/client-by-id db client-id nil))
    name
    (or (:client/name client) "client")]
   (if
    (and chat-user client-id action-key)
    (case
     action-key
     "custom"
     (do
      (save-pending-next-action!
       chat-id
       {:client-id client-id, :user chat-user, :client-name name})
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Send the next action for " name "."),
        :message-key (str "client-custom-" (System/currentTimeMillis)),
        :reply-markup
        (client-action-cancel-inline-keyboard client-id)}))
     "dismiss"
     (do
      (take-pending-next-action! chat-id)
      (send-message!
       cfg
       {:chat-id chat-id,
        :text "Next action skipped.",
        :message-key
        (str "client-dismiss-" (System/currentTimeMillis))}))
     (let
      [[title desc]
       (case
        action-key
        "call"
        [(str "Call " name) (str "Call " name)]
        "docs"
        [(str "Request docs from " name)
         (str "Request docs from " name)]
        "followup"
        [(str "Follow up with " name) (str "Follow up with " name)]
        "meeting"
        [(str "Schedule meeting with " name)
         (str "Schedule meeting with " name)]
        [(str "Follow up with " name) (str "Follow up with " name)])
       body
       (task-body
        chat-user
        {:title title, :desc desc, :client-id client-id})
       res
       (actions/execute!
        state
        {:action/id :cap/action/task-create,
         :actor (actions/actor-from-telegram chat-user),
         :input body})]
      (if-let
       [err (:error res)]
       (send-message!
        cfg
        {:chat-id chat-id,
         :text (str "Unable to create task: " (:message err)),
         :message-key
         (str "client-action-error-" (System/currentTimeMillis))})
       (send-task-card!
        state
        chat-id
        (get-in res [:result :task])
        {:reply-to-message-id message-id}))))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid client action.",
      :message-key
      (str "client-action-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :pending/cancel
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (do
   (take-pending-reason! chat-id)
   (edit-message!
    cfg
    {:chat-id chat-id,
     :message-id message-id,
     :text "Pending reason cancelled.",
     :reply-markup {:inline_keyboard []}}))))

(defmethod
 handle-callback-dispatch
 :pending/followup
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (let
   [tid
    (:task-id parsed)
    task-id
    (try (UUID/fromString tid) (catch Exception _ nil))
    followup-id
    (:value parsed)
    pending
    (get-pending-reason! chat-id)]
   (if
    (and chat-user task-id pending (= (:task-id pending) task-id))
    (if
     (present-string? (:reason pending))
     (if
      (= "pick-date" followup-id)
      (do
       (let
        [text
         "Pick the follow-up date:"
         quicks
         [{:id :tomorrow, :label "Tomorrow"}
          {:id :in-3-days, :label "In 3 days"}
          {:id :next-week, :label "Next week"}]]
        (save-pending-reason!
         chat-id
         (assoc
          pending
          :stage
          :followup-date-picker
          :picker
          {:kind :pending/followup, :text text, :quicks quicks}))
        (send-message!
         cfg
         {:chat-id chat-id,
          :text text,
          :message-key (str "pending-followup-date-" tid),
          :reply-markup
          (date-picker-inline-keyboard
           {:month (LocalDate/now (ZoneId/systemDefault)),
            :quicks quicks,
            :extra-rows
            [[(inline-button
               "Cancel"
               (str "pending:cancel:" tid))]]})})))
      (let
       [followup
        (followup-date (keyword followup-id))
        res
        (actions/execute!
         state
         {:action/id :cap/action/task-set-status,
          :actor (actions/actor-from-telegram chat-user),
          :input
          (cond->
           {:task/id task-id,
            :task/status :pending,
            :note/body (:reason pending),
            :note/last-contact (now-inst)}
           followup
           (assoc :note/next-followup followup))})]
       (take-pending-reason! chat-id)
       (if-let
        [err (:error res)]
        (send-message!
         cfg
         {:chat-id chat-id,
          :text (str "Unable to set pending: " (:message err)),
          :message-key (str "pending-followup-error-" tid)})
        (send-task-card!
         state
         chat-id
         (get-in res [:result :task])
         {:reply-to-message-id message-id}))))
     (send-message!
      cfg
      {:chat-id chat-id,
       :text "Pending reason missing. Start again.",
       :message-key
       (str "pending-reason-missing-" (System/currentTimeMillis))}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Pending reason expired.",
      :message-key
      (str "pending-followup-expired-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :pending/reason
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (let
   [tid
    (:task-id parsed)
    task-id
    (try (UUID/fromString tid) (catch Exception _ nil))
    reason-id
    (:value parsed)
    current
    (get-pending-reason! chat-id)]
   (if
    (and
     chat-user
     task-id
     (or (nil? current) (= (:task-id current) task-id)))
    (if
     (= "custom" reason-id)
     (let
      [task
       (find-user-task conn (:user/id chat-user) task-id)
       title
       (or (:task/title task) "Task")
       prompt
       (str "Send a pending reason for:\n" title)]
      (take-pending-edit! chat-id)
      (save-pending-reason!
       chat-id
       {:task-id task-id, :user chat-user, :stage :reason})
      (send-message!
       cfg
       {:chat-id chat-id,
        :text prompt,
        :message-key (str "pending-custom-" tid),
        :reply-markup (pending-reason-inline-keyboard tid)}))
     (let
      [reason (pending-reason-text reason-id)]
      (if
       (str/blank? (str reason))
       (send-message!
        cfg
        {:chat-id chat-id,
         :text "Choose a valid pending reason.",
         :message-key (str "pending-reason-invalid-" tid)})
       (do
        (save-pending-reason!
         chat-id
         {:task-id task-id,
          :user chat-user,
          :stage :followup,
          :reason reason})
        (send-message!
         cfg
         {:chat-id chat-id,
          :text "When should I follow up?",
          :message-key (str "pending-followup-" tid),
          :reply-markup (pending-followup-inline-keyboard tid)})))))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Pending reason expired.",
      :message-key
      (str "pending-reason-expired-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :task/archive
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (let
   [tid
    (:task-id parsed)
    task-id
    (try (UUID/fromString tid) (catch Exception _ nil))
    archived?
    (= "true" (:value parsed))]
   (if
    (and chat-user task-id (some? archived?))
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/task-archive,
        :actor (actions/actor-from-telegram chat-user),
        :input #:task{:id task-id, :archived? archived?}})]
     (if-let
      [err (:error res)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to update archive: " (:message err)),
        :message-key (str "task-archive-error-" tid)})
      (send-task-card!
       state
       chat-id
       (get-in res [:result :task])
       {:reply-to-message-id message-id})))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid archive action.",
      :message-key
      (str "task-archive-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :task/client-cancel
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (do
   (take-pending-client! chat-id)
   (edit-message!
    cfg
    {:chat-id chat-id,
     :message-id message-id,
     :text "Client link cancelled.",
     :reply-markup {:inline_keyboard []}}))))

(defmethod
 handle-callback-dispatch
 :task/client-create
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (let
   [tid
    (:task-id parsed)
    task-id
    (try (UUID/fromString tid) (catch Exception _ nil))]
   (if
    (and chat-user task-id)
    (let
     [task
      (find-user-task conn (:user/id chat-user) task-id)
      title
      (or (:task/title task) "Task")
      prompt
      (str "Send a client name to link for:\n" title)]
     (save-pending-client!
      chat-id
      {:task-id task-id, :user chat-user, :mode :link})
     (send-message!
      cfg
      {:chat-id chat-id,
       :text prompt,
       :message-key (str "task-client-create-" tid),
       :reply-markup (client-cancel-inline-keyboard tid)}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Cannot create client.",
      :message-key
      (str "task-client-create-error-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :task/client-pick
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (let
   [tid
    (:task-id parsed)
    task-id
    (try (UUID/fromString tid) (catch Exception _ nil))]
   (if
    (and chat-user task-id)
    (prompt-client-pick! state chat-id (str task-id))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Cannot pick client.",
      :message-key
      (str "task-client-pick-error-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :task/client-set
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (let
   [tid
    (:task-id parsed)
    task-id
    (try (UUID/fromString tid) (catch Exception _ nil))
    cid
    (:client-id parsed)
    client-id
    (try (UUID/fromString (str cid)) (catch Exception _ nil))]
   (if
    (and chat-user task-id client-id)
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/task-set-client,
        :actor (actions/actor-from-telegram chat-user),
        :input #:task{:id task-id, :client client-id}})]
     (if-let
      [err (:error res)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to link client: " (:message err)),
        :message-key
        (str "task-client-set-error-" (System/currentTimeMillis))})
      (send-task-card!
       state
       chat-id
       (get-in res [:result :task])
       {:reply-to-message-id message-id})))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid client selection.",
      :message-key
      (str "task-client-set-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :task/client-skip
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (do
   (take-pending-client! chat-id)
   (send-message!
    cfg
    {:chat-id chat-id,
     :text "Client link skipped.",
     :message-key
     (str "task-client-skip-" (System/currentTimeMillis))}))))

(defmethod
 handle-callback-dispatch
 :task/delete
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (let
   [tid
    (:task-id parsed)
    task-id
    (try (UUID/fromString tid) (catch Exception _ nil))]
   (if
    (and chat-user task-id)
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/task-delete,
        :actor (actions/actor-from-telegram chat-user),
        :input #:task{:id task-id}})]
     (if-let
      [err (:error res)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to delete task: " (:message err)),
        :message-key
        (str "task-delete-error-" (System/currentTimeMillis))})
      (if
       message-id
       (edit-message!
        cfg
        {:chat-id chat-id,
         :message-id message-id,
         :text "Task deleted.",
         :reply-markup {:inline_keyboard []}})
       (send-message!
        cfg
        {:chat-id chat-id,
         :text "Task deleted.",
         :message-key
         (str "task-delete-" (System/currentTimeMillis))}))))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Cannot delete task.",
      :message-key
      (str "task-delete-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :task/edit-cancel
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (do
   (take-pending-edit! chat-id)
   (edit-message!
    cfg
    {:chat-id chat-id,
     :message-id message-id,
     :text "Edit cancelled.",
     :reply-markup {:inline_keyboard []}}))))

(defmethod
 handle-callback-dispatch
 :task/edit-desc
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (let
   [tid
    (:task-id parsed)
    task-id
    (try (UUID/fromString tid) (catch Exception _ nil))]
   (if
    (and chat-user task-id)
    (start-pending-edit!
     state
     chat-id
     chat-user
     task-id
     :edit-desc
     message-id)
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Cannot edit task.",
      :message-key
      (str "task-edit-desc-error-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :task/edit-title
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (let
   [tid
    (:task-id parsed)
    task-id
    (try (UUID/fromString tid) (catch Exception _ nil))]
   (if
    (and chat-user task-id)
    (start-pending-edit!
     state
     chat-id
     chat-user
     task-id
     :edit-title
     message-id)
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Cannot edit task.",
      :message-key
      (str "task-edit-title-error-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :task/note-add
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (let
   [tid
    (:task-id parsed)
    task-id
    (try (UUID/fromString tid) (catch Exception _ nil))]
   (if
    (and chat-user task-id)
    (start-pending-edit!
     state
     chat-id
     chat-user
     task-id
     :note-add
     message-id)
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Cannot add note.",
      :message-key
      (str "task-note-add-error-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :task/note-delete
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (let
   [tid
    (:task-id parsed)
    task-id
    (try (UUID/fromString tid) (catch Exception _ nil))]
   (if
    (and chat-user task-id)
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/task-delete-note,
        :actor (actions/actor-from-telegram chat-user),
        :input #:task{:id task-id}})]
     (if-let
      [err (:error res)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to delete note: " (:message err)),
        :message-key
        (str "task-note-delete-error-" (System/currentTimeMillis))})
      (send-message!
       cfg
       {:chat-id chat-id,
        :text "Latest note deleted.",
        :message-key
        (str "task-note-delete-" (System/currentTimeMillis))})))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Cannot delete note.",
      :message-key
      (str "task-note-delete-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :task/note-edit
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (let
   [tid
    (:task-id parsed)
    task-id
    (try (UUID/fromString tid) (catch Exception _ nil))]
   (if
    (and chat-user task-id)
    (start-pending-edit!
     state
     chat-id
     chat-user
     task-id
     :note-edit
     message-id)
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Cannot edit note.",
      :message-key
      (str "task-note-edit-error-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :task/status
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  :pending))

(defmethod
 handle-callback-dispatch
 :task/view
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (let
   [tid
    (:task-id parsed)
    task-id
    (try (UUID/fromString tid) (catch Exception _ nil))]
   (if
    (and chat-user task-id)
    (if-let
     [task (find-user-task conn (:user/id chat-user) task-id)]
     (send-task-card!
      state
      chat-id
      task
      {:reply-to-message-id message-id})
     (send-message!
      cfg
      {:chat-id chat-id,
       :text "Task not found.",
       :message-key (str "task-not-found-" tid)}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Cannot view task.",
      :message-key
      (str "task-view-error-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :tasks/filter
 [ctx]
 (let
  [{:keys
    [state
     chat-id
     message-id
     callback-id
     data
     conn
     db
     chat-user
     parsed
     cfg]}
   ctx]
  (let
   [filters
    (or
     (get-task-list! chat-id message-id)
     {:status nil, :archived :active, :limit tasks-list-limit})
    new-filters
    (case
     (:filter parsed)
     :status
     (assoc filters :status (:value parsed))
     :archived
     (assoc filters :archived (:value parsed))
     :refresh
     filters
     filters)]
   (if
    chat-user
    (let
     [resp
      (list-user-tasks conn (:user/id chat-user) new-filters)
      tasks
      (:tasks resp)
      pending-reasons
      (into
       {}
       (keep
        (fn
         [task]
         (when-let
          [reason (pending-reason-for-task db task)]
          [(:task/id task) reason])))
       tasks)
      text
      (tasks-summary-text tasks pending-reasons)
      header
      (str
       "Tasks"
       (when-let
        [status (:status new-filters)]
        (str " • " (name status)))
       (case
        (:archived new-filters)
        :archived
        " • archived"
        :all
        " • all"
        ""))
      body
      (if
       (seq tasks)
       (str header "\n" text)
       (str header "\nNo tasks found."))
      keyboard
      (tasks-list-keyboard tasks new-filters)]
     (save-task-list! chat-id message-id new-filters)
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text body,
       :reply-markup keyboard}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Chat not linked.",
      :message-key (str "tasks-filter-unlinked-" message-id)})))))

