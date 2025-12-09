(ns darelwasl.features.home
  (:require [darelwasl.features.tasks :as tasks-ui]
            [darelwasl.ui.components :as ui]
            [darelwasl.ui.shell :as shell]
            [re-frame.core :as rf]))

(defn tag-highlights [tags]
  [:div.tag-highlights
   [:h4 "Tags"]
   (if (seq tags)
     [:div.tags
      (for [t tags]
        ^{:key (str "tag-" (:tag/id t))}
        [ui/tag-chip (:tag/name t)])]
     [:span.meta "No tags yet"])])

(defn status-count-cards
  [{:keys [todo in-progress done]}]
  [ui/stat-group {:cards [{:label "To do" :value (or todo 0)}
                          {:label "In progress" :value (or in-progress 0)}
                          {:label "Done" :value (or done 0)}]}])

(defn home-recent-list
  [tasks]
  (let [tag-index {}]
    [ui/entity-list {:title "Recent tasks"
                     :meta (str (count tasks) " items")
                     :items tasks
                     :status (if (seq tasks) :ready :empty)
                     :error nil
                     :selected nil
                     :key-fn :task/id
                     :render-row (fn [t _] [tasks-ui/task-card t false tag-index])}]))

(defn home-view []
  (let [home @(rf/subscribe [:darelwasl.app/home])
        tags @(rf/subscribe [:darelwasl.app/tags])]
    (when (= :idle (:status home))
      (rf/dispatch [:darelwasl.app/fetch-home]))
    [:div.home
     (case (:status home)
       :loading [ui/home-loading]
       :error [ui/error-state (:error home) #(rf/dispatch [:darelwasl.app/fetch-home])]
       :ready (let [counts (:counts home)
                    recent (:recent home)
                    tag-items (take 6 (:items tags))]
                [:div.home-grid
                 [:div.home-hero
                  [:div
                   [:h2 "Welcome back"]
                   [:p "Quick overview of your workspace."]]
                  [:div.button-row
                   [:button.button {:type "button"
                                    :on-click #(do
                                                 (rf/dispatch [:darelwasl.app/navigate :tasks])
                                                 (rf/dispatch [:darelwasl.app/start-new-task]))}
                    "New task"]]]
                 [status-count-cards counts]
                 [:div.home-section
                  [:h3 "Recent"]
                  (if (seq recent)
                    [home-recent-list recent]
                    [ui/home-empty])]
                 [:div.home-section
                  [tag-highlights tag-items]]])
       [ui/home-empty])]))

(defn home-shell []
  [shell/app-shell
   [:main.home-layout
    [home-view]]
   [:span "Home overview · Quick links into your workspace."]])
