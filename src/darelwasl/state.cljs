(ns darelwasl.state)

(def theme-storage-key "darelwasl/theme")
(def nav-storage-key "darelwasl/last-app")
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
   :order :desc
   :page 1
   :page-size 25})

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

(def default-home-state
  {:status :idle
   :error nil
   :recent []
   :counts {:todo 0 :in-progress 0 :done 0}})

(def default-tags-state
  {:items []
   :status :idle
   :error nil})

(def default-theme-state
  {:id :theme/default})

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
  {:status :idle
  :error nil
   :people []
   :parcels []
   :stats nil
   :pagination {:people {:total nil :limit (:people-page-size default-land-filters) :offset 0}
                :parcels {:total nil :limit (:parcels-page-size default-land-filters) :offset 0}}
   :selected {:person nil :parcel nil}
   :filters default-land-filters})

(def default-db
  {:route :login
   :session nil
   :theme default-theme-state
   :nav default-nav-state
   :tags default-tags-state
  :home default-home-state
   :login default-login-state
   :tasks default-task-state
   :land default-land-state})

(def app-options
  (cond-> [{:id :home
            :label "Home"
            :desc "Summary surface"}
           {:id :tasks
            :label "Tasks"
            :desc "Workboard"}]
    land-enabled? (conj {:id :land
                         :label "Land"
                         :desc "People ↔ parcels"})))

(defn mark-loading [db path]
  (-> db
      (assoc-in (conj path :status) :loading)
      (assoc-in (conj path :error) nil)))

(defn mark-error [db path message]
  (-> db
      (assoc-in (conj path :status) :error)
      (assoc-in (conj path :error) message)))
