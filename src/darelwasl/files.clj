;; Local file library management.
(ns darelwasl.files
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.validation :as v])
  (:import (java.io File FileInputStream)
           (java.math BigInteger)
           (java.security MessageDigest)
           (java.time Instant)
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

(def ^:private param-value v/param-value)
(def ^:private normalize-uuid v/normalize-uuid)
(def ^:private normalize-string v/normalize-string)

(def ^:private pull-pattern
  [:file/id
   :entity/type
   :file/name
   :file/slug
   :file/type
   :file/mime
   :file/size-bytes
   :file/checksum
   :file/storage-path
   :file/created-at
   {:file/created-by [:user/id :user/username :user/name]}])

(defn- now-inst
  []
  (Date/from (Instant/now)))

(defn- slugify
  [s]
  (let [slug (-> s
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"(^-|-$)" ""))]
    (if (str/blank? slug) "file" slug)))

(defn- reference->slug
  [reference]
  (let [ref (some-> reference str/trim)]
    (when-not (str/blank? ref)
      (if (str/starts-with? ref "file:")
        (some-> (subs ref 5) str/trim)
        ref))))

(defn- file-type
  [mime]
  (cond
    (and mime (str/starts-with? mime "image/")) :file.type/image
    (= mime "application/pdf") :file.type/pdf
    :else nil))

(defn- sha256
  [^File file]
  (with-open [is (FileInputStream. file)]
    (let [buffer (byte-array 8192)
          digest (MessageDigest/getInstance "SHA-256")]
      (loop []
        (let [read (.read is buffer)]
          (when (pos? read)
            (.update digest buffer 0 read)
            (recur))))
      (format "%064x" (BigInteger. 1 (.digest digest))))))

(defn- ensure-storage-dir
  [storage-dir]
  (cond
    (or (nil? storage-dir) (str/blank? (str storage-dir)))
    (error 500 "File storage directory not configured")

    :else
    (let [root (io/file storage-dir)]
      (when-not (.exists root)
        (.mkdirs root))
      (when-not (.isDirectory root)
        (error 500 "File storage path is not a directory"))
      root)))

(defn- file-eids
  [db]
  (map first (d/q '[:find ?e :where [?e :file/id _]] db)))

(defn- pull-file
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
                        :file/checksum
                        :file/created-at
                        :file/created-by
                        :file/url
                        :file/ref])))))

