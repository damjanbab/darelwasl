(ns darelwasl.tasks
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.entity :as entity]
            [darelwasl.provenance :as prov]
            [darelwasl.schema :as schema]
            [darelwasl.validation :as v])
  (:import (java.time Instant)
           (java.time.format DateTimeFormatter)
           (java.util Date UUID)))

(def ^:private iso-formatter DateTimeFormatter/ISO_INSTANT)

(def ^:private task-schema-entry
  (delay (some #(when (= (:id %) :cap/schema/task) %) (schema/read-registry))))

(def ^:private allowed-statuses
  (delay (or (some-> @task-schema-entry (get-in [:enums :task/status]) set)
             #{:todo :in-progress :pending :done})))

(def ^:private allowed-priorities
  (delay (or (some-> @task-schema-entry (get-in [:enums :task/priority]) set)
             #{:low :medium :high})))

(def ^:private allowed-note-types
  #{:note.type/comment :note.type/pending-reason :note.type/system})

(def ^:private priority-rank
  {:high 3 :medium 2 :low 1})

(def ^:private param-value v/param-value)
(def ^:private normalize-string-field v/normalize-string)
(def ^:private normalize-enum v/normalize-enum)
(def ^:private normalize-uuid v/normalize-uuid)
(def ^:private normalize-boolean v/normalize-boolean)

(def ^:private default-list-limit 25)
(def ^:private max-list-limit 200)

(def ^:private pull-pattern
  [:task/id
   :entity/ref
   :entity/type
   :task/title
   :task/description
   :task/status
   :fact/source-id
   :fact/source-type
   :fact/adapter
   :fact/run-id
   :fact/workspace
   :fact/created-at
   :fact/valid-from
   :fact/valid-until
   {:task/assignee [:user/id :user/username :user/name]}
   :task/due-date
   :task/priority
   {:task/tags [:tag/id :tag/name]}
   :task/archived?
   :task/extended?
   :task/automation-key
   :task/pending-reason])

(defn- format-inst
  [^Date inst]
  (when inst
    (.format iso-formatter (.toInstant inst))))

(defn- now-inst
  []
  (Date/from (Instant/now)))

(defn- pending-note-tx
  [db task-id reason actor]
  (let [author-id (:user/id actor)
        base {:note/id (UUID/randomUUID)
              :entity/type :entity.type/note
              :note/body reason
              :note/type :note.type/pending-reason
              :note/subject [:task/id task-id]
              :note/created-at (now-inst)}
        base (entity/with-ref db base)]
    (cond-> base
      author-id (assoc :note/author [:user/id author-id]))))

(defn- comment-note-tx
  [task-id body note-type actor]
  (let [author-id (:user/id actor)]
    (cond-> {:note/id (UUID/randomUUID)
             :entity/type :entity.type/note
             :note/body body
             :note/type note-type
             :note/subject [:task/id task-id]
             :note/created-at (now-inst)}
      author-id (assoc :note/author [:user/id author-id]))))

(defn- latest-comment-note
  [db task-id author-id]
  (let [notes (d/q '[:find ?id ?created
                     :in $ ?task-id ?author-id
                     :where [?t :task/id ?task-id]
                            [?author :user/id ?author-id]
                            [?n :note/subject ?t]
                            [?n :note/type :note.type/comment]
                            [?n :note/author ?author]
                            [?n :note/id ?id]
                            [?n :note/created-at ?created]]
                   db task-id author-id)]
    (when (seq notes)
      (->> notes
           (sort-by second)
           last
           first))))

(defn- normalize-instant
  [value label]
  (let [raw (if (sequential? value) (first value) value)]
    (cond
      (nil? raw) {:value nil}
      (instance? Date raw) {:value raw}
      (instance? Instant raw) {:value (Date/from raw)}
      (string? raw) (try
                      {:value (Date/from (Instant/parse (str/trim raw)))}
                      (catch Exception _
                        {:error (str "Invalid " label " format; expected ISO-8601")}))
      :else {:error (str "Invalid " label)})))

(defn- tag-by-id
  [db tag-id]
  (when tag-id
    (-> (d/q '[:find (pull ?t [:tag/id :tag/name])
               :in $ ?id
               :where [?t :tag/id ?id]]
             db tag-id)
        ffirst)))

(defn- ensure-tags-exist
  [db tag-ids]
  (let [tags (->> tag-ids
                  (map (fn [id] [id (tag-by-id db id)]))
                  (into {}))
        missing (->> tags (keep (fn [[id tag]] (when-not tag id))) seq)]
    (cond
      missing {:error (str "Unknown tag(s): " (str/join ", " (map str missing)))}
      :else {:tags (vals tags)
             :tag-ids tag-ids})))

(defn- normalize-tags
  ([db value] (normalize-tags db value {:default-on-nil #{}}))
  ([db value {:keys [default-on-nil]}]
   (cond
     (nil? value) {:value default-on-nil :tags []}
     :else
     (let [raw (cond
                 (set? value) value
                 (sequential? value) (set value)
                 (string? value) (set (map str/trim (str/split value #",")))
                 :else ::invalid)
           tag-ids (when-not (= raw ::invalid)
                     (->> raw
                          (map (fn [v]
                                 (cond
                                   (uuid? v) v
                                   (string? v) (try
                                                 (UUID/fromString (str/trim v))
                                                 (catch Exception _ nil))
                                   :else nil)))
                          (remove nil?)
                          set))]
       (cond
         (= raw ::invalid) {:error "Tags must be provided as a collection"}
         (nil? tag-ids) {:value default-on-nil :tags []}
         :else
         (let [{:keys [error tags]} (ensure-tags-exist db tag-ids)]
           (if error
             {:error error}
             {:value tag-ids
              :tags tags})))))))

(defn- user-by-id
  [db user-id]
  (when user-id
    (-> (d/q '[:find (pull ?u [:user/id :user/username :user/name])
               :in $ ?id
               :where [?u :user/id ?id]]
             db user-id)
        ffirst)))

(defn- resolve-task-id
  [db task-id]
  (entity/resolve-id db :task/id task-id "task id"))

(defn- task-eid
  [db task-id]
  (when task-id
    (let [{tid :value} (resolve-task-id db task-id)]
      (when tid
        (ffirst (d/q '[:find ?e :in $ ?id :where [?e :task/id ?id]]
                     db tid))))))

(defn- task-eid-by-automation-key
  [db automation-key]
  (when (and (string? automation-key) (not (str/blank? (str/trim automation-key))))
    (ffirst (d/q '[:find ?e
                   :in $ ?k
                   :where [?e :task/automation-key ?k]]
                 db (str/trim automation-key)))))

(defn- updated-at
  [db eid]
  (-> (d/q '[:find (max ?inst)
             :in $ ?e
             :where [?e _ _ ?tx]
                    [?tx :db/txInstant ?inst]]
           db eid)
      ffirst))

(defn- pull-task
  [db eid]
  (when-let [task (d/pull db pull-pattern eid)]
    (assoc task :task/updated-at (updated-at db eid))))

(defn- present-task
  [task]
  (when (map? task)
    (let [prov (select-keys task [:fact/source-id
                                  :fact/source-type
                                  :fact/adapter
                                  :fact/run-id
                                  :fact/workspace
                                  :fact/created-at
                                  :fact/valid-from
                                  :fact/valid-until])]
      (-> task
          (update :task/due-date format-inst)
          (update :task/updated-at format-inst)
          (update :fact/created-at format-inst)
          (update :fact/valid-from format-inst)
          (update :fact/valid-until format-inst)
          (assoc :task/provenance prov)
          (update :task/tags (fn [tags]
                               (when tags
                                 (->> tags
                                      (sort-by (comp str/lower-case :tag/name))
                                      vec))))))))

(defn- existing-tag-by-name
  [db tag-name]
  (let [lname (-> tag-name str/trim str/lower-case)]
    (some (fn [[tag]]
            (when (= lname (-> (:tag/name tag) str/lower-case))
              tag))
          (d/q '[:find (pull ?t [:tag/id :tag/name])
                 :where [?t :tag/name _]]
               db))))

(defn- tag-name-field
  [name]
  (normalize-string-field name "Tag name" {:required true
                                           :allow-blank? false}))

(defn- error
  [status message & [details]]
  {:error {:status status
           :message message
           :details details}})

(defn- ensure-conn
  [conn]
  (when-not conn
    (error 500 "Database not ready")))

(defn- parse-int
  [v]
  (cond
    (nil? v) nil
    (integer? v) v
    (number? v) (int v)
    (string? v) (try
                  (Integer/parseInt (str/trim v))
                  (catch Exception _ nil))
    :else nil))

(defn- attempt-transact
  [conn tx-data context]
  (try
    (if (seq tx-data)
      (d/transact conn {:tx-data tx-data})
      {:db-after (d/db conn)})
    (catch Exception e
      (log/error e (str "Failed to " context))
      {:error {:status 500
               :message (str "Failed to " context)
               :details (.getMessage e)}})))

(defn- normalize-list-params
  [params]
  (let [{status :value status-err :error} (v/normalize-enum (v/param-value params :status) @allowed-statuses "status")
        {priority :value priority-err :error} (v/normalize-enum (v/param-value params :priority) @allowed-priorities "priority")
        {tag :value tag-err :error} (v/normalize-uuid (v/param-value params :tag) "tag")
        {assignee :value assignee-err :error} (v/normalize-uuid (v/param-value params :assignee) "assignee")
        {archived :value archived-err :error} (v/normalize-boolean (v/param-value params :archived) "archived" {:default false
                                                                                                                :allow-all? true})
        limit-raw (v/param-value params :limit)
        offset-raw (v/param-value params :offset)
        limit (or (parse-int limit-raw) default-list-limit)
        offset (or (parse-int offset-raw) 0)
        raw-sort (v/param-value params :sort)
        {sort-val :value sort-err :error} (v/normalize-enum raw-sort #{:due :priority :updated} "sort")
        sort-key (or sort-val :updated)
        default-order (if (= sort-key :due) :asc :desc)
        raw-order (v/param-value params :order)
        {order-val :value order-err :error} (v/normalize-enum raw-order #{:asc :ascending :desc :descending} "order")
        order-key (cond
                    order-val (if (#{:desc :descending} order-val) :desc :asc)
                    :else default-order)]
    (cond
      status-err (error 400 status-err)
      priority-err (error 400 priority-err)
      tag-err (error 400 tag-err)
      assignee-err (error 400 assignee-err)
      archived-err (error 400 archived-err)
      (or (nil? limit) (<= limit 0)) (error 400 (str "Invalid limit; must be between 1 and " max-list-limit))
      (> limit max-list-limit) (error 400 (str "Limit too high; max " max-list-limit))
      (or (nil? offset) (neg? offset)) (error 400 "Invalid offset; must be 0 or greater")
      sort-err (error 400 sort-err)
      order-err (error 400 order-err)
      :else {:filters {:status status
                       :priority priority
                       :tag tag
                       :assignee assignee
                       :archived archived
                       :sort sort-key
                       :order order-key}
             :limit limit
             :offset offset})))

(defn- filter-task
  [{:task/keys [status assignee tags priority archived?]}
   filters]
  (let [status-filter (:status filters)
        priority-filter (:priority filters)
        assignee-filter (:assignee filters)
        tag-filter (:tag filters)
        tag-set (->> tags (map :tag/id) set)
        archived-filter (:archived filters)]
    (and (or (nil? status-filter) (= status status-filter))
         (or (nil? priority-filter) (= priority priority-filter))
         (or (nil? assignee-filter) (= (:user/id assignee) assignee-filter))
         (or (nil? tag-filter) (and (seq tag-set) (contains? tag-set tag-filter)))
         (case archived-filter
           :all true
           true archived?
           false (not archived?)))))

(defn- compare-nil-last
  [order]
  (fn [a b]
    (cond
      (and (nil? a) (nil? b)) 0
      (nil? a) 1
      (nil? b) -1
      (= order :desc) (compare b a)
      :else (compare a b))))

(defn- sort-tasks
  [tasks {:keys [sort order]}]
  (case sort
    :due (sort-by :task/due-date (compare-nil-last order) tasks)
    :priority (sort-by (fn [{:task/keys [priority]}]
                         (get priority-rank priority Integer/MAX_VALUE))
                       (compare-nil-last order)
                       tasks)
    :updated (sort-by :task/updated-at (compare-nil-last order) tasks)
    tasks))

(defn- task-eids-by-filters
  [db {:keys [status priority assignee tag archived]}]
  (let [archived-filter (case archived
                          :all :all
                          true true
                          false)
        where (cond-> ['[?e :entity/type :entity.type/task]]
                status (conj ['?e :task/status '?status])
                priority (conj ['?e :task/priority '?priority])
                assignee (-> (conj ['?e :task/assignee '?assignee-entity])
                             (conj ['?assignee-entity :user/id '?assignee-id]))
                tag (-> (conj ['?e :task/tags '?tag-entity])
                        (conj ['?tag-entity :tag/id '?tag-id])))
        in (cond-> ['$]
             status (conj '?status)
             priority (conj '?priority)
             assignee (conj '?assignee-id)
             tag (conj '?tag-id))
        args (cond-> [db]
               status (conj status)
               priority (conj priority)
               assignee (conj assignee)
               tag (conj tag))
        [where in args] (let [archived-clause ['(get-else $ ?e :task/archived? false) '?arch]]
                          (case archived-filter
                            :all [where in args]
                            true [(conj where archived-clause ['(= ?arch true)])
                                  in
                                  args]
                            [(conj where archived-clause ['(= ?arch false)])
                             in
                             args]))
        query {:find ['?e]
               :in (vec in)
               :where where}]
    (map first (d/q {:query query
                     :args args}))))

(defn recent-tasks
  "Return up to `limit` recent tasks sorted by updated-at desc, optional include-archived?."
  [conn {:keys [limit include-archived?]}]
  (or (ensure-conn conn)
      (try
        (let [db (d/db conn)
              eids (entity/eids-by-type db :entity.type/task)
              tasks (->> eids
                         (map #(pull-task db %))
                         (remove nil?)
                         (filter #(or include-archived?
                                      (not (:task/archived? %))))
                         (sort-tasks {:sort :updated :order :desc})
                         (take (or limit 5))
                         (map present-task)
                         (remove nil?)
                         vec)]
          {:tasks tasks})
        (catch Exception e
          (log/error e "Failed to compute recent tasks")
          {:error {:status 500
                   :message "Unable to fetch recent tasks"}}))))

(defn task-status-counts
  "Return counts of tasks by status (optionally excluding archived unless include-archived?)."
  [conn {:keys [include-archived?]}]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            eids (entity/eids-by-type db :entity.type/task)
            counts (->> eids
                        (map #(pull-task db %))
                        (remove nil?)
                        (filter #(or include-archived?
                                     (not (:task/archived? %))))
                        (map :task/status)
                        (frequencies))]
        {:counts {:todo (get counts :todo 0)
                  :in-progress (get counts :in-progress 0)
                  :pending (get counts :pending 0)
                  :done (get counts :done 0)}})))

(defn list-tasks
  "List tasks with optional filters and sort/order parameters."
  [conn params]
  (or (ensure-conn conn)
      (let [{:keys [filters error limit offset]} (normalize-list-params params)]
        (if error
          {:error error}
          (let [db (d/db conn)
                eids (task-eids-by-filters db filters)
                tasks (->> eids
                           (map #(pull-task db %))
                           (remove nil?))
                sorted (sort-tasks tasks filters)
                total (count sorted)
                bounded-offset (min offset (max 0 (- total limit)))
                paged (->> sorted
                           (drop bounded-offset)
                           (take limit)
                           (map present-task)
                           vec)]
            {:tasks paged
             :pagination {:total total
                          :limit limit
                          :offset bounded-offset
                          :page (inc (quot bounded-offset limit))
                          :returned (count paged)}})))))

(defn fetch-task
  "Fetch a single task by UUID or :entity/ref."
  [conn task-id]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            {tid :value id-err :error} (resolve-task-id db task-id)]
        (cond
          id-err (error 400 id-err)
          (nil? tid) (error 400 "Task id is required")
          :else
          (let [eid (ffirst (d/q '[:find ?e
                                   :in $ ?id
                                   :where [?e :task/id ?id]]
                                 db tid))]
            (if-not eid
              (error 404 "Task not found")
              {:task (present-task (pull-task db eid))}))))))

(defn- validate-assignee!
  [db assignee-id]
  (cond
    (nil? assignee-id) (error 400 "Assignee is required")
    :else
    (if-let [assignee (user-by-id db assignee-id)]
      {:assignee assignee}
      (error 400 "Assignee not found"))))

(defn- validate-create
  [db body]
  (let [{title :value title-err :error} (normalize-string-field (param-value body :task/title) "Title" {:required true
                                                                                                         :allow-blank? false})
        {desc :value desc-err :error} (normalize-string-field (param-value body :task/description) "Description" {:required true
                                                                                                                   :allow-blank? false})
        {status :value status-err :error} (normalize-enum (param-value body :task/status) @allowed-statuses "status")
        pending? (= status :pending)
        pending-raw (or (param-value body :pending-reason)
                        (param-value body :note/body))
        {pending-reason :value pending-err :error} (normalize-string-field pending-raw
                                                                           "Pending reason"
                                                                           {:required pending?
                                                                            :allow-blank? false})
        {priority :value priority-err :error} (normalize-enum (param-value body :task/priority) @allowed-priorities "priority")
        {:keys [value] :as tags-result} (normalize-tags db (param-value body :task/tags) {:default-on-nil #{}})
        {due-date :value due-err :error} (normalize-instant (param-value body :task/due-date) "due date")
        {archived? :value archived-err :error} (normalize-boolean (param-value body :task/archived?) "archived" {:default false})
        {extended? :value extended-err :error} (normalize-boolean (param-value body :task/extended?) "extended" {:default false})
        {automation-key :value automation-err :error} (normalize-string-field (param-value body :task/automation-key)
                                                                              "automation key"
                                                                              {:required false
                                                                               :allow-blank? false})
        {assignee-id :value assignee-err :error} (normalize-uuid (param-value body :task/assignee) "assignee")]
    (cond
      title-err (error 400 title-err)
      desc-err (error 400 desc-err)
      status-err (error 400 status-err)
      pending-err (error 400 pending-err)
      priority-err (error 400 priority-err)
      (:error tags-result) (error 400 (:error tags-result))
      due-err (error 400 due-err)
      archived-err (error 400 archived-err)
      extended-err (error 400 extended-err)
      automation-err (error 400 automation-err)
      assignee-err (error 400 assignee-err)
      :else
      (let [{assignee :assignee assignee-error :error} (validate-assignee! db assignee-id)]
        (if assignee-error
          {:error assignee-error}
          (let [tag-ids (or value #{})
                base {:task/id (UUID/randomUUID)
                      :entity/type :entity.type/task
                      :task/title title
                      :task/description desc
                      :task/status status
                      :task/priority priority
                      :task/tags (mapv (fn [tid] [:tag/id tid]) tag-ids)
                      :task/assignee [:user/id (:user/id assignee)]
                      :task/archived? (boolean archived?)
                      :task/extended? (boolean extended?)}
                base (entity/with-ref db base)
                data (cond-> base
                       due-date (assoc :task/due-date due-date)
                       (and pending? pending-reason) (assoc :task/pending-reason pending-reason)
                       automation-key (assoc :task/automation-key automation-key))]
            {:data data
             :pending-reason pending-reason}))))))

(defn- update-tags-tx
  [task-id existing-tags new-tag-ids]
  (let [existing (->> existing-tags (map :tag/id) set)
        new-set (set (or new-tag-ids #{}))
        to-add (set/difference new-set existing)
        to-retract (set/difference existing new-set)]
    (concat (map (fn [tag-id] [:db/add [:task/id task-id] :task/tags [:tag/id tag-id]]) to-add)
            (map (fn [tag-id] [:db/retract [:task/id task-id] :task/tags [:tag/id tag-id]]) to-retract))))

(defn- validate-update
  [db task-id body]
  (let [{task-id :value id-err :error} (resolve-task-id db task-id)
        {title :value title-err :error} (normalize-string-field (param-value body :task/title) "Title" {:required false
                                                                                                         :allow-blank? false})
        {desc :value desc-err :error} (normalize-string-field (param-value body :task/description) "Description" {:required false
                                                                                                                   :allow-blank? true})
        {priority :value priority-err :error} (normalize-enum (param-value body :task/priority) @allowed-priorities "priority")
        {:keys [value] :as tags-result} (normalize-tags db (param-value body :task/tags) {:default-on-nil nil})
        {extended? :value extended-err :error} (normalize-boolean (param-value body :task/extended?) "extended" {:default nil})]
    (cond
      id-err (error 400 id-err)
      title-err (error 400 title-err)
      desc-err (error 400 desc-err)
      priority-err (error 400 priority-err)
      (:error tags-result) (error 400 (:error tags-result))
      extended-err (error 400 extended-err)
      (and (nil? title) (nil? desc) (nil? priority) (nil? value) (nil? extended?))
      (error 400 "No fields provided to update")
      :else
      (if-let [eid (task-eid db task-id)]
        {:eid eid
         :task-id task-id
         :updates (cond-> {:task/id task-id}
                    title (assoc :task/title title)
                    desc (assoc :task/description desc)
                    priority (assoc :task/priority priority)
                    (some? extended?) (assoc :task/extended? extended?))
         :tags value}
        (error 404 "Task not found")))))

(defn- validate-simple-status
  [db task-id body]
  (let [{task-id :value id-err :error} (resolve-task-id db task-id)
        {status :value status-err :error} (normalize-enum (param-value body :task/status) @allowed-statuses "status")
        pending? (= status :pending)
        pending-raw (or (param-value body :pending-reason)
                        (param-value body :note/body))
        {pending-reason :value pending-err :error} (normalize-string-field pending-raw
                                                                           "Pending reason"
                                                                           {:required pending?
                                                                            :allow-blank? false})]
    (cond
      id-err (error 400 id-err)
      status-err (error 400 status-err)
      pending-err (error 400 pending-err)
      :else
      (if (task-eid db task-id)
        {:task-id task-id
         :updates {:task/id task-id
                   :task/status status}
         :pending-reason (when (= status :pending) pending-reason)}
        (error 404 "Task not found")))))

(defn- validate-assignee-update
  [db task-id body]
  (let [{task-id :value id-err :error} (resolve-task-id db task-id)
        {assignee-id :value assignee-err :error} (normalize-uuid (param-value body :task/assignee) "assignee")]
    (cond
      id-err (error 400 id-err)
      assignee-err (error 400 assignee-err)
      :else
      (let [{assignee :assignee assignee-error :error} (validate-assignee! db assignee-id)]
        (cond
          assignee-error {:error assignee-error}
          (not (task-eid db task-id)) (error 404 "Task not found")
          :else {:task-id task-id
                 :updates {:task/id task-id
                           :task/assignee [:user/id (:user/id assignee)]}})))))

(defn- validate-due-date-update
  [db task-id body]
  (let [{task-id :value id-err :error} (resolve-task-id db task-id)
        {due-date :value due-err :error} (normalize-instant (param-value body :task/due-date) "due date")]
    (cond
      id-err (error 400 id-err)
      due-err (error 400 due-err)
      :else
      (if-let [eid (task-eid db task-id)]
        {:task-id task-id
         :eid eid
         :due-date due-date}
        (error 404 "Task not found")))))

(defn- validate-tags-update
  [db task-id body]
  (let [{task-id :value id-err :error} (resolve-task-id db task-id)
        {:keys [value] :as tags-result} (normalize-tags db (param-value body :task/tags) {:default-on-nil nil})]
    (cond
      id-err (error 400 id-err)
      (:error tags-result) (error 400 (:error tags-result))
      (nil? value) (error 400 "Tags are required")
      :else
      (if-let [eid (task-eid db task-id)]
        {:task-id task-id
         :eid eid
         :tags value}
        (error 404 "Task not found")))))

(defn- validate-archive-update
  [db task-id body]
  (let [{task-id :value id-err :error} (resolve-task-id db task-id)
        {archived? :value archived-err :error} (normalize-boolean (param-value body :task/archived?) "archived" {:default nil})]
    (cond
      id-err (error 400 id-err)
      archived-err (error 400 archived-err)
      (nil? archived?) (error 400 "Archived flag is required")
      :else
      (if (task-eid db task-id)
        {:task-id task-id
         :updates {:task/id task-id
                   :task/archived? archived?}}
        (error 404 "Task not found")))))

(defn- validate-add-note
  [db body]
  (let [{task-id :value id-err :error} (normalize-uuid (param-value body :task/id) "task id")
        {note-body :value body-err :error} (normalize-string-field (param-value body :note/body)
                                                                   "Note"
                                                                   {:required true
                                                                    :allow-blank? false})
        {note-type :value type-err :error} (normalize-enum (param-value body :note/type) allowed-note-types "note type")
        note-type (or note-type :note.type/comment)]
    (cond
      id-err (error 400 id-err)
      body-err (error 400 body-err)
      type-err (error 400 type-err)
      (not (task-eid db task-id)) (error 404 "Task not found")
      :else {:task-id task-id
             :note-body note-body
             :note-type note-type})))

(defn- validate-edit-note
  [db body actor]
  (let [{task-id :value id-err :error} (normalize-uuid (param-value body :task/id) "task id")
        {note-body :value body-err :error} (normalize-string-field (param-value body :note/body)
                                                                   "Note"
                                                                   {:required true
                                                                    :allow-blank? false})
        author-id (:user/id actor)]
    (cond
      id-err (error 400 id-err)
      body-err (error 400 body-err)
      (nil? author-id) (error 400 "Note edits require an authenticated user")
      (not (task-eid db task-id)) (error 404 "Task not found")
      :else {:task-id task-id
             :note-body note-body
             :author-id author-id})))

(defn- validate-delete-note
  [db body actor]
  (let [{task-id :value id-err :error} (normalize-uuid (param-value body :task/id) "task id")
        author-id (:user/id actor)]
    (cond
      id-err (error 400 id-err)
      (nil? author-id) (error 400 "Note deletions require an authenticated user")
      (not (task-eid db task-id)) (error 404 "Task not found")
      :else {:task-id task-id
             :author-id author-id})))

(defn create-task!
  [conn body actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            {:keys [data pending-reason error]} (validate-create db body)]
        (if error
          {:error error}
          (let [automation-key (:task/automation-key data)
                existing-eid (when automation-key (task-eid-by-automation-key db automation-key))]
            (if existing-eid
              (if-let [existing (some-> (pull-task db existing-eid) present-task)]
                (do
                  (log/infof "AUDIT task-create-idempotent actor=%s key=%s task=%s"
                             (or (:user/username actor) (:user/id actor) (:automation/id actor) "-")
                             automation-key
                             (:task/id existing))
                  {:task existing})
                (error 500 "Task not available for automation key"))
              (let [tx-prov (prov/provenance actor)
                    data-with-reason (cond-> data
                                       (and (= :pending (:task/status data))
                                            (not (str/blank? pending-reason)))
                                       (assoc :task/pending-reason pending-reason))
                    note-tx (when (and (= :pending (:task/status data))
                                       (not (str/blank? pending-reason)))
                              (pending-note-tx db (:task/id data) pending-reason actor))
                    tx-data (cond-> [(prov/enrich-tx data-with-reason tx-prov)]
                              note-tx (conj (prov/enrich-tx note-tx tx-prov)))
                    tx-result (attempt-transact conn tx-data "create task")]
                (if-let [tx-error (:error tx-result)]
                  (let [db-now (d/db conn)
                        eid (when automation-key (task-eid-by-automation-key db-now automation-key))
                        task (when eid (some-> (pull-task db-now eid) present-task))]
                    (if task
                      (do
                        (log/infof "AUDIT task-create-idempotent-on-conflict actor=%s key=%s task=%s"
                                   (or (:user/username actor) (:user/id actor) (:automation/id actor) "-")
                                   automation-key
                                   (:task/id task))
                        {:task task})
                      {:error tx-error}))
                  (let [db-after (:db-after tx-result)
                        created-eid (task-eid db-after (:task/id data))
                        task (when created-eid
                               (some-> (pull-task db-after created-eid) present-task))]
                    (if task
                      (do
                        (log/infof "AUDIT task-create user=%s task=%s status=%s"
                                   (or (:user/username actor) (:user/id actor))
                                   (:task/id data)
                                   (:task/status data))
                        {:task task})
                      (error 500 "Task not available after create")))))))))))

(defn update-task!
  [conn task-id body actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            result (validate-update db task-id body)]
        (if-let [err (:error result)]
          {:error err}
          (let [{:keys [eid task-id updates tags]} result
                current (pull-task db eid)
                tx-data (cond-> [updates]
                          tags (into (update-tags-tx task-id (:task/tags current) tags)))
                tx-prov (prov/provenance actor)
                tx-result (attempt-transact conn (map #(prov/enrich-tx % tx-prov) tx-data) "update task")
                updated (when-let [db-after (:db-after tx-result)]
                          (when-let [eid (task-eid db-after task-id)]
                            (some-> (pull-task db-after eid)
                                    present-task)))]
            (cond
              (:error tx-result) {:error (:error tx-result)}
              updated (do
                        (log/infof "AUDIT task-update user=%s task=%s fields=%s"
                                   (or (:user/username actor) (:user/id actor))
                                   task-id
                                   (-> updates keys sort))
                        {:task updated})
              :else (error 500 "Task not available after update")))))))

(defn set-status!
  [conn task-id body actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            result (validate-simple-status db task-id body)]
        (if-let [err (:error result)]
          {:error err}
          (let [{:keys [task-id updates pending-reason]} result
                current (when-let [eid (task-eid db task-id)]
                          (pull-task db eid))
                tx-prov (prov/provenance actor)
                note-tx (when (and (= :pending (:task/status updates))
                                   (not (str/blank? pending-reason)))
                          (pending-note-tx db task-id pending-reason actor))
                retract-pending (when (and (not= (:task/status updates) :pending)
                                           (some? (:task/pending-reason current)))
                                  [:db/retract [:task/id task-id] :task/pending-reason (:task/pending-reason current)])
                updates-with-reason (cond-> updates
                                       (and (= (:task/status updates) :pending)
                                            (not (str/blank? pending-reason)))
                                       (assoc :task/pending-reason pending-reason))
                tx-data (cond-> [(prov/enrich-tx updates-with-reason tx-prov)]
                          note-tx (conj (prov/enrich-tx note-tx tx-prov))
                          retract-pending (conj retract-pending))
                tx-result (attempt-transact conn tx-data "set task status")
                updated (when-let [db-after (:db-after tx-result)]
                          (when-let [eid (task-eid db-after task-id)]
                            (some-> (pull-task db-after eid)
                                    present-task)))]
            (cond
              (:error tx-result) {:error (:error tx-result)}
              updated (do
                        (log/infof "AUDIT task-set-status user=%s task=%s status=%s"
                                   (or (:user/username actor) (:user/id actor))
                                   task-id
                                   (:task/status updates))
                        {:task updated})
              :else (error 500 "Task not available after status update")))))))

(defn assign-task!
  [conn task-id body actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            result (validate-assignee-update db task-id body)]
        (if-let [err (:error result)]
          {:error err}
          (let [{:keys [updates task-id]} result
                tx-prov (prov/provenance actor)
                tx-result (attempt-transact conn [(prov/enrich-tx updates tx-prov)] "assign task")
                updated (when-let [db-after (:db-after tx-result)]
                          (when-let [eid (task-eid db-after task-id)]
                            (some-> (pull-task db-after eid)
                                    present-task)))]
            (cond
              (:error tx-result) {:error (:error tx-result)}
              updated (do
                        (log/infof "AUDIT task-assign user=%s task=%s assignee=%s"
                                   (or (:user/username actor) (:user/id actor))
                                   task-id
                                   (:task/assignee updates))
                        {:task updated})
              :else (error 500 "Task not available after assignment")))))))

(defn set-due-date!
  [conn task-id body actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            result (validate-due-date-update db task-id body)]
        (if-let [err (:error result)]
          {:error err}
          (let [{:keys [task-id due-date eid]} result
                current (pull-task db eid)
                tx-data (cond-> []
                          due-date (conj {:task/id task-id
                                          :task/due-date due-date})
                          (and (nil? due-date) (:task/due-date current))
                          (conj [:db/retract [:task/id task-id] :task/due-date (:task/due-date current)]))
                tx-prov (prov/provenance actor)
                tx-result (attempt-transact conn (map #(prov/enrich-tx % tx-prov) tx-data) "update task due date")
                updated (when-let [db-after (:db-after tx-result)]
                          (when-let [eid (task-eid db-after task-id)]
                            (some-> (pull-task db-after eid)
                                    present-task)))]
            (cond
              (:error tx-result) {:error (:error tx-result)}
              updated (do
                        (log/infof "AUDIT task-set-due user=%s task=%s"
                                   (or (:user/username actor) (:user/id actor))
                                   task-id)
                        {:task updated})
              :else (error 500 "Task not available after due date update")))))))

(defn set-tags!
  [conn task-id body actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            result (validate-tags-update db task-id body)]
        (if-let [err (:error result)]
          {:error err}
          (let [{:keys [eid task-id tags]} result
                current (pull-task db eid)
                tx-data (vec (update-tags-tx task-id (:task/tags current) tags))
                tx-prov (prov/provenance actor)
                tx-result (attempt-transact conn (map #(prov/enrich-tx % tx-prov) tx-data) "update task tags")
                updated (when-let [db-after (:db-after tx-result)]
                          (when-let [eid (task-eid db-after task-id)]
                            (some-> (pull-task db-after eid)
                                    present-task)))]
            (cond
              (:error tx-result) {:error (:error tx-result)}
              updated (do
                        (log/infof "AUDIT task-set-tags user=%s task=%s tags=%s"
                                   (or (:user/username actor) (:user/id actor))
                                   task-id
                                   (sort tags))
                        {:task updated})
              :else (error 500 "Task not available after tag update")))))))

(defn archive-task!
  [conn task-id body actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            result (validate-archive-update db task-id body)]
        (if-let [err (:error result)]
          {:error err}
          (let [{:keys [updates task-id]} result
                tx-prov (prov/provenance actor)
                tx-result (attempt-transact conn [(prov/enrich-tx updates tx-prov)] "archive task")
                updated (when-let [db-after (:db-after tx-result)]
                          (when-let [eid (task-eid db-after task-id)]
                            (some-> (pull-task db-after eid)
                                    present-task)))]
            (cond
              (:error tx-result) {:error (:error tx-result)}
              updated (do
                (log/infof "AUDIT task-archive user=%s task=%s archived=%s"
                           (or (:user/username actor) (:user/id actor))
                           task-id
                           (:task/archived? updates))
                {:task updated})
              :else (error 500 "Task not available after archive update")))))))

(defn add-note!
  [conn body actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            result (validate-add-note db body)]
        (if-let [err (:error result)]
          {:error err}
          (let [{:keys [task-id note-body note-type]} result
                tx-prov (prov/provenance actor)
                note-tx (comment-note-tx task-id note-body note-type actor)
                tx-result (attempt-transact conn [(prov/enrich-tx note-tx tx-prov)] "add note")
                updated (when-let [db-after (:db-after tx-result)]
                          (when-let [eid (task-eid db-after task-id)]
                            (some-> (pull-task db-after eid)
                                    present-task)))]
            (cond
              (:error tx-result) {:error (:error tx-result)}
              updated (do
                        (log/infof "AUDIT task-add-note user=%s task=%s type=%s"
                                   (or (:user/username actor) (:user/id actor))
                                   task-id
                                   note-type)
                        {:task updated
                         :note {:note/id (:note/id note-tx)
                                :note/type note-type
                                :note/body note-body}})
              :else (error 500 "Task not available after note add")))))))

(defn edit-note!
  [conn body actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            result (validate-edit-note db body actor)]
        (if-let [err (:error result)]
          {:error err}
          (let [{:keys [task-id note-body author-id]} result
                note-id (latest-comment-note db task-id author-id)]
            (if-not note-id
              (error 404 "No editable comment note found for this task")
              (let [tx-prov (prov/provenance actor)
                    tx-result (attempt-transact conn [(prov/enrich-tx {:note/id note-id
                                                                      :note/body note-body}
                                                                     tx-prov)]
                                                "edit note")
                    updated (when-let [db-after (:db-after tx-result)]
                              (when-let [eid (task-eid db-after task-id)]
                                (some-> (pull-task db-after eid)
                                        present-task)))]
                (cond
                  (:error tx-result) {:error (:error tx-result)}
                  updated (do
                            (log/infof "AUDIT task-edit-note user=%s task=%s"
                                       (or (:user/username actor) (:user/id actor))
                                       task-id)
                            {:task updated
                             :note {:note/id note-id
                                    :note/body note-body}})
                  :else (error 500 "Task not available after note edit")))))))))

(defn delete-note!
  [conn body actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            result (validate-delete-note db body actor)]
        (if-let [err (:error result)]
          {:error err}
          (let [{:keys [task-id author-id]} result
                note-id (latest-comment-note db task-id author-id)]
            (if-not note-id
              (error 404 "No deletable comment note found for this task")
              (let [tx-result (attempt-transact conn [[:db/retractEntity [:note/id note-id]]] "delete note")
                    updated (when-let [db-after (:db-after tx-result)]
                              (when-let [eid (task-eid db-after task-id)]
                                (some-> (pull-task db-after eid)
                                        present-task)))]
                (cond
                  (:error tx-result) {:error (:error tx-result)}
                  updated (do
                            (log/infof "AUDIT task-delete-note user=%s task=%s"
                                       (or (:user/username actor) (:user/id actor))
                                       task-id)
                            {:task updated
                             :note {:note/id note-id
                                    :note/deleted true}})
                  :else (error 500 "Task not available after note delete")))))))))

(defn delete-task!
  [conn task-id actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            {parsed-id :value id-err :error} (resolve-task-id db task-id)]
        (cond
          id-err (error 400 id-err)
          :else
          (if-let [eid (task-eid db parsed-id)]
            (let [task (some-> (pull-task db eid) present-task)
                  tx-result (attempt-transact conn [[:db/retractEntity eid]] "delete task")]
              (if-let [tx-error (:error tx-result)]
                {:error tx-error}
                (do
                  (log/infof "AUDIT task-delete user=%s task=%s"
                             (or (:user/username actor) (:user/id actor))
                             parsed-id)
                  {:task {:task/id parsed-id
                          :task/title (:task/title task)
                          :deleted true}})))
            (do
              (log/warnf "Delete failed: task-id %s not found. Available task ids=%s"
                         parsed-id
                         (->> (d/q '[:find ?id :where [_ :task/id ?id]] db)
                              (map first)
                              (take 10)))
              (error 404 "Task not found")))))))

(defn list-tags
  [conn]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            tags (->> (d/q '[:find (pull ?t [:tag/id :tag/name])
                             :where [?t :tag/id _]]
                           db)
                      (map first)
                      (sort-by (comp str/lower-case :tag/name))
                      vec)]
        {:tags tags})))

(defn create-tag!
  [conn body]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            {:keys [value] :as tag-result} (tag-name-field (param-value body :tag/name))]
        (cond
          (:error tag-result) (error 400 (:error tag-result))
          (existing-tag-by-name db value) (error 409 "Tag name already exists")
          :else
          (let [tag-id (UUID/randomUUID)
                base {:tag/id tag-id
                      :entity/type :entity.type/tag
                      :tag/name value}
                tx [(entity/with-ref db base)]
                tx-result (attempt-transact conn tx "create tag")]
            (if-let [tx-error (:error tx-result)]
              {:error tx-error}
              {:tag {:tag/id tag-id
                     :tag/name value}}))))))

(defn rename-tag!
  [conn tag-id body]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            {tag-name :value name-err :error} (tag-name-field (param-value body :tag/name))
            {parsed-id :value id-err :error} (normalize-uuid tag-id "tag id")]
        (cond
          id-err (error 400 id-err)
          name-err (error 400 name-err)
          :else
          (let [tag (tag-by-id db parsed-id)
                dup (existing-tag-by-name db tag-name)]
            (cond
              (nil? tag) (error 404 "Tag not found")
              (and dup (not= (:tag/id dup) parsed-id)) (error 409 "Tag name already exists")
              :else
              (let [tx-result (attempt-transact conn [[:db/add [:tag/id parsed-id] :tag/name tag-name]]
                                                "rename tag")]
                (if-let [tx-error (:error tx-result)]
                  {:error tx-error}
                  {:tag {:tag/id parsed-id
                         :tag/name tag-name}}))))))))

