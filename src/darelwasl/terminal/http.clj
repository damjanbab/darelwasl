(ns darelwasl.terminal.http
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.terminal.session :as session]
            [darelwasl.terminal.store :as store]
            [darelwasl.validation :as v]
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

(defn- parse-uuid
  [value]
  (try
    (when value
      (java.util.UUID/fromString (str value)))
    (catch Exception _
      nil)))

(def ^:private surface-aliases
  {"http" :surface/http
   "telegram" :surface/telegram
   "automation" :surface/automation
   "rule" :surface/rule
   "import" :surface/import})

(def ^:private actor-type-aliases
  {"user" :actor.type/user
   "automation" :actor.type/automation
   "integration" :integration
   "system" :actor.type/system})

(def ^:private adapter-aliases
  {"web-ui" :adapter/web-ui
   "telegram" :adapter/telegram
   "rule" :adapter/rule
   "import" :adapter/import})

(defn- normalize-actor-type
  [value]
  (cond
    (keyword? value) (if (namespace value)
                       value
                       (get actor-type-aliases (name value) value))
    (string? value) (get actor-type-aliases (str/lower-case (str/trim value)))
    :else nil))

(defn- normalize-actor-surface
  [value]
  (cond
    (keyword? value) (if (namespace value)
                       value
                       (get surface-aliases (name value) value))
    (string? value) (get surface-aliases (str/lower-case (str/trim value)))
    :else nil))

(defn- normalize-actor-adapter
  [value]
  (cond
    (keyword? value) (if (namespace value)
                       value
                       (get adapter-aliases (name value) value))
    (string? value) (get adapter-aliases (str/lower-case (str/trim value)))
    :else nil))

(defn- actor-value
  [actor keys]
  (some #(get actor %) keys))

(defn- normalize-actor
  [actor]
  (if (map? actor)
    (let [raw-id (actor-value actor [:user/id "user/id" :id "id"])
          raw-username (actor-value actor [:user/username "user/username" :username "username"])
          raw-name (actor-value actor [:user/name "user/name" :name "name"])
          raw-roles (actor-value actor [:user/roles "user/roles" :roles "roles"])
          raw-type (actor-value actor [:actor/type "actor/type" :type "type"])
          raw-surface (actor-value actor [:actor/surface "actor/surface" :surface "surface"])
          raw-adapter (actor-value actor [:actor/adapter "actor/adapter" :adapter "adapter"])
          user-id (cond
                    (instance? java.util.UUID raw-id) raw-id
                    (string? raw-id) (parse-uuid raw-id)
                    :else nil)
          actor-type (normalize-actor-type raw-type)
          actor-surface (normalize-actor-surface raw-surface)
          actor-adapter (normalize-actor-adapter raw-adapter)]
      (cond-> actor
        (some? user-id) (assoc :user/id user-id)
        (and raw-id (nil? user-id)) (dissoc :user/id)
        raw-username (assoc :user/username raw-username)
        raw-name (assoc :user/name raw-name)
        raw-roles (assoc :user/roles raw-roles)
        actor-type (assoc :actor/type actor-type)
        actor-surface (assoc :actor/surface actor-surface)
        actor-adapter (assoc :actor/adapter actor-adapter)))
    actor))

(defn- ok
  [body]
  {:status 200
   :body body})

(defn- error-response
  [status message & [details]]
  (cond-> {:status status
           :body {:error message}}
    details (assoc-in [:body :details] details)))

(defn- parse-bool
  [value]
  (let [{:keys [value error]} (v/normalize-boolean value "boolean" {:default false})]
    (if error false value)))

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
                           (get-in request [:body-params "type"]))
          dev-bot-raw (v/param-value (:body-params request) :dev-bot?)
          dev-bot? (parse-bool dev-bot-raw)]
      (try
        (let [session (session/create-session! (:terminal/store state)
                                               (:terminal/config state)
                                               {:name name
                                                :type session-type
                                                :dev-bot? dev-bot?})]
          (ok {:session (session/present-session session)}))
        (catch Exception e
          (let [data (when (instance? clojure.lang.ExceptionInfo e) (ex-data e))
                status (or (:status data) 500)
                message (or (:message data) "Failed to create terminal session")]
            (log/warn e "Failed to create terminal session")
            (error-response status message
                            (cond-> {:message (.getMessage e)}
                              data (assoc :data data)))))))))

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

