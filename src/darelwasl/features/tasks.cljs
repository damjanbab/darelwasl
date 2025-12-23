(ns darelwasl.features.tasks
  (:require [clojure.string :as str]
            [darelwasl.state :as state]
            [darelwasl.ui.components :as ui]
            [darelwasl.ui.entity :as entity]
            [darelwasl.ui.shell :as shell]
            [darelwasl.util :as util]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(defn status-filter-chips
  [status]
  (map (fn [{:keys [id label]}]
         {:label (if (nil? id) "All" label)
          :active? (= status id)
          :on-click #(rf/dispatch [:darelwasl.app/update-filter :status id])})
       state/status-options))

(defn inline-select
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

(defn tasks-filter-bar []
  (let [{:keys [filters status assignees]} @(rf/subscribe [:darelwasl.app/tasks])
        tasks-status status
        {:keys [items]} @(rf/subscribe [:darelwasl.app/tags])
        {:keys [status priority tag assignee archived sort order page-size]} filters
        available-assignees (if (seq assignees) assignees state/fallback-assignees)
        tag-options (cons {:id nil :label "All tags"}
                          (map (fn [t] {:id (str (:tag/id t)) :label (:tag/name t)}) items))
        archived-value (case archived
                         true "archived"
                         :all "all"
                         "active")
        loading? (= tasks-status :loading)]
    [:div.tasks-filter-bar
     [:div.tasks-filter-bar__main
      [:div.tasks-filter-group
       [:span.tasks-filter-label "Status"]
       [ui/chip-bar {:chips (status-filter-chips status)}]]
      [inline-select {:label "Priority"
                      :options state/priority-options
                      :value priority
                      :on-change #(rf/dispatch [:darelwasl.app/update-filter :priority (util/->keyword-or-nil %)])}]
      [inline-select {:label "Assignee"
                      :options (cons {:id nil :label "All assignees"} available-assignees)
                      :value assignee
                      :on-change #(rf/dispatch [:darelwasl.app/update-filter :assignee (when-not (str/blank? %) %)])}]
      [inline-select {:label "Tag"
                      :options tag-options
                      :value (when tag (str tag))
                      :on-change #(rf/dispatch [:darelwasl.app/update-filter :tag (when-not (str/blank? %) %)])}]
      [:div.tasks-filter-group
       [:span.tasks-filter-label "Archived"]
      [:select.filter-select
        {:value archived-value
         :aria-label "Archived"
         :on-change #(let [val (.. % -target -value)
                           next (case val
                                  "archived" true
                                  "all" :all
                                  false)]
                       (rf/dispatch [:darelwasl.app/update-filter :archived next]))}
        [:option {:value "active"} "Active"]
        [:option {:value "all"} "All"]
        [:option {:value "archived"} "Archived only"]]]
      [:div.tasks-filter-group
       [:span.tasks-filter-label "Sort"]
       [:select.filter-select
        {:value (name (or sort :updated))
         :aria-label "Sort"
         :on-change #(rf/dispatch [:darelwasl.app/set-sort (util/->keyword-or-nil (.. % -target -value))])}
        (for [opt [{:id :updated :label "Updated"}
                   {:id :due :label "Due date"}
                   {:id :priority :label "Priority"}]]
          ^{:key (name (:id opt))}
          [:option {:value (name (:id opt))} (:label opt)])]
       [ui/button {:variant :secondary
                   :on-click #(rf/dispatch [:darelwasl.app/toggle-order])}
        (if (= order :asc) "Asc" "Desc")]]
      [inline-select {:label "Rows"
                      :options (map (fn [n] {:id n :label (str n " rows")}) [10 25 50 100])
                      :value (or page-size 25)
                      :on-change #(rf/dispatch [:darelwasl.app/set-task-page-size (js/parseInt % 10)])}]]
     [:div.tasks-filter-bar__actions
      [ui/button {:variant :secondary
                  :disabled loading?
                  :on-click #(rf/dispatch [:darelwasl.app/clear-filters])}
       "Reset"]
      [ui/button {:variant :secondary
                  :disabled loading?
                  :on-click #(rf/dispatch [:darelwasl.app/fetch-tasks])}
       "Refresh"]
      [ui/button {:disabled loading?
                  :on-click #(rf/dispatch [:darelwasl.app/start-new-task])}
       "New task"]]]))

(defn task-list []
  (let [tasks-state @(rf/subscribe [:darelwasl.app/tasks])
        {:keys [items status error selected pagination]} tasks-state
        list-config (entity/list-config :entity.type/task)
        limit (or (:limit pagination) (count items))
        offset (or (:offset pagination) 0)
        total (or (:total pagination) (count items))
        page (or (:page pagination) 1)
        current-count (count items)]
    [:div.task-pane
     [ui/entity-list {:title "Tasks"
                      :meta (entity/list-meta :entity.type/task items)
                      :items items
                      :status status
                      :error error
                      :selected selected
                      :chips nil
                      :header-actions nil
                      :key-fn (or (:key list-config) :task/id)
                      :render-row (entity/render-row :entity.type/task {:on-select #(rf/dispatch [:darelwasl.app/select-task (:task/id %)])})}]
     [ui/pagination-controls {:limit limit
                              :offset offset
                              :total total
                              :current-count current-count
                              :on-prev #(rf/dispatch [:darelwasl.app/set-task-page (dec page)])
                              :on-next #(rf/dispatch [:darelwasl.app/set-task-page (inc page)])}]]))

(defn tag-selector
  [{:keys [selected-tags tag-state tag-entry]}]
  (let [tags (:items tag-state)
        saving? (= (:status tag-state) :saving)]
    [:div.tag-selector
     [:div.selected-tags
      (if (seq selected-tags)
        (for [tid selected-tags
              :let [tag (some #(when (= (:tag/id %) tid) %) tags)
                    name (or (:tag/name tag) "Tag")]]
          ^{:key (str "sel-" tid)}
          [:div.tag-chip
           [:span "#" name]
           [:button.icon-button {:type "button"
                                 :on-click #(rf/dispatch [:darelwasl.app/detach-tag tid])}
            "×"]])
        [:span.helper-text "No tags yet. Add one below."])]
     [:div.available-tags
      (for [tag tags]
        (let [tid (:tag/id tag)
              name (:tag/name tag)
              checked? (contains? selected-tags tid)]
          ^{:key (str "tag-" tid)}
          [:div.tag-row
           [:label
            [:input {:type "checkbox"
                     :checked checked?
                     :on-change #(if (.. % -target -checked)
                                   (rf/dispatch [:darelwasl.app/attach-tag tid])
                                   (rf/dispatch [:darelwasl.app/detach-tag tid]))}]
            [:span name]]
           [:div.tag-row__actions
            [:button.link {:type "button"
                           :disabled saving?
                           :on-click (fn []
                                       (when-let [next (js/prompt "Rename tag" name)]
                                         (rf/dispatch [:darelwasl.app/rename-tag tid next])))}
             "Rename"]
            [:button.link.danger {:type "button"
                                  :disabled saving?
                                  :on-click (fn []
                                              (when (js/confirm (str "Delete tag \"" name "\"? This will remove it from tasks."))
                                                (rf/dispatch [:darelwasl.app/delete-tag tid])))}
             "Delete"]]]))]
     [:div.tag-input
      [:input.form-input {:type "text"
                          :placeholder "Create or attach tag (press Enter)"
                          :value tag-entry
                          :on-change #(rf/dispatch [:darelwasl.app/set-tag-entry (.. % -target -value)])
                          :on-key-down (fn [e]
                                         (when (= "Enter" (.-key e))
                                           (.preventDefault e)
                                           (rf/dispatch [:darelwasl.app/add-tag-by-name (.. e -target -value)])))
                          :disabled saving?}]
      [:button.button.secondary {:type "button"
                                 :disabled saving?
                                 :on-click #(rf/dispatch [:darelwasl.app/add-tag-by-name tag-entry])}
       "Add tag"]]
     (when-let [err (:error tag-state)]
       [:div.form-error err])]))

(defn task-preview []
  (r/with-let [show-advanced? (r/atom false)]
    (let [{:keys [assignees]} @(rf/subscribe [:darelwasl.app/tasks])
          detail @(rf/subscribe [:darelwasl.app/task-detail])
          task @(rf/subscribe [:darelwasl.app/selected-task])
          task-config (entity/detail-config :entity.type/task)
          {:keys [form mode status error]} detail
          detail-status status
          task-status (:status form)
          create? (= mode :create)
          saving? (= detail-status :saving)
          available-assignees (if (seq assignees) assignees state/fallback-assignees)
          close-sheet! #(rf/dispatch [:darelwasl.app/close-detail])]
      (let [placeholder (when (and (not create?) (nil? task))
                          [:div.placeholder-card
                           [:strong (:placeholder-title task-config)]
                           [:p (:placeholder-copy task-config)]])
            title (cond
                    create? (:create-title task-config)
                    task (or (:task/title task) "Untitled task")
                    :else (:title task-config))
            meta nil
            footer (case detail-status
                     :saving [:span.pill "Saving..."]
                     :success [:span.pill "Saved"]
                     :deleting [:span.pill "Deleting..."]
                     nil)
            show-detail? (or create? (some? task))]
        (if show-detail?
          [:div.task-detail-panel
           [ui/entity-detail
           {:title title
            :meta meta
            :actions [:div.detail-header-actions
                      [:button.button.secondary {:type "button"
                                                 :on-click close-sheet!
                                                 :disabled saving?}
                       "Close"]
                      [:button.button.secondary {:type "button"
                                                 :on-click #(rf/dispatch [:darelwasl.app/start-new-task])
                                                 :disabled saving?}
                       "New"]
                      [:button.button.secondary {:type "button"
                                                 :on-click #(rf/dispatch [:darelwasl.app/reset-detail])
                                                 :disabled saving?}
                       "Reset"]]
            :placeholder placeholder
            :content (when-not placeholder
                       (let [{:keys [title description priority assignee due-date tags archived? extended?]} form
                             tag-state @(rf/subscribe [:darelwasl.app/tags])
                             current-assignee (or assignee (:id (first available-assignees)) "")
                             summary (->> [(if archived? "Archived" (when task-status (util/status-label task-status)))
                                           (when priority (str "Priority " (util/priority-label priority)))
                                           (when due-date (str "Due " (util/format-date due-date)))]
                                          (remove #(or (nil? %) (str/blank? %)))
                                          (str/join " · "))]
                         [:form.detail-form
                          {:on-submit (fn [e]
                                        (.preventDefault e)
                                        (rf/dispatch [:darelwasl.app/save-task]))}
                          [:div.detail-header-row
                          [:div.detail-meta
                           (when (seq summary) [:span.meta summary])
                           (when extended? [:span.meta "Extended"])
                           (when-let [prov (util/provenance-label task)]
                             [:span.meta prov])]
                          [:button.icon-button {:type "button"
                                                 :aria-label "Close detail"
                                                 :on-click close-sheet!}
                            "×"]]
                          (when error
                            [:div.form-error error])
                          [:div.quick-controls
                           [:div.form-group
                            [:label.form-label "Status"]
                            [:div.chip-row
                             (for [{:keys [id label]} state/task-status-options]
                               ^{:key (str "status-chip-" (name id))}
                               [:button.button.secondary {:type "button"
                                                          :class (when (= task-status id) "active")
                                                          :disabled saving?
                                                          :on-click #(rf/dispatch [:darelwasl.app/update-detail-field :status id])}
                                label])]
                           [:select.form-input {:id "task-status"
                                                 :aria-label "Task status"
                                                 :value (name (or task-status :todo))
                                                 :on-change #(rf/dispatch [:darelwasl.app/update-detail-field :status (util/->keyword-or-nil (.. % -target -value))])
                                                 :disabled saving?}
                             (for [{:keys [id label]} state/task-status-options]
                               ^{:key (name id)}
                               [:option {:value (name id)} label])]]
                          [:div.form-group
                           [:label.form-label "Priority"]
                           [:div.chip-row
                            (for [{:keys [id label]} state/task-priority-options]
                               ^{:key (str "priority-chip-" (name id))}
                               [:button.button.secondary {:type "button"
                                                          :class (when (= priority id) "active")
                                                          :disabled saving?
                                                          :on-click #(rf/dispatch [:darelwasl.app/update-detail-field :priority id])}
                                label])]
                           [:select.form-input {:id "task-priority"
                                                 :aria-label "Task priority"
                                                 :value (name (or priority :medium))
                                                 :on-change #(rf/dispatch [:darelwasl.app/update-detail-field :priority (util/->keyword-or-nil (.. % -target -value))])
                                                 :disabled saving?}
                            (for [{:keys [id label]} state/task-priority-options]
                               ^{:key (name id)}
                               [:option {:value (name id)} label])]]]
                          (when (= task-status :pending)
                            [:div.form-group
                             [:label.form-label {:for "task-pending-reason"} "Pending reason"]
                             [:textarea.form-input {:id "task-pending-reason"
                                                    :rows 3
                                                    :value (or (:pending-reason form) "")
                                                    :placeholder "Why is this pending?"
                                                    :on-change #(rf/dispatch [:darelwasl.app/update-detail-field :pending-reason (.. % -target -value)])
                                                    :disabled saving?}]])
                          [:div.detail-grid
                           [:div.form-group
                            [:label.form-label {:for "task-title"} "Title"]
                            [:input.form-input {:id "task-title"
                                                :type "text"
                                                :value title
                                                :placeholder "Name the task"
                                                :on-change #(rf/dispatch [:darelwasl.app/update-detail-field :title (.. % -target -value)])
                                                :disabled saving?}]]
                           [:div.form-group
                            [:label.form-label {:for "task-assignee"} "Assignee"]
                           [:select.form-input {:id "task-assignee"
                                                 :aria-label "Assignee"
                                                 :value current-assignee
                                                 :on-change #(rf/dispatch [:darelwasl.app/update-detail-field :assignee (.. % -target -value)])
                                                 :disabled saving?}
                             (for [{:keys [id label]} available-assignees]
                               ^{:key (str "assignee-" id)}
                               [:option {:value id} label])]]
                           [:div.form-group
                            [:label.form-label {:for "task-due"} "Due date"]
                           [:input.form-input {:id "task-due"
                                                :aria-label "Due date"
                                                :type "date"
                                                :value (or due-date "")
                                                :on-change #(rf/dispatch [:darelwasl.app/update-detail-field :due-date (.. % -target -value)])
                                                :disabled saving?}]]]
                          [:div.form-group
                           [:label.form-label {:for "task-description"} "Description"]
                           [:textarea.form-input {:id "task-description"
                                                  :rows 4
                                                  :value description
                                                  :placeholder "Add context, acceptance, or next steps (optional)"
                                                  :on-change #(rf/dispatch [:darelwasl.app/update-detail-field :description (.. % -target -value)])
                                                  :disabled saving?}]]
                          [:div.disclosure
                           [:button.button.secondary {:type "button"
                                                      :on-click #(swap! show-advanced? not)}
                            (if @show-advanced? "Hide advanced" "More fields")]
                           (when @show-advanced?
                             [:div.detail-grid
                              [:div.form-group
                               [:label.form-label "Tags"]
                               [tag-selector {:selected-tags tags
                                              :tag-state tag-state
                                              :tag-entry (:tag-entry detail)}]]
                              [:div.form-group
                               [:label.form-label "Flags"]
                               [:div.toggle-row
                                [:label.toggle
                                 [:input {:type "checkbox"
                                          :checked (boolean extended?)
                                          :on-change #(rf/dispatch [:darelwasl.app/update-detail-field :extended? (.. % -target -checked)])
                                          :disabled saving?}]
                                 [:span "Extended"]]
                                [:label.toggle
                                 [:input {:type "checkbox"
                                          :checked (boolean archived?)
                                          :on-change #(rf/dispatch [:darelwasl.app/update-detail-field :archived? (.. % -target -checked)])
                                          :disabled saving?}]
                                 [:span "Archived"]]]]])]
                          [:div.detail-actions
                           [:button.button {:type "submit"
                                            :disabled saving?}
                            (if create? "Create task" "Save changes")]
                           [:button.button.secondary {:type "button"
                                                      :on-click #(rf/dispatch [:darelwasl.app/reset-detail])
                                                      :disabled saving?}
                            "Cancel"]
                          (when-not create?
                            [:button.button.danger {:type "button"
                                                    :disabled (= detail-status :deleting)
                                                    :on-click #(when (js/confirm "Delete this task? This cannot be undone.")
                                                                 (rf/dispatch [:darelwasl.app/delete-task (:id form)]))}
                             (if (= detail-status :deleting) "Deleting..." "Delete")])]]))
            :footer footer}]]
          [:div.task-detail-empty])))))

(defn task-shell []
  [shell/app-shell
   [:<>
    [tasks-filter-bar]
    [:main.tasks-layout.mailbox
     [:div.tasks-column.list
      [task-list]]
     [:div.tasks-column.detail
      [task-preview]]
     [:div.tasks-spacer]]]
   [:span "Logged in workspace · Filter and sort tasks from the API."]])
