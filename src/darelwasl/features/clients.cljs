(ns darelwasl.features.clients
  (:require [clojure.string :as str]
            [darelwasl.state :as state]
            [darelwasl.ui.components :as ui]
            [darelwasl.ui.shell :as shell]
            [darelwasl.util :as util]
            [re-frame.core :as rf]))

(def ^:private client-status-options
  (remove #(nil? (:id %)) state/client-status-options))

(defn- option-label
  [options value fallback]
  (or (some (fn [{:keys [id label]}]
              (when (= id value) label))
            options)
      fallback))

(defn- client-status-label
  [status]
  (if (nil? status)
    "Unknown"
    (option-label state/client-status-options status "Unknown")))

(defn- client-status-chip
  [status]
  (let [label (client-status-label status)
        cls (if status (name status) "neutral")]
    [ui/chip label :class (str "status " cls)]))

(defn- status-filter-chips
  [status]
  (map (fn [{:keys [id label]}]
         {:label (if (nil? id) "All" label)
          :active? (= status id)
          :on-click #(rf/dispatch [:darelwasl.app/set-client-filter :status id])})
       state/client-status-options))

(defn- inline-select
  [{:keys [label options value on-change]}]
  (let [current (cond
                  (keyword? value) (name value)
                  (string? value) value
                  (nil? value) ""
                  :else (str value))]
    [:div.tasks-filter-group
     [:span.tasks-filter-label label]
     [:select.filter-select
      {:value current
       :aria-label label
       :on-change #(on-change (.. % -target -value))}
      (for [{:keys [id label]} options
            :let [option-val (cond
                               (keyword? id) (name id)
                               (string? id) id
                               (nil? id) ""
                               :else (str id))]]
        ^{:key (or (str id) "all")}
        [:option {:value option-val} label])]]))

(defn- next-action-summary
  [task]
  (if task
    (let [title (or (:task/title task) "Next action")
          due (util/format-date (:task/due-date task))
          parts (cond-> [(str "Next: " title)]
                  due (conj (str "Due " due)))]
      (str/join " · " parts))
    "No next action yet"))

(defn clients-filter-bar
  []
  (let [{:keys [filters status]} @(rf/subscribe [:darelwasl.app/clients])
        filter-status (:status filters)
        page-size (:page-size filters)
        loading? (= status :loading)]
    [:div.tasks-filter-bar
     [:div.tasks-filter-bar__main
      [:div.tasks-filter-group
       [:span.tasks-filter-label "Status"]
       [ui/chip-bar {:chips (status-filter-chips filter-status)}]]
      [inline-select {:label "Rows"
                      :options (map (fn [n] {:id n :label (str n " rows")}) [10 25 50 100])
                      :value (or page-size 25)
                      :on-change #(rf/dispatch [:darelwasl.app/set-client-page-size (js/parseInt % 10)])}]]
     [:div.tasks-filter-bar__actions
      [ui/button {:variant :secondary
                  :disabled loading?
                  :on-click #(rf/dispatch [:darelwasl.app/set-client-filter :status nil])}
       "Reset"]
      [ui/button {:variant :secondary
                  :disabled loading?
                  :on-click #(rf/dispatch [:darelwasl.app/fetch-clients])}
       "Refresh"]
      [ui/button {:disabled loading?
                  :on-click #(rf/dispatch [:darelwasl.app/new-client])}
       "New client"]]]))

(defn- client-row
  [client selected?]
  (let [client-id (:client/id client)
        name (or (:client/name client) "Unnamed client")
        status (:client/status client)
        next-task (:client/next-task client)]
    [ui/list-row {:title name
                  :meta (next-action-summary next-task)
                  :trailing (client-status-chip status)
                  :selected? selected?
                  :class "compact"
                  :on-click #(rf/dispatch [:darelwasl.app/select-client client-id])}]))

(defn client-list
  []
  (let [{:keys [items status error selected pagination]} @(rf/subscribe [:darelwasl.app/clients])
        limit (or (:limit pagination) (count items))
        offset (or (:offset pagination) 0)
        total (or (:total pagination) (count items))
        page (or (:page pagination) 1)
        current-count (count items)]
    [:div.task-pane
     [ui/entity-list {:title "Clients"
                      :meta (str (count items) " clients")
                      :items items
                      :status status
                      :error error
                      :selected selected
                      :key-fn :client/id
                      :render-row client-row
                      :panel-class "task-list-panel"
                      :list-class "task-list"}]
     [ui/pagination-controls {:limit limit
                              :offset offset
                              :total total
                              :current-count current-count
                              :on-prev #(rf/dispatch [:darelwasl.app/set-client-page (dec page)])
                              :on-next #(rf/dispatch [:darelwasl.app/set-client-page (inc page)])}]]))

(defn- task-meta-line
  [task]
  (let [status-label (if (:task/archived? task)
                       "Archived"
                       (util/status-label (:task/status task)))
        due-label (when-let [due (:task/due-date task)]
                    (str "Due " (util/format-date due)))
        pending-reason (when (= :pending (:task/status task))
                         (:task/pending-reason task))
        desc (or (util/truncate pending-reason 80)
                 (util/truncate (:task/description task) 80))
        trailing (->> [status-label due-label]
                      (remove #(or (nil? %) (str/blank? %)))
                      (str/join " · "))]
    {:description desc
     :trailing trailing}))

(defn- task-history
  [tasks]
  [:div
   (if (seq tasks)
     (for [task tasks
           :let [meta (task-meta-line task)]]
       ^{:key (str "client-task-" (:task/id task))}
       [ui/list-row {:title (or (:task/title task) "Untitled task")
                     :description (:description meta)
                     :trailing (:trailing meta)
                     :class "compact"}])
     [:div.meta "No tasks yet."] )])

(defn client-preview
  []
  (let [detail @(rf/subscribe [:darelwasl.app/client-detail])
        selected @(rf/subscribe [:darelwasl.app/selected-client])
        tasks-state @(rf/subscribe [:darelwasl.app/tasks])
        {:keys [form mode error next-action next-action-form next-action-status next-action-error tasks]} detail
        detail-status (:status detail)
        {:keys [phone email channel notes id] :as form} form
        client-name (:name form)
        client-status (:status form)
        create? (= mode :create)
        saving? (= detail-status :saving)
        action-saving? (= next-action-status :saving)
        action-success? (= next-action-status :success)
        assignees (if (seq (:assignees tasks-state))
                    (:assignees tasks-state)
                    state/fallback-assignees)
        action-form (or next-action-form state/default-task-form)
        {:keys [title description priority assignee due-date pending-reason]} action-form
        action-status (:status action-form)
        current-status (or action-status :todo)
        current-priority (or priority :medium)
        current-assignee (or assignee (:id (first assignees)) "")
        title-text (cond
                     create? "New client"
                     selected (or client-name "Client")
                     :else "Client")
        meta (when (and (not create?) selected)
               (client-status-label client-status))
        reset-client (fn []
                       (if create?
                         (rf/dispatch [:darelwasl.app/new-client])
                         (when-let [client-id (:id form)]
                           (rf/dispatch [:darelwasl.app/fetch-client-detail client-id]))))]
    [:div.task-detail-panel
     [ui/entity-detail
      {:title title-text
       :meta meta
       :actions [:div.detail-header-actions
                 [:button.button.secondary {:type "button"
                                            :disabled saving?
                                            :on-click #(rf/dispatch [:darelwasl.app/new-client])}
                  "New"]
                 [:button.button.secondary {:type "button"
                                            :disabled saving?
                                            :on-click reset-client}
                  "Reset"]]
       :placeholder (when (and (not create?) (nil? selected))
                      [:div.placeholder-card
                       [:strong "Select a client"]
                       [:p "Pick a client from the list or create a new one."]])
       :content (when (or create? selected)
                  (if (= detail-status :loading)
                    [ui/loading-state "Loading client..."]
                    [:<>
                     [:form.detail-form
                      {:on-submit (fn [e]
                                    (.preventDefault e)
                                    (rf/dispatch [:darelwasl.app/save-client]))}
                      (when error [:div.form-error error])
                      (when (= detail-status :success) [:div.form-success {:aria-live "polite"} "Saved"])
                      [:div.detail-grid
                       [:div.form-group
                        [:label.form-label {:for "client-name"} "Name"]
                        [:input.form-input {:id "client-name"
                                            :type "text"
                                            :value (or client-name "")
                                            :placeholder "Client name"
                                            :on-change #(rf/dispatch [:darelwasl.app/set-client-field :name (.. % -target -value)])
                                            :disabled saving?}]]
                       [:div.form-group
                        [:label.form-label {:for "client-status"} "Status"]
                        [:select.form-input {:id "client-status"
                                             :aria-label "Client status"
                                             :value (name (or client-status :lead))
                                             :on-change #(rf/dispatch [:darelwasl.app/set-client-field :status (util/->keyword-or-nil (.. % -target -value))])
                                             :disabled saving?}
                         (for [{:keys [id label]} client-status-options]
                           ^{:key (str "client-status-" (name id))}
                           [:option {:value (name id)} label])]]
                       [:div.form-group
                         [:label.form-label {:for "client-channel"} "Channel"]
                         [:select.form-input {:id "client-channel"
                                              :aria-label "Channel"
                                              :value (name (or channel ""))
                                              :on-change #(rf/dispatch [:darelwasl.app/set-client-field :channel (util/->keyword-or-nil (.. % -target -value))])
                                              :disabled saving?}
                         (for [{:keys [id label]} state/client-channel-options]
                           (let [id-str (when id (name id))]
                             ^{:key (str "client-channel-" (or id-str "none"))}
                             [:option {:value (or id-str "")} label]))]]
                       [:div.form-group
                        [:label.form-label {:for "client-phone"} "Phone"]
                        [:input.form-input {:id "client-phone"
                                            :type "text"
                                            :value (or phone "")
                                            :placeholder "Phone number"
                                            :on-change #(rf/dispatch [:darelwasl.app/set-client-field :phone (.. % -target -value)])
                                            :disabled saving?}]]
                       [:div.form-group
                        [:label.form-label {:for "client-email"} "Email"]
                        [:input.form-input {:id "client-email"
                                            :type "email"
                                            :value (or email "")
                                            :placeholder "Email address"
                                            :on-change #(rf/dispatch [:darelwasl.app/set-client-field :email (.. % -target -value)])
                                            :disabled saving?}]]]
                      [:div.form-group
                       [:label.form-label {:for "client-notes"} "Notes"]
                       [:textarea.form-input {:id "client-notes"
                                              :rows 3
                                              :value (or notes "")
                                              :placeholder "Add context, preferences, or history"
                                              :on-change #(rf/dispatch [:darelwasl.app/set-client-field :notes (.. % -target -value)])
                                              :disabled saving?}]]
                      [:div.detail-actions
                       [:button.button {:type "submit"
                                        :disabled saving?}
                        (if create? "Create client" "Save client")]]]
                     [:h3 "Next action"]
                     (if create?
                       [:div.meta "Save the client to add a next action."]
                       [:form.detail-form
                        {:on-submit (fn [e]
                                      (.preventDefault e)
                                      (rf/dispatch [:darelwasl.app/save-next-action]))}
                        (when next-action-error [:div.form-error next-action-error])
                        (when action-success? [:div.form-success {:aria-live "polite"} "Saved"])
                        (when next-action
                          [:div.meta
                           (str "Current: "
                                (or (:task/title next-action) "Next action"))])
                        [:div.detail-grid
                         [:div.form-group
                          [:label.form-label {:for "action-title"} "Title"]
                          [:input.form-input {:id "action-title"
                                              :type "text"
                                              :value (or title "")
                                              :placeholder "Next action title"
                                              :on-change #(rf/dispatch [:darelwasl.app/set-next-action-field :title (.. % -target -value)])
                                              :disabled action-saving?}]]
                         [:div.form-group
                          [:label.form-label {:for "action-status"} "Status"]
                          [:select.form-input {:id "action-status"
                                               :aria-label "Status"
                                               :value (name current-status)
                                               :on-change #(rf/dispatch [:darelwasl.app/set-next-action-field :status (util/->keyword-or-nil (.. % -target -value))])
                                               :disabled action-saving?}
                           (for [{:keys [id label]} state/task-status-options]
                             ^{:key (str "action-status-" (name id))}
                             [:option {:value (name id)} label])]]
                         [:div.form-group
                          [:label.form-label {:for "action-priority"} "Priority"]
                          [:select.form-input {:id "action-priority"
                                               :aria-label "Priority"
                                               :value (name current-priority)
                                               :on-change #(rf/dispatch [:darelwasl.app/set-next-action-field :priority (util/->keyword-or-nil (.. % -target -value))])
                                               :disabled action-saving?}
                           (for [{:keys [id label]} state/task-priority-options]
                             ^{:key (str "action-priority-" (name id))}
                             [:option {:value (name id)} label])]]
                         [:div.form-group
                          [:label.form-label {:for "action-assignee"} "Assignee"]
                          [:select.form-input {:id "action-assignee"
                                               :aria-label "Assignee"
                                               :value current-assignee
                                               :on-change #(rf/dispatch [:darelwasl.app/set-next-action-field :assignee (.. % -target -value)])
                                               :disabled action-saving?}
                           (for [{:keys [id label]} assignees]
                             ^{:key (str "action-assignee-" id)}
                             [:option {:value id} label])]]
                         [:div.form-group
                          [:label.form-label {:for "action-due"} "Due date"]
                          [:input.form-input {:id "action-due"
                                              :type "date"
                                              :value (or due-date "")
                                              :on-change #(rf/dispatch [:darelwasl.app/set-next-action-field :due-date (.. % -target -value)])
                                              :disabled action-saving?}]]]
                        (when (= current-status :pending)
                          [:div.form-group
                           [:label.form-label {:for "action-pending-reason"} "Pending reason"]
                           [:textarea.form-input {:id "action-pending-reason"
                                                  :rows 3
                                                  :value (or pending-reason "")
                                                  :placeholder "Why is this pending?"
                                                  :on-change #(rf/dispatch [:darelwasl.app/set-next-action-field :pending-reason (.. % -target -value)])
                                                  :disabled action-saving?}]])
                        [:div.form-group
                         [:label.form-label {:for "action-description"} "Description"]
                         [:textarea.form-input {:id "action-description"
                                                :rows 4
                                                :value (or description "")
                                                :placeholder "Add context or next steps"
                                                :on-change #(rf/dispatch [:darelwasl.app/set-next-action-field :description (.. % -target -value)])
                                                :disabled action-saving?}]]
                        [:div.detail-actions
                         [:button.button {:type "submit"
                                          :disabled action-saving?}
                          (if (:id action-form) "Save action" "Create action")]
                         [:button.button.secondary {:type "button"
                                                    :disabled action-saving?
                                                    :on-click #(rf/dispatch [:darelwasl.app/reset-next-action])}
                          "Reset"]]] )
                     [:h3 "Task history"]
                     [task-history tasks]]))}]]))

(defn clients-shell
  []
  [shell/app-shell
   [:<>
    [clients-filter-bar]
    [:main.tasks-layout.mailbox
     [:div.tasks-column.list
      [client-list]]
     [:div.tasks-column.detail
      [client-preview]]
     [:div.tasks-spacer]]]
   [:span "Clients and follow-ups"]])
