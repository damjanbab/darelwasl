;; File library UI.
(ns darelwasl.features.files
  (:require [darelwasl.ui.components :as ui]
            [darelwasl.ui.shell :as shell]
            [darelwasl.util :as util]
            [re-frame.core :as rf]))

(defn- type-label
  [t]
  (case t
    :file.type/image "Image"
    :file.type/pdf "PDF"
    :file.type/markdown "Markdown"
    :file.type/zip "ZIP"
    "File"))

(defn- bundle-type-label
  [t]
  (case t
    :bundle.type/site-screenshot "Site screenshots"
    "Bundle"))

(defn- bundle-row
  [bundle selected?]
  (let [title (or (:bundle/title bundle) "Untitled bundle")
        count (or (:bundle/count bundle) (count (:bundle/files bundle)) 0)
        created (or (util/format-date (:bundle/created-at bundle)) "—")
        meta (str (bundle-type-label (:bundle/type bundle)) " · " count " items")
        trailing created]
    [ui/list-row {:title title
                  :meta meta
                  :trailing trailing
                  :selected? selected?
                  :on-click #(rf/dispatch [:darelwasl.app/select-bundle (:bundle/id bundle)])}]))

(defn- file-row
  [file selected?]
  (let [name (or (:file/name file) "Untitled")
        slug (or (:file/slug file) "—")
        kind (type-label (:file/type file))
        size (util/format-bytes (:file/size-bytes file))
        created (or (util/format-date (:file/created-at file)) "—")
        meta (str slug " · " kind)
        trailing (str size " · " created)]
    [ui/list-row {:title name
                  :meta meta
                  :trailing trailing
                  :selected? selected?
                  :on-click #(rf/dispatch [:darelwasl.app/select-file (:file/id file)])}]))

(defn- upload-panel
  []
  (let [{:keys [upload]} @(rf/subscribe [:darelwasl.app/files])
        {:keys [file slug error status]} upload
        uploading? (= status :uploading)
        success? (= status :success)
        file-name (some-> file .-name)]
    [:div.panel.files-upload
     [:div.section-header
      [:div
       [:h2 "Upload"]
       [:span.meta "Images, PDFs, Markdown, and ZIP"]]
      [:div.controls
       [ui/button {:variant :secondary
                   :disabled uploading?
                   :on-click #(rf/dispatch [:darelwasl.app/fetch-files])}
        "Refresh"]]]
     [:div.files-upload-grid
      [:div.field-group
       [:label {:for "file-input"} "File"]
       [:input.form-input {:id "file-input"
                           :type "file"
                           :accept "image/*,application/pdf,application/zip,text/markdown,text/x-markdown,.md,.markdown,.zip"
                           :disabled uploading?
                           :on-change #(let [f (aget (.. % -target -files) 0)]
                                         (rf/dispatch [:darelwasl.app/set-upload-file f]))}]
       (when file-name
         [:div.meta (str "Selected: " file-name)])]
      [:div.field-group
       [:label {:for "file-slug"} "Reference slug"]
       [ui/form-input {:id "file-slug"
                       :placeholder "e.g. hero-banner"
                       :value (or slug "")
                       :disabled uploading?
                       :on-change #(rf/dispatch [:darelwasl.app/set-upload-slug (.. % -target -value)])}]
       [:div.meta "Leave blank to auto-generate from filename."]]]
     (when error
       [:div.form-error {:role "alert"} error])
     (when success?
       [:div.form-success {:aria-live "polite"} "Upload complete."])
     [:div.button-row
      [ui/button {:disabled uploading?
                  :on-click #(rf/dispatch [:darelwasl.app/upload-file])}
       (if uploading? "Uploading..." "Upload")]
      [ui/button {:variant :secondary
                  :disabled uploading?
                  :on-click #(rf/dispatch [:darelwasl.app/clear-upload])}
       "Clear"]]]))

(defn- statement-payment-panel
  [idx payment loading?]
  [:div.panel.statement-payment
   [:div.section-header
    [:div
     [:h3 (str "Payment " (inc idx))]
     [:span.meta "Optional; include if you want a payment breakdown."]]
    [:div.controls
     [ui/button {:variant :danger
                 :disabled loading?
                 :on-click #(rf/dispatch [:darelwasl.app/remove-statement-payment idx])}
      "Remove"]]]
   [:div.detail-grid
    [:div.field-group
     [:label "Date"]
     [ui/form-input {:type "date"
                     :value (or (:date payment) "")
                     :disabled loading?
                     :on-change #(rf/dispatch [:darelwasl.app/set-statement-payment-field idx :date (.. % -target -value)])}]]
    [:div.field-group
     [:label "Amount"]
     [ui/form-input {:type "number"
                     :step "0.01"
                     :value (or (:amount payment) "")
                     :disabled loading?
                     :on-change #(rf/dispatch [:darelwasl.app/set-statement-payment-field idx :amount (.. % -target -value)])}]]
    [:div.field-group
     [:label "Mode"]
     [ui/form-input {:placeholder "Cash / Transfer / Card"
                     :value (or (:mode payment) "")
                     :disabled loading?
                     :on-change #(rf/dispatch [:darelwasl.app/set-statement-payment-field idx :mode (.. % -target -value)])}]]
    [:div.field-group
     [:label "Status"]
     [ui/form-input {:placeholder "Completed / Pending"
                     :value (or (:status payment) "")
                     :disabled loading?
                     :on-change #(rf/dispatch [:darelwasl.app/set-statement-payment-field idx :status (.. % -target -value)])}]]
    [:div.field-group {:style {:grid-column "1 / -1"}}
     [:label "Description"]
     [ui/form-input {:placeholder "Payment description"
                     :value (or (:description payment) "")
                     :disabled loading?
                     :on-change #(rf/dispatch [:darelwasl.app/set-statement-payment-field idx :description (.. % -target -value)])}]]]])

(defn- statement-panel
  []
  (let [{:keys [statement]} @(rf/subscribe [:darelwasl.app/files])
        {:keys [form status error last-file]} statement
        loading? (= status :loading)
        success? (= status :success)
        payments (vec (or (:payments form) []))]
    [:div.panel.statement-generator
     [:div.section-header
      [:div
       [:h2 "Account statement"]
       [:span.meta "Fill in the fields and generate a branded PDF stored in the library."]]
      [:div.controls
       [ui/button {:variant :secondary
                   :disabled loading?
                   :on-click #(rf/dispatch [:darelwasl.app/clear-statement])}
        "Clear"]]]

     [:div.detail-grid
      [:div.field-group
       [:label "Company name"]
       [ui/form-input {:placeholder "Required"
                       :value (or (:company-name form) "")
                       :disabled loading?
                       :on-change #(rf/dispatch [:darelwasl.app/set-statement-field :company-name (.. % -target -value)])}]]
      [:div.field-group
       [:label "Client name"]
       [ui/form-input {:placeholder "Required"
                       :value (or (:client-name form) "")
                       :disabled loading?
                       :on-change #(rf/dispatch [:darelwasl.app/set-statement-field :client-name (.. % -target -value)])}]]
      [:div.field-group
       [:label "Date"]
       [ui/form-input {:type "date"
                       :value (or (:date form) "")
                       :disabled loading?
                       :on-change #(rf/dispatch [:darelwasl.app/set-statement-field :date (.. % -target -value)])}]]
      [:div.field-group
       [:label "Currency"]
       [ui/form-input {:placeholder "SAR"
                       :value (or (:currency form) "")
                       :disabled loading?
                       :on-change #(rf/dispatch [:darelwasl.app/set-statement-field :currency (.. % -target -value)])}]]
      [:div.field-group
       [:label "Total contract amount"]
       [ui/form-input {:type "number"
                       :step "0.01"
                       :value (or (:total-contract-amount form) "")
                       :disabled loading?
                       :on-change #(rf/dispatch [:darelwasl.app/set-statement-field :total-contract-amount (.. % -target -value)])}]]
      [:div.field-group
       [:label "Total received"]
       [ui/form-input {:type "number"
                       :step "0.01"
                       :value (or (:total-amount-received form) "")
                       :disabled loading?
                       :on-change #(rf/dispatch [:darelwasl.app/set-statement-field :total-amount-received (.. % -target -value)])}]]
      [:div.field-group
       [:label "Outstanding balance"]
       [ui/form-input {:type "number"
                       :step "0.01"
                       :placeholder "Optional (auto-computed when possible)"
                       :value (or (:outstanding-balance form) "")
                       :disabled loading?
                       :on-change #(rf/dispatch [:darelwasl.app/set-statement-field :outstanding-balance (.. % -target -value)])}]]
      [:div.field-group
       [:label "File slug"]
       [ui/form-input {:placeholder "Optional (stored in library)"
                       :value (or (:slug form) "")
                       :disabled loading?
                       :on-change #(rf/dispatch [:darelwasl.app/set-statement-field :slug (.. % -target -value)])}]]
      [:div.field-group {:style {:grid-column "1 / -1"}}
       [:label "Remarks"]
       [:textarea.form-input {:rows 3
                              :placeholder "Optional remarks"
                              :value (or (:remarks form) "")
                              :disabled loading?
                              :on-change #(rf/dispatch [:darelwasl.app/set-statement-field :remarks (.. % -target -value)])}]]]

     [:div.section-header
      [:div
       [:h3 "Payments"]
       [:span.meta "Optional; add zero or more payment rows."]]
      [:div.controls
       [ui/button {:variant :secondary
                   :disabled loading?
                   :on-click #(rf/dispatch [:darelwasl.app/add-statement-payment])}
        "Add payment"]]]

     (if (seq payments)
       [:div.statement-payments
        (for [[idx payment] (map-indexed vector payments)]
          ^{:key (str "statement-payment-" idx)}
          [statement-payment-panel idx payment loading?])]
       [:div.meta "No payments added."])

     (when error
       [:div.form-error {:role "alert"} error])
     (when (and success? last-file)
       [:div.form-success {:aria-live "polite"}
        [:div "PDF generated and saved to the library."]
        (when-let [ref (:file/ref last-file)]
          [:div.meta (str "Reference: " ref)])])

     [:div.button-row
      [ui/button {:disabled loading?
                  :on-click #(rf/dispatch [:darelwasl.app/generate-account-statement])}
       (if loading? "Generating..." "Generate PDF")]
      (when (and last-file (:file/url last-file))
        [:a.button.secondary {:href (:file/url last-file)
                              :target "_blank"
                              :rel "noreferrer"}
         "Open PDF"])]]))

(defn- search-panel
  []
  (let [{:keys [filters status]} @(rf/subscribe [:darelwasl.app/files])
        loading? (= status :loading)]
    [:div.files-search
     [:div.field-group
      [:label "Search"]
      [ui/form-input {:placeholder "Search by name or slug"
                      :value (or (:query filters) "")
                      :disabled loading?
                      :on-change #(rf/dispatch [:darelwasl.app/set-file-query (.. % -target -value)])}]]
     [:div.controls
      [ui/button {:variant :secondary
                  :disabled loading?
                  :on-click #(rf/dispatch [:darelwasl.app/fetch-files])}
       "Search"]
      [ui/button {:variant :secondary
                  :disabled loading?
                  :on-click #(do
                               (rf/dispatch [:darelwasl.app/set-file-query ""])
                               (rf/dispatch [:darelwasl.app/fetch-files]))}
       "Reset"]]]))

(defn- list-panel
  []
  (let [{:keys [items status error selected bundles]} @(rf/subscribe [:darelwasl.app/files])
        bundle-items (:items bundles)
        bundle-status (:status bundles)
        bundle-error (:error bundles)
        bundle-selected (:selected bundles)]
    [:div.panel.files-list
     [:div.section-header
      [:div
       [:h2 "Library"]
       [:span.meta (str (count items) " items")]]]
     [:div.files-bundles
      [:div.section-header
       [:div
        [:h3 "Bundles"]
        [:span.meta (str (count bundle-items) " bundles")]]
       [:div.controls
        [ui/button {:variant :secondary
                    :disabled (= bundle-status :loading)
                    :on-click #(rf/dispatch [:darelwasl.app/fetch-bundles])}
         "Refresh"]]]
      (case bundle-status
        :loading [ui/loading-state "Loading bundles..."]
        :error [ui/error-state bundle-error #(rf/dispatch [:darelwasl.app/fetch-bundles])]
        (if (seq bundle-items)
          [:div.files-bundles-items
           (for [bundle bundle-items]
             ^{:key (str (:bundle/id bundle))}
             [bundle-row bundle (= (:bundle/id bundle) bundle-selected)])]
          [ui/empty-state "No bundles yet" "Create a screenshot bundle to organize your visuals."]))]
     [search-panel]
     (case status
       :loading [ui/loading-state "Loading files..."]
       :error [ui/error-state error #(rf/dispatch [:darelwasl.app/fetch-files])]
       (if (seq items)
         [:div.files-list-items
          (for [file items]
            ^{:key (str (:file/id file))}
            [file-row file (= (:file/id file) selected)])]
         [ui/empty-state "No files yet" "Upload an image, PDF, or Markdown file to get started."]))]))

(defn- preview-panel
  []
  (let [{:keys [detail bundles]} @(rf/subscribe [:darelwasl.app/files])
        file @(rf/subscribe [:darelwasl.app/selected-file])
        selected-bundle-id (:selected bundles)
        selected-bundle (some #(when (= (:bundle/id %) selected-bundle-id) %) (:items bundles))
        {:keys [form status error]} detail
        saving? (= status :saving)
        success? (= status :success)]
    [:div.panel.files-detail
     [:div.section-header
      [:div
       [:h2 "Details"]
       [:span.meta "Preview + references"]]]
     (cond
       selected-bundle
       [:div.bundle-detail
        [:div.bundle-header
         [:div
          [:h3 (or (:bundle/title selected-bundle) "Untitled bundle")]
          [:div.meta (str (bundle-type-label (:bundle/type selected-bundle))
                          " · "
                          (or (:bundle/count selected-bundle)
                              (count (:bundle/files selected-bundle))
                              0)
                          " items")]]
         [:div.meta (or (util/format-date (:bundle/created-at selected-bundle)) "—")]]
        (if (seq (:bundle/files selected-bundle))
          [:div.bundle-gallery
           (for [f (:bundle/files selected-bundle)]
             ^{:key (str (:file/id f))}
             [:button.bundle-thumb
              {:type "button"
               :on-click #(rf/dispatch [:darelwasl.app/select-file (:file/id f)])}
              (if (= :file.type/image (:file/type f))
                [:img {:src (:file/url f)
                       :alt (or (:file/name f) "Bundle image")}]
                [:span.meta (or (:file/name f) "File")])])]
          [ui/empty-state "No files" "Bundle files could not be loaded."])]

       (nil? file)
       [:div.state.empty
        [:strong "Select a file"]
        [:p "Pick a file from the list to see details and preview."]]

       :else
       [:div.files-detail-body
        [:div.files-preview
         (case (:file/type file)
           :file.type/image [:img {:src (:file/url file)
                                   :alt (or (:file/name file) "Uploaded image")}]
           :file.type/pdf [:iframe {:src (:file/url file)
                                    :title (or (:file/name file) "PDF preview")}]
           :file.type/markdown [:iframe {:src (:file/url file)
                                         :title (or (:file/name file) "Markdown preview")}]
           [:div.meta "No preview available."])]
        [:div.files-meta
         [:div.meta-row
          [:span.meta-label "Reference"]
          [:code (or (:file/ref file) "—")]]
         [:div.meta-row
          [:span.meta-label "Slug"]
          [:code (or (:file/slug file) "—")]]
         [:div.meta-row
          [:span.meta-label "ID"]
          [:code (or (:file/id file) "—")]]
         [:div.meta-row
          [:span.meta-label "Type"]
          [:span.meta-value (type-label (:file/type file))]]
         [:div.meta-row
          [:span.meta-label "Size"]
          [:span.meta-value (util/format-bytes (:file/size-bytes file))]]
         [:div.meta-row
          [:span.meta-label "Uploaded"]
          [:span.meta-value (or (util/format-date (:file/created-at file)) "—")]]]
        [:div.files-edit
         [:div.field-group
          [:label {:for "file-detail-reference"} "Reference"]
          [ui/form-input {:id "file-detail-reference"
                          :placeholder "file:hero-banner"
                          :value (or (:reference form) "")
                          :disabled saving?
                          :on-change #(rf/dispatch [:darelwasl.app/set-file-detail-reference (.. % -target -value)])}]
          [:div.meta "Use the file: prefix to set a reference."]]
         [:div.field-group
          [:label {:for "file-detail-slug"} "Slug"]
          [ui/form-input {:id "file-detail-slug"
                          :placeholder "hero-banner"
                          :value (or (:slug form) "")
                          :disabled saving?
                          :on-change #(rf/dispatch [:darelwasl.app/set-file-detail-slug (.. % -target -value)])}]
          [:div.meta "Reference updates automatically when you edit the slug."]]
         (when error
           [:div.form-error {:role "alert"} error])
         (when success?
           [:div.form-success {:aria-live "polite"} "Reference updated."])
         [:div.button-row
          [ui/button {:disabled saving?
                      :on-click #(rf/dispatch [:darelwasl.app/update-file])}
           (if saving? "Saving..." "Update")]]]
        [:div.button-row
         [:a.button.secondary {:href (:file/url file)
                               :target "_blank"
                               :rel "noreferrer"}
          "Open"]
         [ui/button {:variant :danger
                     :on-click #(when (js/confirm "Delete this file? This cannot be undone.")
                                  (rf/dispatch [:darelwasl.app/delete-file (:file/id file)]))}
          "Delete"]]])]))

(defn file-library-shell
  []
  [shell/app-shell
   [:main.files-layout
    [upload-panel]
    [statement-panel]
    [:div.files-grid
     [list-panel]
     [preview-panel]]]
   [:span "File library"]])
