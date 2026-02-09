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
         :stage :rc/lead-status
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
 :rc/lead-status
 [{:keys [state chat-id message-id cfg parsed]}]
 (let [session (get-report-card-session! chat-id)
       v (:value parsed)]
   (if-not session
     (rc-edit! cfg chat-id message-id "Session expired. Tap Report card again." {:inline_keyboard []})
     (case v
       "proceed"
       (do
         (save-report-card-session!
          chat-id
          (-> session
              rc-push-history
              (assoc :stage :rc/contacted)
              (assoc-in [:fields :onboarding/dead-lead?] false)))
         (rc-render-stage! state chat-id message-id))

       "dead"
       (do
         (save-report-card-session!
          chat-id
          (-> session
              rc-push-history
              (assoc :stage :rc/dead-lead-reason-await)
              (assoc :fields {:onboarding/dead-lead? true})))
         (rc-render-stage! state chat-id message-id))

       (rc-edit! cfg chat-id message-id "Invalid selection." (rc-lead-status-keyboard))))))

(defmethod
 handle-callback-dispatch
 :rc/dead-lead-reason-skip
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
            (update :fields dissoc :onboarding/dead-lead-reason)))
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
            (assoc :stage :rc/objective-await)
            (assoc-in [:fields :budget] budget)))
       (rc-render-stage! state chat-id message-id)))))

(defmethod
 handle-callback-dispatch
 :rc/pricing-model
 [{:keys [state chat-id message-id cfg parsed]}]
 (let [session (get-report-card-session! chat-id)
       v (:value parsed)]
   (if-not session
     (rc-edit! cfg chat-id message-id "Session expired. Tap Report card again." {:inline_keyboard []})
     (let [next-stage (case v
                        "fixed" :rc/pricing-fixed-total-await
                        "range" :rc/pricing-range-min-await
                        "custom" :rc/pricing-custom-notes-await
                        nil)]
       (if-not next-stage
         (rc-edit! cfg chat-id message-id "Invalid selection." (rc-pricing-model-keyboard))
         (do
           (save-report-card-session!
            chat-id
            (-> session
                rc-push-history
                (assoc :stage next-stage)))
           (rc-render-stage! state chat-id message-id)))))))

(defmethod
 handle-callback-dispatch
 :rc/deposit
 [{:keys [state chat-id message-id cfg parsed]}]
 (let [session (get-report-card-session! chat-id)
       v (:value parsed)]
   (if-not session
     (rc-edit! cfg chat-id message-id "Session expired. Tap Report card again." {:inline_keyboard []})
     (case v
       "skip"
       (do
         (save-report-card-session!
          chat-id
          (-> session
              rc-push-history
              (assoc :stage :rc/milestones)
              (update :fields dissoc :payment/deposit)))
         (rc-render-stage! state chat-id message-id))

       "amount"
       (do
         (save-report-card-session!
          chat-id
          (-> session
              rc-push-history
              (assoc :stage :rc/deposit-value-await)
              (assoc :draft (assoc (or (:draft session) {}) :deposit/type :amount))))
         (rc-render-stage! state chat-id message-id))

       "percent"
       (do
         (save-report-card-session!
          chat-id
          (-> session
              rc-push-history
              (assoc :stage :rc/deposit-value-await)
              (assoc :draft (assoc (or (:draft session) {}) :deposit/type :percent))))
         (rc-render-stage! state chat-id message-id))

       (rc-edit! cfg chat-id message-id "Invalid selection." (rc-deposit-kind-keyboard))))))

