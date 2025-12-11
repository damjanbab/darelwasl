(ns darelwasl.http.common
  (:require [clojure.set :as set]
            [clojure.tools.logging :as log]
            [darelwasl.db :as db]
            [ring.middleware.session.memory :refer [memory-store]]))

(def session-opts
  {:store (memory-store)
   :cookie-attrs {:http-only true
                  :same-site :lax
                  :path "/"}})

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
        (error-response 401 "Unauthorized")))))

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
