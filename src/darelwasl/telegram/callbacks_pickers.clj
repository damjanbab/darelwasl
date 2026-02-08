(in-ns 'darelwasl.telegram)

(comment "Callback handlers: date/time pickers.")

(defmethod
 handle-callback-dispatch
 :date-picker/day
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
   [ymd
    (:value parsed)
    picked
    (parse-ymd->date ymd)
    pending
    (get-pending-reason! chat-id)
    session
    (get-docs-session! chat-id)]
   (cond
    (and
     picked
     pending
     (= (:stage pending) :followup-date-picker)
     (present-string? (:reason pending)))
    (let
     [task-id
      (:task-id pending)
      res
      (actions/execute!
       state
       {:action/id :cap/action/task-set-status,
        :actor (actions/actor-from-telegram chat-user),
        :input
        {:task/id task-id,
         :task/status :pending,
         :note/body (:reason pending),
         :note/last-contact (now-inst),
         :note/next-followup picked}})]
     (take-pending-reason! chat-id)
     (if-let
      [err (:error res)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to set pending: " (:message err)),
        :message-key
        (str "pending-followup-error-" (System/currentTimeMillis))})
      (do
       (edit-message!
        cfg
        {:chat-id chat-id,
         :message-id message-id,
         :text (str "Follow-up date set: " ymd),
         :reply-markup {:inline_keyboard []}})
       (send-task-card!
        state
        chat-id
        (get-in res [:result :task])
        {}))))
    (and picked session (= (:stage session) :docs/payment-date))
    (do
     (save-docs-session!
      chat-id
      (->
       session
       (assoc :stage :docs/payment-time)
       (update :draft dissoc :payment/paid-at :payment/paid-date)
       (assoc-in [:draft :payment/paid-date] ymd)))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Pick payment time (optional):",
       :reply-markup
       (time-picker-inline-keyboard
        {:allow-skip? true,
         :skip-label "Skip time",
         :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))
    (and picked session (= (:stage session) :docs/inv-due-date))
    (do
     (save-docs-session!
      chat-id
      (->
       session
       (assoc :stage :docs/inv-due-time)
       (update :draft dissoc :invoice/due-at :invoice/due-date)
       (assoc-in [:draft :invoice/due-date] ymd)))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Pick invoice due time (optional):",
       :reply-markup
       (time-picker-inline-keyboard
        {:allow-skip? true,
         :skip-label "Skip time",
         :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))
    (and
     picked
     session
     (= (:stage session) :docs/agreement-effective-date))
    (let
     [draft
      (:draft session)
      actor
      (actions/actor-from-telegram chat-user)
      input
      (cond->
       {:client/id (:client-id session),
        :agreement/title (:agreement/title draft),
        :agreement/terms (:agreement/terms draft)}
       (present-string? (:agreement/client-company draft))
       (assoc
        :agreement/client-company
        (:agreement/client-company draft))
       (present-string? (:agreement/client-representative draft))
       (assoc
        :agreement/client-representative
        (:agreement/client-representative draft))
       (present-string? (:agreement/our-representative draft))
       (assoc
        :agreement/our-representative
        (:agreement/our-representative draft))
       (present-string? (:agreement/our-recipient draft))
       (assoc
        :agreement/our-recipient
        (:agreement/our-recipient draft))
       picked
       (assoc :agreement/effective-at picked))
      res
      (actions/execute!
       state
       {:action/id :cap/action/agreement-create,
        :actor actor,
        :input input})
      agreement
      (get-in res [:result :agreement])
      agreement-id
      (:agreement/id agreement)]
     (if-let
      [err (:error res)]
      (do
       (save-docs-session!
        chat-id
        (assoc session :stage :docs/agreements-menu :draft nil))
       (send-message!
        cfg
        {:chat-id chat-id,
         :text (str "Unable to create agreement: " (:message err)),
         :message-key
         (str
          "docs-agreement-create-error-"
          (System/currentTimeMillis)),
         :reply-markup (docs-agreements-menu-inline-keyboard)}))
      (do
       (save-docs-session!
        chat-id
        (->
         session
         (assoc
          :stage
          :docs/agreement-actions
          :agreement/id
          agreement-id
          :draft
          nil)))
       (edit-message!
        cfg
        {:chat-id chat-id,
         :message-id message-id,
         :text (str "Effective date set: " ymd),
         :reply-markup {:inline_keyboard []}})
       (send-message!
        cfg
        {:chat-id chat-id,
         :text "Agreement created.",
         :message-key
         (str "docs-agreement-created-" (System/currentTimeMillis)),
         :reply-markup
         (docs-agreement-actions-inline-keyboard agreement-id)}))))
    (and
     picked
     session
     (= (:stage session) :docs/agreement-accept-date))
    (let
     [aid (get-in session [:draft :agreement/id])]
     (save-docs-session!
      chat-id
      (->
       session
       (assoc :stage :docs/agreement-accept-time)
       (update
        :draft
        dissoc
        :agreement/accepted-at
        :agreement/accepted-date)
       (assoc-in [:draft :agreement/accepted-date] ymd)))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Pick acceptance time (optional):",
       :reply-markup
       (time-picker-inline-keyboard
        {:allow-skip? true,
         :skip-label "Skip time",
         :extra-rows
         [[(inline-button
            "Back"
            (str "docs:agreements:set:" aid))]]})}))
    (and picked session (= (:stage session) :docs/plan-item-due-date))
    (do
     (save-docs-session!
      chat-id
      (->
       session
       (assoc :stage :docs/plan-item-due-time)
       (update :draft dissoc :plan.item/due-at :plan.item/due-date)
       (assoc-in [:draft :plan.item/due-date] ymd)))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Pick plan item due time (optional):",
       :reply-markup
       (time-picker-inline-keyboard
        {:allow-skip? true,
         :skip-label "Skip time",
         :extra-rows
         [[(inline-button "Cancel" "docs:agreements:menu")]]})}))
    (and
     picked
     session
     (= (:stage session) :docs/plan-item-edit-due-date))
    (let
     [aid
      (get-in session [:draft :agreement/id])
      pid
      (get-in session [:draft :plan.item/id])]
     (save-docs-session!
      chat-id
      (->
       session
       (assoc :stage :docs/plan-item-edit-due-time)
       (update :draft dissoc :plan.item/due-at :plan.item/due-date)
       (assoc-in [:draft :plan.item/due-date] ymd)))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Pick new due time (optional):",
       :reply-markup
       (time-picker-inline-keyboard
        {:allow-skip? true,
         :skip-label "Skip time",
         :extra-rows
         [[(inline-button
            "Back"
            (str "docs:plan-item:open:" aid ":" pid))]]})}))
    :else
    nil))))

