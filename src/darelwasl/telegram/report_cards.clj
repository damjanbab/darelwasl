(in-ns 'darelwasl.telegram)

(comment "Report card flows (operator UI) driven via inline buttons + minimal typing.")

(defn- rc-inline-button
  [text data]
  {:text text
   :callback_data data})

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
  (update session :history (fnil conj []) {:stage (:stage session) :fields (:fields session) :draft (:draft session)}))

(defn- rc-pop-history
  [session]
  (let [h (vec (or (:history session) []))]
    (when (seq h)
      (let [prev (peek h)]
        (-> session
            (assoc :stage (:stage prev)
                   :fields (:fields prev)
                   :draft (:draft prev))
            (assoc :history (pop h)))))))

(defn- rc-cancel-keyboard
  []
  {:inline_keyboard [[(rc-inline-button "Back to task" "rc:cancel")]]})

(defn- rc-lead-status-keyboard
  []
  {:inline_keyboard
   [[(rc-inline-button "Proceed" "rc:lead-status:proceed")
     (rc-inline-button "Dead lead" "rc:lead-status:dead")]
    [(rc-inline-button "Back to task" "rc:cancel")]]})

(defn- rc-contacted-keyboard
  []
  {:inline_keyboard
   [[(rc-inline-button "Yes" "rc:contacted:yes")
     (rc-inline-button "No" "rc:contacted:no")]
    [(rc-inline-button "Skip" "rc:contacted:skip")
     (rc-inline-button "Back to task" "rc:cancel")]]})

(defn- rc-schedule-keyboard
  []
  {:inline_keyboard
   [[(rc-inline-button "Pick date" "rc:schedule:pick-date")
     (rc-inline-button "Skip" "rc:schedule:skip")]
    [(rc-inline-button "Back" "rc:back")
     (rc-inline-button "Back to task" "rc:cancel")]]})

(defn- rc-service-keyboard
  [services]
  {:inline_keyboard
   (vec
    (concat
     (->> (or services [])
          (take 6)
          (map (fn [{:keys [id title]}]
                 [(rc-inline-button (truncate-text (or title (name id)) 40)
                                    (str "rc:service:" (subs (str id) 1)))]))
          vec)
     [[(rc-inline-button "Skip" "rc:service:skip")
       (rc-inline-button "Back" "rc:back")]
      [(rc-inline-button "Back to task" "rc:cancel")]]))})

(defn- rc-budget-keyboard
  []
  {:inline_keyboard
   [[(rc-inline-button "Low" "rc:budget:low")
     (rc-inline-button "Medium" "rc:budget:medium")
     (rc-inline-button "High" "rc:budget:high")]
    [(rc-inline-button "Skip" "rc:budget:skip")
     (rc-inline-button "Back" "rc:back")]
    [(rc-inline-button "Back to task" "rc:cancel")]]})

(defn- rc-objective-keyboard
  []
  {:inline_keyboard
   [[(rc-inline-button "Back" "rc:back")
     (rc-inline-button "Back to task" "rc:cancel")]]})

(defn- rc-pricing-model-keyboard
  []
  {:inline_keyboard
   [[(rc-inline-button "Fixed" "rc:pricing:fixed")
     (rc-inline-button "Range" "rc:pricing:range")
     (rc-inline-button "Custom" "rc:pricing:custom")]
    [(rc-inline-button "Back" "rc:back")
     (rc-inline-button "Back to task" "rc:cancel")]]})

(defn- rc-text-back-cancel-keyboard
  []
  {:inline_keyboard
   [[(rc-inline-button "Back" "rc:back")
     (rc-inline-button "Back to task" "rc:cancel")]]})

(defn- rc-deposit-kind-keyboard
  []
  {:inline_keyboard
   [[(rc-inline-button "Skip" "rc:deposit:skip")
     (rc-inline-button "Amount" "rc:deposit:amount")
     (rc-inline-button "Percent" "rc:deposit:percent")]
    [(rc-inline-button "Back" "rc:back")
     (rc-inline-button "Back to task" "rc:cancel")]]})

