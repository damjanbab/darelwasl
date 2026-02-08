(in-ns 'darelwasl.telegram)

(comment "Callback handlers: service cases (/services UI).")

(defn- svc-session-expired!
  [cfg chat-id message-id]
  (edit-message!
   cfg
   {:chat-id chat-id
    :message-id message-id
    :text "Session expired. Use /services again."
    :reply-markup {:inline_keyboard []}}))

(defn- svc-client-name
  [db client-id workspace]
  (or (:client/name (clients/client-by-id db client-id workspace))
      "Client"))

(defn- svc-edit!
  [cfg chat-id message-id {:keys [text reply-markup]}]
  (edit-message!
   cfg
   {:chat-id chat-id
    :message-id message-id
    :text (or text "—")
    :reply-markup (or reply-markup {:inline_keyboard []})}))

(defn- svc-render-client-pick!
  [{:keys [state chat-id message-id cfg]}]
  (let [conn (ensure-conn state)
        workspace nil
        res (when conn (clients/list-clients conn {:limit 12} workspace))
        items (or (:clients res) [])]
    (save-services-session! chat-id {:stage :svc/client-pick})
    (svc-edit! cfg chat-id message-id {:text (if (seq items) "Pick a client:" "No clients available yet.")
                                       :reply-markup (svc-client-pick-keyboard items)})))

(defn- svc-render-menu!
  [{:keys [chat-id message-id cfg db chat-user]}]
  (let [session (get-services-session! chat-id)
        client-id (:client-id session)]
    (if-not (and session client-id)
      (svc-session-expired! cfg chat-id message-id)
      (let [name (svc-client-name db client-id nil)]
        (save-services-session! chat-id (assoc session :stage :svc/menu))
        (svc-edit! cfg chat-id message-id {:text (str "Services · " name)
                                           :reply-markup (svc-menu-keyboard)})))))

(defn- svc-render-cases!
  [{:keys [state chat-id message-id cfg db chat-user]}]
  (let [session (get-services-session! chat-id)
        client-id (:client-id session)
        actor (actions/actor-from-telegram (or (:user session) chat-user))]
    (cond
      (nil? session) (svc-session-expired! cfg chat-id message-id)
      (nil? client-id) (svc-session-expired! cfg chat-id message-id)
      :else
      (let [res (actions/execute! state {:action/id :cap/action/service-case-list
                                        :actor actor
                                        :input {:client/id client-id}})
            err (:error res)
            cases (get-in res [:result :cases])]
        (if err
          (svc-edit! cfg chat-id message-id {:text (str "Failed to load cases: " (:message err))
                                             :reply-markup (svc-menu-keyboard)})
          (do
            (save-services-session! chat-id (assoc session :stage :svc/cases))
            (svc-edit! cfg chat-id message-id {:text (if (seq cases) "Cases:" "No cases yet.")
                                               :reply-markup (svc-cases-keyboard cases)})))))))

(defn- svc-render-create-menu!
  [{:keys [state chat-id message-id cfg]}]
  (let [session (get-services-session! chat-id)
        client-id (:client-id session)]
    (cond
      (nil? session) (svc-session-expired! cfg chat-id message-id)
      (nil? client-id) (svc-session-expired! cfg chat-id message-id)
      :else
      (let [res (actions/execute! state {:action/id :cap/action/service-list
                                        :actor {:actor/type :system}})
            services (get-in res [:result :services])]
        (save-services-session! chat-id (assoc session :stage :svc/create))
        (svc-edit! cfg chat-id message-id {:text "Pick a service:"
                                           :reply-markup (svc-services-keyboard services)})))))

(defn- svc-render-case-open!
  [{:keys [state chat-id message-id cfg db chat-user]} case-id]
  (let [session (get-services-session! chat-id)
        client-id (:client-id session)
        actor (actions/actor-from-telegram (or (:user session) chat-user))]
    (cond
      (nil? session) (svc-session-expired! cfg chat-id message-id)
      (nil? client-id) (svc-session-expired! cfg chat-id message-id)
      (nil? case-id) (svc-edit! cfg chat-id message-id {:text "Missing case id."
                                                        :reply-markup (svc-menu-keyboard)})
      :else
      (let [res (actions/execute! state {:action/id :cap/action/service-case-read
                                        :actor actor
                                        :input {:service.case/id case-id}})
            err (:error res)
            result (:result res)
            c (:case result)
            steps (:steps result)]
        (if err
          (svc-edit! cfg chat-id message-id {:text (str "Failed to open case: " (:message err))
                                             :reply-markup (svc-menu-keyboard)})
          (do
            (save-services-session! chat-id (assoc session
                                                  :stage :svc/case-open
                                                  :case-id (:service.case/id c)
                                                  :step-id nil))
            (svc-edit! cfg chat-id message-id {:text (str (or (:service.case/title c) "Case")
                                                          "\n"
                                                          (some-> (:service.case/lifecycle c) name))
                                               :reply-markup (svc-step-list-keyboard steps)})))))))

(defn- svc-render-step-open!
  [{:keys [state chat-id message-id cfg db chat-user]} step-id-raw]
  (let [session (get-services-session! chat-id)
        case-id (:case-id session)
        step-id (when (present-string? step-id-raw)
                  (keyword "step" (str step-id-raw)))
        actor (actions/actor-from-telegram (or (:user session) chat-user))]
    (cond
      (nil? session) (svc-session-expired! cfg chat-id message-id)
      (nil? case-id) (svc-session-expired! cfg chat-id message-id)
      (nil? step-id) (svc-edit! cfg chat-id message-id {:text "Invalid step."
                                                        :reply-markup (svc-step-actions-keyboard)})
      :else
      (let [res (actions/execute! state {:action/id :cap/action/service-case-read
                                        :actor actor
                                        :input {:service.case/id case-id}})
            err (:error res)
            steps (get-in res [:result :steps])
            step (first (filter #(= step-id (:service.case.step/step %)) steps))]
        (if (or err (nil? step))
          (svc-edit! cfg chat-id message-id {:text "Unable to open step."
                                             :reply-markup (svc-step-list-keyboard (or steps []))})
          (do
            (save-services-session! chat-id (assoc session :stage :svc/step-open :step-id step-id))
            (svc-edit! cfg chat-id message-id {:text (str (or (:service.case.step/internal-label step)
                                                             (:service.case.step/public-label step)
                                                             (name step-id))
                                                          "\nStatus: " (name (or (:service.case.step/status step) :not-started)))
                                               :reply-markup (svc-step-actions-keyboard)})))))))

(defn- svc-step-status-requires-reason?
  [status]
  (contains? #{:blocked :action-required :rejected} status))

(defn- svc-handle-step-set!
  [{:keys [state chat-id message-id cfg db chat-user]} status-raw]
  (let [session (get-services-session! chat-id)
        case-id (:case-id session)
        step-id (:step-id session)
        status (when (present-string? status-raw)
                 (keyword status-raw))
        actor (actions/actor-from-telegram (or (:user session) chat-user))]
    (cond
      (nil? session) (svc-session-expired! cfg chat-id message-id)
      (or (nil? case-id) (nil? step-id)) (svc-session-expired! cfg chat-id message-id)
      (nil? status) (svc-edit! cfg chat-id message-id {:text "Invalid status."
                                                       :reply-markup (svc-step-actions-keyboard)})
      (svc-step-status-requires-reason? status)
      (do
        (save-services-session! chat-id (assoc session
                                              :awaiting-reason {:case-id case-id
                                                                :step-id step-id
                                                                :status status}))
        (svc-edit! cfg chat-id message-id {:text (str "Send a short reason for " (name status) ".")
                                           :reply-markup {:inline_keyboard [[(svc-inline-button "Back" "svc:case:open")]]}}))
      :else
      (let [res (actions/execute! state {:action/id :cap/action/service-case-step-set-status
                                        :actor actor
                                        :input {:service.case/id case-id
                                                :service.case.step/step step-id
                                                :service.case.step/status status}})
            err (:error res)
            steps (get-in res [:result :steps])]
        (if err
          (svc-edit! cfg chat-id message-id {:text (str "Failed: " (:message err))
                                             :reply-markup (svc-step-actions-keyboard)})
          (do
            (save-services-session! chat-id (assoc session :stage :svc/case-open :step-id nil))
            (svc-edit! cfg chat-id message-id {:text "Updated."
                                               :reply-markup (svc-step-list-keyboard steps)})))))))

(defmethod
 handle-callback-dispatch
 :svc/cancel
 [{:keys [chat-id message-id cfg]}]
 (take-services-session! chat-id)
 (edit-message!
  cfg
  {:chat-id chat-id
   :message-id message-id
   :text "Closed."
   :reply-markup {:inline_keyboard []}}))

(defmethod
 handle-callback-dispatch
 :svc/client-pick
 [ctx]
 (svc-render-client-pick! ctx))

(defmethod
 handle-callback-dispatch
 :svc/client-set
 [{:keys [chat-id message-id cfg db parsed chat-user] :as ctx}]
 (let [raw (:client-id parsed)
       client-id (when raw (try (UUID/fromString (str raw)) (catch Exception _ nil)))]
   (if-not client-id
     (svc-edit! cfg chat-id message-id {:text "Invalid client."
                                        :reply-markup (svc-menu-keyboard)})
     (let [existing (or (get-services-session! chat-id) {})
           chat-user (or (:user existing) chat-user)]
       (save-services-session! chat-id (assoc existing
                                             :user chat-user
                                             :client-id client-id
                                             :case-id nil
                                             :step-id nil
                                             :stage :svc/menu))
       (svc-render-menu! (assoc ctx :chat-user chat-user))))))

(defmethod
 handle-callback-dispatch
 :svc/menu
 [ctx]
 (svc-render-menu! ctx))

(defmethod
 handle-callback-dispatch
 :svc/cases
 [ctx]
 (svc-render-cases! ctx))

(defmethod
 handle-callback-dispatch
 :svc/create-menu
 [ctx]
 (svc-render-create-menu! ctx))

(defmethod
 handle-callback-dispatch
 :svc/case-create
 [{:keys [state chat-id message-id cfg db parsed chat-user] :as ctx}]
 (let [session (get-services-session! chat-id)
       client-id (:client-id session)
       raw (:service-id parsed)
       service-id (when (present-string? raw) (keyword raw))
       actor (actions/actor-from-telegram (or (:user session) chat-user))]
   (cond
     (nil? session) (svc-session-expired! cfg chat-id message-id)
     (nil? client-id) (svc-session-expired! cfg chat-id message-id)
     (nil? service-id) (svc-edit! cfg chat-id message-id {:text "Invalid service."
                                                          :reply-markup (svc-menu-keyboard)})
     :else
     (let [res (actions/execute! state {:action/id :cap/action/service-case-create
                                       :actor actor
                                       :input {:client/id client-id
                                               :service/id service-id}})
           err (:error res)
           c (get-in res [:result :case])]
       (if err
         (svc-edit! cfg chat-id message-id {:text (str "Failed to create case: " (:message err))
                                            :reply-markup (svc-menu-keyboard)})
         (svc-render-case-open! ctx (:service.case/id c)))))))

(defmethod
 handle-callback-dispatch
 :svc/case-open
 [{:keys [parsed] :as ctx}]
 (let [raw (:case-id parsed)
       case-id (when raw (try (UUID/fromString (str raw)) (catch Exception _ nil)))]
   (svc-render-case-open! ctx case-id)))

(defmethod
 handle-callback-dispatch
 :svc/case-open-current
 [{:keys [chat-id] :as ctx}]
 (let [session (get-services-session! chat-id)]
   (svc-render-case-open! ctx (:case-id session))))

(defmethod
 handle-callback-dispatch
 :svc/step-open
 [{:keys [parsed] :as ctx}]
 (svc-render-step-open! ctx (:step-id parsed)))

(defmethod
 handle-callback-dispatch
 :svc/step-set
 [{:keys [parsed] :as ctx}]
 (svc-handle-step-set! ctx (:status parsed)))

(defmethod
 handle-callback-dispatch
 :svc/portal
 [{:keys [state chat-id message-id cfg db chat-user]}]
 (let [session (get-services-session! chat-id)
       client-id (:client-id session)
       actor (actions/actor-from-telegram (or (:user session) chat-user))]
   (cond
     (nil? session) (svc-session-expired! cfg chat-id message-id)
     (nil? client-id) (svc-session-expired! cfg chat-id message-id)
     :else
     (let [res (actions/execute! state {:action/id :cap/action/client-portal-link
                                       :actor actor
                                       :input {:client/id client-id}})
           err (:error res)
           link (get-in res [:result :link])]
       (if err
         (svc-edit! cfg chat-id message-id {:text (str "Failed: " (:message err))
                                            :reply-markup (svc-menu-keyboard)})
         (svc-edit! cfg chat-id message-id {:text (str "Client portal link:\n" link)
                                            :reply-markup {:inline_keyboard [[(svc-inline-button "Back" "svc:menu")]]}}))))))
