(ns darelwasl.http.routes.preview
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [darelwasl.http.common :as common]
            [ring.util.codec :as codec]
            [ring.util.response :as resp])
  (:import (java.time Instant Duration)))

(def ^:private preview-cookie-name "preview_token")

(defn- now [] (Instant/now))

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
    (assoc response :cookies
           {preview-cookie-name (cond-> {:value token
                                         :http-only true
                                         :secure (https-request? request)
                                         :same-site :lax
                                         :path (cookie-path run-id)}
                                  (some? max-age) (assoc :max-age max-age))})))

(defn- strip-preview-prefix
  [uri run-id module]
  (let [prefix (str "/_preview/" run-id "/" module)]
    (cond
      (= uri prefix) "/"
      (= uri (str prefix "/")) "/"
      (str/starts-with? uri (str prefix "/")) (subs uri (count prefix))
      :else "/")))

(defn- upstream-port
  [manifest module]
  (let [ports (:ports manifest)]
    (case module
      "app" (some-> ports :app int)
      "site" (some-> ports :site int)
      nil)))

(defn- hop-by-hop-header?
  [k]
  (contains? #{"connection" "keep-alive" "proxy-authenticate" "proxy-authorization"
               "te" "trailer" "transfer-encoding" "upgrade"}
             (str/lower-case (name k))))

(defn- sanitize-headers
  [headers]
  (into {}
        (remove (fn [[k _v]] (hop-by-hop-header? k)))
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
  [request upstream-url]
  (let [method (:request-method request)
        headers (sanitize-headers (:headers request))
        body (:body request)
        content-type (get headers "content-type")
        has-body? (and body (not (#{:get :head} method)))
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
  [_state]
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
              cookie-token (get-in request [:cookies preview-cookie-name :value])
              token (or query-token cookie-token)
              expires-at (parse-instant (:expires_at manifest))]
          (if-not (token-valid? manifest token)
            (common/error-response 403 "Forbidden")
            (let [port (upstream-port manifest module)]
              (if-not port
                (common/error-response 400 "Preview module not available")
                (let [up-path (strip-preview-prefix uri run-id module)
                      up-url (str "http://127.0.0.1:" port up-path (when-let [qs (:query-string request)]
                                                                     (when-not (str/blank? qs)
                                                                       (str "?" qs))))
                      method (:request-method request)
                      set-cookie? (and query-token (token-valid? manifest query-token))
                      resp0 (if (and set-cookie? (#{:get :head} method))
                              (redirect-without-token request)
                              nil)
                      proxied (or resp0
                                  (let [up (proxy-request request up-url)]
                                    {:status (:status up)
                                     :headers (sanitize-headers (:headers up))
                                     :body (:body up)}))
                      out (if set-cookie?
                            (set-token-cookie request proxied run-id query-token expires-at)
                            proxied)]
                  out)))))))))

(defn routes
  [state]
  [["/_preview/*" {:get (handler state)
                   :post (handler state)
                   :put (handler state)
                   :patch (handler state)
                   :delete (handler state)}]])
