(ns darelwasl.features.login
  (:require [re-frame.core :as rf]))

(defn login-form []
  (let [{:keys [username password status error]} @(rf/subscribe [:darelwasl.app/login-state])
        loading? (= status :loading)]
    [:div.login-card
     [:div.login-card__header
      [:h2 "Sign in"]
      [:p "Enter your credentials to continue."]]
     [:form.login-form
      {:on-submit (fn [e]
                    (.preventDefault e)
                    (rf/dispatch [:darelwasl.app/submit-login]))}
      [:label.form-label {:for "username"} "Username"]
      [:input.form-input {:id "username"
                          :name "username"
                          :type "text"
                          :placeholder "Enter username"
                          :value username
                          :autoComplete "username"
                          :on-change #(rf/dispatch [:darelwasl.app/update-login-field :username (.. % -target -value)])
                          :disabled loading?}]
      [:label.form-label {:for "password"} "Password"]
      [:input.form-input {:id "password"
                          :name "password"
                          :type "password"
                          :placeholder "Enter password"
                          :value password
                          :autoComplete "current-password"
                          :on-change #(rf/dispatch [:darelwasl.app/update-login-field :password (.. % -target -value)])
                          :disabled loading?}]
      (when error
        [:div.form-error error])
      [:div.form-actions
       [:button.button {:type "submit" :disabled loading?}
        (if loading? "Signing in..." "Sign in")]
       [:div.helper-text "Sessions stay local to your browser until the server restarts."]]]]))

(defn login-page []
  [:div.login-page
   [:div.login-panel
    [:div.login-panel__brand
     [:h1 "Welcome back!"]
     [:p ""]]
    [login-form]]])
