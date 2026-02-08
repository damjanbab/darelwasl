(in-ns 'darelwasl.telegram)

(comment "Service case management via Telegram (/services).")

(defn- svc-inline-button
  [text data]
  {:text text
   :callback_data data})

(defn- svc-client-pick-keyboard
  [clients]
  {:inline_keyboard
   (vec
    (concat
     (->> clients
          (map (fn [{:client/keys [id name]}]
                 [(svc-inline-button (truncate-text (or name "Client") 28)
                                     (str "svc:client:set:" id))]))
          vec)
     [[(svc-inline-button "Cancel" "svc:cancel")]]))})

(defn- svc-menu-keyboard
  []
  {:inline_keyboard
   [[(svc-inline-button "Cases" "svc:cases")
     (svc-inline-button "Create case" "svc:create:menu")]
    [(svc-inline-button "Portal link" "svc:portal")
     (svc-inline-button "Change client" "svc:client:pick")]
    [(svc-inline-button "Close" "svc:cancel")]]})

(defn- svc-cases-keyboard
  [cases]
  {:inline_keyboard
   (vec
    (concat
     (->> cases
          (map (fn [c]
                 (let [cid (:service.case/id c)
                       title (or (:service.case/title c) "Case")
                       status (some-> (:service.case/lifecycle c) name (str/replace "s" "S") (str/replace "-" " "))
                       label (truncate-text (str title " · " (or status "")) 40)]
                   [(svc-inline-button label (str "svc:case:open:" cid))])))
          vec)
     [[(svc-inline-button "Back" "svc:menu")]]))})

(defn- svc-services-keyboard
  [services]
  {:inline_keyboard
   (vec
    (concat
     (->> services
          (map (fn [{:keys [id title]}]
                 [(svc-inline-button (truncate-text (or title (name id)) 40)
                                     (str "svc:case:create:" (name id)))]))
          vec)
     [[(svc-inline-button "Back" "svc:menu")]]))})

(defn- svc-step-list-keyboard
  [steps]
  {:inline_keyboard
   (vec
    (concat
     (->> steps
          (map (fn [s]
                 (let [sid (:service.case.step/step s)
                       status (:service.case.step/status s)
                       check (case status
                               :done "✅ "
                               :waiting-client "🟣 "
                               :submitted "📨 "
                               :under-review "🔎 "
                               :action-required "⚠️ "
                               :blocked "⛔ "
                               :rejected "❌ "
                               :skipped "➖ "
                               "▫️ ")
                       label (truncate-text (str check (or (:service.case.step/internal-label s)
                                                          (:service.case.step/public-label s)
                                                          (name sid)))
                                            44)]
                   [(svc-inline-button label (str "svc:step:open:" (name sid)))])))
          vec)
     [[(svc-inline-button "Back to cases" "svc:cases")
       (svc-inline-button "Menu" "svc:menu")]]))})

(defn- svc-step-actions-keyboard
  []
  {:inline_keyboard
   [[(svc-inline-button "Done" "svc:step:set:done")
     (svc-inline-button "Waiting on client" "svc:step:set:waiting-client")]
    [(svc-inline-button "Submitted" "svc:step:set:submitted")
     (svc-inline-button "Under review" "svc:step:set:under-review")]
    [(svc-inline-button "Action required" "svc:step:set:action-required")
     (svc-inline-button "Blocked" "svc:step:set:blocked")]
    [(svc-inline-button "Rejected" "svc:step:set:rejected")
     (svc-inline-button "Skipped" "svc:step:set:skipped")]
    [(svc-inline-button "Back" "svc:case:open")]]})

(defn handle-services-command
  [state chat-id chat-user]
  (let [conn (ensure-conn state)
        workspace nil
        res (clients/list-clients conn {:limit 8} workspace)
        items (or (:clients res) [])]
    (save-services-session! chat-id {:stage :svc/client-pick
                                     :user chat-user})
    {:text (if (seq items) "Pick a client:" "No clients available yet.")
     :reply-markup (svc-client-pick-keyboard items)}))

(defn handle-services-message
  [state chat-user chat-id text]
  (let [session (get-services-session! chat-id)]
    (if-not (and session (:awaiting-reason session))
      {:text "Use /services to manage cases."}
      (let [{:keys [case-id step-id status]} (:awaiting-reason session)
            reason (some-> text str str/trim)]
        (if (str/blank? reason)
          {:text "Send a short reason (non-empty), or press Back."
           :reply-markup {:inline_keyboard [[(svc-inline-button "Back" "svc:case:open")]]}}
          (let [action-res (actions/execute! state {:action/id :cap/action/service-case-step-set-status
                                                    :actor (actions/actor-from-telegram chat-user)
                                                    :input {:service.case/id case-id
                                                            :service.case.step/step step-id
                                                            :service.case.step/status status
                                                            :service.case.step/reason reason}})
                err (:error action-res)
                result (:result action-res)]
            (if err
              {:text (str "Failed: " (:message err))}
              (do
                (save-services-session! chat-id (-> session
                                                    (dissoc :awaiting-reason)
                                                    (assoc :stage :svc/case-open)))
                {:text "Updated."
                 :reply-markup (svc-step-list-keyboard (:steps result))}))))))))

