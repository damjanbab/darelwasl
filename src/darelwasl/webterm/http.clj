(ns darelwasl.webterm.http
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [darelwasl.webterm.config :as cfg]
            [darelwasl.webterm.fs :as fs]
            [darelwasl.webterm.tmux :as tmux]
            [darelwasl.webterm.ui :as ui]
            [ring.middleware.cookies :as cookies]
            [ring.middleware.multipart-params :as multipart]
            [ring.middleware.params :as params]
            [ring.util.codec :as codec]
            [ring.util.response :as resp])
  (:import (java.io ByteArrayInputStream)
           (java.nio.file Files Path)
           (java.time Instant)))

(defn- parse-int
  [s default]
  (try
    (Integer/parseInt (str s))
    (catch Exception _ default)))

(defn- clamp-session
  [cfg n]
  (fs/clamp n 1 (:terminal-count cfg)))

(defn- cookie
  [req k]
  (get-in req [:cookies k :value]))

(defn- lab-session
  [cfg req]
  (let [stable (:lab-stable-session cfg)
        qp (get-in req [:query-params "session"])
        n (when (some? qp) (parse-int qp stable))
        n (if (some? qp) n (parse-int (cookie req "dw_lab_session") stable))]
    (clamp-session cfg n)))

(defn- ui-role
  [cfg]
  (if (= (:public-base-path cfg) "/canary") "canary" "stable"))

(defn json-response
  [payload]
  (-> (resp/response (json/write-str payload))
      (resp/content-type "application/json; charset=utf-8")))

(defn text-response
  [s]
  (-> (resp/response (str s))
      (resp/content-type "text/plain; charset=utf-8")))

(defn html-response
  [s]
  (-> (resp/response s)
      (resp/content-type "text/html; charset=utf-8")))