(defn- rc-milestones-keyboard
  [milestones]
  (let [ms (vec (or milestones []))
        remove-rows (->> (map-indexed (fn [idx _]
                                        [(rc-inline-button (str "Remove " (inc idx)) (str "rc:milestones:remove:" idx))])
                                      ms)
                         (take 6)
                         vec)
        done-row [(rc-inline-button "Add milestone" "rc:milestones:add")
                  (rc-inline-button "Done" "rc:milestones:done")]]
    {:inline_keyboard
     (vec
      (concat
       remove-rows
       [done-row
        [(rc-inline-button "Back" "rc:back")
         (rc-inline-button "Back to task" "rc:cancel")]]))}))

(defn- rc-milestone-type-keyboard
  []
  {:inline_keyboard
   [[(rc-inline-button "Amount" "rc:milestone-type:amount")
     (rc-inline-button "Percent" "rc:milestone-type:percent")]
    [(rc-inline-button "Back" "rc:back")
     (rc-inline-button "Back to task" "rc:cancel")]]})

(defn- rc-client-notes-keyboard
  []
  {:inline_keyboard
   [[(rc-inline-button "Skip" "rc:client-notes:skip")
     (rc-inline-button "Back" "rc:back")]
    [(rc-inline-button "Back to task" "rc:cancel")]]})

(defn- rc-notes-keyboard
  []
  {:inline_keyboard
   [[(rc-inline-button "Skip notes" "rc:notes:skip")
     (rc-inline-button "Back" "rc:back")]
    [(rc-inline-button "Back to task" "rc:cancel")]]})

(defn- rc-dead-lead-reason-keyboard
  []
  {:inline_keyboard
   [[(rc-inline-button "Skip reason" "rc:dead-lead-reason:skip")
     (rc-inline-button "Back" "rc:back")]
    [(rc-inline-button "Back to task" "rc:cancel")]]})

(defn- rc-review-keyboard
  []
  {:inline_keyboard
   [[(rc-inline-button "Submit" "rc:submit")
     (rc-inline-button "Back" "rc:back")]
    [(rc-inline-button "Back to task" "rc:cancel")]]})

