(ns darelwasl.app
  (:require [re-frame.core :as rf]
            [reagent.dom :as rdom]))

(def default-db
  {:active-view :overview
   :notes [{:id :login
            :title "Login surface"
            :body "Form + error states wired to /api/login with session cookie persistence."}
           {:id :tasks
            :title "Task workspace"
            :body "List with filters/sorts and side-panel detail for create/edit; responsive between desktop grid and mobile stack."}
           {:id :states
            :title "State handling"
            :body "Explicit loading/empty/error/ready states with finite-state model; no implicit globals."}]
   :workspace [{:id :theme
                :title "Theme tokens"
                :body "CSS vars sourced from registries/theme.edn; components avoid hardcoded colors/spacing/fonts."
                :label "Theme ready"}
               {:id :actions
                :title "Action wiring"
                :body "Re-frame events/effects will call backend endpoints once implemented; keep contracts in sync with registries."
                :label "Contracts"}
               {:id :fixtures
                :title "Fixtures + smoke"
                :body "Headless smoke will load fixtures (users/tasks) and cover login + list/detail flows."
                :label "App smoke"}]})

(rf/reg-event-db ::initialize
  (fn [_ _]
    default-db))

(rf/reg-event-db ::set-view
  (fn [db [_ view]]
    (assoc db :active-view view)))

(rf/reg-sub ::active-view (fn [db _] (:active-view db)))
(rf/reg-sub ::notes (fn [db _] (:notes db)))
(rf/reg-sub ::workspace (fn [db _] (:workspace db)))

(def view-options
  [{:id :overview :label "Overview"}
   {:id :tasks :label "Tasks focus"}])

(defn top-bar []
  [:header.top-bar
   [:div
    [:div.brand "Darel Wasl Tasks"]
    [:div.meta "Frontend scaffold · shadow-cljs + re-frame"]]
   [:div.badge
    [:span.badge-dot]
    "Ready to wire"]])

(defn placeholder-card [{:keys [id title body]}]
  ^{:key id}
  [:div.placeholder-card
   [:strong title]
   [:div body]])

(defn notes-panel []
  (let [notes @(rf/subscribe [::notes])]
    [:div.panel
     [:div.section-header
      [:h2 "Shell states"]
      [:div.pill
       [:span.dot]
       "Loading / empty / error / ready"]]
     [:p "Base UI shell for login and task flows. Wire events/effects into backend once available."]
     [:div.button-row
      (let [active @(rf/subscribe [::active-view])]
        (for [{:keys [id label]} view-options]
          ^{:key id}
          [:button.button
           {:type "button"
            :class (when (not= id active) "secondary")
            :on-click #(rf/dispatch [::set-view id])}
           label]))]
     (for [note notes]
       [placeholder-card note])]))

(defn workspace-panel []
  (let [items @(rf/subscribe [::workspace])
        active @(rf/subscribe [::active-view])]
    [:div.panel
     [:div.section-header
      [:h2 "Workspace"]
      [:div.pill
       [:span.dot]
       "Theme + commands"]]
     [:p
      (case active
        :tasks "Focus on task list/detail flows; keep filters/sorts and feature-flagged fields ready."
        "Use the theme tokens and registries to keep UI consistent. Start dev server to iterate quickly.")]
     (for [{:keys [id title body label]} items]
       ^{:key id}
       [:div.placeholder-card
        [:div.workspace-row
         [:strong title]
         (when label
           [:span.badge label])]
        [:div body]])
     [:div.button-row
      [:button.button {:type "button"} "npm run dev"]
      [:button.button.secondary {:type "button"} "npm run build"]]]))

(defn app-root []
  (let [active @(rf/subscribe [::active-view])]
    [:div.app-shell
     [top-bar]
     [:main.main-grid
     [notes-panel]
     [workspace-panel]]
     [:footer.app-footer
      [:span "Active view: " (name active) " · Theme tokens via CSS vars with warm neutrals + teal accent."]]]))

(defn mount-root []
  (when-let [root (.getElementById js/document "app")]
    (rdom/render [app-root] root)))

(defn ^:export init []
  (rf/dispatch-sync [::initialize])
  (mount-root))

(defn reload []
  (rf/clear-subscription-cache!)
  (mount-root))
