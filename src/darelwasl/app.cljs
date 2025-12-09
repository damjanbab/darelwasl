(ns darelwasl.app
  (:require [clojure.string :as str]
            [darelwasl.features.home :as home]
            [darelwasl.features.land :as land]
            [darelwasl.features.login :as login]
            [darelwasl.features.tasks :as tasks-ui]
            [darelwasl.state :as state]
            [darelwasl.ui.shell :as shell]
            [darelwasl.util :as util]
            [re-frame.core :as rf]
            [reagent.dom.client :as rdom]))

(def theme-storage-key state/theme-storage-key)
(def nav-storage-key state/nav-storage-key)
(def theme-options state/theme-options)
(def status-options state/status-options)
(def priority-options state/priority-options)
(def task-status-options state/task-status-options)
(def task-priority-options state/task-priority-options)
(def default-login-state state/default-login-state)
(def fallback-assignees state/fallback-assignees)
(def default-task-filters state/default-task-filters)
(def default-task-form state/default-task-form)
(def default-task-detail state/default-task-detail)
(def default-task-state state/default-task-state)
(def default-home-state state/default-home-state)
(def default-tags-state state/default-tags-state)
(def default-theme-state state/default-theme-state)
(def default-nav-state state/default-nav-state)
(def land-enabled? state/land-enabled?)
(def default-land-filters state/default-land-filters)
(def default-land-state state/default-land-state)
(def default-db state/default-db)
(def task-entity-config state/task-entity-config)

(def distinct-by util/distinct-by)

(defn- theme-slug [theme-id]
  (-> theme-id
      name
      (str/replace "/" "-")
      (str/replace "_" "-")))

(defn- resolve-theme-id
  [value]
  (let [candidate (cond
                    (keyword? value) value
                    (string? value) (-> value
                                        (str/replace #"^:" "")
                                        keyword)
                    :else nil)
        allowed (set (map :id theme-options))]
    (if (contains? allowed candidate) candidate :theme/default)))

(defn- apply-theme!
  [theme-id]
  (when-let [root (.-documentElement js/document)]
    (.setAttribute root "data-theme" (theme-slug theme-id))
    (set! (.-colorScheme (.-style root)) (if (= theme-id :theme/dark) "dark" "light"))))

(def clean-tag-name util/clean-tag-name)
(def tag-slug util/tag-slug)
(def find-tag-by-name util/find-tag-by-name)
(def remove-tag-from-tasks util/remove-tag-from-tasks)
(def rename-tag-in-tasks util/rename-tag-in-tasks)
(def normalize-tag-list util/normalize-tag-list)
(def format-date util/format-date)
(def status-label util/status-label)
(def priority-label util/priority-label)
(def truncate util/truncate)
(def pct util/pct)
(def format-area util/format-area)
(def parcel-title util/parcel-title)
(def person-title util/person-title)

(defn- build-query
  [{:keys [status priority tag assignee archived sort order]}]
  (let [params (cond-> []
                 status (conj ["status" (name status)])
                 priority (conj ["priority" (name priority)])
                 tag (conj ["tag" (str tag)])
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

(defn- iso->input-date
  [iso-str]
  (when (and iso-str (not (str/blank? iso-str)))
    (subs (.toISOString (js/Date. iso-str)) 0 10)))

(defn- input-date->iso
  [date-str]
  (when (and date-str (not (str/blank? date-str)))
    (.toISOString (js/Date. (str date-str "T00:00:00Z")))))

(defn- default-assignee-id
  [assignees session]
  (or (get-in session [:user :id])
      (:id (first assignees))
      (:id (first fallback-assignees))))

(defn- status-count-cards
  [{:keys [todo in-progress done]}]
  [:div.summary-cards
   [:div.card
    [:div.card-label "To do"]
    [:div.card-value (or todo 0)]]
   [:div.card
    [:div.card-label "In progress"]
    [:div.card-value (or in-progress 0)]]
   [:div.card
    [:div.card-label "Done"]
    [:div.card-value (or done 0)]]])

(declare tag-chip)
(declare entity-list task-card)

(defn- tag-highlights [tags]
  [:div.tag-highlights
   [:h4 "Tags"]
   (if (seq tags)
     [:div.tags
      (for [t tags]
        ^{:key (str "tag-" (:tag/id t))}
        [tag-chip (:tag/name t)])]
     [:span.meta "No tags yet"] )])

(defn- kw
  [v]
  (cond
    (keyword? v) v
    (string? v) (keyword v)
    :else v))

(defn- normalize-task
  "Coerce API payload fields into keywords/sets/booleans for UI consumption."
  [task]
  (-> task
      (update :task/status kw)
      (update :task/priority kw)
      (update :task/tags normalize-tag-list)
      (update :task/archived? boolean)
      (update :task/extended? boolean)))

(defn- task->form
  [task]
  (merge default-task-form
         {:id (:task/id task)
          :title (or (:task/title task) "")
          :description (or (:task/description task) "")
          :status (:task/status task)
          :priority (:task/priority task)
          :assignee (get-in task [:task/assignee :user/id])
          :due-date (iso->input-date (:task/due-date task))
          :tags (set (map :tag/id (or (:task/tags task) [])))
          :archived? (boolean (:task/archived? task))
          :extended? (boolean (:task/extended? task))}))

(defn- detail-from-task
  [task]
  (-> default-task-detail
      (assoc :mode :edit
             :status :idle
             :error nil
             :tag-entry ""
             :form (task->form task))))

(defn- blank-detail
  [assignees session]
  (-> default-task-detail
      (assoc :mode :create
             :status :idle
             :error nil
             :tag-entry ""
             :form (assoc default-task-form :assignee (default-assignee-id assignees session))))) 

(rf/reg-fx
 ::apply-theme
 (fn [theme-id]
   (when theme-id
     (try
       (.setItem js/localStorage theme-storage-key (name theme-id))
       (catch :default _))
     (apply-theme! theme-id))))

(rf/reg-fx
 ::persist-last-route
 (fn [route]
   (when route
     (try
       (.setItem js/localStorage nav-storage-key (name route))
       (catch :default _)))))

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
 ::session-request
 (fn [{:keys [on-success on-error]}]
   (-> (js/fetch "/api/session"
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
                                       :body {:error "Network error. Please try again."}})))))))

