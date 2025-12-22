(ns darelwasl.app
  (:require [clojure.string :as str]
            [darelwasl.features.betting :as betting-ui]
            [darelwasl.features.home :as home]
            [darelwasl.features.land :as land]
            [darelwasl.features.login :as login]
            [darelwasl.features.control-panel :as control-panel]
            [darelwasl.features.terminal :as terminal-ui]
            [darelwasl.features.tasks :as tasks-ui]
            [darelwasl.fx :as fx]
            [darelwasl.state :as state]
            [darelwasl.shared.block-types :as block-types]
            [darelwasl.ui.components :as ui]
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
(def default-betting-state state/default-betting-state)
(def default-betting-form state/default-betting-form)
(def default-betting-odds state/default-betting-odds)
(def default-betting-bets state/default-betting-bets)
(def default-terminal-state state/default-terminal-state)
(def default-page-form state/default-page-form)
(def default-block-form state/default-block-form)
(def default-tag-form state/default-tag-form)
(def default-control-state state/default-control-state)
(def default-db state/default-db)

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
  [{:keys [status priority tag assignee archived sort order page page-size limit offset]}]
  (let [limit-val (max 1 (or page-size limit 25))
        page-val (max 1 (or page 1))
        offset-val (max 0 (if page-size
                            (* (dec page-val) limit-val)
                            (or offset 0)))
        params (cond-> []
                 status (conj ["status" (name status)])
                 priority (conj ["priority" (name priority)])
                 tag (conj ["tag" (str tag)])
                 assignee (conj ["assignee" (str assignee)])
                 (true? archived) (conj ["archived" "true"])
                 (= archived :all) (conj ["archived" "all"])
                 sort (conj ["sort" (name sort)])
                 order (conj ["order" (name order)])
                 limit-val (conj ["limit" limit-val])
                 true (conj ["offset" offset-val]))]
    (when (seq params)
      (let [sp (js/URLSearchParams.)]
        (doseq [[k v] params]
          (.append sp k v))
        (.toString sp)))))

(defn- ref-id
  [entry kw]
  (cond
    (map? entry) (get entry kw)
    (vector? entry) (second entry)
    :else entry))

(defn- present-str
  [s]
  (let [trimmed (str/trim (or s ""))]
    (when-not (str/blank? trimmed) trimmed)))

(defn- parse-long-safe
  [v]
  (cond
    (number? v) (long v)
    (string? v) (let [s (str/trim v)
                      n (js/parseInt s 10)]
                  (when-not (js/isNaN n) n))
    :else nil))

(defn- parse-double-safe
  [v]
  (cond
    (number? v) (double v)
    (string? v) (let [s (-> v str/trim (str/replace #"," "."))
                      n (js/parseFloat s)]
                  (when-not (js/isNaN n) n))
    :else nil))

(defn- page->form
  [page]
  (if-not page
    default-page-form
    {:id (:content.page/id page)
     :title (or (:content.page/title page) "")
     :path (or (:content.page/path page) "")
     :summary (or (:content.page/summary page) "")
     :navigation-order (or (:content.page/navigation-order page) "")
     :visible? (not= false (:content.page/visible? page))
     :tags (set (map #(ref-id % :content.tag/id) (:content.page/tag page)))
     :blocks (vec (map #(ref-id % :content.block/id) (:content.page/blocks page)))}))

(defn- block->form
  [block]
  (if-not block
    default-block-form
    {:id (:content.block/id block)
     :page (ref-id (:content.block/page block) :content.page/id)
     :type (:content.block/type block)
     :title (or (:content.block/title block) "")
     :body (or (:content.block/body block) "")
     :media-ref (or (:content.block/media-ref block) "")
     :slug (or (:content.block/slug block) "")
     :order (or (:content.block/order block) 0)
     :visible? (not= false (:content.block/visible? block))
     :tags (set (map #(ref-id % :content.tag/id) (:content.block/tag block)))}))

(defn- tag->form
  [tag]
  (if-not tag
    default-tag-form
    {:id (:content.tag/id tag)
     :name (or (:content.tag/name tag) "")
     :slug (or (:content.tag/slug tag) "")
     :description (or (:content.tag/description tag) "")}))

(defn- page-detail
  [page]
  {:form (page->form page)
   :mode (if page :edit :create)
   :status :idle
   :error nil})

(defn- block-detail
  [block]
  {:form (block->form block)
   :mode (if block :edit :create)
   :status :idle
   :error nil})

(defn- tag-detail
  [tag]
  {:form (tag->form tag)
   :mode (if tag :edit :create)
   :status :idle
   :error nil})

(defn- validate-page-form
  [{:keys [title path]}]
  (cond
    (str/blank? title) "Title is required"
    (str/blank? path) "Path is required"
    :else nil))

(defn- page-form->payload
  [{:keys [title path summary navigation-order visible? tags blocks]}]
  (let [nav (parse-long-safe navigation-order)
        title' (present-str title)
        path' (present-str path)
        summary' (present-str summary)]
    (cond-> {:content.page/title title'
             :content.page/path path'
             :content.page/visible? (boolean visible?)}
      summary' (assoc :content.page/summary summary')
      (some? nav) (assoc :content.page/navigation-order nav)
      (seq tags) (assoc :content.page/tag (vec tags))
      (seq blocks) (assoc :content.page/blocks (vec blocks)))))

(defn- validate-block-form
  [{:keys [type]}]
  (when (nil? type) "Type is required"))

(defn- block-form->payload
  [{:keys [page type title body media-ref slug order visible? tags]}]
  (let [order' (parse-long-safe order)
        slug' (present-str slug)
        title' (present-str title)
        body' (present-str body)
        media' (present-str media-ref)]
    (cond-> {:content.block/type type
             :content.block/visible? (boolean visible?)}
      page (assoc :content.block/page page)
      title' (assoc :content.block/title title')
      body' (assoc :content.block/body body')
      media' (assoc :content.block/media-ref media')
      slug' (assoc :content.block/slug slug')
      (some? order') (assoc :content.block/order order')
      (seq tags) (assoc :content.block/tag (vec tags)))))

(defn- validate-tag-form
  [{:keys [name]}]
  (when (str/blank? name) "Name is required"))

(defn- tag-form->payload
  [{:keys [name slug description]}]
  (let [name' (present-str name)
        slug' (present-str slug)
        description' (present-str description)]
    (cond-> {:content.tag/name name'}
      slug' (assoc :content.tag/slug slug')
      description' (assoc :content.tag/description description'))))

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
  [{:keys [todo in-progress pending done]}]
  [ui/stat-group {:cards [{:label "To do" :value (or todo 0)}
                          {:label "In progress" :value (or in-progress 0)}
                          {:label "Pending" :value (or pending 0)}
                          {:label "Done" :value (or done 0)}]}])

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
  (let [kw-provenance (fn [p]
                        (some-> p
                                (update :fact/source-type kw)
                                (update :fact/adapter kw)))]
    (-> task
        (update :task/status kw)
        (update :task/priority kw)
        (update :task/tags normalize-tag-list)
        (update :task/archived? boolean)
        (update :task/extended? boolean)
        (update :fact/source-type kw)
        (update :fact/adapter kw)
        (update :task/provenance kw-provenance))))

(defn- task->form
  [task]
  (merge default-task-form
         {:id (:task/id task)
          :title (or (:task/title task) "")
          :description (or (:task/description task) "")
          :status (:task/status task)
          :pending-reason (:task/pending-reason task)
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

(defn- closed-detail
  [assignees session]
  (-> default-task-detail
      (assoc :mode :closed
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
 ::set-body-scroll-lock
 (fn [locked?]
   (let [class-list (.-classList js/document.body)]
     (if locked?
       (.add class-list "detail-open")
       (.remove class-list "detail-open")))))

(rf/reg-fx
 ::persist-last-route
 (fn [route]
   (when route
     (try
       (.setItem js/localStorage nav-storage-key (name route))
       (catch :default _)))))

(rf/reg-event-fx
 ::initialize
 (fn [_ _]
   (let [stored (try
                 (.getItem js/localStorage theme-storage-key)
                 (catch :default _ nil))
        stored-route (try
                       (.getItem js/localStorage nav-storage-key)
                       (catch :default _ nil))
       allowed-routes (state/allowed-routes nil)
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
   (let [allowed (state/allowed-routes (:session db))
         safe-route (if (contains? allowed route) route :home)
         dispatches (cond-> []
                       (= safe-route :home) (conj [::fetch-home])
                       (= safe-route :tasks) (conj [::fetch-tasks])
                       (= safe-route :land) (conj [::fetch-land])
                       (= safe-route :betting) (conj [::fetch-betting-events])
                       (= safe-route :terminal) (conj [::fetch-terminal-sessions])
                       (= safe-route :control-panel) (conj [::fetch-content]))]
     {:db (-> db
              (assoc :route safe-route)
              (assoc-in [:nav :menu-open?] false)
              (assoc-in [:nav :last-route] safe-route))
      :dispatch-n dispatches
      ::persist-last-route safe-route
      ::set-body-scroll-lock false})))

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
        ::fx/http {:url "/api/login"
                   :method "POST"
                   :body {"user/username" username
                          "user/password" password}
                   :on-success [::login-success]
                   :on-error [::login-failure]}}))))

(rf/reg-event-fx
 ::login-success
  (fn [{:keys [db]} [_ payload]]
   (let [token (:session/token payload)
         user-id (:user/id payload)
         username (:user/username payload)
         preferred-route (let [cand (get-in db [:nav :last-route])
                               allowed (state/allowed-routes {:user {:roles (:user/roles payload)}})]
                           (if (contains? allowed cand) cand :home))
         db' (-> db
                 (assoc :session {:token token
                                  :user {:id user-id
                                         :username username
                                         :roles (:user/roles payload)}})
                 (assoc :route preferred-route)
                 (assoc-in [:nav :last-route] preferred-route)
                 (assoc :tasks default-task-state)
                 (assoc :betting default-betting-state)
                 (assoc :control default-control-state)
                 (assoc :terminal default-terminal-state)
                 (assoc :home default-home-state)
                 (assoc-in [:login :status] :success)
                 (assoc-in [:login :password] "")
                 (assoc-in [:login :error] nil))]
    (let [dispatches (cond-> [[::fetch-tags]
                              [::fetch-home]
                              [::fetch-tasks]]
                       (= preferred-route :betting) (conj [::fetch-betting-events])
                       (= preferred-route :terminal) (conj [::fetch-terminal-sessions]))]
      {:db db'
       ::persist-last-route preferred-route
       :dispatch-n dispatches}))))

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
    ::fx/http {:url "/api/session"
               :method "GET"
               :on-success [::session-restored]
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
            :betting default-betting-state
            :control default-control-state
                   :tags default-tags-state
                   :home default-home-state)
            (assoc :login (assoc default-login-state :username (or (get-in db [:session :user :username])
                                                                   (get-in db [:login :username])
                                                                   ""))))}))

(rf/reg-event-fx
 ::fetch-tasks
 (fn [{:keys [db]} _]
   (let [filters (get-in db [:tasks :filters])]
     {:db (state/mark-loading db [:tasks])
      ::fx/http {:url (str "/api/tasks" (when-let [qs (build-query filters)] (str "?" qs)))
                 :method "GET"
                 :on-success [::fetch-success]
                 :on-error [::fetch-failure]}})))

(rf/reg-event-db
 ::fetch-success
 (fn [db [_ payload]]
   (let [tasks (mapv normalize-task (:tasks payload))
         selected (let [current (get-in db [:tasks :selected])]
                    (when (some #(= (:task/id %) current) tasks)
                      current))
         assignees (let [opts (assignees-from-tasks tasks)]
                     (if (seq opts) opts fallback-assignees))
         pagination-raw (:pagination payload)
         fallback-limit (or (get-in db [:tasks :filters :page-size]) 25)
         fallback-page (or (get-in db [:tasks :filters :page]) 1)
         limit (max 1 (or (:limit pagination-raw) fallback-limit))
         offset (max 0 (or (:offset pagination-raw) (* (dec fallback-page) fallback-limit)))
         total (max (count tasks) (or (:total pagination-raw) (count tasks)))
         page (max 1 (or (:page pagination-raw) (inc (quot offset limit))))
         pages (max 1 (int (Math/ceil (/ total (double limit)))))]
     (-> db
         (assoc-in [:tasks :items] tasks)
         (assoc-in [:tasks :selected] selected)
          (assoc-in [:tasks :assignees] assignees)
         (assoc-in [:tasks :filters :page] page)
         (assoc-in [:tasks :filters :page-size] limit)
         (assoc-in [:tasks :pagination] {:limit limit
                                         :offset offset
                                         :total total
                                         :page page
                                         :pages pages})
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
                       :else (closed-detail assignees (:session db)))))
          (assoc-in [:tasks :status] (if (seq tasks) :ready :empty))
         (assoc-in [:tasks :error] nil)))))

(rf/reg-event-db
 ::fetch-failure
 (fn [db [_ {:keys [status body]}]]
   (let [message (or (:error body)
                     (when (= status 401) "Session expired. Please sign in again.")
                     "Unable to load tasks.")]
     (-> (state/mark-error db [:tasks] message)
         (cond-> (= status 401)
           (assoc :session nil
                  :route :login))))))

(rf/reg-event-fx
 ::fetch-tags
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc-in [:tags :status] :loading)
            (assoc-in [:tags :error] nil))
    ::fx/http {:url "/api/tags"
               :method "GET"
               :on-success [::fetch-tags-success]
               :on-error [::fetch-tags-failure]}}))

(rf/reg-event-fx
 ::fetch-home
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc-in [:home :status] :loading)
            (assoc-in [:home :error] nil))
    :fx [[::fx/http {:url "/api/tasks/counts"
                     :method "GET"
                     :on-success [::fetch-home-counts-success]
                     :on-error [::fetch-home-failure]}]
         [::fx/http {:url "/api/tasks/recent"
                     :method "GET"
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
     (-> (state/mark-error db [:home] message)
         (cond-> (= status 401)
           (assoc :session nil
                  :route :login))))))

