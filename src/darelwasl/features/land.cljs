(ns darelwasl.features.land
  (:require [clojure.string :as str]
            [darelwasl.ui.components :as ui]
            [darelwasl.ui.entity :as entity]
            [darelwasl.ui.shell :as shell]
            [darelwasl.util :as util]
            [re-frame.core :as rf]))

(def land-completeness-options
  [{:id nil :label "All"}
   {:id :complete :label "Complete shares"}
   {:id :incomplete :label "Incomplete shares"}])

(defn land-stats-cards [{:keys [persons parcels total-area-m2 share-complete share-complete-pct top-owners]}]
  [ui/stat-group {:variant :wide
                  :cards [{:label "Parcels" :value (or parcels 0)}
                          {:label "People" :value (or persons 0)}
                          {:label "Total area (m²)" :value (or (.toLocaleString (or total-area-m2 0) "en-US") 0)}
                          {:label "Share completeness"
                           :value (str (or (some-> share-complete-pct js/Math.round) 0) "%")
                           :meta (str (or share-complete 0) " parcels balanced")}
                          {:label "Top owners by area"
                           :content (if (seq top-owners)
                                      [:ul.mini-list
                                       (for [o top-owners
                                             :let [owner-name (:person/name o)
                                                   area (:person/owned-area-m2 o)]]
                                         ^{:key (str "top-" (:person/id o))}
                                         [:li
                                          [:div owner-name]
                                          [:span.meta (str (js/Math.round (or area 0)) " m²")]])]
                                      [:span.meta "No owners yet"])}]}])

