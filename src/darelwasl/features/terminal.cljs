(ns darelwasl.features.terminal
   (:require [clojure.string :as str]
             [darelwasl.ui.components :as ui]
             [darelwasl.ui.shell :as shell]
             [re-frame.core :as rf]))

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
  (let [{:keys [sessions status error]} @(rf/subscribe [:darelwasl.app/terminal])]
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
      (case status
        :loading [ui/loading-state "Loading sessions..."]
        :error [ui/error-state (or error "Unable to load terminal sessions.")
                #(rf/dispatch [:darelwasl.app/fetch-terminal-sessions])]
        :empty [ui/empty-state "No sessions yet" "Create a session to start work."]
        [:div.terminal-session-list
         (for [session sessions]
           ^{:key (:id session)}
           [session-row session false])])]))

 (defn- terminal-chat
   []
   (let [{:keys [selected output input error sending?]} @(rf/subscribe [:darelwasl.app/terminal])]
     (if-not selected
       [:div.panel.terminal-empty
        [:div.state.empty
         [:strong "Select a session"]
         [:p "Choose a session to view output and send commands."]]]
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
          [ui/button {:variant :danger
                      :on-click #(rf/dispatch [:darelwasl.app/terminal-complete-session])}
           "Complete"]]]
       [:div.terminal-output
         [:pre output]
         (when error
           [:div.form-error error])]
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
          (if sending? "Sending..." "Send")]]])))

(defn terminal-shell
  []
  (let [selected (get-in @(rf/subscribe [:darelwasl.app/terminal]) [:selected])]
    [shell/app-shell
     [:main.terminal-layout
      (if selected
        [terminal-chat]
        [terminal-list])]
     [:span "Sessions run independently; output streams even when you leave."]]))
