(ns darelwasl.ui.entity
  (:require [darelwasl.ui.components :as ui]
            [darelwasl.util :as util]))

(def ^:private task-detail-copy
  {:title "Task detail"
   :create-title "Draft a new task"
   :badge "Detail"
   :create-badge "Compose"
   :meta-create "Create a task with full fields and feature flag handling."
   :meta-edit-default "Select a task to edit"
   :placeholder-title "Select a task"
   :placeholder-copy "Choose a task from the list or start a fresh one. Full create/edit is available here."})

(def entity-view-config
  {:entity.type/task
   {:list {:title "Tasks"
           :key :task/id
           :meta-fn (fn [items] (str (count items) " items"))
           :render-row (fn [task selected? {:keys [tag-index on-select]}]
                         (let [handle-select (when on-select #(on-select task))]
                           [ui/task-card task selected? tag-index
                            {:on-select handle-select}]))}
    :detail task-detail-copy}

   :entity.type/person
   {:list {:title "People"
           :key :person/id
           :meta "Browse owners and their parcels"
           :render-row (fn [person selected? {:keys [on-select]}]
                         (let [pid (:person/id person)]
                           [ui/list-row {:title (:person/name person)
                                         :meta (:person/address person)
                                         :trailing (str (or (:person/parcel-count person) 0) " parcels · "
                                                        (util/format-area (:person/owned-area-m2 person)) " m²")
                                         :selected? selected?
                                         :on-click #(when on-select (on-select pid))}]))}
    :detail {:component (fn [props] [ui/land-person-detail-view props])}}

   :entity.type/parcel
   {:list {:title "Parcels"
           :key :parcel/id
           :meta "Cadastral lots and owners"
           :render-row (fn [parcel selected? {:keys [on-select]}]
                         (let [pid (:parcel/id parcel)
                               complete? (< (js/Math.abs (- (:parcel/share-total parcel 0.0) 1.0)) 1e-6)]
                           [ui/list-row {:title (str (:parcel/cadastral-id parcel) "/" (:parcel/number parcel))
                                         :meta (:parcel/address parcel)
                                         :description (if complete? "Complete" "Incomplete")
                                         :trailing (str (or (:parcel/owner-count parcel) 0) " owners · "
                                                        (util/format-area (:parcel/area-m2 parcel)) " m²")
                                         :selected? selected?
                                         :on-click #(when on-select (on-select pid))}]))}
    :detail {:component (fn [props] [ui/land-parcel-detail-view props])}}})

(defn config [entity-type]
  (get entity-view-config entity-type))

(defn list-config [entity-type]
  (get-in entity-view-config [entity-type :list]))

(defn list-meta [entity-type items]
  (let [{:keys [meta meta-fn]} (list-config entity-type)]
    (cond
      meta meta
      meta-fn (meta-fn items)
      :else nil)))

(defn render-row
  "Returns a render-row function (item selected?) closed over context."
  [entity-type context]
  (let [f (get-in entity-view-config [entity-type :list :render-row])]
    (fn [item selected?]
      (when f (f item selected? context)))))

(defn detail-config [entity-type]
  (get-in entity-view-config [entity-type :detail]))

(defn detail-component [entity-type]
  (get-in entity-view-config [entity-type :detail :component]))
