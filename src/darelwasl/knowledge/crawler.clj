(ns darelwasl.knowledge.crawler
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.config :as config]
            [darelwasl.knowledge.allowlist :as allowlist]
            [darelwasl.knowledge.http :as http]
            [darelwasl.knowledge.robots :as robots]
            [darelwasl.knowledge.util :as kutil]
            [darelwasl.knowledge.extract :as extract]
            [darelwasl.knowledge.classify :as classify]
            [darelwasl.knowledge.store :as store]
            [datomic.client.api :as d])
  (:import (java.net URI)
           (java.nio.file Files Paths)
           (java.util UUID)))

(def ^:private default-delay-ms 2000)

(defn- storage-dir
  [cfg]
  (get-in cfg [:knowledge :storage-dir]))

(defn- ensure-dir
  [path]
  (let [p (Paths/get path (make-array String 0))]
    (when-not (Files/exists p (make-array java.nio.file.LinkOption 0))
      (Files/createDirectories p (make-array java.nio.file.attribute.FileAttribute 0)))
    p))

(defn- write-blob!
  [root sha256 bytes]
  (let [dir (ensure-dir (str root "/blobs"))
        path (.resolve dir sha256)]
    (Files/write path ^bytes bytes (make-array java.nio.file.OpenOption 0))
    (str path)))

(defn- normalize-url
  [url]
  (kutil/normalize-url url))

(defn- allowlisted?
  [^URI uri]
  (allowlist/allowlisted-url? uri))

(defn- fetch-robots
  [state host]
  (let [robots-url (str "https://" host "/robots.txt")
        {:keys [status body headers]} (http/request-text robots-url {:timeout-ms 8000})
        robots (robots/parse-robots body)]
    {:robots robots
     :status status
     :headers headers
     :body body
     :url robots-url}))

(defn- ensure-robots
  [state host]
  (if-let [cached (get-in state [:robots-cache host])]
    [state cached]
    (let [{:keys [robots status headers body url]} (fetch-robots state host)
          {:keys [normalized]} (normalize-url url)
          conn (:conn state)
          url-eid (store/ensure-url! conn {:normalized normalized
                                           :original url
                                           :status :url.status/fetched})
          bytes (.getBytes (or body "") "UTF-8")
          sha (kutil/sha256-bytes bytes)
          blob-path (write-blob! (:storage-dir state) sha bytes)
          blob-eid (store/ensure-blob! conn {:sha256 sha
                                             :mime "text/plain"
                                             :size (alength bytes)
                                             :uri blob-path})
          fetch-id (UUID/randomUUID)]
      (store/record-fetch! conn {:fetch-id fetch-id
                                 :status status
                                 :headers headers
                                 :url url-eid
                                 :blob blob-eid
                                 :run (:run-id state)
                                 :error nil})
      [(assoc-in state [:robots-cache host] robots)
       robots])))

(defn- enforce-delay
  [state host delay-ms]
  (let [last-at (get-in state [:last-fetch host])
        now (System/currentTimeMillis)
        elapsed (when last-at (- now last-at))
        wait-ms (if (and elapsed (< elapsed delay-ms))
                  (- delay-ms elapsed)
                  0)]
    (when (pos? wait-ms)
      (Thread/sleep wait-ms))
    (assoc-in state [:last-fetch host] (System/currentTimeMillis))))