(declare rc-title rc-summary-text)

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
      :rc/lead-status
      (rc-edit! cfg chat-id message-id
                (str (rc-title typ) "\n\nStep 0/4: Is this lead viable?")
                (rc-lead-status-keyboard))

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

      :rc/objective-await
      (rc-edit! cfg chat-id message-id
                (str (rc-title typ) "\n\nObjective (required): send 1 sentence describing what the client wants to achieve.")
                (rc-objective-keyboard))

      :rc/pricing-model
      (rc-edit! cfg chat-id message-id
                (str (rc-title typ) "\n\nPricing model (required):")
                (rc-pricing-model-keyboard))

      :rc/pricing-fixed-total-await
      (rc-edit! cfg chat-id message-id
                (str (rc-title typ) "\n\nFixed total (required): send a number (e.g. 50000).")
                (rc-text-back-cancel-keyboard))

      :rc/pricing-range-min-await
      (rc-edit! cfg chat-id message-id
                (str (rc-title typ) "\n\nRange minimum (required): send a number.")
                (rc-text-back-cancel-keyboard))

      :rc/pricing-range-max-await
      (let [mn (get-in session [:draft :pricing/range-min])]
        (rc-edit! cfg chat-id message-id
                  (str (rc-title typ) "\n\nRange maximum (required): send a number (min was " (or mn "—") ").")
                  (rc-text-back-cancel-keyboard)))

      :rc/pricing-custom-notes-await
      (rc-edit! cfg chat-id message-id
                (str (rc-title typ) "\n\nCustom pricing (required): send client-facing pricing notes.")
                (rc-text-back-cancel-keyboard))

      :rc/deposit-kind
      (rc-edit! cfg chat-id message-id
                (str (rc-title typ) "\n\nDeposit (optional):")
                (rc-deposit-kind-keyboard))

      :rc/deposit-value-await
      (let [dtype (get-in session [:draft :deposit/type])]
        (rc-edit! cfg chat-id message-id
                  (str (rc-title typ) "\n\nDeposit value (" (name (or dtype :value)) "): send a number.")
                  (rc-text-back-cancel-keyboard)))

      :rc/milestones
      (let [ms (vec (or (get-in session [:fields :payment/milestones]) []))
            lines (when (seq ms)
                    (->> ms
                         (map-indexed (fn [idx m]
                                        (str (inc idx) ") " (or (:label m) "—") " — " (:value m) " " (name (:type m)))))
                         vec))
            body (str (rc-title typ) "\n\nPayment milestones (required: at least 1)."
                      (when (seq lines) (str "\n\nCurrent:\n" (str/join "\n" lines))))]
        (rc-edit! cfg chat-id message-id body (rc-milestones-keyboard ms)))

      :rc/milestone-type
      (rc-edit! cfg chat-id message-id
                (str (rc-title typ) "\n\nMilestone type:")
                (rc-milestone-type-keyboard))

      :rc/milestone-label-await
      (rc-edit! cfg chat-id message-id
                (str (rc-title typ) "\n\nMilestone label (required): send a short label (e.g. \"On submission\").")
                (rc-text-back-cancel-keyboard))

      :rc/milestone-value-await
      (let [mtype (get-in session [:draft :milestone/type])
            label (get-in session [:draft :milestone/label])]
        (rc-edit! cfg chat-id message-id
                  (str (rc-title typ) "\n\nMilestone value (" (name (or mtype :value)) ") for \"" (or label "—") "\": send a number.")
                  (rc-text-back-cancel-keyboard)))

      :rc/client-notes-await
      (rc-edit! cfg chat-id message-id
                (str (rc-title typ) "\n\nClient-visible notes (optional): send a message (this can appear in the proposal).")
                (rc-client-notes-keyboard))

      :rc/internal-notes-await
      (rc-edit! cfg chat-id message-id
                (str (rc-title typ) "\n\nInternal notes (optional): send a message.")
                (rc-notes-keyboard))

      :rc/dead-lead-reason-await
      (rc-edit! cfg chat-id message-id
                (str (rc-title typ) "\n\nDead lead reason (optional): send a message.")
                (rc-dead-lead-reason-keyboard))

      :rc/review
      (rc-edit! cfg chat-id message-id
                (rc-summary-text session)
                (rc-review-keyboard))

      (rc-edit! cfg chat-id message-id
                "Report card session not ready. Use Back to task."
                (rc-cancel-keyboard)))))

(defn- rc-title
  [typ]
  (case typ
    :report.card.type/onboarding "Onboarding report card"
    :report.card.type/consultation "Consultation report card"
    :report.card.type/proposal-response "Proposal response report card"
    "Report card"))

(defn- rc-format-field
  [k v]
  (let [label (case k
                :onboarding/dead-lead? "Dead lead"
                :onboarding/dead-lead-reason "Dead lead reason"
                :contacted? "Contacted"
                :meeting/date "Scheduled date"
                :meeting/time "Scheduled time"
                :offer/objective "Objective"
                :service/id "Service"
                :pricing/model "Pricing"
                :payment/deposit "Deposit"
                :payment/milestones "Milestones"
                :notes/client-visible "Client notes"
                :notes/internal "Internal notes"
                :budget "Budget"
                (name k))
        value (cond
                (true? v) "Yes"
                (false? v) "No"
                (keyword? v) (name v)
                (string? v) (str/trim v)
                (and (= k :payment/milestones) (vector? v))
                (str (count v) " item(s)")
                (map? v) (pr-str v)
                :else (str v))]
    (str "- " label ": " (if (str/blank? (str value)) "—" value))))

(defn- rc-summary-text
  [{:keys [type fields]}]
  (let [fields (or fields {})
        ordered [:onboarding/dead-lead?
                 :onboarding/dead-lead-reason
                 :contacted?
                 :meeting/date
                 :meeting/time
                 :service/id
                 :budget
                 :offer/objective
                 :pricing/model
                 :payment/deposit
                 :payment/milestones
                 :notes/client-visible
                 :notes/internal]]
    (str (rc-title type) "\n\n"
         (str/join "\n" (map (fn [k] (rc-format-field k (get fields k))) ordered)))))

