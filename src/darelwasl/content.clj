(ns darelwasl.content
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.entity :as entity])
  (:import (java.util UUID)))

;; -------- Shared helpers --------

(defn- error
  [status message & [details]]
  {:error {:status status
           :message message
           :details details}})

(defn- ensure-conn
  [conn]
  (when-not conn
    (error 500 "Database not ready")))

(defn- param-value
  [m k]
  (let [kname (name k)]
    (or (get m k)
        (get m (keyword kname))
        (get m kname))))

(defn- normalize-string
  [v label {:keys [required allow-blank?] :or {allow-blank? false}}]
  (let [raw (if (sequential? v) (first v) v)]
    (cond
      (and required (nil? raw)) {:error (str label " is required")}
      (nil? raw) {:value nil}
      (not (string? raw)) {:error (str label " must be a string")}
      :else (let [s (str/trim raw)]
              (cond
                (and (not allow-blank?) (str/blank? s)) {:error (str label " cannot be blank")}
                :else {:value s})))))

(defn- normalize-uuid
  [v label]
  (let [raw (if (sequential? v) (first v) v)]
    (cond
      (nil? raw) {:value nil}
      (uuid? raw) {:value raw}
      (string? raw) (try
                      {:value (UUID/fromString (str/trim raw))}
                      (catch Exception _
                        {:error (str "Invalid " label)}))
      :else {:error (str "Invalid " label)})))

