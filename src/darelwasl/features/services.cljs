(ns darelwasl.features.services
  (:require [clojure.string :as str]
            [darelwasl.ui.components :as ui]
            [darelwasl.ui.shell :as shell]
            [re-frame.core :as rf]))

(defn- normalize-status
  [value]
  (let [raw (some-> value str str/trim)]
    (when (and raw (not (str/blank? raw)))
      (str/lower-case raw))))

(defn- status-label
  [status]
  (case status
    "ok" "Healthy"
    "degraded" "Degraded"
    "missing" "Missing"
    "error" "Error"
    "unknown" "Unknown"
    (if (seq status) (str/capitalize status) "Unknown")))

(defn- format-time
  [ms]
  (when (number? ms)
    (.toLocaleTimeString (js/Date. ms))))

(defn- service-card
  [service restarting?]
  (let [health (:health service)
        status (normalize-status (:status health))
        datastore (:datastore health)
        db-status (normalize-status (:status datastore))
        checked-at (format-time (:checked-at health))
        latency-ms (:latency-ms health)
        message (:message health)
        url (:url service)]
    [:div.service-card
     [:div.service-card__header
      [:div
       [:div.service-label (:label service)]
       (when url [:div.meta.service-url url])]
      [:div.service-status {:class status}
       [:span.service-status-dot]
       [:span (status-label status)]]]
     (when message
       [:div.service-message message])
     (when db-status
       [:div.meta (str "Datastore: " (status-label db-status))])
     (when (or checked-at latency-ms)
       [:div.service-meta
        (when checked-at
          [:span.meta (str "Checked " checked-at)])
        (when latency-ms
          [:span.meta (str "Latency " latency-ms "ms")])])
     [:div.service-actions
      (if (:restartable? service)
        [ui/button {:variant :secondary
                    :disabled restarting?
                    :on-click #(rf/dispatch [:darelwasl.app/restart-service (:id service)])}
         (if restarting? "Restarting..." "Restart")]
        [:span.meta "No controls available"])]]))

(defn- services-grid
  [services restarting]
  [:div.services-grid
   (for [service services]
     (let [sid (:id service)]
       ^{:key (str "service-" sid)}
       [service-card service (contains? restarting (str sid))]))])

(defn services-shell
  []
  (let [{:keys [status error items notice restarting]} @(rf/subscribe [:darelwasl.app/services])]
    (when (or (= status :idle) (= status :pending))
      (rf/dispatch [:darelwasl.app/fetch-services]))
    [shell/app-shell
     [:main.services-layout
      [:div.panel.services-panel
       [:div.section-header
        [:div
         [:h2 "Services"]
         [:div.meta "Health checks and restart controls for core services."]]
        [:div.controls
         [ui/button {:variant :secondary
                     :on-click #(rf/dispatch [:darelwasl.app/fetch-services])}
          "Refresh"]]]
       (when notice
         [:div.form-success notice])
       (case status
         :loading [ui/loading-state "Checking services..."]
         :idle [ui/loading-state "Checking services..."]
         :pending [ui/loading-state "Checking services..."]
         :error [ui/error-state (or error "Unable to load services.")
                 #(rf/dispatch [:darelwasl.app/fetch-services])]
         :empty [ui/empty-state "No services reported" "Check configuration or refresh the page."]
         [services-grid items restarting])]]
     [:span "Service status view · Restart core processes without leaving the UI."]]))
