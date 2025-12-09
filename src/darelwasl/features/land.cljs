(ns darelwasl.features.land
  (:require [clojure.string :as str]
            [darelwasl.ui.components :as ui]
            [darelwasl.ui.shell :as shell]
            [darelwasl.util :as util]
            [re-frame.core :as rf]))

(def land-completeness-options
  [{:id nil :label "All"}
   {:id :complete :label "Complete shares"}
   {:id :incomplete :label "Incomplete shares"}])

(defn land-stats-cards [{:keys [persons parcels total-area-m2 share-complete share-complete-pct top-owners]}]
  [:div.summary-cards.wide
   [:div.card
    [:div.card-label "Parcels"]
    [:div.card-value (or parcels 0)]]
   [:div.card
    [:div.card-label "People"]
    [:div.card-value (or persons 0)]]
   [:div.card
    [:div.card-label "Total area (m²)"]
    [:div.card-value (or (.toLocaleString (or total-area-m2 0) "en-US") 0)]]
   [:div.card
    [:div.card-label "Share completeness"]
    [:div.card-value (str (or (some-> share-complete-pct js/Math.round) 0) "%")]
    [:div.meta (str (or share-complete 0) " parcels balanced")]]
   [:div.card
    [:div.card-label "Top owners by area"]
    (if (seq top-owners)
      [:ul.mini-list
       (for [o top-owners
             :let [owner-name (:person/name o)
                   area (:person/owned-area-m2 o)]]
         ^{:key (str "top-" (:person/id o))}
         [:li
          [:div owner-name]
          [:span.meta (str (js/Math.round (or area 0)) " m²")]])]
      [:span.meta "No owners yet"])]])

