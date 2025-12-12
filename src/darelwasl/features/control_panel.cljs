(ns darelwasl.features.control-panel
  (:require [clojure.string :as str]
            [goog.string :as gstring]
            [darelwasl.ui.components :as ui]
            [darelwasl.ui.shell :as shell]
            [re-frame.core :as rf]))

(defn- ref-id
  [entry kw]
  (cond
    (map? entry) (get entry kw)
    (vector? entry) (second entry)
    :else entry))

(defn- block-type-label [t]
  (if t
    (str/capitalize (name t))
    "Unknown"))

(defn- visibility-pill [visible?]
  [ui/badge-pill (if (false? visible?) "Hidden" "Visible")
   :tone (when (false? visible?) :muted)])

(defn- page-row
  [page selected?]
  (let [title (or (:content.page/title page) "Untitled page")
        path (or (:content.page/path page) "—")
        block-count (count (:content.page/blocks page))]
    [ui/list-row {:title title
                  :meta path
                  :description (str block-count " block" (when (not= block-count 1) "s"))
                  :selected? selected?
                  :on-click #(rf/dispatch [:darelwasl.app/select-page (:content.page/id page)])
                  :trailing [visibility-pill (:content.page/visible? page)]}]))

(defn- block-row
  [block selected? tag-index]
  (let [title (or (:content.block/title block) (block-type-label (:content.block/type block)))
        slug (or (:content.block/slug block) "—")
        tags (map #(get tag-index (ref-id % :content.tag/id)) (:content.block/tag block))
        tag-label (when (seq tags) (str (count tags) " tag" (when (not= 1 (count tags)) "s")))]
    [ui/list-row {:title title
                  :meta (str (block-type-label (:content.block/type block)) " · " slug)
                  :description tag-label
                  :selected? selected?
                  :on-click #(rf/dispatch [:darelwasl.app/select-block (:content.block/id block)])
                  :trailing [visibility-pill (:content.block/visible? block)]}]))

(defn- tag-row
  [tag selected?]
  (let [name (or (:content.tag/name tag) "Untitled tag")
        slug (or (:content.tag/slug tag) "—")]
    [ui/list-row {:title name
                  :meta (str "#" slug)
                  :selected? selected?
                  :on-click #(rf/dispatch [:darelwasl.app/select-tag (:content.tag/id tag)])}]))

(defn- tag-picker
  [selected-tag-ids tags toggle-evt]
  [:div.field-group
   [:div.meta "Tags"]
   (if (seq tags)
     [:div.tags
      (for [tag tags]
        (let [tid (:content.tag/id tag)
              checked? (contains? selected-tag-ids tid)]
          ^{:key (str "tag-opt-" tid)}
          [:label.checkbox
           [:input {:type "checkbox"
                    :checked checked?
                    :aria-label (str "Toggle tag " (or (:content.tag/name tag) tid))
                    :on-change #(rf/dispatch [toggle-evt tid])}]
           [:span (or (:content.tag/name tag) "Tag")]]))]
     [:div.meta "No tags yet"])])

(defn- page-form
  [{:keys [form mode status error]} pages tags]
  (let [saving? (= status :saving)
        deleting? (= status :deleting)
        selected-tags (or (:tags form) #{})
        nav (:navigation-order form)]
    [:div.panel.detail-panel
     [:div.section-header
      [:div
       [:h2 (if (= mode :create) "New page" "Edit page")]
       (when (= mode :edit) [:span.meta (:path form)])]
      [:div.controls
       [ui/button {:variant :secondary
                   :on-click #(rf/dispatch [:darelwasl.app/new-page])}
        "New page"]]]
     (if (and (nil? (:id form)) (= mode :edit))
       [:div.state.empty
        [:strong "Select a page"]
        [:p "Choose a page or start a new one."]]
       (let [save-btn [ui/button {:type "submit"
                                  :disabled saving?}
                       (if (= mode :create) "Create page" "Save page")]
             delete-btn (when (= mode :edit)
                          [ui/button {:variant :danger
                                      :on-click #(rf/dispatch [:darelwasl.app/delete-page])
                                      :disabled deleting?}
                           "Delete page"])]
         [:form {:on-submit (fn [e] (.preventDefault e) (rf/dispatch [:darelwasl.app/save-page]))}
          (when error [:div.form-error {:role "alert"} error])
          (when (= status :success) [:div.form-success {:aria-live "polite"} "Saved"])
          [:div.detail-grid
           [:div.field-group
            [:label "Title"]
            [ui/form-input {:value (:title form)
                            :on-change #(rf/dispatch [:darelwasl.app/set-page-field :title (.. % -target -value)])}]]
           [:div.field-group
            [:label "Path"]
            [ui/form-input {:value (:path form)
                            :placeholder "/about"
                            :on-change #(rf/dispatch [:darelwasl.app/set-page-field :path (.. % -target -value)])}]]
           [:div.field-group
            [:label "Summary"]
            [:textarea.form-input {:value (:summary form)
                                   :rows 2
                                   :on-change #(rf/dispatch [:darelwasl.app/set-page-field :summary (.. % -target -value)])}]]
           [:div.field-group
            [:label "Navigation order"]
            [ui/form-input {:type "number"
                            :value nav
                            :on-change #(rf/dispatch [:darelwasl.app/set-page-field :navigation-order (.. % -target -value)])}]]
           [:div.field-group
            [:label "Visible?"]
            [:label.checkbox
             [:input {:type "checkbox"
                      :checked (boolean (:visible? form))
                      :aria-label "Show page on site"
                      :on-change #(rf/dispatch [:darelwasl.app/set-page-field :visible? (.. % -target -checked)])}]
             [:span "Show on site"]]]
           [tag-picker selected-tags tags :darelwasl.app/toggle-page-tag]
           [:div.field-group
            [:div.meta "Linked blocks"]
            [:div.meta (str (count (:blocks form)) " block(s)")]]]
          [:div.button-row
           save-btn
           delete-btn]]))]))

(defn- block-form
  [{:keys [form mode status error]} pages tags]
  (let [saving? (= status :saving)
        deleting? (= status :deleting)
        selected-tags (or (:tags form) #{})
        current-page (:page form)]
    [:div.panel.detail-panel
     [:div.section-header
      [:div
       [:h2 (if (= mode :create) "New block" "Edit block")]
       (when (= mode :edit) [:span.meta (:slug form)])]
      [:div.controls
       [ui/button {:variant :secondary
                   :on-click #(rf/dispatch [:darelwasl.app/new-block])}
        "New block"]]]
     (if (and (nil? (:id form)) (= mode :edit))
       [:div.state.empty
        [:strong "Select a block"]
        [:p "Pick a block or start a new one."]]
       (let [save-btn [ui/button {:type "submit"
                                  :disabled saving?}
                       (if (= mode :create) "Create block" "Save block")]
             delete-btn (when (= mode :edit)
                          [ui/button {:variant :danger
                                      :on-click #(rf/dispatch [:darelwasl.app/delete-block])
                                      :disabled deleting?}
                           "Delete block"])]
         [:form {:on-submit (fn [e] (.preventDefault e) (rf/dispatch [:darelwasl.app/save-block]))}
          (when error [:div.form-error {:role "alert"} error])
          (when (= status :success) [:div.form-success {:aria-live "polite"} "Saved"])
          [:div.detail-grid
           [:div.field-group
            [:label "Title"]
            [ui/form-input {:value (:title form)
                            :on-change #(rf/dispatch [:darelwasl.app/set-block-field :title (.. % -target -value)])}]]
           [:div.field-group
            [:label "Slug"]
            [ui/form-input {:value (:slug form)
                            :on-change #(rf/dispatch [:darelwasl.app/set-block-field :slug (.. % -target -value)])}]]
           [:div.field-group
            [:label "Page"]
            [ui/select-field {:value (or current-page "")
                              :on-change #(rf/dispatch [:darelwasl.app/set-block-field :page (let [v (.. % -target -value)]
                                                                                                  (when-not (str/blank? v) v))])}
             [:option {:value ""} "Unassigned"]
             (for [p pages]
               ^{:key (:content.page/id p)}
               [:option {:value (:content.page/id p)} (or (:content.page/title p) (:content.page/path p))])]]
           [:div.field-group
            [:label "Type"]
            [ui/select-field {:value (or (:type form) :hero)
                              :on-change #(rf/dispatch [:darelwasl.app/set-block-field :type (keyword (.. % -target -value))])}
             (for [t [:hero :section :rich-text :feature :cta :list]]
               ^{:key (name t)}
               [:option {:value (name t)} (block-type-label t)])]]
           [:div.field-group
            [:label "Order"]
            [ui/form-input {:type "number"
                            :value (:order form)
                            :on-change #(rf/dispatch [:darelwasl.app/set-block-field :order (.. % -target -value)])}]]
           [:div.field-group
            [:label "Visible?"]
            [:label.checkbox
             [:input {:type "checkbox"
                      :checked (boolean (:visible? form))
                      :aria-label "Show block on site"
                      :on-change #(rf/dispatch [:darelwasl.app/set-block-field :visible? (.. % -target -checked)])}]
             [:span "Show on site"]]]
          [:div.field-group
           [:label "Body"]
           [:textarea.form-input {:value (:body form)
                                  :rows 3
                                  :placeholder "Optional body copy"
                                  :on-change #(rf/dispatch [:darelwasl.app/set-block-field :body (.. % -target -value)])}]]
           [:div.field-group
            [:label "Media ref"]
            [ui/form-input {:value (:media-ref form)
                            :on-change #(rf/dispatch [:darelwasl.app/set-block-field :media-ref (.. % -target -value)])}]]
           [tag-picker selected-tags tags :darelwasl.app/toggle-block-tag]]
          [:div.button-row
           save-btn
           delete-btn]]))]))

(defn- tag-form
  [{:keys [form mode status error]}]
  (let [saving? (= status :saving)
        deleting? (= status :deleting)]
    [:div.panel.detail-panel
     [:div.section-header
      [:div
       [:h2 (if (= mode :create) "New tag" "Edit tag")]
       (when (= mode :edit) [:span.meta (:slug form)])]
      [:div.controls
       [ui/button {:variant :secondary
                   :on-click #(rf/dispatch [:darelwasl.app/new-tag])}
        "New tag"]]]
     (if (and (nil? (:id form)) (= mode :edit))
       [:div.state.empty
        [:strong "Select a tag"]
        [:p "Pick a tag or start a new one."]]
       (let [save-btn [ui/button {:type "submit"
                                  :disabled saving?}
                       (if (= mode :create) "Create tag" "Save tag")]
             delete-btn (when (= mode :edit)
                          [ui/button {:variant :danger
                                      :on-click #(rf/dispatch [:darelwasl.app/delete-tag])
                                      :disabled deleting?}
                           "Delete tag"])]
         [:form {:on-submit (fn [e] (.preventDefault e) (rf/dispatch [:darelwasl.app/save-tag]))}
          (when error [:div.form-error {:role "alert"} error])
          (when (= status :success) [:div.form-success {:aria-live "polite"} "Saved"])
          [:div.detail-grid
           [:div.field-group
            [:label "Name"]
            [ui/form-input {:value (:name form)
                            :on-change #(rf/dispatch [:darelwasl.app/set-tag-field :name (.. % -target -value)])}]]
           [:div.field-group
            [:label "Slug"]
            [ui/form-input {:value (:slug form)
                            :on-change #(rf/dispatch [:darelwasl.app/set-tag-field :slug (.. % -target -value)])}]]
           [:div.field-group
            [:label "Description"]
            [:textarea.form-input {:value (:description form)
                                   :rows 2
                                   :on-change #(rf/dispatch [:darelwasl.app/set-tag-field :description (.. % -target -value)])}]]]
          [:div.button-row
           save-btn
           delete-btn]]))]))

;; -------- V2 read-only view --------

(defn- pills
  [labels]
  [:div.pill-bank
   (for [l labels]
     ^{:key l} [:span.pill l])])

(defn- v2-section
  [title & children]
  [:section.panel
   [:div.section-header
    [:div
     [:p.eyebrow "Content v2"]
     [:h3 title]]]
   (into [:div.section-body] children)])

(defn- licenses-view [licenses]
  [v2-section "Licenses"
   (if (seq licenses)
     [:div.card-grid
      (for [l licenses]
        ^{:key (:license/id l)}
        [:article.card
         [:p.eyebrow (name (:license/type l))]
         [:h4 (:license/label l)]
         [:p.meta (:license/processing-time l)]
         [:p.meta (:license/ownership l)]
         [:p.meta (:license/renewal-cost l)]
         (when (seq (:license/pricing-lines l)) [pills (:license/pricing-lines l)])
         (when (seq (:license/activities l))
           [:div.meta (str "Activities: " (str/join ", " (:license/activities l)))])
        (when (seq (:license/document-checklist l))
          [:ul.icon-list
           (for [d (:license/document-checklist l)]
             ^{:key d} [:li d])])])]
     [:div.meta "No licenses available"])] )

(defn- comparison-view [rows]
  [v2-section "Comparison rows"
   (if (seq rows)
     [:div.table-wrapper
      [:table
       [:thead [:tr [:th "Criteria"] [:th "Entrepreneur"] [:th "General"] [:th "GCC"]]]
       [:tbody
        (for [r rows]
          ^{:key (:comparison.row/id r)}
          [:tr
           [:td (:comparison.row/criterion r)]
           [:td (:comparison.row/entrepreneur r)]
           [:td (:comparison.row/general r)]
           [:td (:comparison.row/gcc r)]])]]]
     [:div.meta "No comparison rows"])])

(defn- journey-view [phases steps]
  [v2-section "Journey & activation"
   (if (seq phases)
     [:div.timeline
      (for [p phases]
        ^{:key (:journey.phase/id p)}
        [:article.timeline-card
         [:span.step-badge (gstring/format "%02d" (or (:journey.phase/order p) 0))]
         [:div.timeline-meta
          [:h4 (:journey.phase/title p)]
          [:p.meta (name (:journey.phase/kind p))]]
         (when (seq (:journey.phase/bullets p))
           [:ul.icon-list
            (for [b (:journey.phase/bullets p)]
              ^{:key b} [:li b])])
         (when (seq steps)
           [:div.meta (str "Steps linked: "
                           (count (filter #(= (get-in % [:activation.step/phase :journey.phase/id])
                                              (:journey.phase/id p)) steps)))])])]
     [:div.meta "No journey phases"])
   (when (seq steps)
     [:div.activation-grid
      (for [s (sort-by #(or (:activation.step/order %) 999999) steps)]
        ^{:key (:activation.step/id s)}
        [:div.activation-card
         [:span.step-no (gstring/format "%02d" (or (:activation.step/order s) 0))]
         [:p (:activation.step/title s)]])])])

(defn- personas-view [personas]
  [v2-section "Personas"
   (if (seq personas)
     [:div.card-grid
      (for [p personas]
        ^{:key (:persona/id p)}
        [:article.card
         [:p.eyebrow (or (some-> (:persona/type p) name) "Persona")]
         [:h4 (:persona/title p)]
         [:p (:persona/detail p)]
         (when-not (:persona/visible? p) [:p.meta "Hidden"])])]
     [:div.meta "No personas"])])

(defn- support-view [entries]
  [v2-section "Support map"
   (if (seq entries)
     [:div.helper-columns
      (for [role [:support/we :support/you]]
        ^{:key role}
        [:article
         [:p.eyebrow (if (= role :support/we) "We choreograph" "You provide")]
         [:h4 (if (= role :support/we) "Our scope" "Your inputs")]
         [:ul
          (for [e (filter #(= (:support.entry/role %) role) entries)]
            ^{:key (:support.entry/id e)} [:li (:support.entry/text e)])]])]
     [:div.meta "No support entries"])])

(defn- hero-view [stats flows]
  [v2-section "Hero"
   [:div.hero-statboard
    (for [s stats]
      ^{:key (:hero.stat/id s)}
      [:div.hero-stat-card
       [:span (:hero.stat/label s)]
       [:strong (:hero.stat/value s)]
       [:p (:hero.stat/hint s)]])]
   (when (seq flows)
     [:div.hero-flow
      (for [[idx f] (map-indexed vector flows)]
        ^{:key (:hero.flow/id f)}
        [:div.flow-card
         [:span.step-badge (gstring/format "%02d" (inc idx))]
         [:div
          [:h3 (:hero.flow/title f)]
          [:p (:hero.flow/detail f)]]])])])

(defn- faq-values-team-view [faqs values team]
  [:div
   [v2-section "FAQs"
    (if (seq faqs)
      [:div.faq-grid
       (for [f faqs]
         ^{:key (:faq/id f)}
         [:article
          [:h4 (:faq/question f)]
          [:p (:faq/answer f)]])]
      [:div.meta "No FAQs"])]
   [v2-section "Values & Team"
    [:div.card-grid
     (for [v values]
       ^{:key (:value/id v)}
       [:article.card
        [:p.eyebrow "Value"]
        [:h4 (:value/title v)]
        [:p (:value/copy v)]])
     (for [m team]
       ^{:key (:team.member/id m)}
       [:article.card
        [:p.eyebrow "Team"]
        [:h4 (:team.member/name m)]
        [:p (:team.member/title m)]])]]])

(defn- checkbox-list [items selected key-fn label-fn on-toggle]
  (into [:div.checkbox-list]
        (for [item items
              :let [id (key-fn item)
                    checked? (contains? selected id)]]
          ^{:key id}
          [:label
           [:input {:type "checkbox"
                    :checked checked?
                    :on-change #(on-toggle id (not checked?))}]
           " "
           (label-fn item)])))

(defn- business-contact-view
  [{:keys [business-detail contact-detail]} hero-stats hero-flows contacts]
  (let [bform (get-in business-detail [:form])
        bstatus (get-in business-detail [:status])
        berror (get-in business-detail [:error])
        cform (get-in contact-detail [:form])
        cstatus (get-in contact-detail [:status])
        cerror (get-in contact-detail [:error])]
    [v2-section "Business & Contact"
     [:div.card-grid
      [:article.card
       [:p.eyebrow "Business"]
       [ui/form-input {:placeholder "Name"
                       :value (:name bform)
                       :on-change #(rf/dispatch [:darelwasl.app/set-business-field :name (.. % -target -value)])}]
       [ui/form-input {:placeholder "Nav label"
                       :value (:nav-label bform)
                       :on-change #(rf/dispatch [:darelwasl.app/set-business-field :nav-label (.. % -target -value)])}]
       [ui/form-input {:placeholder "Hero headline"
                       :value (:hero-headline bform)
                       :on-change #(rf/dispatch [:darelwasl.app/set-business-field :hero-headline (.. % -target -value)])}]
       [ui/form-input {:placeholder "Hero strapline"
                       :value (:hero-strapline bform)
                       :on-change #(rf/dispatch [:darelwasl.app/set-business-field :hero-strapline (.. % -target -value)])}]
       [ui/form-input {:placeholder "Tagline"
                       :value (:tagline bform)
                       :on-change #(rf/dispatch [:darelwasl.app/set-business-field :tagline (.. % -target -value)])}]
       [ui/form-input {:placeholder "Summary"
                       :value (:summary bform)
                       :on-change #(rf/dispatch [:darelwasl.app/set-business-field :summary (.. % -target -value)])}]
       [ui/form-input {:placeholder "Mission"
                       :value (:mission bform)
                       :on-change #(rf/dispatch [:darelwasl.app/set-business-field :mission (.. % -target -value)])}]
       [ui/form-input {:placeholder "Vision"
                       :value (:vision bform)
                       :on-change #(rf/dispatch [:darelwasl.app/set-business-field :vision (.. % -target -value)])}]
       [:label [:input {:type "checkbox"
                        :checked (not= false (:visible? bform))
                        :on-change #(rf/dispatch [:darelwasl.app/set-business-field :visible? (.. % -target -checked)])}]
        " Visible on site"]
       [:div.field-group
        [:p.eyebrow "Contact"]
        [ui/select-field {:value (:contact bform)
                          :on-change #(rf/dispatch [:darelwasl.app/set-business-field :contact (some-> % .-target .-value not-empty uuid)])}
         [:option {:value ""} "Select contact"]
         (for [c contacts]
           ^{:key (:contact/id c)}
           [:option {:value (str (:contact/id c))} (str (:contact/email c) " · " (:contact/phone c))])]]
       [:div.field-group
        [:p.eyebrow "Hero stats"]
        (checkbox-list hero-stats (set (:hero-stats bform)) :hero.stat/id (fn [s] (or (:hero.stat/label s) (:hero.stat/id s)))
                       (fn [id add?]
                         (let [current (set (:hero-stats bform))
                               updated (if add? (conj current id) (disj current id))]
                           (rf/dispatch [:darelwasl.app/set-business-field :hero-stats (vec updated)]))))]
       [:div.field-group
        [:p.eyebrow "Hero flows"]
        (checkbox-list hero-flows (set (:hero-flows bform)) :hero.flow/id (fn [f] (or (:hero.flow/title f) (:hero.flow/id f)))
                       (fn [id add?]
                         (let [current (set (:hero-flows bform))
                               updated (if add? (conj current id) (disj current id))]
                           (rf/dispatch [:darelwasl.app/set-business-field :hero-flows (vec updated)]))))]
       (when berror [:div.error berror])
       [ui/button {:variant :primary
                   :class "save-button"
                   :on-click #(rf/dispatch [:darelwasl.app/save-business])}
        (case bstatus
          :saving "Saving..."
          "Save business")]]
      [:article.card
       [:p.eyebrow "Contact"]
       [ui/form-input {:placeholder "Email"
                       :value (:email cform)
                       :on-change #(rf/dispatch [:darelwasl.app/set-contact-field :email (.. % -target -value)])}]
       [ui/form-input {:placeholder "Phone"
                       :value (:phone cform)
                       :on-change #(rf/dispatch [:darelwasl.app/set-contact-field :phone (.. % -target -value)])}]
       [ui/form-input {:placeholder "Primary CTA label"
                       :value (:primary-cta-label cform)
                       :on-change #(rf/dispatch [:darelwasl.app/set-contact-field :primary-cta-label (.. % -target -value)])}]
       [ui/form-input {:placeholder "Primary CTA URL"
                       :value (:primary-cta-url cform)
                       :on-change #(rf/dispatch [:darelwasl.app/set-contact-field :primary-cta-url (.. % -target -value)])}]
       [ui/form-input {:placeholder "Secondary CTA label"
                       :value (:secondary-cta-label cform)
                       :on-change #(rf/dispatch [:darelwasl.app/set-contact-field :secondary-cta-label (.. % -target -value)])}]
       [ui/form-input {:placeholder "Secondary CTA URL"
                       :value (:secondary-cta-url cform)
                       :on-change #(rf/dispatch [:darelwasl.app/set-contact-field :secondary-cta-url (.. % -target -value)])}]
       (when cerror [:div.error cerror])
       [ui/button {:variant :primary
                   :class "save-button"
                   :on-click #(rf/dispatch [:darelwasl.app/save-contact])}
        (case cstatus
          :saving "Saving..."
          "Save contact")]]]]))

(defn control-panel-v2
  [{:keys [status error data business contact]}]
  [:div.control-panel-v2
   (case status
     :loading [ui/loading-state "Loading site content..."]
     :error [ui/error-state (or error "Unable to load content v2") #(rf/dispatch [:darelwasl.app/fetch-content])]
     :empty [ui/empty-state "No site content yet" "Seed content to preview here."]
     (let [{:keys [licenses comparison-rows journey-phases activation-steps personas support-entries hero-stats hero-flows faqs values team-members businesses contacts]} data]
       [:div.control-panel-v2__grid
        [licenses-view licenses]
        [comparison-view comparison-rows]
        [journey-view journey-phases activation-steps]
        [personas-view personas]
        [support-view support-entries]
        [hero-view hero-stats hero-flows]
        [faq-values-team-view faqs values team-members]
        [business-contact-view {:business-detail business :contact-detail contact} hero-stats hero-flows contacts]]))])

(defn control-panel-view []
  (let [{:keys [pages blocks tags v2 tab business contact]} @(rf/subscribe [:darelwasl.app/control])
        tag-index (into {} (map (fn [t] [(:content.tag/id t) (:content.tag/name t)]) (:items tags)))
        selected-page (some #(when (= (:content.page/id %) (:selected pages)) %) (:items pages))
        selected-block (some #(when (= (:content.block/id %) (:selected blocks)) %) (:items blocks))
        selected-tag (some #(when (= (:content.tag/id %) (:selected tags)) %) (:items tags))]
    (when (some #{:idle} [(:status pages) (:status blocks) (:status tags)])
      (rf/dispatch [:darelwasl.app/fetch-content]))
    (when (and (= tab :v2) (= :idle (:status v2)))
      (rf/dispatch [:darelwasl.app/fetch-content]))
    [:div.control-panel
     [:div.section-header
      [:div
       [:p.eyebrow "Control panel"]
       [:h2 "Manage site content"]]
     [:div.controls
       [ui/button {:variant (if (= tab :v1) :primary :secondary)
                   :on-click #(rf/dispatch [:darelwasl.app/set-control-tab :v1])}
        "Pages/Blocks/Tags"]
       [ui/button {:variant (if (= tab :v2) :primary :secondary)
                   :on-click #(rf/dispatch [:darelwasl.app/set-control-tab :v2])}
        "Site data (v2)"]
       [ui/button {:variant :secondary
                   :on-click #(rf/dispatch [:darelwasl.app/fetch-content])}
        "Refresh"]]]
     (if (= tab :v2)
       [control-panel-v2 v2]
       [:div
        [:div.control-panel__lists
         [ui/entity-list {:title "Pages"
                          :meta (str (count (:items pages)) " pages")
                          :items (:items pages)
                          :status (:status pages)
                          :error (:error pages)
                          :error-node [ui/error-state (:error pages) #(rf/dispatch [:darelwasl.app/fetch-content])]
                          :selected (:selected pages)
                          :key-fn :content.page/id
                          :empty-node [:div.state.empty
                                       [:strong "No pages yet"]
                                       [:p "Create your first page to manage site content."]]
                          :header-actions [[ui/button {:variant :secondary
                                                       :on-click #(rf/dispatch [:darelwasl.app/new-page])}
                                            "New"]
                                           [ui/button {:variant :secondary
                                                       :on-click #(rf/dispatch [:darelwasl.app/fetch-content])}
                                            "Refresh"]]
                          :render-row (fn [page selected?]
                                        [page-row page selected?])}]
         [ui/entity-list {:title "Blocks"
                          :meta (str (count (:items blocks)) " blocks")
                          :items (:items blocks)
                          :status (:status blocks)
                          :error (:error blocks)
                          :error-node [ui/error-state (:error blocks) #(rf/dispatch [:darelwasl.app/fetch-content])]
                          :selected (:selected blocks)
                          :key-fn :content.block/id
                          :empty-node [:div.state.empty
                                       [:strong "No blocks yet"]
                                       [:p "Add blocks and link them to pages."]]
                          :header-actions [[ui/button {:variant :secondary
                                                       :on-click #(rf/dispatch [:darelwasl.app/new-block])}
                                            "New"]
                                           [ui/button {:variant :secondary
                                                       :on-click #(rf/dispatch [:darelwasl.app/fetch-content])}
                                            "Refresh"]]
                          :render-row (fn [block selected?]
                                        [block-row block selected? tag-index])}]
         [ui/entity-list {:title "Tags"
                          :meta (str (count (:items tags)) " tags")
                          :items (:items tags)
                          :status (:status tags)
                          :error (:error tags)
                          :error-node [ui/error-state (:error tags) #(rf/dispatch [:darelwasl.app/fetch-content])]
                          :selected (:selected tags)
                          :key-fn :content.tag/id
                          :empty-node [:div.state.empty
                                       [:strong "No tags yet"]
                                       [:p "Create tags to organize pages and blocks."]]
                          :header-actions [[ui/button {:variant :secondary
                                                       :on-click #(rf/dispatch [:darelwasl.app/new-tag])}
                                            "New"]
                                           [ui/button {:variant :secondary
                                                       :on-click #(rf/dispatch [:darelwasl.app/fetch-content])}
                                            "Refresh"]]
                          :render-row (fn [tag selected?]
                                        [tag-row tag selected?])}]]
        [:div.control-panel__details
         [page-form (:detail pages) (:items pages) (:items tags)]
         [block-form (:detail blocks) (:items pages) (:items tags)]
         [tag-form (:detail tags)]]])]))

(defn control-panel-shell []
  [shell/app-shell
   [:main.control-panel-layout
    [control-panel-view]]
   [:span "Control panel · Content and site management shell."]])
