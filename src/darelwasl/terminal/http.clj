(ns darelwasl.terminal.http
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.terminal.session :as session]
            [darelwasl.terminal.store :as store]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]))

(defn- parse-long
  [value default]
  (try
    (Long/parseLong (str value))
    (catch Exception _
      default)))

(defn- ok
  [body]
  {:status 200
   :body body})

(defn- error-response
  [status message & [details]]
  (cond-> {:status status
           :body {:error message}}
    details (assoc-in [:body :details] details)))

(defn- find-session
  [store session-id]
  (when session-id
    (store/get-session store session-id)))

(defn- admin-authorized?
  [state request]
  (let [token (get-in state [:terminal/config :admin-token])
        provided (get-in request [:headers "x-terminal-admin-token"])]
    (and (not (str/blank? (str token)))
         (= token provided))))

(defn health-handler
  [_state]
  (fn [_request]
    (ok {:service "terminal"
         :status "ok"})))

(defn list-sessions-handler
  [state]
  (fn [_request]
    (let [sessions (mapv session/present-session (store/list-sessions (:terminal/store state)))]
      (ok {:sessions sessions}))))

(defn create-session-handler
  [state]
  (fn [request]
    (let [name (or (get-in request [:body-params :name])
                   (get-in request [:body-params "name"]))
          session-type (or (get-in request [:body-params :type])
                           (get-in request [:body-params "type"]))]
      (try
        (let [session (session/create-session! (:terminal/store state)
                                               (:terminal/config state)
                                               {:name name
                                                :type session-type})]
          (ok {:session (session/present-session session)}))
        (catch Exception e
          (log/warn e "Failed to create terminal session")
          (error-response 500 "Failed to create terminal session"
                          (cond-> {:message (.getMessage e)}
                            (instance? clojure.lang.ExceptionInfo e)
                            (assoc :data (ex-data e)))))))))

(defn session-detail-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          session (find-session (:terminal/store state) session-id)]
      (if session
        (ok {:session (session/present-session session)})
        (error-response 404 "Session not found")))))

(defn send-input-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          text (or (get-in request [:body-params :text])
                   (get-in request [:body-params "text"]))
          session (find-session (:terminal/store state) session-id)]
      (cond
        (nil? session) (error-response 404 "Session not found")
        (or (nil? text) (str/blank? (str text)))
        (error-response 400 "Missing input text")
        :else
        (try
          (session/send-input! session (str text))
          (ok {:status "ok"})
          (catch Exception e
            (log/warn e "Failed to send input" {:id session-id})
            (error-response 500 "Failed to send input")))))))

(defn- normalize-keys
  [keys]
  (cond
    (nil? keys) []
    (string? keys) [(str keys)]
    (sequential? keys) (mapv str keys)
    :else []))

(defn send-keys-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          keys (or (get-in request [:body-params :keys])
                   (get-in request [:body-params "keys"]))
          keys (normalize-keys keys)
          session (find-session (:terminal/store state) session-id)]
      (cond
        (nil? session) (error-response 404 "Session not found")
        (empty? keys) (error-response 400 "Missing keys")
        :else
        (try
          (session/send-keys! session keys)
          (ok {:status "ok"})
          (catch Exception e
            (log/warn e "Failed to send keys" {:id session-id})
            (error-response 500 "Failed to send keys")))))))

(defn output-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          cursor (parse-long (or (get-in request [:query-params :cursor])
                                 (get-in request [:query-params "cursor"]))
                             0)
          session (find-session (:terminal/store state) session-id)
          max-bytes (get-in state [:terminal/config :max-output-bytes] 20000)]
      (if session
        (let [output (session/output-since session cursor max-bytes)
              app-ready? (session/app-ready? session)
              output (assoc output :app-ready app-ready?)
              text (str/lower-case (or (:chunk output) ""))
              auto-continue-enabled? (get session :auto-continue-enabled? true)
              auto-approval? (get session :auto-approval? false)
              auto-continue? (get session :auto-continue? false)
              needs-approval? (and auto-continue-enabled?
                                   (not auto-approval?)
                                   (str/includes? text "allow codex to work in this folder"))
              needs-continue? (and auto-continue-enabled?
                                   (not auto-continue?)
                                   (str/includes? text "press enter to continue"))]
          (when needs-approval?
            (session/send-keys! session ["1" "Enter"]))
          (when needs-continue?
            (session/send-keys! session ["Enter"]))
          (let [next-session (cond-> session
                               needs-approval? (assoc :auto-approval? true)
                               needs-continue? (assoc :auto-continue? true))]
            (when (not= session next-session)
              (store/upsert-session! (:terminal/store state) next-session))
            (ok output)))
        (error-response 404 "Session not found")))))

