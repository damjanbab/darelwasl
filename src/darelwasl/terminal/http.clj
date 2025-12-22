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
                   (get-in request [:body-params "name"]))]
      (try
        (let [session (session/create-session! (:terminal/store state)
                                               (:terminal/config state)
                                               {:name name})]
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
              text (str/lower-case (or (:chunk output) ""))
              auto-approval? (get session :auto-approval? false)
              auto-continue? (get session :auto-continue? false)
              next-session (cond-> session
                             (and (not auto-approval?)
                                  (str/includes? text "allow codex to work in this folder"))
                             (do
                               (session/send-keys! session ["1" "Enter"])
                               (assoc :auto-approval? true))
                             (and (not auto-continue?)
                                  (str/includes? text "press enter to continue"))
                             (do
                               (session/send-keys! session ["Enter"])
                               (assoc :auto-continue? true)))]
          (when (not= session next-session)
            (store/upsert-session! (:terminal/store state) next-session))
          (ok output))
        (error-response 404 "Session not found")))))

(defn complete-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          session (find-session (:terminal/store state) session-id)]
      (if session
        (do
          (session/complete-session! (:terminal/store state) session)
          (ok {:status "ok"}))
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
    {:post (complete-handler state)}]])

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
