(ns darelwasl.features.tasks
  (:require [clojure.string :as str]
            [darelwasl.state :as state]
            [darelwasl.ui.components :as ui]
            [darelwasl.ui.entity :as entity]
            [darelwasl.ui.shell :as shell]
            [darelwasl.util :as util]
            [re-frame.core :as rf]
            [reagent.core :as r]))

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

(defn quick-filter-chips
  [{:keys [status archived]}]
  (let [statuses [{:id nil :label "All"}
                  {:id :todo :label "To do"}
                  {:id :in-progress :label "In progress"}
                  {:id :done :label "Done"}]]
    (concat
     (map (fn [{:keys [id label]}]
            {:label label
             :active? (= status id)
             :on-click #(rf/dispatch [:darelwasl.app/update-filter :status id])})
          statuses)
     [{:label (case archived
                true "Archived"
                :all "All"
                "Active")
       :active? (not= archived false)
       :on-click #(rf/dispatch [:darelwasl.app/toggle-archived])}])))

(defn more-filters-drawer
  [{:keys [open? on-close]}]
  (let [{:keys [filters status assignees]} @(rf/subscribe [:darelwasl.app/tasks])
        tasks-status status
        {:keys [items]} @(rf/subscribe [:darelwasl.app/tags])
        {:keys [status priority tag assignee archived sort order page-size]} filters
        available-assignees (if (seq assignees) assignees state/fallback-assignees)
        tag-options (cons {:id nil :label "All tags"}
                          (map (fn [t] {:id (str (:tag/id t)) :label (:tag/name t)}) items))
        loading? (= tasks-status :loading)]
    [:div.more-filters {:class (when open? "open")}
     [:div.more-filters__backdrop {:on-click on-close}]
     [:div.more-filters__panel
      [:div.more-filters__header
       [:div
        [:h4 "Filters & sort"]
        [:p "Tuck away the advanced filters; keep the main bar minimal."]]
       [:button.icon-button {:type "button"
                             :aria-label "Close filters"
                             :on-click on-close}
        "×"]]
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
                       :on-change #(rf/dispatch [:darelwasl.app/update-filter :assignee (when-not (str/blank? %) %)])}]
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
           [:option {:value n} (str n " rows")])]]]
      [:div.more-filters__footer
       [ui/button {:variant :secondary
                   :type "button"
                   :disabled loading?
                   :on-click #(rf/dispatch [:darelwasl.app/clear-filters])}
        "Reset"]
       [ui/button {:type "button"
                   :disabled loading?
                   :on-click (fn []
                               (rf/dispatch [:darelwasl.app/fetch-tasks])
                               (on-close))}
        "Apply"]]]]))

(defn task-list []
  (r/with-let [filters-open? (r/atom false)]
    (let [tasks-state @(rf/subscribe [:darelwasl.app/tasks])
          {:keys [items status error selected pagination]} tasks-state
          tag-state @(rf/subscribe [:darelwasl.app/tags])
          tag-index (into {} (map (fn [t] [(:tag/id t) (:tag/name t)]) (:items tag-state)))
          list-config (entity/list-config :entity.type/task)
          filters (:filters tasks-state)
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
      [:div.task-pane
       [:div.tasks-hero
        [:div
         [:h2 "Tasks"]
         [:p "List-first, tap-first. Quick filters up top, everything else tucked away."]]
        [:div.button-row
         [ui/button {:variant :secondary
                     :on-click #(reset! filters-open? true)}
          "Filters"]
         [ui/button {:on-click #(rf/dispatch [:darelwasl.app/start-new-task])}
          "New task"]]]
       [ui/entity-list {:title (:title list-config)
                        :meta meta-str
                        :items items
                        :status status
                        :error error
                        :selected selected
                        :chips (quick-filter-chips filters)
                        :header-actions [[:button.button.secondary {:type "button"
                                                                    :on-click #(rf/dispatch [:darelwasl.app/fetch-tasks])
                                                                    :disabled (= status :loading)}
                                          "Refresh"]]
                        :key-fn (or (:key list-config) :task/id)
                        :render-row (entity/render-row :entity.type/task {:tag-index tag-index
                                                                          :on-select #(rf/dispatch [:darelwasl.app/select-task (:task/id %)])})}]
       [ui/pagination-controls {:limit limit
                                :offset offset
                                :total total
                                :current-count current-count
                                :on-prev #(rf/dispatch [:darelwasl.app/set-task-page (dec page)])
                                :on-next #(rf/dispatch [:darelwasl.app/set-task-page (inc page)])}]
       [more-filters-drawer {:open? @filters-open?
                             :on-close #(reset! filters-open? false)}]])))

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
          close-sheet! #(do
                          (rf/dispatch [:darelwasl.app/reset-detail])
                          (rf/dispatch [:darelwasl.app/select-task nil]))]
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
                     nil)
            sheet-open? (or create? (some? task))]
        [:div.detail-shell {:class (when sheet-open? "open")}
         [:div.detail-sheet__backdrop {:on-click close-sheet!}]
         [:div.detail-sheet__panel
          [ui/entity-detail
           {:title title
            :badge badge
            :meta meta
            :actions [:div.detail-header-actions
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
                             current-assignee (or assignee (:id (first available-assignees)) "")]
                         [:form.detail-form
                          {:on-submit (fn [e]
                                        (.preventDefault e)
                                        (rf/dispatch [:darelwasl.app/save-task]))}
                          [:div.detail-header-row
                          [:div.detail-meta
                           [ui/status-chip {:task/status task-status :task/archived? archived?}]
                           [ui/priority-chip priority]
                           (when archived?
                             [:span.chip.status.muted "Archived"])
                           (when extended?
                              [:span.chip.tag "Extended flag"])
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
            :footer footer}]]]))))

(defn task-shell []
  [shell/app-shell
   [:main.tasks-layout
    [:div.tasks-column
     [task-list]]
    [:div.tasks-column.narrow
     [task-preview]]]
   [:span "Logged in workspace · Filter and sort tasks from the API."]])
