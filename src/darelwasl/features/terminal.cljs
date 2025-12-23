(ns darelwasl.features.terminal
   (:require [clojure.string :as str]
             [darelwasl.ui.components :as ui]
             [darelwasl.ui.shell :as shell]
             [re-frame.core :as rf]
             [reagent.core :as r]))

 (defn- status-label
   [session]
   (case (:status session)
     :running "Running"
     :idle "Idle"
     :complete "Complete"
     "Idle"))

(defn- session-row
   [session selected?]
   (let [ports (:ports session)
         meta (str (status-label session)
                   (when-let [app (:app ports)] (str " · app:" app)))]
     [ui/list-row {:title (:name session)
                   :meta meta
                   :selected? selected?
                   :class "terminal-session-row"
                   :on-click #(rf/dispatch [:darelwasl.app/terminal-select-session session])}]))

(defn- quick-actions
  [output]
  (let [text (str/lower-case (or output ""))
        allow? (str/includes? text "allow codex to work in this folder")
        enter? (str/includes? text "press enter to continue")
        actions (cond-> []
                  allow? (into [[ui/button {:variant :secondary
                                            :on-click #(rf/dispatch [:darelwasl.app/terminal-send-keys ["1" "Enter"]])}
                                 "Allow edits"]
                                [ui/button {:variant :secondary
                                            :on-click #(rf/dispatch [:darelwasl.app/terminal-send-keys ["2" "Enter"]])}
                                 "Ask for approval"]])
                  enter? (conj [ui/button {:variant :secondary
                                           :on-click #(rf/dispatch [:darelwasl.app/terminal-send-keys ["Enter"]])}
                                "Press Enter"]))]
    (when (seq actions)
      (into [:div.terminal-quick-actions] actions))))

(defn- terminal-list
  []
  (let [{:keys [sessions status error notice]} @(rf/subscribe [:darelwasl.app/terminal])]
     [:div.panel.terminal-list-panel
      [:div.section-header
       [:div
        [:h2 "Terminal sessions"]
        [:div.meta "Parallel Codex sessions with isolated repos and data."]]
       [:div.section-actions
        [ui/button {:variant :secondary
                    :on-click #(rf/dispatch [:darelwasl.app/fetch-terminal-sessions])}
         "Refresh"]
        [ui/button {:on-click #(rf/dispatch [:darelwasl.app/terminal-create-session])}
         "New session"]]]
      (when notice
        [:div.form-success notice])
      (case status
        :loading [ui/loading-state "Loading sessions..."]
        :error [ui/error-state (or error "Unable to load terminal sessions.")
                #(rf/dispatch [:darelwasl.app/fetch-terminal-sessions])]
        :empty [ui/empty-state "No sessions yet" "Create a session to start work."]
        [:div.terminal-session-list
         (for [session sessions]
           ^{:key (:id session)}
           [session-row session false])])]))

(defn- scroll-to-bottom!
  [node]
  (set! (.-scrollTop node) (.-scrollHeight node)))

(def terminal-output
  (let [node (atom nil)
        stick? (r/atom true)]
    (r/create-class
      {:display-name "terminal-output"
       :component-did-mount
       (fn [_]
         (when-let [el @node]
           (scroll-to-bottom! el)))
       :component-did-update
       (fn [this old-argv]
         (let [old-props (second old-argv)
               new-props (second (r/argv this))
               old-output (:output old-props)
               new-output (:output new-props)]
           (when (and (not= old-output new-output) (empty? new-output))
             (reset! stick? true))
           (when (and @stick? @node)
             (scroll-to-bottom! @node))))
       :reagent-render
       (fn [{:keys [output error]}]
         [:div.terminal-output
          {:ref (fn [el] (reset! node el))
           :on-scroll (fn [event]
                        (let [el (.-target event)
                              distance (- (.-scrollHeight el)
                                          (.-scrollTop el)
                                          (.-clientHeight el))]
                          (reset! stick? (<= distance 40))))}
          [:pre output]
          (when error
            [:div.form-error error])])})))

 (defn- terminal-chat
   []
   (let [{:keys [selected output input error sending? verifying? app-ready?]} @(rf/subscribe [:darelwasl.app/terminal])]
     (if-not selected
       [:div.panel.terminal-empty
        [:div.state.empty
         [:strong "Select a session"]
         [:p "Choose a session to view output and send commands."]]]
       (let [protocol (if (= "https:" (.-protocol js/window.location))
                        "http:"
                        (.-protocol js/window.location))
             host (.-hostname js/window.location)
             app-port (get-in selected [:ports :app])
             site-port (get-in selected [:ports :site])
             app-link (if app-port
                        (str protocol "//" host ":" app-port "/")
                        "#")
             site-link (if site-port
                         (str protocol "//" host ":" site-port "/")
                         "#")]
         [:div.terminal-chat
          [:div.terminal-chat__header
           [:button.terminal-back
            {:type "button"
             :aria-label "Back to sessions"
             :on-click #(rf/dispatch [:darelwasl.app/terminal-back])}
            "←"]
           [:div
            [:div.terminal-title (:name selected)]
            [:div.terminal-meta (status-label selected)]]
           [:div.terminal-actions
            [:a.terminal-action-link.button.secondary
             {:href app-link
              :target "_blank"
              :rel "noreferrer"
              :aria-disabled (or (not app-port) (not app-ready?))
              :on-click #(when (or (not app-port) (not app-ready?))
                           (.preventDefault %))}
             "Open app"]
            (when site-port
              [:a.terminal-action-link.button.secondary
               {:href site-link
                :target "_blank"
                :rel "noreferrer"}
               "Open site"])
            [ui/button {:variant :secondary
                        :disabled verifying?
                        :on-click #(rf/dispatch [:darelwasl.app/terminal-verify-session])}
             (if verifying? "Creating PR..." "Verify & create PR")]
            [ui/button {:variant :danger
                        :on-click #(rf/dispatch [:darelwasl.app/terminal-complete-session])}
             "Complete"]]]
          [:div.terminal-link-row
           [:span.meta "App:"]
           (cond
             (not app-port) [:span.meta "Unavailable"]
             (not app-ready?) [:span.meta "Starting..."]
             :else [:a.terminal-link-text
                    {:href app-link
                     :target "_blank"
                     :rel "noreferrer"}
                    app-link])
           (when site-port
             [:<>
              [:span.meta "Site:"]
              [:a.terminal-link-text
               {:href site-link
                :target "_blank"
                :rel "noreferrer"}
               site-link]])]
          [terminal-output {:output output
                            :error error}]
          (when-let [actions (quick-actions output)]
            actions)
          [:div.terminal-input
           [ui/form-input {:value input
                           :placeholder "Send instructions..."
                           :on-change #(rf/dispatch [:darelwasl.app/terminal-update-input (.. % -target -value)])
                           :on-key-down #(when (= "Enter" (.-key %))
                                           (.preventDefault %)
                                           (rf/dispatch [:darelwasl.app/terminal-send-input]))}]
           [ui/button {:disabled sending?
                       :on-click #(rf/dispatch [:darelwasl.app/terminal-send-input])}
            (if sending? "Sending..." "Send")]]]))))

(defn terminal-shell
  []
  (let [selected (get-in @(rf/subscribe [:darelwasl.app/terminal]) [:selected])]
    [shell/app-shell
     [:main.terminal-layout
      (if selected
        [terminal-chat]
        [terminal-list])]]))
