(ns darelwasl.features.knowledge
  (:require [clojure.string :as str]
            [darelwasl.ui.components :as ui]
            [darelwasl.ui.shell :as shell]
            [darelwasl.util :as util]
            [re-frame.core :as rf]))

(defn- type-label
  [t]
  (case t
    :doc.type/law "Law"
    :doc.type/implementing-regulation "Implementing regulation"
    :doc.type/regulatory-rule "Regulatory rule"
    :doc.type/guidance "Guidance"
    :doc.type/decision "Decision"
    :doc.type/procedure "Procedure"
    :doc.type/treaty "Treaty"
    :doc.type/draft "Draft"
    :doc.type/dataset "Dataset"
    :doc.type/api "API"
    "Unknown"))

(defn- authority-band-options
  []
  [{:id nil :label "All"}
   {:id :law :label "Law (80-100)"}
   {:id :regulation :label "Regulation (60-79)"}
   {:id :guidance :label "Guidance (40-59)"}
   {:id :draft :label "Draft (0-20)"}])

(defn- language-options
  []
  [{:id nil :label "All"}
   {:id :lang/ar :label "Arabic"}
   {:id :lang/en :label "English"}
   {:id :lang/mixed :label "Mixed"}
   {:id :lang/unknown :label "Unknown"}])

(defn- doc-type-options
  []
  [{:id nil :label "All"}
   {:id :doc.type/law :label "Law"}
   {:id :doc.type/implementing-regulation :label "Implementing regulation"}
   {:id :doc.type/regulatory-rule :label "Regulatory rule"}
   {:id :doc.type/guidance :label "Guidance"}
   {:id :doc.type/decision :label "Decision"}
   {:id :doc.type/procedure :label "Procedure"}
   {:id :doc.type/treaty :label "Treaty"}
   {:id :doc.type/draft :label "Draft"}
   {:id :doc.type/dataset :label "Dataset"}
   {:id :doc.type/api :label "API"}
   {:id :doc.type/unknown :label "Unknown"}])

(defn- filter-select
  [{:keys [label value options on-change ns]}]
  [:div.knowledge-filter
   [:label label]
   [ui/select-field {:value (or (some-> value name) "")
                     :on-change #(let [v (.. % -target -value)]
                                   (on-change (when-not (str/blank? v)
                                                (if ns (keyword ns v) (keyword v)))))}
    (for [{:keys [id label]} options]
      ^{:key (str label)}
      [:option {:value (if id (name id) "")} label])]])

