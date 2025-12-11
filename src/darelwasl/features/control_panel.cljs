(ns darelwasl.features.control-panel
  (:require [clojure.string :as str]
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

(defn control-panel-view []
  (let [{:keys [pages blocks tags]} @(rf/subscribe [:darelwasl.app/control])
        tag-index (into {} (map (fn [t] [(:content.tag/id t) (:content.tag/name t)]) (:items tags)))
        selected-page (some #(when (= (:content.page/id %) (:selected pages)) %) (:items pages))
        selected-block (some #(when (= (:content.block/id %) (:selected blocks)) %) (:items blocks))
        selected-tag (some #(when (= (:content.tag/id %) (:selected tags)) %) (:items tags))]
    (when (some #{:idle} [(:status pages) (:status blocks) (:status tags)])
      (rf/dispatch [:darelwasl.app/fetch-content]))
    [:div.control-panel
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
      [tag-form (:detail tags)]]]))

(defn control-panel-shell []
  [shell/app-shell
   [:main.control-panel-layout
    [control-panel-view]]
   [:span "Control panel · Content and site management shell."]])
