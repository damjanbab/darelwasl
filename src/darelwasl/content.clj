(ns darelwasl.content
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.db :as db]
            [darelwasl.entity :as entity]
            [darelwasl.provenance :as prov]
            [darelwasl.shared.block-types :as block-types]
            [darelwasl.validation :as v])
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

(def ^:private param-value v/param-value)
(def ^:private normalize-string v/normalize-string)
(def ^:private normalize-uuid v/normalize-uuid)
(def ^:private normalize-boolean v/normalize-boolean)
(def ^:private normalize-long v/normalize-long)
(def ^:private normalize-keyword v/normalize-keyword)
(def ^:private normalize-string-list v/normalize-string-list)
(def ^:private normalize-ref-list v/normalize-ref-list)

(defn- slugify
  [s]
  (let [slug (-> s
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"(^-|-$)" ""))]
    (if (str/blank? slug) "item" slug)))

(defn- attempt-transact
  [conn tx-data context & [tx-prov]]
  (try
    (let [tx-data' (if tx-prov
                     (map #(prov/enrich-tx % tx-prov) tx-data)
                     tx-data)]
      (if (seq tx-data')
        (db/transact! conn {:tx-data tx-data'})
        {:db-after (d/db conn)}))
    (catch Exception e
      (log/error e (str "Failed to " context))
      {:error {:status 500
               :message (str "Failed to " context)
               :details (.getMessage e)}})))

;; -------- Pull helpers --------

(defn- present-license
  [license]
  (when license
    (select-keys license [:license/id
                          :license/type
                          :license/slug
                          :license/label
                          :license/processing-time
                          :license/ownership
                          :license/renewal-cost
                          :license/pricing-lines
                          :license/activities
                          :license/who
                          :license/who-activities
                          :license/document-checklist
                          :license/order
                          :license/visible?])))

(defn- present-comparison-row
  [row]
  (when row
    (select-keys row [:comparison.row/id
                      :comparison.row/criterion
                      :comparison.row/order
                      :comparison.row/entrepreneur
                      :comparison.row/general
                      :comparison.row/gcc])))

(defn- present-journey-phase
  [phase]
  (when phase
    (select-keys phase [:journey.phase/id
                        :journey.phase/title
                        :journey.phase/kind
                        :journey.phase/order
                        :journey.phase/bullets])))

(defn- present-activation-step
  [step]
  (when step
    (-> step
        (update :activation.step/phase #(when-let [p (:journey.phase/id %)] [:journey.phase/id p]))
        (select-keys [:activation.step/id
                      :activation.step/title
                      :activation.step/order
                      :activation.step/phase]))))

(defn- present-persona
  [persona]
  (when persona
    (select-keys persona [:persona/id
                          :persona/title
                          :persona/detail
                          :persona/type
                          :persona/order
                          :persona/visible?])))

(defn- present-support-entry
  [entry]
  (when entry
    (select-keys entry [:support.entry/id
                        :support.entry/role
                        :support.entry/text
                        :support.entry/order])))

(defn- present-hero-stat
  [stat]
  (when stat
    (select-keys stat [:hero.stat/id
                       :hero.stat/label
                       :hero.stat/value
                       :hero.stat/hint
                       :hero.stat/order])))

(defn- present-hero-flow
  [flow]
  (when flow
    (select-keys flow [:hero.flow/id
                       :hero.flow/title
                       :hero.flow/detail
                       :hero.flow/order])))

(defn- present-faq
  [faq]
  (when faq
    (select-keys faq [:faq/id
                      :faq/question
                      :faq/answer
                      :faq/scope
                      :faq/order
                      :faq/visible?])))

(defn- present-value
  [v]
  (when v
    (select-keys v [:value/id :value/title :value/copy :value/order])))

(defn- present-team-member
  [member]
  (when member
    (select-keys member [:team.member/id
                         :team.member/name
                         :team.member/title
                         :team.member/order
                         :team.member/avatar])))

(defn- present-contact
  [contact]
  (when contact
    (select-keys contact [:contact/id
                          :contact/email
                          :contact/phone
                          :contact/primary-cta-label
                          :contact/primary-cta-url
                          :contact/secondary-cta-label
                          :contact/secondary-cta-url])))

(defn- present-business
  [business]
  (when business
    (-> business
        (update :business/contact #(when-let [c (:contact/id %)] [:contact/id c]))
        (update :business/hero-stats #(vec (map (fn [s] [:hero.stat/id (:hero.stat/id s)]) %)))
        (update :business/hero-flows #(vec (map (fn [f] [:hero.flow/id (:hero.flow/id f)]) %)))
        (select-keys [:business/id
                      :business/name
                      :business/tagline
                      :business/summary
                      :business/mission
                      :business/vision
                      :business/nav-label
                      :business/hero-headline
                      :business/hero-strapline
                      :business/contact
                      :business/hero-stats
                      :business/hero-flows
                      :business/visible?]))))

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
                      description (assoc :content.tag/description description))
                tag (entity/with-ref db tag)]
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
          (let [tx-prov (prov/provenance actor)
                tx (attempt-transact conn [tag] "create content tag" tx-prov)
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
          (let [tx-prov (prov/provenance actor)
                tx (attempt-transact conn [updates] "update content tag" tx-prov)
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
          (let [tx-prov (prov/provenance actor)
                tx (attempt-transact conn [[:db/retractEntity [:content.tag/id value]]] "delete content tag" tx-prov)]
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
      (let [page (cond-> {:content.page/id (UUID/randomUUID)
                          :entity/type :entity.type/content-page
                          :content.page/title title
                          :content.page/path path
                          :content.page/visible? visible?}
                   (some? summary) (assoc :content.page/summary summary)
                   (some? nav-order) (assoc :content.page/navigation-order nav-order)
                   (seq tag-refs) (assoc :content.page/tag tag-refs)
                   (seq block-refs) (assoc :content.page/blocks block-refs))
            page (entity/with-ref db page)]
        {:page page
         :block-ids block-ids}))))

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
                tx-prov (prov/provenance actor)
                tx (attempt-transact conn (into [page] block-tx) "create content page" tx-prov)
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
                tx-prov (prov/provenance actor)
                tx (attempt-transact conn (into [updates] block-tx) "update content page" tx-prov)
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
          (let [tx-prov (prov/provenance actor)
                tx (attempt-transact conn [[:db/retractEntity [:content.page/id pid]]] "delete content page" tx-prov)]
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
                                           (normalize-keyword raw "block type" block-types/allowed-block-type-set)))
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
          (let [block (cond-> {:content.block/id (or id (UUID/randomUUID))
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
                block (entity/with-ref db block)]
            {:block block
             :page-id page-id})))))) 

(defn- validate-block-update
  [db block-id body]
  (let [raw-order (param-value body :content.block/order)
        {bid :value id-err :error} (normalize-uuid block-id "block id")
        {page-id :value page-err :error} (normalize-uuid (param-value body :content.block/page) "page id")
        {type :value type-err :error} (let [raw (param-value body :content.block/type)]
                                         (if (nil? raw)
                                           {:value nil}
                                           (normalize-keyword raw "block type" block-types/allowed-block-type-set)))
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
          (let [tx-prov (prov/provenance actor)
                tx (attempt-transact conn [block] "create content block" tx-prov)
                created (when-let [db-after (:db-after tx)]
                          (some-> (block-by-id db-after (:content.block/id block))
                                  present-block))
                link-tx (when (and (nil? (:error tx)) page-id)
                          (attempt-transact conn [[:db/add [:content.page/id page-id] :content.page/blocks [:content.block/id (:content.block/id block)]]]
                                            "link block to page" tx-prov))]
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
          (let [tx-prov (prov/provenance actor)
                tx (attempt-transact conn [updates] "update content block" tx-prov)
                link-tx (when (and (nil? (:error tx)) page-id)
                          (attempt-transact conn [[:db/add [:content.page/id page-id] :content.page/blocks [:content.block/id block-id]]]
                                            "link block to page" tx-prov))
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
          (let [tx-prov (prov/provenance actor)
                tx (attempt-transact conn [[:db/retractEntity [:content.block/id bid]]] "delete content block" tx-prov)]
            (if (:error tx)
              {:error (:error tx)}
              (do
                (log/infof "AUDIT content-block-delete user=%s block=%s"
                           (or (:user/username actor) (:user/id actor))
                           bid)
                {:block {:content.block/id bid}})))))))

;; -------- License + comparison mutations --------

(def license-types #{:license.type/general :license.type/entrepreneur :license.type/gcc})

(defn- validate-license
  [db body]
  (let [{id :value id-err :error} (normalize-uuid (param-value body :license/id) "license id")
        {slug :value slug-err :error} (normalize-string (param-value body :license/slug) "slug" {:required true})
        {label :value label-err :error} (normalize-string (param-value body :license/label) "label" {:required true})
        {type-kw :value type-err :error} (normalize-keyword (param-value body :license/type) "license type" license-types)
        {processing :value processing-err :error} (normalize-string (param-value body :license/processing-time) "processing time" {:required false :allow-blank? true})
        {ownership :value ownership-err :error} (normalize-string (param-value body :license/ownership) "ownership" {:required false :allow-blank? true})
        {renewal :value renewal-err :error} (normalize-string (param-value body :license/renewal-cost) "renewal cost" {:required false :allow-blank? true})
        {pricing :value pricing-err :error} (normalize-string-list (param-value body :license/pricing-lines) "pricing")
        {activities :value activities-err :error} (normalize-string-list (param-value body :license/activities) "activities")
        {who :value who-err :error} (normalize-string-list (param-value body :license/who) "who")
        {who-act :value who-act-err :error} (normalize-string-list (param-value body :license/who-activities) "who activities")
        {docs :value docs-err :error} (normalize-string-list (param-value body :license/document-checklist) "document checklist")
        {order :value order-err :error} (normalize-long (param-value body :license/order) "order")
        {visible :value visible-err :error} (normalize-boolean (param-value body :license/visible?) "visible" {:default true})]
    (or id-err slug-err label-err type-err processing-err ownership-err renewal-err pricing-err activities-err who-err who-act-err docs-err order-err visible-err
        (let [tx {:license/id (or id (UUID/randomUUID))
                  :entity/type :entity.type/license
                  :license/slug slug
                  :license/label label
                  :license/type type-kw
                  :license/processing-time processing
                  :license/ownership ownership
                  :license/renewal-cost renewal
                  :license/pricing-lines pricing
                  :license/activities activities
                  :license/who who
                  :license/who-activities who-act
                  :license/document-checklist docs
                  :license/order order
                  :license/visible? (if (nil? visible) true visible)}
              tx (entity/with-ref db tx)]
          {:tx tx}))))

(defn upsert-license!
  [conn body actor]
  (or (ensure-conn conn)
      (let [{:keys [tx error]} (validate-license (d/db conn) body)]
        (if error
          (error 400 error)
          (let [tx-prov (prov/provenance actor)
                txres (attempt-transact conn [(assoc tx :db/id [:license/id (:license/id tx)])] "upsert license" tx-prov)]
            (if (:error txres)
              {:error (:error txres)}
              (do
                (log/infof "AUDIT license-upsert user=%s license=%s"
                           (or (:user/username actor) (:user/id actor))
                           (:license/id tx))
                {:license (present-license tx)})))))))

(defn delete-license!
  [conn license-id actor]
  (or (ensure-conn conn)
      (let [{lid :value id-err :error} (normalize-uuid license-id "license id")
            db (d/db conn)]
        (cond
          id-err (error 400 id-err)
          (nil? (d/q '[:find ?e :in $ ?id :where [?e :license/id ?id]] db lid)) (error 404 "License not found")
          :else (let [tx-prov (prov/provenance actor)
                      txres (attempt-transact conn [[:db/retractEntity [:license/id lid]]] "delete license" tx-prov)]
                  (if (:error txres)
                    {:error (:error txres)}
                    (do
                      (log/infof "AUDIT license-delete user=%s license=%s"
                                 (or (:user/username actor) (:user/id actor))
                                 lid)
                      {:license/id lid})))))))

(defn- validate-comparison-row
  [db body]
  (let [{id :value id-err :error} (normalize-uuid (param-value body :comparison.row/id) "comparison row id")
        {criterion :value crit-err :error} (normalize-string (param-value body :comparison.row/criterion) "criterion" {:required true})
        {order :value order-err :error} (normalize-long (param-value body :comparison.row/order) "order")
        {ent :value ent-err :error} (normalize-string (param-value body :comparison.row/entrepreneur) "entrepreneur value" {:required false :allow-blank? true})
        {gen :value gen-err :error} (normalize-string (param-value body :comparison.row/general) "general value" {:required false :allow-blank? true})
        {gcc :value gcc-err :error} (normalize-string (param-value body :comparison.row/gcc) "gcc value" {:required false :allow-blank? true})]
    (or id-err crit-err order-err ent-err gen-err gcc-err
        (let [tx {:comparison.row/id (or id (UUID/randomUUID))
                  :entity/type :entity.type/comparison-row
                  :comparison.row/criterion criterion
                  :comparison.row/order order
                  :comparison.row/entrepreneur ent
                  :comparison.row/general gen
                  :comparison.row/gcc gcc}
              tx (entity/with-ref db tx)]
          {:tx tx}))))

(defn upsert-comparison-row!
  [conn body actor]
  (or (ensure-conn conn)
      (let [{:keys [tx error]} (validate-comparison-row (d/db conn) body)]
        (if error
          (error 400 error)
          (let [tx-prov (prov/provenance actor)
                txres (attempt-transact conn [(assoc tx :db/id [:comparison.row/id (:comparison.row/id tx)])] "upsert comparison row" tx-prov)]
            (if (:error txres)
              {:error (:error txres)}
              (do
                (log/infof "AUDIT comparison-row-upsert user=%s row=%s"
                           (or (:user/username actor) (:user/id actor))
                           (:comparison.row/id tx))
                {:comparison-row (present-comparison-row tx)})))))))

(defn delete-comparison-row!
  [conn row-id actor]
  (or (ensure-conn conn)
      (let [{rid :value id-err :error} (normalize-uuid row-id "comparison row id")
            db (d/db conn)]
        (cond
          id-err (error 400 id-err)
          (nil? (d/q '[:find ?e :in $ ?id :where [?e :comparison.row/id ?id]] db rid)) (error 404 "Comparison row not found")
          :else (let [tx-prov (prov/provenance actor)
                      txres (attempt-transact conn [[:db/retractEntity [:comparison.row/id rid]]] "delete comparison row" tx-prov)]
                  (if (:error txres)
                    {:error (:error txres)}
                    (do
                      (log/infof "AUDIT comparison-row-delete user=%s row=%s"
                                 (or (:user/username actor) (:user/id actor))
                                 rid)
                      {:comparison.row/id rid})))))))

;; -------- Content v2 read helpers --------

(defn- q-all
  [db ident pull-expr]
  (->> (d/q '[:find (pull ?e pattern)
              :in $ ?attr pattern
              :where [?e ?attr]]
            db ident pull-expr)
       (map first)))

(defn list-licenses
  [conn]
  (if-let [err (ensure-conn conn)]
    err
    (let [items (->> (q-all (d/db conn) :license/id [:license/id
                                                     :license/type
                                                     :license/slug
                                                     :license/label
                                                     :license/processing-time
                                                     :license/ownership
                                                     :license/renewal-cost
                                                     :license/pricing-lines
                                                     :license/activities
                                                     :license/who
                                                     :license/who-activities
                                                     :license/document-checklist
                                                     :license/order
                                                     :license/visible?])
                     (map present-license)
                     (sort-by (fn [l] (or (:license/order l) Long/MAX_VALUE))))]
      {:licenses (vec items)})))

(defn list-comparison-rows
  [conn]
  (if-let [err (ensure-conn conn)]
    err
    (let [rows (->> (q-all (d/db conn) :comparison.row/id [:comparison.row/id
                                                          :comparison.row/criterion
                                                          :comparison.row/order
                                                          :comparison.row/entrepreneur
                                                          :comparison.row/general
                                                          :comparison.row/gcc])
                    (map present-comparison-row)
                    (sort-by (fn [r] (or (:comparison.row/order r) Long/MAX_VALUE))))]
      {:comparison-rows (vec rows)})))

(defn list-journey-phases
  [conn]
  (if-let [err (ensure-conn conn)]
    err
    (let [items (->> (q-all (d/db conn) :journey.phase/id [:journey.phase/id
                                                          :journey.phase/title
                                                          :journey.phase/kind
                                                          :journey.phase/order
                                                          :journey.phase/bullets])
                     (map present-journey-phase)
                     (sort-by (fn [p] (or (:journey.phase/order p) Long/MAX_VALUE))))]
      {:journey-phases (vec items)})))

(defn list-activation-steps
  [conn]
  (if-let [err (ensure-conn conn)]
    err
    (let [items (->> (q-all (d/db conn) :activation.step/id [:activation.step/id
                                                             :activation.step/title
                                                             :activation.step/order
                                                             {:activation.step/phase [:journey.phase/id]}])
                      (map present-activation-step)
                      (sort-by (fn [s] (or (:activation.step/order s) Long/MAX_VALUE))))]
      {:activation-steps (vec items)})))

(defn- validate-activation-step
  [db body]
  (let [{id :value id-err :error} (normalize-uuid (param-value body :activation.step/id) "activation step id")
        {title :value title-err :error} (normalize-string (param-value body :activation.step/title) "title" {:required true})
        {order :value order-err :error} (normalize-long (param-value body :activation.step/order) "order")
        {phase :value phase-err :error} (normalize-uuid (param-value body :activation.step/phase) "phase id")]
    (or id-err title-err order-err phase-err
        (let [tx (cond-> {:activation.step/id (or id (UUID/randomUUID))
                          :entity/type :entity.type/activation-step
                          :activation.step/title title
                          :activation.step/order order}
                   phase (assoc :activation.step/phase [:journey.phase/id phase]))
              tx (entity/with-ref db tx)]
          {:tx tx}))))

(defn upsert-activation-step!
  [conn body actor]
  (or (ensure-conn conn)
      (let [{:keys [tx error]} (validate-activation-step (d/db conn) body)]
        (if error
          (error 400 error)
          (let [tx-prov (prov/provenance actor)
                txres (attempt-transact conn [(assoc tx :db/id [:activation.step/id (:activation.step/id tx)])] "upsert activation step" tx-prov)]
            (if (:error txres)
              {:error (:error txres)}
              (do
                (log/infof "AUDIT activation-step-upsert user=%s step=%s"
                           (or (:user/username actor) (:user/id actor))
                           (:activation.step/id tx))
                {:activation-step tx})))))))

(defn delete-activation-step!
  [conn step-id actor]
  (or (ensure-conn conn)
      (let [{sid :value id-err :error} (normalize-uuid step-id "activation step id")
            db (d/db conn)]
        (cond
          id-err (error 400 id-err)
          (nil? (d/q '[:find ?e :in $ ?id :where [?e :activation.step/id ?id]] db sid)) (error 404 "Activation step not found")
          :else (let [tx-prov (prov/provenance actor)
                      txres (attempt-transact conn [[:db/retractEntity [:activation.step/id sid]]] "delete activation step" tx-prov)]
                  (if (:error txres)
                    {:error (:error txres)}
                    (do
                      (log/infof "AUDIT activation-step-delete user=%s step=%s"
                                 (or (:user/username actor) (:user/id actor))
                                 sid)
                      {:activation.step/id sid})))))))

(defn list-personas
  [conn]
  (if-let [err (ensure-conn conn)]
    err
    (let [items (->> (q-all (d/db conn) :persona/id [:persona/id
                                                   :persona/title
                                                   :persona/detail
                                                   :persona/type
                                                   :persona/order
                                                   :persona/visible?])
                     (map present-persona)
                     (sort-by (fn [p] (or (:persona/order p) Long/MAX_VALUE))))]
      {:personas (vec items)})))

(defn- validate-persona
  [db body]
  (let [{id :value id-err :error} (normalize-uuid (param-value body :persona/id) "persona id")
        {title :value title-err :error} (normalize-string (param-value body :persona/title) "title" {:required true})
        {detail :value detail-err :error} (normalize-string (param-value body :persona/detail) "detail" {:required true})
        {type-kw :value type-err :error} (normalize-keyword (param-value body :persona/type) "persona type" nil)
        {order :value order-err :error} (normalize-long (param-value body :persona/order) "order")
        {visible :value visible-err :error} (normalize-boolean (param-value body :persona/visible?) "visible" {:default true})]
    (or id-err title-err detail-err type-err order-err visible-err
        (let [tx {:persona/id (or id (UUID/randomUUID))
                  :entity/type :entity.type/persona
                  :persona/title title
                  :persona/detail detail
                  :persona/type type-kw
                  :persona/order order
                  :persona/visible? (if (nil? visible) true visible)}
              tx (entity/with-ref db tx)]
          {:tx tx}))))

(defn upsert-persona!
  [conn body actor]
  (or (ensure-conn conn)
      (let [{:keys [tx error]} (validate-persona (d/db conn) body)]
        (if error
          (error 400 error)
          (let [tx-prov (prov/provenance actor)
                txres (attempt-transact conn [(assoc tx :db/id [:persona/id (:persona/id tx)])] "upsert persona" tx-prov)]
            (if (:error txres)
              {:error (:error txres)}
              (do
                (log/infof "AUDIT persona-upsert user=%s persona=%s"
                           (or (:user/username actor) (:user/id actor))
                           (:persona/id tx))
                {:persona tx})))))))

(defn delete-persona!
  [conn persona-id actor]
  (or (ensure-conn conn)
      (let [{pid :value id-err :error} (normalize-uuid persona-id "persona id")
            db (d/db conn)]
        (cond
          id-err (error 400 id-err)
          (nil? (d/q '[:find ?e :in $ ?id :where [?e :persona/id ?id]] db pid)) (error 404 "Persona not found")
          :else (let [tx-prov (prov/provenance actor)
                      txres (attempt-transact conn [[:db/retractEntity [:persona/id pid]]] "delete persona" tx-prov)]
                  (if (:error txres)
                    {:error (:error txres)}
                    (do
                      (log/infof "AUDIT persona-delete user=%s persona=%s"
                                 (or (:user/username actor) (:user/id actor))
                                 pid)
                      {:persona/id pid})))))))

(defn list-support-entries
  [conn]
  (if-let [err (ensure-conn conn)]
    err
    (let [items (->> (q-all (d/db conn) :support.entry/id [:support.entry/id
                                                         :support.entry/role
                                                         :support.entry/text
                                                         :support.entry/order])
                     (map present-support-entry)
                     (sort-by (fn [s] (or (:support.entry/order s) Long/MAX_VALUE))))]
      {:support-entries (vec items)})))

(defn- validate-support-entry
  [db body]
  (let [{id :value id-err :error} (normalize-uuid (param-value body :support.entry/id) "support entry id")
        {role :value role-err :error} (normalize-keyword (param-value body :support.entry/role) "support role" #{:support/we :support/you})
        {text :value text-err :error} (normalize-string (param-value body :support.entry/text) "text" {:required true})
        {order :value order-err :error} (normalize-long (param-value body :support.entry/order) "order")]
    (or id-err role-err text-err order-err
        (let [tx {:support.entry/id (or id (UUID/randomUUID))
                  :entity/type :entity.type/support-entry
                  :support.entry/role (or role :support/we)
                  :support.entry/text text
                  :support.entry/order order}
              tx (entity/with-ref db tx)]
          {:tx tx}))))

(defn upsert-support-entry!
  [conn body actor]
  (or (ensure-conn conn)
      (let [{:keys [tx error]} (validate-support-entry (d/db conn) body)]
        (if error
          (error 400 error)
          (let [tx-prov (prov/provenance actor)
                txres (attempt-transact conn [(assoc tx :db/id [:support.entry/id (:support.entry/id tx)])] "upsert support entry" tx-prov)]
            (if (:error txres)
              {:error (:error txres)}
              (do
                (log/infof "AUDIT support-entry-upsert user=%s entry=%s"
                           (or (:user/username actor) (:user/id actor))
                           (:support.entry/id tx))
                {:support-entry tx})))))))

(defn delete-support-entry!
  [conn entry-id actor]
  (or (ensure-conn conn)
      (let [{sid :value id-err :error} (normalize-uuid entry-id "support entry id")
            db (d/db conn)]
        (cond
          id-err (error 400 id-err)
          (nil? (d/q '[:find ?e :in $ ?id :where [?e :support.entry/id ?id]] db sid)) (error 404 "Support entry not found")
          :else (let [tx-prov (prov/provenance actor)
                      txres (attempt-transact conn [[:db/retractEntity [:support.entry/id sid]]] "delete support entry" tx-prov)]
                  (if (:error txres)
                    {:error (:error txres)}
                    (do
                      (log/infof "AUDIT support-entry-delete user=%s entry=%s"
                                 (or (:user/username actor) (:user/id actor))
                                 sid)
                      {:support.entry/id sid})))))))

(defn- validate-contact
  [db body]
  (let [{id :value id-err :error} (normalize-uuid (param-value body :contact/id) "contact id")
        {email :value email-err :error} (normalize-string (param-value body :contact/email) "email" {:required true})
        {phone :value phone-err :error} (normalize-string (param-value body :contact/phone) "phone" {:required true})
        {primary-label :value primary-label-err :error} (normalize-string (param-value body :contact/primary-cta-label) "primary CTA label" {:required false})
        {primary-url :value primary-url-err :error} (normalize-string (param-value body :contact/primary-cta-url) "primary CTA url" {:required false})
        {secondary-label :value secondary-label-err :error} (normalize-string (param-value body :contact/secondary-cta-label) "secondary CTA label" {:required false})
        {secondary-url :value secondary-url-err :error} (normalize-string (param-value body :contact/secondary-cta-url) "secondary CTA url" {:required false})]
    (or id-err email-err phone-err primary-label-err primary-url-err secondary-label-err secondary-url-err
        (let [tx {:contact/id (or id (UUID/randomUUID))
                  :entity/type :entity.type/contact
                  :contact/email email
                  :contact/phone phone
                  :contact/primary-cta-label primary-label
                  :contact/primary-cta-url primary-url
                  :contact/secondary-cta-label secondary-label
                  :contact/secondary-cta-url secondary-url}
              tx (entity/with-ref db tx)]
          {:tx tx}))))

(defn upsert-contact!
  [conn body actor]
  (or (ensure-conn conn)
      (let [{:keys [tx error]} (validate-contact (d/db conn) body)]
        (if error
          (error 400 error)
          (let [tx-prov (prov/provenance actor)
                txres (attempt-transact conn [(assoc tx :db/id [:contact/id (:contact/id tx)])] "upsert contact" tx-prov)]
            (if (:error txres)
              {:error (:error txres)}
              (do
                (log/infof "AUDIT contact-upsert user=%s contact=%s"
                           (or (:user/username actor) (:user/id actor))
                           (:contact/id tx))
                {:contact tx})))))))

(defn- validate-business
  [db body]
  (let [{id :value id-err :error} (normalize-uuid (param-value body :business/id) "business id")
        {name :value name-err :error} (normalize-string (param-value body :business/name) "name" {:required true})
        {tagline :value tagline-err :error} (normalize-string (param-value body :business/tagline) "tagline" {:required false})
        {summary :value summary-err :error} (normalize-string (param-value body :business/summary) "summary" {:required false})
        {mission :value mission-err :error} (normalize-string (param-value body :business/mission) "mission" {:required false})
        {vision :value vision-err :error} (normalize-string (param-value body :business/vision) "vision" {:required false})
        {nav-label :value nav-label-err :error} (normalize-string (param-value body :business/nav-label) "nav label" {:required false})
        {headline :value headline-err :error} (normalize-string (param-value body :business/hero-headline) "hero headline" {:required false})
        {strapline :value strapline-err :error} (normalize-string (param-value body :business/hero-strapline) "hero strapline" {:required false})
        {contact-ref :value contact-err :error} (normalize-uuid (param-value body :business/contact) "contact id")
        hero-stats-res (normalize-ref-list (param-value body :business/hero-stats) :hero.stat/id "hero stat id")
        hero-flows-res (normalize-ref-list (param-value body :business/hero-flows) :hero.flow/id "hero flow id")
        {visible :value visible-err :error} (normalize-boolean (param-value body :business/visible?) "visible" {:default true})]
    (or id-err name-err tagline-err summary-err mission-err vision-err nav-label-err headline-err strapline-err contact-err visible-err
        (:error hero-stats-res)
        (:error hero-flows-res)
        (let [tx (cond-> {:business/id (or id (UUID/randomUUID))
                          :entity/type :entity.type/business
                          :business/name name
                          :business/tagline tagline
                          :business/summary summary
                          :business/mission mission
                          :business/vision vision
                          :business/nav-label nav-label
                          :business/hero-headline headline
                          :business/hero-strapline strapline
                          :business/visible? (if (nil? visible) true visible)}
                   contact-ref (assoc :business/contact [:contact/id contact-ref])
                   (seq (:value hero-stats-res)) (assoc :business/hero-stats (:value hero-stats-res))
                   (seq (:value hero-flows-res)) (assoc :business/hero-flows (:value hero-flows-res)))
              tx (entity/with-ref db tx)]
          {:tx tx}))))

(defn upsert-business!
  [conn body actor]
  (or (ensure-conn conn)
      (let [{:keys [tx error]} (validate-business (d/db conn) body)]
        (if error
          (error 400 error)
          (let [tx-prov (prov/provenance actor)
                txres (attempt-transact conn [(assoc tx :db/id [:business/id (:business/id tx)])] "upsert business" tx-prov)]
            (if (:error txres)
              {:error (:error txres)}
              (do
                (log/infof "AUDIT business-upsert user=%s business=%s"
                           (or (:user/username actor) (:user/id actor))
                           (:business/id tx))
                {:business tx})))))))

(defn list-hero-stats
  [conn]
  (if-let [err (ensure-conn conn)]
    err
    {:hero-stats (->> (q-all (d/db conn) :hero.stat/id [:hero.stat/id
                                                       :hero.stat/label
                                                       :hero.stat/value
                                                       :hero.stat/hint
                                                       :hero.stat/order])
                      (map present-hero-stat)
                      (sort-by (fn [h] (or (:hero.stat/order h) Long/MAX_VALUE)))
                      vec)}))

(defn- validate-journey-phase
  [db body]
  (let [{id :value id-err :error} (normalize-uuid (param-value body :journey.phase/id) "journey phase id")
        {title :value title-err :error} (normalize-string (param-value body :journey.phase/title) "title" {:required true})
        {kind :value kind-err :error} (normalize-keyword (param-value body :journey.phase/kind) "phase kind" #{:phase/pre-incorporation :phase/incorporation :phase/post-incorporation})
        {order :value order-err :error} (normalize-long (param-value body :journey.phase/order) "order")
        {bullets :value bullets-err :error} (normalize-string-list (param-value body :journey.phase/bullets) "bullets")]
    (or id-err title-err kind-err order-err bullets-err
        (let [tx {:journey.phase/id (or id (UUID/randomUUID))
                  :entity/type :entity.type/journey-phase
                  :journey.phase/title title
                  :journey.phase/kind (or kind :phase/pre-incorporation)
                  :journey.phase/order order
                  :journey.phase/bullets bullets}
              tx (entity/with-ref db tx)]
          {:tx tx}))))

(defn upsert-journey-phase!
  [conn body actor]
  (or (ensure-conn conn)
      (let [{:keys [tx error]} (validate-journey-phase (d/db conn) body)]
        (if error
          (error 400 error)
          (let [tx-prov (prov/provenance actor)
                txres (attempt-transact conn [(assoc tx :db/id [:journey.phase/id (:journey.phase/id tx)])] "upsert journey phase" tx-prov)]
            (if (:error txres)
              {:error (:error txres)}
              (do
                (log/infof "AUDIT journey-phase-upsert user=%s phase=%s"
                           (or (:user/username actor) (:user/id actor))
                           (:journey.phase/id tx))
                {:journey-phase tx})))))))

(defn delete-journey-phase!
  [conn phase-id actor]
  (or (ensure-conn conn)
      (let [{pid :value id-err :error} (normalize-uuid phase-id "journey phase id")
            db (d/db conn)]
        (cond
          id-err (error 400 id-err)
          (nil? (d/q '[:find ?e :in $ ?id :where [?e :journey.phase/id ?id]] db pid)) (error 404 "Journey phase not found")
          :else (let [tx-prov (prov/provenance actor)
                      txres (attempt-transact conn [[:db/retractEntity [:journey.phase/id pid]]] "delete journey phase" tx-prov)]
                  (if (:error txres)
                    {:error (:error txres)}
                    (do
                      (log/infof "AUDIT journey-phase-delete user=%s phase=%s"
                                 (or (:user/username actor) (:user/id actor))
                                 pid)
                      {:journey.phase/id pid})))))))

(defn list-hero-flows
  [conn]
  (if-let [err (ensure-conn conn)]
    err
    {:hero-flows (->> (q-all (d/db conn) :hero.flow/id [:hero.flow/id
                                                       :hero.flow/title
                                                       :hero.flow/detail
                                                       :hero.flow/order])
                      (map present-hero-flow)
                      (sort-by (fn [h] (or (:hero.flow/order h) Long/MAX_VALUE)))
                      vec)}))

(defn list-faqs
  [conn]
  (if-let [err (ensure-conn conn)]
    err
    {:faqs (->> (q-all (d/db conn) :faq/id [:faq/id
                                           :faq/question
                                           :faq/answer
                                           :faq/scope
                                           :faq/order
                                           :faq/visible?])
                (map present-faq)
                (sort-by (fn [f] (or (:faq/order f) Long/MAX_VALUE)))
                vec)}))

(defn list-values
  [conn]
  (if-let [err (ensure-conn conn)]
    err
    {:values (->> (q-all (d/db conn) :value/id [:value/id
                                                :value/title
                                                :value/copy
                                                :value/order])
                  (map present-value)
                  (sort-by (fn [v] (or (:value/order v) Long/MAX_VALUE)))
                  vec)}))

(defn list-team-members
  [conn]
  (if-let [err (ensure-conn conn)]
    err
    {:team-members (->> (q-all (d/db conn) :team.member/id [:team.member/id
                                                            :team.member/name
                                                            :team.member/title
                                                            :team.member/order
                                                            :team.member/avatar])
                        (map present-team-member)
                        (sort-by (fn [t] (or (:team.member/order t) Long/MAX_VALUE)))
                        vec)}))

(defn list-contacts
  [conn]
  (if-let [err (ensure-conn conn)]
    err
    {:contacts (->> (q-all (d/db conn) :contact/id [:contact/id
                                                    :contact/email
                                                    :contact/phone
                                                    :contact/primary-cta-label
                                                    :contact/primary-cta-url
                                                    :contact/secondary-cta-label
                                                    :contact/secondary-cta-url])
                    (map present-contact)
                    vec)}))

(defn list-businesses
  [conn]
  (if-let [err (ensure-conn conn)]
    err
    {:businesses (->> (q-all (d/db conn) :business/id [:business/id
                                                       :business/name
                                                       :business/tagline
                                                       :business/summary
                                                       :business/mission
                                                       :business/vision
                                                       :business/nav-label
                                                       :business/hero-headline
                                                       :business/hero-strapline
                                                       {:business/contact [:contact/id]}
                                                       {:business/hero-stats [:hero.stat/id]}
                                                       {:business/hero-flows [:hero.flow/id]}
                                                       :business/visible?])
                      (map present-business)
                      vec)}))

(defn list-content-v2
  [conn]
  (if-let [err (ensure-conn conn)]
    err
    (merge
     (list-licenses conn)
     (list-comparison-rows conn)
     (list-journey-phases conn)
     (list-activation-steps conn)
     (list-personas conn)
     (list-support-entries conn)
     (list-hero-stats conn)
     (list-hero-flows conn)
     (list-faqs conn)
     (list-values conn)
     (list-team-members conn)
     (list-contacts conn)
     (list-businesses conn))))