(defmethod
 handle-callback-dispatch
 :date-picker/nav
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
   [ym
    (:value parsed)
    month
    (or (parse-ym ym) (LocalDate/now (ZoneId/systemDefault)))
    pending
    (get-pending-reason! chat-id)
    session
    (get-docs-session! chat-id)]
   (cond
    (and pending (= (:stage pending) :followup-date-picker))
    (let
     [tid
      (str (:task-id pending))
      text
      (get-in pending [:picker :text] "Pick the follow-up date:")
      quicks
      (or
       (get-in pending [:picker :quicks])
       [{:id :tomorrow, :label "Tomorrow"}
        {:id :in-3-days, :label "In 3 days"}
        {:id :next-week, :label "Next week"}])]
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text text,
       :reply-markup
       (date-picker-inline-keyboard
        {:month month,
         :quicks quicks,
         :extra-rows
         [[(inline-button "Cancel" (str "pending:cancel:" tid))]]})}))
    (and session (= (:stage session) :docs/payment-date))
    (let
     [text
      (get-in session [:picker :text] "Pick payment date:")
      quicks
      (or
       (get-in session [:picker :quicks])
       [{:id :today, :label "Today"}
        {:id :yesterday, :label "Yesterday"}])]
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text text,
       :reply-markup
       (date-picker-inline-keyboard
        {:month month,
         :quicks quicks,
         :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))
    (and session (= (:stage session) :docs/inv-due-date))
    (let
     [text
      (get-in session [:picker :text] "Pick invoice due date:")
      quicks
      (or
       (get-in session [:picker :quicks])
       [{:id :in-7-days, :label "+7 days"}
        {:id :in-14-days, :label "+14 days"}
        {:id :in-30-days, :label "+30 days"}])]
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text text,
       :reply-markup
       (date-picker-inline-keyboard
        {:month month,
         :quicks quicks,
         :allow-skip? true,
         :skip-label "No due date",
         :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))
    (and session (= (:stage session) :docs/agreement-effective-date))
    (let
     [text
      (get-in
       session
       [:picker :text]
       "Pick agreement effective date (optional):")
      quicks
      (or
       (get-in session [:picker :quicks])
       [{:id :today, :label "Today"}
        {:id :tomorrow, :label "Tomorrow"}])]
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text text,
       :reply-markup
       (date-picker-inline-keyboard
        {:month month,
         :quicks quicks,
         :allow-skip? true,
         :skip-label "Skip (no effective date)",
         :extra-rows
         [[(inline-button "Cancel" "docs:agreements:menu")]]})}))
    (and session (= (:stage session) :docs/agreement-accept-date))
    (let
     [text
      (get-in session [:picker :text] "Pick acceptance date:")
      quicks
      (or
       (get-in session [:picker :quicks])
       [{:id :today, :label "Today"}
        {:id :yesterday, :label "Yesterday"}])
      aid
      (get-in session [:draft :agreement/id])]
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text text,
       :reply-markup
       (date-picker-inline-keyboard
        {:month month,
         :quicks quicks,
         :extra-rows
         [[(inline-button
            "Back"
            (str "docs:agreements:set:" aid))]]})}))
    (and session (= (:stage session) :docs/plan-item-due-date))
    (let
     [text
      (get-in session [:picker :text] "Pick plan item due date:")
      quicks
      (or
       (get-in session [:picker :quicks])
       [{:id :in-7-days, :label "+7 days"}
        {:id :in-14-days, :label "+14 days"}
        {:id :in-30-days, :label "+30 days"}])]
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text text,
       :reply-markup
       (date-picker-inline-keyboard
        {:month month,
         :quicks quicks,
         :extra-rows
         [[(inline-button "Cancel" "docs:agreements:menu")]]})}))
    (and session (= (:stage session) :docs/plan-item-edit-due-date))
    (let
     [text
      (get-in session [:picker :text] "Pick new due date:")
      quicks
      (or
       (get-in session [:picker :quicks])
       [{:id :in-7-days, :label "+7 days"}
        {:id :in-14-days, :label "+14 days"}
        {:id :in-30-days, :label "+30 days"}])
      aid
      (get-in session [:draft :agreement/id])
      pid
      (get-in session [:draft :plan.item/id])]
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text text,
       :reply-markup
       (date-picker-inline-keyboard
        {:month month,
         :quicks quicks,
         :extra-rows
         [[(inline-button
            "Back"
            (str "docs:plan-item:open:" aid ":" pid))]]})}))
    :else
    nil))))

