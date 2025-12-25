(ns darelwasl.users
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.entity :as entity]
            [darelwasl.validation :as v])
  (:import (java.util UUID)))

(defn- error
  [status message & [details]]
  {:error {:status status
           :message message
           :details details}})

(defn- ensure-conn
  [conn]
  (when-not conn
    (error 500 "Database not ready")))

(def ^:private param-value v/param-value)
(def ^:private normalize-string v/normalize-string)
(def ^:private normalize-uuid v/normalize-uuid)

(def ^:private user-pull-pattern
  [:user/id
   :entity/ref
   :entity/type
   :user/username
   :user/name
   :user/password
   :user/roles])

(def ^:private public-keys
  [:user/id :entity/ref :user/username :user/name :user/roles])

(defn- sanitize-user
  [user]
  (select-keys user public-keys))

(defn- user-eids
  [db]
  (map first (d/q '[:find ?e :where [?e :user/id _]] db)))

(defn- pull-user
  [db eid]
  (d/pull db user-pull-pattern eid))

(defn- user-eid-by-id
  [db user-id]
  (ffirst
   (d/q '[:find ?e :in $ ?id :where [?e :user/id ?id]] db user-id)))

(defn- user-eid-by-username
  [db username]
  (ffirst
   (d/q '[:find ?e :in $ ?u :where [?e :user/username ?u]] db username)))

(defn- role->keyword
  [role]
  (cond
    (keyword? role) role
    (string? role) (let [s (-> role str/trim (str/replace #"^:" ""))]
                     (when-not (str/blank? s)
                       (keyword s)))
    :else nil))

(defn- normalize-roles
  [value]
  (cond
    (nil? value) {:value nil}
    (or (sequential? value) (set? value))
    (let [roles (map role->keyword value)
          invalid (remove some? roles)]
      (if (seq invalid)
        {:error "Roles must be keywords"}
        {:value (->> roles (remove nil?) set)}))
    (string? value) (normalize-roles [value])
    :else {:error "Roles must be a list of keywords"}))

(defn- normalize-optional-string
  [value label]
  (let [{:keys [value error]} (normalize-string value label {:required false :allow-blank? true})]
    (cond
      error {:error error}
      (str/blank? (str value)) {:value nil}
      :else {:value value})))

(defn list-users
  [conn]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            users (->> (user-eids db)
                       (map #(pull-user db %))
                       (remove nil?)
                       (map sanitize-user)
                       (sort-by (comp str/lower-case str :user/username))
                       vec)]
        {:users users})))

(defn user-by-username
  [db username]
  (when (and db (string? username) (not (str/blank? username)))
    (when-let [eid (user-eid-by-username db username)]
      (pull-user db eid))))

(defn create-user!
  [conn params actor]
  (or (ensure-conn conn)
      (let [{uname :value uname-err :error} (normalize-string (param-value params :user/username)
                                                              "Username"
                                                              {:required true})
            {pwd :value pwd-err :error} (normalize-string (param-value params :user/password)
                                                          "Password"
                                                          {:required true})
            {name :value name-err :error} (normalize-optional-string (param-value params :user/name)
                                                                      "Name")
            {roles :value roles-err :error} (normalize-roles (param-value params :user/roles))]
        (cond
          uname-err (error 400 uname-err)
          pwd-err (error 400 pwd-err)
          name-err (error 400 name-err)
          roles-err (error 400 roles-err)
          :else
          (let [db (d/db conn)
                existing (user-eid-by-username db uname)]
            (if existing
              (error 409 "Username already exists")
              (let [user-id (UUID/randomUUID)
                    roles (or roles #{})
                    base {:user/id user-id
                          :entity/type :entity.type/user
                          :user/username uname
                          :user/password pwd}
                    base (cond-> base
                           (seq roles) (assoc :user/roles roles)
                           name (assoc :user/name name))
                    base (entity/with-ref db base)
                    tx-data [base]]
                (try
                  (d/transact conn {:tx-data tx-data})
                  (when actor
                    (log/infof "AUDIT user-create user=%s target=%s"
                               (or (:user/username actor) (:user/id actor))
                               uname))
                  (if-let [user (user-by-username (d/db conn) uname)]
                    {:user (sanitize-user user)}
                    {:user (sanitize-user {:user/id user-id
                                           :user/username uname
                                           :user/name name
                                           :user/roles roles})})
                  (catch Exception e
                    (error 500 "Unable to create user" {:exception (.getMessage e)}))))))))))

(defn update-user!
  [conn user-id params actor]
  (or (ensure-conn conn)
      (let [{uid :value uid-err :error} (normalize-uuid user-id "user/id")
            {uname :value uname-err :error} (normalize-string (param-value params :user/username)
                                                              "Username"
                                                              {:required false})
            {pwd :value pwd-err :error} (normalize-string (param-value params :user/password)
                                                          "Password"
                                                          {:required false})
            {name :value name-err :error} (normalize-optional-string (param-value params :user/name)
                                                                      "Name")
            {roles :value roles-err :error} (normalize-roles (param-value params :user/roles))]
        (cond
          uid-err (error 400 uid-err)
          uname-err (error 400 uname-err)
          pwd-err (error 400 pwd-err)
          name-err (error 400 name-err)
          roles-err (error 400 roles-err)
          :else
          (let [db (d/db conn)
                eid (user-eid-by-id db uid)
                current (when eid (pull-user db eid))]
            (cond
              (nil? eid) (error 404 "User not found")
              (and uname
                   (not= uname (:user/username current))
                   (user-eid-by-username db uname))
              (error 409 "Username already exists")
              (and pwd (str/blank? (str pwd)))
              (error 400 "Password cannot be blank")
              :else
              (let [updates (cond-> []
                              uname (conj [:db/add [:user/id uid] :user/username uname])
                              pwd (conj [:db/add [:user/id uid] :user/password pwd])
                              (some? name) (conj [:db/add [:user/id uid] :user/name name]))
                    existing-roles (set (:user/roles current))
                    updates (if (nil? roles)
                              updates
                              (let [next-roles (set (or roles #{}))
                                    retracts (mapv (fn [role]
                                                     [:db/retract [:user/id uid] :user/roles role])
                                                   (set/difference existing-roles next-roles))
                                    adds (mapv (fn [role]
                                                 [:db/add [:user/id uid] :user/roles role])
                                               (set/difference next-roles existing-roles))]
                                (into (vec updates) (concat retracts adds))))]
                (if (empty? updates)
                  (error 400 "No updates provided")
                  (try
                    (d/transact conn {:tx-data updates})
                    (when actor
                      (log/infof "AUDIT user-update user=%s target=%s"
                                 (or (:user/username actor) (:user/id actor))
                                 (or uname (:user/username current))))
                    (if-let [user (pull-user (d/db conn) eid)]
                      {:user (sanitize-user user)}
                      {:user (sanitize-user current)})
                    (catch Exception e
                      (error 500 "Unable to update user" {:exception (.getMessage e)})))))))))))

(defn delete-user!
  [conn user-id actor]
  (or (ensure-conn conn)
      (let [{uid :value uid-err :error} (normalize-uuid user-id "user/id")]
        (cond
          uid-err (error 400 uid-err)
          (and actor (= uid (:user/id actor))) (error 400 "Cannot delete the active user")
          :else
          (let [db (d/db conn)
                eid (user-eid-by-id db uid)
                current (when eid (pull-user db eid))
                assigned (ffirst (d/q '[:find (count ?t)
                                        :in $ ?uid
                                        :where [?u :user/id ?uid]
                                               [?t :task/assignee ?u]]
                                      db uid))]
            (cond
              (nil? eid) (error 404 "User not found")
              (pos? (or assigned 0)) (error 409 "User is assigned to tasks; reassign tasks before deleting")
              :else
              (try
                (d/transact conn {:tx-data [[:db/retractEntity eid]]})
                (when actor
                  (log/infof "AUDIT user-delete user=%s target=%s"
                             (or (:user/username actor) (:user/id actor))
                             (:user/username current)))
                {:user/id uid
                 :deleted true}
                (catch Exception e
                  (error 500 "Unable to delete user" {:exception (.getMessage e)})))))))))
