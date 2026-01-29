(ns darelwasl.site.http
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.content :as content]
            [darelwasl.site.templates :as templates]
            [ring.util.codec :as codec]
            [ring.util.response :as resp]))

(def ^:private supported-lang-prefixes
  {"ar" :ar
   "ur" :ur})

(defn- strip-trailing-slash
  [raw-path]
  (if (and raw-path (not= raw-path "/") (str/ends-with? raw-path "/"))
    (subs raw-path 0 (dec (count raw-path)))
    raw-path))

(defn- parse-lang-prefix
  "Returns {:lang <kw|nil> :prefix <string> :path <string>}."
  [clean-path]
  (let [parts (->> (str/split (or clean-path "/") #"/")
                   (remove str/blank?))
        [first-seg & more] parts
        lang (get supported-lang-prefixes first-seg)]
    (if lang
      {:lang lang
       :prefix (str "/" first-seg)
       :path (str "/" (str/join "/" more))}
      {:lang nil
       :prefix ""
       :path clean-path})))

(defn- static-path?
  [path]
  (or (str/starts-with? path "/css/")
      (str/starts-with? path "/images/")
      (str/starts-with? path "/js/")
      (= path "/robots.txt")
      (= path "/sitemap.xml")
      (= path "/logo.svg")
      (= path "/logo.jpg")
      (= path "/preview-annotate.js")))

(defn- content-type-for-path
  [path]
  (let [p (str (or path ""))]
    (cond
      (str/ends-with? p ".css") "text/css; charset=utf-8"
      (str/ends-with? p ".js") "application/javascript; charset=utf-8"
      (str/ends-with? p ".svg") "image/svg+xml"
      (str/ends-with? p ".png") "image/png"
      (or (str/ends-with? p ".jpg") (str/ends-with? p ".jpeg")) "image/jpeg"
      (str/ends-with? p ".webp") "image/webp"
      (str/ends-with? p ".xml") "application/xml; charset=utf-8"
      (str/ends-with? p ".txt") "text/plain; charset=utf-8"
      :else nil)))

(defn- maybe-no-cache
  "Avoid clients getting stuck on stale CSS/JS/logo during rapid iterations."
  [path response]
  (if (or (str/starts-with? path "/css/")
          (str/starts-with? path "/js/")
          (str/ends-with? path ".svg"))
    (resp/header response "Cache-Control" "no-cache")
    response))

(defn- request-public-base-url
  "Derives the public base URL from reverse-proxy headers."
  [request]
  (let [headers (into {} (for [[k v] (:headers request)] [(str/lower-case (name k)) v]))
        proto (or (get headers "x-forwarded-proto")
                  (some-> (:scheme request) name)
                  "http")
        host (or (get headers "x-forwarded-host")
                 (get headers "host")
                 (:server-name request)
                 "localhost")]
    (str proto "://" host)))

(defn- content-context
  [conn]
  (let [data (content/list-content-v2 conn)
        {:keys [error businesses contacts]} data]
    (if error
      {:error error}
      {:contact (templates/select-contact businesses contacts)})))

(defn handle-request
  [{:keys [db config]} request]
  (let [start (System/nanoTime)
        conn (:conn db)
        base-path (or (get-in config [:site :base-path]) "")
        raw-path (:uri request)
        query (codec/form-decode (or (:query-string request) ""))
        clean-path (strip-trailing-slash raw-path)
        {:keys [lang prefix path]} (parse-lang-prefix clean-path)
        public-base-url (request-public-base-url request)]
    (try
      (let [response (cond
                       (= path "/health")
                       {:status 200
                        :headers {"Content-Type" "text/plain; charset=utf-8"}
                        :body "ok"}

                       (static-path? path)
                       (let [static-resp (resp/file-response (subs path 1) {:root "public"})]
                         (if static-resp
                           (let [ctype (content-type-for-path path)
                                 typed (if ctype
                                         (resp/content-type static-resp ctype)
                                         static-resp)]
                             (maybe-no-cache path typed))
                           (templates/public-not-found {:public-base-url public-base-url
                                                        :base-path base-path
                                                        :lang lang
                                                        :path path})))

                       :else
                       (let [{:keys [error contact]} (content-context conn)]
                         (cond
                           error
                           {:status 500
                            :headers {"Content-Type" "text/plain; charset=utf-8"}
                            :body (str "Content unavailable: " (:message error "unexpected error"))}

                           :else
                           (templates/public-route {:public-base-url public-base-url
                                                    :base-path base-path
                                                    :lang lang
                                                    :path path
                                                    :query query
                                                    :contact contact}))))
            dur-ms (/ (double (- (System/nanoTime) start)) 1e6)]
        (log/infof "site request path=%s status=%s dur=%.1fms"
                   clean-path
                   (:status response)
                   dur-ms)
        response)
      (catch Exception e
        (let [dur-ms (/ (double (- (System/nanoTime) start)) 1e6)]
          (log/error e (format "site request path=%s crashed after %.1fms" clean-path dur-ms))
          {:status 500
           :headers {"Content-Type" "text/plain; charset=utf-8"}
           :body "Site error"})))))

(defn app
  "Ring handler for the public site process."
  [state]
  (fn [request]
    (handle-request state request)))