(defn- content-type
  [headers]
  (some-> (get headers "content-type") (str/split #";") first str/trim))

(defn- extract-links
  [html base-url]
  (let [pattern #"href=\"([^\"]+)\""
        links (->> (re-seq pattern (or html ""))
                   (map second)
                   (remove str/blank?))]
    (->> links
         (keep (fn [href]
                 (try
                   (let [base (URI. base-url)
                         uri (.resolve base href)
                         {:keys [normalized]} (normalize-url (str uri))]
                     normalized)
                   (catch Exception _ nil))))
         distinct
         vec)))

(defn- instrument-key
  [doc host]
  (let [text (or (:text doc) "")
        decree-re (re-pattern "(?i)royal decree\\s+no\\.\\s*([A-Za-z0-9/\\-]+)")
        decree-ar (re-pattern "(?i)المرسوم\\s+الملكي\\s+رقم\\s*([0-9A-Za-z/\\-]+)")
        decree (or (second (re-find decree-re text))
                   (second (re-find decree-ar text))
                   (second (re-find decree-re (or (:title doc) "")))
                   (second (re-find decree-ar (or (:title doc) ""))))
        title (kutil/normalize-text (:title doc))]
    (if decree
      {:key (str "decree:" decree) :kind :decree}
      {:key (str "title:" title "|host:" host) :kind :title :title title})))

(defn- section-tree
  [version-id sections]
  (let [with-ids (map (fn [sec]
                        (assoc sec :section-id (kutil/name-uuid (str version-id ":" (:order sec)))))
                      sections)
        by-order (zipmap (map :order with-ids) with-ids)]
    (map (fn [sec]
           (if-let [pid (:parent-index sec)]
             (assoc sec :parent-id (:section-id (get by-order pid)))
             sec))
         with-ids)))

(defn crawl-url
  [state url]
  (if-let [{:keys [normalized uri]} (normalize-url url)]
    (let [host (.getHost ^URI uri)
          path (.getPath ^URI uri)
          conn (:conn state)]
      (if-not (allowlisted? uri)
        {:state state
         :status :blocked
         :url normalized}
        (let [[state robots] (ensure-robots state host)
              delay (robots/crawl-delay robots "*" (:default-delay-ms state))
              delay-ms (if (and (number? delay) (< delay 1000)) (* 1000 delay) delay)
              state (enforce-delay state host delay-ms)]
          (if-not (robots/allowed? robots "*" path)
            (do
              (store/ensure-url! conn {:normalized normalized
                                       :original url
                                       :status :url.status/blocked})
              {:state state
               :status :blocked
               :url normalized})
            (let [{:keys [status headers body error]} (http/request-bytes normalized {:timeout-ms 10000})
                  content-type (content-type headers)
                  url-eid (store/ensure-url! conn {:normalized normalized
                                                   :original url
                                                   :status (if (<= 200 (or status 0) 299)
                                                             :url.status/fetched
                                                             :url.status/error)})
                  bytes (or body (byte-array 0))
                  sha (kutil/sha256-bytes bytes)
                  blob-path (write-blob! (:storage-dir state) sha bytes)
                  blob-eid (store/ensure-blob! conn {:sha256 sha
                                                     :mime content-type
                                                     :size (alength bytes)
                                                     :uri blob-path})
                  fetch-id (UUID/randomUUID)]
              (store/record-fetch! conn {:fetch-id fetch-id
                                         :status status
                                         :headers headers
                                         :url url-eid
                                         :blob blob-eid
                                         :run (:run-id state)
                                         :error error})
              (if (and status (<= 200 status 299))
                (let [html (when (and content-type (str/includes? content-type "text/html"))
                             (String. ^bytes bytes "UTF-8"))
                      extract (extract/safe-extract content-type bytes html)]
                  (if (:error extract)
                    {:state state :status :error :url normalized}
                    (let [doc-type (classify/infer-doc-type {:host host
                                                             :url normalized
                                                             :title (:title extract)})
                          rank (classify/authority-rank doc-type)
                          doc-id (kutil/name-uuid (str "doc:" sha))
                          instrument-key (instrument-key extract host)
                          instrument-id (kutil/name-uuid (str "instrument:" (:key instrument-key)))
                          version-id (kutil/name-uuid (str instrument-id ":v1"))
                          text (:text extract)
                          spans (:spans extract)
                          instrument-name (or (:title extract) normalized)
                          topics (classify/topics-for {:host host :title instrument-name})
                          db (d/db conn)
                          _ (store/ensure-doc! conn {:doc-id doc-id
                                                     :title instrument-name
                                                     :text text
                                                     :doc-type doc-type
                                                     :source host
                                                     :org host
                                                     :language :lang/unknown
                                                     :repr (if (and content-type (str/includes? content-type "pdf"))
                                                             :doc.repr/pdf
                                                             :doc.repr/html)
                                                     :url url-eid
                                                     :blob blob-eid
                                                     :status :doc.status/active})
                          _ (store/ensure-doc-spans! conn doc-id spans)
                          _ (store/ensure-instrument! conn {:instrument-id instrument-id
                                                            :name instrument-name
                                                            :doc-type doc-type
                                                            :rank rank
                                                            :org host
                                                            :jurisdiction :jurisdiction/ksa
                                                            :languages [:lang/unknown]
                                                            :topics topics
                                                            :status :instrument.status/active})
                          _ (when (and (= (:kind instrument-key) :title)
                                       (seq (:title instrument-key)))
                              (let [existing (store/find-instruments-by-name db instrument-name)
                                    current-eid (ffirst (d/q '[:find ?e :in $ ?id :where [?e :instrument/id ?id]] db instrument-id))
                                    other-eids (remove #(= % current-eid) existing)
                                    other-ids (keep (fn [eid]
                                                      (ffirst (d/q '[:find ?id :in $ ?e :where [?e :instrument/id ?id]] db eid)))
                                                    other-eids)]
                                (doseq [other-id other-ids]
                                  (store/store-possible-same! conn {:from-id instrument-id
                                                                    :to-id other-id}))))
                          _ (store/ensure-instrument-version! conn {:version-id version-id
                                                                    :instrument [:instrument/id instrument-id]
                                                                    :doc-id [:doc/id doc-id]
                                                                    :label "v1"
                                                                    :status :instrument.version.status/current})
                          links (when html (extract-links html normalized))
                          link-refs (when (seq links)
                                      (->> links
                                           (keep (fn [link]
                                                   (when-let [{:keys [normalized]} (normalize-url link)]
                                                     (store/ensure-url! conn {:normalized normalized
                                                                              :original link
                                                                              :status :url.status/seen}))))
                                           vec))
                          source-links (vec (distinct (concat [url-eid] (or link-refs []))))
                          _ (store/ensure-doc! conn {:doc-id doc-id
                                                     :instrument-version [:instrument.version/id version-id]
                                                     :source-links source-links
                                                     :status :doc.status/active})
                          sections (extract/sectionize spans)
                          sections (map-indexed (fn [idx sec]
                                                  (assoc sec :order (inc idx)))
                                                sections)
                          sections (section-tree version-id sections)
                          _ (store/store-sections! conn version-id doc-id
                                                   (map (fn [sec]
                                                          (assoc sec :parent-id (:parent-id sec)))
                                                        sections))
                          xrefs (extract/extract-xrefs text spans)
                          _ (store/store-xrefs! conn xrefs doc-id [:instrument/id instrument-id])]
                      {:state state
                       :status :ok
                       :url normalized
                       :doc-id doc-id})))
                {:state state :status :error :url normalized}))))))
    {:state state :status :error :url nil}))

(defn crawl-urls
  [state urls]
  (reduce (fn [acc url]
            (let [{:keys [state status]} (crawl-url (:state acc) url)
                  metrics (update-in (:metrics acc) [:fetched] (fnil inc 0))
                  metrics (if (= status :ok)
                            (update-in metrics [:ok] (fnil inc 0))
                            (update-in metrics [:failed] (fnil inc 0)))]
              {:state state
               :metrics metrics
               :errors (if (= status :error)
                         (conj (:errors acc) {:url url :status status})
                         (:errors acc))}))
          {:state state :metrics {:fetched 0 :ok 0 :failed 0} :errors []}
          urls))

(defn start-state
  [conn]
  (let [cfg (config/load-config)
        storage (storage-dir cfg)]
    {:conn conn
     :storage-dir storage
     :default-delay-ms (get-in cfg [:knowledge :crawl-delay-ms] default-delay-ms)
     :robots-cache {}
     :last-fetch {}}))