(defmethod
 handle-callback-dispatch
 :rc/milestones
 [{:keys [state chat-id message-id cfg parsed]}]
 (let [session (get-report-card-session! chat-id)
       v (:value parsed)
       raw-idx (:index parsed)]
   (if-not session
     (rc-edit! cfg chat-id message-id "Session expired. Tap Report card again." {:inline_keyboard []})
     (case v
       "add"
       (do
         (save-report-card-session!
          chat-id
          (-> session
              rc-push-history
              (assoc :stage :rc/milestone-type)))
         (rc-render-stage! state chat-id message-id))

       "done"
       (let [ms (vec (or (get-in session [:fields :payment/milestones]) []))]
         (if (empty? ms)
           (rc-edit! cfg chat-id message-id "Add at least 1 milestone." (rc-milestones-keyboard ms))
           (do
             (save-report-card-session!
              chat-id
              (-> session
                  rc-push-history
                  (assoc :stage :rc/client-notes-await)))
             (rc-render-stage! state chat-id message-id))))

       "remove"
       (let [idx raw-idx
             ms (vec (or (get-in session [:fields :payment/milestones]) []))]
         (if (or (nil? idx) (neg? idx) (>= idx (count ms)))
           (rc-edit! cfg chat-id message-id "Invalid milestone." (rc-milestones-keyboard ms))
           (do
             (save-report-card-session!
              chat-id
              (-> session
                  (assoc :stage :rc/milestones)
                  (assoc-in [:fields :payment/milestones] (vec (concat (subvec ms 0 idx) (subvec ms (inc idx)))))))
             (rc-render-stage! state chat-id message-id))))

       (rc-edit! cfg chat-id message-id "Invalid selection." (rc-cancel-keyboard))))))

(defmethod
 handle-callback-dispatch
 :rc/milestone-type
 [{:keys [state chat-id message-id cfg parsed]}]
 (let [session (get-report-card-session! chat-id)
       v (:value parsed)
       typ (case v
             "amount" :amount
             "percent" :percent
             nil)]
   (if-not session
     (rc-edit! cfg chat-id message-id "Session expired. Tap Report card again." {:inline_keyboard []})
     (if-not typ
       (rc-edit! cfg chat-id message-id "Invalid selection." (rc-milestone-type-keyboard))
       (do
         (save-report-card-session!
          chat-id
          (-> session
              rc-push-history
              (assoc :stage :rc/milestone-label-await)
              (assoc :draft (assoc (or (:draft session) {}) :milestone/type typ))))
         (rc-render-stage! state chat-id message-id))))))

(defmethod
 handle-callback-dispatch
 :rc/client-notes-skip
 [{:keys [state chat-id message-id cfg]}]
 (let [session (get-report-card-session! chat-id)]
   (if-not session
     (rc-edit! cfg chat-id message-id "Session expired. Tap Report card again." {:inline_keyboard []})
     (do
       (save-report-card-session!
        chat-id
        (-> session
            rc-push-history
            (assoc :stage :rc/internal-notes-await)
            (update :fields dissoc :notes/client-visible)))
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
            (update :fields dissoc :notes/internal)))
       (rc-render-stage! state chat-id message-id)))))

(defmethod
 handle-callback-dispatch
 :rc/submit
 [{:keys [state chat-id message-id cfg chat-user]}]
 (let [session (get-report-card-session! chat-id)]
   (if-not (and session chat-user)
     (rc-edit! cfg chat-id message-id "Session expired. Tap Report card again." {:inline_keyboard []})
     (let [{:keys [type task-id client-id fields]} session
           fields (or fields {})
           dead? (true? (:onboarding/dead-lead? fields))
           objective (some-> (:offer/objective fields) str str/trim not-empty)
           pricing (:pricing/model fields)
           milestones (vec (or (:payment/milestones fields) []))
           missing (when (and (= type :report.card.type/onboarding) (not dead?))
                     (->> [(when-not (and (keyword? (:service/id fields)) (not= :skip (:service/id fields))) "Service")
                           (when-not objective "Objective")
                           (when-not (map? pricing) "Pricing model")
                           (when (and (map? pricing) (nil? (:model pricing))) "Pricing model")
                           (when (empty? milestones) "Milestones (≥ 1 required)")]
                          (remove nil?)
                          vec))]
       (if (seq missing)
         (rc-edit! cfg chat-id message-id
                   (str "Missing required fields:\n- " (str/join "\n- " missing) "\n\nUse Back to complete.")
                   (rc-review-keyboard))
         (let [fields-str (pr-str fields)
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
               (take-report-card-session! chat-id)
               (rc-edit! cfg
                         chat-id
                         message-id
                         "Submitted. Next steps will be created automatically."
                         {:inline_keyboard [[(inline-button "Tasks" "filter:refresh")]]})))))))))