;; Control panel content
(rf/reg-event-fx
 ::fetch-content
 (fn [{:keys [db]} _]
   {:db (-> db
            (state/mark-loading [:control :pages])
            (state/mark-loading [:control :blocks])
            (state/mark-loading [:control :tags])
            (state/mark-loading [:control :journey])
            (state/mark-loading [:control :activation])
            (state/mark-loading [:control :v2]))
    :fx [[::fx/http {:url "/api/content/pages"
                     :method "GET"
                     :on-success [::fetch-pages-success]
                     :on-error [::content-failure :pages]}]
         [::fx/http {:url "/api/content/blocks"
                     :method "GET"
                     :on-success [::fetch-blocks-success]
                     :on-error [::content-failure :blocks]}]
         [::fx/http {:url "/api/content/tags"
                     :method "GET"
                     :on-success [::fetch-tags-success-control]
                     :on-error [::content-failure :tags]}]
         [::fx/http {:url "/api/content/v2"
                     :method "GET"
                     :on-success [::fetch-content-v2-success]
                     :on-error [::content-failure :v2]}]]}))

(rf/reg-event-db
 ::fetch-content-v2-success
 (fn [db [_ payload]]
   (let [data (or payload {})
         any? (some (comp seq val) data)
         status (if any? :ready :empty)
         phases (vec (:journey-phases data))
         steps (vec (:activation-steps data))
         business (first (:businesses data))
         contact (first (:contacts data))
         phase-selected (some-> (get-in db [:control :journey :selected])
                                (#(some (fn [p] (when (= (:journey.phase/id p) %) p)) phases)))
         step-selected (some-> (get-in db [:control :activation :selected])
                               (#(some (fn [s] (when (= (:activation.step/id s) %) s)) steps)))]
     (-> db
         (assoc-in [:control :v2 :data] data)
         (assoc-in [:control :v2 :status] status)
         (assoc-in [:control :v2 :error] nil)
         (assoc-in [:control :journey :items] phases)
         (assoc-in [:control :journey :selected] (or (:journey.phase/id phase-selected) (:journey.phase/id (first phases))))
         (assoc-in [:control :journey :status] (if (seq phases) :ready :empty))
         (assoc-in [:control :journey :error] nil)
         (assoc-in [:control :journey :detail] {:form (or (and phase-selected {:id (:journey.phase/id phase-selected)
                                                                              :title (:journey.phase/title phase-selected)
                                                                              :kind (:journey.phase/kind phase-selected)
                                                                              :order (:journey.phase/order phase-selected)
                                                                              :bullets (str/join "\n" (:journey.phase/bullets phase-selected))})
                                                         state/default-journey-form)
                                                :mode (if phase-selected :edit :create)
                                                :status :idle
                                                :error nil})
         (assoc-in [:control :activation :items] steps)
         (assoc-in [:control :activation :selected] (or (:activation.step/id step-selected) (:activation.step/id (first steps))))
         (assoc-in [:control :activation :status] (if (seq steps) :ready :empty))
         (assoc-in [:control :activation :error] nil)
         (assoc-in [:control :activation :detail] {:form (or (and step-selected {:id (:activation.step/id step-selected)
                                                                                :title (:activation.step/title step-selected)
                                                                                :order (:activation.step/order step-selected)
                                                                                :phase (some-> step-selected :activation.step/phase :journey.phase/id)})
                                                            state/default-activation-form)
                                                    :mode (if step-selected :edit :create)
                                                    :status :idle
                                                    :error nil})
         (assoc-in [:control :business :detail]
                   {:form {:id (:business/id business)
                           :name (:business/name business)
                           :tagline (:business/tagline business)
                           :summary (:business/summary business)
                           :mission (:business/mission business)
                           :vision (:business/vision business)
                           :nav-label (:business/nav-label business)
                           :hero-headline (:business/hero-headline business)
                           :hero-strapline (:business/hero-strapline business)
                           :contact (some-> business :business/contact :contact/id)
                           :hero-stats (vec (map #(ref-id % :hero.stat/id) (:business/hero-stats business)))
                           :hero-flows (vec (map #(ref-id % :hero.flow/id) (:business/hero-flows business)))
                           :visible? (not= false (:business/visible? business))}
                    :mode (if business :edit :create)
                    :status :idle
                    :error nil})
         (assoc-in [:control :contact :detail]
                   {:form {:id (:contact/id contact)
                           :email (:contact/email contact)
                           :phone (:contact/phone contact)
                           :primary-cta-label (:contact/primary-cta-label contact)
                           :primary-cta-url (:contact/primary-cta-url contact)
                           :secondary-cta-label (:contact/secondary-cta-label contact)
                           :secondary-cta-url (:contact/secondary-cta-url contact)}
                    :mode (if contact :edit :create)
                    :status :idle
                    :error nil})
         (assoc-in [:control :personas :items] (vec (:personas data)))
         (assoc-in [:control :personas :status] (if (seq (:personas data)) :ready :empty))
         (assoc-in [:control :personas :selected] (let [sel (get-in db [:control :personas :selected])]
                                                    (or sel (:persona/id (first (:personas data))))))
         (assoc-in [:control :personas :detail] {:form (if-let [p (some (fn [p] (when (= (:persona/id p) (get-in db [:control :personas :selected])) p)) (:personas data))]
                                                       {:id (:persona/id p)
                                                        :title (:persona/title p)
                                                        :detail (:persona/detail p)
                                                        :type (:persona/type p)
                                                        :order (:persona/order p)
                                                        :visible? (:persona/visible? p)}
                                                       {:id nil :title "" :detail "" :type nil :order 0 :visible? true})
                                                 :mode (if (get-in db [:control :personas :selected]) :edit :create)
                                                 :status :idle
                                                 :error nil})
         (assoc-in [:control :support :items] (vec (:support-entries data)))
         (assoc-in [:control :support :status] (if (seq (:support-entries data)) :ready :empty))
         (assoc-in [:control :support :selected] (let [sel (get-in db [:control :support :selected])]
                                                   (or sel (:support.entry/id (first (:support-entries data))))))
         (assoc-in [:control :support :detail] {:form (if-let [s (some (fn [s] (when (= (:support.entry/id s) (get-in db [:control :support :selected])) s)) (:support-entries data))]
                                                       {:id (:support.entry/id s)
                                                        :role (:support.entry/role s)
                                                        :text (:support.entry/text s)
                                                        :order (:support.entry/order s)}
                                                       {:id nil :role :support/we :text "" :order 0})
                                                 :mode (if (get-in db [:control :support :selected]) :edit :create)
                                                 :status :idle
                                                 :error nil})))))

(rf/reg-event-db
 ::fetch-pages-success
 (fn [db [_ payload]]
   (let [pages (vec (:pages payload))
         current (get-in db [:control :pages :selected])
         selected (or (some->> pages (some #(when (= (:content.page/id %) current) %)) :content.page/id)
                      (:content.page/id (first pages)))
         selected-page (some #(when (= (:content.page/id %) selected) %) pages)
         current-detail (get-in db [:control :pages :detail])
         keep-create? (and (= :create (:mode current-detail))
                           (not= default-page-form (:form current-detail)))]
     (-> db
         (assoc-in [:control :pages :items] pages)
         (assoc-in [:control :pages :selected] selected)
         (assoc-in [:control :pages :status] (if (seq pages) :ready :empty))
         (assoc-in [:control :pages :error] nil)
         (assoc-in [:control :pages :detail]
                   (cond
                     keep-create? (-> current-detail
                                      (assoc :status :idle)
                                      (assoc :error nil))
                     selected-page (page-detail selected-page)
                     :else (page-detail nil)))))))

(rf/reg-event-db
 ::fetch-blocks-success
 (fn [db [_ payload]]
   (let [blocks (vec (:blocks payload))
         current (get-in db [:control :blocks :selected])
         selected (or (some->> blocks (some #(when (= (:content.block/id %) current) %)) :content.block/id)
                      (:content.block/id (first blocks)))
         selected-block (some #(when (= (:content.block/id %) selected) %) blocks)
         current-detail (get-in db [:control :blocks :detail])
         keep-create? (and (= :create (:mode current-detail))
                           (not= default-block-form (:form current-detail)))]
     (-> db
         (assoc-in [:control :blocks :items] blocks)
         (assoc-in [:control :blocks :selected] selected)
         (assoc-in [:control :blocks :status] (if (seq blocks) :ready :empty))
         (assoc-in [:control :blocks :error] nil)
         (assoc-in [:control :blocks :detail]
                   (cond
                     keep-create? (-> current-detail
                                      (assoc :status :idle)
                                      (assoc :error nil))
                     selected-block (block-detail selected-block)
                     :else (block-detail nil)))))))

(rf/reg-event-db
 ::fetch-tags-success-control
 (fn [db [_ payload]]
   (let [tags (vec (:tags payload))
         current (get-in db [:control :tags :selected])
         selected (or (some->> tags (some #(when (= (:content.tag/id %) current) %)) :content.tag/id)
                      (:content.tag/id (first tags)))
         selected-tag (some #(when (= (:content.tag/id %) selected) %) tags)
         current-detail (get-in db [:control :tags :detail])
         keep-create? (and (= :create (:mode current-detail))
                           (not= default-tag-form (:form current-detail)))]
     (-> db
         (assoc-in [:control :tags :items] tags)
         (assoc-in [:control :tags :selected] selected)
         (assoc-in [:control :tags :status] (if (seq tags) :ready :empty))
         (assoc-in [:control :tags :error] nil)
         (assoc-in [:control :tags :detail]
                   (cond
                     keep-create? (-> current-detail
                                      (assoc :status :idle)
                                      (assoc :error nil))
                     selected-tag (tag-detail selected-tag)
                     :else (tag-detail nil)))))))

(rf/reg-event-db
 ::content-failure
 (fn [db [_ section {:keys [status body]}]]
   (let [message (or (:error body)
                     (when (= status 401) "Session expired. Please sign in again.")
                     "Unable to load content.")]
     (-> (state/mark-error db [:control section] message)
         (cond-> (= status 401)
           (assoc :session nil
                  :route :login))))))

(rf/reg-event-fx
 ::set-control-tab
 (fn [{:keys [db]} [_ tab]]
   (let [already? (= (get-in db [:control :tab]) tab)
         v2-status (get-in db [:control :v2 :status])]
    {:db (assoc-in db [:control :tab] tab)
      :fx (cond-> []
            (and (= tab :v2) (not already?) (#{:idle :error} v2-status))
            (conj [::fx/http {:url "/api/content/v2"
                              :method "GET"
                              :on-success [::fetch-content-v2-success]
                              :on-error [::content-failure :v2]}]))})))

;; Journey/activation helpers
(defn journey-detail [phase]
  (if phase
    {:form {:id (:journey.phase/id phase)
            :title (:journey.phase/title phase)
            :kind (:journey.phase/kind phase)
            :order (:journey.phase/order phase)
            :bullets (str/join "\n" (:journey.phase/bullets phase))}
     :mode :edit
     :status :idle
     :error nil}
    {:form state/default-journey-form
     :mode :create
     :status :idle
     :error nil}))

(defn activation-detail [step]
  (if step
    {:form {:id (:activation.step/id step)
            :title (:activation.step/title step)
            :order (:activation.step/order step)
            :phase (some-> step :activation.step/phase :journey.phase/id)}
     :mode :edit
     :status :idle
     :error nil}
    {:form state/default-activation-form
     :mode :create
     :status :idle
     :error nil}))

(rf/reg-event-db
 ::select-journey
 (fn [db [_ phase-id]]
   (let [phase (some #(when (= (:journey.phase/id %) phase-id) %) (get-in db [:control :journey :items]))]
     (-> db
         (assoc-in [:control :journey :selected] phase-id)
         (assoc-in [:control :journey :detail] (journey-detail phase))))))

(rf/reg-event-db
 ::select-activation
 (fn [db [_ step-id]]
   (let [step (some #(when (= (:activation.step/id %) step-id) %) (get-in db [:control :activation :items]))]
     (-> db
         (assoc-in [:control :activation :selected] step-id)
         (assoc-in [:control :activation :detail] (activation-detail step))))))

(rf/reg-event-db
 ::set-journey-field
 (fn [db [_ field value]]
   (-> db
       (assoc-in [:control :journey :detail :form field] value)
       (assoc-in [:control :journey :detail :status] :idle)
       (assoc-in [:control :journey :detail :error] nil))))

(rf/reg-event-db
 ::set-activation-field
 (fn [db [_ field value]]
   (-> db
       (assoc-in [:control :activation :detail :form field] value)
       (assoc-in [:control :activation :detail :status] :idle)
       (assoc-in [:control :activation :detail :error] nil))))

(rf/reg-event-db
 ::set-persona-field
 (fn [db [_ field value]]
   (-> db
       (assoc-in [:control :personas :detail :form field] value)
       (assoc-in [:control :personas :detail :status] :idle)
       (assoc-in [:control :personas :detail :error] nil))))

(rf/reg-event-db
 ::set-support-field
 (fn [db [_ field value]]
   (-> db
       (assoc-in [:control :support :detail :form field] value)
       (assoc-in [:control :support :detail :status] :idle)
       (assoc-in [:control :support :detail :error] nil))))

(defn- parse-bullets [text]
  (->> (str/split (or text "") #"\n")
       (map #(str/trim %))
       (remove str/blank?)
       vec))

(rf/reg-event-fx
 ::save-journey
 (fn [{:keys [db]} _]
   (let [{:keys [form mode]} (get-in db [:control :journey :detail])
         bullets (parse-bullets (:bullets form))
         required-errors (cond-> []
                           (str/blank? (:title form)) (conj "Title is required"))
         body (-> form
                  (assoc :journey.phase/title (:title form))
                  (assoc :journey.phase/kind (or (:kind form) :phase/pre-incorporation))
                  (assoc :journey.phase/order (some-> (:order form) js/parseInt))
                  (assoc :journey.phase/bullets bullets)
                  (dissoc :title :kind :order :bullets))]
     (if (seq required-errors)
       {:db (-> db
                (assoc-in [:control :journey :detail :status] :error)
                (assoc-in [:control :journey :detail :error] (str/join ", " required-errors)))}
       {:db (-> db
                (assoc-in [:control :journey :detail :status] :saving)
                (assoc-in [:control :journey :detail :error] nil))
        :fx [[::fx/http {:url (if (:id form) (str "/api/content/journey-phases/" (:id form)) "/api/content/journey-phases")
                         :method (if (:id form) "PUT" "POST")
                         :params body
                         :on-success [::fetch-content-v2]
                         :on-error [::content-failure :journey]}]]}))))

(rf/reg-event-fx
 ::save-activation
 (fn [{:keys [db]} _]
   (let [{:keys [form mode]} (get-in db [:control :activation :detail])
         required-errors (cond-> []
                           (str/blank? (:title form)) (conj "Title is required"))
         body (-> form
                  (assoc :activation.step/title (:title form))
                  (assoc :activation.step/order (some-> (:order form) js/parseInt))
                  (assoc :activation.step/phase (when-let [p (:phase form)] [:journey.phase/id p]))
                  (dissoc :title :order :phase))]
     (if (seq required-errors)
       {:db (-> db
                (assoc-in [:control :activation :detail :status] :error)
                (assoc-in [:control :activation :detail :error] (str/join ", " required-errors)))}
       {:db (-> db
                (assoc-in [:control :activation :detail :status] :saving)
                (assoc-in [:control :activation :detail :error] nil))
        :fx [[::fx/http {:url (if (:id form) (str "/api/content/activation-steps/" (:id form)) "/api/content/activation-steps")
                         :method (if (:id form) "PUT" "POST")
                         :params body
                         :on-success [::fetch-content-v2]
                         :on-error [::content-failure :activation]}]]}))))

(rf/reg-event-fx
 ::delete-journey
 (fn [{:keys [db]} _]
   (let [id (get-in db [:control :journey :detail :form :id])]
     (if (nil? id)
       {:db (assoc-in db [:control :journey :detail :error] "Select a phase to delete")}
       {:db (assoc-in db [:control :journey :detail :status] :deleting)
        :fx [[::fx/http {:url (str "/api/content/journey-phases/" id)
                         :method "DELETE"
                         :on-success [::fetch-content-v2]
                         :on-error [::content-failure :journey]}]]}))))

(rf/reg-event-fx
 ::delete-activation
 (fn [{:keys [db]} _]
   (let [id (get-in db [:control :activation :detail :form :id])]
     (if (nil? id)
       {:db (assoc-in db [:control :activation :detail :error] "Select a step to delete")}
       {:db (assoc-in db [:control :activation :detail :status] :deleting)
        :fx [[::fx/http {:url (str "/api/content/activation-steps/" id)
                         :method "DELETE"
                         :on-success [::fetch-content-v2]
                         :on-error [::content-failure :activation]}]]}))))

(rf/reg-event-fx
 ::save-persona
 (fn [{:keys [db]} _]
   (let [{:keys [form]} (get-in db [:control :personas :detail])
         required-errors (cond-> []
                           (str/blank? (:title form)) (conj "Title is required")
                           (str/blank? (:detail form)) (conj "Detail is required"))
         body (-> form
                  (assoc :persona/title (:title form))
                  (assoc :persona/detail (:detail form))
                  (assoc :persona/order (some-> (:order form) js/parseInt))
                  (assoc :persona/type (:type form))
                  (assoc :persona/visible? (if (nil? (:visible? form)) true (:visible? form)))
                  (dissoc :title :detail :order :type :visible?))]
     (if (seq required-errors)
       {:db (-> db
                (assoc-in [:control :personas :detail :status] :error)
                (assoc-in [:control :personas :detail :error] (str/join ", " required-errors)))}
       {:db (-> db
                (assoc-in [:control :personas :detail :status] :saving)
                (assoc-in [:control :personas :detail :error] nil))
        :fx [[::fx/http {:url (if (:id form) (str "/api/content/personas/" (:id form)) "/api/content/personas")
                         :method (if (:id form) "PUT" "POST")
                         :params body
                         :on-success [::fetch-content-v2]
                         :on-error [::content-failure :personas]}]]}))))

(rf/reg-event-fx
 ::delete-persona
 (fn [{:keys [db]} _]
   (let [id (get-in db [:control :personas :detail :form :id])]
     (if (nil? id)
       {:db (assoc-in db [:control :personas :detail :error] "Select a persona to delete")}
       {:db (assoc-in db [:control :personas :detail :status] :deleting)
        :fx [[::fx/http {:url (str "/api/content/personas/" id)
                         :method "DELETE"
                         :on-success [::fetch-content-v2]
                         :on-error [::content-failure :personas]}]]}))))

(rf/reg-event-fx
 ::save-support-entry
 (fn [{:keys [db]} _]
   (let [{:keys [form]} (get-in db [:control :support :detail])
         required-errors (cond-> []
                           (str/blank? (:text form)) (conj "Text is required"))
         body (-> form
                  (assoc :support.entry/role (or (:role form) :support/we))
                  (assoc :support.entry/text (:text form))
                  (assoc :support.entry/order (some-> (:order form) js/parseInt))
                  (dissoc :role :text :order))]
     (if (seq required-errors)
       {:db (-> db
                (assoc-in [:control :support :detail :status] :error)
                (assoc-in [:control :support :detail :error] (str/join ", " required-errors)))}
       {:db (-> db
                (assoc-in [:control :support :detail :status] :saving)
                (assoc-in [:control :support :detail :error] nil))
        :fx [[::fx/http {:url (if (:id form) (str "/api/content/support-entries/" (:id form)) "/api/content/support-entries")
                         :method (if (:id form) "PUT" "POST")
                         :params body
                         :on-success [::fetch-content-v2]
                         :on-error [::content-failure :support]}]]}))))

(rf/reg-event-fx
 ::delete-support-entry
 (fn [{:keys [db]} _]
   (let [id (get-in db [:control :support :detail :form :id])]
     (if (nil? id)
       {:db (assoc-in db [:control :support :detail :error] "Select an entry to delete")}
       {:db (assoc-in db [:control :support :detail :status] :deleting)
        :fx [[::fx/http {:url (str "/api/content/support-entries/" id)
                         :method "DELETE"
                         :on-success [::fetch-content-v2]
                         :on-error [::content-failure :support]}]]}))))

;; Business/contact edits
(rf/reg-event-db
 ::set-business-field
 (fn [db [_ field value]]
   (-> db
       (assoc-in [:control :business :detail :form field] value)
       (assoc-in [:control :business :detail :status] :idle)
       (assoc-in [:control :business :detail :error] nil))))

(rf/reg-event-fx
 ::save-business
 (fn [{:keys [db]} _]
   (let [{:keys [form]} (get-in db [:control :business :detail])
         required-errors (cond-> []
                           (str/blank? (:name form)) (conj "Name is required"))
         body (-> form
                  (assoc :business/name (:name form))
                  (assoc :business/tagline (:tagline form))
                  (assoc :business/summary (:summary form))
                  (assoc :business/mission (:mission form))
                  (assoc :business/vision (:vision form))
                  (assoc :business/nav-label (:nav-label form))
                  (assoc :business/hero-headline (:hero-headline form))
                  (assoc :business/hero-strapline (:hero-strapline form))
                  (assoc :business/contact (:contact form))
                  (assoc :business/hero-stats (vec (:hero-stats form)))
                  (assoc :business/hero-flows (vec (:hero-flows form)))
                  (assoc :business/visible? (if (nil? (:visible? form)) true (:visible? form)))
                  (dissoc :name :tagline :summary :mission :vision :nav-label :hero-headline :hero-strapline :hero-stats :hero-flows :visible?))]
     (if (seq required-errors)
       {:db (-> db
                (assoc-in [:control :business :detail :status] :error)
                (assoc-in [:control :business :detail :error] (str/join ", " required-errors)))}
       {:db (-> db
                (assoc-in [:control :business :detail :status] :saving)
                (assoc-in [:control :business :detail :error] nil))
        :fx [[::fx/http {:url (if (:id form) (str "/api/content/businesses/" (:id form)) "/api/content/businesses")
                         :method (if (:id form) "PUT" "POST")
                         :params body
                         :on-success [::fetch-content-v2]
                         :on-error [::content-failure :business]}]]}))))

(rf/reg-event-db
 ::set-contact-field
 (fn [db [_ field value]]
   (-> db
       (assoc-in [:control :contact :detail :form field] value)
       (assoc-in [:control :contact :detail :status] :idle)
       (assoc-in [:control :contact :detail :error] nil))))

(rf/reg-event-fx
 ::save-contact
 (fn [{:keys [db]} _]
   (let [{:keys [form]} (get-in db [:control :contact :detail])
         required-errors (cond-> []
                           (str/blank? (:email form)) (conj "Email is required")
                           (str/blank? (:phone form)) (conj "Phone is required"))
         body (-> form
                  (assoc :contact/email (:email form))
                  (assoc :contact/phone (:phone form))
                  (assoc :contact/primary-cta-label (:primary-cta-label form))
                  (assoc :contact/primary-cta-url (:primary-cta-url form))
                  (assoc :contact/secondary-cta-label (:secondary-cta-label form))
                  (assoc :contact/secondary-cta-url (:secondary-cta-url form))
                  (dissoc :email :phone :primary-cta-label :primary-cta-url :secondary-cta-label :secondary-cta-url))]
     (if (seq required-errors)
       {:db (-> db
                (assoc-in [:control :contact :detail :status] :error)
                (assoc-in [:control :contact :detail :error] (str/join ", " required-errors)))}
       {:db (-> db
                (assoc-in [:control :contact :detail :status] :saving)
                (assoc-in [:control :contact :detail :error] nil))
        :fx [[::fx/http {:url (if (:id form) (str "/api/content/contacts/" (:id form)) "/api/content/contacts")
                         :method (if (:id form) "PUT" "POST")
                         :params body
                         :on-success [::fetch-content-v2]
                         :on-error [::content-failure :contact]}]]}))))

(rf/reg-event-db
 ::select-page
 (fn [db [_ page-id]]
   (let [page (some #(when (= (:content.page/id %) page-id) %)
                    (get-in db [:control :pages :items]))]
     (-> db
         (assoc-in [:control :pages :selected] page-id)
         (assoc-in [:control :pages :detail] (page-detail page))))))

(rf/reg-event-db
 ::select-block
 (fn [db [_ block-id]]
   (let [block (some #(when (= (:content.block/id %) block-id) %)
                     (get-in db [:control :blocks :items]))]
     (-> db
         (assoc-in [:control :blocks :selected] block-id)
         (assoc-in [:control :blocks :detail] (block-detail block))))))

(rf/reg-event-db
 ::select-tag
 (fn [db [_ tag-id]]
   (let [tag (some #(when (= (:content.tag/id %) tag-id) %)
                   (get-in db [:control :tags :items]))]
     (-> db
         (assoc-in [:control :tags :selected] tag-id)
          (assoc-in [:control :tags :detail] (tag-detail tag))))))

(rf/reg-event-db
 ::new-page
 (fn [db _]
   (assoc-in db [:control :pages :detail] (page-detail nil))))

(rf/reg-event-db
 ::new-block
 (fn [db _]
   (assoc-in db [:control :blocks :detail] (block-detail nil))))

(rf/reg-event-db
 ::new-tag
 (fn [db _]
   (assoc-in db [:control :tags :detail] (tag-detail nil))))

(rf/reg-event-db
 ::set-page-field
 (fn [db [_ field value]]
   (-> db
       (assoc-in [:control :pages :detail :form field] value)
       (assoc-in [:control :pages :detail :status] :idle)
       (assoc-in [:control :pages :detail :error] nil))))

(rf/reg-event-db
 ::toggle-page-tag
 (fn [db [_ tag-id]]
   (let [current (or (get-in db [:control :pages :detail :form :tags]) #{})
         updated (if (contains? current tag-id) (disj current tag-id) (conj current tag-id))]
     (-> db
         (assoc-in [:control :pages :detail :form :tags] updated)
         (assoc-in [:control :pages :detail :status] :idle)
         (assoc-in [:control :pages :detail :error] nil)))))

(rf/reg-event-db
 ::set-block-field
 (fn [db [_ field value]]
   (-> db
       (assoc-in [:control :blocks :detail :form field] value)
       (assoc-in [:control :blocks :detail :status] :idle)
       (assoc-in [:control :blocks :detail :error] nil))))

(rf/reg-event-db
 ::toggle-block-tag
 (fn [db [_ tag-id]]
   (let [current (or (get-in db [:control :blocks :detail :form :tags]) #{})
         updated (if (contains? current tag-id) (disj current tag-id) (conj current tag-id))]
     (-> db
         (assoc-in [:control :blocks :detail :form :tags] updated)
         (assoc-in [:control :blocks :detail :status] :idle)
         (assoc-in [:control :blocks :detail :error] nil)))))

(rf/reg-event-db
 ::set-tag-field
 (fn [db [_ field value]]
   (-> db
       (assoc-in [:control :tags :detail :form field] value)
       (assoc-in [:control :tags :detail :status] :idle)
       (assoc-in [:control :tags :detail :error] nil))))

(rf/reg-event-fx
 ::save-page
 (fn [{:keys [db]} _]
   (let [{:keys [form mode]} (get-in db [:control :pages :detail])
         id (:id form)
         validation (validate-page-form form)
         body (page-form->payload form)]
     (cond
       validation {:db (-> db
                           (assoc-in [:control :pages :detail :status] :error)
                           (assoc-in [:control :pages :detail :error] validation))}
       (= mode :create)
       {:db (-> db
                (assoc-in [:control :pages :detail :status] :saving)
                (assoc-in [:control :pages :detail :error] nil))
        ::fx/http {:url "/api/content/pages"
                   :method "POST"
                   :body body
                   :on-success [::page-save-success {:mode :create}]
                   :on-error [::content-operation-failure :pages]}}
       :else
       {:db (-> db
                (assoc-in [:control :pages :detail :status] :saving)
                (assoc-in [:control :pages :detail :error] nil))
        ::fx/http {:url (str "/api/content/pages/" id)
                   :method "PUT"
                   :body (assoc body :content.page/id id)
                   :on-success [::page-save-success {:mode :update}]
                   :on-error [::content-operation-failure :pages]}}))))

(rf/reg-event-fx
 ::delete-page
 (fn [{:keys [db]} _]
   (let [id (get-in db [:control :pages :detail :form :id])]
     (if (nil? id)
       {:db (assoc-in db [:control :pages :detail :error] "Select a page to delete")}
       {:db (-> db
                (assoc-in [:control :pages :detail :status] :deleting)
                (assoc-in [:control :pages :detail :error] nil))
        ::fx/http {:url (str "/api/content/pages/" id)
                   :method "DELETE"
                   :on-success [::page-delete-success]
                   :on-error [::content-operation-failure :pages]}}))))

(rf/reg-event-fx
 ::page-save-success
 (fn [{:keys [db]} [_ context payload]]
   (let [page (:page payload)
         pid (:content.page/id page)]
     {:db (-> db
              (assoc-in [:control :pages :detail :status] :success)
              (assoc-in [:control :pages :detail :error] nil)
              (cond-> page (assoc-in [:control :pages :detail] (page-detail page))
                      pid (assoc-in [:control :pages :selected] pid)))
      :dispatch-n [[::fetch-content]]})))

(rf/reg-event-fx
 ::page-delete-success
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc-in [:control :pages :detail] (page-detail nil))
            (assoc-in [:control :pages :selected] nil))
    :dispatch-n [[::fetch-content]]}))

(rf/reg-event-fx
 ::save-block
 (fn [{:keys [db]} _]
   (let [{:keys [form mode]} (get-in db [:control :blocks :detail])
         id (:id form)
         validation (validate-block-form form)
         body (block-form->payload form)]
     (cond
       validation {:db (-> db
                           (assoc-in [:control :blocks :detail :status] :error)
                           (assoc-in [:control :blocks :detail :error] validation))}
       (= mode :create)
       {:db (-> db
                (assoc-in [:control :blocks :detail :status] :saving)
                (assoc-in [:control :blocks :detail :error] nil))
        ::fx/http {:url "/api/content/blocks"
                   :method "POST"
                   :body body
                   :on-success [::block-save-success {:mode :create}]
                   :on-error [::content-operation-failure :blocks]}}
       :else
       {:db (-> db
                (assoc-in [:control :blocks :detail :status] :saving)
                (assoc-in [:control :blocks :detail :error] nil))
        ::fx/http {:url (str "/api/content/blocks/" id)
                   :method "PUT"
                   :body (assoc body :content.block/id id)
                   :on-success [::block-save-success {:mode :update}]
                   :on-error [::content-operation-failure :blocks]}}))))

(rf/reg-event-fx
 ::delete-block
 (fn [{:keys [db]} _]
   (let [id (get-in db [:control :blocks :detail :form :id])]
     (if (nil? id)
       {:db (assoc-in db [:control :blocks :detail :error] "Select a block to delete")}
       {:db (-> db
                (assoc-in [:control :blocks :detail :status] :deleting)
                (assoc-in [:control :blocks :detail :error] nil))
        ::fx/http {:url (str "/api/content/blocks/" id)
                   :method "DELETE"
                   :on-success [::block-delete-success]
                   :on-error [::content-operation-failure :blocks]}}))))

(rf/reg-event-fx
 ::block-save-success
 (fn [{:keys [db]} [_ _context payload]]
   (let [block (:block payload)
         bid (:content.block/id block)]
     {:db (-> db
              (assoc-in [:control :blocks :detail :status] :success)
              (assoc-in [:control :blocks :detail :error] nil)
              (cond-> block (assoc-in [:control :blocks :detail] (block-detail block))
                      bid (assoc-in [:control :blocks :selected] bid)))
      :dispatch-n [[::fetch-content]]})))

(rf/reg-event-fx
 ::block-delete-success
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc-in [:control :blocks :detail] (block-detail nil))
            (assoc-in [:control :blocks :selected] nil))
    :dispatch-n [[::fetch-content]]}))

(rf/reg-event-fx
 ::save-tag
 (fn [{:keys [db]} _]
   (let [{:keys [form mode]} (get-in db [:control :tags :detail])
         id (:id form)
         validation (validate-tag-form form)
         body (tag-form->payload form)]
     (cond
       validation {:db (-> db
                           (assoc-in [:control :tags :detail :status] :error)
                           (assoc-in [:control :tags :detail :error] validation))}
       (= mode :create)
       {:db (-> db
                (assoc-in [:control :tags :detail :status] :saving)
                (assoc-in [:control :tags :detail :error] nil))
        ::fx/http {:url "/api/content/tags"
                   :method "POST"
                   :body body
                   :on-success [::tag-save-success {:mode :create}]
                   :on-error [::content-operation-failure :tags]}}
       :else
       {:db (-> db
                (assoc-in [:control :tags :detail :status] :saving)
                (assoc-in [:control :tags :detail :error] nil))
        ::fx/http {:url (str "/api/content/tags/" id)
                   :method "PUT"
                   :body (assoc body :content.tag/id id)
                   :on-success [::tag-save-success {:mode :update}]
                   :on-error [::content-operation-failure :tags]}}))))

(rf/reg-event-fx
 ::delete-tag
 (fn [{:keys [db]} _]
   (let [id (get-in db [:control :tags :detail :form :id])]
     (if (nil? id)
       {:db (assoc-in db [:control :tags :detail :error] "Select a tag to delete")}
       {:db (-> db
                (assoc-in [:control :tags :detail :status] :deleting)
                (assoc-in [:control :tags :detail :error] nil))
        ::fx/http {:url (str "/api/content/tags/" id)
                   :method "DELETE"
                   :on-success [::tag-delete-success]
                   :on-error [::content-operation-failure :tags]}}))))

(rf/reg-event-fx
 ::tag-save-success
 (fn [{:keys [db]} [_ _context payload]]
   (let [tag (:tag payload)
         tid (:content.tag/id tag)]
     {:db (-> db
              (assoc-in [:control :tags :detail :status] :success)
              (assoc-in [:control :tags :detail :error] nil)
              (cond-> tag (assoc-in [:control :tags :detail] (tag-detail tag))
                      tid (assoc-in [:control :tags :selected] tid)))
      :dispatch-n [[::fetch-content]]})))

(rf/reg-event-fx
 ::tag-delete-success
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc-in [:control :tags :detail] (tag-detail nil))
            (assoc-in [:control :tags :selected] nil))
    :dispatch-n [[::fetch-content]]}))

(rf/reg-event-db
 ::content-operation-failure
 (fn [db [_ section {:keys [status body]}]]
   (let [message (or (:error body)
                     (when (= status 401) "Session expired. Please sign in again.")
                     "Unable to save content.")]
     (-> db
         (assoc-in [:control section :detail :status] :error)
         (assoc-in [:control section :detail :error] message)
         (cond-> (= status 401)
           (assoc :session nil
                  :route :login))))))

;; Land registry events
(rf/reg-event-fx
 ::fetch-land
 (fn [{:keys [db]} _]
   (let [filters (get-in db [:land :filters])]
     {:db (state/mark-loading db [:land])
      :dispatch-n [[::fetch-land-stats]
                   [::fetch-land-people filters]
                   [::fetch-land-parcels filters]]})))

(rf/reg-event-fx
 ::fetch-land-stats
 (fn [{:keys [db]} _]
   {:db (state/mark-loading db [:land])
    ::fx/http {:url "/api/land/stats"
               :method "GET"
               :on-success [::land-stats-success]
               :on-error [::land-failure]}}))

(rf/reg-event-fx
 ::fetch-land-people
 (fn [{:keys [db]} [_ {:keys [people-search sort people-page people-page-size]}]]
   (let [params (cond-> []
                  (seq people-search) (conj ["q" people-search])
                  sort (conj ["sort" (name sort)])
                  true (conj ["limit" (or people-page-size 25)])
                  true (conj ["offset" (* (max 0 (dec (or people-page 1)))
                                          (or people-page-size 25))]))
         qs (when (seq params)
              (let [sp (js/URLSearchParams.)]
                (doseq [[k v] params] (.append sp k v))
                (.toString sp)))]
     {:db (state/mark-loading db [:land])
      ::fx/http {:url (str "/api/land/people" (when qs (str "?" qs)))
                 :method "GET"
                 :on-success [::land-people-success]
                 :on-error [::land-failure]}})))

(rf/reg-event-fx
 ::fetch-land-parcels
 (fn [{:keys [db]} [_ {:keys [parcel-number completeness sort parcels-page parcels-page-size]}]]
   (let [params (cond-> []
                  (seq parcel-number) (conj ["parcel-number" parcel-number])
                  completeness (conj ["completeness" (name completeness)])
                  sort (conj ["sort" (name sort)])
                  true (conj ["limit" (or parcels-page-size 25)])
                  true (conj ["offset" (* (max 0 (dec (or parcels-page 1)))
                                          (or parcels-page-size 25))]))
         qs (when (seq params)
              (let [sp (js/URLSearchParams.)]
                (doseq [[k v] params] (.append sp k v))
                (.toString sp)))]
     {:db (state/mark-loading db [:land])
      ::fx/http {:url (str "/api/land/parcels" (when qs (str "?" qs)))
                 :method "GET"
                 :on-success [::land-parcels-success]
                 :on-error [::land-failure]}})))

(rf/reg-event-fx
 ::select-person
 (fn [{:keys [db]} [_ person-id]]
   {:db (assoc-in db [:land :selected :person] person-id)
    ::fx/http {:url (str "/api/land/people/" person-id)
               :method "GET"
               :on-success [::land-person-detail-success]
               :on-error [::land-failure]}}))

(rf/reg-event-fx
 ::select-parcel
 (fn [{:keys [db]} [_ parcel-id]]
   {:db (assoc-in db [:land :selected :parcel] parcel-id)
    ::fx/http {:url (str "/api/land/parcels/" parcel-id)
               :method "GET"
               :on-success [::land-parcel-detail-success]
               :on-error [::land-failure]}}))

(rf/reg-event-db
 ::land-people-success
 (fn [db [_ payload]]
   (let [{:keys [pagination]} payload
         limit (max 1 (or (:limit pagination) (get-in db [:land :filters :people-page-size]) 25))
         offset (max 0 (or (:offset pagination) 0))
         total (max (count (:people payload)) (or (:total pagination) (count (:people payload))))
         page (max 1 (or (:page pagination) (inc (quot offset limit))))
         pages (max 1 (int (Math/ceil (/ (max total 1) (double limit)))))]
     (-> db
         (assoc-in [:land :people] (:people payload))
         (assoc-in [:land :status] :ready)
         (assoc-in [:land :error] nil)
         (assoc-in [:land :filters :people-page] page)
         (assoc-in [:land :filters :people-page-size] limit)
         (assoc-in [:land :pagination :people] {:limit limit
                                                :offset offset
                                                :total total
                                                :page page
                                                :pages pages})))))

(rf/reg-event-db
 ::land-parcels-success
 (fn [db [_ payload]]
   (let [{:keys [pagination]} payload
         limit (max 1 (or (:limit pagination) (get-in db [:land :filters :parcels-page-size]) 25))
         offset (max 0 (or (:offset pagination) 0))
         total (max (count (:parcels payload)) (or (:total pagination) (count (:parcels payload))))
         page (max 1 (or (:page pagination) (inc (quot offset limit))))
         pages (max 1 (int (Math/ceil (/ (max total 1) (double limit)))))]
     (-> db
         (assoc-in [:land :parcels] (:parcels payload))
         (assoc-in [:land :status] :ready)
         (assoc-in [:land :error] nil)
         (assoc-in [:land :filters :parcels-page] page)
         (assoc-in [:land :filters :parcels-page-size] limit)
         (assoc-in [:land :pagination :parcels] {:limit limit
                                                 :offset offset
                                                 :total total
                                                 :page page
                                                 :pages pages})))))

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
     (-> (state/mark-error db [:land] message)
         (cond-> (= status 401)
           (assoc :session nil
                  :route :login))))))

;; Betting CLV events
(rf/reg-event-fx
 ::fetch-betting-events
 (fn [{:keys [db]} _]
   (let [day (get-in db [:betting :day] 0)
         qs (str "?day=" day)]
     {:db (-> db
              (assoc-in [:betting :status] :loading)
              (assoc-in [:betting :error] nil))
      ::fx/http {:url (str "/api/betting/events" qs)
                 :method "GET"
                 :on-success [::betting-events-success]
                 :on-error [::betting-events-failure]}})))

(rf/reg-event-fx
 ::betting-events-success
 (fn [{:keys [db]} [_ payload]]
   (let [matches (vec (:matches payload))
         groups (vec (:groups payload))
         selected-id (get-in db [:betting :selected :event-id])
         selected (some #(when (= (:event-id %) selected-id) %) matches)
         next-selected (or selected (first matches))
         db' (-> db
                 (assoc-in [:betting :matches] matches)
                 (assoc-in [:betting :groups] groups)
                 (assoc-in [:betting :cached?] (boolean (:cached? payload)))
                 (assoc-in [:betting :fetched-at-ms] (:fetched-at-ms payload))
                 (assoc-in [:betting :status] (if (seq matches) :ready :empty))
                 (assoc-in [:betting :error] nil))]
     {:db db'
      :dispatch-n (cond-> []
                    next-selected (conj [::select-betting-match next-selected]))})))

(rf/reg-event-db
 ::betting-events-failure
 (fn [db [_ {:keys [status body]}]]
   (let [message (or (:error body)
                     (when (= status 401) "Session expired. Please sign in again.")
                     "Unable to load betting events.")]
     (-> db
         (assoc-in [:betting :status] :error)
         (assoc-in [:betting :error] message)
         (cond-> (= status 401)
           (assoc :session nil
                  :route :login))))))

(rf/reg-event-fx
 ::select-betting-match
 (fn [{:keys [db]} [_ match]]
   (let [event-id (:event-id match)
         form (-> default-betting-form
                  (assoc :selection (:home match)))]
     {:db (-> db
              (assoc-in [:betting :selected] match)
              (assoc-in [:betting :form] form)
              (assoc-in [:betting :odds] default-betting-odds)
              (assoc-in [:betting :bets] default-betting-bets))
      :dispatch-n (cond-> []
                    event-id (conj [::fetch-betting-odds event-id false]
                                   [::fetch-betting-bets event-id]))})))

(rf/reg-event-fx
 ::fetch-betting-odds
 (fn [{:keys [db]} [_ event-id refresh?]]
   (let [eid (or event-id (get-in db [:betting :selected :event-id]))
         qs (when refresh? "?refresh=true")]
     (if-not eid
       {:db db}
       {:db (-> db
                (assoc-in [:betting :odds :status] :loading)
                (assoc-in [:betting :odds :error] nil))
        ::fx/http {:url (str "/api/betting/events/" eid "/odds" (or qs ""))
                   :method "GET"
                   :on-success [::betting-odds-success]
                   :on-error [::betting-odds-failure]}}))))

(rf/reg-event-db
 ::betting-odds-success
 (fn [db [_ payload]]
   (-> db
       (assoc-in [:betting :odds :summary] (:odds payload))
       (assoc-in [:betting :odds :captured-at] (:captured-at payload))
       (assoc-in [:betting :odds :status] (if (:odds payload) :ready :empty))
       (assoc-in [:betting :odds :error] nil))))

(rf/reg-event-db
 ::betting-odds-failure
 (fn [db [_ {:keys [status body]}]]
   (let [message (or (:error body)
                     (when (= status 401) "Session expired. Please sign in again.")
                     "Unable to load odds.")]
     (-> db
         (assoc-in [:betting :odds :status] :error)
         (assoc-in [:betting :odds :error] message)
         (cond-> (= status 401)
           (assoc :session nil
                  :route :login))))))

(rf/reg-event-fx
 ::capture-betting-close
 (fn [{:keys [db]} _]
   (let [eid (get-in db [:betting :selected :event-id])]
     (if-not eid
       {:db db}
       {:db (-> db
                (assoc-in [:betting :odds :status] :loading)
                (assoc-in [:betting :odds :error] nil))
        ::fx/http {:url (str "/api/betting/events/" eid "/close")
                   :method "POST"
                   :on-success [::betting-close-success]
                   :on-error [::betting-odds-failure]}}))))

(rf/reg-event-fx
 ::betting-close-success
 (fn [{:keys [db]} [_ payload]]
   (let [eid (get-in db [:betting :selected :event-id])]
     {:db (-> db
              (assoc-in [:betting :odds :summary] (:odds payload))
              (assoc-in [:betting :odds :captured-at] (:close-captured-at payload))
              (assoc-in [:betting :odds :status] (if (:odds payload) :ready :empty))
              (assoc-in [:betting :odds :error] nil))
      :dispatch-n (cond-> []
                    eid (conj [::fetch-betting-bets eid]))})))

(rf/reg-event-db
 ::update-betting-form
 (fn [db [_ k v]]
   (-> db
       (assoc-in [:betting :form k] v)
       (assoc-in [:betting :form :error] nil)
       (assoc-in [:betting :form :status] :idle))))

(rf/reg-event-fx
 ::submit-betting-bet
 (fn [{:keys [db]} _]
   (let [form (get-in db [:betting :form])
         event-id (get-in db [:betting :selected :event-id])
         selection (:selection form)
         summary (get-in db [:betting :odds :summary])]
     (cond
       (nil? event-id)
       {:db (assoc-in db [:betting :form :error] "Select a match first.")}

       (str/blank? (str selection))
       {:db (assoc-in db [:betting :form :error] "Select a side to log.")}

       (nil? summary)
       {:db (assoc-in db [:betting :form :error] "Refresh odds before logging a bet.")}

       :else
       {:db (-> db
                (assoc-in [:betting :form :status] :loading)
                (assoc-in [:betting :form :error] nil))
        ::fx/http {:url "/api/betting/bets"
                   :method "POST"
                   :body {:event-id event-id
                          :market-key (:market-key form)
                          :selection selection}
                   :on-success [::betting-bet-success]
                   :on-error [::betting-bet-failure]}}))))

(rf/reg-event-fx
 ::betting-bet-success
 (fn [{:keys [db]} _]
   (let [eid (get-in db [:betting :selected :event-id])
         selection (get-in db [:betting :form :selection])]
     {:db (-> db
              (assoc-in [:betting :form] (assoc default-betting-form :selection selection))
              (assoc-in [:betting :form :status] :success))
      :dispatch-n (cond-> []
                    eid (conj [::fetch-betting-bets eid]))})))

(rf/reg-event-db
 ::betting-bet-failure
 (fn [db [_ {:keys [status body]}]]
   (let [message (or (:error body)
                     (when (= status 401) "Session expired. Please sign in again.")
                     "Unable to log bet.")]
     (-> db
         (assoc-in [:betting :form :status] :error)
         (assoc-in [:betting :form :error] message)
         (cond-> (= status 401)
           (assoc :session nil
                  :route :login))))))

(rf/reg-event-fx
 ::fetch-betting-bets
 (fn [{:keys [db]} [_ event-id]]
   (let [eid (or event-id (get-in db [:betting :selected :event-id]))
         qs (when eid (str "?event-id=" eid))]
     {:db (-> db
              (assoc-in [:betting :bets :status] :loading)
              (assoc-in [:betting :bets :error] nil))
      ::fx/http {:url (str "/api/betting/bets" (or qs ""))
                 :method "GET"
                 :on-success [::betting-bets-success]
                 :on-error [::betting-bets-failure]}})))

(rf/reg-event-db
 ::betting-bets-success
 (fn [db [_ payload]]
   (-> db
       (assoc-in [:betting :bets :items] (vec (:bets payload)))
       (assoc-in [:betting :bets :scoreboard] (:scoreboard payload))
       (assoc-in [:betting :bets :status] (if (seq (:bets payload)) :ready :empty))
       (assoc-in [:betting :bets :error] nil))))

(rf/reg-event-db
 ::betting-bets-failure
 (fn [db [_ {:keys [status body]}]]
   (let [message (or (:error body)
                     (when (= status 401) "Session expired. Please sign in again.")
                     "Unable to load bet log.")]
     (-> db
         (assoc-in [:betting :bets :status] :error)
         (assoc-in [:betting :bets :error] message)
         (cond-> (= status 401)
           (assoc :session nil
                  :route :login))))))

(rf/reg-event-fx
 ::fetch-terminal-sessions
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc-in [:terminal :status] :loading)
            (assoc-in [:terminal :error] nil))
    ::fx/http {:url "/api/terminal/sessions"
               :method "GET"
               :on-success [::terminal-sessions-success]
               :on-error [::terminal-sessions-failure]}}))

(rf/reg-event-db
 ::terminal-sessions-success
 (fn [db [_ payload]]
   (-> db
       (assoc-in [:terminal :sessions] (vec (:sessions payload)))
       (assoc-in [:terminal :status] (if (seq (:sessions payload)) :ready :empty))
       (assoc-in [:terminal :error] nil))))

(rf/reg-event-db
 ::terminal-sessions-failure
 (fn [db [_ {:keys [status body]}]]
   (let [message (or (:error body)
                     (when (= status 401) "Session expired. Please sign in again.")
                     "Unable to load sessions.")]
     (-> db
         (assoc-in [:terminal :status] :error)
         (assoc-in [:terminal :error] message)
         (cond-> (= status 401)
           (assoc :session nil
                  :route :login))))))

(rf/reg-event-fx
 ::terminal-create-session
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc-in [:terminal :status] :loading)
            (assoc-in [:terminal :error] nil))
    ::fx/http {:url "/api/terminal/sessions"
               :method "POST"
               :body {}
               :on-success [::terminal-session-created]
               :on-error [::terminal-sessions-failure]}}))

(rf/reg-event-fx
 ::terminal-session-created
 (fn [{:keys [db]} [_ payload]]
   (let [session (:session payload)]
     {:db (-> db
              (assoc-in [:terminal :selected] session)
              (assoc-in [:terminal :output] "")
              (assoc-in [:terminal :cursor] 0)
              (assoc-in [:terminal :input] "")
              (assoc-in [:terminal :status] :ready))
      :dispatch-n [[::fetch-terminal-sessions]
                   [::terminal-start-poll]]})))

(rf/reg-event-fx
 ::terminal-select-session
 (fn [{:keys [db]} [_ session]]
   {:db (-> db
            (assoc-in [:terminal :selected] session)
            (assoc-in [:terminal :output] "")
            (assoc-in [:terminal :cursor] 0)
            (assoc-in [:terminal :input] "")
            (assoc-in [:terminal :error] nil))
    :dispatch-n [[::terminal-start-poll]
                 [::terminal-fetch-session (:id session)]]}))

(rf/reg-event-fx
 ::terminal-fetch-session
 (fn [{:keys [db]} [_ session-id]]
   {:db db
    ::fx/http {:url (str "/api/terminal/sessions/" session-id)
               :method "GET"
               :on-success [::terminal-session-detail]
               :on-error [::terminal-session-failure]}}))

(rf/reg-event-db
 ::terminal-session-detail
 (fn [db [_ payload]]
   (if-let [session (:session payload)]
     (assoc-in db [:terminal :selected] session)
     db)))

(rf/reg-event-db
 ::terminal-session-failure
 (fn [db [_ {:keys [body]}]]
   (assoc-in db [:terminal :error] (or (:error body) "Unable to load session."))))

(rf/reg-event-db
 ::terminal-back
 (fn [db _]
   (-> db
       (assoc-in [:terminal :selected] nil)
       (assoc-in [:terminal :polling?] false)
       (assoc-in [:terminal :output] "")
       (assoc-in [:terminal :cursor] 0))))

(rf/reg-event-db
 ::terminal-update-input
 (fn [db [_ value]]
   (assoc-in db [:terminal :input] value)))

(rf/reg-event-fx
 ::terminal-send-input
 (fn [{:keys [db]} _]
   (let [session-id (get-in db [:terminal :selected :id])
         text (str/trim (get-in db [:terminal :input] ""))]
     (cond
       (nil? session-id) {:db db}
       (str/blank? text) {:db db
                          :dispatch [::terminal-send-keys ["Enter"]]}
       :else
       {:db (-> db
                (assoc-in [:terminal :input] "")
                (assoc-in [:terminal :sending?] true))
        ::fx/http {:url (str "/api/terminal/sessions/" session-id "/input")
                   :method "POST"
                   :body {:text text}
                   :on-success [::terminal-input-sent]
                   :on-error [::terminal-input-failed]}}))))

(rf/reg-event-db
 ::terminal-input-sent
 (fn [db _]
   (assoc-in db [:terminal :sending?] false)))

(rf/reg-event-db
 ::terminal-input-failed
 (fn [db [_ {:keys [body]}]]
   (-> db
       (assoc-in [:terminal :sending?] false)
       (assoc-in [:terminal :error] (or (:error body) "Unable to send input.")))))

(rf/reg-event-fx
 ::terminal-send-keys
 (fn [{:keys [db]} [_ keys]]
   (let [session-id (get-in db [:terminal :selected :id])
         keys (->> keys (map str) (remove str/blank?) vec)]
     (if (and session-id (seq keys))
       {:db (assoc-in db [:terminal :sending?] true)
        ::fx/http {:url (str "/api/terminal/sessions/" session-id "/keys")
                   :method "POST"
                   :body {:keys keys}
                   :on-success [::terminal-keys-sent]
                   :on-error [::terminal-keys-failed]}}
       {:db db}))))

(rf/reg-event-db
 ::terminal-keys-sent
 (fn [db _]
   (assoc-in db [:terminal :sending?] false)))

(rf/reg-event-db
 ::terminal-keys-failed
 (fn [db [_ {:keys [body]}]]
   (-> db
       (assoc-in [:terminal :sending?] false)
       (assoc-in [:terminal :error] (or (:error body) "Unable to send keys.")))))

(rf/reg-event-fx
 ::terminal-start-poll
 (fn [{:keys [db]} _]
   (let [session-id (get-in db [:terminal :selected :id])]
     (if session-id
       {:db (assoc-in db [:terminal :polling?] true)
        :dispatch [::terminal-fetch-output]}
       {:db db}))))

(rf/reg-event-fx
 ::terminal-fetch-output
 (fn [{:keys [db]} _]
   (let [session-id (get-in db [:terminal :selected :id])
         cursor (get-in db [:terminal :cursor] 0)]
     (if (and session-id (get-in db [:terminal :polling?]))
       {:db db
        ::fx/http {:url (str "/api/terminal/sessions/" session-id "/output?cursor=" cursor)
                   :method "GET"
                   :on-success [::terminal-output-success]
                   :on-error [::terminal-output-failure]}}
       {:db db}))))

(rf/reg-event-fx
 ::terminal-output-success
 (fn [{:keys [db]} [_ payload]]
   (let [chunk (or (:chunk payload) "")
         cursor (:cursor payload)
         replace? (= :replace (:mode payload))
         next-db (cond-> db
                   replace? (assoc-in [:terminal :output] chunk)
                   (and (not replace?) (seq chunk)) (update-in [:terminal :output] str chunk)
                   true (assoc-in [:terminal :cursor] cursor)
                   true (assoc-in [:terminal :error] nil))
         polling? (get-in next-db [:terminal :polling?])]
     (cond-> {:db next-db}
       polling? (assoc ::fx/dispatch-later {:ms 1000
                                            :dispatch [::terminal-fetch-output]})))))

(rf/reg-event-fx
 ::terminal-output-failure
 (fn [{:keys [db]} [_ {:keys [body]}]]
   (let [next-db (assoc-in db [:terminal :error] (or (:error body) "Unable to fetch output."))
         polling? (get-in next-db [:terminal :polling?])]
     (cond-> {:db next-db}
       polling? (assoc ::fx/dispatch-later {:ms 2000
                                            :dispatch [::terminal-fetch-output]})))))

(rf/reg-event-fx
 ::terminal-complete-session
 (fn [{:keys [db]} _]
   (let [session-id (get-in db [:terminal :selected :id])]
     (if session-id
       {:db (assoc-in db [:terminal :status] :loading)
        ::fx/http {:url (str "/api/terminal/sessions/" session-id "/complete")
                   :method "POST"
                   :body {}
                   :on-success [::terminal-complete-success]
                   :on-error [::terminal-sessions-failure]}}
       {:db db}))))

(rf/reg-event-fx
 ::terminal-complete-success
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc-in [:terminal :selected] nil)
            (assoc-in [:terminal :polling?] false)
            (assoc-in [:terminal :output] "")
            (assoc-in [:terminal :cursor] 0))
    :dispatch [::fetch-terminal-sessions]}))

(rf/reg-event-db
 ::land-update-filter
 (fn [db [_ k v]]
   (let [updated (assoc-in db [:land :filters k] v)]
     (cond-> updated
       (#{:people-search :sort} k) (assoc-in [:land :filters :people-page] 1)
       (#{:parcel-number :completeness :sort} k) (assoc-in [:land :filters :parcels-page] 1)))))

(rf/reg-event-fx
 ::set-land-people-page
 (fn [{:keys [db]} [_ page]]
   (let [pagination (get-in db [:land :pagination :people])
         pages (max 1 (or (:pages pagination) 1))
         page' (-> page (or 1) int (max 1) (min pages))
         filters (-> (get-in db [:land :filters])
                     (assoc :people-page page'))]
     {:db (assoc-in db [:land :filters :people-page] page')
      :dispatch [::fetch-land-people filters]})))

(rf/reg-event-fx
 ::set-land-people-page-size
 (fn [{:keys [db]} [_ size]]
   (let [size' (max 1 (or size 25))
         filters (-> (get-in db [:land :filters])
                     (assoc :people-page-size size'
                            :people-page 1))]
     {:db (-> db
              (assoc-in [:land :filters :people-page-size] size')
              (assoc-in [:land :filters :people-page] 1))
      :dispatch [::fetch-land-people filters]})))

(rf/reg-event-fx
 ::set-land-parcels-page
 (fn [{:keys [db]} [_ page]]
   (let [pagination (get-in db [:land :pagination :parcels])
         pages (max 1 (or (:pages pagination) 1))
         page' (-> page (or 1) int (max 1) (min pages))
         filters (-> (get-in db [:land :filters])
                     (assoc :parcels-page page'))]
     {:db (assoc-in db [:land :filters :parcels-page] page')
      :dispatch [::fetch-land-parcels filters]})))

(rf/reg-event-fx
 ::set-land-parcels-page-size
 (fn [{:keys [db]} [_ size]]
   (let [size' (max 1 (or size 25))
         filters (-> (get-in db [:land :filters])
                     (assoc :parcels-page-size size'
                            :parcels-page 1))]
     {:db (-> db
              (assoc-in [:land :filters :parcels-page-size] size')
              (assoc-in [:land :filters :parcels-page] 1))
      :dispatch [::fetch-land-parcels filters]})))

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
        ::fx/http {:url "/api/tags"
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
        ::fx/http {:url (str "/api/tags/" tag-id)
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
      ::fx/http {:url (str "/api/tags/" tag-id)
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
     (-> (state/mark-error db [:tags] message)
         (cond-> (= status 401)
           (assoc :session nil
                  :route :login))))))

(def ->keyword-or-nil util/->keyword-or-nil)

(rf/reg-event-fx
 ::update-filter
 (fn [{:keys [db]} [_ field value]]
   (let [updated (-> db
                     (assoc-in [:tasks :filters field] value)
                     (assoc-in [:tasks :filters :page] 1))]
     {:db updated
      :dispatch [::fetch-tasks]})))

(rf/reg-event-fx
 ::set-sort
 (fn [{:keys [db]} [_ sort-key]]
   (let [default-order (if (= sort-key :due) :asc :desc)
         db' (-> db
                 (assoc-in [:tasks :filters :sort] sort-key)
                 (assoc-in [:tasks :filters :order] default-order)
                 (assoc-in [:tasks :filters :page] 1))]
     {:db db'
      :dispatch [::fetch-tasks]})))

(rf/reg-event-fx
 ::toggle-order
 (fn [{:keys [db]} _]
   (let [current (get-in db [:tasks :filters :order] :desc)
         next (if (= current :asc) :desc :asc)]
     {:db (-> db
              (assoc-in [:tasks :filters :order] next)
              (assoc-in [:tasks :filters :page] 1))
      :dispatch [::fetch-tasks]})))

(rf/reg-event-fx
 ::toggle-archived
 (fn [{:keys [db]} _]
   (let [current (get-in db [:tasks :filters :archived] false)
         next (case current
                false :all
                :all true
                false)]
     {:db (-> db
              (assoc-in [:tasks :filters :archived] next)
              (assoc-in [:tasks :filters :page] 1))
      :dispatch [::fetch-tasks]})))

(rf/reg-event-fx
 ::clear-filters
 (fn [{:keys [db]} _]
   {:db (assoc-in db [:tasks :filters] default-task-filters)
    :dispatch [::fetch-tasks]}))

(rf/reg-event-fx
 ::select-task
 (fn [{:keys [db]} [_ task-id]]
   (let [task (some #(when (= (:task/id %) task-id) %) (get-in db [:tasks :items]))
         assignees (or (get-in db [:tasks :assignees]) fallback-assignees)]
     {:db (-> db
              (assoc-in [:tasks :selected] task-id)
              (assoc-in [:tasks :detail] (if task
                                           (detail-from-task task)
                                           (closed-detail assignees (:session db)))))
      ::set-body-scroll-lock false})))

(rf/reg-event-fx
 ::set-task-page
 (fn [{:keys [db]} [_ page]]
   (let [pagination (get-in db [:tasks :pagination])
         pages (max 1 (or (:pages pagination) 1))
         page' (-> page (or 1) int (max 1) (min pages))]
     {:db (assoc-in db [:tasks :filters :page] page')
      :dispatch [::fetch-tasks]})))

(rf/reg-event-fx
 ::set-task-page-size
 (fn [{:keys [db]} [_ size]]
   (let [size' (max 1 (or size 25))]
     {:db (-> db
              (assoc-in [:tasks :filters :page-size] size')
              (assoc-in [:tasks :filters :page] 1))
      :dispatch [::fetch-tasks]})))

(defn- validate-task-form
  [{:keys [title description status priority assignee pending-reason]}]
  (cond
    (str/blank? title) "Title is required"
    (str/blank? description) "Description is required"
    (nil? status) "Status is required"
    (and (= status :pending) (str/blank? pending-reason)) "Pending status requires a pending reason"
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
        status-op (when (and task-id (not= (:status form) (:task/status current-task)))
                    (let [pending? (= (:status form) :pending)
                          reason (some-> (:pending-reason form) str/trim)
                          status-body (cond-> {:task/status (:status form)}
                                        (and pending? (not (str/blank? reason))) (assoc :pending-reason reason))]
                      {:url (str "/api/tasks/" task-id "/status")
                       :method "POST"
                       :body status-body}))
        general-updates (cond-> {}
                          (not= (:title form) (:task/title current-task)) (assoc :task/title (:title form))
                          (not= (:description form) (:task/description current-task)) (assoc :task/description (:description form))
                          (not= (:priority form) (:task/priority current-task)) (assoc :task/priority (:priority form))
                          (not= tags current-tags) (assoc :task/tags (vec tags))
                          (not= (:pending-reason form) (:task/pending-reason current-task)) (assoc :task/pending-reason (:pending-reason form))
                          (not= (:extended? form) (boolean (:task/extended? current-task))) (assoc :task/extended? (:extended? form)))]
    (cond-> []
      (and task-id (seq general-updates)) (conj {:url (str "/api/tasks/" task-id)
                                                 :method "PUT"
                                                 :body general-updates})
      status-op (conj status-op)
      (and task-id (not= (:assignee form) (get-in current-task [:task/assignee :user/id]))) (conj {:url (str "/api/tasks/" task-id "/assignee")
                                                                                                   :method "POST"
                                                                                                   :body {:task/assignee (:assignee form)}})
      (and task-id (not= (:due-date form) current-due)) (conj {:url (str "/api/tasks/" task-id "/due-date")
                                                               :method "POST"
                                                               :body {:task/due-date due-iso}})
      (and task-id (not= (:archived? form) (boolean (:task/archived? current-task)))) (conj {:url (str "/api/tasks/" task-id "/archive")
                                                                                            :method "POST"
                                                                                            :body {:task/archived? (:archived? form)}}))))

(rf/reg-event-fx
 ::start-new-task
 (fn [{:keys [db]} _]
   (let [assignees (or (get-in db [:tasks :assignees]) fallback-assignees)]
     {:db (-> db
              (assoc-in [:tasks :selected] nil)
              (assoc-in [:tasks :detail] (blank-detail assignees (:session db))))
      ::set-body-scroll-lock false})))

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

(rf/reg-event-fx
 ::close-detail
 (fn [{:keys [db]} _]
   (let [assignees (or (get-in db [:tasks :assignees]) fallback-assignees)]
     {:db (-> db
              (assoc-in [:tasks :selected] nil)
              (assoc-in [:tasks :detail] (closed-detail assignees (:session db))))
      ::set-body-scroll-lock false})))

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
       (let [reason (some-> (:pending-reason form) str/trim)
             pending? (= (:status form) :pending)
             body (cond-> {:task/title (:title form)
                           :task/description (:description form)
                           :task/status (:status form)
                           :task/priority (:priority form)
                           :task/assignee (:assignee form)
                           :task/tags (vec tags)
                           :task/due-date due-iso
                           :task/archived? (boolean (:archived? form))
                           :task/extended? (boolean (:extended? form))}
                    (and pending? (not (str/blank? reason))) (assoc :pending-reason reason))]
         {:db (-> db
                  (assoc-in [:tasks :detail :status] :saving)
                  (assoc-in [:tasks :detail :error] nil))
          ::fx/http {:url "/api/tasks"
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
            ::fx/http (assoc (first ops)
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
      ::fx/http {:url (str "/api/tasks/" task-id)
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
       ::fx/http (assoc next-op
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
(rf/reg-sub ::control (fn [db _] (:control db)))
(rf/reg-sub ::theme (fn [db _] (:theme db)))
(rf/reg-sub ::home (fn [db _] (:home db)))
(rf/reg-sub ::land (fn [db _] (:land db)))
(rf/reg-sub ::betting (fn [db _] (:betting db)))
(rf/reg-sub ::terminal (fn [db _] (:terminal db)))
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
         :betting [betting-ui/betting-shell]
         :terminal [terminal-ui/terminal-shell]
         :control-panel [control-panel/control-panel-shell]
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