(defn- filter-input
  [{:keys [label value placeholder on-change]}]
  [:div.knowledge-filter
   [:label label]
   [ui/form-input {:value (or value "")
                   :placeholder placeholder
                   :on-change #(on-change (.. % -target -value))}]])

(defn- filter-date
  [{:keys [label value on-change]}]
  [:div.knowledge-filter
   [:label label]
   [:input.form-input {:type "date"
                       :value (or value "")
                       :on-change #(on-change (.. % -target -value))}]])

(defn- search-filters
  []
  (let [{:keys [filters status]} @(rf/subscribe [:darelwasl.app/knowledge])
        loading? (= status :loading)]
    [:div.knowledge-filters
     [filter-input {:label "Search"
                    :value (:query filters)
                    :placeholder "Search across instrument text"
                    :on-change #(rf/dispatch [:darelwasl.app/set-knowledge-filter :query %])}]
     [filter-select {:label "Type"
                     :value (:doc-type filters)
                     :options (doc-type-options)
                     :ns "doc.type"
                     :on-change #(rf/dispatch [:darelwasl.app/set-knowledge-filter :doc-type %])}]
     [filter-select {:label "Authority band"
                     :value (:authority-band filters)
                     :options (authority-band-options)
                     :on-change #(rf/dispatch [:darelwasl.app/set-knowledge-filter :authority-band %])}]
     [filter-select {:label "Language"
                     :value (:language filters)
                     :options (language-options)
                     :ns "lang"
                     :on-change #(rf/dispatch [:darelwasl.app/set-knowledge-filter :language %])}]
     [filter-input {:label "Organization"
                    :value (:org filters)
                    :placeholder "Issuer or portal"
                    :on-change #(rf/dispatch [:darelwasl.app/set-knowledge-filter :org %])}]
     [filter-input {:label "Topic"
                    :value (:topic filters)
                    :placeholder "e.g. tax, labor, privacy"
                    :on-change #(rf/dispatch [:darelwasl.app/set-knowledge-filter :topic %])}]
     [filter-date {:label "Issued from"
                   :value (:issued-from filters)
                   :on-change #(rf/dispatch [:darelwasl.app/set-knowledge-filter :issued-from %])}]
     [filter-date {:label "Issued to"
                   :value (:issued-to filters)
                   :on-change #(rf/dispatch [:darelwasl.app/set-knowledge-filter :issued-to %])}]
     [filter-date {:label "Effective from"
                   :value (:effective-from filters)
                   :on-change #(rf/dispatch [:darelwasl.app/set-knowledge-filter :effective-from %])}]
     [filter-date {:label "Effective to"
                   :value (:effective-to filters)
                   :on-change #(rf/dispatch [:darelwasl.app/set-knowledge-filter :effective-to %])}]
     [filter-date {:label "Published from"
                   :value (:publication-from filters)
                   :on-change #(rf/dispatch [:darelwasl.app/set-knowledge-filter :publication-from %])}]
     [filter-date {:label "Published to"
                   :value (:publication-to filters)
                   :on-change #(rf/dispatch [:darelwasl.app/set-knowledge-filter :publication-to %])}]
     [:div.knowledge-filter
      [:label "Cited by decisions"]
      [:div.knowledge-toggle
       [:input {:type "checkbox"
                :checked (true? (:has-decisions? filters))
                :disabled loading?
                :on-change #(rf/dispatch [:darelwasl.app/set-knowledge-filter :has-decisions? (.. % -target -checked)])}]
       [:span "Only show instruments with decisions"]]]
     [:div.knowledge-actions
      [ui/button {:variant :secondary
                  :disabled loading?
                  :on-click #(rf/dispatch [:darelwasl.app/fetch-knowledge])}
       (if loading? "Searching..." "Search")]
      [ui/button {:variant :secondary
                  :disabled loading?
                  :on-click #(do
                               (rf/dispatch [:darelwasl.app/set-knowledge-filter :query ""]) 
                               (rf/dispatch [:darelwasl.app/fetch-knowledge]))}
       "Reset"]]]))

(defn- result-row
  [entry selected?]
  (let [title (or (:doc/title entry) "Untitled")
        doc-type (type-label (:doc/type entry))
        rank (or (:instrument/authority-rank entry) 0)
        org (or (:doc/org entry) "—")
        issued (some-> (:doc/issued-at entry) util/format-date)
        effective (some-> (:doc/effective-at entry) util/format-date)
        seen (some-> (:doc/last-seen-at entry) util/format-date)
        links (or (:doc/source-link-count entry) 0)
        instrument-id (:instrument/id entry)]
    [:div.knowledge-row {:class (when selected? "selected")
                         :on-click #(do
                                      (rf/dispatch [:darelwasl.app/select-knowledge-instrument instrument-id])
                                      (rf/dispatch [:darelwasl.app/fetch-knowledge-instrument instrument-id]))}
     [:div.knowledge-cell.title title]
     [:div.knowledge-cell doc-type]
     [:div.knowledge-cell (str rank)]
     [:div.knowledge-cell org]
     [:div.knowledge-cell (or issued "—")]
     [:div.knowledge-cell (or effective "—")]
     [:div.knowledge-cell (or seen "—")]
     [:div.knowledge-cell (str links)]]))

(defn- results-table
  []
  (let [{:keys [results status error selected]} @(rf/subscribe [:darelwasl.app/knowledge])]
    [:div.panel.knowledge-results
     [:div.section-header
      [:div
       [:h2 "Results"]
       [:span.meta (str (count results) " documents")]]]
     (case status
       :loading [ui/loading-state "Searching knowledge base..."]
       :error [ui/error-state (or error "Unable to load results") #(rf/dispatch [:darelwasl.app/fetch-knowledge])]
       (if (seq results)
         [:div.knowledge-table
          [:div.knowledge-row.header
           [:div.knowledge-cell.title "Title"]
           [:div.knowledge-cell "Type"]
           [:div.knowledge-cell "Rank"]
           [:div.knowledge-cell "Org"]
           [:div.knowledge-cell "Issued"]
           [:div.knowledge-cell "Effective"]
           [:div.knowledge-cell "Last seen"]
           [:div.knowledge-cell "Links"]]
          (for [entry results]
            ^{:key (str (:doc/id entry))}
            [result-row entry (= (:instrument/id entry) selected)])]
         [ui/empty-state "No documents yet" "Adjust filters or run a crawl to populate results."]))]))

(defn- detail-tabs
  [current]
  (let [tabs [{:id :overview :label "Overview"}
              {:id :text :label "Text"}
              {:id :versions :label "Versions"}
              {:id :references :label "References"}
              {:id :decisions :label "Decisions"}
              {:id :files :label "Files"}]]
    [:div.knowledge-tabs
     (for [{:keys [id label]} tabs]
       ^{:key (name id)}
       [:button.tab-button {:type "button"
                            :class (when (= id current) "active")
                            :on-click #(rf/dispatch [:darelwasl.app/set-knowledge-detail-tab id])}
        label])]))

(defn- overview-panel
  [instrument versions]
  (let [topics (some->> (:instrument/topics instrument)
                        (map name)
                        (remove str/blank?)
                        (str/join ", "))]
    [:div.knowledge-detail-section
     [:h3 (or (:instrument/name instrument) "Instrument")]
     [:div.meta (str "Authority rank: " (or (:instrument/authority-rank instrument) 0))]
     [:div.meta (str "Organization: " (or (:instrument/org instrument) "—"))]
     [:div.meta (str "Jurisdiction: " (name (or (:instrument/jurisdiction instrument) :jurisdiction/ksa)))]
     (when (seq topics)
       [:div.meta (str "Topics: " topics)])
     [:div.meta (str "Versions: " (count versions))]]))

(defn- text-panel
  [sections]
  (if (seq sections)
    [:div.knowledge-text
     (for [sec sections]
       ^{:key (str (:section/id sec))}
       [:div.knowledge-section
        [:div.section-title
         [:span.section-number (or (:section/number sec) "")]
         [:strong (or (:section/title sec) "Section")]]
        [:div.section-body (or (:section/text sec) "")]])]
    [ui/empty-state "No extracted sections" "Run extraction to populate section text."]))

(defn- versions-panel
  [versions]
  (if (seq versions)
    [:div.knowledge-versions
     (for [v versions]
       ^{:key (str (:instrument.version/id v))}
       [:div.knowledge-version
        [:div.title (or (:instrument.version/label v) "Version")]
        [:div.meta (or (some-> (:instrument.version/amended-at v) util/format-date) "—")]
        [:div.meta (str (count (:instrument.version/docs v)) " files")]])]
    [ui/empty-state "No versions" "Versions will appear after crawl ingestion."]))

(defn- references-panel
  []
  [ui/empty-state "References pending" "Cross references will show once xrefs are resolved."])

(defn- decisions-panel
  []
  [ui/empty-state "No decisions linked" "GSTC decisions citing this instrument will appear here."])

(defn- files-panel
  [versions]
  (let [docs (mapcat :instrument.version/docs versions)]
    (if (seq docs)
      [:div.knowledge-files
       (for [doc docs]
         ^{:key (str (:doc/id doc))}
         [:div.knowledge-file
          [:div.title (or (:doc/title doc) "Document")]
          [:div.meta (str "Type: " (type-label (:doc/type doc)))]])]
      [ui/empty-state "No files" "Source files will appear here after ingestion."])) )

(defn- detail-panel
  []
  (let [{:keys [detail selected]} @(rf/subscribe [:darelwasl.app/knowledge])
        {:keys [status error tab instrument versions sections]} detail]
    [:div.panel.knowledge-detail
     [:div.section-header
      [:div
       [:h2 "Instrument"]
       [:span.meta (if selected "Selected instrument" "Pick a result to inspect")]]]
     (cond
       (= status :loading) [ui/loading-state "Loading instrument..."]
       (= status :error) [ui/error-state (or error "Unable to load instrument") #(rf/dispatch [:darelwasl.app/fetch-knowledge-instrument selected])]
       (nil? instrument) [ui/empty-state "No instrument selected" "Select a document from the results list."]
       :else
       [:div
        [detail-tabs tab]
        (case tab
          :overview [overview-panel instrument versions]
          :text [text-panel sections]
          :versions [versions-panel versions]
          :references [references-panel]
          :decisions [decisions-panel]
          :files [files-panel versions]
          [overview-panel instrument versions])])]))

(defn- sources-panel
  []
  (let [sources @(rf/subscribe [:darelwasl.app/knowledge])
        {:keys [status error runs]} (:sources sources)]
    [:div.panel.knowledge-sources
     [:div.section-header
      [:div
       [:h2 "Source Explorer"]
       [:span.meta "Adapters and crawl runs"]]
      [:div.controls
       [ui/button {:variant :secondary
                   :disabled (= status :loading)
                   :on-click #(rf/dispatch [:darelwasl.app/fetch-knowledge-sources])}
        "Refresh"]]]
     (case status
       :loading [ui/loading-state "Loading sources..."]
       :error [ui/error-state (or error "Unable to load sources") #(rf/dispatch [:darelwasl.app/fetch-knowledge-sources])]
       (if (seq runs)
         [:div.knowledge-source-list
          (for [run runs]
            ^{:key (str (:crawl.run/id run))}
            [:div.knowledge-source
             [:div.title (str "Run " (:crawl.run/id run))]
             [:div.meta (str "Status: " (name (:crawl.run/status run)))]
             [:div.meta (str "Started: " (some-> (:crawl.run/started-at run) util/format-date))]
             (when-let [finished (:crawl.run/finished-at run)]
               [:div.meta (str "Finished: " (util/format-date finished))])
             [:div.meta (str "Metrics: " (or (:crawl.run/source-metrics run) "—"))]])]
         [ui/empty-state "No crawl runs yet" "Run the crawler to populate source history."]))]))

(defn knowledge-shell
  []
  (let [{:keys [view]} @(rf/subscribe [:darelwasl.app/knowledge])]
    [shell/app-shell
     [:div.knowledge-shell
      [:div.knowledge-header
       [:div
        [:h1 "Saudi Business Domain Map"]
        [:div.meta "Search, version, and cross-reference instruments across official sources."]]
       [:div.knowledge-view-tabs
        [:button.tab-button {:type "button"
                             :class (when (= view :search) "active")
                             :on-click #(rf/dispatch [:darelwasl.app/set-knowledge-view :search])}
         "Knowledge Search"]
        [:button.tab-button {:type "button"
                             :class (when (= view :sources) "active")
                             :on-click #(rf/dispatch [:darelwasl.app/set-knowledge-view :sources])}
         "Source Explorer"]]]
      (if (= view :sources)
        [sources-panel]
        [:div.knowledge-layout
         [search-filters]
         [:div.knowledge-columns
          [results-table]
          [detail-panel]]])]
     "Knowledge graph"]))
