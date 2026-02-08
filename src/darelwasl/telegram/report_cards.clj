(in-ns 'darelwasl.telegram)

(comment "Report card flows (operator UI) driven via inline buttons + minimal typing.")

(defn- rc-inline-button
  [text data]
  {:text text
   :callback_data data})

(defn- rc-cancel-keyboard
  []
  {:inline_keyboard [[(rc-inline-button "Back to task" "rc:cancel")]]})

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

(defn- rc-notes-keyboard
  []
  {:inline_keyboard
   [[(rc-inline-button "Skip notes" "rc:notes:skip")
     (rc-inline-button "Back" "rc:back")]
    [(rc-inline-button "Back to task" "rc:cancel")]]})

(defn- rc-review-keyboard
  []
  {:inline_keyboard
   [[(rc-inline-button "Submit" "rc:submit")
     (rc-inline-button "Back" "rc:back")]
    [(rc-inline-button "Back to task" "rc:cancel")]]})

(defn- rc-title
  [typ]
  (case typ
    :report.card.type/onboarding "Onboarding report card"
    :report.card.type/proposal-response "Proposal response report card"
    "Report card"))

(defn- rc-format-field
  [k v]
  (let [label (case k
                :contacted? "Contacted"
                :meeting/date "Scheduled date"
                :meeting/time "Scheduled time"
                :service/id "Service"
                :budget "Budget"
                :notes "Notes"
                (name k))
        value (cond
                (true? v) "Yes"
                (false? v) "No"
                (keyword? v) (name v)
                (string? v) (str/trim v)
                :else (str v))]
    (str "- " label ": " (if (str/blank? (str value)) "—" value))))

(defn- rc-summary-text
  [{:keys [type fields]}]
  (let [fields (or fields {})
        ordered [:contacted? :meeting/date :meeting/time :service/id :budget :notes]]
    (str (rc-title type) "\n\n"
         (str/join "\n" (map (fn [k] (rc-format-field k (get fields k))) ordered)))))

(defn handle-report-card-message
  [state _chat-user chat-id text]
  (let [cfg (get-in state [:config :telegram])
        session (get-report-card-session! chat-id)]
    (if-not session
      {:text "No active report card. Open a task and tap Report card."}
      (let [{:keys [stage message-id]} session
            trimmed (some-> text str str/trim)]
        (cond
          (not= stage :rc/notes-await)
          {:text "Use the buttons to continue."}

          (str/blank? trimmed)
          {:text "Send a short note (non-empty), or tap Skip notes."}

          :else
          (do
            (save-report-card-session!
             chat-id
             (-> session
                 (assoc :stage :rc/review)
                 (assoc-in [:fields :notes] trimmed)))
            (when message-id
              (edit-message!
               cfg
               {:chat-id chat-id
                :message-id message-id
                :text (rc-summary-text (get-report-card-session! chat-id))
                :reply-markup (rc-review-keyboard)}))
            {:status :handled}))))))