(defn- slug-exists?
  [db slug]
  (boolean
   (ffirst
    (d/q '[:find ?e :in $ ?slug :where [?e :file/slug ?slug]] db slug))))

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

(defn- file-eid-by-id
  [db file-id]
  (ffirst
   (d/q '[:find ?e :in $ ?id :where [?e :file/id ?id]] db file-id)))

(defn list-files
  [conn params]
  (or (ensure-conn conn)
      (let [{q :value q-err :error} (normalize-string (param-value params :q) "query" {:required false
                                                                                        :allow-blank? true})]
        (if q-err
          (error 400 q-err)
          (let [db (d/db conn)
                items (->> (file-eids db)
                           (map #(pull-file db %))
                           (remove nil?)
                           (map present-file))
                needle (some-> q str/lower-case str/trim)
                filtered (if (seq needle)
                           (filter (fn [f]
                                     (let [name (some-> (:file/name f) str/lower-case)
                                           slug (some-> (:file/slug f) str/lower-case)]
                                       (or (and name (str/includes? name needle))
                                           (and slug (str/includes? slug needle)))))
                                   items)
                           items)
                sorted (sort-by :file/created-at #(compare %2 %1) filtered)]
            {:files (vec sorted)})))))

(defn- storage-filename
  [file-id filename]
  (let [ext (when (and (string? filename) (str/includes? filename "."))
              (some-> filename
                      (str/split #"\.")
                      last
                      str/trim))]
    (if (and ext (not (str/blank? ext)))
      (str file-id "." ext)
      (str file-id))))

(defn create-file!
  [conn {:keys [file slug]} actor storage-dir]
  (or (ensure-conn conn)
      (let [upload file
            filename (:filename upload)
            mime (:content-type upload)
            tempfile (:tempfile upload)
            size (or (:size upload) (when tempfile (.length ^File tempfile)))
            ftype (file-type mime)]
        (cond
          (nil? upload) (error 400 "File is required")
          (nil? tempfile) (error 400 "Upload missing file data")
          (nil? ftype) (error 400 "Only images and PDFs are supported")
          :else
          (let [db (d/db conn)
                {slug-val :value slug-err :error} (normalize-string slug "slug" {:required false
                                                                                 :allow-blank? false})
                base-slug (or slug-val filename "file")
                final-slug (unique-slug db base-slug)
                root (ensure-storage-dir storage-dir)]
            (cond
              slug-err (error 400 slug-err)
              (:error root) {:error (:error root)}
              :else
              (let [file-id (UUID/randomUUID)
                    storage-name (storage-filename file-id filename)
                    storage-path (.getPath (io/file ^File root storage-name))
                    checksum (sha256 tempfile)
                    created-at (now-inst)
                    created-by (some-> actor :user/id)
                    tx-data (cond-> {:file/id file-id
                                     :entity/type :entity.type/file
                                     :file/name (or filename storage-name)
                                     :file/slug final-slug
                                     :file/type ftype
                                     :file/mime mime
                                     :file/size-bytes (long (or size 0))
                                     :file/checksum checksum
                                     :file/storage-path storage-name
                                     :file/created-at created-at}
                              created-by (assoc :file/created-by [:user/id created-by]))]
                (try
                  (io/copy tempfile (io/file storage-path))
                  (let [tx-res (d/transact conn {:tx-data [tx-data]})
                        db-after (:db-after tx-res)
                        eid (file-eid-by-id db-after file-id)
                        file (present-file (pull-file db-after eid))]
                    {:file file})
                  (catch Exception e
                    (log/error e "Failed to store uploaded file")
                    (try
                      (io/delete-file (io/file storage-path) true)
                      (catch Exception _))
                    (error 500 "Failed to store file" (.getMessage e)))))))))))

(defn update-file!
  [conn file-id {:keys [slug ref]} _actor]
  (or (ensure-conn conn)
      (let [{id :value id-err :error} (normalize-uuid file-id "file id")]
        (if id-err
          (error 400 id-err)
          (let [db (d/db conn)
                eid (file-eid-by-id db id)
                file (pull-file db eid)
                requested (or (reference->slug ref) slug)
                {slug-val :value slug-err :error} (normalize-string requested "slug" {:required true
                                                                                      :allow-blank? false})
                conflict? (and slug-val
                               (not= slug-val (:file/slug file))
                               (slug-exists? db slug-val))]
            (cond
              (nil? eid) (error 404 "File not found")
              slug-err (error 400 slug-err)
              conflict? (error 409 "Slug already in use")
              :else
              (try
                (let [tx-res (d/transact conn {:tx-data [[:db/add eid :file/slug slug-val]]})
                      db-after (:db-after tx-res)
                      updated (present-file (pull-file db-after eid))]
                  {:file updated})
                (catch Exception e
                  (log/error e "Failed to update file")
                  (error 500 "Failed to update file")))))))))

(defn delete-file!
  [conn file-id storage-dir]
  (or (ensure-conn conn)
      (let [{id :value id-err :error} (normalize-uuid file-id "file id")]
        (if id-err
          (error 400 id-err)
          (let [db (d/db conn)
                eid (file-eid-by-id db id)]
            (if-not eid
              (error 404 "File not found")
              (let [{:file/keys [storage-path]} (pull-file db eid)
                    root (ensure-storage-dir storage-dir)]
                (if-let [err (:error root)]
                  {:error err}
                  (do
                    (when storage-path
                      (try
                        (io/delete-file (io/file ^File root storage-path) true)
                        (catch Exception e
                          (log/warn e "Failed to delete file from storage"))))
                    (try
                      (d/transact conn {:tx-data [[:db/retractEntity eid]]})
                      {:file/id id}
                      (catch Exception e
                        (log/error e "Failed to delete file metadata")
                        (error 500 "Failed to delete file"))))))))))))

(defn fetch-file
  [conn file-id]
  (or (ensure-conn conn)
      (let [{id :value id-err :error} (normalize-uuid file-id "file id")]
        (if id-err
          (error 400 id-err)
          (let [db (d/db conn)
                eid (file-eid-by-id db id)
                file (pull-file db eid)]
            (if file
              {:file file}
              (error 404 "File not found")))))))
