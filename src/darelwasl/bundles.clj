;; Library bundles for organizing related files.
(ns darelwasl.bundles
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [darelwasl.db :as db]
            [darelwasl.entity :as entity]
            [darelwasl.validation :as v]
            [darelwasl.workspace :as workspace])
  (:import (java.time Instant)
           (java.util Date UUID)))

(defn- error
  [status message & [details]]
  {:error {:status status
           :message message
           :details details}})

(defn- ensure-conn
  [conn]
  (when-not conn
    (error 500 "Database not ready")))

(def ^:private normalize-string v/normalize-string)

(def ^:private pull-pattern
  [:bundle/id
   :entity/ref
   :entity/type
   :bundle/title
   :bundle/slug
   :bundle/type
   :bundle/created-at
   :bundle/workspace
   {:bundle/created-by [:user/id :user/username :user/name]}
   {:bundle/files [:file/id
                   :file/name
                   :file/slug
                   :file/type
                   :file/mime
                   :file/size-bytes
                   :file/created-at]}])

(defn- workspace-id
  [value]
  (workspace/resolve-id value))

(defn- now-inst
  []
  (Date/from (Instant/now)))

(defn- slugify
  [s]
  (let [slug (-> s
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"(^-|-$)" ""))]
    (if (str/blank? slug) "bundle" slug)))

(defn- bundle-eids
  [db workspace]
  (map first (d/q '[:find ?e
                    :in $ ?workspace
                    :where [?e :bundle/id _]
                           [?e :bundle/workspace ?workspace]]
                  db workspace)))

(defn- pull-bundle
  [db eid]
  (d/pull db pull-pattern eid))

(defn- present-file
  [file]
  (when file
    (let [file-id (:file/id file)
          slug (:file/slug file)]
      (-> file
          (assoc :file/url (str "/api/files/" file-id "/content"))
          (assoc :file/ref (when slug (str "file:" slug)))
          (select-keys [:file/id
                        :file/name
                        :file/slug
                        :file/type
                        :file/mime
                        :file/size-bytes
                        :file/created-at
                        :file/url
                        :file/ref])))))

(defn- present-bundle
  [bundle]
  (when bundle
    (let [files (mapv present-file (:bundle/files bundle))]
      (-> bundle
          (assoc :bundle/files files)
          (assoc :bundle/count (count files))
          (select-keys [:bundle/id
                        :bundle/title
                        :bundle/slug
                        :bundle/type
                        :bundle/created-at
                        :bundle/created-by
                        :bundle/workspace
                        :bundle/count
                        :bundle/files
                        :entity/ref])))))

(defn- slug-exists?
  [db slug]
  (boolean
   (ffirst
    (d/q '[:find ?e :in $ ?slug :where [?e :bundle/slug ?slug]] db slug))))

(defn- unique-slug
  [db base]
  (let [base (slugify base)]
    (loop [suffix 0]
      (let [candidate (if (zero? suffix)
                        base
                        (str base "-" (inc suffix)))]
        (if (slug-exists? db candidate)
          (recur (inc suffix))
          candidate)))))

(defn- file-exists?
  [db file-id workspace]
  (boolean
   (ffirst
    (d/q '[:find ?e
           :in $ ?id ?workspace
           :where [?e :file/id ?id]
                  [?e :file/workspace ?workspace]]
         db file-id workspace))))

(defn list-bundles
  ([conn params] (list-bundles conn params nil))
  ([conn _params workspace]
   (or (ensure-conn conn)
       (let [db (d/db conn)
             workspace (workspace-id workspace)
             bundles (->> (bundle-eids db workspace)
                          (map #(pull-bundle db %))
                          (remove nil?)
                          (map present-bundle))
             sorted (sort-by :bundle/created-at #(compare %2 %1) bundles)]
         {:bundles (vec sorted)}))))

(defn create-bundle!
  [conn {:keys [title slug type files]} actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            {title :value title-err :error} (normalize-string title "title" {:required true
                                                                             :allow-blank? false})
            {slug-val :value slug-err :error} (normalize-string slug "slug" {:required false
                                                                             :allow-blank? false})
            workspace (workspace-id (:actor/workspace actor))
            file-ids (->> (or files [])
                          (map #(entity/resolve-id db :file/id % "file id"))
                          (map :value)
                          (remove nil?)
                          vec)
            missing (->> file-ids
                         (remove #(file-exists? db % workspace))
                         vec)]
        (cond
          title-err (error 400 title-err)
          slug-err (error 400 slug-err)
          (empty? file-ids) (error 400 "Bundle requires at least one file")
          (seq missing) (error 404 "Some files were not found" {:missing missing})
          :else
          (let [final-slug (unique-slug db (or slug-val title))
                bundle-id (UUID/randomUUID)
                created-at (now-inst)
                created-by (some-> actor :user/id)
                base {:bundle/id bundle-id
                      :entity/type :entity.type/library-bundle
                      :bundle/title title
                      :bundle/slug final-slug
                      :bundle/type (or type :bundle.type/site-screenshot)
                      :bundle/created-at created-at
                      :bundle/workspace workspace
                      :bundle/files (mapv (fn [id] [:file/id id]) file-ids)}
                base (entity/with-ref db base)
                tx-data (cond-> base
                          created-by (assoc :bundle/created-by [:user/id created-by]))]
            (try
              (let [tx-res (db/transact! conn {:tx-data [tx-data]})
                    db-after (:db-after tx-res)
                    eid (ffirst (d/q '[:find ?e
                                      :in $ ?id ?workspace
                                      :where [?e :bundle/id ?id]
                                             [?e :bundle/workspace ?workspace]]
                                    db-after bundle-id workspace))
                    bundle (present-bundle (pull-bundle db-after eid))]
                {:bundle bundle})
              (catch Exception e
                (error 500 "Failed to create bundle" (.getMessage e)))))))))
