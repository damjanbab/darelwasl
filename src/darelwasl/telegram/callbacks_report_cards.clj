(in-ns 'darelwasl.telegram)

(comment "Callback handlers: report card flows.")

(defn- rc-parse-uuid
  [raw]
  (when (present-string? raw)
    (try (UUID/fromString (str raw)) (catch Exception _ nil))))

(defn- rc-task-by-id
  [db task-id]
  (when (and db task-id)
    (ffirst (d/q '[:find (pull ?t [:task/id :task/title :task/status :task/report-card-type
                                   {:task/client [:client/id :client/name]}
                                   {:task/assignee [:user/id :user/username]}])
                  :in $ ?id
                  :where [?t :task/id ?id]]
                db task-id))))

(defn- rc-edit!
  [cfg chat-id message-id text reply-markup]
  (edit-message!
   cfg
   {:chat-id chat-id
    :message-id message-id
    :text text
    :reply-markup reply-markup}))

(defn- rc-push-history
  [session]
  (update session :history (fnil conj []) {:stage (:stage session) :fields (:fields session)}))

(defn- rc-pop-history
  [session]
  (let [h (vec (or (:history session) []))]
    (when (seq h)
      (let [prev (peek h)]
        (-> session
            (assoc :stage (:stage prev)
                   :fields (:fields prev))
            (assoc :history (pop h)))))))

(defn- rc-render-stage!
  [state chat-id message-id]
  (let [cfg (get-in state [:config :telegram])
        session (get-report-card-session! chat-id)
        typ (:type session)
        stage (:stage session)
        services (when (= stage :rc/service)
                   (get-in (actions/execute! state {:action/id :cap/action/service-list
                                                   :actor {:actor/type :actor.type/system}})
                           [:result :services]))]
    (case stage
      :rc/contacted
      (rc-edit! cfg chat-id message-id
                (str (rc-title typ) "\n\nStep 1/4: Contacted the client?")
                (rc-contacted-keyboard))

      :rc/schedule
      (rc-edit! cfg chat-id message-id
                (str (rc-title typ) "\n\nStep 2/4: Call/meeting scheduled?")
                (rc-schedule-keyboard))

      :rc/pick-date
      (let [month (or (:picker/month session) (LocalDate/now (ZoneId/systemDefault)))]
        (rc-edit! cfg chat-id message-id
                  (str (rc-title typ) "\n\nPick the scheduled date:")
                  (date-picker-inline-keyboard
                   {:month month
                    :quicks [{:id :tomorrow :label "Tomorrow"}
                             {:id :in-3-days :label "In 3 days"}
                             {:id :next-week :label "Next week"}]
                    :extra-rows [[(inline-button "Skip" "rc:schedule:skip")
                                 (inline-button "Back" "rc:back")]
                                [(inline-button "Back to task" "rc:cancel")]]})))

      :rc/pick-time
      (rc-edit! cfg chat-id message-id
                (str (rc-title typ) "\n\nPick time (optional):")
                (time-picker-inline-keyboard
                 {:allow-skip? true
                  :skip-label "Skip time"
                  :extra-rows [[(inline-button "Back" "rc:back")]
                               [(inline-button "Back to task" "rc:cancel")]]}))

      :rc/service
      (rc-edit! cfg chat-id message-id
                (str (rc-title typ) "\n\nStep 3/4: Primary service interest?")
                (rc-service-keyboard services))

      :rc/budget
      (rc-edit! cfg chat-id message-id
                (str (rc-title typ) "\n\nStep 4/4: Rough budget?")
                (rc-budget-keyboard))

      :rc/notes-await
      (rc-edit! cfg chat-id message-id
                (str (rc-title typ) "\n\nNotes (optional): send a message.")
                (rc-notes-keyboard))

      :rc/review
      (rc-edit! cfg chat-id message-id
                (rc-summary-text session)
                (rc-review-keyboard))

      (rc-edit! cfg chat-id message-id
                "Report card session not ready. Use Back to task."
                (rc-cancel-keyboard)))))

