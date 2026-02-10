(in-ns 'darelwasl.telegram)

(comment "Callback handlers: docs flows.")

(defmethod
 handle-callback-dispatch
 :docs/agreement-accept-by-skip
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
    (:agreement-id parsed)
    agreement-id
    (when
     raw
     (try (UUID/fromString (str raw)) (catch Exception _ nil)))]
   (if
    (and chat-user session (:client-id session) agreement-id)
    (let
     [draft
      (:draft session)
      accepted-at
      (:agreement/accepted-at draft)
      selected
      (set (or (:agreement/delivery-channels draft) #{}))]
     (if-not
      accepted-at
      (send-message!
       cfg
       {:chat-id chat-id,
        :text "Pick acceptance date/time first.",
        :message-key
        (str
         "docs-agreement-accept-missing-"
         (System/currentTimeMillis))})
      (do
       (save-docs-session!
        chat-id
        (->
         session
         (assoc :stage :docs/agreement-accept-channels)
         (assoc
          :draft
          (assoc
           draft
           :agreement/id
           agreement-id
           :agreement/delivery-channels
           selected))))
       (edit-message!
        cfg
        {:chat-id chat-id,
         :message-id message-id,
         :text "Delivery channels (select all that apply):",
         :reply-markup
         (docs-agreement-accept-channels-inline-keyboard
          {:agreement-id agreement-id, :selected selected})}))))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid agreement.",
      :message-key
      (str "docs-agreement-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/agreement-accept-channels-done
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
    (:agreement-id parsed)
    agreement-id
    (when
     raw
     (try (UUID/fromString (str raw)) (catch Exception _ nil)))]
   (if
    (and chat-user session (:client-id session) agreement-id)
    (do
     (save-docs-session!
      chat-id
      (assoc session :stage :docs/agreement-accept-consent))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Electronic consent to receive documents?",
       :reply-markup
       (docs-agreement-accept-consent-inline-keyboard agreement-id)}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid agreement.",
      :message-key
      (str "docs-agreement-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/agreement-accept-channels-skip
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
    (:agreement-id parsed)
    agreement-id
    (when
     raw
     (try (UUID/fromString (str raw)) (catch Exception _ nil)))]
   (if
    (and chat-user session (:client-id session) agreement-id)
    (do
     (save-docs-session!
      chat-id
      (->
       session
       (assoc :stage :docs/agreement-accept-consent)
       (assoc-in [:draft :agreement/delivery-channels] #{})))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Electronic consent to receive documents?",
       :reply-markup
       (docs-agreement-accept-consent-inline-keyboard agreement-id)}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid agreement.",
      :message-key
      (str "docs-agreement-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/agreement-accept-channels-toggle
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
    value
    (:value parsed)
    channel
    (when value (keyword value))
    raw
    (:agreement-id parsed)
    agreement-id
    (when
     raw
     (try (UUID/fromString (str raw)) (catch Exception _ nil)))]
   (if
    (and
     chat-user
     session
     (:client-id session)
     agreement-id
     (contains? #{:email :paper :telegram :whatsapp} channel))
    (let
     [draft
      (:draft session)
      selected
      (set (or (:agreement/delivery-channels draft) #{}))
      selected
      (if
       (contains? selected channel)
       (disj selected channel)
       (conj selected channel))
      next-draft
      (assoc draft :agreement/delivery-channels selected)]
     (save-docs-session!
      chat-id
      (->
       session
       (assoc :stage :docs/agreement-accept-channels)
       (assoc :draft next-draft)))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Delivery channels (select all that apply):",
       :reply-markup
       (docs-agreement-accept-channels-inline-keyboard
        {:agreement-id agreement-id, :selected selected})}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid selection.",
      :message-key
      (str
       "docs-agreement-accept-channels-invalid-"
       (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/agreement-accept-confirm
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
    (:agreement-id parsed)
    agreement-id
    (when
     raw
     (try (UUID/fromString (str raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and chat-user session (:client-id session) agreement-id)
    (let
     [draft
      (:draft session)
      accepted-at
      (:agreement/accepted-at draft)
      accepted-by
      (some-> (:agreement/accepted-by draft) str/trim not-empty)
      channels
      (set (or (:agreement/delivery-channels draft) #{}))
      input
      (cond->
       #:agreement{:id agreement-id,
                   :accepted-at accepted-at,
                   :delivery-channels channels}
       accepted-by
       (assoc :agreement/accepted-by accepted-by)
       (contains? channels :telegram)
       (assoc :agreement/delivery-telegram-chat-id (str chat-id))
       (contains? draft :agreement/electronic-consent?)
       (assoc
        :agreement/electronic-consent?
        (:agreement/electronic-consent? draft))
       (:agreement/consented-at draft)
       (assoc
        :agreement/consented-at
        (:agreement/consented-at draft)))]
     (when
      message-id
      (edit-message!
       cfg
       {:chat-id chat-id,
        :message-id message-id,
        :text "Accepting agreement + issuing PDFs…",
        :reply-markup {:inline_keyboard []}}))
     (let
      [res
       (actions/execute!
        state
        {:action/id :cap/action/agreement-accept,
         :actor actor,
         :input input})
       agreement-pdf
       (get-in res [:result :agreement-pdf :file])
       invoices
       (get-in res [:result :invoices] [])
       send-agreement
       (when
        agreement-pdf
        (docs-send-file!
         state
         chat-id
         actor
         agreement-pdf
         :caption
         "Agreement"))
       invoice-files
       (->>
        invoices
        (keep (fn [inv] (get-in inv [:invoice-pdf :file])))
        vec)
       _
       (doseq
        [f invoice-files]
        (docs-send-file! state chat-id actor f :caption "Invoice"))]
      (save-docs-session!
       chat-id
       (->
        session
        (assoc
         :stage
         :docs/agreement-actions
         :agreement/id
         agreement-id)))
      (if-let
       [err (:error res)]
       (send-message!
        cfg
        {:chat-id chat-id,
         :text (str "Unable to accept agreement: " (:message err)),
         :message-key
         (str
          "docs-agreement-accept-error-"
          (System/currentTimeMillis)),
         :reply-markup
         (docs-agreement-actions-inline-keyboard agreement-id)})
       (if-let
        [send-err (:error send-agreement)]
        (send-message!
         cfg
         {:chat-id chat-id,
          :text
          (str
           "Accepted, but could not send agreement PDF: "
           send-err),
          :message-key
          (str
           "docs-agreement-accept-send-error-"
           (System/currentTimeMillis)),
          :reply-markup
          (docs-agreement-actions-inline-keyboard agreement-id)})
        (send-message!
         cfg
         {:chat-id chat-id,
          :text
          (str
           "Accepted. Sent agreement + "
           (count invoice-files)
           " invoice PDFs."),
          :message-key
          (str "docs-agreement-accept-ok-" (System/currentTimeMillis)),
          :reply-markup
          (docs-agreement-actions-inline-keyboard agreement-id)})))))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid agreement.",
      :message-key
      (str "docs-agreement-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/agreement-accept-consent
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
    (:agreement-id parsed)
    agreement-id
    (when
     raw
     (try (UUID/fromString (str raw)) (catch Exception _ nil)))
    v
    (:value parsed)]
   (if
    (and chat-user session (:client-id session) agreement-id)
    (let
     [draft
      (:draft session)
      accepted-at
      (:agreement/accepted-at draft)
      next-draft
      (cond
       (= v :skip)
       (dissoc
        draft
        :agreement/electronic-consent?
        :agreement/consented-at)
       (true? v)
       (assoc
        draft
        :agreement/electronic-consent?
        true
        :agreement/consented-at
        accepted-at)
       (false? v)
       (assoc draft :agreement/electronic-consent? false)
       :else
       draft)
      channels
      (set (or (:agreement/delivery-channels next-draft) #{}))
      by
      (some-> (:agreement/accepted-by next-draft) str/trim not-empty)
      consent
      (cond
       (= v :skip)
       "—"
       (true? (:agreement/electronic-consent? next-draft))
       "yes"
       (false? (:agreement/electronic-consent? next-draft))
       "no"
       :else
       "—")
      summary
      (str
       "Confirm acceptance:\n"
       "Accepted at: "
       (or accepted-at "—")
       "\n"
       "Accepted by: "
       (or by "—")
       "\n"
       "Delivery: "
       (if
        (seq channels)
        (str/join ", " (map name (sort channels)))
        "—")
       "\n"
       "Electronic consent: "
       consent)]
     (save-docs-session!
      chat-id
      (->
       session
       (assoc :stage :docs/agreement-accept-confirm)
       (assoc
        :draft
        (assoc next-draft :agreement/delivery-channels channels))))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text summary,
       :reply-markup
       (docs-agreement-accept-confirm-inline-keyboard agreement-id)}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid agreement.",
      :message-key
      (str "docs-agreement-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/agreement-party-skip
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
    field-str
    (:field parsed)
    field
    (when field-str (keyword field-str))]
   (when
    session
    (let
     [draft
      (or (:draft session) {})
      respond!
      (fn
       [payload]
       (if
        message-id
        (edit-message!
         cfg
         (assoc payload :chat-id chat-id :message-id message-id))
        (send-message!
         cfg
         (assoc
          payload
          :chat-id
          chat-id
          :message-key
          (str
           "docs-agreement-party-skip-"
           (System/currentTimeMillis))))))]
     (case
      field
      :client-company
      (do
       (save-docs-session!
        chat-id
        (->
         session
         (assoc :stage :docs/agreement-client-representative)
         (assoc :draft draft)))
       (respond!
        {:text
         "Client representative/signatory (optional). Send it, or tap Skip.",
         :reply-markup
         (docs-agreement-party-inline-keyboard
          :client-representative)}))
      :client-representative
      (do
       (save-docs-session!
        chat-id
        (->
         session
         (assoc :stage :docs/agreement-our-representative)
         (assoc :draft draft)))
       (respond!
        {:text "Our representative (optional). Send it, or tap Skip.",
         :reply-markup
         (docs-agreement-party-inline-keyboard :our-representative)}))
      :our-representative
      (do
       (save-docs-session!
        chat-id
        (->
         session
         (assoc :stage :docs/agreement-our-recipient)
         (assoc :draft draft)))
       (respond!
        {:text "Who receives funds (optional). Send it, or tap Skip.",
         :reply-markup
         (docs-agreement-party-inline-keyboard :our-recipient)}))
      :our-recipient
      (let
       [month
        (LocalDate/now (ZoneId/systemDefault))
        quicks
        [{:id :today, :label "Today"}
         {:id :tomorrow, :label "Tomorrow"}]]
       (save-docs-session!
        chat-id
        (->
         session
         (assoc :stage :docs/agreement-effective-date)
         (assoc
          :picker
          {:kind :docs/agreement-effective,
           :text "Pick agreement effective date (optional):",
           :quicks quicks})
         (assoc :draft draft)))
       (respond!
        {:text "Pick agreement effective date (optional):",
         :reply-markup
         (date-picker-inline-keyboard
          {:month month,
           :quicks quicks,
           :allow-skip? true,
           :skip-label "Skip (no effective date)",
           :extra-rows
           [[(inline-button "Cancel" "docs:agreements:menu")]]})}))
      nil))))))

(defmethod
 handle-callback-dispatch
 :docs/agreement-terms-system-v1
 [ctx]
 (let [{:keys [chat-id message-id chat-user cfg]} ctx
       session (get-docs-session! chat-id)]
   (if-not (and chat-user session (= :docs/agreement-terms-source (:stage session)))
     (send-message! cfg {:chat-id chat-id
                         :text "Start from Docs → Create agreement."
                         :message-key (str "docs-agreement-terms-system-v1-missing-" (System/currentTimeMillis))})
     (do
       (save-docs-session! chat-id (-> session
                                       (assoc :stage :docs/agreement-client-company)
                                       (assoc-in [:draft :agreement/terms] (agreement-templates/terms :agreement.template/system-v1))))
       (edit-message! cfg {:chat-id chat-id
                           :message-id message-id
                           :text "Client company/legal name (optional). Send it, or tap Skip."
                           :reply-markup (docs-agreement-party-inline-keyboard :client-company)})))))

(defmethod
 handle-callback-dispatch
 :docs/agreement-terms-custom
 [ctx]
 (let [{:keys [chat-id message-id chat-user cfg]} ctx
       session (get-docs-session! chat-id)]
   (if-not (and chat-user session (= :docs/agreement-terms-source (:stage session)))
     (send-message! cfg {:chat-id chat-id
                         :text "Start from Docs → Create agreement."
                         :message-key (str "docs-agreement-terms-custom-missing-" (System/currentTimeMillis))})
     (do
       (save-docs-session! chat-id (assoc session :stage :docs/agreement-terms-custom))
       (edit-message! cfg {:chat-id chat-id
                           :message-id message-id
                           :text "Send agreement terms (free-form)."
                           :reply-markup {:inline_keyboard [[(inline-button "Cancel" "docs:agreements:menu")]]}})))))

(defmethod
 handle-callback-dispatch
 :docs/agreements-accept
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
    (:agreement-id parsed)
    agreement-id
    (when
     raw
     (try (UUID/fromString (str raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and chat-user session (:client-id session) agreement-id)
    (let
     [month
      (LocalDate/now (ZoneId/systemDefault))
      quicks
      [{:id :today, :label "Today"}
       {:id :yesterday, :label "Yesterday"}]]
     (save-docs-session!
      chat-id
      (->
       session
       (assoc :stage :docs/agreement-accept-date)
       (assoc
        :picker
        {:kind :docs/agreement-accept,
         :text "Pick acceptance date:",
         :quicks quicks})
       (assoc :draft #:agreement{:id agreement-id})))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Pick acceptance date:",
       :reply-markup
       (date-picker-inline-keyboard
        {:month month,
         :quicks quicks,
         :extra-rows
         [[(inline-button
            "Back"
            (str "docs:agreements:set:" agreement-id))]]})}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid agreement.",
      :message-key
      (str "docs-agreement-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/agreements-create
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
   (if
    (and chat-user session (:client-id session))
    (do
     (save-docs-session!
      chat-id
      (->
       session
       (assoc
        :stage
        :docs/agreement-title
        :agreement/id
        nil
        :draft
        nil)))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Send agreement title:",
       :reply-markup
       {:inline_keyboard
        [[(inline-button "Cancel" "docs:agreements:menu")]]}}))
    (prompt-docs-client-pick! state chat-id)))))

(defmethod
 handle-callback-dispatch
 :docs/agreements-due
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
    (:agreement-id parsed)
    agreement-id
    (when
     raw
     (try (UUID/fromString (str raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and chat-user session (:client-id session) agreement-id)
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/agreement-generate-due-invoices,
        :actor actor,
        :input {:agreement/id agreement-id, :window/days 30}})
      invoices
      (get-in res [:result :invoices] [])
      invoice-files
      (->>
       invoices
       (keep (fn [inv] (get-in inv [:invoice-pdf :file])))
       vec)
      _
      (doseq
       [f invoice-files]
       (docs-send-file! state chat-id actor f :caption "Invoice"))]
     (save-docs-session!
      chat-id
      (->
       session
       (assoc
        :stage
        :docs/agreement-actions
        :agreement/id
        agreement-id)))
     (if-let
      [err (:error res)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to generate due invoices: " (:message err)),
        :message-key
        (str "docs-agreement-due-error-" (System/currentTimeMillis)),
        :reply-markup
        (docs-agreement-actions-inline-keyboard agreement-id)})
      (send-message!
       cfg
       {:chat-id chat-id,
        :text
        (str "Generated " (count invoice-files) " due invoice PDFs."),
        :message-key
        (str "docs-agreement-due-ok-" (System/currentTimeMillis)),
        :reply-markup
        (docs-agreement-actions-inline-keyboard agreement-id)})))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid agreement.",
      :message-key
      (str "docs-agreement-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/agreements-invoices
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
    (:agreement-id parsed)
    agreement-id
    (when
     raw
     (try (UUID/fromString (str raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and chat-user session (:client-id session) agreement-id)
    (let
     [inv-res
      (actions/execute!
       state
       {:action/id :cap/action/invoice-list,
        :actor actor,
        :input #:client{:id (:client-id session)}})
      all-invs
      (get-in inv-res [:result :invoices] [])
      invoices
      (->>
       all-invs
       (filter
        (fn
         [inv]
         (=
          agreement-id
          (get-in inv [:invoice/agreement :agreement/id]))))
       vec)
      out-res
      (actions/execute!
       state
       {:action/id :cap/action/analytics-outstanding-invoices,
        :actor actor,
        :input #:client{:id (:client-id session)}})
      out-lines
      (get-in out-res [:result :invoices] [])
      outstanding-by-id
      (->> out-lines (map (fn [l] [(:invoice/id l) l])) (into {}))]
     (save-docs-session!
      chat-id
      (->
       session
       (assoc
        :stage
        :docs/agreement-actions
        :agreement/id
        agreement-id)
       (assoc :cache {:outstanding-by-id outstanding-by-id})))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text
       (if
        (seq invoices)
        "Invoices: tap an action under an invoice."
        "No invoices yet."),
       :reply-markup
       (if
        (seq invoices)
        (docs-invoices-inline-keyboard
         agreement-id
         invoices
         outstanding-by-id)
        (docs-agreement-actions-inline-keyboard agreement-id))}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid agreement.",
      :message-key
      (str "docs-agreement-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/agreements-menu
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
   (if
    (and chat-user session (:client-id session))
    (do
     (save-docs-session!
      chat-id
      (assoc session :stage :docs/agreements-menu))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Agreements:",
       :reply-markup (docs-agreements-menu-inline-keyboard)}))
    (prompt-docs-client-pick! state chat-id)))))

(defmethod
 handle-callback-dispatch
 :docs/agreements-overview
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
    (:agreement-id parsed)
    agreement-id
    (when
     raw
     (try (UUID/fromString (str raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and chat-user session (:client-id session) agreement-id)
    (let
     [agreements-res
      (actions/execute!
       state
       {:action/id :cap/action/agreement-list,
        :actor actor,
        :input #:client{:id (:client-id session)}})
      agreement
      (some
       (fn [a] (when (= agreement-id (:agreement/id a)) a))
       (get-in agreements-res [:result :agreements] []))
      items-res
      (actions/execute!
       state
       {:action/id :cap/action/plan-item-list,
        :actor actor,
        :input #:agreement{:id agreement-id}})
      plan-items
      (get-in items-res [:result :plan-items] [])
      inv-res
      (actions/execute!
       state
       {:action/id :cap/action/invoice-list,
        :actor actor,
        :input #:client{:id (:client-id session)}})
      all-invs
      (get-in inv-res [:result :invoices] [])
      invoices
      (->>
       all-invs
       (filter
        (fn
         [inv]
         (=
          agreement-id
          (get-in inv [:invoice/agreement :agreement/id]))))
       vec)
      out-res
      (actions/execute!
       state
       {:action/id :cap/action/analytics-outstanding-invoices,
        :actor actor,
        :input #:client{:id (:client-id session)}})
      out-lines
      (get-in out-res [:result :invoices] [])
      outstanding-by-id
      (->> out-lines (map (fn [l] [(:invoice/id l) l])) (into {}))
      currency
      (or
       (:plan.item/currency (first plan-items))
       (:invoice/currency (first invoices))
       "SAR")
      plan-total
      (double
       (reduce
        +
        0.0
        (keep
         (fn
          [it]
          (when
           (number? (:plan.item/amount it))
           (double (:plan.item/amount it))))
         plan-items)))
      inv-total
      (double
       (reduce
        +
        0.0
        (keep
         (fn
          [inv]
          (when
           (number? (:invoice/total-amount inv))
           (double (:invoice/total-amount inv))))
         invoices)))
      paid-total
      (double
       (reduce
        +
        0.0
        (keep
         (fn
          [inv]
          (get-in outstanding-by-id [(:invoice/id inv) :paid]))
         invoices)))
      out-total
      (double
       (reduce
        +
        0.0
        (keep
         (fn
          [inv]
          (get-in outstanding-by-id [(:invoice/id inv) :outstanding]))
         invoices)))
      num
      (or
       (:agreement/number agreement)
       (some-> agreement-id str (subs 0 8)))
      status
      (or (some-> (:agreement/status agreement) name) "—")
      title
      (or (:agreement/title agreement) "—")
      summary
      (str
       "Agreement "
       num
       "\n"
       "Status: "
       status
       "\n"
       "Title: "
       title
       "\n\n"
       "Plan: "
       (count plan-items)
       " item(s) · total "
       (fmt-money plan-total currency)
       "\n"
       "Invoices: "
       (count invoices)
       " · invoiced "
       (fmt-money inv-total currency)
       " · paid "
       (fmt-money paid-total currency)
       " · outstanding "
       (fmt-money out-total currency))
      _
      (save-docs-session!
       chat-id
       (->
        session
        (assoc
         :stage
         :docs/agreement-actions
         :agreement/id
         agreement-id)
        (assoc :cache {:outstanding-by-id outstanding-by-id})))]
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text summary,
       :reply-markup
       (docs-agreement-actions-inline-keyboard agreement-id)}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid agreement.",
      :message-key
      (str "docs-agreement-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/agreements-payments
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
    (:agreement-id parsed)
    agreement-id
    (when
     raw
     (try (UUID/fromString (str raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and chat-user session (:client-id session) agreement-id)
    (let
     [inv-res
      (actions/execute!
       state
       {:action/id :cap/action/invoice-list,
        :actor actor,
        :input #:client{:id (:client-id session)}})
      all-invs
      (get-in inv-res [:result :invoices] [])
      invoice-id-set
      (->>
       all-invs
       (filter
        (fn
         [inv]
         (=
          agreement-id
          (get-in inv [:invoice/agreement :agreement/id]))))
       (map :invoice/id)
       set)
      pay-res
      (actions/execute!
       state
       {:action/id :cap/action/payment-list,
        :actor actor,
        :input #:client{:id (:client-id session)}})
      all-pays
      (get-in pay-res [:result :payments] [])
      payments
      (->>
       all-pays
       (filter
        (fn
         [p]
         (or
          (=
           agreement-id
           (get-in p [:payment/agreement :agreement/id]))
          (contains?
           invoice-id-set
           (get-in p [:payment/invoice :invoice/id])))))
       (sort-by
        :payment/paid-at
        (fn* [p1__173# p2__172#] (compare p2__172# p1__173#)))
       vec)
      preview
      (take 10 payments)
      lines
      (if
       (seq preview)
       (->>
        preview
        (map
         (fn
          [p]
          (str
           (fmt-money (:payment/amount p) (:payment/currency p))
           " · "
           (or (:payment/paid-at p) "—")
           (when-let [ref (:payment/reference p)] (str " · " ref)))))
        (str/join "\n"))
       "No payments yet.")
      kb
      (when
       (seq payments)
       (docs-payment-pick-inline-keyboard
        payments
        "docs:generate:receipt:set:%s"
        :cancel-data
        (str "docs:agreements:set:" agreement-id)))]
     (save-docs-session!
      chat-id
      (->
       session
       (assoc
        :stage
        :docs/agreement-actions
        :agreement/id
        agreement-id)))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text
       (str
        "Payments (latest 10):\n"
        lines
        (when
         (seq payments)
         "\n\nPick one to re-send its receipt PDF:")),
       :reply-markup
       (or kb (docs-agreement-actions-inline-keyboard agreement-id))}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid agreement.",
      :message-key
      (str "docs-agreement-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/agreements-pick
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
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and chat-user session (:client-id session))
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/agreement-list,
        :actor actor,
        :input #:client{:id (:client-id session)}})
      agreements
      (get-in res [:result :agreements] [])
      kb
      (docs-agreement-pick-inline-keyboard
       agreements
       "docs:agreements:set:%s"
       :cancel-data
       "docs:agreements:menu")]
     (save-docs-session!
      chat-id
      (assoc session :stage :docs/agreements-pick))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Pick agreement:",
       :reply-markup kb}))
    (prompt-docs-client-pick! state chat-id)))))

(defmethod
 handle-callback-dispatch
 :docs/agreements-plan-add
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
    (:agreement-id parsed)
    agreement-id
    (when
     raw
     (try (UUID/fromString (str raw)) (catch Exception _ nil)))]
   (if
    (and chat-user session (:client-id session) agreement-id)
    (do
     (save-docs-session!
      chat-id
      (->
       session
       (assoc
        :stage
        :docs/plan-item-kind
        :agreement/id
        agreement-id
        :draft
        #:agreement{:id agreement-id})))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Choose plan item kind:",
       :reply-markup (docs-plan-item-kind-inline-keyboard)}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid agreement.",
      :message-key
      (str "docs-agreement-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/agreements-plan-list
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
    (:agreement-id parsed)
    agreement-id
    (when
     raw
     (try (UUID/fromString (str raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and chat-user session (:client-id session) agreement-id)
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/plan-item-list,
        :actor actor,
        :input #:agreement{:id agreement-id}})
      items
      (get-in res [:result :plan-items] [])
      lines
      (if
       (seq items)
       "Tap an action under the plan item."
       "No plan items yet.")]
     (save-docs-session!
      chat-id
      (->
       session
       (assoc
        :stage
        :docs/agreement-actions
        :agreement/id
        agreement-id)))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text (str "Plan items:\n" lines),
       :reply-markup
       (if
        (seq items)
        (docs-plan-items-inline-keyboard agreement-id items)
        (docs-agreement-actions-inline-keyboard agreement-id))}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid agreement.",
      :message-key
      (str "docs-agreement-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/agreements-propose
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
    (:agreement-id parsed)
    agreement-id
    (when
     raw
     (try (UUID/fromString (str raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)
    actor-name
    (or (:user/username chat-user) (:user/name chat-user))]
   (if
    (and chat-user session (:client-id session) agreement-id)
    (do
     (when
      message-id
      (edit-message!
       cfg
       {:chat-id chat-id,
        :message-id message-id,
        :text "Issuing proposal PDF…",
        :reply-markup {:inline_keyboard []}}))
     (let
      [res
       (actions/execute!
        state
        {:action/id :cap/action/agreement-propose,
         :actor actor,
         :input
         #:agreement{:id agreement-id, :proposed-by actor-name}})
       proposal-pdf
       (get-in res [:result :proposal-pdf :file])
       send-proposal
       (when
        proposal-pdf
        (docs-send-file!
         state
         chat-id
         actor
         proposal-pdf
         :caption
         "Proposal"))]
      (save-docs-session!
       chat-id
       (->
        session
        (assoc
         :stage
         :docs/agreement-actions
         :agreement/id
         agreement-id)))
      (if-let
       [err (:error res)]
       (send-message!
        cfg
        {:chat-id chat-id,
         :text (str "Unable to issue proposal: " (:message err)),
         :message-key
         (str
          "docs-agreement-propose-error-"
          (System/currentTimeMillis)),
         :reply-markup
         (docs-agreement-actions-inline-keyboard agreement-id)})
       (if-let
        [send-err (:error send-proposal)]
        (send-message!
         cfg
         {:chat-id chat-id,
          :text
          (str "Proposal issued, but could not send PDF: " send-err),
          :message-key
          (str
           "docs-agreement-propose-send-error-"
           (System/currentTimeMillis)),
          :reply-markup
          (docs-agreement-actions-inline-keyboard agreement-id)})
        (send-message!
         cfg
         {:chat-id chat-id,
          :text "Proposal issued and sent.",
          :message-key
          (str
           "docs-agreement-propose-ok-"
           (System/currentTimeMillis)),
          :reply-markup
          (docs-agreement-actions-inline-keyboard agreement-id)})))))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid agreement.",
      :message-key
      (str "docs-agreement-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/agreements-set
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
    (:agreement-id parsed)
    agreement-id
    (when
     raw
     (try (UUID/fromString (str raw)) (catch Exception _ nil)))]
   (if
    (and chat-user session (:client-id session) agreement-id)
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
       :text "Agreement:",
       :reply-markup
       (docs-agreement-actions-inline-keyboard agreement-id)}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid agreement.",
      :message-key
      (str "docs-agreement-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/analytics-menu
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
   (if
    (and chat-user session (:client-id session))
    (do
     (save-docs-session!
      chat-id
      (assoc session :stage :docs/analytics-menu))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Analytics:",
       :reply-markup (docs-analytics-menu-inline-keyboard)}))
    (prompt-docs-client-pick! state chat-id)))))

(defmethod
 handle-callback-dispatch
 :docs/analytics-outstanding
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
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and chat-user session (:client-id session))
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/analytics-outstanding-invoices,
        :actor actor,
        :input #:client{:id (:client-id session)}})
      invs
      (get-in res [:result :invoices] [])
      totals
      (get-in res [:result :totals] {})
      by-cur
      (->>
       invs
       (group-by :currency)
       (map
        (fn
         [[cur xs]]
         [cur (double (reduce + 0.0 (map :outstanding xs)))]))
       (sort-by first)
       vec)]
     (if-let
      [err (:error res)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text
        (str "Unable to fetch outstanding invoices: " (:message err)),
        :message-key
        (str
         "docs-analytics-outstanding-error-"
         (System/currentTimeMillis)),
        :reply-markup (docs-analytics-menu-inline-keyboard)})
      (let
       [summary
        (if
         (seq by-cur)
         (str/join
          "\n"
          (map
           (fn
            [[cur total]]
            (str (or cur "—") ": " (format "%.2f" total)))
           by-cur))
         "No invoices found.")
        headline
        (format
         "Totals: invoiced %.2f · paid %.2f · outstanding %.2f"
         (double (or (:total-invoiced totals) 0.0))
         (double (or (:total-paid totals) 0.0))
         (double (or (:total-outstanding totals) 0.0)))]
       (send-message!
        cfg
        {:chat-id chat-id,
         :text
         (str
          "Outstanding invoices:\n"
          headline
          "\n\nBy currency:\n"
          summary),
         :message-key
         (str
          "docs-analytics-outstanding-ok-"
          (System/currentTimeMillis)),
         :reply-markup (docs-analytics-menu-inline-keyboard)}))))
    (prompt-docs-client-pick! state chat-id)))))

(defmethod
 handle-callback-dispatch
 :docs/analytics-revenue
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
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and chat-user session (:client-id session))
    (let
     [from
      (.toString (.minus (Instant/now) (Duration/ofDays 365)))
      res
      (actions/execute!
       state
       {:action/id :cap/action/analytics-revenue-by-month,
        :actor actor,
        :input {:client/id (:client-id session), :from from}})
      series
      (get-in res [:result :series] [])]
     (if-let
      [err (:error res)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to fetch revenue: " (:message err)),
        :message-key
        (str
         "docs-analytics-revenue-error-"
         (System/currentTimeMillis)),
        :reply-markup (docs-analytics-menu-inline-keyboard)})
      (let
       [lines
        (if
         (seq series)
         (->>
          series
          (map
           (fn
            [{:keys [month currency total]}]
            (str
             month
             " · "
             (format "%.2f" (double (or total 0.0)))
             " "
             currency)))
          (str/join "\n"))
         "No payments in the last 12 months.")]
       (send-message!
        cfg
        {:chat-id chat-id,
         :text (str "Revenue (last 12 months):\n" lines),
         :message-key
         (str "docs-analytics-revenue-ok-" (System/currentTimeMillis)),
         :reply-markup (docs-analytics-menu-inline-keyboard)}))))
    (prompt-docs-client-pick! state chat-id)))))

(defmethod
 handle-callback-dispatch
 :docs/cancel
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
   (take-docs-session! chat-id)
   (edit-message!
    cfg
    {:chat-id chat-id,
     :message-id message-id,
     :text "Docs closed.",
     :reply-markup {:inline_keyboard []}}))))

(defmethod
 handle-callback-dispatch
 :docs/client-pick
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
  (prompt-docs-client-pick! state chat-id)))

(defmethod
 handle-callback-dispatch
 :docs/client-set
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
    (when
     cid
     (try (UUID/fromString (str cid)) (catch Exception _ nil)))
    client
    (when (and db client-id) (clients/client-by-id db client-id nil))
    name
    (or (:client/name client) "Client")]
   (if
    (and chat-user client-id)
    (do
     (save-docs-session!
      chat-id
      {:stage :docs/menu, :user chat-user, :client-id client-id})
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text (str "Documents for " name ":"),
       :reply-markup (docs-menu-inline-keyboard)}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid client.",
      :message-key
      (str "docs-client-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/currency
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
    v
    (:value parsed)
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and chat-user session (:client-id session) (present-string? v))
    (if
     (= v "other")
     (do
      (save-docs-session!
       chat-id
       (assoc session :stage :docs/field-currency-other))
      (edit-message!
       cfg
       {:chat-id chat-id,
        :message-id message-id,
        :text "Send currency code (e.g. SAR):",
        :reply-markup (docs-skip-cancel-inline-keyboard)}))
     (let
      [res
       (actions/execute!
        state
        {:action/id :cap/action/doc-pack-upsert,
         :actor actor,
         :input
         {:client/id (:client-id session), :doc.pack/currency v}})]
      (save-docs-session! chat-id (assoc session :stage :docs/menu))
      (if-let
       [err (:error res)]
       (send-message!
        cfg
        {:chat-id chat-id,
         :text (str "Unable to save: " (:message err)),
         :message-key
         (str "docs-currency-error-" (System/currentTimeMillis)),
         :reply-markup (docs-menu-inline-keyboard)})
       (edit-message!
        cfg
        {:chat-id chat-id,
         :message-id message-id,
         :text "Saved.",
         :reply-markup (docs-menu-inline-keyboard)}))))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid currency.",
      :message-key
      (str "docs-currency-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/field
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
   [session (get-docs-session! chat-id) field (:value parsed)]
   (if
    (and chat-user session (:client-id session))
    (case
     field
     "company-name"
     (do
      (save-docs-session!
       chat-id
       (assoc session :stage :docs/field-company-name))
      (edit-message!
       cfg
       {:chat-id chat-id,
        :message-id message-id,
        :text "Send company name:",
        :reply-markup (docs-skip-cancel-inline-keyboard)}))
     "currency"
     (do
      (save-docs-session!
       chat-id
       (assoc session :stage :docs/field-currency))
      (edit-message!
       cfg
       {:chat-id chat-id,
        :message-id message-id,
        :text "Choose currency:",
        :reply-markup (docs-currency-inline-keyboard)}))
     "services"
     (do
      (save-docs-session!
       chat-id
       (assoc session :stage :docs/field-services))
      (edit-message!
       cfg
       {:chat-id chat-id,
        :message-id message-id,
        :text "Send services included text:",
        :reply-markup (docs-skip-cancel-inline-keyboard)}))
     "payment-plan"
     (do
      (save-docs-session!
       chat-id
       (assoc session :stage :docs/field-payment-plan))
      (edit-message!
       cfg
       {:chat-id chat-id,
        :message-id message-id,
        :text "Send payment plan text:",
        :reply-markup (docs-skip-cancel-inline-keyboard)}))
     "status-notes"
     (do
      (save-docs-session!
       chat-id
       (assoc session :stage :docs/field-status-notes))
      (edit-message!
       cfg
       {:chat-id chat-id,
        :message-id message-id,
        :text "Send status notes text:",
        :reply-markup (docs-skip-cancel-inline-keyboard)}))
     (send-message!
      cfg
      {:chat-id chat-id,
       :text "Unknown field.",
       :message-key
       (str "docs-field-unknown-" (System/currentTimeMillis))}))
    (prompt-docs-client-pick! state chat-id)))))

(defmethod
 handle-callback-dispatch
 :docs/generate
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
    client-id
    (:client-id session)
    actor
    (actions/actor-from-telegram chat-user)
    type
    (:value parsed)
    action-id
    (case
     type
     :proposal
     :cap/action/proposal-generate
     :status-report
     :cap/action/status-report-generate
     nil)]
   (if
    (and chat-user session client-id action-id)
    (let
     [res
      (actions/execute!
       state
       {:action/id action-id,
        :actor actor,
        :input #:client{:id client-id}})
      file
      (get-in res [:result :file])
      send-res
      (when
       (and file (not (:error res)))
       (docs-send-file!
        state
        chat-id
        actor
        file
        :caption
        (case
         type
         :proposal
         "Proposal"
         :status-report
         "Status report"
         "Document")))]
     (save-docs-session! chat-id (assoc session :stage :docs/menu))
     (if-let
      [err (:error res)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to generate: " (:message err)),
        :message-key
        (str "docs-generate-error-" (System/currentTimeMillis)),
        :reply-markup (docs-menu-inline-keyboard)})
      (if-let
       [send-err (:error send-res)]
       (send-message!
        cfg
        {:chat-id chat-id,
         :text (str "Generated, but could not send PDF: " send-err),
         :message-key
         (str "docs-generate-send-error-" (System/currentTimeMillis)),
         :reply-markup (docs-menu-inline-keyboard)})
       (send-message!
        cfg
        {:chat-id chat-id,
         :text "PDF sent.",
         :message-key
         (str "docs-generate-ok-" (System/currentTimeMillis)),
         :reply-markup (docs-menu-inline-keyboard)}))))
    (prompt-docs-client-pick! state chat-id)))))

(defmethod
 handle-callback-dispatch
 :docs/generate-invoice-pick
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
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and chat-user session (:client-id session))
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/invoice-list,
        :actor actor,
        :input #:client{:id (:client-id session)}})
      invoices
      (get-in res [:result :invoices] [])
      kb
      (docs-invoice-pick-inline-keyboard
       invoices
       "docs:generate:invoice:set:%s"
       :cancel-data
       "docs:menu")]
     (save-docs-session!
      chat-id
      (assoc session :stage :docs/generate-invoice-pick))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Pick invoice:",
       :reply-markup kb}))
    (prompt-docs-client-pick! state chat-id)))))

(defmethod
 handle-callback-dispatch
 :docs/generate-invoice-set
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
    (:invoice-id parsed)
    invoice-id
    (when
     raw
     (try (UUID/fromString (str raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and chat-user session (:client-id session) invoice-id)
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/invoice-pdf-generate,
        :actor actor,
        :input
        {:client/id (:client-id session), :invoice/id invoice-id}})
      file
      (get-in res [:result :file])
      send-res
      (when
       (and file (not (:error res)))
       (docs-send-file! state chat-id actor file :caption "Invoice"))]
     (save-docs-session! chat-id (assoc session :stage :docs/menu))
     (if-let
      [err (:error res)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to generate invoice PDF: " (:message err)),
        :message-key
        (str "docs-invoice-pdf-error-" (System/currentTimeMillis)),
        :reply-markup (docs-menu-inline-keyboard)})
      (if-let
       [send-err (:error send-res)]
       (send-message!
        cfg
        {:chat-id chat-id,
         :text (str "Generated, but could not send PDF: " send-err),
         :message-key
         (str
          "docs-invoice-pdf-send-error-"
          (System/currentTimeMillis)),
         :reply-markup (docs-menu-inline-keyboard)})
       (send-message!
        cfg
        {:chat-id chat-id,
         :text "PDF sent.",
         :message-key
         (str "docs-invoice-pdf-ok-" (System/currentTimeMillis)),
         :reply-markup (docs-menu-inline-keyboard)}))))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid invoice.",
      :message-key
      (str "docs-invoice-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/generate-receipt-pick
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
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and chat-user session (:client-id session))
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/payment-list,
        :actor actor,
        :input #:client{:id (:client-id session)}})
      payments
      (get-in res [:result :payments] [])
      kb
      (docs-payment-pick-inline-keyboard
       payments
       "docs:generate:receipt:set:%s"
       :cancel-data
       "docs:menu")]
     (save-docs-session!
      chat-id
      (assoc session :stage :docs/generate-receipt-pick))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Pick payment:",
       :reply-markup kb}))
    (prompt-docs-client-pick! state chat-id)))))

(defmethod
 handle-callback-dispatch
 :docs/generate-receipt-set
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
    (:payment-id parsed)
    payment-id
    (when
     raw
     (try (UUID/fromString (str raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and chat-user session (:client-id session) payment-id)
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/receipt-generate,
        :actor actor,
        :input
        {:client/id (:client-id session), :payment/id payment-id}})
      file
      (get-in res [:result :file])
      send-res
      (when
       (and file (not (:error res)))
       (docs-send-file! state chat-id actor file :caption "Receipt"))]
     (save-docs-session! chat-id (assoc session :stage :docs/menu))
     (if-let
      [err (:error res)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to generate receipt PDF: " (:message err)),
        :message-key
        (str "docs-receipt-error-" (System/currentTimeMillis)),
        :reply-markup (docs-menu-inline-keyboard)})
      (if-let
       [send-err (:error send-res)]
       (send-message!
        cfg
        {:chat-id chat-id,
         :text (str "Generated, but could not send PDF: " send-err),
         :message-key
         (str "docs-receipt-send-error-" (System/currentTimeMillis)),
         :reply-markup (docs-menu-inline-keyboard)})
       (send-message!
        cfg
        {:chat-id chat-id,
         :text "PDF sent.",
         :message-key
         (str "docs-receipt-ok-" (System/currentTimeMillis)),
         :reply-markup (docs-menu-inline-keyboard)}))))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid payment.",
      :message-key
      (str "docs-payment-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/invoice-add
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
   (if
    (and chat-user session (:client-id session))
    (do
     (save-docs-session!
      chat-id
      (assoc session :stage :docs/inv-number :draft {}))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Send invoice number:",
       :reply-markup
       {:inline_keyboard [[(inline-button "Cancel" "docs:menu")]]}}))
    (prompt-docs-client-pick! state chat-id)))))

(defmethod
 handle-callback-dispatch
 :docs/invoice-open
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
    aid-raw
    (:agreement-id parsed)
    iid-raw
    (:invoice-id parsed)
    agreement-id
    (when
     aid-raw
     (try (UUID/fromString (str aid-raw)) (catch Exception _ nil)))
    invoice-id
    (when
     iid-raw
     (try (UUID/fromString (str iid-raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and
     chat-user
     session
     (:client-id session)
     agreement-id
     invoice-id)
    (let
     [inv-res
      (actions/execute!
       state
       {:action/id :cap/action/invoice-list,
        :actor actor,
        :input #:client{:id (:client-id session)}})
      invoice
      (some
       (fn [inv] (when (= invoice-id (:invoice/id inv)) inv))
       (get-in inv-res [:result :invoices] []))
      out-res
      (actions/execute!
       state
       {:action/id :cap/action/analytics-outstanding-invoices,
        :actor actor,
        :input #:client{:id (:client-id session)}})
      out-lines
      (get-in out-res [:result :invoices] [])
      outstanding
      (some (fn [l] (when (= invoice-id (:invoice/id l)) l)) out-lines)
      cur
      (:invoice/currency invoice)
      text
      (str
       "Invoice:\n"
       "Number: "
       (or (:invoice/number invoice) "—")
       "\n"
       "Status: "
       (or (some-> (:invoice/status invoice) name) "—")
       "\n"
       "Total: "
       (fmt-money (:invoice/total-amount invoice) cur)
       "\n"
       (when
        outstanding
        (str
         "Paid: "
         (fmt-money (:paid outstanding) cur)
         "\n"
         "Outstanding: "
         (fmt-money (:outstanding outstanding) cur)
         "\n"))
       "Due: "
       (or (:invoice/due-at invoice) "—"))]
     (if-not
      invoice
      (send-message!
       cfg
       {:chat-id chat-id,
        :text "Invoice not found.",
        :message-key
        (str "docs-invoice-missing-" (System/currentTimeMillis))})
      (do
       (save-docs-session!
        chat-id
        (->
         session
         (assoc
          :stage
          :docs/agreement-actions
          :agreement/id
          agreement-id)
         (assoc
          :draft
          {:agreement/id agreement-id, :invoice/id invoice-id})))
       (edit-message!
        cfg
        {:chat-id chat-id,
         :message-id message-id,
         :text text,
         :reply-markup
         (docs-invoice-actions-inline-keyboard
          {:agreement-id agreement-id, :invoice-id invoice-id})}))))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid invoice.",
      :message-key
      (str "docs-invoice-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/invoice-payment
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
    aid-raw
    (:agreement-id parsed)
    iid-raw
    (:invoice-id parsed)
    agreement-id
    (when
     aid-raw
     (try (UUID/fromString (str aid-raw)) (catch Exception _ nil)))
    invoice-id
    (when
     iid-raw
     (try (UUID/fromString (str iid-raw)) (catch Exception _ nil)))]
   (if
    (and
     chat-user
     session
     (:client-id session)
     agreement-id
     invoice-id)
    (do
     (save-docs-session!
      chat-id
      (assoc
       session
       :stage
       :docs/payment-amount
       :draft
       {:client/id (:client-id session),
        :invoice/id invoice-id,
        :agreement/id agreement-id}))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Send payment amount:",
       :reply-markup
       {:inline_keyboard
        [[(inline-button
           "Back"
           (str
            "docs:invoice:open:"
            agreement-id
            ":"
            invoice-id))]]}}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid invoice.",
      :message-key
      (str "docs-invoice-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/invoice-pdf
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
    aid-raw
    (:agreement-id parsed)
    iid-raw
    (:invoice-id parsed)
    agreement-id
    (when
     aid-raw
     (try (UUID/fromString (str aid-raw)) (catch Exception _ nil)))
    invoice-id
    (when
     iid-raw
     (try (UUID/fromString (str iid-raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and
     chat-user
     session
     (:client-id session)
     agreement-id
     invoice-id)
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/invoice-pdf-generate,
        :actor actor,
        :input
        {:client/id (:client-id session), :invoice/id invoice-id}})
      file
      (get-in res [:result :file])]
     (when
      file
      (docs-send-file! state chat-id actor file :caption "Invoice"))
     (when-let
      [err (:error res)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to generate invoice PDF: " (:message err)),
        :message-key
        (str "docs-invoice-pdf-error-" (System/currentTimeMillis))})))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid invoice.",
      :message-key
      (str "docs-invoice-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/invoice-receipts
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
    aid-raw
    (:agreement-id parsed)
    iid-raw
    (:invoice-id parsed)
    agreement-id
    (when
     aid-raw
     (try (UUID/fromString (str aid-raw)) (catch Exception _ nil)))
    invoice-id
    (when
     iid-raw
     (try (UUID/fromString (str iid-raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and
     chat-user
     session
     (:client-id session)
     agreement-id
     invoice-id)
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/payment-list,
        :actor actor,
        :input #:client{:id (:client-id session)}})
      pays
      (->>
       (get-in res [:result :payments] [])
       (filter
        (fn
         [p]
         (= invoice-id (get-in p [:payment/invoice :invoice/id]))))
       (sort-by
        :payment/paid-at
        (fn* [p1__175# p2__174#] (compare p2__174# p1__175#)))
       vec)
      kb
      (when
       (seq pays)
       (docs-payment-pick-inline-keyboard
        pays
        "docs:generate:receipt:set:%s"
        :cancel-data
        (str "docs:invoice:open:" agreement-id ":" invoice-id)))]
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text
       (if
        (seq pays)
        "Pick a payment to re-send its receipt PDF:"
        "No payments attached to this invoice yet."),
       :reply-markup
       (or
        kb
        (docs-invoice-actions-inline-keyboard
         {:agreement-id agreement-id, :invoice-id invoice-id}))}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid invoice.",
      :message-key
      (str "docs-invoice-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/invoice-status
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
    status-raw
    (:value parsed)
    status
    (when status-raw (keyword status-raw))
    quicks
    [{:id :in-7-days, :label "+7 days"}
     {:id :in-14-days, :label "+14 days"}
     {:id :in-30-days, :label "+30 days"}]
    month
    (LocalDate/now (ZoneId/systemDefault))]
   (if
    (and
     chat-user
     session
     (:client-id session)
     status
     (= (:stage session) :docs/inv-status))
    (do
     (save-docs-session!
      chat-id
      (->
       session
       (assoc :stage :docs/inv-due-date)
       (assoc
        :picker
        {:kind :docs/invoice-due,
         :text "Pick invoice due date (or No due date):",
         :quicks quicks})
       (assoc-in [:draft :invoice/status] status)))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Pick invoice due date (or No due date):",
       :reply-markup
       (date-picker-inline-keyboard
        {:month month,
         :quicks quicks,
         :allow-skip? true,
         :skip-label "No due date",
         :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid invoice status.",
      :message-key
      (str "docs-inv-status-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/menu
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
   (if
    (and chat-user session (:client-id session))
    (do
     (save-docs-session! chat-id (assoc session :stage :docs/menu))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Documents:",
       :reply-markup (docs-menu-inline-keyboard)}))
    (prompt-docs-client-pick! state chat-id)))))

(defmethod
 handle-callback-dispatch
 :docs/payment-add
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
   (if
    (and chat-user session (:client-id session))
    (do
     (save-docs-session!
      chat-id
      (assoc
       session
       :stage
       :docs/payment-amount
       :draft
       #:client{:id (:client-id session)}))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Send payment amount:",
       :reply-markup
       {:inline_keyboard [[(inline-button "Cancel" "docs:menu")]]}}))
    (prompt-docs-client-pick! state chat-id)))))

(defmethod
 handle-callback-dispatch
 :docs/payment-invoice-pick
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
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and chat-user session (:client-id session))
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/invoice-list,
        :actor actor,
        :input #:client{:id (:client-id session)}})
      invoices
      (get-in res [:result :invoices] [])
      kb
      (docs-invoice-pick-inline-keyboard
       invoices
       "docs:payment:invoice:set:%s"
       :cancel-data
       "docs:menu"
       :skip-data
       "docs:payment:invoice:skip")]
     (save-docs-session!
      chat-id
      (assoc session :stage :docs/payment-invoice-pick))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Pick invoice to attach:",
       :reply-markup kb}))
    (prompt-docs-client-pick! state chat-id)))))

(defmethod
 handle-callback-dispatch
 :docs/payment-invoice-set
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
    (:invoice-id parsed)
    invoice-id
    (when
     raw
     (try (UUID/fromString (str raw)) (catch Exception _ nil)))
    quicks
    [{:id :today, :label "Today"} {:id :yesterday, :label "Yesterday"}]
    month
    (LocalDate/now (ZoneId/systemDefault))]
   (if
    (and chat-user session (:client-id session) invoice-id)
    (do
     (save-docs-session!
      chat-id
      (->
       session
       (assoc :stage :docs/payment-date)
       (assoc
        :picker
        {:kind :docs/payment,
         :text "Pick payment date:",
         :quicks quicks})
       (assoc-in [:draft :invoice/id] invoice-id)))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Pick payment date:",
       :reply-markup
       (date-picker-inline-keyboard
        {:month month,
         :quicks quicks,
         :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid invoice.",
      :message-key
      (str
       "docs-payment-invoice-invalid-"
       (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/payment-invoice-skip
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
    quicks
    [{:id :today, :label "Today"} {:id :yesterday, :label "Yesterday"}]
    month
    (LocalDate/now (ZoneId/systemDefault))]
   (if
    (and chat-user session (:client-id session))
    (do
     (save-docs-session!
      chat-id
      (->
       session
       (assoc :stage :docs/payment-date)
       (assoc
        :picker
        {:kind :docs/payment,
         :text "Pick payment date:",
         :quicks quicks})))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Pick payment date:",
       :reply-markup
       (date-picker-inline-keyboard
        {:month month,
         :quicks quicks,
         :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))
    (prompt-docs-client-pick! state chat-id)))))

(defmethod
 handle-callback-dispatch
 :docs/payment-method
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
    method-raw
    (:value parsed)
    method
    (when method-raw (keyword method-raw))]
   (if
    (and chat-user session (:client-id session) method)
    (let
     [draft
      (-> (:draft session) (assoc :payment/method method))
      invoice-id
      (:invoice/id draft)]
     (if
      invoice-id
      (let
       [quicks
        [{:id :today, :label "Today"}
         {:id :yesterday, :label "Yesterday"}]
        month
        (LocalDate/now (ZoneId/systemDefault))]
       (save-docs-session!
        chat-id
        (->
         session
         (assoc :stage :docs/payment-date)
         (assoc :draft draft)
         (assoc
          :picker
          {:kind :docs/payment,
           :text "Pick payment date:",
           :quicks quicks})))
       (edit-message!
        cfg
        {:chat-id chat-id,
         :message-id message-id,
         :text "Pick payment date:",
         :reply-markup
         (date-picker-inline-keyboard
          {:month month,
           :quicks quicks,
           :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))
      (do
       (save-docs-session!
        chat-id
        (->
         session
         (assoc :stage :docs/payment-invoice-attach)
         (assoc :draft draft)))
       (edit-message!
        cfg
        {:chat-id chat-id,
         :message-id message-id,
         :text "Attach this payment to an invoice?",
         :reply-markup
         (docs-payment-invoice-attach-inline-keyboard)}))))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid payment method.",
      :message-key
      (str
       "docs-payment-method-invalid-"
       (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/payment-note-skip
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
    draft
    (:draft session)
    client-id
    (:client-id session)
    paid-at
    (:payment/paid-at draft)
    input
    (cond->
     {:client/id client-id,
      :payment/amount (:payment/amount draft),
      :payment/method (:payment/method draft),
      :payment/paid-at paid-at}
     (:invoice/id draft)
     (assoc :invoice/id (:invoice/id draft))
     (:agreement/id draft)
     (assoc :agreement/id (:agreement/id draft))
     (:plan.item/id draft)
     (assoc :plan.item/id (:plan.item/id draft))
     (present-string? (:payment/reference draft))
     (assoc :payment/reference (:payment/reference draft)))
    res
    (when
     (and chat-user session (:client-id session) paid-at)
     (actions/execute!
      state
      {:action/id :cap/action/payment-create,
       :actor (actions/actor-from-telegram chat-user),
       :input input}))]
   (if
    (and chat-user session (:client-id session))
    (if-not
     paid-at
     (send-message!
      cfg
      {:chat-id chat-id,
       :text "Pick payment date first.",
       :message-key
       (str "docs-payment-date-missing-" (System/currentTimeMillis))})
     (do
      (save-docs-session!
       chat-id
       (assoc session :stage :docs/menu :draft nil))
      (if-let
       [err (:error res)]
       (send-message!
        cfg
        {:chat-id chat-id,
         :text (str "Unable to add payment: " (:message err)),
         :message-key
         (str "docs-payment-create-error-" (System/currentTimeMillis)),
         :reply-markup (docs-menu-inline-keyboard)})
       (let
        [actor
         (actions/actor-from-telegram chat-user)
         receipt
         (get-in res [:result :receipt :file])
         invoice
         (get-in res [:result :invoice-pdf :file])]
        (when
         receipt
         (docs-send-file!
          state
          chat-id
          actor
          receipt
          :caption
          "Receipt"))
        (when
         invoice
         (docs-send-file!
          state
          chat-id
          actor
          invoice
          :caption
          "Invoice"))
        (send-message!
         cfg
         {:chat-id chat-id,
          :text "Payment added.",
          :message-key
          (str "docs-payment-added-" (System/currentTimeMillis)),
          :reply-markup (docs-menu-inline-keyboard)})))))
    (prompt-docs-client-pick! state chat-id)))))

(defmethod
 handle-callback-dispatch
 :docs/payment-ref-skip
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
   (if
    (and chat-user session (:client-id session))
    (if-not
     (get-in session [:draft :payment/paid-at])
     (send-message!
      cfg
      {:chat-id chat-id,
       :text "Pick payment date first.",
       :message-key
       (str "docs-payment-date-missing-" (System/currentTimeMillis))})
     (do
      (save-docs-session!
       chat-id
       (assoc session :stage :docs/payment-note))
      (send-message!
       cfg
       {:chat-id chat-id,
        :text
        "Payment note (optional). You can include who sent/received the money, bank details, anything:",
        :message-key
        (str "docs-payment-note-" (System/currentTimeMillis)),
        :reply-markup (docs-payment-note-inline-keyboard)})))
    (prompt-docs-client-pick! state chat-id)))))

(defmethod
 handle-callback-dispatch
 :docs/plan-item-edit-amount
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
    aid-raw
    (:agreement-id parsed)
    pid-raw
    (:plan-item-id parsed)
    agreement-id
    (when
     aid-raw
     (try (UUID/fromString (str aid-raw)) (catch Exception _ nil)))
    plan-item-id
    (when
     pid-raw
     (try (UUID/fromString (str pid-raw)) (catch Exception _ nil)))]
   (if
    (and
     chat-user
     session
     (:client-id session)
     agreement-id
     plan-item-id)
    (do
     (save-docs-session!
      chat-id
      (->
       session
       (assoc :stage :docs/plan-item-edit-amount)
       (assoc
        :draft
        {:agreement/id agreement-id, :plan.item/id plan-item-id})))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Send new plan item amount (e.g. 5000 or 5,000 SAR):",
       :reply-markup
       {:inline_keyboard
        [[(inline-button
           "Back"
           (str
            "docs:plan-item:open:"
            agreement-id
            ":"
            plan-item-id))]]}}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid plan item.",
      :message-key
      (str "docs-plan-item-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/plan-item-edit-due
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
    aid-raw
    (:agreement-id parsed)
    pid-raw
    (:plan-item-id parsed)
    agreement-id
    (when
     aid-raw
     (try (UUID/fromString (str aid-raw)) (catch Exception _ nil)))
    plan-item-id
    (when
     pid-raw
     (try (UUID/fromString (str pid-raw)) (catch Exception _ nil)))]
   (if
    (and
     chat-user
     session
     (:client-id session)
     agreement-id
     plan-item-id)
    (let
     [month
      (LocalDate/now (ZoneId/systemDefault))
      quicks
      [{:id :in-7-days, :label "+7 days"}
       {:id :in-14-days, :label "+14 days"}
       {:id :in-30-days, :label "+30 days"}]]
     (save-docs-session!
      chat-id
      (->
       session
       (assoc :stage :docs/plan-item-edit-due-date)
       (assoc
        :picker
        {:kind :docs/plan-item-edit-due,
         :text "Pick new due date:",
         :quicks quicks})
       (assoc
        :draft
        {:agreement/id agreement-id, :plan.item/id plan-item-id})))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Pick new due date:",
       :reply-markup
       (date-picker-inline-keyboard
        {:month month,
         :quicks quicks,
         :extra-rows
         [[(inline-button
            "Back"
            (str
             "docs:plan-item:open:"
             agreement-id
             ":"
             plan-item-id))]]})}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid plan item.",
      :message-key
      (str "docs-plan-item-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/plan-item-edit-invoice-on
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
    aid-raw
    (:agreement-id parsed)
    pid-raw
    (:plan-item-id parsed)
    agreement-id
    (when
     aid-raw
     (try (UUID/fromString (str aid-raw)) (catch Exception _ nil)))
    plan-item-id
    (when
     pid-raw
     (try (UUID/fromString (str pid-raw)) (catch Exception _ nil)))]
   (if
    (and
     chat-user
     session
     (:client-id session)
     agreement-id
     plan-item-id)
    (edit-message!
     cfg
     {:chat-id chat-id,
      :message-id message-id,
      :text "When should invoices be generated?",
      :reply-markup
      (docs-plan-item-invoice-on-edit-inline-keyboard
       {:agreement-id agreement-id, :plan-item-id plan-item-id})})
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid plan item.",
      :message-key
      (str "docs-plan-item-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/plan-item-edit-kind
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
    aid-raw
    (:agreement-id parsed)
    pid-raw
    (:plan-item-id parsed)
    agreement-id
    (when
     aid-raw
     (try (UUID/fromString (str aid-raw)) (catch Exception _ nil)))
    plan-item-id
    (when
     pid-raw
     (try (UUID/fromString (str pid-raw)) (catch Exception _ nil)))]
   (if
    (and
     chat-user
     session
     (:client-id session)
     agreement-id
     plan-item-id)
    (edit-message!
     cfg
     {:chat-id chat-id,
      :message-id message-id,
      :text "Pick plan item kind:",
      :reply-markup
      (docs-plan-item-kind-edit-inline-keyboard
       {:agreement-id agreement-id, :plan-item-id plan-item-id})})
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid plan item.",
      :message-key
      (str "docs-plan-item-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/plan-item-edit-label
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
    aid-raw
    (:agreement-id parsed)
    pid-raw
    (:plan-item-id parsed)
    agreement-id
    (when
     aid-raw
     (try (UUID/fromString (str aid-raw)) (catch Exception _ nil)))
    plan-item-id
    (when
     pid-raw
     (try (UUID/fromString (str pid-raw)) (catch Exception _ nil)))]
   (if
    (and
     chat-user
     session
     (:client-id session)
     agreement-id
     plan-item-id)
    (do
     (save-docs-session!
      chat-id
      (->
       session
       (assoc :stage :docs/plan-item-edit-label)
       (assoc
        :draft
        {:agreement/id agreement-id, :plan.item/id plan-item-id})))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Send new plan item label:",
       :reply-markup
       {:inline_keyboard
        [[(inline-button
           "Back"
           (str
            "docs:plan-item:open:"
            agreement-id
            ":"
            plan-item-id))]]}}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid plan item.",
      :message-key
      (str "docs-plan-item-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/plan-item-invoice-issue
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
    aid-raw
    (:agreement-id parsed)
    pid-raw
    (:plan-item-id parsed)
    agreement-id
    (when
     aid-raw
     (try (UUID/fromString (str aid-raw)) (catch Exception _ nil)))
    plan-item-id
    (when
     pid-raw
     (try (UUID/fromString (str pid-raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and
     chat-user
     session
     (:client-id session)
     agreement-id
     plan-item-id)
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/plan-item-invoice-issue,
        :actor actor,
        :input
        {:agreement/id agreement-id, :plan.item/id plan-item-id}})
      pdf
      (get-in res [:result :invoice-pdf :file])]
     (when
      pdf
      (docs-send-file! state chat-id actor pdf :caption "Invoice"))
     (if-let
      [err (:error res)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to issue invoice PDF: " (:message err)),
        :message-key
        (str
         "docs-plan-item-invoice-issue-error-"
         (System/currentTimeMillis))})
      (send-message!
       cfg
       {:chat-id chat-id,
        :text "Invoice PDF issued.",
        :message-key
        (str
         "docs-plan-item-invoice-issue-ok-"
         (System/currentTimeMillis))})))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid plan item.",
      :message-key
      (str "docs-plan-item-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/plan-item-invoice-on
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
    on
    (when raw (keyword raw))]
   (if
    (and chat-user session (contains? #{:accepted :due :manual} on))
    (do
     (save-docs-session!
      chat-id
      (->
       session
       (assoc :stage :docs/plan-item-label)
       (assoc-in [:draft :plan.item/invoice-on] on)))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "Send plan item label:",
       :reply-markup
       {:inline_keyboard
        [[(inline-button "Cancel" "docs:agreements:menu")]]}}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Pick a valid invoice option.",
      :message-key
      (str
       "docs-plan-item-on-invalid-"
       (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/plan-item-invoice-paid
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
    aid-raw
    (:agreement-id parsed)
    pid-raw
    (:plan-item-id parsed)
    agreement-id
    (when
     aid-raw
     (try (UUID/fromString (str aid-raw)) (catch Exception _ nil)))
    plan-item-id
    (when
     pid-raw
     (try (UUID/fromString (str pid-raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and
     chat-user
     session
     (:client-id session)
     agreement-id
     plan-item-id)
    (let
     [ensure
      (actions/execute!
       state
       {:action/id :cap/action/plan-item-invoice-ensure,
        :actor actor,
        :input
        {:agreement/id agreement-id, :plan.item/id plan-item-id}})
      invoice-id
      (get-in ensure [:result :invoice :invoice/id])]
     (if-let
      [err (:error ensure)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to prepare payment: " (:message err)),
        :message-key
        (str
         "docs-plan-item-paid-ensure-error-"
         (System/currentTimeMillis))})
      (do
       (save-docs-session!
        chat-id
        (assoc
         session
         :stage
         :docs/payment-amount
         :draft
         {:client/id (:client-id session),
          :invoice/id invoice-id,
          :agreement/id agreement-id,
          :plan.item/id plan-item-id}))
       (edit-message!
        cfg
        {:chat-id chat-id,
         :message-id message-id,
         :text "Send payment amount (this will issue a receipt):",
         :reply-markup
         {:inline_keyboard
          [[(inline-button
             "Back"
             (str
              "docs:plan-item:open:"
              agreement-id
              ":"
              plan-item-id))]]}}))))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid plan item.",
      :message-key
      (str "docs-plan-item-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/plan-item-kind
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
    kind
    (when raw (keyword raw))]
   (if
    (and
     chat-user
     session
     (contains? #{:milestone :recurring :installment} kind))
    (do
     (save-docs-session!
      chat-id
      (->
       session
       (assoc :stage :docs/plan-item-invoice-on)
       (assoc-in [:draft :plan.item/kind] kind)))
     (edit-message!
      cfg
      {:chat-id chat-id,
       :message-id message-id,
       :text "When should invoices be generated?",
       :reply-markup (docs-plan-item-invoice-on-inline-keyboard)}))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Pick a valid kind.",
      :message-key
      (str
       "docs-plan-item-kind-invalid-"
       (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/plan-item-move
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
    aid-raw
    (:agreement-id parsed)
    pid-raw
    (:plan-item-id parsed)
    agreement-id
    (when
     aid-raw
     (try (UUID/fromString (str aid-raw)) (catch Exception _ nil)))
    plan-item-id
    (when
     pid-raw
     (try (UUID/fromString (str pid-raw)) (catch Exception _ nil)))
    dir
    (:dir parsed)
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and
     chat-user
     session
     (:client-id session)
     agreement-id
     plan-item-id
     (contains? #{:down :up} dir))
    (let
     [list-res
      (actions/execute!
       state
       {:action/id :cap/action/plan-item-list,
        :actor actor,
        :input #:agreement{:id agreement-id}})
      items
      (get-in list-res [:result :plan-items] [])
      active-items
      (->>
       items
       (filter (fn [it] (not= false (:plan.item/active? it))))
       vec)
      ids
      (mapv :plan.item/id active-items)
      pos
      (.indexOf ids plan-item-id)
      target-pos
      (cond
       (neg? pos)
       nil
       (= dir :up)
       (when (pos? pos) (dec pos))
       (= dir :down)
       (when (< pos (dec (count ids))) (inc pos))
       :else
       nil)]
     (if-not
      (number? target-pos)
      (send-message!
       cfg
       {:chat-id chat-id,
        :text "Cannot move further.",
        :message-key
        (str "docs-plan-item-move-edge-" (System/currentTimeMillis))})
      (let
       [a
        (nth active-items pos)
        b
        (nth active-items target-pos)
        idx-a
        (long (or (:plan.item/index a) (inc pos)))
        idx-b
        (long (or (:plan.item/index b) (inc target-pos)))
        u1
        (actions/execute!
         state
         {:action/id :cap/action/plan-item-update,
          :actor actor,
          :input #:plan.item{:id (:plan.item/id a), :index idx-b}})
        u2
        (actions/execute!
         state
         {:action/id :cap/action/plan-item-update,
          :actor actor,
          :input #:plan.item{:id (:plan.item/id b), :index idx-a}})
        refreshed
        (actions/execute!
         state
         {:action/id :cap/action/plan-item-list,
          :actor actor,
          :input #:agreement{:id agreement-id}})
        new-items
        (get-in refreshed [:result :plan-items] [])]
       (if-let
        [err (or (:error u1) (:error u2))]
        (send-message!
         cfg
         {:chat-id chat-id,
          :text (str "Unable to move plan item: " (:message err)),
          :message-key
          (str
           "docs-plan-item-move-error-"
           (System/currentTimeMillis))})
        (edit-message!
         cfg
         {:chat-id chat-id,
          :message-id message-id,
          :text "Plan items: tap an action under the plan item.",
          :reply-markup
          (docs-plan-items-inline-keyboard
           agreement-id
           new-items)})))))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid plan item.",
      :message-key
      (str "docs-plan-item-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/plan-item-open
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
    aid-raw
    (:agreement-id parsed)
    pid-raw
    (:plan-item-id parsed)
    agreement-id
    (when
     aid-raw
     (try (UUID/fromString (str aid-raw)) (catch Exception _ nil)))
    plan-item-id
    (when
     pid-raw
     (try (UUID/fromString (str pid-raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and
     chat-user
     session
     (:client-id session)
     agreement-id
     plan-item-id)
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/plan-item-list,
        :actor actor,
        :input #:agreement{:id agreement-id}})
      items
      (get-in res [:result :plan-items] [])
      it
      (some (fn [x] (when (= plan-item-id (:plan.item/id x)) x)) items)
      active?
      (get it :plan.item/active? true)
      cur
      (or (:plan.item/currency it) "SAR")
      desc
      (str
       "Plan item:\n"
       "Label: "
       (or (:plan.item/label it) "—")
       "\n"
       "Amount: "
       (fmt-money (:plan.item/amount it) cur)
       "\n"
       "Due: "
       (or (:plan.item/due-at it) "—")
       "\n"
       "Invoice on: "
       (or (some-> (:plan.item/invoice-on it) name) "—")
       "\n"
       "Kind: "
       (or (some-> (:plan.item/kind it) name) "—")
       "\n"
       "Active: "
       (if (not= false active?) "yes" "no"))]
     (if-not
      it
      (send-message!
       cfg
       {:chat-id chat-id,
        :text "Plan item not found.",
        :message-key
        (str "docs-plan-item-missing-" (System/currentTimeMillis))})
      (do
       (save-docs-session!
        chat-id
        (->
         session
         (assoc
          :stage
          :docs/agreement-actions
          :agreement/id
          agreement-id)
         (assoc
          :draft
          {:agreement/id agreement-id, :plan.item/id plan-item-id})))
       (edit-message!
        cfg
        {:chat-id chat-id,
         :message-id message-id,
         :text desc,
         :reply-markup
         (docs-plan-item-actions-inline-keyboard
          {:agreement-id agreement-id,
           :plan-item-id plan-item-id,
           :active? active?})}))))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid plan item.",
      :message-key
      (str "docs-plan-item-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/plan-item-payment
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
    aid-raw
    (:agreement-id parsed)
    pid-raw
    (:plan-item-id parsed)
    agreement-id
    (when
     aid-raw
     (try (UUID/fromString (str aid-raw)) (catch Exception _ nil)))
    plan-item-id
    (when
     pid-raw
     (try (UUID/fromString (str pid-raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and
     chat-user
     session
     (:client-id session)
     agreement-id
     plan-item-id)
    (let
     [ensure
      (actions/execute!
       state
       {:action/id :cap/action/plan-item-invoice-ensure,
        :actor actor,
        :input
        {:agreement/id agreement-id, :plan.item/id plan-item-id}})
      invoice-id
      (get-in ensure [:result :invoice :invoice/id])]
     (if-let
      [err (:error ensure)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to prepare payment: " (:message err)),
        :message-key
        (str
         "docs-plan-item-pay-ensure-error-"
         (System/currentTimeMillis))})
      (do
       (save-docs-session!
        chat-id
        (assoc
         session
         :stage
         :docs/payment-amount
         :draft
         {:client/id (:client-id session),
          :invoice/id invoice-id,
          :agreement/id agreement-id,
          :plan.item/id plan-item-id}))
       (edit-message!
        cfg
        {:chat-id chat-id,
         :message-id message-id,
         :text "Send payment amount:",
         :reply-markup
         {:inline_keyboard
          [[(inline-button
             "Back"
             (str
              "docs:plan-item:open:"
              agreement-id
              ":"
              plan-item-id))]]}}))))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid plan item.",
      :message-key
      (str "docs-plan-item-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/plan-item-set-invoice-on
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
    on
    (when raw (keyword raw))
    aid-raw
    (:agreement-id parsed)
    pid-raw
    (:plan-item-id parsed)
    agreement-id
    (when
     aid-raw
     (try (UUID/fromString (str aid-raw)) (catch Exception _ nil)))
    plan-item-id
    (when
     pid-raw
     (try (UUID/fromString (str pid-raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and
     chat-user
     session
     (:client-id session)
     agreement-id
     plan-item-id
     (contains? #{:accepted :due :manual} on))
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/plan-item-update,
        :actor actor,
        :input #:plan.item{:id plan-item-id, :invoice-on on}})]
     (if-let
      [err (:error res)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to update plan item: " (:message err)),
        :message-key
        (str
         "docs-plan-item-invoice-on-error-"
         (System/currentTimeMillis))})
      (edit-message!
       cfg
       {:chat-id chat-id,
        :message-id message-id,
        :text "Updated.",
        :reply-markup
        (docs-plan-item-actions-inline-keyboard
         {:agreement-id agreement-id,
          :plan-item-id plan-item-id,
          :active? true})})))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid plan item.",
      :message-key
      (str "docs-plan-item-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/plan-item-set-kind
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
    kind
    (when raw (keyword raw))
    aid-raw
    (:agreement-id parsed)
    pid-raw
    (:plan-item-id parsed)
    agreement-id
    (when
     aid-raw
     (try (UUID/fromString (str aid-raw)) (catch Exception _ nil)))
    plan-item-id
    (when
     pid-raw
     (try (UUID/fromString (str pid-raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and
     chat-user
     session
     (:client-id session)
     agreement-id
     plan-item-id
     (contains? #{:milestone :recurring :installment} kind))
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/plan-item-update,
        :actor actor,
        :input #:plan.item{:id plan-item-id, :kind kind}})]
     (if-let
      [err (:error res)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to update plan item: " (:message err)),
        :message-key
        (str "docs-plan-item-kind-error-" (System/currentTimeMillis))})
      (edit-message!
       cfg
       {:chat-id chat-id,
        :message-id message-id,
        :text "Updated.",
        :reply-markup
        (docs-plan-item-actions-inline-keyboard
         {:agreement-id agreement-id,
          :plan-item-id plan-item-id,
          :active? true})})))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid plan item.",
      :message-key
      (str "docs-plan-item-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/plan-item-toggle
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
    aid-raw
    (:agreement-id parsed)
    pid-raw
    (:plan-item-id parsed)
    agreement-id
    (when
     aid-raw
     (try (UUID/fromString (str aid-raw)) (catch Exception _ nil)))
    plan-item-id
    (when
     pid-raw
     (try (UUID/fromString (str pid-raw)) (catch Exception _ nil)))
    actor
    (actions/actor-from-telegram chat-user)]
   (if
    (and
     chat-user
     session
     (:client-id session)
     agreement-id
     plan-item-id)
    (let
     [res
      (actions/execute!
       state
       {:action/id :cap/action/plan-item-list,
        :actor actor,
        :input #:agreement{:id agreement-id}})
      items
      (get-in res [:result :plan-items] [])
      it
      (some (fn [x] (when (= plan-item-id (:plan.item/id x)) x)) items)
      active?
      (get it :plan.item/active? true)
      updated
      (actions/execute!
       state
       {:action/id :cap/action/plan-item-update,
        :actor actor,
        :input #:plan.item{:id plan-item-id, :active? (not active?)}})]
     (if-let
      [err (:error updated)]
      (send-message!
       cfg
       {:chat-id chat-id,
        :text (str "Unable to update plan item: " (:message err)),
        :message-key
        (str
         "docs-plan-item-toggle-error-"
         (System/currentTimeMillis))})
      (edit-message!
       cfg
       {:chat-id chat-id,
        :message-id message-id,
        :text "Updated.",
        :reply-markup
        (docs-plan-item-actions-inline-keyboard
         {:agreement-id agreement-id,
          :plan-item-id plan-item-id,
          :active? (not active?)})})))
    (send-message!
     cfg
     {:chat-id chat-id,
      :text "Invalid plan item.",
      :message-key
      (str "docs-plan-item-invalid-" (System/currentTimeMillis))})))))

(defmethod
 handle-callback-dispatch
 :docs/skip
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
    session
    (save-docs-session! chat-id (assoc session :stage :docs/menu))
    (edit-message!
     cfg
     {:chat-id chat-id,
      :message-id message-id,
      :text "Skipped.",
      :reply-markup (docs-menu-inline-keyboard)})))))