(defn claim-command-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          command-id (or (get-in request [:body-params :id])
                         (get-in request [:body-params "id"]))
          session (find-session (:terminal/store state) session-id)]
      (cond
        (nil? session) (error-response 404 "Session not found")
        :else
        (let [{:keys [status message]} (session/claim-command! (:terminal/store state) session command-id)]
          (case status
            :claimed (ok {:status "claimed"})
            :duplicate (ok {:status "duplicate"})
            (error-response 400 (or message "Unable to claim command"))))))))

(defn run-command-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          raw-body (or (:body-params request) {})
          command (or (:command raw-body) (get raw-body "command") raw-body)
          command-id (or (:id command) (get command "id") (:command/id command))
          command-type (or (:type command) (get command "type") (:command/type command))
          input (or (:input command) (get command "input") (:command/input command) {})
          actor (normalize-actor (or (:actor raw-body) (get raw-body "actor")))
          session (find-session (:terminal/store state) session-id)]
      (cond
        (nil? session) (error-response 404 "Session not found")
        (str/blank? (str command-id)) (error-response 400 "Command id is required")
        (str/blank? (str command-type)) (error-response 400 "Command type is required")
        :else
        (let [command {:id (str command-id)
                       :type command-type
                       :input input}
              result (session/run-command! state (:terminal/store state) session command actor)
              payload (:result result)]
          (case (:status result)
            :duplicate (ok {:status "duplicate"})
            :error (error-response 400 (or (:message result) "Unable to run command"))
            (ok {:status "ok"
                 :result (or (:result payload) (:message payload))
                 :error (:error payload)})))))))

(defn reallocate-ports-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          session (find-session (:terminal/store state) session-id)]
      (if session
        (try
          (let [next-session (session/reallocate-ports! (:terminal/store state)
                                                       (:terminal/config state)
                                                       session)]
            (ok {:session (session/present-session next-session)}))
          (catch Exception e
            (log/warn e "Failed to reallocate ports" {:id session-id})
            (error-response 500 "Failed to reallocate ports"
                            (cond-> {:message (.getMessage e)}
                              (instance? clojure.lang.ExceptionInfo e)
                              (assoc :data (ex-data e))))))
        (error-response 404 "Session not found")))))

(defn output-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          cursor (parse-long (or (get-in request [:query-params :cursor])
                                 (get-in request [:query-params "cursor"]))
                             0)
          store (:terminal/store state)
          session (find-session store session-id)
          max-bytes (get-in state [:terminal/config :max-output-bytes] 20000)]
      (if session
        (let [session (session/ensure-app-running! store (:terminal/config state) session)
              output (session/output-since session cursor max-bytes)
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
          (session/auto-run-commands! state store session (:chunk output))
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

(defn cleanup-handler
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

(defn restart-service-handler
  [state]
  (fn [request]
    (if (admin-authorized? state request)
      (if-let [restart! (:terminal/restart! state)]
        (try
          (restart!)
          {:status 202
           :body {:status "restarting"}}
          (catch Exception e
            (log/warn e "Failed to restart terminal service")
            (error-response 500 "Unable to restart terminal service")))
        (error-response 500 "Restart unavailable"))
      (error-response 403 "Admin token required"))))

(defn routes
  [state]
  [["/health" {:get (health-handler state)}]
   ["/system/restart" {:post (restart-service-handler state)}]
   ["/sessions"
    {:get (list-sessions-handler state)
     :post (create-session-handler state)}]
   ["/sessions/:id"
    {:get (session-detail-handler state)}]
   ["/sessions/:id/input"
    {:post (send-input-handler state)}]
   ["/sessions/:id/keys"
    {:post (send-keys-handler state)}]
   ["/sessions/:id/commands/claim"
    {:post (claim-command-handler state)}]
   ["/sessions/:id/commands/run"
    {:post (run-command-handler state)}]
   ["/sessions/:id/output"
    {:get (output-handler state)}]
   ["/sessions/:id/complete"
    {:post (complete-handler state)}]
   ["/sessions/:id/cleanup"
    {:post (cleanup-handler state)}]
   ["/sessions/:id/verify"
    {:post (verify-handler state)}]
   ["/sessions/:id/resume"
    {:post (resume-handler state)}]
   ["/sessions/:id/restart-app"
    {:post (restart-app-handler state)}]
   ["/sessions/:id/ports/reallocate"
    {:post (reallocate-ports-handler state)}]
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
