(ns darelwasl.features.agent-control
  (:require [clojure.string :as str]
            [darelwasl.ui.components :as ui]
            [darelwasl.ui.shell :as shell]
            [re-frame.core :as rf]))

(defn- run-busy?
  [run]
  (boolean (some #(= "running" (:status %)) (or (:jobs run) []))))

(defn- preview-links
  [urls]
  (when (seq urls)
    [:div.field-group
     [:div.meta "Preview links"]
     (for [[k v] urls]
       ^{:key (str k)}
       [:div.agent-control-kv
        [:a {:href v :target "_blank" :rel "noreferrer"} (str (name k) " preview")]
        [:div.meta v]])]))

(defn- run-row
  [run selected?]
  (let [rid (:id run)
        status (or (:status run) "unknown")
        title (or (:title run) (:message run) rid)
        last-job (:latest_job run)
        job-meta (when last-job
                   (str (:kind last-job) " · " (:status last-job)))
        meta (str status (when job-meta (str " · " job-meta)))]
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
     [:div.meta "A run is the unit of review (refs, assets, jobs, logs)."]]
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

(defn- jobs-panel
  [{:keys [run]}]
  (let [run-id (:id run)
        jobs (vec (or (:jobs run) []))
        latest (:latest_job run)]
    [:div.field-group
     [:div.section-header
      [:div
       [:h3 "Jobs"]
       [:div.meta "What happened, in order, with logs."]]
      (when (and latest (:id latest))
        [:div.controls
         [ui/button {:variant :secondary
                     :on-click #(rf/dispatch [:darelwasl.app/fetch-agent-job-log run-id (:id latest)])}
          "View latest log"]])]
     (if (seq jobs)
       [:div.agent-control-jobs
        (for [j jobs
              :let [jid (:id j)]]
          ^{:key (or jid (str (hash j)))}
          [:div.agent-control-job
           [:div
            [:div (str (or (:kind j) "job") " · " (or (:status j) "unknown"))]
            (when-let [err (:error j)]
              [:div.meta (str "error: " err)])
            (when-let [exit (:exit j)]
              [:div.meta (str "exit: " exit)])
            (when-let [at (:started_at j)]
              [:div.meta (str "started: " at)])
            (when-let [at (:finished_at j)]
              [:div.meta (str "finished: " at)])]
           [:div
            [ui/button {:variant :secondary
                        :disabled (str/blank? (or jid ""))
                        :on-click #(rf/dispatch [:darelwasl.app/fetch-agent-job-log run-id jid])}
             "View log"]]])]
       [:div.meta "No jobs yet. Start a preview or apply changes to generate logs."])]))

(defn- log-panel
  [{:keys [run log]}]
  (let [run-id (:id run)
        job-id (:job log)
        status (:status log)
        text (:text log)
        err (:error log)]
    [:div.field-group
     [:div.section-header
      [:div
       [:h3 "Job log"]
       [:div.meta "Text output from preview/agent/promote scripts."]]
      [:div.controls
       (when (and run-id job-id (not= status :loading))
         [ui/button {:variant :secondary
                     :on-click #(rf/dispatch [:darelwasl.app/fetch-agent-job-log run-id job-id])}
          "Refresh"])
       [ui/button {:variant :secondary
                   :on-click #(rf/dispatch [:darelwasl.app/select-agent-job-log nil])}
        "Clear"]]]
     (cond
       (= status :loading) [:div.meta "Loading log..."]
       (= status :error) [:div.form-error (or err "Unable to load log.")]
       (str/blank? (or text "")) [:div.meta "Select a job to view its log."]
       :else [:pre.agent-control-log text])]))

(defn- refs-panel
  [{:keys [run]}]
  (let [run-id (:id run)
        refs (vec (or (:site_refs run) []))]
    [:div.field-group
     [:div.section-header
      [:div
       [:h3 "Reference points"]
       [:div.meta "Captured from the preview overlay (Select on → click → add note → Save)."]]
      (when (seq refs)
        [:div.controls
         [ui/button {:variant :secondary
                     :on-click #(rf/dispatch [:darelwasl.app/clear-agent-site-refs run-id])}
          "Clear all"]])]
     (if (seq refs)
       (for [r refs]
         ^{:key (:id r)}
         [:div.agent-control-ref
          [:div.meta (or (:url r) "")]
          [:div (or (:text r) "")]
          (when-not (str/blank? (or (:note r) ""))
            [:div.meta (str "Note: " (:note r))])
          [:div.agent-control-actions
           [ui/button {:variant :secondary
                       :on-click #(rf/dispatch [:darelwasl.app/delete-agent-site-ref run-id (:id r)])}
            "Remove"]]])
       [:div.meta "No reference points saved for this run."])]))

(defn- assets-panel
  [{:keys [run upload]}]
  (let [run-id (:id run)
        assets (vec (or (:site_assets run) []))
        uploading? (= :uploading (:status upload))
        err (:error upload)
        file (:file upload)
        slug (or (:slug upload) "")
        note (or (:note upload) "")]
    [:div.field-group
     [:div.section-header
      [:div
       [:h3 "Assets"]
       [:div.meta "Upload files (images/SVG/PDF/Markdown). They go to Library and attach to this run."]]
      (when (seq assets)
        [:div.controls
         [ui/button {:variant :secondary
                     :on-click #(rf/dispatch [:darelwasl.app/clear-agent-site-assets run-id])}
          "Clear all"]])]
     (when err
       [:div.form-error err])
     [:div.field-group
      [:label "Upload file"]
      [:input.form-input {:type "file"
                          :data-testid "agent-asset-upload-input"
                          :on-change #(let [files (.. % -target -files)
                                            f (when (and files (pos? (.-length files)))
                                                (aget files 0))]
                                        (rf/dispatch [:darelwasl.app/set-agent-asset-upload-file f]))}]
      (when (and file (.-name file))
        [:div.meta (str "Selected: " (.-name file))])]
     [:div.field-group
      [:label "Slug (optional)"]
      [ui/form-input {:value slug
                      :placeholder "e.g. dar-el-wasl-logo"
                      :on-change #(rf/dispatch [:darelwasl.app/set-agent-asset-upload-slug (.. % -target -value)])}]]
     [:div.field-group
      [:label "Note (optional)"]
      [:textarea.form-input {:rows 2
                             :placeholder "Example: Use this image as the homepage hero."
                             :value note
                             :on-change #(rf/dispatch [:darelwasl.app/set-agent-asset-upload-note (.. % -target -value)])}]]
     [:div.agent-control-actions
      [ui/button {:variant :primary
                  :disabled (or uploading? (nil? file))
                  :data-testid "agent-asset-upload-btn"
                  :on-click #(rf/dispatch [:darelwasl.app/upload-agent-site-asset run-id])}
       (if uploading? "Uploading..." "Upload")]]
     (if (seq assets)
       (for [a assets]
         ^{:key (or (:id a) (str (hash a)))}
         [:div.agent-control-ref
          [:div (or (:name a) "")]
          (when-not (str/blank? (or (:ref a) ""))
            [:div.meta (str "Ref: " (:ref a))])
          (when-not (str/blank? (or (:note a) ""))
            [:div.meta (str "Note: " (:note a))])
          [:div.agent-control-actions
           (when-not (str/blank? (or (:url a) ""))
             [:a.meta {:href (:url a) :target "_blank" :rel "noreferrer"} "Open file"])
           [ui/button {:variant :secondary
                       :on-click #(rf/dispatch [:darelwasl.app/delete-agent-site-asset run-id (:id a)])}
           "Remove"]]])
       [:div.meta "No assets uploaded for this run."])]))

(defn- detail-panel
  [{:keys [detail runs composer admins log asset-upload]}]
  (let [run (:data detail)
        selected (get-in runs [:selected])
        creating? (= :loading (:status composer))
        err (:error detail)
        preview (get run :preview)
        urls (get preview :urls)
        expires (get preview :expires_at)
        last-updated (or (get preview :last_updated_at) (get run :updated_at))
        busy? (run-busy? run)
        can-promote? (true? (:can_promote run))]
    [:div.panel
     [:div.section-header
      [:div
       [:h2 "Agent control"]
       [:div.meta "Run-based preview and promotion gate. Use jobs + logs to verify what happened."]]]
     (when err
       [:div.form-error err])
     [:div.agent-control-detail-grid
      [:div
       [:div.section-header
        [:h3 "Create run"]
        [:div.meta "Create a run id to collect preview reference points and uploaded assets, then apply changes."]]
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
                      :disabled busy?
                      :on-click #(rf/dispatch [:darelwasl.app/start-agent-preview selected false])}
           (if busy? "Preview running..." "Start/refresh preview")])]
       (when-not selected
         [:div.meta "Tip: Create a run first. Then start a preview and capture reference points and assets."])
       (when selected
         [:<>
          [:div.section-header
           [:h3 "Apply changes"]
           [:div.meta "Write a request and/or rely on reference-point/asset notes to drive the agent."]]
          [:div.field-group
           [:label "Request (optional)"]
           [:textarea.form-input {:rows 6
                                  :placeholder "Example: Change the hero headline and swap the logo to the uploaded SVG."
                                  :value (or (:request composer) "")
                                  :on-change #(rf/dispatch [:darelwasl.app/set-agent-composer-field :request (.. % -target -value)])}]]
          [:div.agent-control-actions
           [ui/button {:variant :primary
                       :disabled busy?
                       :on-click #(rf/dispatch [:darelwasl.app/start-agent-preview selected true])}
            (if busy? "Applying..." "Apply changes")]
           [ui/button {:variant :secondary
                       :disabled busy?
                       :on-click #(rf/dispatch [:darelwasl.app/fetch-agent-run selected])}
            "Refresh status"]
           [ui/button {:variant :danger
                       :disabled busy?
                       :on-click #(rf/dispatch [:darelwasl.app/trash-agent-run selected])}
            "Trash"]
           [ui/button {:variant :primary
                       :disabled (or busy? (not can-promote?))
                       :on-click #(rf/dispatch [:darelwasl.app/accept-agent-run selected])}
            "Accept + go live"]]
          (when (and run (not can-promote?))
            [:div.meta "Accept is only enabled when preview is ready and all jobs finished successfully."])
          [:div.section-header
           [:h3 "Request changes"]
           [:div.meta "Leave feedback without applying changes yet (revision history is kept)."]]
          [:div.field-group
           [:label "Revision message"]
           [:textarea.form-input {:rows 3
                                  :placeholder "Example: The CTA needs higher contrast and the logo should be smaller on mobile."
                                  :value (or (:revise composer) "")
                                  :on-change #(rf/dispatch [:darelwasl.app/set-agent-composer-field :revise (.. % -target -value)])}]]
          [:div.agent-control-actions
           [ui/button {:variant :secondary
                       :disabled (or busy? (str/blank? (str/trim (or (:revise composer) ""))))
                       :on-click #(rf/dispatch [:darelwasl.app/revise-agent-run selected (:revise composer)])}
            "Save revision"]]
          (when (seq (:revisions run))
            [:div.field-group
             [:div.meta (str "Revisions (" (count (:revisions run)) ")")]
             (for [{:keys [at message]} (reverse (vec (:revisions run)))]
               ^{:key (str at "-" (hash message))}
               [:div.agent-control-ref
                [:div.meta (or at "")]
                [:div (or message "")]])])
          (when (#{ "both" "site"} (or (:mode composer) "both"))
            [:div.meta "Tip: open the site preview → Select on → click elements → add notes → Save. Also upload assets in the preview overlay."])])]
      [:div
       (if run
         [:<>
          [:div.field-group
           [:div.meta (str "Selected run: " (:id run) " · status: " (:status run)
                           (when can-promote? " · ready to promote"))]]
          (when-let [e (:error run)]
            [:div.form-error e])
          (when last-updated
            [:div.meta (str "Last preview update: " last-updated)])
          (when expires
            [:div.meta (str "Review window ends: " expires " (resets on preview update)")])
          [preview-links urls]
          [assets-panel {:run run :upload asset-upload}]
          [refs-panel {:run run}]
          [jobs-panel {:run run}]
          [log-panel {:run run :log log}]
          (when (= :ready (:status admins))
            [:div.field-group
             [:div.meta "Access"]
             [:div.meta (str "Allowlist: " (str/join ", " (or (:admins admins) [])))]
             (when (seq (:items admins))
               [:div.meta (str "Users visible here: " (str/join ", " (map :user/username (:items admins))))])])]
         [:div.meta "No run selected yet. Create a run to start a preview."])]]]))

(defn agent-control-shell
  []
  (let [{:keys [runs detail composer log asset-upload] :as ac} @(rf/subscribe [:darelwasl.app/agent-control])]
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
       [detail-panel {:runs runs :detail detail :composer composer :admins (:admins ac) :log log :asset-upload asset-upload}]]]
     [:span "Agent control · Run-based preview and promotion gate."]]))
