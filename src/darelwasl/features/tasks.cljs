(ns darelwasl.features.tasks
  (:require [clojure.string :as str]
            [darelwasl.state :as state]
            [darelwasl.ui.components :as ui]
            [darelwasl.ui.entity :as entity]
            [darelwasl.ui.shell :as shell]
            [darelwasl.util :as util]
            [re-frame.core :as rf]))

(defn filter-select
  [{:keys [label options value on-change]}]
  (let [current (cond
                  (keyword? value) (name value)
                  (string? value) value
                  (nil? value) ""
                  :else (str value))]
    [:label.filter-group
     [:span.filter-label label]
     [:select.filter-select
      {:value current
       :on-change #(on-change (.. % -target -value))}
      (for [{:keys [id label]} options
            :let [option-val (cond
                               (keyword? id) (name id)
                               (string? id) id
                               (nil? id) ""
                               :else (str id))]]
        ^{:key (or (str id) "all")}
        [:option {:value option-val} label])]]))

(defn filters-panel []
  (let [{:keys [filters status assignees]} @(rf/subscribe [:darelwasl.app/tasks])
        {:keys [items]} @(rf/subscribe [:darelwasl.app/tags])
        task-status status
        {:keys [status priority tag assignee archived sort order page-size]} filters
        available-assignees (if (seq assignees) assignees state/fallback-assignees)
        tag-options (cons {:id nil :label "All tags"}
                          (map (fn [t] {:id (str (:tag/id t)) :label (:tag/name t)}) items))]
    [:div.panel.tasks-controls.compact
     [:div.controls-header
      [:div
       [:h3 "Filters"]
       [:p "Quickly narrow tasks by status, priority, tag, or assignee."]]
      [:div.controls-actions
       [:button.button.secondary {:type "button"
                                  :on-click #(rf/dispatch [:darelwasl.app/clear-filters])
                                  :disabled (= filters state/default-task-filters)}
        "Reset"]
       [:button.button.secondary {:type "button"
                                  :on-click #(rf/dispatch [:darelwasl.app/fetch-tasks])
                                  :disabled (= task-status :loading)}
        "Refresh"]]]
     [:div.controls-grid
      [filter-select {:label "Status"
                      :options state/status-options
                      :value (when status (name status))
                      :on-change #(rf/dispatch [:darelwasl.app/update-filter :status (util/->keyword-or-nil %)])}]
      [filter-select {:label "Priority"
                      :options state/priority-options
                      :value (when priority (name priority))
                      :on-change #(rf/dispatch [:darelwasl.app/update-filter :priority (util/->keyword-or-nil %)])}]
      [filter-select {:label "Tag"
                      :options tag-options
                      :value (when tag (str tag))
                      :on-change #(rf/dispatch [:darelwasl.app/update-filter :tag (when-not (str/blank? %) %)])}]
      [filter-select {:label "Assignee"
                      :options (cons {:id nil :label "All assignees"} available-assignees)
                      :value assignee
                      :on-change #(rf/dispatch [:darelwasl.app/update-filter :assignee (when-not (str/blank? %) %)])}]]
     [:div.controls-grid
      [:div.filter-group
       [:span.filter-label "Archived"]
       [:div.chip-row
        [ui/button {:variant :secondary
                    :class (when (= archived false) "active")
                    :on-click #(rf/dispatch [:darelwasl.app/update-filter :archived false])}
         "Active"]
        [ui/button {:variant :secondary
                    :class (when (= archived :all) "active")
                    :on-click #(rf/dispatch [:darelwasl.app/update-filter :archived :all])}
         "All"]
        [ui/button {:variant :secondary
                    :class (when (= archived true) "active")
                    :on-click #(rf/dispatch [:darelwasl.app/update-filter :archived true])}
         "Archived only"]]]
      [:div.filter-group
       [:span.filter-label "Sort"]
       [:div.sort-row
        [:select.filter-select
         {:value (name sort)
          :aria-label "Sort"
         :on-change #(rf/dispatch [:darelwasl.app/set-sort (util/->keyword-or-nil (.. % -target -value))])}
         (for [opt [{:id :updated :label "Updated"}
                    {:id :due :label "Due date"}
                    {:id :priority :label "Priority"}]]
           ^{:key (name (:id opt))}
           [:option {:value (name (:id opt))} (:label opt)])]
        [ui/button {:variant :secondary
                    :on-click #(rf/dispatch [:darelwasl.app/toggle-order])}
         (if (= order :asc) "Asc" "Desc")]]]
      [:div.filter-group
       [:span.filter-label "Page size"]
       [:select.filter-select
        {:value (or page-size 25)
         :aria-label "Page size"
         :on-change #(rf/dispatch [:darelwasl.app/set-task-page-size (js/parseInt (.. % -target -value) 10)])}
        (for [n [10 25 50 100]]
          ^{:key (str "page-size-" n)}
          [:option {:value n} (str n " rows")])]]]]))

(defn task-list []
  (let [tasks-state @(rf/subscribe [:darelwasl.app/tasks])
        {:keys [items status error selected pagination]} tasks-state
        tag-state @(rf/subscribe [:darelwasl.app/tags])
        tag-index (into {} (map (fn [t] [(:tag/id t) (:tag/name t)]) (:items tag-state)))
        list-config (entity/list-config :entity.type/task)
        filters (:filters tasks-state)
        {:keys [status archived]} filters
        chip-filters [{:label "All" :active? (nil? status) :on-click #(rf/dispatch [:darelwasl.app/update-filter :status nil])}
                      {:label "In progress" :active? (= status :in-progress) :on-click #(rf/dispatch [:darelwasl.app/update-filter :status :in-progress])}
                      {:label "Pending" :active? (= status :pending) :on-click #(rf/dispatch [:darelwasl.app/update-filter :status :pending])}
                      {:label "Done" :active? (= status :done) :on-click #(rf/dispatch [:darelwasl.app/update-filter :status :done])}
                      {:label (case archived
                                true "Archived"
                                :all "All"
                                "Active")
                       :active? (not= archived false)
                       :on-click #(rf/dispatch [:darelwasl.app/toggle-archived])}]
        limit (or (:limit pagination) (count items))
        offset (or (:offset pagination) 0)
        total (or (:total pagination) (count items))
        page (or (:page pagination) 1)
        pages (or (:pages pagination) (max 1 (int (Math/ceil (/ (max total 1) (double (max limit 1)))))))
        current-count (count items)
        start (if (pos? current-count) (inc offset) 0)
        end (if (pos? current-count) (+ offset current-count) offset)
        meta-str (str "Showing "
                      (if (zero? total) 0 start)
                      (when (pos? total) (str "–" end))
                      " of "
                      total
                      " · Page "
                      page
                      " of "
                      pages)]
    [:<>
     [ui/entity-list {:title (:title list-config)
                      :meta meta-str
                      :items items
                      :status status
                      :error error
                      :selected selected
                      :chips chip-filters
                      :key-fn (or (:key list-config) :task/id)
                      :render-row (entity/render-row :entity.type/task {:tag-index tag-index
                                                                        :on-select #(rf/dispatch [:darelwasl.app/select-task (:task/id %)])})}]
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
  (let [{:keys [assignees]} @(rf/subscribe [:darelwasl.app/tasks])
        detail @(rf/subscribe [:darelwasl.app/task-detail])
        task @(rf/subscribe [:darelwasl.app/selected-task])
        task-config (entity/detail-config :entity.type/task)
        {:keys [form mode status error]} detail
        detail-status status
        task-status (:status form)
        create? (= mode :create)
        saving? (= detail-status :saving)
        available-assignees (if (seq assignees) assignees state/fallback-assignees)]
    (let [placeholder (when (and (not create?) (nil? task))
                        [:div.placeholder-card
                         [:strong (:placeholder-title task-config)]
                         [:p (:placeholder-copy task-config)]])
          title (if create?
                  (:create-title task-config)
                  (:title task-config))
          badge (if create?
                  (:create-badge task-config)
                  (:badge task-config))
          meta (if create?
                 (:meta-create task-config)
                 (or (:task/title task)
                     (:meta-edit-default task-config)))
          footer (case detail-status
                   :saving [:span.pill "Saving..."]
                   :success [:span.pill "Saved"]
                   :deleting [:span.pill "Deleting..."]
                   nil)]
      [ui/entity-detail
       {:title title
        :badge badge
        :meta meta
        :actions [:<>
                  [:button.button.secondary {:type "button"
                                             :on-click #(rf/dispatch [:darelwasl.app/start-new-task])
                                             :disabled saving?}
                   "New task"]
                  [:button.button.secondary {:type "button"
                                             :on-click #(rf/dispatch [:darelwasl.app/reset-detail])
                                             :disabled saving?}
                   "Reset"]]
        :placeholder placeholder
        :content (when-not placeholder
                   (let [{:keys [title description priority assignee due-date tags archived? extended?]} form
                         tag-state @(rf/subscribe [:darelwasl.app/tags])
                         current-assignee (or assignee (:id (first available-assignees)) "")]
                     [:form.detail-form
                      {:on-submit (fn [e]
                                    (.preventDefault e)
                                    (rf/dispatch [:darelwasl.app/save-task]))}
                      (when error
                        [:div.form-error error])
                      [:div.detail-meta
                       [ui/status-chip {:task/status task-status :task/archived? archived?}]
                       [ui/priority-chip priority]
                       (when archived?
                         [:span.chip.status.muted "Archived"])
                       (when extended?
                         [:span.chip.tag "Extended flag"])]
                      [:div.detail-grid
                       [:div.form-group
                        [:label.form-label {:for "task-title"} "Title"]
                        [:input.form-input {:id "task-title"
                                            :type "text"
                                            :value title
                                            :placeholder "Write a concise title"
                                            :on-change #(rf/dispatch [:darelwasl.app/update-detail-field :title (.. % -target -value)])
                                            :disabled saving?}]]
                       [:div.form-group
                        [:label.form-label {:for "task-status"} "Status"]
                        [:select.form-input {:id "task-status"
                                             :value (name (or task-status :todo))
                                             :on-change #(rf/dispatch [:darelwasl.app/update-detail-field :status (util/->keyword-or-nil (.. % -target -value))])
                                             :disabled saving?}
                         (for [{:keys [id label]} state/task-status-options]
                           ^{:key (name id)}
                           [:option {:value (name id)} label])]]
                       [:div.form-group
                        [:label.form-label {:for "task-assignee"} "Assignee"]
                        [:select.form-input {:id "task-assignee"
                                             :value current-assignee
                                             :on-change #(rf/dispatch [:darelwasl.app/update-detail-field :assignee (.. % -target -value)])
                                             :disabled saving?}
                         (for [{:keys [id label]} available-assignees]
                           ^{:key (str "assignee-" id)}
                           [:option {:value id} label])]]
                       [:div.form-group
                        [:label.form-label {:for "task-due"} "Due date"]
                        [:input.form-input {:id "task-due"
                                            :type "date"
                                            :value (or due-date "")
                                            :on-change #(rf/dispatch [:darelwasl.app/update-detail-field :due-date (.. % -target -value)])
                                            :disabled saving?}]]
                       [:div.form-group
                        [:label.form-label {:for "task-priority"} "Priority"]
                        [:select.form-input {:id "task-priority"
                                             :value (name (or priority :medium))
                                             :on-change #(rf/dispatch [:darelwasl.app/update-detail-field :priority (util/->keyword-or-nil (.. % -target -value))])
                                             :disabled saving?}
                         (for [{:keys [id label]} state/task-priority-options]
                           ^{:key (name id)}
                           [:option {:value (name id)} label])]]
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
                          [:span "Archived"]]]]]
                      [:div.form-group
                       [:label.form-label "Tags"]
                       [tag-selector {:selected-tags tags
                                      :tag-state tag-state
                                      :tag-entry (:tag-entry detail)}]]
                      [:div.form-group
                       [:label.form-label {:for "task-description"} "Description"]
                       [:textarea.form-input {:id "task-description"
                                              :rows 5
                                              :value description
                                              :placeholder "Add context, acceptance, and next steps"
                                              :on-change #(rf/dispatch [:darelwasl.app/update-detail-field :description (.. % -target -value)])
                                              :disabled saving?}]]
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
        :footer footer}])))

(defn task-shell []
  [shell/app-shell
   [:main.tasks-layout
    [:div.tasks-column
     [filters-panel]
     [task-list]]
    [:div.tasks-column.narrow
     [task-preview]]]
   [:span "Logged in workspace · Filter and sort tasks from the API."]])
