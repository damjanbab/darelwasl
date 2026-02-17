(ns darelwasl.http.common
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.db :as db]
            [darelwasl.http.session-store :as session-store]
            [darelwasl.workspace :as workspace]))

(defn- env
  [k]
  (some-> (System/getenv k) str/trim not-empty))

(defn- preview-token
  []
  (env "PREVIEW_TOKEN"))

(defn- header
  [request k]
  (or (get-in request [:headers k])
      (get-in request [:headers (str/lower-case k)])
      (get-in request [:headers (str/upper-case k)])))

(defn- preview-request?
  "A request is treated as an auto-auth preview request when:
  - PREVIEW_TOKEN is set in the process environment, and
  - the caller provided the matching token via one of:
    - X-Preview-Token header (preferred), or
    - preview cookie (set by the preview proxy), or
    - ?t= query param (used on initial entry links).

  This is intentionally environment-gated so production processes are unaffected."
  [request]
  (let [expected (preview-token)
        actual (or (some-> (header request "x-preview-token") str/trim not-empty)
                   (some-> (get-in request [:cookies "preview_token" :value]) str/trim not-empty)
                   (some-> (get-in request [:query-params "t"]) str/trim not-empty)
                   (some-> (get-in request [:parameters :query "t"]) str/trim not-empty)
                   (some-> (get-in request [:params "t"]) str/trim not-empty))]
    (and (string? expected)
         (not (str/blank? expected))
         (= expected actual))))

(defn- parse-roles
  [s]
  (->> (str/split (or s "") #",")
       (map str/trim)
       (remove str/blank?)
       (map (fn [x]
              (let [x (if (str/starts-with? x ":") (subs x 1) x)]
                (keyword x))))
       vec))

(defn- preview-auto-session
  "Build a synthetic session used only for preview auto-auth.

  Configure with:
  - PREVIEW_AUTO_LOGIN_USER_ID (uuid string)
  - PREVIEW_AUTO_LOGIN_USERNAME (string)
  - PREVIEW_AUTO_LOGIN_ROLES (comma-separated keywords, e.g. :role/admin,:role/content-editor)
  "
  []
  (when-let [uid (env "PREVIEW_AUTO_LOGIN_USER_ID")]
    (try
      (let [id (java.util.UUID/fromString uid)
            username (or (env "PREVIEW_AUTO_LOGIN_USERNAME") "preview")
            roles (parse-roles (or (env "PREVIEW_AUTO_LOGIN_ROLES") ":role/admin"))]
        {:session/token (or (preview-token) "preview")
         :user/id id
         :user/username username
         :user/roles roles})
      (catch Exception _
        nil))))

(def session-opts
  (let [store-path (or (System/getenv "SESSION_STORE_PATH")
                       "data/sessions.edn")
        cookie-path (or (some-> (System/getenv "SESSION_COOKIE_PATH") str/trim not-empty)
                        "/")]
    {:store (session-store/file-session-store store-path)
     :cookie-attrs {:http-only true
                    :same-site :lax
                    :path cookie-path}}))

(defn error-response
  [status message & [details]]
  (cond-> {:status status
           :body {:error message}}
    details (assoc-in [:body :details] details)))

(defn health-response
  [state]
  {:status 200
   :body {:service "darelwasl"
          :status "ok"
          :datastore (db/status (:db state))}})

(defn require-session
  [handler]
  (fn [request]
    (let [session (:session request)]
      (if (and session (:session/token session) (:user/id session))
        (handler (assoc request :auth/session session))
        (if (preview-request? request)
          (if-let [auto (preview-auto-session)]
            ;; Attach both :session and :auth/session to match normal middleware behavior.
            (handler (assoc request :session auto :auth/session auto))
            (error-response 401 "Unauthorized"))
          (error-response 401 "Unauthorized"))))))

(defn require-roles
  "Middleware to enforce role membership. `roles` should be a set of keywords.
  Attaches :auth/session to request (using require-session before this)."
  [roles]
  (fn [handler]
    (fn [request]
      (let [session (:auth/session request)
            user-roles (set (:user/roles session))]
        (if (seq (clojure.set/intersection roles user-roles))
          (handler request)
          (error-response 403 "Forbidden: insufficient role"))))))

(defn handle-task-result
  [result & [success-status]]
  (if-let [err (:error result)]
    (error-response (or (:status err) 500)
                    (:message err)
                    (:details err))
    {:status (or success-status 200)
     :body result}))

(defn wrap-logging
  "Middleware to log request method/path, status, duration, and user id when present."
  [handler]
  (fn [request]
    (let [start (System/nanoTime)
          resp (handler request)
          dur-ms (/ (double (- (System/nanoTime) start)) 1e6)
          status (:status resp)
          user-id (or (get-in request [:auth/session :user/id])
                      (get-in request [:session :user/id]))]
      (log/infof "http %s %s status=%s user=%s dur=%.1fms"
                 (-> request :request-method name)
                 (:uri request)
                 status
                 (or user-id "-")
                 dur-ms)
      resp)))

(defn task-id-param
  [request]
  (or (get-in request [:path-params :id])
      (get-in request [:path-params "id"])
      (get-in request [:parameters :path :id])
      (get-in request [:parameters :path "id"])
      (some-> request :path-params vals first)))

(defn workspace-id
  [request]
  (workspace/resolve-id (get-in request [:headers "x-workspace-id"])))
