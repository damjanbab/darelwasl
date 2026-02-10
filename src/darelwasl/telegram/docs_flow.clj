(in-ns 'darelwasl.telegram)

(comment "Docs prompts + freeform message handler.")

(defn- prompt-docs-client-pick!
  [state chat-id]
  (let [cfg (get-in state [:config :telegram])
        conn (ensure-conn state)
        workspace nil
        res (clients/list-clients conn {:limit 6} workspace)
        client-list (or (:clients res) [])
        prompt (if (seq client-list)
                 "Pick a client:"
                 "No clients available yet.")
        keyboard (docs-client-pick-inline-keyboard client-list)]
    (send-message! cfg {:chat-id chat-id
                        :text prompt
                        :message-key (str "docs-client-pick-" (System/currentTimeMillis))
                        :reply-markup keyboard})))

(defn- handle-docs-message
  [state chat-user chat-id text]
  (let [cfg (get-in state [:config :telegram])
        session (get-docs-session! chat-id)
        trimmed (some-> text str/trim)]
    (when session
      (let [stage (:stage session)
            client-id (:client-id session)
            actor (actions/actor-from-telegram chat-user)]
        (case stage
          :docs/field-company-name
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send company name, or tap Skip."
                                :message-key (str "docs-company-empty-" (System/currentTimeMillis))
                                :reply-markup (docs-skip-cancel-inline-keyboard)})
            (let [res (actions/execute! state {:action/id :cap/action/doc-pack-upsert
                                               :actor actor
                                               :input {:client/id client-id
                                                       :doc.pack/company-name trimmed}})]
              (save-docs-session! chat-id (assoc session :stage :docs/menu))
              (if-let [err (:error res)]
                (send-message! cfg {:chat-id chat-id
                                    :text (str "Unable to save: " (:message err))
                                    :message-key (str "docs-company-error-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)})
                (send-message! cfg {:chat-id chat-id
                                    :text "Saved."
                                    :message-key (str "docs-company-ok-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)}))))

          :docs/field-services
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send services included text, or tap Skip."
                                :message-key (str "docs-services-empty-" (System/currentTimeMillis))
                                :reply-markup (docs-skip-cancel-inline-keyboard)})
            (let [res (actions/execute! state {:action/id :cap/action/doc-pack-upsert
                                               :actor actor
                                               :input {:client/id client-id
                                                       :doc.pack/services-included trimmed}})]
              (save-docs-session! chat-id (assoc session :stage :docs/menu))
              (if-let [err (:error res)]
                (send-message! cfg {:chat-id chat-id
                                    :text (str "Unable to save: " (:message err))
                                    :message-key (str "docs-services-error-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)})
                (send-message! cfg {:chat-id chat-id
                                    :text "Saved."
                                    :message-key (str "docs-services-ok-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)}))))

          :docs/field-payment-plan
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send payment plan text, or tap Skip."
                                :message-key (str "docs-plan-empty-" (System/currentTimeMillis))
                                :reply-markup (docs-skip-cancel-inline-keyboard)})
            (let [res (actions/execute! state {:action/id :cap/action/doc-pack-upsert
                                               :actor actor
                                               :input {:client/id client-id
                                                       :doc.pack/payment-plan trimmed}})]
              (save-docs-session! chat-id (assoc session :stage :docs/menu))
              (if-let [err (:error res)]
                (send-message! cfg {:chat-id chat-id
                                    :text (str "Unable to save: " (:message err))
                                    :message-key (str "docs-plan-error-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)})
                (send-message! cfg {:chat-id chat-id
                                    :text "Saved."
                                    :message-key (str "docs-plan-ok-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)}))))

          :docs/field-status-notes
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send status notes text, or tap Skip."
                                :message-key (str "docs-status-empty-" (System/currentTimeMillis))
                                :reply-markup (docs-skip-cancel-inline-keyboard)})
            (let [res (actions/execute! state {:action/id :cap/action/doc-pack-upsert
                                               :actor actor
                                               :input {:client/id client-id
                                                       :doc.pack/status-notes trimmed}})]
              (save-docs-session! chat-id (assoc session :stage :docs/menu))
              (if-let [err (:error res)]
                (send-message! cfg {:chat-id chat-id
                                    :text (str "Unable to save: " (:message err))
                                    :message-key (str "docs-status-error-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)})
                (send-message! cfg {:chat-id chat-id
                                    :text "Saved."
                                    :message-key (str "docs-status-ok-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)}))))

          :docs/field-currency-other
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send currency code (e.g. SAR), or tap Skip."
                                :message-key (str "docs-currency-empty-" (System/currentTimeMillis))
                                :reply-markup (docs-skip-cancel-inline-keyboard)})
            (let [res (actions/execute! state {:action/id :cap/action/doc-pack-upsert
                                               :actor actor
                                               :input {:client/id client-id
                                                       :doc.pack/currency trimmed}})]
              (save-docs-session! chat-id (assoc session :stage :docs/menu))
              (if-let [err (:error res)]
                (send-message! cfg {:chat-id chat-id
                                    :text (str "Unable to save: " (:message err))
                                    :message-key (str "docs-currency-error-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)})
                (send-message! cfg {:chat-id chat-id
                                    :text "Saved."
                                    :message-key (str "docs-currency-ok-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)}))))

          :docs/inv-number
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send invoice number."
                                :message-key (str "docs-inv-num-empty-" (System/currentTimeMillis))})
            (do
              (save-docs-session! chat-id (-> session
                                              (assoc :stage :docs/inv-total)
                                              (assoc :draft {:invoice/number trimmed})))
              (send-message! cfg {:chat-id chat-id
                                  :text "Send invoice total amount:"
                                  :message-key (str "docs-inv-total-" (System/currentTimeMillis))
                                  :reply-markup {:inline_keyboard [[(inline-button "Cancel" "docs:menu")]]}})))

          :docs/inv-total
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send invoice total amount."
                                :message-key (str "docs-inv-total-empty-" (System/currentTimeMillis))})
            (do
              (save-docs-session! chat-id (-> session
                                              (assoc :stage :docs/inv-status)
                                              (assoc-in [:draft :invoice/total-amount] trimmed)))
              (send-message! cfg {:chat-id chat-id
                                  :text "Choose invoice status:"
                                  :message-key (str "docs-inv-status-" (System/currentTimeMillis))
                                  :reply-markup (docs-invoice-status-inline-keyboard)})))

          :docs/payment-amount
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send the payment amount."
                                :message-key (str "docs-payment-amount-empty-" (System/currentTimeMillis))})
            (do
              (save-docs-session! chat-id (-> session
                                              (assoc :stage :docs/payment-method)
                                              (assoc-in [:draft :payment/amount] trimmed)))
              (send-message! cfg {:chat-id chat-id
                                  :text "Choose payment method:"
                                  :message-key (str "docs-payment-method-" (System/currentTimeMillis))
                                  :reply-markup (docs-payment-method-inline-keyboard)})))

          :docs/payment-reference
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send the payment reference, or tap Skip."
                                :message-key (str "docs-payment-ref-empty-" (System/currentTimeMillis))
                                :reply-markup (docs-payment-reference-inline-keyboard)})
            (let [draft (:draft session)
                  paid-at (:payment/paid-at draft)]
              (if-not paid-at
                (send-message! cfg {:chat-id chat-id
                                    :text "Pick payment date first (buttons)."
                                    :message-key (str "docs-payment-date-missing-" (System/currentTimeMillis))})
                (do
                  (save-docs-session! chat-id (-> session
                                                  (assoc :stage :docs/payment-note)
                                                  (assoc-in [:draft :payment/reference] (when (present-string? trimmed) trimmed))))
                  (send-message! cfg {:chat-id chat-id
                                      :text "Payment note (optional). You can include who sent/received the money, bank details, anything:"
                                      :message-key (str "docs-payment-note-" (System/currentTimeMillis))
                                      :reply-markup (docs-payment-note-inline-keyboard)})))))

          :docs/payment-note
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send the payment note, or tap Skip."
                                :message-key (str "docs-payment-note-empty-" (System/currentTimeMillis))
                                :reply-markup (docs-payment-note-inline-keyboard)})
            (let [draft (:draft session)
                  paid-at (:payment/paid-at draft)]
              (if-not paid-at
                (send-message! cfg {:chat-id chat-id
                                    :text "Pick payment date first (buttons)."
                                    :message-key (str "docs-payment-date-missing-" (System/currentTimeMillis))})
                (let [input (cond-> {:client/id client-id
                                     :payment/amount (:payment/amount draft)
                                     :payment/method (:payment/method draft)
                                     :payment/paid-at paid-at}
                              (:invoice/id draft) (assoc :invoice/id (:invoice/id draft))
                              (:agreement/id draft) (assoc :agreement/id (:agreement/id draft))
                              (:plan.item/id draft) (assoc :plan.item/id (:plan.item/id draft))
                              (present-string? (:payment/reference draft)) (assoc :payment/reference (:payment/reference draft))
                              (present-string? trimmed) (assoc :payment/note trimmed))
                      res (actions/execute! state {:action/id :cap/action/payment-create
                                                   :actor actor
                                                   :input input})]
                  (save-docs-session! chat-id (assoc session :stage :docs/menu :draft nil))
                  (if-let [err (:error res)]
                    (send-message! cfg {:chat-id chat-id
                                        :text (str "Unable to add payment: " (:message err))
                                        :message-key (str "docs-payment-create-error-" (System/currentTimeMillis))
                                        :reply-markup (docs-menu-inline-keyboard)})
                    (let [receipt (get-in res [:result :receipt :file])
                          invoice (get-in res [:result :invoice-pdf :file])]
                      (when receipt
                        (docs-send-file! state chat-id actor receipt :caption "Receipt"))
                      (when invoice
                        (docs-send-file! state chat-id actor invoice :caption "Invoice"))
                      (send-message! cfg {:chat-id chat-id
                                          :text "Payment added."
                                          :message-key (str "docs-payment-added-" (System/currentTimeMillis))
                                          :reply-markup (docs-menu-inline-keyboard)})))))))

          :docs/agreement-accept-by
          (let [draft (:draft session)
                agreement-id (:agreement/id draft)
                accepted-at (:agreement/accepted-at draft)
                accepted-by (when (present-string? trimmed) trimmed)]
            (if-not (and agreement-id accepted-at)
              (send-message! cfg {:chat-id chat-id
                                  :text "Pick acceptance date/time first."
                                  :message-key (str "docs-agreement-accept-missing-" (System/currentTimeMillis))})
              (let [next-draft (cond-> draft
                                 accepted-by (assoc :agreement/accepted-by accepted-by))
                    selected (set (or (:agreement/delivery-channels next-draft) #{}))]
                (save-docs-session! chat-id (-> session
                                                (assoc :stage :docs/agreement-accept-channels)
                                                (assoc :draft (assoc next-draft :agreement/delivery-channels selected))))
                (send-message! cfg {:chat-id chat-id
                                    :text "Delivery channels (select all that apply):"
                                    :message-key (str "docs-agreement-accept-channels-" (System/currentTimeMillis))
                                    :reply-markup (docs-agreement-accept-channels-inline-keyboard {:agreement-id agreement-id
                                                                                                   :selected selected})}))))

          :docs/agreement-title
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send agreement title."
                                :message-key (str "docs-agreement-title-empty-" (System/currentTimeMillis))
                                :reply-markup {:inline_keyboard [[(inline-button "Cancel" "docs:agreements:menu")]]}})
            (do
              (save-docs-session! chat-id (-> session
                                              (assoc :stage :docs/agreement-terms-source)
                                              (assoc :draft {:agreement/title trimmed})))
              (send-message! cfg {:chat-id chat-id
                                  :text "Choose agreement terms:"
                                  :message-key (str "docs-agreement-terms-source-" (System/currentTimeMillis))
                                  :reply-markup (docs-agreement-terms-source-inline-keyboard)})))

          :docs/agreement-terms-source
          (send-message! cfg {:chat-id chat-id
                              :text "Use the buttons below to choose agreement terms."
                              :message-key (str "docs-agreement-terms-source-click-" (System/currentTimeMillis))
                              :reply-markup (docs-agreement-terms-source-inline-keyboard)})

          :docs/agreement-terms-custom
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send agreement terms."
                                :message-key (str "docs-agreement-terms-custom-empty-" (System/currentTimeMillis))
                                :reply-markup {:inline_keyboard [[(inline-button "Cancel" "docs:agreements:menu")]]}})
            (do
              (save-docs-session! chat-id (-> session
                                              (assoc :stage :docs/agreement-client-company)
                                              (assoc-in [:draft :agreement/terms] trimmed)))
              (send-message! cfg {:chat-id chat-id
                                  :text "Client company/legal name (optional). Send it, or tap Skip."
                                  :message-key (str "docs-agreement-client-company-" (System/currentTimeMillis))
                                  :reply-markup (docs-agreement-party-inline-keyboard :client-company)})))

          :docs/agreement-client-company
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send client company name, or tap Skip."
                                :message-key (str "docs-agreement-client-company-empty-" (System/currentTimeMillis))
                                :reply-markup (docs-agreement-party-inline-keyboard :client-company)})
            (do
              (save-docs-session! chat-id (-> session
                                              (assoc :stage :docs/agreement-client-representative)
                                              (assoc-in [:draft :agreement/client-company] trimmed)))
              (send-message! cfg {:chat-id chat-id
                                  :text "Client representative/signatory (optional). Send it, or tap Skip."
                                  :message-key (str "docs-agreement-client-rep-" (System/currentTimeMillis))
                                  :reply-markup (docs-agreement-party-inline-keyboard :client-representative)})))

          :docs/agreement-client-representative
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send client representative, or tap Skip."
                                :message-key (str "docs-agreement-client-rep-empty-" (System/currentTimeMillis))
                                :reply-markup (docs-agreement-party-inline-keyboard :client-representative)})
            (do
              (save-docs-session! chat-id (-> session
                                              (assoc :stage :docs/agreement-our-representative)
                                              (assoc-in [:draft :agreement/client-representative] trimmed)))
              (send-message! cfg {:chat-id chat-id
                                  :text "Our representative (optional). Send it, or tap Skip."
                                  :message-key (str "docs-agreement-our-rep-" (System/currentTimeMillis))
                                  :reply-markup (docs-agreement-party-inline-keyboard :our-representative)})))

          :docs/agreement-our-representative
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send our representative, or tap Skip."
                                :message-key (str "docs-agreement-our-rep-empty-" (System/currentTimeMillis))
                                :reply-markup (docs-agreement-party-inline-keyboard :our-representative)})
            (do
              (save-docs-session! chat-id (-> session
                                              (assoc :stage :docs/agreement-our-recipient)
                                              (assoc-in [:draft :agreement/our-representative] trimmed)))
              (send-message! cfg {:chat-id chat-id
                                  :text "Who receives funds (optional). Send it, or tap Skip."
                                  :message-key (str "docs-agreement-recipient-" (System/currentTimeMillis))
                                  :reply-markup (docs-agreement-party-inline-keyboard :our-recipient)})))

          :docs/agreement-our-recipient
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send recipient, or tap Skip."
                                :message-key (str "docs-agreement-recipient-empty-" (System/currentTimeMillis))
                                :reply-markup (docs-agreement-party-inline-keyboard :our-recipient)})
            (let [month (LocalDate/now (ZoneId/systemDefault))
                  quicks [{:id :today :label "Today"}
                          {:id :tomorrow :label "Tomorrow"}]]
              (save-docs-session! chat-id (-> session
                                              (assoc :stage :docs/agreement-effective-date)
                                              (assoc :picker {:kind :docs/agreement-effective
                                                              :text "Pick agreement effective date (optional):"
                                                              :quicks quicks})
                                              (assoc-in [:draft :agreement/our-recipient] trimmed)))
              (send-message! cfg {:chat-id chat-id
                                  :text "Pick agreement effective date (optional):"
                                  :message-key (str "docs-agreement-effective-" (System/currentTimeMillis))
                                  :reply-markup (date-picker-inline-keyboard {:month month
                                                                              :quicks quicks
                                                                              :allow-skip? true
                                                                              :skip-label "Skip (no effective date)"
                                                                              :extra-rows [[(inline-button "Cancel" "docs:agreements:menu")]]})})))

          :docs/plan-item-label
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send plan item label."
                                :message-key (str "docs-plan-item-label-empty-" (System/currentTimeMillis))
                                :reply-markup {:inline_keyboard [[(inline-button "Cancel" "docs:agreements:menu")]]}})
            (do
              (save-docs-session! chat-id (-> session
                                              (assoc :stage :docs/plan-item-amount)
                                              (assoc-in [:draft :plan.item/label] trimmed)))
              (send-message! cfg {:chat-id chat-id
                                  :text "Send plan item amount (e.g. 5000 or 5,000 SAR):"
                                  :message-key (str "docs-plan-item-amount-" (System/currentTimeMillis))
                                  :reply-markup {:inline_keyboard [[(inline-button "Cancel" "docs:agreements:menu")]]}})))

          :docs/plan-item-amount
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send plan item amount."
                                :message-key (str "docs-plan-item-amount-empty-" (System/currentTimeMillis))
                                :reply-markup {:inline_keyboard [[(inline-button "Cancel" "docs:agreements:menu")]]}})
            (let [month (LocalDate/now (ZoneId/systemDefault))
                  quicks [{:id :in-7-days :label "+7 days"}
                          {:id :in-14-days :label "+14 days"}
                          {:id :in-30-days :label "+30 days"}]]
              (save-docs-session! chat-id (-> session
                                              (assoc :stage :docs/plan-item-due-date)
                                              (assoc :picker {:kind :docs/plan-item-due
                                                              :text "Pick plan item due date:"
                                                              :quicks quicks})
                                              (assoc-in [:draft :plan.item/amount] trimmed)))
              (send-message! cfg {:chat-id chat-id
                                  :text "Pick plan item due date:"
                                  :message-key (str "docs-plan-item-due-" (System/currentTimeMillis))
                                  :reply-markup (date-picker-inline-keyboard {:month month
                                                                              :quicks quicks
                                                                              :extra-rows [[(inline-button "Cancel" "docs:agreements:menu")]]})})))

          :docs/plan-item-edit-label
          (let [draft (:draft session)
                agreement-id (:agreement/id draft)
                plan-item-id (:plan.item/id draft)]
            (if (str/blank? trimmed)
              (send-message! cfg {:chat-id chat-id
                                  :text "Send a non-empty label."
                                  :message-key (str "docs-plan-item-edit-label-empty-" (System/currentTimeMillis))})
              (let [res (actions/execute! state {:action/id :cap/action/plan-item-update
                                                 :actor actor
                                                 :input {:plan.item/id plan-item-id
                                                         :plan.item/label trimmed}})]
                (save-docs-session! chat-id (assoc session :stage :docs/agreement-actions))
                (if-let [err (:error res)]
                  (send-message! cfg {:chat-id chat-id
                                      :text (str "Unable to update label: " (:message err))
                                      :message-key (str "docs-plan-item-edit-label-error-" (System/currentTimeMillis))})
                  (send-message! cfg {:chat-id chat-id
                                      :text "Updated."
                                      :message-key (str "docs-plan-item-edit-label-ok-" (System/currentTimeMillis))
                                      :reply-markup (docs-plan-item-actions-inline-keyboard {:agreement-id agreement-id
                                                                                             :plan-item-id plan-item-id
                                                                                             :active? true})})))))

          :docs/plan-item-edit-amount
          (let [draft (:draft session)
                agreement-id (:agreement/id draft)
                plan-item-id (:plan.item/id draft)]
            (if (str/blank? trimmed)
              (send-message! cfg {:chat-id chat-id
                                  :text "Send a non-empty amount."
                                  :message-key (str "docs-plan-item-edit-amount-empty-" (System/currentTimeMillis))})
              (let [res (actions/execute! state {:action/id :cap/action/plan-item-update
                                                 :actor actor
                                                 :input {:plan.item/id plan-item-id
                                                         :plan.item/amount trimmed}})]
                (save-docs-session! chat-id (assoc session :stage :docs/agreement-actions))
                (if-let [err (:error res)]
                  (send-message! cfg {:chat-id chat-id
                                      :text (str "Unable to update amount: " (:message err))
                                      :message-key (str "docs-plan-item-edit-amount-error-" (System/currentTimeMillis))})
                  (send-message! cfg {:chat-id chat-id
                                      :text "Updated."
                                      :message-key (str "docs-plan-item-edit-amount-ok-" (System/currentTimeMillis))
                                      :reply-markup (docs-plan-item-actions-inline-keyboard {:agreement-id agreement-id
                                                                                             :plan-item-id plan-item-id
                                                                                             :active? true})})))))

          :docs/payment-time
          (send-message! cfg {:chat-id chat-id
                              :text "Pick payment time using the buttons (optional)."
                              :message-key (str "docs-payment-time-click-" (System/currentTimeMillis))
                              :reply-markup (time-picker-inline-keyboard {:allow-skip? true
                                                                          :skip-label "Skip time"
                                                                          :extra-rows [[(inline-button "Cancel" "docs:menu")]]})})

          :docs/plan-item-due-time
          (send-message! cfg {:chat-id chat-id
                              :text "Pick plan item due time using the buttons (optional)."
                              :message-key (str "docs-plan-item-due-time-click-" (System/currentTimeMillis))
                              :reply-markup (time-picker-inline-keyboard {:allow-skip? true
                                                                          :skip-label "Skip time"
                                                                          :extra-rows [[(inline-button "Cancel" "docs:agreements:menu")]]})})

          :docs/payment-date
          (let [month (LocalDate/now (ZoneId/systemDefault))
                quicks (or (get-in session [:picker :quicks])
                           [{:id :today :label "Today"}
                            {:id :yesterday :label "Yesterday"}])]
            (send-message! cfg {:chat-id chat-id
                                :text "Use the calendar buttons to choose a payment date."
                                :message-key (str "docs-payment-date-click-" (System/currentTimeMillis))
                                :reply-markup (date-picker-inline-keyboard {:month month
                                                                            :quicks quicks
                                                                            :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))

          :docs/inv-due-date
          (let [month (LocalDate/now (ZoneId/systemDefault))
                quicks (or (get-in session [:picker :quicks])
                           [{:id :in-7-days :label "+7 days"}
                            {:id :in-14-days :label "+14 days"}
                            {:id :in-30-days :label "+30 days"}])]
            (send-message! cfg {:chat-id chat-id
                                :text "Use the calendar buttons to choose a due date."
                                :message-key (str "docs-inv-due-click-" (System/currentTimeMillis))
                                :reply-markup (date-picker-inline-keyboard {:month month
                                                                            :quicks quicks
                                                                            :allow-skip? true
                                                                            :skip-label "No due date"
                                                                            :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))

          :docs/inv-due-time
          (send-message! cfg {:chat-id chat-id
                              :text "Pick invoice due time using the buttons (optional)."
                              :message-key (str "docs-inv-time-click-" (System/currentTimeMillis))
                              :reply-markup (time-picker-inline-keyboard {:allow-skip? true
                                                                          :skip-label "Skip time"
                                                                          :extra-rows [[(inline-button "Cancel" "docs:menu")]]})})

          :docs/agreement-effective-date
          (send-message! cfg {:chat-id chat-id
                              :text "Use the calendar buttons to pick an effective date (optional)."
                              :message-key (str "docs-agreement-effective-click-" (System/currentTimeMillis))
                              :reply-markup (date-picker-inline-keyboard {:month (LocalDate/now (ZoneId/systemDefault))
                                                                          :quicks [{:id :today :label "Today"}
                                                                                   {:id :tomorrow :label "Tomorrow"}]
                                                                          :allow-skip? true
                                                                          :skip-label "Skip (no effective date)"
                                                                          :extra-rows [[(inline-button "Cancel" "docs:agreements:menu")]]})})

          :docs/agreement-accept-date
          (let [month (LocalDate/now (ZoneId/systemDefault))
                quicks (or (get-in session [:picker :quicks])
                           [{:id :today :label "Today"}
                            {:id :yesterday :label "Yesterday"}])
                agreement-id (get-in session [:draft :agreement/id])]
            (send-message! cfg {:chat-id chat-id
                                :text "Use the calendar buttons to pick an acceptance date."
                                :message-key (str "docs-agreement-accept-date-click-" (System/currentTimeMillis))
                                :reply-markup (date-picker-inline-keyboard {:month month
                                                                            :quicks quicks
                                                                            :extra-rows [[(inline-button "Back" (str "docs:agreements:set:" agreement-id))]]})}))

          :docs/agreement-accept-time
          (let [agreement-id (get-in session [:draft :agreement/id])]
            (send-message! cfg {:chat-id chat-id
                                :text "Pick acceptance time using the buttons (optional)."
                                :message-key (str "docs-agreement-accept-time-click-" (System/currentTimeMillis))
                                :reply-markup (time-picker-inline-keyboard {:allow-skip? true
                                                                            :skip-label "Skip time"
                                                                            :extra-rows [[(inline-button "Back" (str "docs:agreements:set:" agreement-id))]]})}))

          :docs/plan-item-due-date
          (send-message! cfg {:chat-id chat-id
                              :text "Use the calendar buttons to pick a due date."
                              :message-key (str "docs-plan-item-due-click-" (System/currentTimeMillis))
                              :reply-markup (date-picker-inline-keyboard {:month (LocalDate/now (ZoneId/systemDefault))
                                                                          :quicks [{:id :in-7-days :label "+7 days"}
                                                                                   {:id :in-14-days :label "+14 days"}
                                                                                   {:id :in-30-days :label "+30 days"}]
                                                                          :extra-rows [[(inline-button "Cancel" "docs:agreements:menu")]]})})

          :docs/agreement-actions
          (let [agreement-id (:agreement/id session)]
            (when agreement-id
              (send-message! cfg {:chat-id chat-id
                                  :text "Agreement:"
                                  :message-key (str "docs-agreement-actions-" (System/currentTimeMillis))
                                  :reply-markup (docs-agreement-actions-inline-keyboard agreement-id)})))

          :docs/payment-invoice-attach
          (send-message! cfg {:chat-id chat-id
                              :text "Use the buttons to choose invoice attachment."
                              :message-key (str "docs-payment-inv-attach-click-" (System/currentTimeMillis))
                              :reply-markup (docs-payment-invoice-attach-inline-keyboard)})

          :docs/payment-method
          (send-message! cfg {:chat-id chat-id
                              :text "Use the buttons to choose a payment method."
                              :message-key (str "docs-payment-method-click-" (System/currentTimeMillis))
                              :reply-markup (docs-payment-method-inline-keyboard)})

          :docs/inv-status
          (send-message! cfg {:chat-id chat-id
                              :text "Use the buttons to choose invoice status."
                              :message-key (str "docs-inv-status-click-" (System/currentTimeMillis))
                              :reply-markup (docs-invoice-status-inline-keyboard)})

          :docs/menu
          (send-message! cfg {:chat-id chat-id
                              :text "Use the buttons."
                              :message-key (str "docs-menu-click-" (System/currentTimeMillis))
                              :reply-markup (docs-menu-inline-keyboard)})

          (send-message! cfg {:chat-id chat-id
                              :text "Use /docs to start."
                              :message-key (str "docs-unknown-" (System/currentTimeMillis))
                              :reply-markup (docs-menu-inline-keyboard)}))))))