(defmethod
 handle-callback-dispatch
 :date-picker/quick
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
   [quick-id
    (some-> (:value parsed) keyword)
    today
    (LocalDate/now (ZoneId/systemDefault))
    pending
    (get-pending-reason! chat-id)
    session
    (get-docs-session! chat-id)
    picked-day
    (cond
     (and pending (= (:stage pending) :followup-date-picker))
     (case
      quick-id
      :tomorrow
      (.plusDays today 1)
      :in-3-days
      (.plusDays today 3)
      :next-week
      (.plusDays today 7)
      nil)
     (and session (= (:stage session) :docs/payment-date))
     (case quick-id :today today :yesterday (.minusDays today 1) nil)
     (and session (= (:stage session) :docs/inv-due-date))
     (case
      quick-id
      :in-7-days
      (.plusDays today 7)
      :in-14-days
      (.plusDays today 14)
      :in-30-days
      (.plusDays today 30)
      nil)
     (and session (= (:stage session) :docs/agreement-effective-date))
     (case quick-id :today today :tomorrow (.plusDays today 1) nil)
     (and session (= (:stage session) :docs/agreement-accept-date))
     (case quick-id :today today :yesterday (.minusDays today 1) nil)
     (and session (= (:stage session) :docs/plan-item-due-date))
     (case
      quick-id
      :in-7-days
      (.plusDays today 7)
      :in-14-days
      (.plusDays today 14)
      :in-30-days
      (.plusDays today 30)
      nil)
     (and session (= (:stage session) :docs/plan-item-edit-due-date))
     (case
      quick-id
      :in-7-days
      (.plusDays today 7)
      :in-14-days
      (.plusDays today 14)
      :in-30-days
      (.plusDays today 30)
      nil)
     :else
     nil)
    ymd
    (when picked-day (yyyy-mm-dd picked-day))]
   (when
    ymd
    (handle-callback
     state
     chat-id
     {:message-id message-id,
      :callback-id nil,
      :data (str "dp:day:" ymd)})))))

