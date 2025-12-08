(ns darelwasl.app
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.dom :as rdom]))

(def theme-storage-key "darelwasl/theme")
(def theme-options
  [{:id :theme/default :label "Light" :icon :sun}
  {:id :theme/dark :label "Dark" :icon :moon}])

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

(def task-status-options (remove #(nil? (:id %)) status-options))
(def task-priority-options (remove #(nil? (:id %)) priority-options))

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

(def default-task-form
  {:id nil
   :title ""
   :description ""
   :status :todo
   :priority :medium
   :assignee nil
   :due-date ""
   :tags #{}
   :archived? false
   :extended? false})

(def default-task-detail
  {:mode :edit
   :status :idle
   :error nil
   :tag-entry ""
   :form (assoc default-task-form :assignee (:id (first fallback-assignees)))})

(def default-task-state
  {:items []
   :status :idle
   :error nil
   :filters default-task-filters
   :selected nil
   :assignees []
   :detail default-task-detail})

(def default-tags-state
  {:items []
   :status :idle
   :error nil})

(def default-theme-state
  {:id :theme/default})

(def default-db
  {:route :login
   :session nil
   :theme default-theme-state
   :tags default-tags-state
   :login default-login-state
   :tasks default-task-state})

(def task-entity-config
  {:type :entity.type/task
   :list {:title "Tasks"
          :key :task/id
          :meta-fn (fn [items] (str (count items) " items"))
          :badge "Tasks"}
   :detail {:title "Task detail"
            :create-title "Draft a new task"
            :badge "Detail"
            :create-badge "Compose"
            :placeholder-title "Select a task"
            :placeholder-copy "Choose a task from the list or start a fresh one. Full create/edit is available here."}}) 

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

(defn- clean-tag-name [s]
  (-> s (or "") str/trim))

(defn- tag-slug [s]
  (-> s clean-tag-name str/lower-case))

(defn- find-tag-by-name
  [tags name]
  (let [needle (tag-slug name)]
    (some #(when (= needle (tag-slug (:tag/name %))) %) tags)))

(defn- remove-tag-from-tasks
  [tasks tag-id]
  (mapv (fn [task]
          (update task :task/tags
                  (fn [tags]
                    (->> tags
                         (remove #(= (:tag/id %) tag-id))
                         vec))))
        tasks))

(defn- rename-tag-in-tasks
  [tasks tag-id new-name]
  (mapv (fn [task]
          (update task :task/tags
                  (fn [tags]
                    (mapv (fn [t]
                            (if (= (:tag/id t) tag-id)
                              (assoc t :tag/name new-name)
                              t))
                          tags))))
        tasks))

(defn- normalize-tag-list [tags]
  (->> tags
       (keep (fn [t]
               (when (and (:tag/id t) (:tag/name t))
                 {:tag/id (:tag/id t)
                  :tag/name (clean-tag-name (:tag/name t))})))
       (sort-by (comp tag-slug :tag/name))
       vec))

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
         theme-id (resolve-theme-id stored)]
     {:db (assoc-in default-db [:theme :id] theme-id)
      ::apply-theme theme-id
      :dispatch [::restore-session]})))

(rf/reg-event-db
 ::set-view
 (fn [db [_ view]]
   (assoc db :active-view view)))

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
     :dispatch-n [[::fetch-tags]
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
                   :tasks default-task-state
                   :tags default-tags-state)
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
(rf/reg-sub ::tasks (fn [db _] (:tasks db)))
(rf/reg-sub ::tags (fn [db _] (:tags db)))
(rf/reg-sub ::theme (fn [db _] (:theme db)))
(rf/reg-sub
 ::selected-task
 (fn [db _]
   (let [selected (get-in db [:tasks :selected])
         items (get-in db [:tasks :items])]
     (some #(when (= (:task/id %) selected) %) items))))
(rf/reg-sub ::task-detail (fn [db _] (get-in db [:tasks :detail])))

(defn badge-pill [text & {:keys [tone]}]
  [:div.badge {:class (when (= tone :muted) "muted")}
   [:span.badge-dot]
   text])

(defn login-form []
  (let [{:keys [username password status error]} @(rf/subscribe [::login-state])
        loading? (= status :loading)]
    [:div.login-card
     [:div.login-card__header
      [:h2 "Sign in"]
      [:p "Enter your credentials to continue."]]
     [:form.login-form
      {:on-submit (fn [e]
                    (.preventDefault e)
                    (rf/dispatch [::submit-login]))}
      [:label.form-label {:for "username"} "Username"]
      [:input.form-input {:id "username"
                          :name "username"
                          :type "text"
                          :placeholder "Enter username"
                          :value username
                          :autoComplete "username"
                          :on-change #(rf/dispatch [::update-login-field :username (.. % -target -value)])
                          :disabled loading?}]
      [:label.form-label {:for "password"} "Password"]
      [:input.form-input {:id "password"
                          :name "password"
                          :type "password"
                          :placeholder "Enter password"
                          :value password
                          :autoComplete "current-password"
                          :on-change #(rf/dispatch [::update-login-field :password (.. % -target -value)])
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

(defn tag-chip [tag-name]
  [chip (str "#" tag-name) :class "tag"])

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
        {:keys [items]} @(rf/subscribe [::tags])
        task-status status
        {:keys [status priority tag assignee archived sort order]} filters
        available-assignees (if (seq assignees) assignees fallback-assignees)
        tag-options (cons {:id nil :label "All tags"}
                          (map (fn [t] {:id (str (:tag/id t)) :label (:tag/name t)}) items))]
    [:div.panel.tasks-controls.compact
     [:div.controls-header
      [:div
       [:h3 "Filters"]
       [:p "Quickly narrow tasks by status, priority, tag, or assignee."]]
      [:div.controls-actions
       [:button.button.secondary {:type "button"
                                  :on-click #(rf/dispatch [::clear-filters])
                                  :disabled (= filters default-task-filters)}
        "Reset"]
       [:button.button.secondary {:type "button"
                                  :on-click #(rf/dispatch [::fetch-tasks])
                                  :disabled (= task-status :loading)}
        "Refresh"]]]
     [:div.controls-grid
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
                      :value (when tag (str tag))
                      :on-change #(rf/dispatch [::update-filter :tag (when-not (str/blank? %) %)])}]
      [filter-select {:label "Assignee"
                      :options (cons {:id nil :label "All assignees"} available-assignees)
                      :value assignee
                      :on-change #(rf/dispatch [::update-filter :assignee (when-not (str/blank? %) %)])}]]
     [:div.controls-grid
      [:div.filter-group
       [:span.filter-label "Archived"]
       [:div.chip-row
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

(defn entity-list
  "Generic list panel with states. render-row receives (item selected?)."
  [{:keys [title meta items status error selected render-row key-fn]}]
  (let [items (or items [])
        key-fn (or key-fn :task/id)]
    [:div.panel.task-list-panel
     [:div.section-header
      [:h2 title]
      (when meta [:span.meta meta])]
     (case status
       :loading [loading-state]
       :error [error-state error]
       (if (seq items)
         [:div.task-list
          (for [t items]
            ^{:key (str (key-fn t))}
            [render-row t (= selected (key-fn t))])]
         [empty-state]))]))

(defn entity-detail
  "Generic detail shell with header/actions and placeholder handling."
  [{:keys [title badge meta actions error placeholder content footer]}]
  [:div.panel.task-preview
   [:div.section-header
    [:div
     (when badge [badge-pill badge])
     [:h2 title]
     (when meta [:div.meta meta])]
    (when actions
      [:div.actions actions])]
   (cond
     error [:div.form-error error]
     placeholder placeholder
     :else
     [:<>
      content
      (when footer footer)])])

(defn task-card
  [{:task/keys [id title description status priority tags due-date updated-at assignee archived?] :as task} selected? tag-index]
  (let [tag-list (or tags [])
        tag-names (map (fn [t] (or (:tag/name t) (get tag-index (:tag/id t)) "Tag")) tag-list)]
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
       (for [t tag-names]
         ^{:key (str id "-" t)}
         [tag-chip t])]]]))

(defn task-list []
  (let [{:keys [items status error selected]} @(rf/subscribe [::tasks])
        tag-state @(rf/subscribe [::tags])
        tag-index (into {} (map (fn [t] [(:tag/id t) (:tag/name t)]) (:items tag-state)))]
    [entity-list {:title (get-in task-entity-config [:list :title])
                  :meta ((get-in task-entity-config [:list :meta-fn]) items)
                  :items items
                  :status status
                  :error error
                  :selected selected
                  :key-fn :task/id
                  :render-row (fn [t selected?]
                                [task-card t selected? tag-index])}]))

(defn tag-selector
  [{:keys [selected-tags tag-state tag-entry]}]
  (let [tags (:items tag-state)
        saving? (= (:status tag-state) :saving)]
    [:div.tag-selector
     [:div.selected-tags
      (if (seq selected-tags)
        (for [tid selected-tags
              :let [tag (some #(when (= (:tag/id %) tid) %) tags)
                    name (or (:tag/name tag) "Tag")]]
          ^{:key (str "sel-" tid)}
          [:div.tag-chip
           [:span "#" name]
           [:button.icon-button {:type "button"
                                 :on-click #(rf/dispatch [::detach-tag tid])}
            "×"]])
        [:span.helper-text "No tags yet. Add one below."])]
     [:div.available-tags
      (for [tag tags]
        (let [tid (:tag/id tag)
              name (:tag/name tag)
              checked? (contains? selected-tags tid)]
          ^{:key (str "tag-" tid)}
          [:div.tag-row
           [:label
            [:input {:type "checkbox"
                     :checked checked?
                     :on-change #(if (.. % -target -checked)
                                   (rf/dispatch [::attach-tag tid])
                                   (rf/dispatch [::detach-tag tid]))}]
            [:span name]]
           [:div.tag-row__actions
            [:button.link {:type "button"
                           :disabled saving?
                           :on-click (fn []
                                       (when-let [next (js/prompt "Rename tag" name)]
                                         (rf/dispatch [::rename-tag tid next])))}
             "Rename"]
            [:button.link.danger {:type "button"
                                  :disabled saving?
                                  :on-click (fn []
                                              (when (js/confirm (str "Delete tag \"" name "\"? This will remove it from tasks."))
                                                (rf/dispatch [::delete-tag tid])))}
             "Delete"]]]))]
     [:div.tag-input
      [:input.form-input {:type "text"
                          :placeholder "Create or attach tag (press Enter)"
                          :value tag-entry
                          :on-change #(rf/dispatch [::set-tag-entry (.. % -target -value)])
                          :on-key-down (fn [e]
                                         (when (= "Enter" (.-key e))
                                           (.preventDefault e)
                                           (rf/dispatch [::add-tag-by-name (.. e -target -value)])))
                          :disabled saving?}]
      [:button.button.secondary {:type "button"
                                 :disabled saving?
                                 :on-click #(rf/dispatch [::add-tag-by-name tag-entry])}
       "Add tag"]]
     (when-let [err (:error tag-state)]
       [:div.form-error err])]))

(defn task-preview []
  (let [{:keys [assignees]} @(rf/subscribe [::tasks])
        detail @(rf/subscribe [::task-detail])
        task @(rf/subscribe [::selected-task])
        {:keys [form mode status error]} detail
        detail-status status
        task-status (:status form)
        create? (= mode :create)
        saving? (= detail-status :saving)
        success? (= detail-status :success)
        available-assignees (if (seq assignees) assignees fallback-assignees)]
    (let [placeholder (when (and (not create?) (nil? task))
                        [:div.placeholder-card
                         [:strong (get-in task-entity-config [:detail :placeholder-title])]
                         [:p (get-in task-entity-config [:detail :placeholder-copy])]])
          title (if create?
                  (get-in task-entity-config [:detail :create-title])
                  (get-in task-entity-config [:detail :title]))
          badge (if create?
                  (get-in task-entity-config [:detail :create-badge])
                  (get-in task-entity-config [:detail :badge]))
          meta (if create?
                 "Create a task with full fields and feature flag handling."
                 (or (:task/title task) "Select a task to edit"))
          footer (case detail-status
                   :saving [:span.pill "Saving..."]
                   :success [:span.pill "Saved"]
                   :deleting [:span.pill "Deleting..."]
                   nil)]
      [entity-detail
       {:title title
        :badge badge
        :meta meta
        :actions [:<>
                  [:button.button.secondary {:type "button"
                                             :on-click #(rf/dispatch [::start-new-task])
                                             :disabled saving?}
                   "New task"]
                  [:button.button.secondary {:type "button"
                                             :on-click #(rf/dispatch [::reset-detail])
                                             :disabled saving?}
                   "Reset"]]
        :placeholder placeholder
        :content (when-not placeholder
                   (let [{:keys [title description priority assignee due-date tags archived? extended?]} form
                         tag-state @(rf/subscribe [::tags])
                         current-assignee (or assignee (:id (first available-assignees)) "")]
                     [:form.detail-form
                      {:on-submit (fn [e]
                                    (.preventDefault e)
                                    (rf/dispatch [::save-task]))}
                      (when error
                        [:div.form-error error])
                      [:div.detail-meta
                       [status-chip {:task/status task-status :task/archived? archived?}]
                       [priority-chip priority]
                       (when archived?
                         [:span.chip.status.muted "Archived"])
                       (when extended?
                         [:span.chip.tag "Extended flag"])]
                      [:div.detail-grid
                       [:div.form-group
                        [:label.form-label {:for "task-title"} "Title"]
                        [:input.form-input {:id "task-title"
                                            :type "text"
                                            :value title
                                            :placeholder "Write a concise title"
                                            :on-change #(rf/dispatch [::update-detail-field :title (.. % -target -value)])
                                            :disabled saving?}]]
                       [:div.form-group
                        [:label.form-label {:for "task-status"} "Status"]
                        [:select.form-input {:id "task-status"
                                             :value (name (or task-status :todo))
                                             :on-change #(rf/dispatch [::update-detail-field :status (->keyword-or-nil (.. % -target -value))])
                                             :disabled saving?}
                         (for [{:keys [id label]} task-status-options]
                           ^{:key (name id)}
                           [:option {:value (name id)} label])]]
                       [:div.form-group
                        [:label.form-label {:for "task-assignee"} "Assignee"]
                        [:select.form-input {:id "task-assignee"
                                             :value current-assignee
                                             :on-change #(rf/dispatch [::update-detail-field :assignee (.. % -target -value)])
                                             :disabled saving?}
                         (for [{:keys [id label]} available-assignees]
                           ^{:key (str "assignee-" id)}
                           [:option {:value id} label])]]
                       [:div.form-group
                        [:label.form-label {:for "task-due"} "Due date"]
                        [:input.form-input {:id "task-due"
                                            :type "date"
                                            :value (or due-date "")
                                            :on-change #(rf/dispatch [::update-detail-field :due-date (.. % -target -value)])
                                            :disabled saving?}]]
                       [:div.form-group
                        [:label.form-label {:for "task-priority"} "Priority"]
                        [:select.form-input {:id "task-priority"
                                             :value (name (or priority :medium))
                                             :on-change #(rf/dispatch [::update-detail-field :priority (->keyword-or-nil (.. % -target -value))])
                                             :disabled saving?}
                         (for [{:keys [id label]} task-priority-options]
                           ^{:key (name id)}
                           [:option {:value (name id)} label])]]
                       [:div.form-group
                        [:label.form-label "Flags"]
                        [:div.toggle-row
                         [:label.toggle
                          [:input {:type "checkbox"
                                   :checked (boolean extended?)
                                   :on-change #(rf/dispatch [::update-detail-field :extended? (.. % -target -checked)])
                                   :disabled saving?}]
                          [:span "Extended"]]
                         [:label.toggle
                          [:input {:type "checkbox"
                                   :checked (boolean archived?)
                                   :on-change #(rf/dispatch [::update-detail-field :archived? (.. % -target -checked)])
                                   :disabled saving?}]
                          [:span "Archived"]]]]]
                      [:div.form-group
                       [:label.form-label "Tags"]
                       [tag-selector {:selected-tags tags
                                      :tag-state tag-state
                                      :tag-entry (:tag-entry detail)}]]
                      [:div.form-group
                       [:label.form-label {:for "task-description"} "Description"]
                       [:textarea.form-input {:id "task-description"
                                              :rows 5
                                              :value description
                                              :placeholder "Add context, acceptance, and next steps"
                                              :on-change #(rf/dispatch [::update-detail-field :description (.. % -target -value)])
                                              :disabled saving?}]]
                      [:div.detail-actions
                       [:button.button {:type "submit"
                                        :disabled saving?}
                        (if create? "Create task" "Save changes")]
                       [:button.button.secondary {:type "button"
                                                  :on-click #(rf/dispatch [::reset-detail])
                                                  :disabled saving?}
                        "Cancel"]
                       (when-not create?
                         [:button.button.danger {:type "button"
                                                 :disabled (= detail-status :deleting)
                                                 :on-click #(when (js/confirm "Delete this task? This cannot be undone.")
                                                              (rf/dispatch [::delete-task (:id form)]))}
                          (if (= detail-status :deleting) "Deleting..." "Delete")])]]))
        :footer footer}]))
    )

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

(defn theme-toggle []
  (let [theme @(rf/subscribe [::theme])
        current (:id theme)]
    [:div.theme-toggle
     (for [{:keys [id label icon]} theme-options]
       ^{:key (name id)}
       [:button.theme-toggle__btn {:type "button"
                                   :class (when (= current id) "active")
                                   :on-click #(rf/dispatch [::set-theme id])}
        [:span.icon (case icon
                      :sun "☀"
                      :moon "☾"
                      "•")]
        [:span label]])]))

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
       [login-page])
     [:div.theme-toggle-container
      [theme-toggle]]]))

(defn mount-root []
  (when-let [root (.getElementById js/document "app")]
    (rdom/render [app-root] root)))

(defn ^:export init []
  (rf/dispatch-sync [::initialize])
  (mount-root))

(defn reload []
  (rf/clear-subscription-cache!)
  (mount-root))
