(ns darelwasl.app
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.dom :as rdom]))

(def default-login-state
  {:username ""
   :password ""
   :status :idle
   :error nil})

(def status-options
  [{:id nil :label "All statuses"}
   {:id :todo :label "To do"}
   {:id :in-progress :label "In progress"}
   {:id :done :label "Done"}])

(def priority-options
  [{:id nil :label "All priorities"}
   {:id :high :label "High"}
   {:id :medium :label "Medium"}
   {:id :low :label "Low"}])

(def tag-options
  [{:id nil :label "All tags"}
   {:id :ops :label "Ops"}
   {:id :home :label "Home"}
   {:id :finance :label "Finance"}
   {:id :urgent :label "Urgent"}])

(def fallback-assignees
  [{:id "00000000-0000-0000-0000-000000000001" :label "huda"}
   {:id "00000000-0000-0000-0000-000000000002" :label "damjan"}])

(def default-task-filters
  {:status nil
   :priority nil
   :tag nil
   :assignee nil
   :archived false
   :sort :updated
   :order :desc})

(def default-task-state
  {:items []
   :status :idle
   :error nil
   :filters default-task-filters
   :selected nil
   :assignees []})

(def default-db
  {:route :login
   :session nil
   :login default-login-state
   :tasks default-task-state})

(defn- distinct-by
  [f coll]
  (loop [seen #{}
         xs coll
         out []]
    (if-let [item (first xs)]
      (let [k (f item)]
        (if (contains? seen k)
          (recur seen (rest xs) out)
          (recur (conj seen k) (rest xs) (conj out item))))
      out)))

