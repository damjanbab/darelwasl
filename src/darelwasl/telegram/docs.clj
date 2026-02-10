(in-ns 'darelwasl.telegram)

(comment "Docs UI helpers + date/time pickers.")

(declare docs-menu-inline-keyboard
         docs-analytics-menu-inline-keyboard
         docs-skip-cancel-inline-keyboard
         docs-payment-reference-inline-keyboard
         docs-payment-note-inline-keyboard
         docs-payment-method-inline-keyboard
         docs-payment-invoice-attach-inline-keyboard
         docs-invoice-status-inline-keyboard
         docs-agreements-menu-inline-keyboard
         docs-agreement-actions-inline-keyboard
         docs-agreement-party-inline-keyboard
         docs-agreement-terms-source-inline-keyboard
         docs-agreement-accept-by-inline-keyboard
         docs-agreement-accept-channels-inline-keyboard
         docs-plan-items-inline-keyboard
         docs-plan-item-actions-inline-keyboard
         docs-plan-item-kind-inline-keyboard
         docs-plan-item-invoice-on-inline-keyboard)

(defn- truncate-text
  [s max-len]
  (if (and (string? s) (pos? max-len) (> (count s) max-len))
    (let [trim-len (max 0 (- max-len 3))]
      (str (subs s 0 trim-len) "..."))
    s))

(defn- now-inst
  []
  (Date/from (Instant/now)))

(defn- followup-date
  [option]
  (case option
    :tomorrow (Date/from (.plus (Instant/now) (Duration/ofDays 1)))
    :in-3-days (Date/from (.plus (Instant/now) (Duration/ofDays 3)))
    :next-week (Date/from (.plus (Instant/now) (Duration/ofDays 7)))
    nil))

(defn- parse-followup-date
  [text]
  (let [raw (str/trim (or text ""))]
    (or (try
          (Date/from (Instant/parse raw))
          (catch Exception _ nil))
        (try
          (let [date (LocalDate/parse raw)
                zoned (.atStartOfDay date (ZoneId/systemDefault))]
            (Date/from (.toInstant zoned)))
          (catch Exception _ nil)))))

(defn- yyyy-mm
  [^LocalDate d]
  (format "%04d-%02d" (.getYear d) (.getMonthValue d)))

(defn- yyyy-mm-dd
  [^LocalDate d]
  (format "%04d-%02d-%02d" (.getYear d) (.getMonthValue d) (.getDayOfMonth d)))

(defn- parse-ym
  [s]
  (try
    (LocalDate/parse (str (str/trim (or s "")) "-01"))
    (catch Exception _ nil)))

(defn- local-date->date
  [^LocalDate d]
  (when d
    (let [zoned (.atStartOfDay d (ZoneId/systemDefault))]
      (Date/from (.toInstant zoned)))))

(defn- parse-ymd->date
  [s]
  (try
    (local-date->date (LocalDate/parse (str/trim (or s ""))))
    (catch Exception _ nil)))

(defn- hhmm->local-time
  "Parse a callback time token like \"0930\" into a LocalTime."
  [s]
  (let [raw (str/trim (or s ""))]
    (when (re-matches #"\d{4}" raw)
      (let [h (Integer/parseInt (subs raw 0 2))
            m (Integer/parseInt (subs raw 2 4))]
        (when (and (<= 0 h 23) (<= 0 m 59))
          (java.time.LocalTime/of h m))))))

(defn- local-date-time->date
  [^LocalDate d ^java.time.LocalTime t]
  (when (and d t)
    (let [ldt (.atTime d t)
          zoned (.atZone ldt (ZoneId/systemDefault))]
      (Date/from (.toInstant zoned)))))

(defn- docs-send-file!
  [state chat-id actor file & {:keys [caption]}]
  (let [cfg (get-in state [:config :telegram])
        conn (ensure-conn state)
        storage-dir (get-in state [:config :files :storage-dir])
        file-id (:file/id file)
        workspace (:actor/workspace actor)]
    (cond
      (nil? file-id) {:error "Missing file id"}
      (str/blank? (str storage-dir)) {:error "File storage not configured"}
      :else
      (let [res (files/fetch-file conn file-id workspace)]
        (if-let [err (:error res)]
          {:error (:message err)}
          (let [{:file/keys [storage-path name]} (:file res)
                path (when storage-path (.getPath (io/file storage-dir storage-path)))]
            (if (and path (.exists (io/file path)))
              (send-document-file! cfg {:chat-id (str chat-id)
                                        :file (io/file path)
                                        :filename name
                                        :caption caption})
              {:error "Stored file not found"})))))))

(defn- date-picker-inline-keyboard
  [{:keys [month quicks allow-skip? skip-label extra-rows]}]
  (let [month (or month (LocalDate/now (ZoneId/systemDefault)))
        month-start (-> month (.withDayOfMonth 1))
        offset (dec (.getValue (.getDayOfWeek month-start)))
        days-in-month (.lengthOfMonth month-start)
        blanks (repeat (max 0 offset) nil)
        days (map (fn [day]
                    (let [d (.withDayOfMonth month-start (int day))]
                      {:text (str day)
                       :callback_data (str "dp:day:" (yyyy-mm-dd d))}))
                  (range 1 (inc days-in-month)))
        cells (concat blanks days)
        pad (mod (- 7 (mod (count cells) 7)) 7)
        cells (concat cells (repeat pad nil))
        weeks (partition 7 cells)
        month-label (.format month-start (java.time.format.DateTimeFormatter/ofPattern "MMM yyyy"))
        prev-month (.minusMonths month-start 1)
        next-month (.plusMonths month-start 1)
        nav-row [{:text "‹" :callback_data (str "dp:nav:" (yyyy-mm prev-month))}
                 {:text month-label :callback_data "dp:noop"}
                 {:text "›" :callback_data (str "dp:nav:" (yyyy-mm next-month))}]
        header-row (mapv (fn [label] {:text label :callback_data "dp:noop"})
                         ["Mon" "Tue" "Wed" "Thu" "Fri" "Sat" "Sun"])
        week-rows (mapv (fn [week]
                          (mapv (fn [cell]
                                  (if (nil? cell)
                                    {:text " " :callback_data "dp:noop"}
                                    cell))
                                week))
                        weeks)
        quicks (or quicks [])
        quick-rows (when (seq quicks)
                     (->> quicks
                          (map (fn [{:keys [id label]}]
                                 {:text label :callback_data (str "dp:quick:" (name id))}))
                          (partition-all 2)
                          (mapv vec)))
        skip-row (when allow-skip?
                   [[{:text (or skip-label "Skip") :callback_data "dp:skip"}]])
        rows (vec (concat [nav-row header-row]
                          week-rows
                          (or quick-rows [])
                          (or skip-row [])
                          (or extra-rows [])))]
    {:inline_keyboard rows}))

(defn- time-picker-inline-keyboard
  "Inline keyboard for picking a time-of-day (hour first). Uses callback data:
  - tp:hour:<HH>
  - tp:now
  - tp:skip"
  [{:keys [allow-skip? skip-label extra-rows]}]
  (let [hours (mapv (fn [h]
                      (let [hh (format "%02d" (int h))]
                        (inline-button hh (str "tp:hour:" hh))))
                    (range 0 24))
        hour-rows (mapv vec (partition-all 4 hours))
        util-row (cond-> [(inline-button "Now" "tp:now")]
                   allow-skip? (conj (inline-button (or skip-label "Skip time") "tp:skip")))
        rows (vec (concat hour-rows
                          [util-row]
                          (or extra-rows [])))]
    {:inline_keyboard rows}))

(defn- time-picker-minutes-inline-keyboard
  [{:keys [hour allow-skip? skip-label extra-rows]}]
  (let [hh (format "%02d" (int hour))
        minute-row (mapv (fn [mm]
                           (inline-button mm (str "tp:set:" hh mm)))
                         ["00" "15" "30" "45"])
        nav-row [(inline-button "Back" "tp:back")
                 (inline-button "Now" "tp:now")]
        skip-row (when allow-skip?
                   [[(inline-button (or skip-label "Skip time") "tp:skip")]])
        rows (vec (concat [minute-row nav-row]
                          (or skip-row [])
                          (or extra-rows [])))]
    {:inline_keyboard rows}))

(defn- apply-docs-time-picker-set!
  [state {:keys [chat-id chat-user message-id raw]}]
  (let [cfg (get-in state [:config :telegram])
        session (get-docs-session! chat-id)
        t (hhmm->local-time raw)
        fmt (java.time.format.DateTimeFormatter/ofPattern "HH:mm")]
    (when (and session t)
      (cond
	        (= (:stage session) :docs/payment-time)
	        (let [ymd (get-in session [:draft :payment/paid-date])
	              d (when ymd (try (LocalDate/parse (str ymd)) (catch Exception _ nil)))
	              picked (when d (local-date-time->date d t))]
	          (when picked
            (save-docs-session! chat-id (-> session
                                            (assoc :stage :docs/payment-reference)
                                            (update :draft dissoc :payment/paid-date)
                                            (assoc-in [:draft :payment/paid-at] picked)))
            (edit-message! cfg {:chat-id chat-id
                                :message-id message-id
                                :text (str "Payment time set: " (.format t fmt))
                                :reply-markup {:inline_keyboard []}})
            (send-message! cfg {:chat-id chat-id
	                                :text "Send the payment reference (optional):"
	                                :message-key (str "docs-payment-ref-" (System/currentTimeMillis))
	                                :reply-markup (docs-payment-reference-inline-keyboard)})))

	        (= (:stage session) :docs/agreement-accept-time)
	        (let [ymd (get-in session [:draft :agreement/accepted-date])
	              d (when ymd (try (LocalDate/parse (str ymd)) (catch Exception _ nil)))
	              picked (when d (local-date-time->date d t))
	              agreement-id (get-in session [:draft :agreement/id])]
	          (when (and picked agreement-id)
	            (save-docs-session! chat-id (-> session
	                                            (assoc :stage :docs/agreement-accept-by)
	                                            (update :draft dissoc :agreement/accepted-date)
	                                            (assoc-in [:draft :agreement/accepted-at] picked)))
	            (edit-message! cfg {:chat-id chat-id
	                                :message-id message-id
	                                :text (str "Acceptance time set: " (.format t fmt))
	                                :reply-markup {:inline_keyboard []}})
	            (send-message! cfg {:chat-id chat-id
	                                :text "Accepted by (optional):"
	                                :message-key (str "docs-agreement-accept-by-" (System/currentTimeMillis))
	                                :reply-markup (docs-agreement-accept-by-inline-keyboard agreement-id)})))

	        (= (:stage session) :docs/inv-due-time)
	        (let [ymd (get-in session [:draft :invoice/due-date])
	              d (when ymd (try (LocalDate/parse (str ymd)) (catch Exception _ nil)))
	              picked (when d (local-date-time->date d t))]
          (when picked
            (let [draft (:draft session)
                  actor (actions/actor-from-telegram chat-user)
                  input (cond-> {:client/id (:client-id session)
                                 :invoice/number (:invoice/number draft)
                                 :invoice/total-amount (:invoice/total-amount draft)
                                 :invoice/status (:invoice/status draft)
                                 :invoice/due-at picked}
                          (nil? (:invoice/status draft)) (dissoc :invoice/status))
                  res (actions/execute! state {:action/id :cap/action/invoice-create
                                               :actor actor
                                               :input input})]
              (save-docs-session! chat-id (assoc session :stage :docs/menu :draft nil))
              (if-let [err (:error res)]
                (send-message! cfg {:chat-id chat-id
                                    :text (str "Unable to add invoice: " (:message err))
                                    :message-key (str "docs-inv-create-error-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)})
                (do
                  (edit-message! cfg {:chat-id chat-id
                                      :message-id message-id
                                      :text (str "Invoice due time set: " (.format t fmt))
                                      :reply-markup {:inline_keyboard []}})
                  (send-message! cfg {:chat-id chat-id
                                      :text "Invoice added."
                                      :message-key (str "docs-inv-added-" (System/currentTimeMillis))
                                      :reply-markup (docs-menu-inline-keyboard)}))))))

	        (= (:stage session) :docs/plan-item-due-time)
	        (let [ymd (get-in session [:draft :plan.item/due-date])
	              d (when ymd (try (LocalDate/parse (str ymd)) (catch Exception _ nil)))
	              picked (when d (local-date-time->date d t))]
	          (when picked
            (let [draft (:draft session)
                  actor (actions/actor-from-telegram chat-user)
                  agreement-id (or (:agreement/id session) (:agreement/id draft))
                  ;; Prefer client doc-pack currency; fallback to SAR.
                  doc-pack-res (when (and actor (:client-id session))
                                 (actions/execute! state {:action/id :cap/action/doc-pack-read
                                                          :actor actor
                                                          :input {:client/id (:client-id session)}}))
                  currency (or (get-in doc-pack-res [:result :doc-pack :doc.pack/currency])
                               "SAR")
                  existing (when (and actor agreement-id)
                             (actions/execute! state {:action/id :cap/action/plan-item-list
                                                      :actor actor
                                                      :input {:agreement/id agreement-id}}))
                  idx (when (and existing (nil? (:error existing)))
                        (inc (count (get-in existing [:result :plan-items] []))))
                  input (cond-> {:agreement/id agreement-id
                                 :plan.item/kind (:plan.item/kind draft)
                                 :plan.item/invoice-on (:plan.item/invoice-on draft)
                                 :plan.item/label (:plan.item/label draft)
                                 :plan.item/amount (:plan.item/amount draft)
                                 :plan.item/currency currency
                                 :plan.item/due-at picked}
                          (number? idx) (assoc :plan.item/index idx))
                  res (actions/execute! state {:action/id :cap/action/plan-item-create
                                               :actor actor
                                               :input input})]
              (save-docs-session! chat-id (-> session
                                              (assoc :stage :docs/agreement-actions
                                                     :agreement/id agreement-id
                                                     :draft nil)))
              (if-let [err (:error res)]
                (send-message! cfg {:chat-id chat-id
                                    :text (str "Unable to add plan item: " (:message err))
                                    :message-key (str "docs-plan-item-error-" (System/currentTimeMillis))
                                    :reply-markup (docs-agreement-actions-inline-keyboard agreement-id)})
                (do
                  (edit-message! cfg {:chat-id chat-id
                                      :message-id message-id
                                      :text (str "Due time set: " (.format t fmt))
                                      :reply-markup {:inline_keyboard []}})
                  (send-message! cfg {:chat-id chat-id
	                                      :text "Plan item added."
	                                      :message-key (str "docs-plan-item-added-" (System/currentTimeMillis))
	                                      :reply-markup (docs-agreement-actions-inline-keyboard agreement-id)}))))))

	        (= (:stage session) :docs/plan-item-edit-due-time)
	        (let [ymd (get-in session [:draft :plan.item/due-date])
	              d (when ymd (try (LocalDate/parse (str ymd)) (catch Exception _ nil)))
	              picked (when d (local-date-time->date d t))]
	          (when picked
	            (let [draft (:draft session)
	                  actor (actions/actor-from-telegram chat-user)
	                  agreement-id (:agreement/id draft)
	                  plan-item-id (:plan.item/id draft)
	                  res (actions/execute! state {:action/id :cap/action/plan-item-update
	                                               :actor actor
	                                               :input {:plan.item/id plan-item-id
	                                                       :plan.item/due-at picked}})]
	              (save-docs-session! chat-id (-> session
	                                              (assoc :stage :docs/agreement-actions
	                                                     :agreement/id agreement-id)))
	              (if-let [err (:error res)]
	                (send-message! cfg {:chat-id chat-id
	                                    :text (str "Unable to update due date/time: " (:message err))
	                                    :message-key (str "docs-plan-item-edit-due-error-" (System/currentTimeMillis))
	                                    :reply-markup (docs-agreement-actions-inline-keyboard agreement-id)})
	                (do
	                  (edit-message! cfg {:chat-id chat-id
	                                      :message-id message-id
	                                      :text (str "Due time set: " (.format t fmt))
	                                      :reply-markup {:inline_keyboard []}})
	                  (send-message! cfg {:chat-id chat-id
	                                      :text "Plan item updated."
	                                      :message-key (str "docs-plan-item-edit-due-ok-" (System/currentTimeMillis))
	                                      :reply-markup (docs-plan-item-actions-inline-keyboard {:agreement-id agreement-id
	                                                                                             :plan-item-id plan-item-id
	                                                                                             :active? true})}))))))

	        :else nil))))

(defn- latest-pending-reason
  [db task-id]
  (when (and db task-id)
    (let [rows (seq (d/q '[:find ?body ?created
                           :in $ ?tid
                           :where [?t :task/id ?tid]
                                  [?n :note/subject ?t]
                                  [?n :note/type :note.type/pending-reason]
                                  [?n :note/body ?body]
                                  [?n :note/created-at ?created]]
                         db task-id))]
      (some->> rows
               (sort-by second #(compare %2 %1))
               ffirst))))

(defn- pending-reason-for-task
  [db task]
  (when (and (= :pending (:task/status task))
             (:task/id task))
    (latest-pending-reason db (:task/id task))))

(def ^:private list-ttl-ms
  (* 30 60 1000))

(defonce task-list-state
  (atom {}))

(defn- prune-task-lists!
  []
  (let [cutoff (- (System/currentTimeMillis) list-ttl-ms)]
    (swap! task-list-state
           (fn [entries]
             (into {}
                   (filter (fn [[_ v]]
                             (let [ts (:created-at v 0)]
                               (>= ts cutoff))))
                   entries)))))

(defn- task-list-key
  [chat-id message-id]
  (str chat-id ":" message-id))

(defn- save-task-list!
  [chat-id message-id filters]
  (prune-task-lists!)
  (swap! task-list-state assoc (task-list-key chat-id message-id) (assoc filters :created-at (System/currentTimeMillis))))

(defn- get-task-list!
  [chat-id message-id]
  (prune-task-lists!)
  (get @task-list-state (task-list-key chat-id message-id)))

(defn- capture-inline-keyboard
  [message-id]
  {:inline_keyboard
   [[(inline-button "Task" (str "capture:task:" message-id))
     (inline-button "Client" (str "capture:client:" message-id))
     (inline-button "Dismiss" (str "capture:cancel:" message-id))]]})

(defn- link-client-inline-keyboard
  [task-id]
  {:inline_keyboard
   [[(inline-button "Pick client" (str "task:client:pick:" task-id))
     (inline-button "Create client" (str "task:client:create:" task-id))]
    [(inline-button "Skip" (str "task:client:skip:" task-id))]]})

(defn- client-pick-inline-keyboard
  [task-id clients]
  (let [buttons (map (fn [{:client/keys [id name]}]
                       (inline-button (truncate-text (or name "Client") 22)
                                      (str "task:client:set:" task-id ":" id)))
                     clients)
        rows (->> buttons
                  (partition-all 2)
                  (mapv vec))]
    {:inline_keyboard
     (conj rows [(inline-button "Create client" (str "task:client:create:" task-id))
                 (inline-button "Cancel" (str "task:client:cancel:" task-id))])}))

(defn- docs-client-pick-inline-keyboard
  [clients]
  (let [buttons (map (fn [{:client/keys [id name]}]
                       (inline-button (truncate-text (or name "Client") 22)
                                      (str "docs:client:set:" id)))
                     clients)
        rows (->> buttons
                  (partition-all 2)
                  (mapv vec))]
    {:inline_keyboard
     (conj rows [(inline-button "Cancel" "docs:cancel")])}))

(defn- docs-invoice-pick-inline-keyboard
  [invoices on-pick-data & {:keys [cancel-data skip-data]}]
  (let [buttons (map (fn [inv]
                       (let [iid (:invoice/id inv)
                             num (or (:invoice/number inv) (subs (str iid) 0 8))
                             status (some-> (:invoice/status inv) name)
                             label (truncate-text (str num (when status (str " · " status))) 28)]
                         (inline-button label (format on-pick-data iid))))
                     (or invoices []))
        rows (->> buttons
                  (partition-all 2)
                  (mapv vec))
        tail (cond-> []
               skip-data (conj (inline-button "Skip" skip-data))
               cancel-data (conj (inline-button "Cancel" cancel-data)))]
    {:inline_keyboard
     (cond
       (empty? tail) rows
       (empty? rows) [(vec tail)]
       :else (conj rows (vec tail)))}))

(defn- docs-payment-pick-inline-keyboard
  [payments on-pick-data & {:keys [cancel-data]}]
  (let [buttons (map (fn [p]
                       (let [pid (:payment/id p)
                             amt (or (:payment/amount p) "")
                             paid-at (or (:payment/paid-at p) "")
                             label (truncate-text (str amt " " paid-at) 28)]
                         (inline-button label (format on-pick-data pid))))
                     (or payments []))
        rows (->> buttons
                  (partition-all 2)
                  (mapv vec))
        rows (if cancel-data
               (conj rows [(inline-button "Cancel" cancel-data)])
               rows)]
    {:inline_keyboard (vec rows)}))

(defn- docs-agreement-pick-inline-keyboard
  [agreements on-pick-data & {:keys [cancel-data]}]
  (let [buttons (map (fn [a]
                       (let [aid (:agreement/id a)
                             num (or (:agreement/number a) (subs (str aid) 0 8))
                             status (some-> (:agreement/status a) name)
                             label (truncate-text (str num (when status (str " · " status))) 28)]
                         (inline-button label (format on-pick-data aid))))
                     (or agreements []))
        rows (->> buttons
                  (partition-all 2)
                  (mapv vec))
        rows (if cancel-data
               (conj rows [(inline-button "Cancel" cancel-data)])
               rows)]
    {:inline_keyboard (vec rows)}))

(defn- docs-agreements-menu-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "Pick agreement" "docs:agreements:pick")
     (inline-button "Create agreement" "docs:agreements:create")]
    [(inline-button "Back" "docs:menu")]]})

(defn- docs-analytics-menu-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "Revenue (12 months)" "docs:analytics:revenue")
     (inline-button "Outstanding invoices" "docs:analytics:outstanding")]
    [(inline-button "Back" "docs:menu")]]})

