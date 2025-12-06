(ns darelwasl.app
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.dom :as rdom]))

(def default-login-state
  {:username ""
   :password ""
   :status :idle
   :error nil})

(def default-notes
  [{:id :auth
    :title "Login is wired"
    :body "Form posts to /api/login and stores the session cookie; errors show inline."}
   {:id :state
    :title "State handling"
    :body "Idle/loading/error/success states drive button and helper copy."}
   {:id :tasks
    :title "Workspace handoff"
    :body "After login, shell is ready for upcoming task list/detail flows."}])

(def default-workspace
  [{:id :theme
    :title "Theme tokens"
    :body "CSS vars sourced from registries/theme.edn; forms and shells reuse spacing/typography."
    :label "Theme ready"}
   {:id :sessions
    :title "Session cookies"
    :body "Uses http-only SameSite=Lax session; logout clears local session state for re-login."
    :label "Auth"}
   {:id :dev
    :title "Dev commands"
    :body "Run npm dev/build to compile CLJS; backend uses clojure -M:dev for the API."}])

(def default-db
  {:route :login
   :session nil
   :active-view :overview
   :login default-login-state
   :notes default-notes
   :workspace default-workspace})

(rf/reg-fx
 ::login-request
 (fn [{:keys [username password on-success on-error]}]
   (let [body (js/JSON.stringify (clj->js {"user/username" username
                                           "user/password" password}))]
     (-> (js/fetch "/api/login"
                   #js {:method "POST"
                        :headers #js {"Content-Type" "application/json"
                                      "Accept" "application/json"}
                        :credentials "same-origin"
                        :body body})
         (.then
          (fn [resp]
            (-> (.json resp)
                (.then
                 (fn [data]
                   (let [payload (js->clj data :keywordize-keys true)
                         status (.-status resp)]
                     (if (<= 200 status 299)
                       (rf/dispatch (conj on-success payload))
                       (rf/dispatch (conj on-error {:status status
                                                    :body payload}))))))
                (.catch
                 (fn [_]
                   (rf/dispatch (conj on-error {:status (.-status resp)
                                                :body {:error "Invalid response from server"}})))))))
         (.catch
          (fn [_]
            (rf/dispatch (conj on-error {:status nil
                                         :body {:error "Network error. Please try again."}}))))))))

(rf/reg-event-db
 ::initialize
 (fn [_ _]
   default-db))

(rf/reg-event-db
 ::set-view
 (fn [db [_ view]]
   (assoc db :active-view view)))

(rf/reg-event-db
 ::update-login-field
 (fn [db [_ field value]]
   (-> db
       (assoc-in [:login field] value)
       (assoc-in [:login :error] nil))))

(rf/reg-event-fx
 ::submit-login
 (fn [{:keys [db]} _]
   (let [username (str/trim (get-in db [:login :username] ""))
         password (get-in db [:login :password] "")]
     (cond
       (str/blank? username)
       {:db (-> db
                (assoc-in [:login :status] :error)
                (assoc-in [:login :error] "Username is required"))}

       (str/blank? password)
       {:db (-> db
                (assoc-in [:login :status] :error)
                (assoc-in [:login :error] "Password is required"))}

       :else
       {:db (-> db
                (assoc-in [:login :username] username)
                (assoc-in [:login :status] :loading)
                (assoc-in [:login :error] nil))
        ::login-request {:username username
                         :password password
                         :on-success [::login-success]
                         :on-error [::login-failure]}}))))

(rf/reg-event-db
 ::login-success
 (fn [db [_ payload]]
   (let [token (:session/token payload)
         user-id (:user/id payload)
         username (:user/username payload)]
     (-> db
         (assoc :session {:token token
                          :user {:id user-id
                                 :username username}})
         (assoc :route :tasks)
         (assoc-in [:login :status] :success)
         (assoc-in [:login :password] "")
         (assoc-in [:login :error] nil)))))

(rf/reg-event-db
 ::login-failure
 (fn [db [_ {:keys [status body]}]]
   (let [message (or (:error body)
                     (when (= status 401) "Invalid username or password")
                     "Unable to log in. Please try again.")]
     (-> db
         (assoc-in [:login :status] :error)
         (assoc-in [:login :error] message)))))

(rf/reg-event-db
 ::logout
 (fn [db _]
   (-> db
       (assoc :session nil
              :route :login)
       (assoc :login (assoc default-login-state :username (or (get-in db [:session :user :username])
                                                              (get-in db [:login :username])
                                                              ""))))))

(rf/reg-sub ::active-view (fn [db _] (:active-view db)))
(rf/reg-sub ::notes (fn [db _] (:notes db)))
(rf/reg-sub ::workspace (fn [db _] (:workspace db)))
(rf/reg-sub ::login-state (fn [db _] (:login db)))
(rf/reg-sub ::session (fn [db _] (:session db)))

(def view-options
  [{:id :overview :label "Overview"}
   {:id :tasks :label "Tasks focus"}])

(defn badge-pill [text]
  [:div.badge
   [:span.badge-dot]
   text])

