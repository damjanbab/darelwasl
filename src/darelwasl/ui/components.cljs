(ns darelwasl.ui.components
  (:require [clojure.string :as str]
            [darelwasl.util :as util]
            [re-frame.core :as rf]))

(defn form-input
  "Standard text input wrapper."
  [{:keys [class] :as attrs}]
  [:input.form-input (-> attrs
                         (dissoc :class)
                         (assoc :class (str class)))])

(defn select-field
  "Standard select wrapper."
  [{:keys [class] :as attrs} & children]
  (into [:select.form-input (-> attrs
                                (dissoc :class)
                                (assoc :class (str class)))]
        children))

(defn button
  "Button with variant support (:primary default, :secondary, :danger)."
  [{:keys [variant class type] :as attrs} & children]
  (let [variant-class (case variant
                        :secondary "button secondary"
                        :danger "button danger"
                        "button")]
    (into [:button (-> attrs
                       (assoc :type (or type "button"))
                       (dissoc :variant :class)
                       (assoc :class (str variant-class
                                          (when (and class (not (str/blank? class))) (str " " class)))))]
          children)))

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
              (= status :pending) "danger"
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

(defn chip-bar
  "Simple chip bar for filters/actions. chips is a seq of {:label :active? :on-click}."
  [{:keys [chips]}]
  (when (seq chips)
    [:div.chip-bar
     (for [{:keys [label active? on-click]} chips
           :when label]
       ^{:key (str "chip-" label)}
       [:button.button.secondary {:type "button"
                                  :class (when active? "active")
                                  :on-click on-click}
        label])]))

(defn entity-list
  "Generic list panel with states. render-row receives (item selected?)."
  [{:keys [title meta items status error selected render-row key-fn panel-class list-class header-actions chips loading-node error-node empty-node]}]
  (let [items (let [base (or items [])]
                (if (sequential? base) base []))
        key-fn (or key-fn :task/id)
        panel-class (or panel-class "task-list-panel")
        list-class (or list-class "task-list")
        loading-node (or loading-node [loading-state])
        error-node (or error-node [error-state error])
        empty-node (or empty-node [empty-state])]
   [:div.panel {:class panel-class}
    [:div.section-header
     [:div
      [:h2 title]
       (when meta [:span.meta meta])]
     (when (seq header-actions)
       (into [:div.controls] header-actions))]
    [chip-bar {:chips chips}]
    (case status
      :loading loading-node
      :error error-node
      (if (seq items)
         [:div {:class list-class}
          (map-indexed (fn [idx t]
                         (let [k (try
                                   (key-fn t)
                                   (catch :default _ (str "idx-" idx)))]
                           ^{:key (str (or k (str "idx-" idx)))}
                           [render-row t (= selected k)]))
                       items)]
         empty-node))]))

(defn list-row
  [{:keys [title meta selected? on-click trailing description]}]
  [:button.list-row {:type "button"
                     :class (when selected? "selected")
                     :on-click on-click}
   [:div
    [:div.title title]
    (when meta [:div.meta meta])
    (when description [:div.meta description])]
   (when trailing [:div.meta trailing])])

(defn pagination-controls
  [{:keys [limit offset total current-count on-prev on-next]}]
  (let [limit (max 1 (or limit 25))
        offset (max 0 (or offset 0))
        current (max 0 (or current-count 0))
        total (or total (+ offset current))
        start (if (pos? current) (inc offset) 0)
        end (if (pos? current) (+ offset current) offset)
        prev-disabled (<= offset 0)
        next-disabled (>= end total)]
    [:div.pagination
     [:span.meta (str "Showing "
                      (if (zero? total) 0 start)
                      (when (pos? total) (str "–" end))
                      " of "
                      total)]
     [:div.button-row
      [:button.button.secondary {:type "button"
                                 :disabled prev-disabled
                                 :on-click #(when (and on-prev (not prev-disabled))
                                              (on-prev))}
       "Prev"]
      [:button.button.secondary {:type "button"
                                 :disabled next-disabled
                                 :on-click #(when (and on-next (not next-disabled))
                                              (on-next))}
       "Next"]]]))

(defn tag-highlights
  "Render tag chips with fallback copy."
  [tags]
  [:div.tag-highlights
   [:h4 "Tags"]
   (if (seq tags)
     [:div.tags
      (for [t tags]
        ^{:key (str "tag-" (:tag/id t))}
        [tag-chip (:tag/name t)])]
     [:span.meta "No tags yet"])])