(defn land-people-list []
  (let [{:keys [people status error filters selected pagination]} @(rf/subscribe [:darelwasl.app/land])
        current-search (:people-search filters)
        selected-id (:person selected)
        list-config (entity/list-config :entity.type/person)
        pagination-state (or (:people pagination) {})
        limit (or (:limit pagination-state) (:people-page-size filters) (count people))
        offset (or (:offset pagination-state) 0)
        total (or (:total pagination-state) (count people))
        page (or (:page pagination-state) (:people-page filters) 1)
        pages (or (:pages pagination-state) (max 1 (int (Math/ceil (/ (max total 1) (double (max limit 1)))))))
        current-count (count people)
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
                      :items people
                      :status status
                      :error error
                      :selected selected-id
                      :panel-class "list-panel"
                      :list-class "list"
                      :key-fn (or (:key list-config) :person/id)
                     :header-actions [[:input.form-input {:type "search"
                                                          :placeholder "Search people"
                                                          :value current-search
                                                          :on-change #(rf/dispatch [:darelwasl.app/land-update-filter :people-search (.. % -target -value)])}]
                                      [:select.form-input {:value (or (:people-page-size filters) 25)
                                                           :aria-label "People page size"
                                                           :on-change #(rf/dispatch [:darelwasl.app/set-land-people-page-size (js/parseInt (.. % -target -value) 10)])}
                                       (for [n [25 50 100]]
                                         ^{:key (str "people-page-size-" n)}
                                         [:option {:value n} (str n " rows")])]
                                      [:button.button.secondary {:type "button"
                                                                 :on-click #(rf/dispatch [:darelwasl.app/fetch-land])}
                                       "Search"]]
                      :loading-node [ui/land-loading-state "Loading people..."]
                      :error-node [ui/land-error-state error]
                      :empty-node [ui/land-empty-state "No people match these filters" "Adjust search or refresh to reload."]
                      :render-row (entity/render-row :entity.type/person {:on-select #(rf/dispatch [:darelwasl.app/select-person %])})}]
     [ui/pagination-controls {:limit limit
                              :offset offset
                              :total total
                              :current-count current-count
                              :on-prev #(rf/dispatch [:darelwasl.app/set-land-people-page (dec page)])
                              :on-next #(rf/dispatch [:darelwasl.app/set-land-people-page (inc page)])}]]))

(defn land-person-detail []
  (let [{:keys [selected-person status]} @(rf/subscribe [:darelwasl.app/land])]
    (when-let [component (entity/detail-component :entity.type/person)]
      [component {:selected-person selected-person
                  :status status
                  :on-select-parcel #(rf/dispatch [:darelwasl.app/select-parcel %])}])))

(defn land-parcel-list []
  (let [{:keys [parcels status error filters selected pagination]} @(rf/subscribe [:darelwasl.app/land])
        selected-id (:parcel selected)
        {:keys [parcel-number completeness]} filters
        list-config (entity/list-config :entity.type/parcel)
        pagination-state (or (:parcels pagination) {})
        limit (or (:limit pagination-state) (:parcels-page-size filters) (count parcels))
        offset (or (:offset pagination-state) 0)
        total (or (:total pagination-state) (count parcels))
        page (or (:page pagination-state) (:parcels-page filters) 1)
        pages (or (:pages pagination-state) (max 1 (int (Math/ceil (/ (max total 1) (double (max limit 1)))))))
        current-count (count parcels)
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
                      :items parcels
                      :status status
                      :error error
                      :selected selected-id
                      :panel-class "list-panel"
                      :list-class "list"
                      :key-fn (or (:key list-config) :parcel/id)
                     :header-actions [[:input.form-input {:type "search"
                                                          :placeholder "Parcel number"
                                                          :value parcel-number
                                                          :on-change #(rf/dispatch [:darelwasl.app/land-update-filter :parcel-number (.. % -target -value)])}]
                                      [:select.form-input {:value (or (some-> completeness name) "")
                                                           :on-change #(let [v (.. % -target -value)]
                                                                         (rf/dispatch [:darelwasl.app/land-update-filter :completeness (when-not (str/blank? v) (keyword v))]))}
                                       (for [{:keys [id label]} land-completeness-options]
                                         ^{:key (str "comp-" (or (some-> id name) "all"))}
                                         [:option {:value (or (some-> id name) "")} label])]
                                      [:select.form-input {:value (or (:parcels-page-size filters) 25)
                                                           :aria-label "Parcels page size"
                                                           :on-change #(rf/dispatch [:darelwasl.app/set-land-parcels-page-size (js/parseInt (.. % -target -value) 10)])}
                                       (for [n [25 50 100]]
                                         ^{:key (str "parcels-page-size-" n)}
                                         [:option {:value n} (str n " rows")])]
                                      [:button.button.secondary {:type "button"
                                                                 :on-click #(rf/dispatch [:darelwasl.app/fetch-land])}
                                       "Apply"]]
                      :loading-node [ui/land-loading-state "Loading parcels..."]
                      :error-node [ui/land-error-state error]
                      :empty-node [ui/land-empty-state "No parcels match these filters" "Adjust parcel filters or refresh."]
                      :render-row (entity/render-row :entity.type/parcel {:on-select #(rf/dispatch [:darelwasl.app/select-parcel %])})}]
     [ui/pagination-controls {:limit limit
                              :offset offset
                              :total total
                              :current-count current-count
                              :on-prev #(rf/dispatch [:darelwasl.app/set-land-parcels-page (dec page)])
                              :on-next #(rf/dispatch [:darelwasl.app/set-land-parcels-page (inc page)])}]]))

(defn land-parcel-detail []
  (let [{:keys [selected-parcel status]} @(rf/subscribe [:darelwasl.app/land])]
    (when-let [component (entity/detail-component :entity.type/parcel)]
      [component {:selected-parcel selected-parcel
                  :status status
                  :on-select-person #(rf/dispatch [:darelwasl.app/select-person %])}])))

(defn land-view []
  (let [{:keys [status stats]} @(rf/subscribe [:darelwasl.app/land])]
    (when (= status :pending)
      (rf/dispatch [:darelwasl.app/fetch-land]))
    [:div.home
     [:div.section-header
      [:div
       [:h2 "Land registry"]
       [:p "Browse people, parcels, and ownership shares."]]]
     (when stats
       [land-stats-cards stats])
     [:div.land-layout
      [:div.land-column
       [land-people-list]]
      [:div.land-column
       [land-parcel-list]]
      [:div.land-column.narrow
       [land-person-detail]]
      [:div.land-column.narrow
       [land-parcel-detail]]]]))

(defn land-shell []
  [shell/app-shell
   [:main.home-layout
    [land-view]]
   [:span "Land registry · People ↔ parcels with summary stats."]])
