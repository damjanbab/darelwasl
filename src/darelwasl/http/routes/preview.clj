(ns darelwasl.http.routes.preview
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [darelwasl.files :as files]
            [darelwasl.http.common :as common]
            [ring.util.codec :as codec]
            [ring.util.response :as resp])
  (:import (java.time Instant Duration)))

(def ^:private preview-cookie-name "preview_token")

(defn- now [] (Instant/now))

(def ^:private refs-lock (Object.))
(def ^:private assets-lock (Object.))

(def ^:private max-asset-bytes (* 10 1024 1024))
(def ^:private allowed-asset-mimes
  #{"image/png" "image/jpeg" "image/webp" "image/svg+xml"})

(defn- parse-instant
  [s]
  (try
    (when (and s (string? s) (not (str/blank? s)))
      (Instant/parse s))
    (catch Exception _
      nil)))

(defn- preview-manifest
  [run-id]
  (let [root (or (System/getenv "PREVIEW_MANIFEST_ROOT") "target/previews")
        path (io/file root run-id "preview.json")]
    (when (.exists path)
      (try
        (json/read-str (slurp path) :key-fn keyword)
        (catch Exception e
          (log/warn e "Failed to read preview manifest" {:path (.getPath path)})
          nil)))))

(defn- token-valid?
  [manifest token]
  (let [expected (:token manifest)]
    (and (string? expected)
         (not (str/blank? expected))
         (= expected token))))

(defn- agent-control-data-dir []
  (or (some-> (System/getenv "AGENT_CONTROL_DATA_DIR") str/trim not-empty)
      "data/agent-control"))

(defn- agent-control-run-path
  [run-id]
  (io/file (agent-control-data-dir) "runs" run-id "run.json"))

(defn- read-json-file
  [^java.io.File f]
  (try
    (when (.exists f)
      (json/read-str (slurp f) :key-fn keyword))
    (catch Exception e
      (log/warn e "Failed to read json" {:path (.getPath f)})
      nil)))

(defn- write-json-file!
  [^java.io.File f data]
  (let [tmp (io/file (str (.getPath f) ".tmp"))
        parent (.getParentFile f)]
    (when (and parent (not (.exists parent)))
      (.mkdirs parent))
    (spit tmp (json/write-str data :escape-slash false))
    (.renameTo tmp f)))

(defn- clamp
  [s max-len]
  (let [t (str (or s ""))]
    (if (<= (count t) max-len) t (subs t 0 max-len))))

(defn- normalize-ref
  [r]
  (when (map? r)
    (let [id (some-> (or (:id r) (get r "id")) str/trim not-empty)
          at (some-> (or (:at r) (get r "at")) str/trim not-empty)
          url (some-> (or (:url r) (get r "url")) str)
          selector (some-> (or (:selector r) (get r "selector")) str)
          text (some-> (or (:text r) (get r "text")) str)
          note (some-> (or (:note r) (get r "note")) str)]
      (when id
        {:id (clamp id 120)
         :at (clamp at 64)
         :url (clamp url 800)
         :selector (clamp selector 800)
         :text (clamp text 240)
         :note (clamp note 800)}))))

(defn- storage-dir
  [state]
  (get-in state [:config :files :storage-dir]))

(defn- file-ext
  [filename]
  (let [name (some-> filename str/trim)]
    (when (and (string? name) (str/includes? name "."))
      (some-> name (str/split #"\.") last str/lower-case str/trim not-empty))))

(defn- normalize-asset-upload
  "Attempts to normalize common browser MIME issues for SVG uploads."
  [upload]
  (if-not (map? upload)
    nil
    (let [filename (:filename upload)
          ext (file-ext filename)]
      (cond
        (= ext "svg") (assoc upload :content-type "image/svg+xml")
        :else upload))))

(defn- upload-size-bytes
  [upload]
  (let [size (:size upload)
        tempfile (:tempfile upload)]
    (cond
      (number? size) (long size)
      (instance? java.io.File tempfile) (.length ^java.io.File tempfile)
      :else 0)))

(defn- normalize-asset
  [a]
  (when (map? a)
    (let [id (some-> (or (:id a) (get a "id")) str/trim not-empty)
          at (some-> (or (:at a) (get a "at")) str/trim not-empty)
          name (some-> (or (:name a) (get a "name")) str)
          ref (some-> (or (:ref a) (get a "ref")) str)
          mime (some-> (or (:mime a) (get a "mime")) str)
          url (some-> (or (:url a) (get a "url")) str)
          local-path (some-> (or (:local_path a) (get a "local_path")) str)
          note (some-> (or (:note a) (get a "note")) str)]
      (when id
        {:id (clamp id 120)
         :at (clamp at 64)
         :name (clamp name 240)
         :ref (clamp ref 240)
         :mime (clamp mime 120)
         :url (clamp url 800)
         :local_path (clamp local-path 800)
         :note (clamp note 800)}))))

(defn- upsert-refs!
  [run-id refs]
  (locking refs-lock
    (let [path (agent-control-run-path run-id)
          existing (or (read-json-file path) {:id run-id})
          safe (->> (or refs [])
                    (map normalize-ref)
                    (remove nil?)
                    (take 30)
                    vec)]
      (if-not (.exists ^java.io.File path)
        nil
        (let [next (assoc existing
                          :site_refs safe
                          :updated_at (.toString (Instant/now)))]
          (write-json-file! path next)
          next)))))

(defn- clear-refs!
  [run-id]
  (locking refs-lock
    (let [path (agent-control-run-path run-id)
          existing (read-json-file path)]
      (when existing
        (let [next (-> existing
                       (assoc :site_refs [])
                       (assoc :updated_at (.toString (Instant/now))))]
          (write-json-file! path next)
          next)))))

(defn- read-refs
  [run-id]
  (let [path (agent-control-run-path run-id)
        existing (read-json-file path)]
    (when existing
      (vec (or (:site_refs existing) [])))))

(defn- read-assets
  [run-id]
  (let [path (agent-control-run-path run-id)
        existing (read-json-file path)]
    (when existing
      (vec (or (:site_assets existing) [])))))

(defn- upsert-assets!
  [run-id asset]
  (locking assets-lock
    (let [path (agent-control-run-path run-id)
          existing (read-json-file path)
          safe (normalize-asset asset)]
      (cond
        (nil? existing) nil
        (nil? safe) existing
        :else
        (let [prev (vec (or (:site_assets existing) []))
              next-assets (->> (cons safe (remove #(= (:id %) (:id safe)) prev))
                               (take 30)
                               vec)
              next (assoc existing
                          :site_assets next-assets
                          :updated_at (.toString (Instant/now)))]
          (write-json-file! path next)
          next)))))

(defn- remove-asset!
  [run-id asset-id]
  (locking assets-lock
    (let [path (agent-control-run-path run-id)
          existing (read-json-file path)]
      (when existing
        (let [id (str asset-id)
              next-assets (->> (vec (or (:site_assets existing) []))
                               (remove #(= (:id %) id))
                               vec)
              next (assoc existing
                          :site_assets next-assets
                          :updated_at (.toString (Instant/now)))]
          (write-json-file! path next)
          next)))))

(defn- clear-assets!
  [run-id]
  (locking assets-lock
    (let [path (agent-control-run-path run-id)
          existing (read-json-file path)]
      (when existing
        (let [next (-> existing
                       (assoc :site_assets [])
                       (assoc :updated_at (.toString (Instant/now))))]
          (write-json-file! path next)
          next)))))

(defn- expired?
  [manifest]
  (let [expires-at (parse-instant (:expires_at manifest))]
    (and expires-at (.isAfter (now) expires-at))))

(defn- cookie-path
  [run-id]
  (str "/_preview/" run-id "/"))

(defn- https-request?
  [request]
  (let [proto (some-> (get-in request [:headers "x-forwarded-proto"]) str/lower-case)
        scheme (:scheme request)]
    (or (= scheme :https)
        (= proto "https"))))

(defn- set-token-cookie
  [request response run-id token expires-at]
  (let [max-age (when expires-at
                  (max 0 (long (.getSeconds (Duration/between (now) expires-at)))))]
    (update response :cookies
            (fn [existing]
              (merge (or existing {})
                     {preview-cookie-name (cond-> {:value token
                                                   :http-only true
                                                   :secure (https-request? request)
                                                   :same-site :lax
                                                   :path (cookie-path run-id)}
                                            (some? max-age) (assoc :max-age max-age))})))))

(def ^:private ring-cookie-attr-keys
  #{:value :path :domain :max-age :expires :secure :http-only :same-site})

(defn- sanitize-upstream-cookies
  "clj-http parses Set-Cookie into :cookies, but it includes attributes that
  ring.middleware.cookies doesn't accept (e.g. :discard, :version). Convert to
  ring cookie maps and drop unsupported keys."
  [cookies]
  (when (map? cookies)
    (into {}
          (keep (fn [[k v]]
                  (let [cookie-name (some-> k name str/trim not-empty)]
                    (when cookie-name
                      (let [attrs (if (map? v) v {})
                            cookie (select-keys attrs ring-cookie-attr-keys)]
                        [cookie-name cookie])))))
          cookies)))

(defn- strip-preview-prefix
  [uri run-id module]
  (let [prefix (str "/_preview/" run-id "/" module)]
    (cond
      (= uri prefix) "/"
      (= uri (str prefix "/")) "/"
      (str/starts-with? uri (str prefix "/")) (subs uri (count prefix))
      :else "/")))

(defn- json-response
  [status body]
  {:status status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body (json/write-str body)})

(defn- parse-json-body
  [request]
  (try
    (let [raw (slurp (:body request))
          trimmed (str/trim (or raw ""))]
      (if (str/blank? trimmed)
        {}
        (json/read-str trimmed :key-fn keyword)))
    (catch Exception _
      ::invalid-json)))

(defn- handle-agent-api
  [state request run-id]
  (try
    (let [uri (:uri request)
          path (strip-preview-prefix uri run-id "agent")
          method (:request-method request)]
      (cond
        (and (= method :get) (or (= path "/") (= path "/refs")))
        (let [refs (read-refs run-id)]
          (if (nil? refs)
            (json-response 404 {:error "run_not_found"})
            (json-response 200 {:status "ok" :refs refs})))

        (and (= method :post) (= path "/refs"))
        (let [body (parse-json-body request)]
          (if (= body ::invalid-json)
            (json-response 400 {:error "invalid_json"})
            (let [refs (:refs body)
                  updated (upsert-refs! run-id refs)]
              (if-not updated
                (json-response 404 {:error "run_not_found"})
                (json-response 200 {:status "ok" :count (count (or (:site_refs updated) []))})))))

        (and (= method :delete) (or (= path "/refs") (= path "/refs/clear")))
        (let [updated (clear-refs! run-id)]
          (if-not updated
            (json-response 404 {:error "run_not_found"})
            (json-response 200 {:status "ok"})))

        (and (= method :get) (or (= path "/assets") (= path "/assets/")))
        (let [assets (read-assets run-id)]
          (if (nil? assets)
            (json-response 404 {:error "run_not_found"})
            (json-response 200 {:status "ok" :assets assets})))

        (and (= method :post) (or (= path "/assets") (= path "/assets/")))
        (let [params (merge (:params request) (:multipart-params request))
              upload0 (or (get params "file") (get params :file))
              upload (normalize-asset-upload upload0)
              note (some-> (or (get params "note") (get params :note)) str)
              slug (some-> (or (get params "slug") (get params :slug)) str)
              size (upload-size-bytes upload)
              mime (some-> (:content-type upload) str/lower-case str/trim)
              tempfile (:tempfile upload)
              storage (storage-dir state)
              conn (get-in state [:db :conn])]
          (cond
            (nil? (read-json-file (agent-control-run-path run-id)))
            (json-response 404 {:error "run_not_found"})

            (nil? upload)
            (json-response 400 {:error "file_required"})

            (nil? tempfile)
            (json-response 400 {:error "upload_missing_tempfile"})

            (and (instance? java.io.File tempfile) (not (.exists ^java.io.File tempfile)))
            (json-response 400 {:error "upload_tempfile_not_found"})

            (> size max-asset-bytes)
            (json-response 413 {:error "file_too_large" :max_bytes max-asset-bytes})

            (not (contains? allowed-asset-mimes mime))
            (json-response 400 {:error "unsupported_file_type"
                                :allowed (vec allowed-asset-mimes)
                                :mime mime})

            :else
            (try
              (let [res (files/create-file! conn {:file upload :slug slug} nil storage)
                    err (:error res)]
                (if err
                  (json-response (or (:status err) 500) {:error (:message err) :details (:details err)})
                  (let [file (:file res)
                        file-id (:file/id file)
                        file-meta (:file (files/fetch-file conn file-id nil))
                        storage-path (:file/storage-path file-meta)
                        local-path (when (and storage storage-path)
                                     (.getPath (io/file storage storage-path)))
                        asset {:id (str file-id)
                               :at (.toString (Instant/now))
                               :name (:file/name file)
                               :ref (:file/ref file)
                               :mime (:file/mime file)
                               :url (:file/url file)
                               :local_path local-path
                               :note note}
                        updated (upsert-assets! run-id asset)]
                    (if-not updated
                      (json-response 404 {:error "run_not_found"})
                      (json-response 201 {:status "ok" :asset asset :count (count (:site_assets updated))})))))
              (catch Exception e
                (log/error e "Failed to upload asset for preview run"
                           {:run-id run-id
                            :filename (:filename upload)
                            :mime (:content-type upload)
                            :tempfile (str tempfile)
                            :tempfile_exists (when (instance? java.io.File tempfile) (.exists ^java.io.File tempfile))
                            :storage storage})
                (json-response 500 {:error "asset_upload_failed"
                                    :exception (.getName (class e))
                                    :message (.getMessage e)})))
          ))

        (and (= method :delete) (or (= path "/assets") (= path "/assets/")))
        (let [updated (clear-assets! run-id)]
          (if-not updated
            (json-response 404 {:error "run_not_found"})
            (json-response 200 {:status "ok"})))

        (and (= method :delete) (str/starts-with? path "/assets/"))
        (let [asset-id (some-> (subs path (count "/assets/")) str/trim not-empty)
              updated (when asset-id (remove-asset! run-id asset-id))]
          (if-not updated
            (json-response 404 {:error "run_not_found"})
            (json-response 200 {:status "ok"})))

        :else
        (json-response 404 {:error "not_found" :path path})))
    (catch Exception e
      (log/error e "Preview agent API failed" {:run-id run-id})
      (json-response 500 {:error "agent_api_failed"
                          :exception (.getName (class e))
                          :message (.getMessage e)}))))
(defn- upstream-port
  [manifest module]
  (let [ports (:ports manifest)]
    (case module
      "app" (some-> ports :app int)
      "site" (some-> ports :site int)
      "agent" 0
      nil)))

(defn- hop-by-hop-header?
  [k]
  (contains? #{"connection" "keep-alive" "proxy-authenticate" "proxy-authorization"
               "te" "trailer" "transfer-encoding" "upgrade"}
             (str/lower-case (name k))))

(defn- proxy-unsafe-request-header?
  "Headers that are safe for the inbound client request but unsafe to forward
  as-is when proxying with a new outbound HTTP client."
  [k]
  (contains? #{"content-length" "host"}
             (str/lower-case (name k))))

(defn- sanitize-headers
  [headers]
  (into {}
        (remove (fn [[k _v]]
                  (or (hop-by-hop-header? k)
                      (proxy-unsafe-request-header? k))))
        headers))

(defn- redirect-without-token
  [request]
  (let [uri (:uri request)
        qs (or (:query-string request) "")
        params (->> (codec/form-decode qs)
                    (remove (fn [[k _]] (= k "t")))
                    (into {}))
        new-qs (codec/form-encode params)]
    (resp/redirect (if (str/blank? new-qs)
                     uri
                     (str uri "?" new-qs)))))

(defn- proxy-request
  [request upstream-url preview-token]
  (let [method (:request-method request)
        headers (sanitize-headers (:headers request))
        headers (cond-> headers
                  (and (string? preview-token) (not (str/blank? preview-token)))
                  (assoc "x-preview-token" preview-token))
        body (:body request)
        content-type (get headers "content-type")
        has-body? (and body (not (#{:get :head} method)))
        body-params (or (:body-params request)
                        (get-in request [:parameters :body]))
        form-params (or (:form-params request)
                        (get-in request [:parameters :form]))
        ;; NOTE: Reitit/Muuntaja middleware may already have consumed the raw
        ;; request body stream in order to populate :body-params. When proxying,
        ;; re-serialize structured params for common JSON API calls so the
        ;; upstream preview app receives the expected payload.
        [body content-type] (cond
                              (and (not (#{:get :head} method))
                                   (map? body-params)
                                   (or (nil? content-type)
                                       (str/includes? (str/lower-case content-type) "application/json")))
                              [(json/write-str body-params) "application/json"]

                              (and (not (#{:get :head} method))
                                   (map? form-params)
                                   (some-> content-type str/lower-case (str/includes? "application/x-www-form-urlencoded")))
                              [(codec/form-encode form-params) "application/x-www-form-urlencoded"]

                              :else [body content-type])
        has-body? (and body (not (#{:get :head} method)))
        headers (cond-> headers
                  (some? content-type) (assoc "content-type" content-type))
        req {:method method
             :url upstream-url
             :throw-exceptions false
             :as :byte-array
             :headers headers
             :body (when has-body? body)
             :content-type content-type
             :socket-timeout 30000
             :conn-timeout 30000}]
    (http/request req)))

(defn- handler
  [state]
  (fn [request]
    (let [uri (:uri request)
          [_ run-id module] (re-matches #"/_preview/([^/]+)/([^/]+)(?:/.*)?$" uri)
          manifest (when run-id (preview-manifest run-id))]
      (cond
        (or (nil? run-id) (nil? module))
        (common/error-response 404 "Not found")

        (nil? manifest)
        (common/error-response 404 "Preview not found")

        (expired? manifest)
        (common/error-response 410 "Preview expired")

        :else
        (let [query-token (or (get-in request [:query-params "t"])
                              (get-in request [:parameters :query "t"]))
              query-token (or query-token
                              (some-> (:query-string request)
                                      codec/form-decode
                                      (get "t")))
              cookie-token (get-in request [:cookies preview-cookie-name :value])
              token (or query-token cookie-token)
              expires-at (parse-instant (:expires_at manifest))]
          (if-not (token-valid? manifest token)
            (common/error-response 403 "Forbidden")
            (cond
              (= module "approve")
              (let [cmd-str (str "scripts/work-prod.sh approve-proof " (pr-str run-id))
                    cmd ["bash" "-lc" cmd-str]
                    pb (ProcessBuilder. ^java.util.List cmd)
                    out (java.io.ByteArrayOutputStream.)
                    _ (.directory pb (io/file (System/getProperty "user.dir")))
                    _ (doto (.environment pb)
                        (.put "DEPLOY_APPROVED" "1"))
                    _ (.redirectErrorStream pb true)
                    p (.start pb)
                    _ (with-open [in (.getInputStream p)]
                        (.transferTo in out))
                    code (.waitFor p)
                    text (.toString out "UTF-8")]
                (if (zero? (long code))
                  {:status 200
                   :headers {"Content-Type" "text/plain; charset=utf-8"}
                   :body text}
                  {:status 500
                   :headers {"Content-Type" "text/plain; charset=utf-8"}
                   :body (str "approve failed (exit " code ")\n\n" text)}))

              (= module "agent")
              (handle-agent-api state request run-id)

              :else
              (let [port (upstream-port manifest module)]
                (if-not port
                  (common/error-response 400 "Preview module not available")
                  (let [up-path (strip-preview-prefix uri run-id module)
                        up-url (str "http://127.0.0.1:" port up-path (when-let [qs (:query-string request)]
                                                                       (when-not (str/blank? qs)
                                                                         (str "?" qs))))
                        method (:request-method request)
                        ;; Only set the preview token cookie on the top-level entry route.
                        ;; This avoids clobbering upstream Set-Cookie headers (e.g. ring-session)
                        ;; on API calls like /health and /api/login.
                        set-cookie? (and query-token
                                         (token-valid? manifest query-token)
                                         (#{:get :head} method)
                                         (= up-path "/"))
                        ;; Only redirect the top-level entry route (/) to strip
                        ;; the token from the URL after setting a cookie.
                        ;; API clients (including our smoke tests) typically
                        ;; don't follow redirects for health checks.
                        resp0 (when set-cookie?
                                (redirect-without-token request))
                        proxied (or resp0
                                    (let [up (proxy-request request up-url token)]
                                      {:status (:status up)
                                       :headers (sanitize-headers (:headers up))
                                       :body (:body up)
                                       :cookies (sanitize-upstream-cookies (:cookies up))}))
                        out (if set-cookie?
                              (set-token-cookie request proxied run-id query-token expires-at)
                              proxied)]
                    out))))))))))

(defn routes
  [state]
  [["/_preview/*" {:get (handler state)
                   :post (handler state)
                   :put (handler state)
                   :patch (handler state)
                   :delete (handler state)}]])