(defn stat-card
  [{:keys [label value meta content tone class]}]
  [:div.card {:class (str (when class class) " " (when tone (name tone)))}
   [:div.card-label label]
   (when value [:div.card-value value])
   (when content content)
   (when meta [:div.meta meta])])

(defn stat-group
  "Render a group of stat cards. variant can be :wide or :narrow to reuse existing layout classes."
  [{:keys [cards variant class]}]
  (let [variant-class (case variant
                        :wide "wide"
                        :narrow "narrow"
                        nil)]
    [:div.summary-cards {:class (str variant-class " " class)}
     (for [{:keys [label] :as card} cards
           :when label]
       ^{:key (str label)}
       [stat-card card])]))

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

(defn task-card
  [{:task/keys [id title description status priority tags due-date updated-at assignee archived?] :as task}
   selected?
   tag-index
   {:keys [on-select]}]
  (let [tag-list (or tags [])
        on-select (or on-select #(rf/dispatch [:darelwasl.app/select-task id]))
        tag-names (map (fn [t] (or (:tag/name t) (get tag-index (:tag/id t)) "Tag")) tag-list)]
    [:div.task-card {:class (when selected? "selected")
                     :on-click on-select}
     [:div.task-card__header
      [:div
       [:div.title-row
        [:h3 title]
        [status-chip {:task/status status :task/archived? archived?}]]
       [:div.meta-row
        (when due-date
          [:span.meta (str "Due " (util/format-date due-date))])
        (when updated-at
          [:span.meta (str "Updated " (util/format-date updated-at))])]]
      [priority-chip priority]]
     [:p.description (or (util/truncate description 140) "No description")]
     [:div.task-card__footer
      [assignee-pill assignee]
      [:div.tags
       (for [tag tag-names]
         ^{:key (str tag)}
         [:span.tag-chip tag])]]]))

(defn land-person-detail-view
  [{:keys [selected-person status on-select-parcel]}]
  [:div.panel.task-preview
   [:div.section-header
    [:div
     [:h3 "Person detail"]
     [:div.meta "Parcels and shares"]]]
   (cond
     (= status :loading) [land-loading-state "Loading person detail..."]
      (nil? selected-person)
      [:div.placeholder-card
       [:strong "Select a person"]
       [:p "Choose a person to see owned parcels."]]
      :else
      (let [{:keys [person/name person/address person/ownerships person/parcel-count person/owned-area-m2]} selected-person
            ownerships (sort-by (fn [o] (- (or (:ownership/area-share-m2 o) 0))) ownerships)
            ownership-cards (for [o ownerships
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
                                                          :on-click #(when on-select-parcel (on-select-parcel pid))}
                                "View parcel"]])]
        [:div
         [:div.detail-meta
          [:h4 name]
          [:div.meta address]
          [:div.chip-row
           [:span.chip (str "Parcels: " (or parcel-count 0))]
           [:span.chip (str "Owned area: " (util/format-area owned-area-m2) " m²")]]]
         (if (seq ownership-cards)
           [:<>
            [stat-group {:variant :narrow
                         :cards [{:label "Parcels" :value (or parcel-count 0)}
                                 {:label "Owned area" :value (str (util/format-area owned-area-m2) " m²")}
                                 {:label "Holdings" :value (str (count ownerships) " shares")}]}]
            [:div {:style {:display "grid"
                           :gridTemplateColumns "repeat(auto-fill,minmax(260px,1fr))"
                           :gap "12px"}}
             ownership-cards]]
           [:div.meta "No parcels attached"])]))])

(defn land-parcel-detail-view
  [{:keys [selected-parcel status on-select-person]}]
  [:div.panel.task-preview
   [:div.section-header
    [:div
     [:h3 "Parcel detail"]
     [:div.meta "Owners and shares"]]]
   (cond
     (= status :loading) [land-loading-state "Loading parcel detail..."]
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
        [stat-group {:variant :narrow
                     :cards [{:label "Owners" :value (or (count owners) 0)}
                             {:label "Parcel area" :value (str (util/format-area area) " m²")}
                             {:label "Share total"
                              :value (if share-total (util/pct share-total) "—")
                              :meta "Expected 100% ± tolerance"}]}]
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
                                         :on-click #(when on-select-person (on-select-person (:person/id o)))}
               "View person"]])]
          [:div.meta "No owners recorded"])]))])