(defn login-hero []
  [:div.login-hero
   [badge-pill "Darel Wasl Tasks"]
   [:h1 "Sign in to continue"]
   [:p "Authenticate with your workspace account to reach the task board. Sessions use http-only cookies for local dev."]
   [:div.login-steps
    [:div.step
     [:span.step-label "01"]
     [:div
      [:strong "Use fixtures"]
      [:p "Usernames: huda or damjan. Password: Damjan1!"]]]
    [:div.step
     [:span.step-label "02"]
     [:div
      [:strong "Stay responsive"]
      [:p "Loading and error states keep the experience clear on desktop and mobile."]]]
    [:div.step
     [:span.step-label "03"]
     [:div
      [:strong "Move to tasks"]
      [:p "On success, you land in the task workspace shell."]]]]])

(defn login-form []
  (let [{:keys [username password status error]} @(rf/subscribe [::login-state])
        loading? (= status :loading)]
    [:div.login-card
     [:div.login-card__header
      [badge-pill "Login"]
      [:h2 "Welcome back"]
      [:p "Enter your credentials to establish a session."]]
     [:form.login-form
      {:on-submit (fn [e]
                    (.preventDefault e)
                    (rf/dispatch [::submit-login]))}
      [:label.form-label {:for "username"} "Username"]
      [:input.form-input {:id "username"
                          :name "username"
                          :type "text"
                          :placeholder "huda or damjan"
                          :value username
                          :autoComplete "username"
                          :on-change #(rf/dispatch [::update-login-field :username (.. % -target -value)])
                          :disabled loading?}]
      [:label.form-label {:for "password"} "Password"]
      [:input.form-input {:id "password"
                          :name "password"
                          :type "password"
                          :placeholder "Damjan1!"
                          :value password
                          :autoComplete "current-password"
                          :on-change #(rf/dispatch [::update-login-field :password (.. % -target -value)])
                          :disabled loading?}]
      (when error
        [:div.form-error error])
      [:div.form-actions
       [:button.button {:type "submit" :disabled loading?}
        (if loading? "Signing in..." "Sign in")]
       [:div.helper-text "Use fixtures from docs/system.md. Sessions persist until the server restarts."]]]]))

(defn login-page []
  [:div.login-page
   [login-hero]
   [login-form]])

(defn notes-panel []
  (let [notes @(rf/subscribe [::notes])]
    [:div.panel
     [:div.section-header
      [:h2 "States"]
      [badge-pill "Login + Tasks"]]
     [:p "Track progress through explicit states. Login drives the transition into the task workspace shell."]
     [:div.button-row
      (let [active @(rf/subscribe [::active-view])]
        (for [{:keys [id label]} view-options]
          ^{:key id}
          [:button.button
           {:type "button"
            :class (when (not= id active) "secondary")
            :on-click #(rf/dispatch [::set-view id])}
           label]))]
     (for [{:keys [id title body]} notes]
       ^{:key id}
       [:div.placeholder-card
        [:strong title]
        [:div body]])]))

(defn workspace-panel []
  (let [items @(rf/subscribe [::workspace])
        active @(rf/subscribe [::active-view])]
    [:div.panel
     [:div.section-header
      [:h2 "Workspace"]
      [badge-pill (if (= active :tasks) "Task view" "Overview ready")]]
     [:p
      (case active
        :tasks "Task list/detail flows wire in next; login already establishes session cookies."
        "Use the theme tokens and registries to keep UI consistent. Start dev server to iterate quickly.")]
     (for [{:keys [id title body label]} items]
       ^{:key id}
       [:div.placeholder-card
        [:div.workspace-row
         [:strong title]
         (when label
           [:span.badge label])]
        [:div body]])
     [:div.button-row
      [:button.button {:type "button"} "npm run dev"]
      [:button.button.secondary {:type "button"} "npm run build"]]]))

(defn top-bar []
  (let [session @(rf/subscribe [::session])
        uname (get-in session [:user :username] "")]
    [:header.top-bar
     [:div
      [:div.brand "Darel Wasl Tasks"]
      [:div.meta (if session
                   (str "Signed in as " uname)
                   "Task workspace")]]

     [:div.top-actions
      (when session
        (let [initial (if (seq uname) (str/upper-case (subs uname 0 1)) "?")]
          [:div.session-chip
           [:div.avatar-circle initial]
           [:div
            [:div.session-label "Session active"]
            [:div.session-name uname]]
           [:button.button.secondary
            {:type "button"
             :on-click #(rf/dispatch [::logout])}
            "Sign out"]]))]]))

(defn task-shell []
  [:div.app-shell
   [top-bar]
   [:main.main-grid
    [notes-panel]
    [workspace-panel]]
   [:footer.app-footer
    [:span "Logged in workspace · Theme via CSS vars with warm neutrals + teal accent."]]])

(defn app-root []
  (let [session @(rf/subscribe [::session])]
    [:div
     (if session
       [task-shell]
       [login-page])]))

(defn mount-root []
  (when-let [root (.getElementById js/document "app")]
    (rdom/render [app-root] root)))

(defn ^:export init []
  (rf/dispatch-sync [::initialize])
  (mount-root))

(defn reload []
  (rf/clear-subscription-cache!)
  (mount-root))
