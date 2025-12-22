(ns darelwasl.ui.entity
  (:require [clojure.string :as str]
            [darelwasl.ui.components :as ui]
            [darelwasl.util :as util]))

(def ^:private task-detail-copy
  {:title "Task"
   :create-title "New task"
   :meta-edit-default "Select a task"
   :placeholder-title "Select a task"
   :placeholder-copy "Choose a task from the list or start a fresh one."})

(def entity-view-config
  {:entity.type/task
   {:list {:title "Tasks"
           :key :task/id
           :meta-fn (fn [items] (str (count items) " items"))
           :render-row (fn [task selected? {:keys [on-select]}]
                         (let [{:task/keys [id title status priority due-date tags assignee archived?]} task
                               handle-select (when on-select #(on-select task))
                               status-label (if archived? "Archived" (when status (util/status-label status)))
                               priority-label (when priority (util/priority-label priority))
                               due-label (when due-date (str "Due " (util/format-date due-date)))
                               assignee-label (or (:user/name assignee) (:user/username assignee))
                               tag-label (when (seq tags)
                                           (str (count tags) " tag" (when (not= 1 (count tags)) "s")))
                               trailing (->> [status-label priority-label due-label assignee-label tag-label]
                                             (remove #(or (nil? %) (str/blank? %)))
                                             (str/join " · "))]
                           [ui/list-row {:title (or title "Untitled")
                                         :trailing trailing
                                         :selected? selected?
                                         :class "compact"
                                         :on-click #(when handle-select (handle-select))}]))}
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
    :detail {:component (fn [props] [ui/land-parcel-detail-view props])}}

   :entity.type/content-page
   {:list {:title "Pages"
           :key :content.page/id
           :render-row (fn [page selected? {:keys [on-select]}]
                         [ui/list-row {:title (or (:content.page/title page) "Untitled page")
                                       :meta (or (:content.page/path page) "—")
                                       :description (str (count (:content.page/blocks page)) " blocks")
                                       :selected? selected?
                                       :on-click #(when on-select (on-select (:content.page/id page)))}])}
    :detail {:title "Page"
             :create-title "New page"
             :badge "Page"
             :create-badge "Create"
             :meta-edit-default "Select a page to edit"
             :placeholder-title "Select a page"
             :placeholder-copy "Choose a page or start a new one."}}

   :entity.type/content-block
   {:list {:title "Blocks"
           :key :content.block/id
           :render-row (fn [block selected? {:keys [on-select tag-index]}]
                         (let [tags (map #(get tag-index (:content.tag/id %)) (:content.block/tag block))
                               tag-label (when (seq tags) (str (count tags) " tag" (when (not= 1 (count tags)) "s")))]
                           [ui/list-row {:title (or (:content.block/title block) (name (:content.block/type block)))
                                         :meta (or (:content.block/slug block) "—")
                                         :description tag-label
                                         :selected? selected?
                                         :on-click #(when on-select (on-select (:content.block/id block)))}]))}
    :detail {:title "Block"
             :create-title "New block"
             :badge "Block"
             :create-badge "Create"
             :meta-edit-default "Select a block to edit"
             :placeholder-title "Select a block"
             :placeholder-copy "Choose a block or start a new one."}}

   :entity.type/content-tag
   {:list {:title "Tags"
           :key :content.tag/id
           :render-row (fn [tag selected? {:keys [on-select]}]
                         [ui/list-row {:title (or (:content.tag/name tag) "Tag")
                                       :meta (or (:content.tag/slug tag) "—")
                                       :selected? selected?
                                       :on-click #(when on-select (on-select (:content.tag/id tag)))}])}
    :detail {:title "Tag"
             :create-title "New tag"
             :badge "Tag"
             :create-badge "Create"
             :meta-edit-default "Select a tag to edit"
             :placeholder-title "Select a tag"
             :placeholder-copy "Choose a tag or start a new one."}}})

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
