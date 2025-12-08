(ns darelwasl.tasks
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.entity :as entity]
            [darelwasl.schema :as schema])
  (:import (java.time Instant)
           (java.time.format DateTimeFormatter)
           (java.util Date UUID)))

(def ^:private iso-formatter DateTimeFormatter/ISO_INSTANT)

(def ^:private task-schema-entry
  (delay (some #(when (= (:id %) :cap/schema/task) %) (schema/read-registry))))

(def ^:private allowed-statuses
  (delay (or (some-> @task-schema-entry (get-in [:enums :task/status]) set)
             #{:todo :in-progress :done})))

(def ^:private allowed-priorities
  (delay (or (some-> @task-schema-entry (get-in [:enums :task/priority]) set)
             #{:low :medium :high})))

(def ^:private priority-rank
  {:high 3 :medium 2 :low 1})

(def ^:private pull-pattern
  [:task/id
   :entity/type
   :task/title
   :task/description
   :task/status
   {:task/assignee [:user/id :user/username :user/name]}
   :task/due-date
   :task/priority
   {:task/tags [:tag/id :tag/name]}
   :task/archived?
   :task/extended?])

(defn- format-inst
  [^Date inst]
  (when inst
    (.format iso-formatter (.toInstant inst))))

(defn- param-value
  "Fetch a value from a map regardless of keyword/string key usage."
  [m k]
  (let [kname (name k)]
    (or (get m k)
        (get m (keyword kname))
        (get m kname))))

(defn- normalize-string-field
  [v label {:keys [required allow-blank?]}]
  (let [raw (if (sequential? v) (first v) v)]
    (cond
      (and required (nil? raw)) {:error (str label " is required")}
      (nil? raw) {:value nil}
      (not (string? raw)) {:error (str label " must be a string")}
      :else (let [s (str/trim raw)]
              (cond
                (and (not allow-blank?) (str/blank? s)) {:error (str label " cannot be blank")}
                :else {:value s})))))

(defn- to-keyword
  [v]
  (cond
    (keyword? v) v
    (string? v) (let [trimmed (str/trim v)]
                  (when-not (str/blank? trimmed)
                    (keyword trimmed)))
    :else nil))

(defn- normalize-enum
  [value allowed label]
  (let [raw (if (sequential? value) (first value) value)]
    (cond
      (nil? raw) {:value nil}
      :else
      (let [kw (to-keyword raw)]
        (cond
          (nil? kw) {:error (str "Invalid " label)}
          (not (contains? allowed kw)) {:error (str "Unsupported " label ": " (name kw))}
          :else {:value kw})))))

(defn- normalize-uuid
  [value label]
  (let [raw (if (sequential? value) (first value) value)]
    (cond
      (nil? raw) {:value nil}
      (uuid? raw) {:value raw}
      (string? raw) (try
                      {:value (UUID/fromString (str/trim raw))}
                      (catch Exception _
                        {:error (str "Invalid " label)}))
      :else {:error (str "Invalid " label)})))

(defn- normalize-boolean
  [value label {:keys [default allow-all?]}]
  (let [raw (if (sequential? value) (first value) value)]
    (cond
      (keyword? raw) (recur (name raw) label {:default default
                                              :allow-all? allow-all?})
      (nil? raw) {:value default}
      (boolean? raw) {:value raw}
      (string? raw) (let [v (str/lower-case (str/trim raw))]
                      (cond
                        (#{"true" "1" "yes" "on"} v) {:value true}
                        (#{"false" "0" "no" "off"} v) {:value false}
                        (and allow-all? (= v "all")) {:value :all}
                        :else {:error (str label " must be " (if allow-all?
                                                               "true, false, or all"
                                                               "true or false"))}))
      :else {:error (str label " must be " (if allow-all?
                                             "true, false, or all"
                                             "true or false"))})))

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

(defn- task-eid
  [db task-id]
  (when task-id
    (-> (d/q '[:find ?e
               :in $ ?id
               :where [?e :task/id ?id]]
             db task-id)
        ffirst)))

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
  (-> task
      (update :task/due-date format-inst)
      (update :task/updated-at format-inst)
      (update :task/tags (fn [tags]
                           (when tags
                             (->> tags
                                  (sort-by (comp str/lower-case :tag/name))
                                  vec))))))

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
  (let [{status :value status-err :error} (normalize-enum (param-value params :status) @allowed-statuses "status")
        {priority :value priority-err :error} (normalize-enum (param-value params :priority) @allowed-priorities "priority")
        {tag :value tag-err :error} (normalize-uuid (param-value params :tag) "tag")
        {assignee :value assignee-err :error} (normalize-uuid (param-value params :assignee) "assignee")
        {archived :value archived-err :error} (normalize-boolean (param-value params :archived) "archived" {:default false
                                                                                                          :allow-all? true})
        raw-sort (param-value params :sort)
        sort-key (cond
                   (nil? raw-sort) :updated
                   :else (case (to-keyword raw-sort)
                           (:due :task/due-date) :due
                           (:priority :task/priority) :priority
                           (:updated :task/updated-at) :updated
                           nil))
        default-order (if (= sort-key :due) :asc :desc)
        raw-order (param-value params :order)
        order-key (cond
                    (nil? raw-order) default-order
                    :else (case (to-keyword raw-order)
                            (:asc :ascending) :asc
                            (:desc :descending) :desc
                            nil))]
    (cond
      status-err (error 400 status-err)
      priority-err (error 400 priority-err)
      tag-err (error 400 tag-err)
      assignee-err (error 400 assignee-err)
      archived-err (error 400 archived-err)
      (and raw-sort (nil? sort-key)) (error 400 "Invalid sort; expected due, priority, or updated")
      (and raw-order (nil? order-key)) (error 400 "Invalid order; expected asc or desc")
      :else {:filters {:status status
                       :priority priority
                       :tag tag
                       :assignee assignee
                       :archived archived
                       :sort sort-key
                       :order order-key}})))

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

(defn list-tasks
  "List tasks with optional filters and sort/order parameters."
  [conn params]
  (or (ensure-conn conn)
      (let [normalized (normalize-list-params params)
            filters (:filters normalized)
            normalized-error (:error normalized)]
        (if normalized-error
          {:error normalized-error}
          (let [db (d/db conn)
                eids (entity/eids-by-type db :entity.type/task)
                tasks (->> eids
                           (map #(pull-task db %))
                           (remove nil?)
                           (filter #(filter-task % filters)))
                sorted (sort-tasks tasks filters)]
            {:tasks (->> sorted
                         (map present-task)
                         vec)})))))

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
        {priority :value priority-err :error} (normalize-enum (param-value body :task/priority) @allowed-priorities "priority")
        {:keys [value] :as tags-result} (normalize-tags db (param-value body :task/tags) {:default-on-nil #{}})
        {due-date :value due-err :error} (normalize-instant (param-value body :task/due-date) "due date")
        {archived? :value archived-err :error} (normalize-boolean (param-value body :task/archived?) "archived" {:default false})
        {extended? :value extended-err :error} (normalize-boolean (param-value body :task/extended?) "extended" {:default false})
        {assignee-id :value assignee-err :error} (normalize-uuid (param-value body :task/assignee) "assignee")]
    (cond
      title-err (error 400 title-err)
      desc-err (error 400 desc-err)
      status-err (error 400 status-err)
      priority-err (error 400 priority-err)
      (:error tags-result) (error 400 (:error tags-result))
      due-err (error 400 due-err)
      archived-err (error 400 archived-err)
      extended-err (error 400 extended-err)
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
                data (cond-> base
                       due-date (assoc :task/due-date due-date))]
            {:data data}))))))

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
  (let [{task-id :value id-err :error} (normalize-uuid task-id "task id")
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
  (let [{task-id :value id-err :error} (normalize-uuid task-id "task id")
        {status :value status-err :error} (normalize-enum (param-value body :task/status) @allowed-statuses "status")]
    (cond
      id-err (error 400 id-err)
      status-err (error 400 status-err)
      :else
      (if (task-eid db task-id)
        {:task-id task-id
         :updates {:task/id task-id
                   :task/status status}}
        (error 404 "Task not found")))))

(defn- validate-assignee-update
  [db task-id body]
  (let [{task-id :value id-err :error} (normalize-uuid task-id "task id")
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
  (let [{task-id :value id-err :error} (normalize-uuid task-id "task id")
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
  (let [{task-id :value id-err :error} (normalize-uuid task-id "task id")
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
  (let [{task-id :value id-err :error} (normalize-uuid task-id "task id")
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

(defn create-task!
  [conn body actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            result (validate-create db body)]
        (if-let [err (:error result)]
          {:error err}
          (let [tx-data [(:data result)]
                tx-result (attempt-transact conn tx-data "create task")]
            (if-let [tx-error (:error tx-result)]
              {:error tx-error}
              (let [db-after (:db-after tx-result)
                    task (when-let [eid (task-eid db-after (:task/id (:data result)))]
                           (some-> (pull-task db-after eid)
                                   present-task))]
                (if task
                  (do
                    (log/infof "AUDIT task-create user=%s task=%s status=%s"
                               (or (:user/username actor) (:user/id actor))
                               (:task/id (:data result))
                               (:task/status (:data result)))
                    {:task task})
                  (error 500 "Task not available after create")))))))))

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
                tx-result (attempt-transact conn tx-data "update task")
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
          (let [{:keys [task-id updates]} result
                tx-result (attempt-transact conn [updates] "set task status")
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
                tx-result (attempt-transact conn [updates] "assign task")
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
                tx-result (attempt-transact conn tx-data "update task due date")
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
                tx-result (attempt-transact conn tx-data "update task tags")
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
                tx-result (attempt-transact conn [updates] "archive task")
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

(defn delete-task!
  [conn task-id actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            {parsed-id :value id-err :error} (normalize-uuid task-id "task id")]
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
            (error 404 "Task not found"))))))

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
                tx [{:tag/id tag-id
                     :entity/type :entity.type/tag
                     :tag/name value}]
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
