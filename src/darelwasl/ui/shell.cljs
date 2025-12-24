(ns darelwasl.ui.shell
  (:require [clojure.string :as str]
            [darelwasl.state :as state]
            [re-frame.core :as rf]))

(defn app-switcher-menu
  [apps route extra-class]
  [:div.app-switcher-menu {:id (when-not (= extra-class "mobile") "app-switcher-menu")
                           :class extra-class
                           :role "menu"}
   (for [{:keys [id label desc]} apps]
     ^{:key (name id)}
     [:button.app-switcher-item
      {:type "button"
       :class (when (= route id) "active")
       :role "menuitem"
       :on-click #(do
                    (rf/dispatch [:darelwasl.app/navigate id])
                    (rf/dispatch [:darelwasl.app/close-switcher]))}
      [:div.item-label label]
      [:div.item-desc desc]])])

(defn app-switcher []
  (let [{:keys [menu-open?]} @(rf/subscribe [:darelwasl.app/nav])
        route @(rf/subscribe [:darelwasl.app/route])
        session @(rf/subscribe [:darelwasl.app/session])
        apps (state/app-options session)]
    [:div.app-switcher
     [:div.app-switcher-edge {:aria-hidden true
                              :on-mouse-enter #(rf/dispatch [:darelwasl.app/open-switcher])}]
     [:div.app-switcher-trigger-area.desktop
      {:on-mouse-enter #(rf/dispatch [:darelwasl.app/open-switcher])}
      [:button.app-switcher-trigger
       {:type "button"
        :aria-expanded (boolean menu-open?)
        :aria-controls "app-switcher-menu"
        :on-click #(rf/dispatch [:darelwasl.app/open-switcher])
        :on-focus #(rf/dispatch [:darelwasl.app/open-switcher])
        :on-key-down #(when (= "Escape" (.-key %))
                        (rf/dispatch [:darelwasl.app/close-switcher]))}
       [:span "Apps"]
       [:span.caret "▾"]]
      (when menu-open?
        [app-switcher-menu apps route "desktop"])]
     [:button.app-switcher-mobile-trigger
      {:type "button"
       :aria-label "Open app switcher"
       :aria-expanded (boolean menu-open?)
       :on-click #(rf/dispatch [:darelwasl.app/open-switcher])}
      "Apps"]
     (when menu-open?
       [app-switcher-menu apps route "mobile"])]))

(defn top-bar []
  (let [session @(rf/subscribe [:darelwasl.app/session])
        route @(rf/subscribe [:darelwasl.app/route])
        apps (state/app-options session)
        app-label (some #(when (= (:id %) route) (:label %)) apps)
        uname (get-in session [:user :username] "")]
    [:header.top-bar
     [:div
      [:div.brand (str "Darel Wasl · " (or app-label "Workspace"))]
      [:div.meta (if session
                   (str "Signed in as " uname)
                   "Task workspace")]]
     [:div.top-actions
      (when session
        [app-switcher])
      (when session
        (let [initial (if (seq uname) (str/upper-case (subs uname 0 1)) "?")]
          [:div.session-chip
           [:div.avatar-circle initial]
           [:div
            [:div.session-label "Session active"]
            [:div.session-name uname]]
           [:button.button.secondary
            {:type "button"
             :on-click #(rf/dispatch [:darelwasl.app/logout])}
            "Sign out"]]))]]))

(defn app-shell [content footer]
  [:div.app-shell
   [top-bar]
   content
   [:footer.app-footer footer]])