(defn- parse-number
  [s]
  (when (string? s)
    (let [raw (-> s str/trim (str/replace #"[^0-9.+-]" ""))]
      (when-not (str/blank? raw)
        (try
          (double (Double/parseDouble raw))
          (catch Exception _ nil))))))

(defn handle-report-card-message
  [state _chat-user chat-id text]
  (let [cfg (get-in state [:config :telegram])
        session (get-report-card-session! chat-id)]
    (if-not session
      {:text "No active report card. Open a task and tap Report card."}
      (let [{:keys [stage message-id]} session
            trimmed (some-> text str str/trim)]
        (cond
          (not (contains? #{:rc/objective-await
                            :rc/pricing-fixed-total-await
                            :rc/pricing-range-min-await
                            :rc/pricing-range-max-await
                            :rc/pricing-custom-notes-await
                            :rc/deposit-value-await
                            :rc/milestone-label-await
                            :rc/milestone-value-await
                            :rc/client-notes-await
                            :rc/internal-notes-await
                            :rc/dead-lead-reason-await} stage))
          {:text "Use the buttons to continue."}

          (str/blank? trimmed)
          {:text (if (= stage :rc/dead-lead-reason-await)
                   "Send a short reason (non-empty), or tap Skip reason."
                   (case stage
                     :rc/objective-await "Send a 1-sentence objective (non-empty)."
                     :rc/pricing-custom-notes-await "Send pricing notes (non-empty)."
                     :rc/milestone-label-await "Send a milestone label (non-empty)."
                     :rc/client-notes-await "Send client notes (non-empty), or tap Skip."
                     :rc/internal-notes-await "Send internal notes (non-empty), or tap Skip notes."
                     "Send a value (non-empty)."))}

          :else
          (let [fields (or (:fields session) {})
                draft (or (:draft session) {})]
            (case stage
              :rc/dead-lead-reason-await
              (do
                (save-report-card-session!
                 chat-id
                 (-> session
                     rc-push-history
                     (assoc :stage :rc/review)
                     (assoc-in [:fields :onboarding/dead-lead-reason] trimmed)))
                (when message-id (rc-render-stage! state chat-id message-id))
                {:status :handled})

              :rc/objective-await
              (do
                (save-report-card-session!
                 chat-id
                 (-> session
                     rc-push-history
                     (assoc :stage :rc/pricing-model)
                     (assoc-in [:fields :offer/objective] trimmed)))
                (when message-id (rc-render-stage! state chat-id message-id))
                {:status :handled})

              :rc/pricing-fixed-total-await
              (if-let [n (parse-number trimmed)]
                (do
                  (save-report-card-session!
                   chat-id
                   (-> session
                       rc-push-history
                       (assoc :stage :rc/deposit-kind)
                       (assoc-in [:fields :pricing/model] {:model :fixed :currency "SAR" :total n})))
                  (when message-id (rc-render-stage! state chat-id message-id))
                  {:status :handled})
                {:text "Send a valid number (e.g. 50000)."})

              :rc/pricing-range-min-await
              (if-let [n (parse-number trimmed)]
                (do
                  (save-report-card-session!
                   chat-id
                   (-> session
                       rc-push-history
                       (assoc :stage :rc/pricing-range-max-await)
                       (assoc :draft (assoc draft :pricing/range-min n))))
                  (when message-id (rc-render-stage! state chat-id message-id))
                  {:status :handled})
                {:text "Send a valid number for the minimum."})

              :rc/pricing-range-max-await
              (let [mn (:pricing/range-min draft)]
                (if-let [mx (parse-number trimmed)]
                  (if (and (number? mn) (<= mn mx))
                    (do
                      (save-report-card-session!
                       chat-id
                       (-> session
                           rc-push-history
                           (assoc :stage :rc/deposit-kind)
                           (assoc :draft (dissoc draft :pricing/range-min))
                           (assoc-in [:fields :pricing/model] {:model :range :currency "SAR" :min mn :max mx})))
                      (when message-id (rc-render-stage! state chat-id message-id))
                      {:status :handled})
                    {:text "Max must be ≥ min."})
                  {:text "Send a valid number for the maximum."}))

              :rc/pricing-custom-notes-await
              (do
                (save-report-card-session!
                 chat-id
                 (-> session
                     rc-push-history
                     (assoc :stage :rc/deposit-kind)
                     (assoc-in [:fields :pricing/model] {:model :custom :currency "SAR" :pricing-notes trimmed})))
                (when message-id (rc-render-stage! state chat-id message-id))
                {:status :handled})

              :rc/deposit-value-await
              (let [dtype (:deposit/type draft)]
                (if-let [n (parse-number trimmed)]
                  (cond
                    (and (= dtype :percent) (<= 0 n 100))
                    (do
                      (save-report-card-session!
                       chat-id
                       (-> session
                           rc-push-history
                           (assoc :stage :rc/milestones)
                           (assoc :draft (dissoc draft :deposit/type))
                           (assoc-in [:fields :payment/deposit] {:type :percent :value n})))
                      (when message-id (rc-render-stage! state chat-id message-id))
                      {:status :handled})

                    (and (= dtype :amount) (pos? n))
                    (do
                      (save-report-card-session!
                       chat-id
                       (-> session
                           rc-push-history
                           (assoc :stage :rc/milestones)
                           (assoc :draft (dissoc draft :deposit/type))
                           (assoc-in [:fields :payment/deposit] {:type :amount :value n})))
                      (when message-id (rc-render-stage! state chat-id message-id))
                      {:status :handled})

                    :else
                    {:text "Invalid deposit value."})
                  {:text "Send a valid number."}))

              :rc/milestone-label-await
              (do
                (save-report-card-session!
                 chat-id
                 (-> session
                     rc-push-history
                     (assoc :stage :rc/milestone-value-await)
                     (assoc :draft (assoc draft :milestone/label trimmed))))
                (when message-id (rc-render-stage! state chat-id message-id))
                {:status :handled})

              :rc/milestone-value-await
              (let [mtype (:milestone/type draft)
                    label (:milestone/label draft)]
                (if-let [n (parse-number trimmed)]
                  (cond
                    (and (= mtype :percent) (<= 0 n 100))
                    (let [milestones (vec (or (get-in fields [:payment/milestones]) []))
                          item {:label label :type :percent :value n}]
                      (save-report-card-session!
                       chat-id
                       (-> session
                           (assoc :stage :rc/milestones)
                           (assoc :draft (dissoc draft :milestone/type :milestone/label))
                           (assoc-in [:fields :payment/milestones] (conj milestones item))))
                      (when message-id (rc-render-stage! state chat-id message-id))
                      {:status :handled})

                    (and (= mtype :amount) (pos? n))
                    (let [milestones (vec (or (get-in fields [:payment/milestones]) []))
                          item {:label label :type :amount :value n}]
                      (save-report-card-session!
                       chat-id
                       (-> session
                           (assoc :stage :rc/milestones)
                           (assoc :draft (dissoc draft :milestone/type :milestone/label))
                           (assoc-in [:fields :payment/milestones] (conj milestones item))))
                      (when message-id (rc-render-stage! state chat-id message-id))
                      {:status :handled})

                    :else
                    {:text "Invalid milestone value."})
                  {:text "Send a valid number."}))

              :rc/client-notes-await
              (do
                (save-report-card-session!
                 chat-id
                 (-> session
                     rc-push-history
                     (assoc :stage :rc/internal-notes-await)
                     (assoc-in [:fields :notes/client-visible] trimmed)))
                (when message-id (rc-render-stage! state chat-id message-id))
                {:status :handled})

              :rc/internal-notes-await
              (do
                (save-report-card-session!
                 chat-id
                 (-> session
                     rc-push-history
                     (assoc :stage :rc/review)
                     (assoc-in [:fields :notes/internal] trimmed)))
                (when message-id (rc-render-stage! state chat-id message-id))
                {:status :handled})

              {:text "Unhandled input."})))))))