(defn- normalize-boolean
  [v label {:keys [default]}]
  (let [raw (if (sequential? v) (first v) v)]
    (cond
      (nil? raw) {:value default}
      (boolean? raw) {:value raw}
      (string? raw) (let [s (str/lower-case (str/trim raw))]
                      (cond
                        (#{"true" "1" "yes" "on"} s) {:value true}
                        (#{"false" "0" "no" "off"} s) {:value false}
                        :else {:error (str label " must be true or false")}))
      :else {:error (str label " must be true or false")})))

(defn- normalize-long
  [v label]
  (let [raw (if (sequential? v) (first v) v)]
    (cond
      (nil? raw) {:value nil}
      (integer? raw) {:value (long raw)}
      (number? raw) {:value (long raw)}
      (string? raw) (try
                      {:value (Long/parseLong (str/trim raw))}
                      (catch Exception _
                        {:error (str label " must be a number")}))
      :else {:error (str label " must be a number")})))

(defn- normalize-keyword
  [v label allowed]
  (let [raw (if (sequential? v) (first v) v)
        kw (cond
             (keyword? raw) raw
             (string? raw) (let [s (-> raw str/trim (str/replace #"^:" ""))]
                             (when-not (str/blank? s)
                               (keyword s)))
             :else nil)]
    (cond
      (nil? kw) {:error (str "Invalid " label)}
      (and allowed (not (contains? allowed kw))) {:error (str "Unsupported " label)}
      :else {:value kw})))

(defn- slugify
  [s]
  (let [slug (-> s
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"(^-|-$)" ""))]
    (if (str/blank? slug) "item" slug)))

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

;; -------- Pull helpers --------

(def allowed-block-types #{:hero :section :rich-text :feature :cta :list})

(defn- present-tag
  [tag]
  (when tag
    (select-keys tag [:content.tag/id :content.tag/name :content.tag/slug :content.tag/description])))

(defn- present-block
  [block]
  (when block
    (-> block
        (update :content.block/page #(when-let [p (:content.page/id %)] [:content.page/id p]))
        (update :content.block/tag #(vec (map (fn [t] [:content.tag/id (:content.tag/id t)]) %)))
        (select-keys [:content.block/id
                      :content.block/page
                      :content.block/type
                      :content.block/title
                      :content.block/body
                      :content.block/media-ref
                      :content.block/slug
                      :content.block/order
                      :content.block/visible?
                      :content.block/tag]))))

(defn- present-page
  [page]
  (when page
    (-> page
        (update :content.page/tag #(vec (map (fn [t] [:content.tag/id (:content.tag/id t)]) %)))
        (update :content.page/blocks #(vec (map (fn [b] [:content.block/id (:content.block/id b)]) %)))
        (select-keys [:content.page/id
                      :content.page/title
                      :content.page/path
                      :content.page/summary
                      :content.page/navigation-order
                      :content.page/visible?
                      :content.page/tag
                      :content.page/blocks]))))

(defn- tag-by-id
  [db tag-id]
  (when tag-id
    (-> (d/q '[:find (pull ?t [:content.tag/id :content.tag/name :content.tag/slug :content.tag/description])
               :in $ ?id
               :where [?t :content.tag/id ?id]]
             db tag-id)
        ffirst)))

(defn- page-by-id
  [db page-id]
  (when page-id
    (-> (d/q '[:find (pull ?p [:content.page/id
                               :content.page/title
                               :content.page/path
                               :content.page/summary
                               :content.page/navigation-order
                               :content.page/visible?
                               {:content.page/tag [:content.tag/id]}
                               {:content.page/blocks [:content.block/id]}])
               :in $ ?id
               :where [?p :content.page/id ?id]]
             db page-id)
        ffirst)))

(defn- block-by-id
  [db block-id]
  (when block-id
    (-> (d/q '[:find (pull ?b [:content.block/id
                               {:content.block/page [:content.page/id]}
                               :content.block/type
                               :content.block/title
                               :content.block/body
                               :content.block/media-ref
                               :content.block/slug
                               :content.block/order
                               :content.block/visible?
                               {:content.block/tag [:content.tag/id]}])
               :in $ ?id
               :where [?b :content.block/id ?id]]
             db block-id)
        ffirst)))

(defn- tag-id-set
  [db tag-ids]
  (let [tags (->> tag-ids
                  (map (fn [tid] [tid (tag-by-id db tid)]))
                  (into {}))
        missing (->> tags (keep (fn [[tid t]] (when-not t tid))) seq)]
    (if missing
      {:error (str "Unknown tag(s): " (str/join ", " (map str missing)))}
      {:tags (vals tags)
       :tag-ids tag-ids})))

;; -------- Tag operations --------

(defn- validate-tag-create
  [db body]
  (let [{name :value name-err :error} (normalize-string (param-value body :content.tag/name) "name" {:required true})
        {slug :value slug-err :error} (normalize-string (param-value body :content.tag/slug) "slug" {:required false
                                                                                                     :allow-blank? false})
        {description :value desc-err :error} (normalize-string (param-value body :content.tag/description) "description" {:required false
                                                                                                                           :allow-blank? true})
        {id :value id-err :error} (normalize-uuid (param-value body :content.tag/id) "tag id")]
    (cond
      name-err (error 400 name-err)
      slug-err (error 400 slug-err)
      desc-err (error 400 desc-err)
      id-err (error 400 id-err)
      :else
      (let [slug' (or slug (slugify name))
            conflict? (d/q '[:find ?e :in $ ?slug :where [?e :content.tag/slug ?slug]] db slug')]
        (if (seq conflict?)
          (error 409 "Slug already exists")
          (let [tag (cond-> {:content.tag/id (or id (UUID/randomUUID))
                             :entity/type :entity.type/content-tag
                             :content.tag/name name
                             :content.tag/slug slug'}
                      description (assoc :content.tag/description description))]
            {:tag tag}))))))

(defn- validate-tag-update
  [db tag-id body]
  (let [{tid :value id-err :error} (normalize-uuid tag-id "tag id")
        {name :value name-err :error} (normalize-string (param-value body :content.tag/name) "name" {:required false})
        {slug :value slug-err :error} (normalize-string (param-value body :content.tag/slug) "slug" {:required false})
        {description :value desc-err :error} (normalize-string (param-value body :content.tag/description) "description" {:required false
                                                                                                                           :allow-blank? true})]
    (cond
      id-err (error 400 id-err)
      slug-err (error 400 slug-err)
      name-err (error 400 name-err)
      desc-err (error 400 desc-err)
      :else
      (if-let [_ (tag-by-id db tid)]
        (let [slug' (or slug (when name (slugify name)))
              conflict? (when slug'
                          (d/q '[:find ?e :in $ ?slug ?tid
                                 :where [?e :content.tag/slug ?slug]
                                        [(not= ?e ?tid)]]
                               db slug' tid))]
          (if (seq conflict?)
            (error 409 "Slug already exists")
            {:tag-id tid
             :updates (cond-> {:content.tag/id tid}
                        name (assoc :content.tag/name name)
                        slug' (assoc :content.tag/slug slug')
                        (some? description) (assoc :content.tag/description description))}))
        (error 404 "Tag not found")))))

(defn list-tags
  [conn]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            tags (->> (entity/eids-by-type db :entity.type/content-tag)
                      (map #(present-tag (d/pull db [:content.tag/id
                                                     :content.tag/name
                                                     :content.tag/slug
                                                     :content.tag/description] %)))
                      (remove nil?)
                      vec)]
        {:tags tags})))