(rf/reg-fx
 ::tag-request
 (fn [{:keys [url method body on-success on-error]}]
   (let [opts (clj->js (cond-> {:method (or method "GET")
                                :headers {"Accept" "application/json"}
                                :credentials "same-origin"}
                         body (assoc :headers {"Content-Type" "application/json"
                                               "Accept" "application/json"}
                                     :body (js/JSON.stringify (clj->js body)))))]
     (-> (js/fetch url opts)
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

(rf/reg-fx
 ::home-request
 (fn [{:keys [url on-success on-error]}]
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
                                       :body {:error "Network error. Please try again."}})))))))

(rf/reg-fx
 ::land-request
 (fn [{:keys [url on-success on-error]}]
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
                                       :body {:error "Network error. Please try again."}})))))))

(rf/reg-fx
 ::task-action
 (fn [{:keys [url method body on-success on-error]}]
   (let [opts (clj->js (cond-> {:method (or method "POST")
                                :headers {"Content-Type" "application/json"
                                          "Accept" "application/json"}
                                :credentials "same-origin"}
                         body (assoc :body (js/JSON.stringify (clj->js body)))))]
     (-> (js/fetch url opts)
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

(rf/reg-event-fx
 ::initialize
 (fn [_ _]
   (let [stored (try
                  (.getItem js/localStorage theme-storage-key)
                  (catch :default _ nil))
         stored-route (try
                        (.getItem js/localStorage nav-storage-key)
                        (catch :default _ nil))
        allowed-routes (cond-> #{:home :tasks}
                          land-enabled? (conj :land))
         last-route (let [cand (some-> stored-route (str/replace #"^:" "") keyword)]
                      (if (contains? allowed-routes cand) cand (:last-route default-nav-state)))
         theme-id (resolve-theme-id stored)]
     {:db (-> default-db
              (assoc :nav (assoc default-nav-state :last-route last-route))
              (assoc-in [:theme :id] theme-id))
      ::apply-theme theme-id
      :dispatch [::restore-session]})))

(rf/reg-event-db
 ::set-view
 (fn [db [_ view]]
   (assoc db :active-view view)))

(rf/reg-event-db
 ::open-switcher
 (fn [db _]
   (assoc-in db [:nav :menu-open?] true)))

(rf/reg-event-db
 ::close-switcher
 (fn [db _]
   (assoc-in db [:nav :menu-open?] false)))

(rf/reg-event-db
 ::toggle-switcher
 (fn [db _]
   (update-in db [:nav :menu-open?] not)))

(rf/reg-event-fx
 ::navigate
 (fn [{:keys [db]} [_ route]]
   (let [allowed (cond-> #{:home :tasks}
                   land-enabled? (conj :land))
         safe-route (if (contains? allowed route) route :home)
         dispatches (cond-> []
                       (= safe-route :home) (conj [::fetch-home])
                       (= safe-route :tasks) (conj [::fetch-tasks])
                       (= safe-route :land) (conj [::fetch-land]))]
     {:db (-> db
              (assoc :route safe-route)
              (assoc-in [:nav :menu-open?] false)
              (assoc-in [:nav :last-route] safe-route))
      :dispatch-n dispatches
      ::persist-last-route safe-route})))

(rf/reg-event-fx
 ::set-theme
 (fn [{:keys [db]} [_ theme-id]]
   (let [tid (resolve-theme-id theme-id)]
     {:db (assoc-in db [:theme :id] tid)
      ::apply-theme tid})))

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
         preferred-route (let [cand (get-in db [:nav :last-route])]
                           (if (#{:home :tasks} cand) cand :home))
         db' (-> db
                 (assoc :session {:token token
                                  :user {:id user-id
                                         :username username}})
                 (assoc :route preferred-route)
                 (assoc-in [:nav :last-route] preferred-route)
                 (assoc :tasks default-task-state)
                 (assoc :home default-home-state)
                 (assoc-in [:login :status] :success)
                 (assoc-in [:login :password] "")
                 (assoc-in [:login :error] nil))]
    {:db db'
     ::persist-last-route preferred-route
     :dispatch-n [[::fetch-tags]
                  [::fetch-home]
                  [::fetch-tasks]]})))

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
 ::restore-session
 (fn [{:keys [db]} _]
   {:db db
    ::session-request {:on-success [::session-restored]
                       :on-error [::session-restore-failed]}}))

(rf/reg-event-fx
 ::session-restored
 (fn [_ [_ payload]]
   {:dispatch [::login-success payload]}))

(rf/reg-event-db
 ::session-restore-failed
 (fn [db _]
   (-> db
       (assoc :session nil)
       (assoc :route :login)
       (assoc :login (assoc default-login-state :username (get-in db [:login :username] ""))))))

(rf/reg-event-fx
 ::logout
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc :session nil
                   :route :login
                   :nav default-nav-state
                   :tasks default-task-state
                   :tags default-tags-state
                   :home default-home-state)
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
   (let [tasks (mapv normalize-task (:tasks payload))
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
         (assoc-in [:tasks :detail]
                   (let [current-detail (get-in db [:tasks :detail])
                         selected-task (some #(when (= (:task/id %) selected) %) tasks)
                         keep-create? (= :create (:mode current-detail))]
                    (cond
                      keep-create? (-> current-detail
                                        (assoc :error nil
                                               :tag-entry "")
                                        (update :form (fn [form]
                                                        (let [assignee (or (:assignee form)
                                                                           (default-assignee-id assignees (:session db)))]
                                                          (assoc form :assignee assignee)))))
                       selected-task (detail-from-task selected-task)
                       :else (blank-detail assignees (:session db)))))
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

(rf/reg-event-fx
 ::fetch-tags
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc-in [:tags :status] :loading)
            (assoc-in [:tags :error] nil))
    ::tag-request {:url "/api/tags"
                   :method "GET"
                   :on-success [::fetch-tags-success]
                   :on-error [::fetch-tags-failure]}}))

(rf/reg-event-fx
 ::fetch-home
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc-in [:home :status] :loading)
            (assoc-in [:home :error] nil))
    :fx [[::home-request {:url "/api/tasks/counts"
                          :on-success [::fetch-home-counts-success]
                          :on-error [::fetch-home-failure]}]
         [::home-request {:url "/api/tasks/recent"
                          :on-success [::fetch-home-recent-success]
                          :on-error [::fetch-home-failure]}]]}))