(defmethod
 handle-callback-dispatch
 :date-picker/skip
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
   [pending (get-pending-reason! chat-id)]
   (when
    (and
     pending
     (= (:stage pending) :followup-date-picker)
     (present-string? (:reason pending)))
    (let
     [task-id
      (:task-id pending)
      res
      (actions/execute!
       state
       {:action/id :cap/action/task-set-status,
        :actor (actions/actor-from-telegram chat-user),
        :input
        {:task/id task-id,
         :task/status :pending,
         :note/body (:reason pending),
         :note/last-contact (now-inst)}})]
     (take-pending-reason! chat-id)
     (if-let
      [err (:error res)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to set pending: " (:message err)),
        :message-key
        (str "pending-followup-error-" (System/currentTimeMillis))})
      (do
       (edit-message!
        cfg
        {:chat-id chat-id,
         :message-id message-id,
         :text "Follow-up set without a date.",
         :reply-markup {:inline_keyboard []}})
       (send-task-card!
        state
        chat-id
        (get-in res [:result :task])
        {})))))
   (let
    [session (get-docs-session! chat-id)]
    (when
     (and session (= (:stage session) :docs/agreement-effective-date))
     (let
      [draft
       (:draft session)
       actor
       (actions/actor-from-telegram chat-user)
       input
       (cond->
        {:client/id (:client-id session),
         :agreement/title (:agreement/title draft),
         :agreement/terms (:agreement/terms draft)}
        (present-string? (:agreement/client-company draft))
        (assoc
         :agreement/client-company
         (:agreement/client-company draft))
        (present-string? (:agreement/client-representative draft))
        (assoc
         :agreement/client-representative
         (:agreement/client-representative draft))
        (present-string? (:agreement/our-representative draft))
        (assoc
         :agreement/our-representative
         (:agreement/our-representative draft))
        (present-string? (:agreement/our-recipient draft))
        (assoc
         :agreement/our-recipient
         (:agreement/our-recipient draft)))
       res
       (actions/execute!
        state
        {:action/id :cap/action/agreement-create,
         :actor actor,
         :input input})
       agreement
       (get-in res [:result :agreement])
       agreement-id
       (:agreement/id agreement)]
      (if-let
       [err (:error res)]
       (do
        (save-docs-session!
         chat-id
         (assoc session :stage :docs/agreements-menu :draft nil))
        (send-message!
         cfg
         {:chat-id chat-id,
          :text (str "Unable to create agreement: " (:message err)),
          :message-key
          (str
           "docs-agreement-create-error-"
           (System/currentTimeMillis)),
          :reply-markup (docs-agreements-menu-inline-keyboard)}))
       (do
        (save-docs-session!
         chat-id
         (->
          session
          (assoc
           :stage
           :docs/agreement-actions
           :agreement/id
           agreement-id
           :draft
           nil)))
        (edit-message!
         cfg
         {:chat-id chat-id,
          :message-id message-id,
          :text "No effective date set.",
          :reply-markup {:inline_keyboard []}})
        (send-message!
         cfg
         {:chat-id chat-id,
          :text "Agreement created.",
          :message-key
          (str "docs-agreement-created-" (System/currentTimeMillis)),
          :reply-markup
          (docs-agreement-actions-inline-keyboard agreement-id)}))))))
   (let
    [session (get-docs-session! chat-id)]
    (when
     (and session (= (:stage session) :docs/inv-due-date))
     (let
      [draft
       (:draft session)
       actor
       (actions/actor-from-telegram chat-user)
       input
       (cond->
        {:client/id (:client-id session),
         :invoice/number (:invoice/number draft),
         :invoice/total-amount (:invoice/total-amount draft),
         :invoice/status (:invoice/status draft)}
        (nil? (:invoice/status draft))
        (dissoc :invoice/status))
       res
       (actions/execute!
        state
        {:action/id :cap/action/invoice-create,
         :actor actor,
         :input input})]
      (save-docs-session!
       chat-id
       (assoc session :stage :docs/menu :draft nil))
      (if-let
       [err (:error res)]
       (send-message!
        cfg
        {:chat-id chat-id,
         :text (str "Unable to add invoice: " (:message err)),
         :message-key
         (str "docs-inv-create-error-" (System/currentTimeMillis)),
         :reply-markup (docs-menu-inline-keyboard)})
       (do
        (edit-message!
         cfg
         {:chat-id chat-id,
          :message-id message-id,
          :text "No due date set.",
          :reply-markup {:inline_keyboard []}})
        (send-message!
         cfg
         {:chat-id chat-id,
          :text "Invoice added.",
          :message-key
          (str "docs-inv-added-" (System/currentTimeMillis)),
          :reply-markup (docs-menu-inline-keyboard)})))))))))