(defn land-people-list []
  (let [{:keys [people status error filters selected]} @(rf/subscribe [:darelwasl.app/land])
        current-search (:people-search filters)
        selected-id (:person selected)]
    [:div.panel
     [:div.section-header
      [:div
       [:h3 "People"]
       [:div.meta "Browse owners and their parcels"]]
      [:div.controls
       [:input.form-input {:type "search"
                           :placeholder "Search people"
                           :value current-search
                           :on-change #(rf/dispatch [:darelwasl.app/land-update-filter :people-search (.. % -target -value)])}]
       [:button.button.secondary {:type "button"
                                  :on-click #(rf/dispatch [:darelwasl.app/fetch-land])}
        "Search"]]]
     (case status
       :loading [ui/land-loading-state "Loading people..."]
       :error [ui/land-error-state error]
       (if (seq people)
         [:div.list
          (for [p people]
            (let [pid (:person/id p)
                  selected? (= selected-id pid)]
              ^{:key (str pid)}
              [:button.list-row {:type "button"
                                 :class (when selected? "selected")
                                 :on-click #(rf/dispatch [:darelwasl.app/select-person pid])}
               [:div
                [:div.title (:person/name p)]
                [:div.meta (:person/address p)]]
               [:div.meta (str (or (:person/parcel-count p) 0) " parcels · "
                               (js/Math.round (or (:person/owned-area-m2 p) 0)) " m²")]]))]
         [ui/land-empty-state "No people match these filters" "Adjust search or refresh to reload."]))]))

(defn land-person-detail []
  (let [{:keys [selected-person status]} @(rf/subscribe [:darelwasl.app/land])]
    [:div.panel.task-preview
     [:div.section-header
      [:div
       [:h3 "Person detail"]
       [:div.meta "Parcels and shares"]]]
     (cond
       (= status :loading) [ui/land-loading-state "Loading person detail..."]
       (nil? selected-person) [:div.placeholder-card
                               [:strong "Select a person"]
                               [:p "Choose a person to see owned parcels."]]
       :else
       (let [{:keys [person/name person/address person/ownerships person/parcel-count person/owned-area-m2]} selected-person
             ownerships (sort-by (fn [o] (- (or (:ownership/area-share-m2 o) 0))) ownerships)]
         [:div
          [:div.detail-meta
           [:h4 name]
           [:div.meta address]
           [:div.chip-row
            [:span.chip (str "Parcels: " (or parcel-count 0))]
            [:span.chip (str "Owned area: " (util/format-area owned-area-m2) " m²")]]]
          (if (seq ownerships)
            [:<>
             [:div.summary-cards.narrow
              [:div.card
               [:div.card-label "Parcels"]
               [:div.card-value (or parcel-count 0)]]
              [:div.card
               [:div.card-label "Owned area"]
               [:div.card-value (str (util/format-area owned-area-m2) " m²")]]
              [:div.card
               [:div.card-label "Holdings"]
               [:div.card-value (str (count ownerships) " shares")]]]
             [:div {:style {:display "grid"
                            :gridTemplateColumns "repeat(auto-fill,minmax(260px,1fr))"
                            :gap "12px"}}
              (for [o ownerships
                    :let [pid (:parcel/id o)
                          share-total (:parcel/share-total o)
                          complete? (and share-total (< (js/Math.abs (- share-total 1.0)) 1e-6))]]
                ^{:key (str pid "-" (:ownership/share o))}
                [:div.card
                 [:div.card-label (util/parcel-title o)]
                 [:div.card-value (or (:parcel/cadastral-name o) (:parcel/address o) "")]
                 (when (:parcel/address o) [:div.meta (:parcel/address o)])
                 [:div.meta (str "Book " (or (:parcel/book-number o) "–"))]
                 [:div.chip {:class (if complete? "status success" "status warning")}
                  (if complete? "Complete" "Incomplete")]
                 [:div.card-row
                  [:span "Share"]
                  [:strong (util/pct (:ownership/share o))]]
                 [:div.card-row
                  [:span "Area"]
                  [:strong (str (util/format-area (:ownership/area-share-m2 o)) " m²")]]
                 [:button.button.secondary {:type "button"
                                            :on-click #(rf/dispatch [:darelwasl.app/select-parcel pid])}
                  "View parcel"]])]]
            [:div.meta "No parcels attached"])]))]))

(defn land-parcel-list []
  (let [{:keys [parcels status error filters selected]} @(rf/subscribe [:darelwasl.app/land])
        selected-id (:parcel selected)]
    [:div.panel
     [:div.section-header
      [:div
       [:h3 "Parcels"]
       [:div.meta "Cadastral lots and owners"]]
      [:div.controls
       [:input.form-input {:type "search"
                           :placeholder "Parcel number"
                           :value (:parcel-number filters)
                           :on-change #(rf/dispatch [:darelwasl.app/land-update-filter :parcel-number (.. % -target -value)])}]
       [:select.form-input {:value (or (some-> (:completeness filters) name) "")
                            :on-change #(let [v (.. % -target -value)]
                                          (rf/dispatch [:darelwasl.app/land-update-filter :completeness (when-not (str/blank? v) (keyword v))]))}
        (for [{:keys [id label]} land-completeness-options]
          ^{:key (str "comp-" (or (some-> id name) "all"))}
          [:option {:value (or (some-> id name) "")} label])]
       [:button.button.secondary {:type "button"
                                  :on-click #(rf/dispatch [:darelwasl.app/fetch-land])}
        "Apply"]]]
     (case status
       :loading [ui/land-loading-state "Loading parcels..."]
       :error [ui/land-error-state error]
       (if (seq parcels)
         [:div.list
          (for [p parcels]
            (let [pid (:parcel/id p)
                  selected? (= selected-id pid)
                  complete? (< (js/Math.abs (- (:parcel/share-total p 0.0) 1.0)) 1e-6)]
              ^{:key (str pid)}
              [:button.list-row {:type "button"
                                 :class (str (when selected? "selected") (when (not complete?) " warn"))
                                 :on-click #(rf/dispatch [:darelwasl.app/select-parcel pid])}
               [:div
                [:div.title (str (:parcel/cadastral-id p) "/" (:parcel/number p))]
                [:div.meta (:parcel/address p)]]
               [:div.meta (str (or (:parcel/owner-count p) 0) " owners · "
                               (js/Math.round (or (:parcel/area-m2 p) 0)) " m²")]
               [:div.meta (if complete? "Complete" "Incomplete")]]))]
         [ui/land-empty-state "No parcels match these filters" "Adjust parcel filters or refresh."]))]))

(defn land-parcel-detail []
  (let [{:keys [selected-parcel status]} @(rf/subscribe [:darelwasl.app/land])]
    [:div.panel.task-preview
     [:div.section-header
      [:div
       [:h3 "Parcel detail"]
       [:div.meta "Owners and shares"]]]
     (cond
       (= status :loading) [ui/land-loading-state "Loading parcel detail..."]
       (nil? selected-parcel) [:div.placeholder-card
                               [:strong "Select a parcel"]
                               [:p "Choose a parcel to see ownership."]]
       :else
       (let [cadastral-id (:parcel/cadastral-id selected-parcel)
             cadastral-name (:parcel/cadastral-name selected-parcel)
             number (:parcel/number selected-parcel)
             address (:parcel/address selected-parcel)
             book (:parcel/book-number selected-parcel)
             area (:parcel/area-m2 selected-parcel)
             owners (:parcel/owners selected-parcel)
             share-total (:parcel/share-total selected-parcel)
             complete? (and share-total (< (js/Math.abs (- share-total 1.0)) 1e-6))
             owners-sorted (sort-by (fn [o]
                                      [(or (:ownership/list-order o) 1e9)
                                       (or (:ownership/position-in-list o) 1e9)
                                       (- (or (:ownership/share o) 0))])
                                    owners)]
         [:div
          [:div.detail-meta
           [:h4 (str cadastral-id "/" number)]
           [:div.meta (str (or cadastral-name "") (when (and cadastral-name address) " · ") (or address ""))]
           [:div.meta (str "Book " (or book "–"))]
           [:div.chip {:class (if complete? "status success" "status warning")}
            (if complete? "Complete shares" "Incomplete shares")]]
          [:div.summary-cards.narrow
           [:div.card
            [:div.card-label "Owners"]
            [:div.card-value (or (count owners) 0)]]
           [:div.card
            [:div.card-label "Parcel area"]
            [:div.card-value (str (util/format-area area) " m²")]]
           [:div.card
            [:div.card-label "Share total"]
            [:div.card-value (if share-total (util/pct share-total) "—")]
            [:div.meta "Expected 100% ± tolerance"]]]
          (if (seq owners-sorted)
            [:div {:style {:display "grid"
                           :gridTemplateColumns "repeat(auto-fill,minmax(260px,1fr))"
                           :gap "12px"}}
             (for [o owners-sorted]
               ^{:key (str (:person/id o) "-" (:ownership/share o))}
               [:div.card
                [:div.card-label (util/person-title o)]
                [:div.card-value (or (:person/address o) "")]
                [:div.chip-row
                 (when (:ownership/list-order o) [:span.chip (str "List " (:ownership/list-order o))])
                 (when (:ownership/position-in-list o) [:span.chip (str "Pos " (:ownership/position-in-list o))])]
                [:div.card-row
                 [:span "Share"]
                 [:strong (util/pct (:ownership/share o))]]
                [:div.card-row
                 [:span "Area"]
                 [:strong (str (util/format-area (:ownership/area-share-m2 o)) " m²")]]
                [:button.button.secondary {:type "button"
                                           :on-click #(rf/dispatch [:darelwasl.app/select-person (:person/id o)])}
                 "View person"]])]
            [:div.meta "No owners recorded"])]))]))

(defn land-view []
  (let [{:keys [status stats]} @(rf/subscribe [:darelwasl.app/land])]
    (when (= status :idle)
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