(rf/reg-event-db
 ::fetch-home-counts-success
 (fn [db [_ payload]]
   (-> db
       (assoc-in [:home :counts] (:counts payload))
       (update-in [:home :status] #(if (= % :loading) :loading %))
       (assoc-in [:home :error] nil))))

(rf/reg-event-db
 ::fetch-home-recent-success
 (fn [db [_ payload]]
   (-> db
       (assoc-in [:home :recent] (:tasks payload))
       (assoc-in [:home :status] :ready)
       (assoc-in [:home :error] nil))))

(rf/reg-event-db
 ::fetch-home-failure
 (fn [db [_ {:keys [status body]}]]
   (let [message (or (:error body)
                     (when (= status 401) "Session expired. Please sign in again.")
                     "Unable to load home data.")]
     (-> db
         (assoc-in [:home :status] :error)
         (assoc-in [:home :error] message)
         (cond-> (= status 401)
           (assoc :session nil
                  :route :login))))))

;; Land registry events
(rf/reg-event-fx
 ::fetch-land
 (fn [{:keys [db]} _]
   (let [filters (get-in db [:land :filters])]
     {:db (-> db
              (assoc-in [:land :status] :loading)
              (assoc-in [:land :error] nil))
      :dispatch-n [[::fetch-land-stats]
                   [::fetch-land-people filters]
                   [::fetch-land-parcels filters]]})))

(rf/reg-event-fx
 ::fetch-land-stats
 (fn [{:keys [db]} _]
   {:db (assoc-in db [:land :status] :loading)
    ::land-request {:url "/api/land/stats"
                    :on-success [::land-stats-success]
                    :on-error [::land-failure]}}))

(rf/reg-event-fx
 ::fetch-land-people
 (fn [{:keys [db]} [_ {:keys [people-search sort]}]]
   (let [params (cond-> []
                  (seq people-search) (conj ["q" people-search])
                  sort (conj ["sort" (name sort)]))
         qs (when (seq params)
              (let [sp (js/URLSearchParams.)]
                (doseq [[k v] params] (.append sp k v))
                (.toString sp)))]
     {:db (assoc-in db [:land :status] :loading)
      ::land-request {:url (str "/api/land/people" (when qs (str "?" qs)))
                      :on-success [::land-people-success]
                      :on-error [::land-failure]}})))

(rf/reg-event-fx
 ::fetch-land-parcels
 (fn [{:keys [db]} [_ {:keys [parcel-number completeness sort]}]]
   (let [params (cond-> []
                  (seq parcel-number) (conj ["parcel-number" parcel-number])
                  completeness (conj ["completeness" (name completeness)])
                  sort (conj ["sort" (name sort)]))
         qs (when (seq params)
              (let [sp (js/URLSearchParams.)]
                (doseq [[k v] params] (.append sp k v))
                (.toString sp)))]
     {:db (assoc-in db [:land :status] :loading)
      ::land-request {:url (str "/api/land/parcels" (when qs (str "?" qs)))
                      :on-success [::land-parcels-success]
                      :on-error [::land-failure]}})))

(rf/reg-event-fx
 ::select-person
 (fn [{:keys [db]} [_ person-id]]
   {:db (assoc-in db [:land :selected :person] person-id)
    ::land-request {:url (str "/api/land/people/" person-id)
                    :on-success [::land-person-detail-success]
                    :on-error [::land-failure]}}))

(rf/reg-event-fx
 ::select-parcel
 (fn [{:keys [db]} [_ parcel-id]]
   {:db (assoc-in db [:land :selected :parcel] parcel-id)
    ::land-request {:url (str "/api/land/parcels/" parcel-id)
                    :on-success [::land-parcel-detail-success]
                    :on-error [::land-failure]}}))

(rf/reg-event-db
 ::land-people-success
 (fn [db [_ payload]]
   (assoc-in db [:land :people] (:people payload))))

(rf/reg-event-db
 ::land-parcels-success
 (fn [db [_ payload]]
   (assoc-in db [:land :parcels] (:parcels payload))))

(rf/reg-event-db
 ::land-person-detail-success
 (fn [db [_ person]]
   (assoc db :land (-> (:land db)
                       (assoc :selected-person person)
                       (assoc :status :ready)
                       (assoc :error nil)))))

(rf/reg-event-db
 ::land-parcel-detail-success
 (fn [db [_ parcel]]
   (assoc db :land (-> (:land db)
                       (assoc :selected-parcel parcel)
                       (assoc :status :ready)
                       (assoc :error nil)))))

(rf/reg-event-db
 ::land-stats-success
 (fn [db [_ stats]]
   (-> db
       (assoc-in [:land :stats] stats)
       (assoc-in [:land :status] :ready)
       (assoc-in [:land :error] nil))))

(rf/reg-event-db
 ::land-failure
 (fn [db [_ {:keys [status body]}]]
   (let [message (or (:error body)
                     (when (= status 401) "Session expired. Please sign in again.")
                     "Unable to load land registry data.")]
     (-> db
         (assoc-in [:land :status] :error)
         (assoc-in [:land :error] message)
         (cond-> (= status 401)
           (assoc :session nil
                  :route :login))))))

(rf/reg-event-db
 ::land-update-filter
 (fn [db [_ k v]]
   (assoc-in db [:land :filters k] v)))

(rf/reg-event-db
 ::fetch-tags-success
 (fn [db [_ payload]]
   (let [tags (normalize-tag-list (:tags payload))]
     (-> db
         (assoc :tags {:items tags :status :ready :error nil})
         (update-in [:tasks :detail] #(when % (assoc % :tag-entry "")))))))

(rf/reg-event-db
 ::fetch-tags-failure
 (fn [db [_ {:keys [status body]}]]
   (let [message (or (:error body) "Unable to load tags.")]
     (-> db
         (assoc-in [:tags :status] :error)
         (assoc-in [:tags :error] message)
         (cond-> (= status 401)
           (assoc :session nil
                  :route :login))))))

(rf/reg-event-db
 ::set-tag-entry
 (fn [db [_ value]]
   (assoc-in db [:tasks :detail :tag-entry] value)))

(rf/reg-event-db
 ::attach-tag
 (fn [db [_ tag-id]]
   (if tag-id
     (update-in db [:tasks :detail :form :tags] (fn [tags] (conj (set (or tags #{})) tag-id)))
     db)))

(rf/reg-event-db
 ::detach-tag
 (fn [db [_ tag-id]]
   (update-in db [:tasks :detail :form :tags] (fn [tags] (disj (set (or tags #{})) tag-id)))))

(rf/reg-event-fx
 ::add-tag-by-name
 (fn [{:keys [db]} [_ raw-name]]
   (let [name (clean-tag-name raw-name)
         tags (get-in db [:tags :items])]
     (cond
       (str/blank? name)
       {:db (assoc-in db [:tasks :detail :tag-entry] "")}

       (find-tag-by-name tags name)
       {:db (-> db
                (update-in [:tasks :detail :form :tags] (fn [ts] (conj (set (or ts #{})) (:tag/id (find-tag-by-name tags name)))))
                (assoc-in [:tasks :detail :tag-entry] ""))}

       :else
       {:db (-> db
                (assoc-in [:tags :status] :saving)
                (assoc-in [:tags :error] nil))
        ::tag-request {:url "/api/tags"
                       :method "POST"
                       :body {:tag/name name}
                       :on-success [::create-tag-success {:attach? true}]
                       :on-error [::tag-operation-failure]}}))))

(rf/reg-event-fx
 ::create-tag-success
 (fn [{:keys [db]} [_ context payload]]
   (let [tag (:tag payload)
         updated (normalize-tag-list (conj (or (get-in db [:tags :items]) []) tag))
         attach? (:attach? context)
         pending-save? (get-in db [:tasks :detail :pending-save?])
         db' (-> db
                 (assoc :tags {:items updated :status :ready :error nil})
                 (assoc-in [:tasks :detail :tag-entry] "")
                 (assoc-in [:tasks :detail :pending-save?] false))]
     {:db (cond-> db'
            attach? (update-in [:tasks :detail :form :tags] (fn [ts] (conj (set (or ts #{})) (:tag/id tag)))))
      :dispatch-n (cond-> [[::fetch-tasks]]
                    pending-save? (conj [::save-task]))})))

(rf/reg-event-fx
 ::rename-tag
 (fn [{:keys [db]} [_ tag-id new-name]]
   (let [name (clean-tag-name new-name)
         tags (get-in db [:tags :items])
         existing (find-tag-by-name tags name)]
     (cond
       (or (str/blank? name) (nil? tag-id)) {:db db}
       (and existing (= (:tag/id existing) tag-id))
       {:db db}
       (and existing (not= (:tag/id existing) tag-id))
       {:db (assoc-in db [:tags :error] "A tag with that name already exists.")}
       :else
       {:db (-> db
                (assoc-in [:tags :status] :saving)
                (assoc-in [:tags :error] nil))
        ::tag-request {:url (str "/api/tags/" tag-id)
                       :method "PUT"
                       :body {:tag/name name}
                       :on-success [::rename-tag-success]
                       :on-error [::tag-operation-failure]}}))))

(rf/reg-event-fx
 ::rename-tag-success
 (fn [{:keys [db]} [_ payload]]
   (let [{:keys [tag]} payload
         updated (normalize-tag-list
                  (map (fn [t]
                         (if (= (:tag/id t) (:tag/id tag))
                           (assoc t :tag/name (:tag/name tag))
                           t))
                       (get-in db [:tags :items])))
         db' (-> db
                 (assoc :tags {:items updated :status :ready :error nil})
                 (update-in [:tasks :items] rename-tag-in-tasks (:tag/id tag) (:tag/name tag))
                 (update-in [:tasks :detail :form :tags] #(set (or % #{}))))]
     {:db db'
      :dispatch-n [[::fetch-tasks]]}))
)

(rf/reg-event-fx
 ::delete-tag
 (fn [{:keys [db]} [_ tag-id]]
   (if (nil? tag-id)
     {:db db}
     {:db (-> db
              (assoc-in [:tags :status] :saving)
              (assoc-in [:tags :error] nil))
      ::tag-request {:url (str "/api/tags/" tag-id)
                     :method "DELETE"
                     :on-success [::delete-tag-success]
                     :on-error [::tag-operation-failure]}})))

(rf/reg-event-fx
 ::delete-tag-success
 (fn [{:keys [db]} [_ payload]]
   (let [tag (:tag payload)
         tag-id (:tag/id tag)
         filtered-tags (->> (get-in db [:tags :items])
                            (remove #(= (:tag/id %) tag-id))
                            normalize-tag-list)
         db' (-> db
                 (assoc :tags {:items filtered-tags :status :ready :error nil})
                 (update-in [:tasks :items] remove-tag-from-tasks tag-id)
                 (update-in [:tasks :detail :form :tags] (fn [ts] (disj (set (or ts #{})) tag-id)))
                 (update-in [:tasks :filters :tag] (fn [current] (when (not= current tag-id) current))))]
     {:db db'
      :dispatch-n [[::fetch-tasks]]}))
)

(rf/reg-event-db
 ::tag-operation-failure
 (fn [db [_ {:keys [status body]}]]
   (let [message (or (:error body) "Tag action failed.")]
     (-> db
         (assoc-in [:tags :status] :error)
         (assoc-in [:tags :error] message)
         (cond-> (= status 401)
           (assoc :session nil
                  :route :login))))))

(def ->keyword-or-nil util/->keyword-or-nil)

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
   (let [task (some #(when (= (:task/id %) task-id) %) (get-in db [:tasks :items]))
         assignees (or (get-in db [:tasks :assignees]) fallback-assignees)]
     (-> db
         (assoc-in [:tasks :selected] task-id)
         (assoc-in [:tasks :detail] (if task
                                      (detail-from-task task)
                                      (blank-detail assignees (:session db))))))))

(defn- validate-task-form
  [{:keys [title description status priority assignee]}]
  (cond
    (str/blank? title) "Title is required"
    (str/blank? description) "Description is required"
    (nil? status) "Status is required"
    (nil? priority) "Priority is required"
    (str/blank? (str assignee)) "Assignee is required"
    :else nil))

(defn- build-update-ops
  [form current-task]
  (let [task-id (or (:id form) (:task/id current-task))
        tags (set (:tags form))
        current-tags (set (map :tag/id (or (:task/tags current-task) [])))
        current-due (iso->input-date (:task/due-date current-task))
        due-iso (input-date->iso (:due-date form))
        general-updates (cond-> {}
                          (not= (:title form) (:task/title current-task)) (assoc :task/title (:title form))
                          (not= (:description form) (:task/description current-task)) (assoc :task/description (:description form))
                          (not= (:priority form) (:task/priority current-task)) (assoc :task/priority (:priority form))
                          (not= tags current-tags) (assoc :task/tags (vec tags))
                          (not= (:extended? form) (boolean (:task/extended? current-task))) (assoc :task/extended? (:extended? form)))]
    (cond-> []
      (and task-id (seq general-updates)) (conj {:url (str "/api/tasks/" task-id)
                                                 :method "PUT"
                                                 :body general-updates})
      (and task-id (not= (:status form) (:task/status current-task))) (conj {:url (str "/api/tasks/" task-id "/status")
                                                                             :method "POST"
                                                                             :body {:task/status (:status form)}})
      (and task-id (not= (:assignee form) (get-in current-task [:task/assignee :user/id]))) (conj {:url (str "/api/tasks/" task-id "/assignee")
                                                                                                    :method "POST"
                                                                                                    :body {:task/assignee (:assignee form)}})
      (and task-id (not= (:due-date form) current-due)) (conj {:url (str "/api/tasks/" task-id "/due-date")
                                                               :method "POST"
                                                               :body {:task/due-date due-iso}})
      (and task-id (not= (:archived? form) (boolean (:task/archived? current-task)))) (conj {:url (str "/api/tasks/" task-id "/archive")
                                                                                            :method "POST"
                                                                                            :body {:task/archived? (:archived? form)}}))))

(rf/reg-event-db
 ::start-new-task
 (fn [db _]
   (let [assignees (or (get-in db [:tasks :assignees]) fallback-assignees)]
     (-> db
         (assoc-in [:tasks :detail] (blank-detail assignees (:session db)))))))

(rf/reg-event-db
 ::reset-detail
 (fn [db _]
   (let [tasks (get-in db [:tasks :items])
         selected-id (get-in db [:tasks :selected])
         selected-task (some #(when (= (:task/id %) selected-id) %) tasks)
         assignees (or (get-in db [:tasks :assignees]) fallback-assignees)]
     (assoc-in db [:tasks :detail] (if selected-task
                                     (detail-from-task selected-task)
                                     (blank-detail assignees (:session db)))))))

(rf/reg-event-db
 ::update-detail-field
 (fn [db [_ field value]]
   (-> db
       (assoc-in [:tasks :detail :form field] value)
       (assoc-in [:tasks :detail :status] :idle)
       (assoc-in [:tasks :detail :error] nil))))

(rf/reg-event-db
 ::toggle-tag
  (fn [db [_ tag]]
    (let [current (get-in db [:tasks :detail :form :tags] #{})
          updated (if (contains? current tag) (disj current tag) (conj current tag))]
      (-> db
          (assoc-in [:tasks :detail :form :tags] updated)
          (assoc-in [:tasks :detail :status] :idle)
          (assoc-in [:tasks :detail :error] nil)))))

(rf/reg-event-fx
 ::save-task
 (fn [{:keys [db]} _]
   (let [form (get-in db [:tasks :detail :form])
         mode (get-in db [:tasks :detail :mode])
         creating? (= mode :create)
         tags-status (get-in db [:tags :status])
         validation-error (validate-task-form form)
         tasks (get-in db [:tasks :items])
         selected-id (get-in db [:tasks :selected])
         current-task (some #(when (= (:task/id %) selected-id) %) tasks)
         due-iso (input-date->iso (:due-date form))
        tags (set (:tags form))]
     (cond
       (= tags-status :saving)
       {:db (-> db
                (assoc-in [:tasks :detail :pending-save?] true)
                (assoc-in [:tasks :detail :status] :saving)
                (assoc-in [:tasks :detail :error] nil))}

       validation-error {:db (-> db
                                 (assoc-in [:tasks :detail :error] validation-error)
                                 (assoc-in [:tasks :detail :status] :error))}
       (and (not creating?) (nil? current-task))
       {:db (-> db
                (assoc-in [:tasks :detail :error] "Select a task to update")
                (assoc-in [:tasks :detail :status] :error))}
       creating?
       (let [body {:task/title (:title form)
                   :task/description (:description form)
                   :task/status (:status form)
                   :task/priority (:priority form)
                   :task/assignee (:assignee form)
                   :task/tags (vec tags)
                   :task/due-date due-iso
                   :task/archived? (boolean (:archived? form))
                   :task/extended? (boolean (:extended? form))}]
         {:db (-> db
                  (assoc-in [:tasks :detail :status] :saving)
                  (assoc-in [:tasks :detail :error] nil))
          ::task-action {:url "/api/tasks"
                         :method "POST"
                         :body body
                         :on-success [::op-success [] {:mode :create}]
                         :on-error [::save-failure]}})
       :else
       (let [ops (build-update-ops form current-task)]
         (if (empty? ops)
           {:db (-> db
                    (assoc-in [:tasks :detail :status] :idle)
                    (assoc-in [:tasks :detail :error] nil))}
           {:db (-> db
                    (assoc-in [:tasks :detail :status] :saving)
                    (assoc-in [:tasks :detail :error] nil))
            ::task-action (assoc (first ops)
                                 :on-success [::op-success (vec (rest ops)) {:mode :update}]
                                 :on-error [::save-failure])})))))) 

(rf/reg-event-fx
 ::delete-task
 (fn [{:keys [db]} [_ task-id]]
   (if (nil? task-id)
     {:db (assoc-in db [:tasks :detail :error] "Select a task to delete")}
     {:db (-> db
              (assoc-in [:tasks :detail :status] :deleting)
              (assoc-in [:tasks :detail :error] nil))
      ::task-action {:url (str "/api/tasks/" task-id)
                     :method "DELETE"
                     :on-success [::delete-task-success task-id]
                     :on-error [::save-failure]}})))

(rf/reg-event-fx
 ::delete-task-success
 (fn [{:keys [db]} [_ task-id _payload]]
   (let [remaining (->> (get-in db [:tasks :items])
                        (remove #(= (:task/id %) task-id))
                        vec)
         next-task (first remaining)
         assignees (or (get-in db [:tasks :assignees]) fallback-assignees)]
     {:db (-> db
              (assoc-in [:tasks :items] remaining)
              (assoc-in [:tasks :selected] (:task/id next-task))
              (assoc-in [:tasks :detail] (if next-task
                                           (detail-from-task next-task)
                                           (blank-detail assignees (:session db)))))
      :dispatch-n [[::fetch-tasks]]})))

(rf/reg-event-fx
 ::op-success
 (fn [{:keys [db]} [_ remaining context payload]]
   (let [task (some-> (:task payload) normalize-task)
         next-op (first remaining)
         rest-ops (vec (rest remaining))
         updated-db (cond-> db
                      task (-> (assoc-in [:tasks :selected] (:task/id task))
                               (assoc-in [:tasks :detail :form] (task->form task)))
                      true (-> (assoc-in [:tasks :detail :error] nil)
                               (assoc-in [:tasks :detail :mode] (if (= (:mode context) :create) :edit (get-in db [:tasks :detail :mode])))
                               (assoc-in [:tasks :detail :status] (if next-op :saving :success))))]
     (if next-op
       {:db updated-db
        ::task-action (assoc next-op
                             :on-success [::op-success rest-ops context]
                             :on-error [::save-failure])}
       {:db updated-db
        :dispatch-n [[::fetch-tasks]]}))))

(rf/reg-event-db
 ::save-failure
 (fn [db [_ {:keys [status body]}]]
   (let [message (or (:error body)
                     (when (= status 401) "Session expired. Please log in again.")
                     "Unable to save task.")]
     (-> db
         (assoc-in [:tasks :detail :status] :error)
         (assoc-in [:tasks :detail :error] message)
         (cond-> (= status 401)
           (assoc :session nil
                  :route :login))))))

(rf/reg-sub ::login-state (fn [db _] (:login db)))
(rf/reg-sub ::session (fn [db _] (:session db)))
(rf/reg-sub ::nav (fn [db _] (:nav db)))
(rf/reg-sub ::tasks (fn [db _] (:tasks db)))
(rf/reg-sub ::tags (fn [db _] (:tags db)))
(rf/reg-sub ::theme (fn [db _] (:theme db)))
(rf/reg-sub ::home (fn [db _] (:home db)))
(rf/reg-sub ::land (fn [db _] (:land db)))
(rf/reg-sub ::route (fn [db _] (:route db)))
(rf/reg-sub
 ::selected-task
 (fn [db _]
   (let [selected (get-in db [:tasks :selected])
         items (get-in db [:tasks :items])]
     (some #(when (= (:task/id %) selected) %) items))))
(rf/reg-sub ::task-detail (fn [db _] (get-in db [:tasks :detail])))


(def app-options state/app-options)

(defn app-root []
  (let [session @(rf/subscribe [::session])
        route @(rf/subscribe [::route])]
    [:div
     (if session
       (case route
         :home [home/home-shell]
         :land (if land-enabled? [land/land-shell] [home/home-shell])
         [tasks-ui/task-shell])
       [login/login-page])
     [:div.theme-toggle-container
      [shell/theme-toggle]]]))

(defn mount-root []
  (when-let [root (.getElementById js/document "app")]
    (-> root
        (rdom/create-root)
        (rdom/render [app-root]))))

(defn ^:export init []
  (rf/dispatch-sync [::initialize])
  (mount-root))

(defn reload []
  (rf/clear-subscription-cache!)
  (mount-root))
