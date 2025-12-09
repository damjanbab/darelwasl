(ns darelwasl.ui.components
  (:require [clojure.string :as str]
            [darelwasl.util :as util]
            [re-frame.core :as rf]))

(defn badge-pill [text & {:keys [tone]}]
  [:div.badge {:class (when (= tone :muted) "muted")}
   [:span.badge-dot]
   text])

(defn chip
  [label & {:keys [class]}]
  [:span.chip {:class class} label])

(defn status-chip
  [{:task/keys [status archived?]}]
  (let [label (cond
                archived? "Archived"
                :else (util/status-label status))
        cls (cond
              archived? "muted"
              (= status :todo) "neutral"
              (= status :in-progress) "warning"
              (= status :done) "success"
              :else "neutral")]
    [chip label :class (str "status " cls)]))

(defn priority-chip [priority]
  (when priority
    [chip (util/priority-label priority) :class (str "priority " (name priority))]))

(defn tag-chip [tag-name]
  [chip (str "#" tag-name) :class "tag"])

(defn assignee-pill [assignee]
  (let [uname (or (:user/username assignee) "")
        initial (if (seq uname) (str/upper-case (subs uname 0 1)) "?")]
    [:div.assignee
     [:div.avatar-circle.sm initial]
     [:div.assignee-text
      [:div.assignee-label "Assignee"]
      [:div.assignee-name (or (:user/name assignee) uname "Unassigned")]]]))

(defn loading-state
  ([] (loading-state "Loading tasks..."))
  ([message]
   [:div.state
    [:div.spinner]
    [:div message]]))

(defn home-loading []
  [:div.state
   [:div.spinner]
   [:div "Loading overview..."]])

(defn error-state
  ([message] (error-state message #(rf/dispatch [:darelwasl.app/fetch-tasks])))
  ([message on-retry]
   [:div.state.error
    [:div.form-error message]
    [:div.button-row
     [:button.button.secondary {:type "button"
                                :on-click on-retry}
      "Try again"]]]))

(defn empty-state
  ([] (empty-state "No tasks match these filters" "Adjust filters to see tasks or refresh to reload."))
  ([title copy]
   [:div.state.empty
    [:strong title]
    [:p copy]]))

(defn land-loading-state [message]
  [:div.state
   [:div.spinner]
   [:div (or message "Loading land data...")]])

(defn land-error-state [message]
  [:div.state.error
   [:div.form-error (or message "Unable to load land registry data.")]
   [:div.button-row
    [:button.button.secondary {:type "button"
                               :on-click #(rf/dispatch [:darelwasl.app/fetch-land])}
     "Refresh land data"]]])

(defn land-empty-state [title copy]
  [:div.state.empty
   [:strong (or title "No land records found")]
   [:p (or copy "Adjust filters or refresh to reload land data.")]
   [:div.button-row
    [:button.button.secondary {:type "button"
                               :on-click #(rf/dispatch [:darelwasl.app/fetch-land])}
     "Refresh"]]])

(defn home-empty []
  [:div.state.empty
   [:strong "No tasks yet"]
   [:p "Create your first task to see summaries here."]])

(defn entity-detail
  "Generic detail shell with header/actions and placeholder handling."
  [{:keys [title badge meta actions error placeholder content footer]}]
  [:div.panel.task-preview
   [:div.section-header
    [:div
     (when badge [badge-pill badge])
     [:h2 title]
     (when meta [:div.meta meta])]
    (when actions
      [:div.actions actions])]
   (cond
     error [:div.form-error error]
     placeholder placeholder
     :else
     [:<>
      content
      (when footer footer)])])
