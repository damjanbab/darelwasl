(ns darelwasl.features.agent-control
  (:require [clojure.string :as str]
            [darelwasl.ui.components :as ui]
            [darelwasl.ui.shell :as shell]
            [re-frame.core :as rf]))

(defn- run-row
  [run selected?]
  (let [rid (:id run)
        status (or (:status run) "unknown")
        title (or (:title run) (:message run) rid)
        meta (str status)]
    [ui/list-row {:title (if (str/blank? title) rid title)
                  :meta meta
                  :description (or (:updated_at run) (:created_at run) "")
                  :selected? selected?
                  :on-click #(rf/dispatch [:darelwasl.app/select-agent-run rid])}]))

(defn- runs-list
  [runs selected]
  [:div.panel
   [:div.section-header
    [:div
     [:h2 "Runs"]
     [:div.meta "Stable run id until accepted or trashed."]]
    [:div.controls
     [ui/button {:variant :secondary
                 :on-click #(rf/dispatch [:darelwasl.app/fetch-agent-runs])}
      "Refresh"]]]
   (if (seq runs)
     [:div.list
      (for [r runs]
        ^{:key (:id r)}
        [run-row r (= selected (:id r))])]
     [ui/empty-state "No runs yet" "Create a run to start a preview review loop."])])

(defn- preview-links
  [urls]
  (when (seq urls)
    [:div.field-group
     [:div.meta "Preview links"]
     (for [[k v] urls]
       ^{:key (str k)}
       [:div
        [:a {:href v :target "_blank" :rel "noreferrer"} (str (name k) " preview")]
        [:div.meta v]])]))

(defn- detail-panel
  [{:keys [detail runs composer admins]}]
  (let [run (:data detail)
        selected (get-in runs [:selected])
        creating? (= :loading (:status composer))
        err (:error detail)
        preview (get run :preview)
        urls (get preview :urls)
        expires (get preview :expires_at)
        last-updated (or (get preview :last_updated_at) (get run :updated_at))]
    [:div.panel
     [:div.section-header
      [:div
       [:h2 "Agent control"]
       [:div.meta "Create → preview → verify → 6h review → accept/trash."]]]
     (when err
       [:div.form-error err])
     [:div.agent-control-detail-grid
      [:div
       [:div.section-header
        [:h3 "Create run"]
        [:div.meta "Starts a preview immediately. You can add reference points from the preview before applying changes."]]
       [:div.field-group
        [:label "Run id (optional)"]
        [ui/form-input {:value (:id composer)
                        :placeholder "kebab-case, e.g. site-hero-copy"
                        :on-change #(rf/dispatch [:darelwasl.app/set-agent-composer-field :id (.. % -target -value)])}]]
       [:div.field-group
        [:label "Title (shows in list)"]
        [ui/form-input {:value (or (:title composer) "")
                        :placeholder "e.g. Homepage hero copy tweak"
                        :on-change #(rf/dispatch [:darelwasl.app/set-agent-composer-field :title (.. % -target -value)])}]]
       [:div.field-group
        [:label "Mode"]
        [ui/select-field {:value (or (:mode composer) "both")
                          :on-change #(rf/dispatch [:darelwasl.app/set-agent-composer-field :mode (.. % -target -value)])}
         [:option {:value "both"} "Site + App"]
         [:option {:value "site"} "Site only"]
         [:option {:value "app"} "App only"]]]
       [:div.agent-control-actions
        [ui/button {:variant :primary
                    :disabled creating?
                    :on-click #(rf/dispatch [:darelwasl.app/create-agent-run])}
         (if creating? "Creating..." "Create run")]
        (when selected
          [ui/button {:variant :secondary
                      :on-click #(rf/dispatch [:darelwasl.app/start-agent-preview selected false])}
           "Refresh preview"])]
       (when selected
         [:<>
          [:div.section-header
           [:h3 "Apply changes"]
           [:div.meta "Write a request and/or use reference-point notes collected in the preview."]]
          [:div.field-group
           [:label "Request (optional)"]
           [:textarea.form-input {:rows 6
                                  :placeholder "Example: “Change the hero headline to … and update the CTA button text.”"
                                  :value (or (:request composer) "")
                                  :on-change #(rf/dispatch [:darelwasl.app/set-agent-composer-field :request (.. % -target -value)])}]]
          [:div.agent-control-actions
           [ui/button {:variant :primary
                       :on-click #(rf/dispatch [:darelwasl.app/start-agent-preview selected true])}
            "Apply changes"]
           [ui/button {:variant :danger
                       :on-click #(rf/dispatch [:darelwasl.app/trash-agent-run selected])}
            "Trash"]
           [ui/button {:variant :primary
                       :on-click #(rf/dispatch [:darelwasl.app/accept-agent-run selected])}
            "Accept + go live"]]
          (when (#{ "both" "site"} (or (:mode composer) "both"))
            [:div.meta "Tip: open the site preview → Select on → click elements → add notes → Save → Apply changes."])])
       (when-not selected
         [:div.meta "Tip: Create a run first. The preview starts immediately, then you can add reference points and apply changes when ready."])]
      [:div
       (if run
         [:<>
          [:div.field-group
           [:div.meta (str "Selected run: " (:id run) " · status: " (:status run))]]
          (when last-updated
            [:div.meta (str "Last preview update: " last-updated)])
          (when expires
            [:div.meta (str "Review window ends: " expires " (resets on preview update)")])
          [preview-links urls]
          (when (seq (:site_refs run))
            [:div.field-group
             [:div.meta (str "Reference points: " (count (:site_refs run)))]
             (for [r (:site_refs run)]
               ^{:key (:id r)}
               [:div.agent-control-ref
                [:div.meta (or (:url r) "")]
                [:div (or (:text r) "")]
                (when-not (str/blank? (or (:note r) ""))
                  [:div.meta (str "Note: " (:note r))])])])
          (when (= :ready (:status admins))
            [:div.field-group
             [:div.meta "Access"]
             [:div.meta (str "Allowlist: " (str/join ", " (or (:admins admins) [])))]
             (when (seq (:items admins))
               [:div.meta (str "Users visible here: " (str/join ", " (map :user/username (:items admins))))])])]
         [:div.meta "No run selected yet. Create a run to start a preview."])
      ]
     ]]))

(defn agent-control-shell
  []
  (let [{:keys [runs detail composer] :as ac} @(rf/subscribe [:darelwasl.app/agent-control])]
    (when (or (= :idle (get-in runs [:status]))
              (= :pending (get-in runs [:status])))
      (do
        (rf/dispatch [:darelwasl.app/fetch-agent-runs])
        (rf/dispatch [:darelwasl.app/fetch-agent-admins])))
    (when (and (= :pending (get-in ac [:detail :status]))
               (get-in runs [:selected]))
      (rf/dispatch [:darelwasl.app/fetch-agent-run (get-in runs [:selected])]))
    [shell/app-shell
     [:main.agent-control-layout
      [:aside.agent-control-sidebar
       [runs-list (get-in runs [:items]) (get-in runs [:selected])]]
      [:section.agent-control-main
       [detail-panel {:runs runs :detail detail :composer composer :admins (:admins ac)}]]]
     [:span "Agent control · Run-based preview and promotion gate."]]))