(defmethod
 handle-callback-dispatch
 :time-picker/back
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
   [session (get-docs-session! chat-id)]
   (when
    (and
     session
     (#{:docs/payment-time
        :docs/inv-due-time
        :docs/plan-item-edit-due-time
        :docs/plan-item-due-time
        :docs/agreement-accept-time}
      (:stage session)))
    (let
     [cancel
      (case
       (:stage session)
       :docs/plan-item-due-time
       "docs:agreements:menu"
       :docs/plan-item-edit-due-time
       (let
        [aid
         (get-in session [:draft :agreement/id])
         pid
         (get-in session [:draft :plan.item/id])]
        (str "docs:plan-item:open:" aid ":" pid))
       :docs/agreement-accept-time
       (let
        [aid (get-in session [:draft :agreement/id])]
        (str "docs:agreements:set:" aid))
       "docs:menu")
      label
      (case
       (:stage session)
       :docs/payment-time
       "Pick payment time (optional):"
       :docs/inv-due-time
       "Pick invoice due time (optional):"
       :docs/plan-item-due-time
       "Pick plan item due time (optional):"
       :docs/plan-item-edit-due-time
       "Pick new due time (optional):"
       :docs/agreement-accept-time
       "Pick acceptance time (optional):"
       "Pick time (optional):")]
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text label,
       :reply-markup
       (time-picker-inline-keyboard
        {:allow-skip? true,
         :skip-label "Skip time",
         :extra-rows [[(inline-button "Cancel" cancel)]]})}))))))

(defmethod
 handle-callback-dispatch
 :time-picker/hour
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
   [session
    (get-docs-session! chat-id)
    raw
    (:value parsed)
    hour
    (when
     (re-matches #"\d{2}" (str raw))
     (try (Integer/parseInt (str raw)) (catch Exception _ nil)))]
   (when
    (and
     session
     (number? hour)
     (<= 0 hour 23)
     (#{:docs/payment-time
        :docs/inv-due-time
        :docs/plan-item-edit-due-time
        :docs/plan-item-due-time
        :docs/agreement-accept-time}
      (:stage session)))
    (let
     [cancel
      (case
       (:stage session)
       :docs/plan-item-due-time
       "docs:agreements:menu"
       :docs/plan-item-edit-due-time
       (let
        [aid
         (get-in session [:draft :agreement/id])
         pid
         (get-in session [:draft :plan.item/id])]
        (str "docs:plan-item:open:" aid ":" pid))
       :docs/agreement-accept-time
       (let
        [aid (get-in session [:draft :agreement/id])]
        (str "docs:agreements:set:" aid))
       "docs:menu")]
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text (str "Pick minutes for " (format "%02d" (int hour)) ":"),
       :reply-markup
       (time-picker-minutes-inline-keyboard
        {:hour hour,
         :allow-skip? true,
         :skip-label "Skip time",
         :extra-rows [[(inline-button "Cancel" cancel)]]})}))))))

(defmethod
 handle-callback-dispatch
 :time-picker/now
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
   [now-time
    (.toLocalTime (java.time.ZonedDateTime/now (ZoneId/systemDefault)))
    raw
    (.format
     now-time
     (java.time.format.DateTimeFormatter/ofPattern "HHmm"))]
   (apply-docs-time-picker-set!
    state
    {:chat-id chat-id,
     :chat-user chat-user,
     :message-id message-id,
     :raw raw}))))

(defmethod
 handle-callback-dispatch
 :time-picker/set
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
  (apply-docs-time-picker-set!
   state
   {:chat-id chat-id,
    :chat-user chat-user,
    :message-id message-id,
    :raw (:value parsed)})))

(defmethod
 handle-callback-dispatch
 :time-picker/skip
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
  (apply-docs-time-picker-set!
   state
   {:chat-id chat-id,
    :chat-user chat-user,
    :message-id message-id,
    :raw "0000"})))