(defmethod
 handle-callback-dispatch
 :rc/start
 [{:keys [state chat-id message-id cfg db chat-user parsed]}]
 (let [task-id (rc-parse-uuid (:task-id parsed))
       task (rc-task-by-id db task-id)
       typ (:task/report-card-type task)
       client-id (get-in task [:task/client :client/id])]
   (cond
     (nil? task-id) (rc-edit! cfg chat-id message-id "Invalid task id." (rc-cancel-keyboard))
     (nil? task) (rc-edit! cfg chat-id message-id "Task not found." (rc-cancel-keyboard))
     (nil? typ) (rc-edit! cfg chat-id message-id "No report card configured for this task." (rc-cancel-keyboard))
     (nil? client-id) (rc-edit! cfg chat-id message-id "Task has no client linked." (rc-cancel-keyboard))
     :else
     (do
       (save-report-card-session!
        chat-id
        {:type typ
         :task-id task-id
         :client-id client-id
         :user chat-user
         :message-id message-id
         :stage :rc/contacted
         :fields {}
         :history []})
       (rc-render-stage! state chat-id message-id)))))

(defmethod
 handle-callback-dispatch
 :rc/cancel
 [{:keys [state chat-id message-id cfg db]}]
 (let [session (take-report-card-session! chat-id)
       task-id (:task-id session)
       task (rc-task-by-id db task-id)]
   (if task
     (send-task-card! state chat-id task {:reply-to-message-id message-id})
     (rc-edit! cfg chat-id message-id "Closed." {:inline_keyboard []}))))

(defmethod
 handle-callback-dispatch
 :rc/back
 [{:keys [state chat-id message-id cfg]}]
 (let [session (get-report-card-session! chat-id)]
   (if-not session
     (rc-edit! cfg chat-id message-id "Session expired. Tap Report card again." {:inline_keyboard []})
     (let [prev (rc-pop-history session)]
       (save-report-card-session! chat-id (or prev session))
       (rc-render-stage! state chat-id message-id)))))

(defmethod
 handle-callback-dispatch
 :rc/contacted
 [{:keys [state chat-id message-id cfg parsed]}]
 (let [session (get-report-card-session! chat-id)
       v (:value parsed)]
   (if-not session
     (rc-edit! cfg chat-id message-id "Session expired. Tap Report card again." {:inline_keyboard []})
     (let [contacted (case v
                       "yes" true
                       "no" false
                       :skip)]
       (save-report-card-session!
        chat-id
        (-> session
            rc-push-history
            (assoc :stage :rc/schedule)
            (assoc-in [:fields :contacted?] contacted)))
       (rc-render-stage! state chat-id message-id)))))

(defmethod
 handle-callback-dispatch
 :rc/schedule-pick-date
 [{:keys [state chat-id message-id cfg]}]
 (let [session (get-report-card-session! chat-id)]
   (if-not session
     (rc-edit! cfg chat-id message-id "Session expired. Tap Report card again." {:inline_keyboard []})
     (do
       (save-report-card-session!
        chat-id
        (-> session
            rc-push-history
            (assoc :stage :rc/pick-date
                   :picker/month (LocalDate/now (ZoneId/systemDefault)))))
       (rc-render-stage! state chat-id message-id)))))

(defmethod
 handle-callback-dispatch
 :rc/schedule-skip
 [{:keys [state chat-id message-id cfg]}]
 (let [session (get-report-card-session! chat-id)]
   (if-not session
     (rc-edit! cfg chat-id message-id "Session expired. Tap Report card again." {:inline_keyboard []})
     (do
       (save-report-card-session!
        chat-id
        (-> session
            rc-push-history
            (assoc :stage :rc/service)
            (update :fields dissoc :meeting/date :meeting/time)))
       (rc-render-stage! state chat-id message-id)))))

(defmethod
 handle-callback-dispatch
 :rc/service
 [{:keys [state chat-id message-id cfg parsed]}]
 (let [session (get-report-card-session! chat-id)
       v (:value parsed)
       service-id (cond
                    (= v "skip") :skip
                    (present-string? v) (keyword v)
                    :else :skip)]
   (if-not session
     (rc-edit! cfg chat-id message-id "Session expired. Tap Report card again." {:inline_keyboard []})
     (do
       (save-report-card-session!
        chat-id
        (-> session
            rc-push-history
            (assoc :stage :rc/budget)
            (assoc-in [:fields :service/id] service-id)))
       (rc-render-stage! state chat-id message-id)))))