(defn create-tag!
  [conn body actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            {:keys [error tag]} (validate-tag-create db body)]
        (if error
          {:error error}
          (let [tx (attempt-transact conn [tag] "create content tag")
                created (when-let [db-after (:db-after tx)]
                          (some-> (tag-by-id db-after (:content.tag/id tag))
                                  present-tag))]
            (cond
              (:error tx) {:error (:error tx)}
              created (do
                        (log/infof "AUDIT content-tag-create user=%s tag=%s"
                                   (or (:user/username actor) (:user/id actor))
                                   (:content.tag/id created))
                        {:tag created})
              :else (error 500 "Content tag not available after create")))))))

(defn update-tag!
  [conn tag-id body actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            {:keys [error updates]} (validate-tag-update db tag-id body)]
        (if error
          {:error error}
          (let [tx (attempt-transact conn [updates] "update content tag")
                updated (when-let [db-after (:db-after tx)]
                          (some-> (tag-by-id db-after (:content.tag/id updates))
                                  present-tag))]
            (cond
              (:error tx) {:error (:error tx)}
              updated (do
                        (log/infof "AUDIT content-tag-update user=%s tag=%s"
                                   (or (:user/username actor) (:user/id actor))
                                   (:content.tag/id updates))
                        {:tag updated})
              :else (error 500 "Content tag not available after update")))))))

(defn delete-tag!
  [conn tag-id actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            {value :value id-err :error} (normalize-uuid tag-id "tag id")]
        (cond
          id-err (error 400 id-err)
          (nil? (tag-by-id db value)) (error 404 "Tag not found")
          :else
          (let [tx (attempt-transact conn [[:db/retractEntity [:content.tag/id value]]] "delete content tag")]
            (if (:error tx)
              {:error (:error tx)}
              (do
                (log/infof "AUDIT content-tag-delete user=%s tag=%s"
                           (or (:user/username actor) (:user/id actor))
                           value)
                {:tag {:content.tag/id value}})))))))

;; -------- Page operations --------