(defn- docs-agreement-actions-inline-keyboard
  [agreement-id]
  {:inline_keyboard
   [[(inline-button "Overview" (str "docs:agreements:overview:" agreement-id))
     (inline-button "Plan items" (str "docs:agreements:plan:list:" agreement-id))]
    [(inline-button "Invoices" (str "docs:agreements:invoices:" agreement-id))
     (inline-button "Payments" (str "docs:agreements:payments:" agreement-id))]
    [(inline-button "Add plan item" (str "docs:agreements:plan:add:" agreement-id))
     (inline-button "Generate due invoices" (str "docs:agreements:due:" agreement-id))]
    [(inline-button "Issue proposal PDF" (str "docs:agreements:propose:" agreement-id))
     (inline-button "Accept + issue PDFs" (str "docs:agreements:accept:" agreement-id))]
    [(inline-button "Back" "docs:agreements:menu")]]})

(defn- docs-agreement-party-inline-keyboard
  [field]
  {:inline_keyboard
   [[(inline-button "Skip" (str "docs:agreement:party:skip:" (name field)))
     (inline-button "Cancel" "docs:agreements:menu")]]})

(defn- docs-agreement-terms-source-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "Use System Agreement (KSA v2)" "docs:agreement:terms:system-v2")]
    [(inline-button "Custom (paste terms)" "docs:agreement:terms:custom")]
    [(inline-button "Cancel" "docs:agreements:menu")]]})