(defn delete-tag!
  [conn tag-id]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            {:keys [value id-err :error]} (normalize-uuid tag-id "tag id")]
        (cond
          id-err (error 400 id-err)
          :else
          (if-let [tag (tag-by-id db value)]
            (let [tasks-with-tag (d/q '[:find ?t
                                        :in $ ?tag-id
                                        :where [?t :task/tags ?tag]
                                               [?tag :tag/id ?tag-id]]
                                      db value)
                  tx-data (concat
                           (map (fn [[task-eid]]
                                  [:db/retract task-eid :task/tags [:tag/id value]])
                                tasks-with-tag)
                           [[:db/retractEntity [:tag/id value]]])
                  tx-result (attempt-transact conn tx-data "delete tag")]
              (if-let [tx-error (:error tx-result)]
                {:error tx-error}
                {:tag {:tag/id value
                       :tag/name (:tag/name tag)}}))
            (error 404 "Tag not found"))))))

(defn migrate-tags!
  "Migrate keyword-based tags to tag entities if present."
  [conn]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            tasks-with-kw (d/q '[:find ?t ?kw
                                 :where [?t :task/tags ?kw]
                                        [(keyword? ?kw)]]
                               db)]
        (when (seq tasks-with-kw)
          (let [kw->id (into {}
                             (for [[_ kw] tasks-with-kw]
                               [kw (UUID/randomUUID)]))
                create-tags (for [[kw id] kw->id]
                              {:tag/id id
                               :tag/name (-> kw name (str/capitalize))})
                tx-data (concat
                         create-tags
                         (mapcat (fn [[task kw]]
                                   (let [tid (get kw->id kw)]
                                     [[:db/add task :task/tags [:tag/id tid]]
                                      [:db/retract task :task/tags kw]]))
                                 tasks-with-kw))]
            (attempt-transact conn tx-data "migrate tags")))
        {:status :ok})))