(defn- normalize-tag-refs
  [db tag-values]
  (let [ids (->> tag-values
                 (map #(-> (normalize-uuid % "tag") :value))
                 (remove nil?)
                 set)
        {:keys [error]} (tag-id-set db ids)]
    (if error
      {:error error}
      {:tag-refs (vec (map (fn [tid] [:content.tag/id tid]) ids))})))

(defn- normalize-block-refs
  [db block-values]
  (let [ids (->> block-values
                 (map #(-> (normalize-uuid % "block") :value))
                 (remove nil?)
                 vec)
        blocks (keep #(block-by-id db %) ids)]
    (cond
      (< (count blocks) (count ids)) {:error "One or more blocks not found"}
      :else {:block-ids ids
             :blocks blocks
             :block-refs (vec (map (fn [bid] [:content.block/id bid]) ids))})))

(defn- validate-page-create
  [db body]
  (let [{title :value title-err :error} (normalize-string (param-value body :content.page/title) "title" {:required true})
        {path :value path-err :error} (normalize-string (param-value body :content.page/path) "path" {:required true})
        {summary :value summary-err :error} (normalize-string (param-value body :content.page/summary) "summary" {:required false
                                                                                                                  :allow-blank? true})
        {nav-order :value nav-err :error} (normalize-long (param-value body :content.page/navigation-order) "navigation order")
        {visible? :value vis-err :error} (normalize-boolean (param-value body :content.page/visible?) "visible" {:default true})
        {tag-err :error tag-refs :tag-refs} (normalize-tag-refs db (let [tv (param-value body :content.page/tag)]
                                                                    (cond
                                                                      (nil? tv) []
                                                                      (sequential? tv) tv
                                                                      :else [tv])))
        {block-error :error block-ids :block-ids block-refs :block-refs}
        (normalize-block-refs db (let [bv (param-value body :content.page/blocks)]
                                   (cond
                                     (nil? bv) []
                                     (sequential? bv) bv
                                     :else [bv])))
        path-conflict (seq (d/q '[:find ?e :in $ ?path :where [?e :content.page/path ?path]] db path))]
    (cond
      title-err (error 400 title-err)
      path-err (error 400 path-err)
      summary-err (error 400 summary-err)
      nav-err (error 400 nav-err)
      vis-err (error 400 vis-err)
      tag-err (error 400 tag-err)
      path-conflict (error 409 "Path already exists")
      block-error (error 400 block-error)
      :else
      {:page (cond-> {:content.page/id (UUID/randomUUID)
                      :entity/type :entity.type/content-page
                      :content.page/title title
                      :content.page/path path
                      :content.page/visible? visible?}
               (some? summary) (assoc :content.page/summary summary)
               (some? nav-order) (assoc :content.page/navigation-order nav-order)
               (seq tag-refs) (assoc :content.page/tag tag-refs)
               (seq block-refs) (assoc :content.page/blocks block-refs))
       :block-ids block-ids})))

(defn- validate-page-update
  [db page-id body]
  (let [{pid :value id-err :error} (normalize-uuid page-id "page id")
        {title :value title-err :error} (normalize-string (param-value body :content.page/title) "title" {:required false})
        {path :value path-err :error} (normalize-string (param-value body :content.page/path) "path" {:required false})
        {summary :value summary-err :error} (normalize-string (param-value body :content.page/summary) "summary" {:required false
                                                                                                                  :allow-blank? true})
        {nav-order :value nav-err :error} (normalize-long (param-value body :content.page/navigation-order) "navigation order")
        {visible? :value vis-err :error} (normalize-boolean (param-value body :content.page/visible?) "visible" {:default nil})
        {tag-err :error tag-refs :tag-refs} (normalize-tag-refs db (let [tv (param-value body :content.page/tag)]
                                                                    (cond
                                                                      (nil? tv) []
                                                                      (sequential? tv) tv
                                                                      :else [tv])))
        {block-error :error block-ids :block-ids block-refs :block-refs}
        (normalize-block-refs db (let [bv (param-value body :content.page/blocks)]
                                   (cond
                                     (nil? bv) []
                                     (sequential? bv) bv
                                     :else [bv])))
        path-conflict (when path
                        (seq (d/q '[:find ?e :in $ ?path ?pid :where [?e :content.page/path ?path]
                                    [(not= ?e ?pid)]]
                                  db path pid)))]
    (cond
      id-err (error 400 id-err)
      title-err (error 400 title-err)
      path-err (error 400 path-err)
      summary-err (error 400 summary-err)
      nav-err (error 400 nav-err)
      vis-err (error 400 vis-err)
      tag-err (error 400 tag-err)
      path-conflict (error 409 "Path already exists")
      block-error (error 400 block-error)
      (nil? (page-by-id db pid)) (error 404 "Page not found")
      :else
      {:page-id pid
       :updates (cond-> {:content.page/id pid}
                 title (assoc :content.page/title title)
                 path (assoc :content.page/path path)
                 (some? summary) (assoc :content.page/summary summary)
                 (some? nav-order) (assoc :content.page/navigation-order nav-order)
                 (some? visible?) (assoc :content.page/visible? visible?)
                 tag-refs (assoc :content.page/tag tag-refs)
                 block-refs (assoc :content.page/blocks block-refs))
       :block-ids block-ids})))

(defn list-pages
  [conn {:keys [with-blocks?] :or {with-blocks? true}}]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            pull-pattern (cond-> [:content.page/id
                                  :content.page/title
                                  :content.page/path
                                  :content.page/summary
                                  :content.page/navigation-order
                                  :content.page/visible?
                                  {:content.page/tag [:content.tag/id]}]
                           with-blocks? (conj {:content.page/blocks [:content.block/id]}))
            pages (->> (entity/eids-by-type db :entity.type/content-page)
                       (map #(present-page (d/pull db pull-pattern %)))
                       (remove nil?)
                       (sort-by #(or (:content.page/navigation-order %) Long/MAX_VALUE))
                       vec)]
        {:pages pages})))

(defn create-page!
  [conn body actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            {:keys [error page block-ids]} (validate-page-create db body)]
        (if error
          {:error error}
          (let [block-tx (mapcat (fn [bid idx]
                                   [[:db/add [:content.block/id bid] :content.block/page [:content.page/id (:content.page/id page)]]
                                    [:db/add [:content.block/id bid] :content.block/order (long idx)]])
                                 block-ids (range))
                tx (attempt-transact conn (into [page] block-tx) "create content page")
                created (when-let [db-after (:db-after tx)]
                          (some-> (page-by-id db-after (:content.page/id page))
                                  present-page))]
            (cond
              (:error tx) {:error (:error tx)}
              created (do
                        (log/infof "AUDIT content-page-create user=%s page=%s"
                                   (or (:user/username actor) (:user/id actor))
                                   (:content.page/id created))
                        {:page created})
              :else (error 500 "Content page not available after create")))))))

(defn update-page!
  [conn page-id body actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            {:keys [error updates block-ids page-id]} (validate-page-update db page-id body)]
        (if error
          {:error error}
          (let [block-tx (mapcat (fn [bid idx]
                                   [[:db/add [:content.block/id bid] :content.block/page [:content.page/id page-id]]
                                    [:db/add [:content.block/id bid] :content.block/order (long idx)]])
                                 block-ids (range))
                tx (attempt-transact conn (into [updates] block-tx) "update content page")
                updated (when-let [db-after (:db-after tx)]
                          (some-> (page-by-id db-after page-id)
                                  present-page))]
            (cond
              (:error tx) {:error (:error tx)}
              updated (do
                        (log/infof "AUDIT content-page-update user=%s page=%s"
                                   (or (:user/username actor) (:user/id actor))
                                   page-id)
                        {:page updated})
              :else (error 500 "Content page not available after update")))))))

(defn delete-page!
  [conn page-id actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            {pid :value id-err :error} (normalize-uuid page-id "page id")]
        (cond
          id-err (error 400 id-err)
          (nil? (page-by-id db pid)) (error 404 "Page not found")
          :else
          (let [tx (attempt-transact conn [[:db/retractEntity [:content.page/id pid]]] "delete content page")]
            (if (:error tx)
              {:error (:error tx)}
              (do
                (log/infof "AUDIT content-page-delete user=%s page=%s"
                           (or (:user/username actor) (:user/id actor))
                           pid)
                {:page {:content.page/id pid}})))))))