(defn- file-response
  [^Path path content-type disposition filename]
  (let [st (Files/readAttributes path "basic:*" (make-array java.nio.file.LinkOption 0))
        size (.size st)
        body (Files/newInputStream path (make-array java.nio.file.OpenOption 0))]
    {:status 200
     :headers {"Content-Type" content-type
               "Content-Length" (str size)
               "Content-Disposition" (str disposition "; filename=\"" (str/replace filename #"\"" "_") "\"")}
     :body body}))

(defn- guess-content-type
  [name]
  (let [ext (some-> name str/lower-case (re-find #"\.[a-z0-9]+$"))]
    (cond
      (= ext ".pdf") "application/pdf"
      (= ext ".png") "image/png"
      (or (= ext ".jpg") (= ext ".jpeg")) "image/jpeg"
      (= ext ".gif") "image/gif"
      (= ext ".webp") "image/webp"
      (= ext ".svg") "image/svg+xml"
      (contains? #{".txt" ".md" ".markdown" ".log" ".json" ".edn" ".csv"} ext) "text/plain; charset=utf-8"
      :else "application/octet-stream")))

(defn- ensure-lab!
  [cfg sess]
  (let [name (tmux/session-name cfg sess)]
    (tmux/ensure-session! cfg sess)
    (fs/ensure-dirs! (:lab-dir cfg) name)
    name))

(defn- handle-sessions
  [cfg _req]
  (let [sessions (tmux/list-sessions cfg)]
    (json-response {:count (:terminal-count cfg)
                    :sessions (vec (for [n (range 1 (inc (:terminal-count cfg)))]
                                     {:n n
                                      :name (tmux/session-name cfg n)
                                      :exists (contains? sessions (tmux/session-name cfg n))
                                      :url (ui/xterm-url cfg n)}))})))

(defn- next-available
  [cfg]
  (let [sessions (tmux/list-sessions cfg)]
    (first (for [n (range 1 (inc (:terminal-count cfg)))
                 :let [name (tmux/session-name cfg n)]
                 :when (not (contains? sessions name))]
             n))))

(defn- redirect
  [location]
  {:status 302
   :headers {"Location" location}
   :body ""})

(defn- handle-lab
  [cfg req build-stamp]
  (let [sess (lab-session cfg req)]
    (ensure-lab! cfg sess)
    (-> (html-response (ui/lab-page cfg {:sess sess :build-stamp build-stamp :ui-role (ui-role cfg)}))
        (resp/header "Set-Cookie" (str "dw_lab_session=" sess "; Path=/; Max-Age=31536000; SameSite=Lax")))))

(defn- list-dir
  [cfg sess which]
  (let [sname (ensure-lab! cfg sess)
        {:keys [inbox outbox]} (fs/ensure-dirs! (:lab-dir cfg) sname)]
    (json-response
     {:lab_session sess
      :tmux_session sname
      which (fs/list-files (if (= which :inbox) inbox outbox))})))

(defn- handle-upload
  [cfg req build-stamp]
  (let [sess (lab-session cfg req)
        sname (ensure-lab! cfg sess)
        dir (some-> (get-in req [:query-params "dir"]) str/lower-case str/trim)
        dir (if (#{"inbox" "outbox"} dir) dir "outbox")
        {:keys [inbox outbox]} (fs/ensure-dirs! (:lab-dir cfg) sname)
        f (get-in req [:params "file"])
        size (some-> f :size long)
        tempfile (some-> f :tempfile)
        filename (fs/safe-name (some-> f :filename) "upload")]
    (when (or (nil? tempfile) (not (.exists ^java.io.File tempfile)))
      (throw (ex-info "missing file" {})))
    (when (and size (> size (:lab-max-upload-bytes cfg)))
      (throw (ex-info "upload too large" {:status 413})))
    (let [dest-dir (if (= dir "inbox") inbox outbox)
          saved (fs/write-upload! dest-dir filename tempfile)]
      (handle-lab cfg req build-stamp))))

(defn- handle-paste
  [cfg req]
  (let [sess (lab-session cfg req)
        sname (ensure-lab! cfg sess)
        {:keys [inbox outbox]} (fs/ensure-dirs! (:lab-dir cfg) sname)
        body-text (try
                    (let [b (:body req)]
                      (cond
                        (nil? b) ""
                        (instance? java.io.InputStream b) (with-open [r (java.io.InputStreamReader. ^java.io.InputStream b "UTF-8")] (slurp r))
                        (instance? java.io.Reader b) (slurp ^java.io.Reader b)
                        :else (str b)))
                    (catch Exception _ ""))
        obj (try (json/read-str body-text) (catch Exception _ {}))
        dir (some-> (get obj "dir") str/lower-case str/trim)
        dir (if (#{"inbox" "outbox"} dir) dir "inbox")
        name (fs/safe-name (get obj "name") (str "paste-" (System/currentTimeMillis) ".txt"))
        content (or (get obj "content") "")
        raw (.getBytes (str content) "UTF-8")
        dest-dir (if (= dir "inbox") inbox outbox)
        dest (fs/write-bytes! dest-dir name raw)]
    (json-response {:dir dir
                    :name (str dest)
                    :size_bytes (alength ^bytes raw)
                    :written_at (str (Instant/now))})))

(defn- handle-history
  [cfg req]
  (let [sess (lab-session cfg req)
        sname (ensure-lab! cfg sess)
        lines (parse-int (get-in req [:query-params "lines"]) (:lab-default-history-lines cfg))
        text (tmux/capture-history cfg sess lines)]
    (json-response {:lab_session sess
                    :tmux_session sname
                    :captured_at (str (Instant/now))
                    :lines_requested lines
                    :text text})))

(defn- handle-terminal-clear
  [cfg req]
  (let [sess (lab-session cfg req)
        sname (ensure-lab! cfg sess)]
    (tmux/clear-terminal! cfg sess)
    (json-response {:ok true :lab_session sess :tmux_session sname})))

(defn- handle-outbox-download
  [cfg req disposition]
  (let [sess (lab-session cfg req)
        sname (ensure-lab! cfg sess)
        name (get-in req [:query-params "name"])
        path (fs/resolve-outbox-path (:lab-dir cfg) sname name)]
    (if-not (and path
                 (Files/exists path (make-array java.nio.file.LinkOption 0))
                 (Files/isRegularFile path (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS]))
                 (not (Files/isSymbolicLink path)))
      (resp/not-found "not found\n")
      (let [fn (fs/safe-name name "download")
            ctype (guess-content-type fn)]
        (file-response path ctype disposition fn)))))

(defn app
  "Returns a Ring handler for the webterm UI (standalone service)."
  []
  (let [cfg (cfg/config)
        build-stamp (str (Instant/now))]
    (-> (fn [req]
          (try
            (let [uri (:uri req)
                  m (:request-method req)]
              (cond
                (and (= m :get) (= uri "/")) (html-response (ui/terminals-page cfg))
                (and (= m :get) (= uri "/api/sessions")) (handle-sessions cfg req)
                (and (= m :get) (= uri "/new"))
                (if-let [n (next-available cfg)]
                  (do (tmux/ensure-session! cfg n) (redirect (ui/xterm-url cfg n)))
                  (-> (text-response "No free terminals.\n") (assoc :status 409)))
                (and (= m :get) (= uri "/open"))
                (let [n (parse-int (get-in req [:query-params "n"]) 0)]
                  (if (<= 1 n (:terminal-count cfg))
                    (do (tmux/ensure-session! cfg n) (redirect (ui/xterm-url cfg n)))
                    (-> (text-response "bad n\n") (assoc :status 400))))
                (and (= m :get) (= uri "/codex"))
                (let [n (parse-int (get-in req [:query-params "n"]) 0)]
                  (if (<= 1 n (:terminal-count cfg))
                    (do (tmux/ensure-session! cfg n) (tmux/start-codex! cfg n) (redirect (ui/xterm-url cfg n)))
                    (-> (text-response "bad n\n") (assoc :status 400))))
                (and (= m :get) (= uri "/kill"))
                (let [n (parse-int (get-in req [:query-params "n"]) 0)]
                  (if (<= 1 n (:terminal-count cfg))
                    (do (tmux/kill-session! cfg n) (html-response (ui/terminals-page cfg)))
                    (-> (text-response "bad n\n") (assoc :status 400))))

                (and (= m :get) (= uri "/lab")) (handle-lab cfg req build-stamp)
                (and (= m :get) (= uri "/api/lab/inbox")) (list-dir cfg (lab-session cfg req) :inbox)
                (and (= m :get) (= uri "/api/lab/outbox")) (list-dir cfg (lab-session cfg req) :outbox)
                (and (= m :get) (= uri "/api/lab/outbox/download")) (handle-outbox-download cfg req "attachment")
                (and (= m :get) (= uri "/api/lab/outbox/view")) (handle-outbox-download cfg req "inline")
                (and (= m :get) (= uri "/api/lab/history")) (handle-history cfg req)
                (and (= m :post) (= uri "/api/lab/terminal/clear")) (handle-terminal-clear cfg req)
                (and (= m :post) (= uri "/api/lab/upload")) (handle-upload cfg req build-stamp)
                (and (= m :post) (= uri "/api/lab/paste")) (handle-paste cfg req)

                :else (resp/not-found "not found\n")))
            (catch clojure.lang.ExceptionInfo e
              (let [{:keys [status]} (ex-data e)]
                (-> (text-response (or (.getMessage e) "error"))
                    (assoc :status (or status 500)))))
            (catch Exception e
              (-> (text-response (or (.getMessage e) "error"))
                  (assoc :status 500)))))
        cookies/wrap-cookies
        params/wrap-params
        multipart/wrap-multipart-params)))