(defn- format-date
  [iso-str]
  (when (and iso-str (not (str/blank? iso-str)))
    (let [d (js/Date. iso-str)]
      (.toLocaleDateString d "en-US" #js {:month "short" :day "numeric"}))))

(defn- status-label [s] (get {:todo "To do" :in-progress "In progress" :done "Done"} s "Unknown"))
(defn- priority-label [p] (get {:high "High" :medium "Medium" :low "Low"} p "Unknown"))
(defn- truncate
  [s n]
  (if (and s (> (count s) n))
    (str (subs s 0 n) "…")
    s))

(defn- build-query
  [{:keys [status priority tag assignee archived sort order]}]
  (let [params (cond-> []
                 status (conj ["status" (name status)])
                 priority (conj ["priority" (name priority)])
                 tag (conj ["tag" (name tag)])
                 assignee (conj ["assignee" (str assignee)])
                 (true? archived) (conj ["archived" "true"])
                 (= archived :all) (conj ["archived" "all"])
                 sort (conj ["sort" (name sort)])
                 order (conj ["order" (name order)]))]
    (when (seq params)
      (let [sp (js/URLSearchParams.)]
        (doseq [[k v] params]
          (.append sp k v))
        (.toString sp)))))

(defn- assignees-from-tasks
  [tasks]
  (->> tasks
       (keep :task/assignee)
       (distinct-by :user/id)
       (map (fn [assignee]
              {:id (:user/id assignee)
               :label (or (:user/username assignee)
                          (:user/name assignee)
                          "Assignee")}))
        vec))

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

(rf/reg-fx
 ::tasks-request
 (fn [{:keys [filters on-success on-error]}]
   (let [qs (build-query filters)
         url (str "/api/tasks" (when qs (str "?" qs)))]
     (-> (js/fetch url
                   #js {:method "GET"
                        :headers #js {"Accept" "application/json"}
                        :credentials "same-origin"})
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

(rf/reg-event-fx
 ::login-success
 (fn [{:keys [db]} [_ payload]]
   (let [token (:session/token payload)
         user-id (:user/id payload)
         username (:user/username payload)
         db' (-> db
                 (assoc :session {:token token
                                  :user {:id user-id
                                         :username username}})
                 (assoc :route :tasks)
                 (assoc :tasks default-task-state)
                 (assoc-in [:login :status] :success)
                 (assoc-in [:login :password] "")
                 (assoc-in [:login :error] nil))]
     {:db db'
      :dispatch [::fetch-tasks]})))

(rf/reg-event-db
 ::login-failure
 (fn [db [_ {:keys [status body]}]]
   (let [message (or (:error body)
                     (when (= status 401) "Invalid username or password")
                     "Unable to log in. Please try again.")]
      (-> db
          (assoc-in [:login :status] :error)
          (assoc-in [:login :error] message)))))

(rf/reg-event-fx
 ::logout
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc :session nil
                   :route :login
                   :tasks default-task-state)
            (assoc :login (assoc default-login-state :username (or (get-in db [:session :user :username])
                                                                   (get-in db [:login :username])
                                                                   ""))))}))

(rf/reg-event-fx
 ::fetch-tasks
 (fn [{:keys [db]} _]
   (let [filters (get-in db [:tasks :filters])]
     {:db (-> db
              (assoc-in [:tasks :status] :loading)
              (assoc-in [:tasks :error] nil))
      ::tasks-request {:filters filters
                       :on-success [::fetch-success]
                       :on-error [::fetch-failure]}})))

(rf/reg-event-db
 ::fetch-success
 (fn [db [_ payload]]
   (let [tasks (vec (:tasks payload))
         selected (when (seq tasks)
                    (let [current (get-in db [:tasks :selected])]
                      (if (some #(= (:task/id %) current) tasks)
                        current
                        (:task/id (first tasks)))))
         assignees (let [opts (assignees-from-tasks tasks)]
                     (if (seq opts) opts fallback-assignees))]
     (-> db
         (assoc-in [:tasks :items] tasks)
         (assoc-in [:tasks :selected] selected)
         (assoc-in [:tasks :assignees] assignees)
         (assoc-in [:tasks :status] (if (seq tasks) :ready :empty))
         (assoc-in [:tasks :error] nil)))))

(rf/reg-event-db
 ::fetch-failure
 (fn [db [_ {:keys [status body]}]]
   (let [message (or (:error body)
                     (when (= status 401) "Session expired. Please sign in again.")
                     "Unable to load tasks.")]
     (-> db
         (assoc-in [:tasks :status] :error)
         (assoc-in [:tasks :error] message)
         (cond-> (= status 401)
           (assoc :session nil
                  :route :login))))))

(defn- ->keyword-or-nil [v]
  (let [trimmed (str/trim (or v ""))]
    (when-not (str/blank? trimmed)
      (keyword trimmed))))

(rf/reg-event-fx
 ::update-filter
 (fn [{:keys [db]} [_ field value]]
   (let [updated (assoc-in db [:tasks :filters field] value)]
     {:db updated
      :dispatch [::fetch-tasks]})))

(rf/reg-event-fx
 ::set-sort
 (fn [{:keys [db]} [_ sort-key]]
   (let [default-order (if (= sort-key :due) :asc :desc)
         db' (-> db
                 (assoc-in [:tasks :filters :sort] sort-key)
                 (assoc-in [:tasks :filters :order] default-order))]
     {:db db'
      :dispatch [::fetch-tasks]})))

(rf/reg-event-fx
 ::toggle-order
 (fn [{:keys [db]} _]
   (let [current (get-in db [:tasks :filters :order] :desc)
         next (if (= current :asc) :desc :asc)]
     {:db (assoc-in db [:tasks :filters :order] next)
      :dispatch [::fetch-tasks]})))

(rf/reg-event-fx
 ::toggle-archived
 (fn [{:keys [db]} _]
   (let [current (get-in db [:tasks :filters :archived] false)
         next (case current
                false :all
                :all true
                false)]
     {:db (assoc-in db [:tasks :filters :archived] next)
      :dispatch [::fetch-tasks]})))

(rf/reg-event-fx
 ::clear-filters
 (fn [{:keys [db]} _]
   {:db (assoc-in db [:tasks :filters] default-task-filters)
    :dispatch [::fetch-tasks]}))

(rf/reg-event-db
 ::select-task
 (fn [db [_ task-id]]
   (assoc-in db [:tasks :selected] task-id)))

(rf/reg-sub ::login-state (fn [db _] (:login db)))
(rf/reg-sub ::session (fn [db _] (:session db)))
(rf/reg-sub ::tasks (fn [db _] (:tasks db)))
(rf/reg-sub
 ::selected-task
 (fn [db _]
   (let [selected (get-in db [:tasks :selected])
         items (get-in db [:tasks :items])]
     (some #(when (= (:task/id %) selected) %) items))))

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

(defn- chip
  [label & {:keys [class]}]
  [:span.chip {:class class} label])

(defn status-chip
  [{:task/keys [status archived?]}]
  (let [label (cond
                archived? "Archived"
                :else (status-label status))
        cls (cond
              archived? "muted"
              (= status :todo) "neutral"
              (= status :in-progress) "warning"
              (= status :done) "success"
              :else "neutral")]
    [chip label :class (str "status " cls)]))

(defn priority-chip [priority]
  (when priority
    [chip (priority-label priority) :class (str "priority " (name priority))]))

(defn tag-chip [tag]
  [chip (str "#" (name tag)) :class "tag"])

(defn assignee-pill [assignee]
  (let [uname (or (:user/username assignee) "")
        initial (if (seq uname) (str/upper-case (subs uname 0 1)) "?")]
    [:div.assignee
     [:div.avatar-circle.sm initial]
     [:div.assignee-text
      [:div.assignee-label "Assignee"]
      [:div.assignee-name (or (:user/name assignee) uname "Unassigned")]]]))

(defn filter-select
  [{:keys [label options value on-change]}]
  (let [current (cond
                  (keyword? value) (name value)
                  (string? value) value
                  (nil? value) ""
                  :else (str value))]
    [:label.filter-group
     [:span.filter-label label]
     [:select.filter-select
      {:value current
       :on-change #(on-change (.. % -target -value))}
      (for [{:keys [id label]} options
            :let [option-val (cond
                               (keyword? id) (name id)
                               (string? id) id
                               (nil? id) ""
                               :else (str id))]]
        ^{:key (or (str id) "all")}
        [:option {:value option-val} label])]]))

(defn filters-panel []
  (let [{:keys [filters status assignees]} @(rf/subscribe [::tasks])
        task-status status
        {:keys [status priority tag assignee archived sort order]} filters
        available-assignees (if (seq assignees) assignees fallback-assignees)]
    [:div.panel.tasks-controls
     [:div.section-header
      [:h2 "Filters"]
      [:div.actions
       [:button.button.secondary {:type "button"
                                  :on-click #(rf/dispatch [::clear-filters])
                                  :disabled (= filters default-task-filters)}
        "Reset"]
       [:button.button.secondary {:type "button"
                                  :on-click #(rf/dispatch [::fetch-tasks])
                                  :disabled (= task-status :loading)}
        "Refresh"]]]
     [:div.tasks-controls__row
      [filter-select {:label "Status"
                      :options status-options
                      :value (when status (name status))
                      :on-change #(rf/dispatch [::update-filter :status (->keyword-or-nil %)])}]
      [filter-select {:label "Priority"
                      :options priority-options
                      :value (when priority (name priority))
                      :on-change #(rf/dispatch [::update-filter :priority (->keyword-or-nil %)])}]
      [filter-select {:label "Tag"
                      :options tag-options
                      :value (when tag (name tag))
                      :on-change #(rf/dispatch [::update-filter :tag (->keyword-or-nil %)])}]
      [filter-select {:label "Assignee"
                      :options (cons {:id nil :label "All assignees"} available-assignees)
                      :value assignee
                      :on-change #(rf/dispatch [::update-filter :assignee (when-not (str/blank? %) %)])}]]
     [:div.tasks-controls__row
      [:div.filter-group
       [:span.filter-label "Archived"]
       [:div.toggle-row
        [:button.button.secondary {:type "button"
                                   :class (when (= archived false) "active")
                                   :on-click #(rf/dispatch [::update-filter :archived false])}
         "Active"]
        [:button.button.secondary {:type "button"
                                   :class (when (= archived :all) "active")
                                   :on-click #(rf/dispatch [::update-filter :archived :all])}
         "All"]
        [:button.button.secondary {:type "button"
                                   :class (when (= archived true) "active")
                                   :on-click #(rf/dispatch [::update-filter :archived true])}
         "Archived only"]]]
      [:div.filter-group
       [:span.filter-label "Sort"]
       [:div.sort-row
        [:select.filter-select
         {:value (name sort)
          :on-change #(rf/dispatch [::set-sort (->keyword-or-nil (.. % -target -value))])}
         (for [opt [{:id :updated :label "Updated"}
                    {:id :due :label "Due date"}
                    {:id :priority :label "Priority"}]]
           ^{:key (name (:id opt))}
           [:option {:value (name (:id opt))} (:label opt)])]
        [:button.button.secondary
         {:type "button"
          :on-click #(rf/dispatch [::toggle-order])}
         (if (= order :asc) "Asc" "Desc")]]]]]))

(defn loading-state []
  [:div.state
   [:div.spinner]
   [:div "Loading tasks..."]])

(defn error-state [message]
  [:div.state.error
   [:div.form-error message]
   [:div.button-row
    [:button.button.secondary {:type "button"
                               :on-click #(rf/dispatch [::fetch-tasks])}
     "Try again"]]])

(defn empty-state []
  [:div.state.empty
   [:strong "No tasks match these filters"]
   [:p "Adjust filters to see tasks or refresh to reload."]])

(defn task-card
  [{:task/keys [id title description status priority tags due-date updated-at assignee archived?] :as task} selected?]
  [:div.task-card {:class (when selected? "selected")
                   :on-click #(rf/dispatch [::select-task id])}
   [:div.task-card__header
    [:div
     [:div.title-row
      [:h3 title]
      [status-chip {:task/status status :task/archived? archived?}]]
     [:div.meta-row
      (when due-date
        [:span.meta (str "Due " (format-date due-date))])
      (when updated-at
        [:span.meta (str "Updated " (format-date updated-at))])]]
    [priority-chip priority]]
   [:p.description (or (truncate description 140) "No description")]
   [:div.task-card__footer
    [assignee-pill assignee]
    [:div.tags
     (for [t tags]
       ^{:key (str id "-" (name t))}
       [tag-chip t])]]])

(defn task-list []
  (let [{:keys [items status error selected]} @(rf/subscribe [::tasks])]
    [:div.panel.task-list-panel
     [:div.section-header
      [:h2 "Tasks"]
      [:span.meta (str (count items) " items")]]
     (case status
       :loading [loading-state]
       :error [error-state error]
       (if (seq items)
         [:div.task-list
          (for [t items]
            ^{:key (:task/id t)}
            [task-card t (= selected (:task/id t))])]
         [empty-state]))]))

(defn task-preview []
  (let [task @(rf/subscribe [::selected-task])]
    [:div.panel.task-preview
     [:div.section-header
      [:h2 "Detail"]
      [:span.meta "Read-only preview"]]
     (if task
       (let [{:task/keys [title description status priority tags assignee due-date archived?]} task]
         [:div.preview-body
          [:div.title-row
           [:h3 title]
           [status-chip {:task/status status :task/archived? archived?}]]
          [:div.meta-grid
           [:div
            [:div.meta-label "Priority"]
            [priority-chip priority]]
           [:div
            [:div.meta-label "Due date"]
            [:div.meta (or (format-date due-date) "Not set")]]
           [:div
            [:div.meta-label "Assignee"]
            [assignee-pill assignee]]]
          [:div.meta-label "Tags"]
          [:div.tags (if (seq tags)
                       (for [t tags]
                         ^{:key (str "preview-" (name t))}
                         [tag-chip t])
                       [:span.meta "No tags"])]
          [:div.meta-label "Description"]
          [:p description]])
       [:div.placeholder-card
        [:strong "Select a task"]
        [:p "Choose a task from the list to inspect details. Full editing arrives with the detail task."]])]))

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
   [:main.tasks-layout
    [:div.tasks-column
     [filters-panel]
     [task-list]]
    [:div.tasks-column.narrow
     [task-preview]]]
   [:footer.app-footer
    [:span "Logged in workspace · Filter and sort tasks from the API."]]])

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
