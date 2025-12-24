;; PR overview UI.
(ns darelwasl.features.prs
  (:require [darelwasl.ui.components :as ui]
            [darelwasl.ui.shell :as shell]
            [darelwasl.util :as util]
            [re-frame.core :as rf]))

(defn- state-label
  [state merged-at]
  (cond
    (and merged-at (seq (str merged-at))) "Merged"
    (= state :open) "Open"
    (= state :closed) "Closed"
    :else "Unknown"))

(defn- state-class
  [state merged-at]
  (cond
    (and merged-at (seq (str merged-at))) "success"
    (= state :open) "active"
    (= state :closed) "muted"
    :else "neutral"))

(defn- filter-chips
  [current]
  (for [[id label] [["open" "Open"] ["closed" "Closed"] ["all" "All"]]]
    {:label label
     :active? (= current id)
     :on-click #(do
                  (rf/dispatch [:darelwasl.app/set-pr-state id])
                  (rf/dispatch [:darelwasl.app/fetch-prs]))}))

(defn- pr-row
  [pr selected?]
  (let [number (:pr/number pr)
        title (:pr/title pr)
        author (:pr/author pr)
        updated (util/format-date (:pr/updated-at pr))
        meta (str "#" number " · " (or author "unknown"))
        trailing (str (state-label (:pr/state pr) (:pr/merged-at pr))
                      (when updated (str " · " updated)))]
    [ui/list-row {:title (or title "Untitled PR")
                  :meta meta
                  :trailing trailing
                  :selected? selected?
                  :on-click #(rf/dispatch [:darelwasl.app/select-pr number])}]))

(defn- list-panel
  []
  (let [{:keys [items status error filters selected]} @(rf/subscribe [:darelwasl.app/prs])]
    [:div.panel.prs-list
     [:div.section-header
      [:div
       [:h2 "Pull Requests"]
       [:span.meta (str (count items) " items")]]
      [:div.controls
       [ui/button {:variant :secondary
                   :disabled (= status :loading)
                   :on-click #(rf/dispatch [:darelwasl.app/fetch-prs])}
        "Refresh"]]]
     [ui/chip-bar {:chips (filter-chips (:state filters))}]
     (case status
       :loading [ui/loading-state "Loading pull requests..."]
       :error [ui/error-state error #(rf/dispatch [:darelwasl.app/fetch-prs])]
       (if (seq items)
         [:div.prs-list-items
          (for [pr items]
            ^{:key (str (:pr/number pr))}
            [pr-row pr (= (:pr/number pr) selected)])]
         [ui/empty-state "No pull requests" "Update the state filter or refresh."]))]))

(defn- commit-row
  [commit]
  (let [sha (:commit/sha commit)
        short-sha (when (seq sha) (subs sha 0 7))
        message (:commit/message commit)
        author (:commit/author commit)
        date (util/format-date (:commit/date commit))]
    [:div.prs-commit
     [:div.prs-commit__title (or message "Commit")]
     [:div.meta
      [:span (or short-sha "—")]
      [:span (or author "unknown")]
      (when date [:span date])]]))

(defn- detail-panel
  []
  (let [pr @(rf/subscribe [:darelwasl.app/selected-pr])]
    [:div.panel.prs-detail
     [:div.section-header
      [:div
       [:h2 "Details"]
       [:span.meta "Branch + commits"]]]
     (if-not pr
       [:div.state.empty
        [:strong "Select a PR"]
        [:p "Choose a pull request to see details and commits."]]
       (let [state (:pr/state pr)
             merged-at (:pr/merged-at pr)
             commits (:pr/commits pr)]
         [:div.prs-detail-body
          [:div.prs-detail-header
           [:div
            [:h3 (or (:pr/title pr) "Untitled PR")]
            [:div.meta (str "#" (:pr/number pr) " · "
                            (or (:pr/author pr) "unknown") " · "
                            (state-label state merged-at))]]
           [:div.controls
            [:a.button.secondary {:href (:pr/url pr)
                                  :target "_blank"
                                  :rel "noreferrer"}
             "Open on GitHub"]]]
          [:div.prs-detail-meta
           [:div.meta-row
            [:span.meta-label "Branches"]
            [:span.meta-value (str (or (:pr/head-ref pr) "—")
                                   " → "
                                   (or (:pr/base-ref pr) "—"))]]
           [:div.meta-row
            [:span.meta-label "State"]
            [:span.meta-value {:class (str "status-chip " (state-class state merged-at))}
             (state-label state merged-at)]]
           [:div.meta-row
            [:span.meta-label "Updated"]
            [:span.meta-value (or (util/format-date (:pr/updated-at pr)) "—")]]]
          [:div.prs-commits
           [:h4 "Commits"]
           (if (seq commits)
             (for [c commits]
               ^{:key (str (:commit/sha c))}
               [commit-row c])
             [:div.meta "No commits returned."])] ]))]))

(defn prs-shell
  []
  [shell/app-shell
   [:main.prs-layout
    [:div.prs-grid
     [list-panel]
     [detail-panel]]]
   [:span "PR overview"]])
