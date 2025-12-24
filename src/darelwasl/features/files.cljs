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
    "File"))

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
       [:span.meta "Images + PDFs only"]]
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
                           :accept "image/*,application/pdf"
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
  (let [{:keys [items status error selected]} @(rf/subscribe [:darelwasl.app/files])]
    [:div.panel.files-list
     [:div.section-header
      [:div
       [:h2 "Library"]
       [:span.meta (str (count items) " items")]]]
     [search-panel]
     (case status
       :loading [ui/loading-state "Loading files..."]
       :error [ui/error-state error #(rf/dispatch [:darelwasl.app/fetch-files])]
       (if (seq items)
         [:div.files-list-items
          (for [file items]
            ^{:key (str (:file/id file))}
            [file-row file (= (:file/id file) selected)])]
         [ui/empty-state "No files yet" "Upload an image or PDF to get started."]))]))

(defn- preview-panel
  []
  (let [file @(rf/subscribe [:darelwasl.app/selected-file])]
    [:div.panel.files-detail
     [:div.section-header
      [:div
       [:h2 "Details"]
       [:span.meta "Preview + references"]]]
     (if-not file
       [:div.state.empty
        [:strong "Select a file"]
        [:p "Pick a file from the list to see details and preview."]]
       [:div.files-detail-body
        [:div.files-preview
         (case (:file/type file)
           :file.type/image [:img {:src (:file/url file)
                                   :alt (or (:file/name file) "Uploaded image")}]
           :file.type/pdf [:iframe {:src (:file/url file)
                                    :title (or (:file/name file) "PDF preview")}]
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
    [:div.files-grid
     [list-panel]
     [preview-panel]]]
   [:span "File library"]])