(defn- docs-agreement-accept-by-inline-keyboard
  [agreement-id]
  {:inline_keyboard
   [[(inline-button "Skip" (str "docs:agreement:accept:by:skip:" agreement-id))
     (inline-button "Cancel" (str "docs:agreements:set:" agreement-id))]]})

(def ^:private accept-delivery-channels
  [{:id :email :label "Email"}
   {:id :whatsapp :label "WhatsApp"}
   {:id :telegram :label "Telegram"}
   {:id :paper :label "Paper"}])

(defn- docs-agreement-accept-channels-inline-keyboard
  [{:keys [agreement-id selected]}]
  (let [selected (set (or selected #{}))
        toggle-btn (fn [{:keys [id label]}]
                     (let [picked? (contains? selected id)
                           txt (str (if picked? "✓ " "") label)]
                       (inline-button txt (str "docs:agreement:accept:channels:toggle:" (name id) ":" agreement-id))))]
    {:inline_keyboard
     [(mapv toggle-btn (take 2 accept-delivery-channels))
      (mapv toggle-btn (drop 2 accept-delivery-channels))
      [(inline-button "Done" (str "docs:agreement:accept:channels:done:" agreement-id))
       (inline-button "Skip" (str "docs:agreement:accept:channels:skip:" agreement-id))]
      [(inline-button "Cancel" (str "docs:agreements:set:" agreement-id))]]}))

(defn- docs-agreement-accept-consent-inline-keyboard
  [agreement-id]
  {:inline_keyboard
   [[(inline-button "Yes" (str "docs:agreement:accept:consent:yes:" agreement-id))
     (inline-button "No" (str "docs:agreement:accept:consent:no:" agreement-id))]
    [(inline-button "Skip" (str "docs:agreement:accept:consent:skip:" agreement-id))
     (inline-button "Cancel" (str "docs:agreements:set:" agreement-id))]]})

(defn- docs-agreement-accept-confirm-inline-keyboard
  [agreement-id]
  {:inline_keyboard
   [[(inline-button "Confirm acceptance" (str "docs:agreement:accept:confirm:" agreement-id))]
    [(inline-button "Cancel" (str "docs:agreements:set:" agreement-id))]]})

(defn- plan-item-label
  [it]
  (let [label (or (:plan.item/label it) "—")
        amount (or (:plan.item/amount it) "—")
        cur (or (:plan.item/currency it) "")
        due (or (:plan.item/due-at it) "—")
        inactive? (= false (:plan.item/active? it))]
    (truncate-text (str label
                       (when inactive? " (inactive)")
                       " · " amount " " cur " · due " due)
                  42)))

(defn- docs-plan-items-inline-keyboard
  [agreement-id plan-items]
  {:inline_keyboard
   (vec
    (concat
     (mapcat (fn [it]
               (let [pid (:plan.item/id it)
                     line [(inline-button (plan-item-label it) (str "docs:plan-item:open:" agreement-id ":" pid))]
                     active? (not= false (:plan.item/active? it))
                     actions (when active?
                               [(inline-button "Invoice PDF" (str "docs:plan-item:invoice:issue:" agreement-id ":" pid))
                                (inline-button "Record payment" (str "docs:plan-item:payment:" agreement-id ":" pid))])]
                 (cond-> [line]
                   (seq actions) (conj actions))))
             (or plan-items []))
     [[(inline-button "Back" (str "docs:agreements:set:" agreement-id))]]))})

(defn- fmt-money
  [amount currency]
  (let [cur (or currency "")]
    (cond
      (number? amount) (str (format "%.2f" (double amount)) (when-not (str/blank? cur) (str " " cur)))
      (string? amount) (str amount (when-not (str/blank? cur) (str " " cur)))
      :else (str "—" (when-not (str/blank? cur) (str " " cur))))))

(defn- invoice-line-label
  [inv outstanding-by-id]
  (let [num (or (:invoice/number inv) (some-> (:invoice/id inv) str (subs 0 8)) "—")
        total (:invoice/total-amount inv)
        cur (:invoice/currency inv)
        status (some-> (:invoice/status inv) name)
        outstanding (get-in outstanding-by-id [(:invoice/id inv) :outstanding])
        due (or (:invoice/due-at inv) "—")
        main (str "Invoice " num " · " (fmt-money total cur) " · " (or status "—"))
        extra (when (number? outstanding)
                (str " · out " (fmt-money outstanding cur)))
        s (str main (or extra "") " · due " due)]
    (truncate-text s 42)))

(defn- docs-invoices-inline-keyboard
  [agreement-id invoices outstanding-by-id]
  {:inline_keyboard
   (vec
    (concat
     (mapcat (fn [inv]
               (let [iid (:invoice/id inv)
                     line [(inline-button (invoice-line-label inv outstanding-by-id)
                                          (str "docs:invoice:open:" agreement-id ":" iid))]
                     actions [(inline-button "Invoice PDF" (str "docs:invoice:pdf:" agreement-id ":" iid))
                              (inline-button "Record payment" (str "docs:invoice:payment:" agreement-id ":" iid))
                              (inline-button "Receipts" (str "docs:invoice:receipts:" agreement-id ":" iid))]]
                 [line actions]))
             (or invoices []))
     [[(inline-button "Back" (str "docs:agreements:set:" agreement-id))]]))})

(defn- docs-invoice-actions-inline-keyboard
  [{:keys [agreement-id invoice-id]}]
  {:inline_keyboard
   [[(inline-button "Invoice PDF" (str "docs:invoice:pdf:" agreement-id ":" invoice-id))
     (inline-button "Record payment" (str "docs:invoice:payment:" agreement-id ":" invoice-id))]
    [(inline-button "Receipts" (str "docs:invoice:receipts:" agreement-id ":" invoice-id))
     (inline-button "Back" (str "docs:agreements:invoices:" agreement-id))]]})

(defn- docs-plan-item-actions-inline-keyboard
  [{:keys [agreement-id plan-item-id active?]}]
  (let [active? (not= false active?)
        toggle-label (if active? "Deactivate" "Activate")]
    {:inline_keyboard
     (vec
      (concat
       (when active?
         [[(inline-button "Invoice PDF" (str "docs:plan-item:invoice:issue:" agreement-id ":" plan-item-id))
           (inline-button "Record payment" (str "docs:plan-item:payment:" agreement-id ":" plan-item-id))]])
       [[(inline-button "Edit label" (str "docs:plan-item:edit:label:" agreement-id ":" plan-item-id))
         (inline-button "Edit amount" (str "docs:plan-item:edit:amount:" agreement-id ":" plan-item-id))]
        [(inline-button "Edit due date/time" (str "docs:plan-item:edit:due:" agreement-id ":" plan-item-id))]
        [(inline-button "Invoice on" (str "docs:plan-item:edit:invoice-on:" agreement-id ":" plan-item-id))
         (inline-button "Kind" (str "docs:plan-item:edit:kind:" agreement-id ":" plan-item-id))]
        [(inline-button "Move up" (str "docs:plan-item:move:up:" agreement-id ":" plan-item-id))
         (inline-button "Move down" (str "docs:plan-item:move:down:" agreement-id ":" plan-item-id))]
        [(inline-button toggle-label (str "docs:plan-item:toggle:" agreement-id ":" plan-item-id))
         (inline-button "Back" (str "docs:agreements:plan:list:" agreement-id))]]))}))

(defn- docs-plan-item-invoice-on-edit-inline-keyboard
  [{:keys [agreement-id plan-item-id]}]
  {:inline_keyboard
   [[(inline-button "On accept" (str "docs:plan-item:set:invoice-on:accepted:" agreement-id ":" plan-item-id))
     (inline-button "On due date" (str "docs:plan-item:set:invoice-on:due:" agreement-id ":" plan-item-id))]
    [(inline-button "Manual" (str "docs:plan-item:set:invoice-on:manual:" agreement-id ":" plan-item-id))
     (inline-button "Back" (str "docs:plan-item:open:" agreement-id ":" plan-item-id))]]})

(defn- docs-plan-item-kind-edit-inline-keyboard
  [{:keys [agreement-id plan-item-id]}]
  {:inline_keyboard
   [[(inline-button "Installment" (str "docs:plan-item:set:kind:installment:" agreement-id ":" plan-item-id))
     (inline-button "Milestone" (str "docs:plan-item:set:kind:milestone:" agreement-id ":" plan-item-id))]
    [(inline-button "Recurring" (str "docs:plan-item:set:kind:recurring:" agreement-id ":" plan-item-id))
     (inline-button "Back" (str "docs:plan-item:open:" agreement-id ":" plan-item-id))]]})

(defn- docs-plan-item-kind-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "Installment" "docs:plan-item:kind:installment")
     (inline-button "Milestone" "docs:plan-item:kind:milestone")]
    [(inline-button "Recurring" "docs:plan-item:kind:recurring")
     (inline-button "Cancel" "docs:agreements:menu")]]})