(defmethod
 handle-callback-dispatch
 :rc/budget
 [{:keys [state chat-id message-id cfg parsed]}]
 (let [session (get-report-card-session! chat-id)
       v (:value parsed)
       budget (case v
                "low" :low
                "medium" :medium
                "high" :high
                :skip)]
   (if-not session
     (rc-edit! cfg chat-id message-id "Session expired. Tap Report card again." {:inline_keyboard []})
     (do
       (save-report-card-session!
        chat-id
        (-> session
            rc-push-history
            (assoc :stage :rc/notes-await)
            (assoc-in [:fields :budget] budget)))
       (rc-render-stage! state chat-id message-id)))))

(defmethod
 handle-callback-dispatch
 :rc/notes-skip
 [{:keys [state chat-id message-id cfg]}]
 (let [session (get-report-card-session! chat-id)]
   (if-not session
     (rc-edit! cfg chat-id message-id "Session expired. Tap Report card again." {:inline_keyboard []})
     (do
       (save-report-card-session!
        chat-id
        (-> session
            rc-push-history
            (assoc :stage :rc/review)
            (update :fields dissoc :notes)))
       (rc-render-stage! state chat-id message-id)))))

(defn- rc-create-followup-task!
  [state chat-user {:keys [type client-id task-id fields]}]
  (let [actor (actions/actor-from-telegram chat-user)
        base-desc (str "Generated from report card.\n\n" (str/join "\n" (map (fn [[k v]] (rc-format-field k v)) fields)))]
    (case type
      :report.card.type/onboarding
      (actions/execute!
       state
       {:action/id :cap/action/task-create
        :actor actor
        :input {:task/title "Deliver proposal (stub)"
                :task/description (str base-desc "\n\nDeliver a proposal and record the response using the Proposal response report card.")
                :task/status :todo
                :task/priority :medium
                :task/client client-id
                :task/assignee (:user/id chat-user)
                :task/report-card-type :report.card.type/proposal-response
                :task/automation-key (str "proposal-response:" task-id)}})

      :report.card.type/proposal-response
      (actions/execute!
       state
       {:action/id :cap/action/task-create
        :actor actor
        :input {:task/title "Draft agreement (stub)"
                :task/description (str base-desc "\n\nDraft an agreement based on the accepted proposal.")
                :task/status :todo
                :task/priority :medium
                :task/client client-id
                :task/assignee (:user/id chat-user)
                :task/automation-key (str "agreement-draft:" task-id)}})

      nil)))

(defmethod
 handle-callback-dispatch
 :rc/submit
 [{:keys [state chat-id message-id cfg chat-user]}]
 (let [session (get-report-card-session! chat-id)]
   (if-not (and session chat-user)
     (rc-edit! cfg chat-id message-id "Session expired. Tap Report card again." {:inline_keyboard []})
     (let [{:keys [type task-id client-id fields]} session
           fields (or fields {})
           fields-str (pr-str fields)
           submit (actions/execute!
                   state
                   {:action/id :cap/action/report-card-submit
                    :actor (actions/actor-from-telegram chat-user)
                    :input {:report.card/type type
                            :task/id task-id
                            :client/id client-id
                            :report.card/fields fields-str}})
           err (:error submit)]
       (if err
         (rc-edit! cfg chat-id message-id (str "Submit failed: " (:message err)) (rc-review-keyboard))
         (do
           (actions/execute!
            state
            {:action/id :cap/action/task-set-status
             :actor (actions/actor-from-telegram chat-user)
             :input {:task/id task-id
                     :task/status :done}})
           (let [next (rc-create-followup-task!
                       state
                       chat-user
                       {:type type
                        :client-id client-id
                        :task-id task-id
                        :fields fields})
                 next-task (get-in next [:result :task])]
             (take-report-card-session! chat-id)
             (if next-task
               (send-task-card! state chat-id next-task {:reply-to-message-id message-id})
               (rc-edit! cfg
                         chat-id
                         message-id
                         "Submitted."
                         {:inline_keyboard [[(inline-button "Tasks" "filter:refresh")]]})))))))))