;; -------- Block operations --------

(defn- validate-block-create
  [db body]
  (let [{id :value id-err :error} (normalize-uuid (param-value body :content.block/id) "block id")
        {page-id :value page-err :error} (normalize-uuid (param-value body :content.block/page) "page id")
        {type :value type-err :error} (let [raw (param-value body :content.block/type)]
                                         (if (nil? raw)
                                           {:value nil}
                                           (normalize-keyword raw "block type" allowed-block-types)))
        {title :value title-err :error} (normalize-string (param-value body :content.block/title) "title" {:required false})
        {body :value body-err :error} (normalize-string (param-value body :content.block/body) "body" {:required false
                                                                                                      :allow-blank? true})
        {media-ref :value media-err :error} (normalize-string (param-value body :content.block/media-ref) "media ref" {:required false
                                                                                                                       :allow-blank? true})
        {slug :value slug-err :error} (normalize-string (param-value body :content.block/slug) "slug" {:required false
                                                                                                       :allow-blank? false})
        {order :value order-err :error} (normalize-long (param-value body :content.block/order) "order")
        {visible? :value vis-err :error} (normalize-boolean (param-value body :content.block/visible?) "visible" {:default true})
        {tag-err :error tag-refs :tag-refs} (normalize-tag-refs db (let [tv (param-value body :content.block/tag)]
                                                                    (cond
                                                                      (nil? tv) []
                                                                      (sequential? tv) tv
                                                                      :else [tv])))]
    (cond
      id-err (error 400 id-err)
      page-err (error 400 page-err)
      type-err (error 400 type-err)
      title-err (error 400 title-err)
      body-err (error 400 body-err)
      media-err (error 400 media-err)
      slug-err (error 400 slug-err)
      order-err (error 400 order-err)
      vis-err (error 400 vis-err)
      tag-err (error 400 tag-err)
      (and page-id (nil? (page-by-id db page-id))) (error 404 "Page not found")
      :else
      (let [slug' (or slug (when title (slugify title)))
            conflict? (when slug'
                        (d/q '[:find ?e :in $ ?slug :where [?e :content.block/slug ?slug]] db slug'))]
        (if (seq conflict?)
          (error 409 "Slug already exists")
          {:block (cond-> {:content.block/id (or id (UUID/randomUUID))
                           :entity/type :entity.type/content-block
                           :content.block/type type
                           :content.block/slug slug'
                           :content.block/order (or order 0)
                           :content.block/visible? visible?}
                    page-id (assoc :content.block/page [:content.page/id page-id])
                    title (assoc :content.block/title title)
                    (some? body) (assoc :content.block/body body)
                    (some? media-ref) (assoc :content.block/media-ref media-ref)
                    (seq tag-refs) (assoc :content.block/tag tag-refs))
           :page-id page-id}))))) 