(defn- docs-plan-item-invoice-on-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "On accept" "docs:plan-item:invoice-on:accepted")
     (inline-button "On due date" "docs:plan-item:invoice-on:due")]
    [(inline-button "Manual" "docs:plan-item:invoice-on:manual")
     (inline-button "Cancel" "docs:agreements:menu")]]})

(defn- docs-menu-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "Company name" "docs:field:company-name")
     (inline-button "Currency" "docs:field:currency")]
    [(inline-button "Services included" "docs:field:services")
     (inline-button "Payment plan" "docs:field:payment-plan")]
    [(inline-button "Status notes" "docs:field:status-notes")]
    [(inline-button "Add invoice" "docs:invoice:add")
     (inline-button "Add payment" "docs:payment:add")]
    [(inline-button "Agreements" "docs:agreements:menu")]
    [(inline-button "Analytics" "docs:analytics:menu")]
    [(inline-button "Issue status report PDF" "docs:generate:status-report")]
    [(inline-button "Invoice PDF (latest)" "docs:generate:invoice:pick")
     (inline-button "Receipt PDF (latest)" "docs:generate:receipt:pick")]
    [(inline-button "Change client" "docs:client:pick")
     (inline-button "Close" "docs:cancel")]]})

(defn- docs-currency-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "SAR" "docs:currency:SAR")
     (inline-button "USD" "docs:currency:USD")
     (inline-button "EUR" "docs:currency:EUR")]
    [(inline-button "Other..." "docs:currency:other")
     (inline-button "Cancel" "docs:menu")]]})

(defn- docs-skip-cancel-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "Skip" "docs:skip")
     (inline-button "Cancel" "docs:menu")]]})

(defn- docs-invoice-status-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "Draft" "docs:invoice:status:draft")
     (inline-button "Sent" "docs:invoice:status:sent")]
	    [(inline-button "Void" "docs:invoice:status:void")
	     (inline-button "Cancel" "docs:menu")]]})

(defn- docs-payment-method-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "Cash" "docs:payment:method:cash")
     (inline-button "Transfer" "docs:payment:method:transfer")]
    [(inline-button "Card" "docs:payment:method:card")
     (inline-button "Other" "docs:payment:method:other")]
    [(inline-button "Cancel" "docs:menu")]]})

(defn- docs-payment-invoice-attach-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "Attach to invoice" "docs:payment:invoice:pick")
     (inline-button "No invoice" "docs:payment:invoice:skip")]
    [(inline-button "Cancel" "docs:menu")]]})

(defn- docs-payment-reference-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "Skip" "docs:payment:ref:skip")
     (inline-button "Cancel" "docs:menu")]]})

(defn- docs-payment-note-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "Skip" "docs:payment:note:skip")
     (inline-button "Cancel" "docs:menu")]]})
