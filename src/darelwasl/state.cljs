(ns darelwasl.state
  (:require [clojure.string :as str]))

(def nav-storage-key "darelwasl/last-app")

(def default-login-state
  {:username ""
   :password ""
   :status :idle
   :error nil})

(def status-options
  [{:id nil :label "All statuses"}
   {:id :todo :label "To do"}
   {:id :in-progress :label "In progress"}
   {:id :pending :label "Pending"}
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
   :order :desc
   :page 1
   :page-size 25})

(def default-task-form
  {:id nil
   :title ""
   :description ""
   :status :todo
   :pending-reason ""
   :priority :medium
   :assignee nil
   :due-date ""
   :tags #{}
   :archived? false
   :extended? false})

(def default-task-detail
  {:mode :closed
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

(def default-home-state
  {:status :pending
   :error nil
   :recent []
   :counts {:todo 0 :in-progress 0 :pending 0 :done 0}
   :restarting? false
   :restart-error nil
   :restart-notice nil})

(def default-tags-state
  {:items []
   :status :idle
   :error nil})

(def default-files-filters
  {:query ""})

(def default-files-upload
  {:file nil
   :slug ""
   :status :idle
   :error nil})

(def default-file-form
  {:id nil
   :slug ""
   :reference ""})

(def default-file-detail
  {:form default-file-form
   :status :idle
   :error nil})

(def default-files-state
  {:items []
   :status :idle
   :error nil
   :filters default-files-filters
   :selected nil
   :upload default-files-upload
   :detail default-file-detail})
(def default-prs-state
  {:items []
   :status :idle
   :error nil
   :selected nil
   :filters {:state "open"}})

(def default-content-list-state
  {:items []
   :status :idle
   :error nil
   :selected nil})

(def default-page-form
  {:id nil
   :title ""
   :path ""
   :summary ""
   :navigation-order ""
   :visible? true
   :tags #{}
   :blocks []})

(def default-block-form
  {:id nil
   :page nil
   :type :hero
   :title ""
   :body ""
   :media-ref ""
   :slug ""
   :order 0
   :visible? true
   :tags #{}})

(def default-tag-form
  {:id nil
   :name ""
   :slug ""
   :description ""})

(def default-content-detail
  {:form default-page-form
   :mode :create
   :status :idle
   :error nil})

(def default-journey-form
  {:id nil
   :title ""
   :kind :phase/pre-incorporation
   :order 0
   :bullets ""})

(def default-activation-form
  {:id nil
   :title ""
   :order 0
   :phase nil})

(def default-simple-detail
  {:form nil
   :mode :create
   :status :idle
   :error nil})

(def default-control-state
  {:tab :v1
   :v2 {:status :idle
        :error nil
        :data nil}
   :pages (assoc default-content-list-state :detail default-content-detail)
   :blocks (assoc default-content-list-state :detail (assoc default-content-detail :form default-block-form))
   :tags (assoc default-content-list-state :detail (assoc default-content-detail :form default-tag-form))
   :journey (assoc default-content-list-state :detail (assoc default-simple-detail :form default-journey-form))
   :activation (assoc default-content-list-state :detail (assoc default-simple-detail :form default-activation-form))
   :personas (assoc default-content-list-state :detail (assoc default-simple-detail :form {:id nil :title "" :detail "" :type nil :order 0 :visible? true}))
   :support (assoc default-content-list-state :detail (assoc default-simple-detail :form {:id nil :role :support/we :text "" :order 0}))
   :business {:detail {:form {:id nil
                              :name ""
                              :tagline ""
                              :summary ""
                              :mission ""
                              :vision ""
                              :nav-label ""
                              :hero-headline ""
                              :hero-strapline ""
                              :contact nil
                              :hero-stats []
                              :hero-flows []
                              :visible? true}
                       :status :idle
                       :error nil}}
   :contact {:detail {:form {:id nil
                             :email ""
                             :phone ""
                             :primary-cta-label ""
                             :primary-cta-url ""
                             :secondary-cta-label ""
                             :secondary-cta-url ""}
                      :status :idle
                      :error nil}}})

(def default-nav-state
  {:menu-open? false
   :last-route :home})

(def land-enabled? true)

(def default-land-filters
  {:people-search ""
   :parcel-number ""
   :completeness nil
   :sort :area
   :people-page 1
   :people-page-size 25
   :parcels-page 1
   :parcels-page-size 25})

(def default-land-state
  {:status :pending
   :error nil
   :people []
   :parcels []
   :stats nil
   :pagination {:people {:total nil :limit (:people-page-size default-land-filters) :offset 0}
                :parcels {:total nil :limit (:parcels-page-size default-land-filters) :offset 0}}
   :selected {:person nil :parcel nil}
   :filters default-land-filters})

(def default-betting-form
  {:market-key "1x2"
   :selection nil
   :error nil
   :status :idle})

(def default-betting-odds
  {:status :idle
   :error nil
   :summary nil
   :captured-at nil})

(def default-betting-bets
  {:items []
   :status :idle
   :error nil
   :scoreboard nil})

(def default-betting-state
  {:status :idle
   :error nil
   :day 0
   :groups []
   :matches []
   :selected nil
   :cached? false
   :fetched-at-ms nil
   :odds default-betting-odds
   :bets default-betting-bets
   :form default-betting-form})

(def default-terminal-state
  {:status :idle
   :error nil
   :notice nil
   :sessions []
   :new-session-type :feature
   :new-session-dev-bot? false
   :selected nil
   :output ""
   :cursor 0
   :input ""
   :sending? false
   :verifying? false
   :resuming? false
   :restarting? false
   :interrupting? false
   :polling? false
   :app-ready? false
   :list-polling? false})

(def default-user-form
  {:id nil
   :username ""
   :name ""
   :password ""
   :roles #{}})

(def default-user-detail
  {:form default-user-form
   :mode :create
   :status :idle
   :error nil})

(def default-users-state
  {:items []
   :status :idle
   :error nil
   :selected nil
   :detail default-user-detail})

(def default-db
  {:route :login
   :session nil
   :nav default-nav-state
   :tags default-tags-state
   :home default-home-state
   :login default-login-state
   :tasks default-task-state
   :files default-files-state
   :prs default-prs-state
   :land default-land-state
   :betting default-betting-state
   :terminal default-terminal-state
   :users default-users-state
   :control default-control-state})

(def base-app-options
  (cond-> [{:id :home
            :label "Home"
            :desc "Summary surface"}
           {:id :tasks
            :label "Tasks"
            :desc "Workboard"}
           {:id :files
            :label "Library"
            :desc "Images + PDFs"}
           {:id :betting
            :label "Betting CLV"
            :desc "Browse odds and log bets"}
           {:id :terminal
            :label "Terminal"
            :desc "Codex sessions"}
           {:id :prs
            :label "PRs"
            :desc "Repo overview"}
           {:id :users
            :label "Users"
            :desc "Accounts + roles"}
           {:id :control-panel
            :label "Control panel"
            :desc "Website content"}]
    land-enabled? (conj {:id :land
                         :label "Land"
                         :desc "People ↔ parcels"})))

(defn control-enabled?
  [session]
  (let [roles (->> (get-in session [:user :roles])
                   (map (fn [r]
                          (cond
                            (keyword? r) r
                            (string? r) (-> r (str/replace #"^:" "") keyword)
                            :else r)))
                   set)]
    (boolean (some #{:role/content-editor :role/admin} roles))))

(defn betting-enabled?
  [session]
  (let [roles (->> (get-in session [:user :roles])
                   (map (fn [r]
                          (cond
                            (keyword? r) r
                            (string? r) (-> r (str/replace #"^:" "") keyword)
                            :else r)))
                   set)]
    (contains? roles :role/betting-engineer)))

(defn file-library-enabled?
  [session]
  (let [roles (->> (get-in session [:user :roles])
                   (map (fn [r]
                          (cond
                            (keyword? r) r
                            (string? r) (-> r (str/replace #"^:" "") keyword)
                            :else r)))
                   set)]
    (boolean (some #{:role/file-library :role/admin} roles))))

(defn terminal-enabled?
  [session]
  (let [roles (->> (get-in session [:user :roles])
                   (map (fn [r]
                          (cond
                            (keyword? r) r
                            (string? r) (-> r (str/replace #"^:" "") keyword)
                            :else r)))
                   set)]
    (contains? roles :role/codex-terminal)))

(defn prs-enabled?
  [session]
  (let [roles (->> (get-in session [:user :roles])
                   (map (fn [r]
                          (cond
                            (keyword? r) r
                            (string? r) (-> r (str/replace #"^:" "") keyword)
                    :else r)))
                   set)]
    (contains? roles :role/admin)))

(defn users-enabled?
  [session]
  (let [roles (->> (get-in session [:user :roles])
                   (map (fn [r]
                          (cond
                            (keyword? r) r
                            (string? r) (-> r (str/replace #"^:" "") keyword)
                            :else r)))
                   set)]
    (contains? roles :role/admin)))

(defn app-options
  "Return available app options filtered by session roles/flags."
  [session]
  (cond->> base-app-options
    (not (betting-enabled? session))
    (remove #(= (:id %) :betting))
    (not (file-library-enabled? session))
    (remove #(= (:id %) :files))
    (not (terminal-enabled? session))
    (remove #(= (:id %) :terminal))
    (not (prs-enabled? session))
    (remove #(= (:id %) :prs))
    (not (users-enabled? session))
    (remove #(= (:id %) :users))
    (not (control-enabled? session))
    (remove #(= (:id %) :control-panel))))

(defn allowed-routes
  "Allowed route keywords for current session."
  [session]
  (into #{}
        (map :id)
        (app-options session)))

(defn mark-loading [db path]
  (-> db
      (assoc-in (conj path :status) :loading)
      (assoc-in (conj path :error) nil)))

(defn mark-error [db path message]
  (-> db
      (assoc-in (conj path :status) :error)
      (assoc-in (conj path :error) message)))