(defn- validate-block-update
  [db block-id body]
  (let [raw-order (param-value body :content.block/order)
        {bid :value id-err :error} (normalize-uuid block-id "block id")
        {page-id :value page-err :error} (normalize-uuid (param-value body :content.block/page) "page id")
        {type :value type-err :error} (let [raw (param-value body :content.block/type)]
                                         (if (nil? raw)
                                           {:value nil}
                                           (normalize-keyword raw "block type" allowed-block-types)))
        {title :value title-err :error} (normalize-string (param-value body :content.block/title) "title" {:required false})
        {body :value body-err :error} (normalize-string (param-value body :content.block/body) "body" {:required false
                                                                                                      :allow-blank? true})
        {media-ref :value media-err :error} (normalize-string (param-value body :content.block/media-ref) "media ref" {:required false
                                                                                                                       :allow-blank? true})
        {slug :value slug-err :error} (normalize-string (param-value body :content.block/slug) "slug" {:required false})
        {order :value order-err :error} (normalize-long raw-order "order")
        {visible? :value vis-err :error} (normalize-boolean (param-value body :content.block/visible?) "visible" {:default nil})
        {tag-err :error tag-refs :tag-refs} (normalize-tag-refs db (let [tv (param-value body :content.block/tag)]
                                                                    (cond
                                                                      (nil? tv) []
                                                                      (sequential? tv) tv
                                                                      :else [tv])))]
    (cond
      id-err (error 400 id-err)
      page-err (error 400 page-err)
      type-err (error 400 type-err)
      title-err (error 400 title-err)
      body-err (error 400 body-err)
      media-err (error 400 media-err)
      slug-err (error 400 slug-err)
      order-err (error 400 order-err)
      vis-err (error 400 vis-err)
      tag-err (error 400 tag-err)
      (nil? (block-by-id db bid)) (error 404 "Block not found")
      (and page-id (nil? (page-by-id db page-id))) (error 404 "Page not found")
      :else
      (let [slug' (or slug (when title (slugify title)))
            conflict? (when slug'
                        (d/q '[:find ?e :in $ ?slug ?bid
                               :where [?e :content.block/slug ?slug]
                                      [(not= ?e ?bid)]]
                             db slug' bid))]
        (if (seq conflict?)
          (error 409 "Slug already exists")
          {:block-id bid
           :page-id page-id
           :updates (cond-> {:content.block/id bid}
                      page-id (assoc :content.block/page [:content.page/id page-id])
                      type (assoc :content.block/type type)
                      (some? title) (assoc :content.block/title title)
                      (some? body) (assoc :content.block/body body)
                      (some? media-ref) (assoc :content.block/media-ref media-ref)
                      slug' (assoc :content.block/slug slug')
                      (some? order) (assoc :content.block/order order)
                      (some? visible?) (assoc :content.block/visible? visible?)
                      tag-refs (assoc :content.block/tag tag-refs))})))))

(defn list-blocks
  [conn {:keys [page-id]}]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            {pid :value pid-err :error} (normalize-uuid page-id "page id")
            block-eids (entity/eids-by-type db :entity.type/content-block)
            blocks (->> block-eids
                        (map #(block-by-id db (:content.block/id (d/pull db [:content.block/id] %))))
                        (remove nil?)
                        (filter (fn [b]
                                  (if pid-err
                                    true
                                    (if pid
                                      (= pid (-> b :content.block/page :content.page/id))
                                      true))))
                        (map present-block)
                        vec)]
        {:blocks blocks})))