(defn complete-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          session (find-session (:terminal/store state) session-id)]
      (if session
        (if (admin-authorized? state request)
          (do
            (session/complete-session! (:terminal/store state) session)
            (ok {:status "ok"}))
          (error-response 403 "Admin token required"))
        (error-response 404 "Session not found")))))

(defn verify-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          session (find-session (:terminal/store state) session-id)]
      (if session
        (try
          (ok (session/verify-session! (:terminal/store state) session))
          (catch Exception e
            (log/warn e "Failed to verify session" {:id session-id})
            (error-response 500 "Failed to verify session"
                            (cond-> {:message (.getMessage e)}
                              (instance? clojure.lang.ExceptionInfo e)
                              (assoc :data (ex-data e))))))
        (error-response 404 "Session not found")))))

(defn resume-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          session (find-session (:terminal/store state) session-id)]
      (if session
        (try
          (let [next-session (session/resume-session! (:terminal/store state)
                                                     (:terminal/config state)
                                                     session)]
            (ok {:session (session/present-session next-session)}))
          (catch Exception e
            (log/warn e "Failed to resume session" {:id session-id})
            (error-response 500 "Failed to resume session"
                            (cond-> {:message (.getMessage e)}
                              (instance? clojure.lang.ExceptionInfo e)
                              (assoc :data (ex-data e))))))
        (error-response 404 "Session not found")))))

(defn restart-app-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          session (find-session (:terminal/store state) session-id)]
      (if session
        (try
          (let [next-session (session/restart-app! (:terminal/store state) session)]
            (ok {:session (session/present-session next-session)}))
          (catch Exception e
            (log/warn e "Failed to restart app" {:id session-id})
            (error-response 500 "Failed to restart app"
                            (cond-> {:message (.getMessage e)}
                              (instance? clojure.lang.ExceptionInfo e)
                              (assoc :data (ex-data e))))))
        (error-response 404 "Session not found")))))

(defn interrupt-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          session (find-session (:terminal/store state) session-id)]
      (if session
        (try
          (let [next-session (session/interrupt-session! (:terminal/store state) session)]
            (ok {:session (session/present-session next-session)}))
          (catch Exception e
            (log/warn e "Failed to interrupt session" {:id session-id})
            (error-response 500 "Failed to interrupt session"
                            (cond-> {:message (.getMessage e)}
                              (instance? clojure.lang.ExceptionInfo e)
                              (assoc :data (ex-data e))))))
        (error-response 404 "Session not found")))))

(defn routes
  [state]
  [["/health" {:get (health-handler state)}]
   ["/sessions"
    {:get (list-sessions-handler state)
     :post (create-session-handler state)}]
   ["/sessions/:id"
    {:get (session-detail-handler state)}]
   ["/sessions/:id/input"
    {:post (send-input-handler state)}]
   ["/sessions/:id/keys"
    {:post (send-keys-handler state)}]
   ["/sessions/:id/output"
    {:get (output-handler state)}]
   ["/sessions/:id/complete"
    {:post (complete-handler state)}]
   ["/sessions/:id/verify"
    {:post (verify-handler state)}]
   ["/sessions/:id/resume"
    {:post (resume-handler state)}]
   ["/sessions/:id/restart-app"
    {:post (restart-app-handler state)}]
   ["/sessions/:id/interrupt"
    {:post (interrupt-handler state)}]])

(defn app
  [state]
  (let [muuntaja-instance (m/create m/default-options)]
    (ring/ring-handler
     (ring/router
      (routes state)
      {:conflicts nil
       :data {:muuntaja muuntaja-instance
              :middleware [parameters/parameters-middleware
                           muuntaja/format-negotiate-middleware
                           muuntaja/format-response-middleware
                           muuntaja/format-request-middleware
                           exception/exception-middleware]}})
     (ring/create-default-handler))))