(defn create-block!
  [conn body actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            {:keys [error block page-id]} (validate-block-create db body)]
        (if error
          {:error error}
          (let [tx (attempt-transact conn [block] "create content block")
                created (when-let [db-after (:db-after tx)]
                          (some-> (block-by-id db-after (:content.block/id block))
                                  present-block))
                link-tx (when (and (nil? (:error tx)) page-id)
                          (attempt-transact conn [[:db/add [:content.page/id page-id] :content.page/blocks [:content.block/id (:content.block/id block)]]]
                                            "link block to page"))]
            (cond
              (:error tx) {:error (:error tx)}
              (and link-tx (:error link-tx)) {:error (:error link-tx)}
              created (do
                        (log/infof "AUDIT content-block-create user=%s block=%s"
                                   (or (:user/username actor) (:user/id actor))
                                   (:content.block/id created))
                        {:block created})
              :else (error 500 "Content block not available after create")))))))

(defn update-block!
  [conn block-id body actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            {:keys [error updates block-id page-id]} (validate-block-update db block-id body)]
        (if error
          {:error error}
          (let [tx (attempt-transact conn [updates] "update content block")
                link-tx (when (and (nil? (:error tx)) page-id)
                          (attempt-transact conn [[:db/add [:content.page/id page-id] :content.page/blocks [:content.block/id block-id]]]
                                            "link block to page"))
                updated (when-let [db-after (:db-after (or link-tx tx))]
                          (some-> (block-by-id db-after block-id)
                                  present-block))]
            (cond
              (:error tx) {:error (:error tx)}
              (and link-tx (:error link-tx)) {:error (:error link-tx)}
              updated (do
                        (log/infof "AUDIT content-block-update user=%s block=%s"
                                   (or (:user/username actor) (:user/id actor))
                                   block-id)
                        {:block updated})
              :else (error 500 "Content block not available after update")))))))

(defn delete-block!
  [conn block-id actor]
  (or (ensure-conn conn)
      (let [db (d/db conn)
            {bid :value id-err :error} (normalize-uuid block-id "block id")]
        (cond
          id-err (error 400 id-err)
          (nil? (block-by-id db bid)) (error 404 "Block not found")
          :else
          (let [tx (attempt-transact conn [[:db/retractEntity [:content.block/id bid]]] "delete content block")]
            (if (:error tx)
              {:error (:error tx)}
              (do
                (log/infof "AUDIT content-block-delete user=%s block=%s"
                           (or (:user/username actor) (:user/id actor))
                           bid)
                {:block {:content.block/id bid}})))))))
