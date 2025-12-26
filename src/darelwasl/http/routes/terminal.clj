(ns darelwasl.http.routes.terminal
   (:require [clojure.string :as str]
             [darelwasl.actions :as actions]
             [darelwasl.http.common :as common]
             [darelwasl.terminal.backend :as backend]
             [darelwasl.terminal.client :as terminal]))

(def ^:private create-session-timeout-ms 300000)

(defn- terminal-config
  [state]
  (backend/config-with-active (:config state)))

(defn- public-base-info
  [cfg]
  (when-let [raw (get-in cfg [:terminal :public-base-url])]
    (try
      (let [uri (java.net.URI. raw)]
        {:host (.getHost uri)
         :scheme (or (.getScheme uri) "http")})
      (catch Exception _ nil))))

(defn- request-host
  [request cfg]
  (let [header (or (get-in request [:headers "x-forwarded-host"])
                   (get-in request [:headers "host"]))
        host (some-> header
                     (str/split #",")
                     first
                     str/trim
                     (str/replace #":\\d+$" ""))
        public-host (:host (public-base-info cfg))]
    (cond
      (and host (not= host "0.0.0.0")) host
      (and public-host (not= public-host "0.0.0.0")) public-host
      :else "localhost")))

(defn- request-scheme
  [request cfg]
  (let [header (or (get-in request [:headers "x-forwarded-proto"])
                   (get-in request [:headers "x-forwarded-protocol"]))
        scheme (some-> header
                       (str/split #",")
                       first
                       str/trim)
        public-scheme (:scheme (public-base-info cfg))]
    (let [raw (or scheme public-scheme (name (:scheme request)) "http")]
      (if (= "https" raw) "http" raw))))

 (defn- qparam
   [query key]
   (or (get query key)
       (get query (name key))))

 (defn- handle-terminal-result
   [result]
   (if-let [err (:error result)]
     (common/error-response (or (:status result) 502) err (:body result))
     {:status 200
      :body (:body result)}))


(defn list-sessions-handler
  [state]
  (fn [_request]
    (handle-terminal-result
      (terminal/request (terminal-config state) :get "/sessions"))))

(defn backend-handler
  [state]
  (fn [_request]
    {:status 200
     :body (backend/backend-info (:config state))}))

(defn backend-update-handler
  [state]
  (fn [request]
    (let [body (or (:body-params request) {})
          active (or (:active body)
                     (get body "active")
                     (:backend body)
                     (get body "backend"))
          _ (backend/set-active-backend! (:config state) active)]
      {:status 200
       :body (backend/backend-info (:config state))})))

(defn app-link-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          session-res (terminal/request (terminal-config state) :get (str "/sessions/" session-id))
          app-port (get-in session-res [:body :session :ports :app])]
      (cond
        (:error session-res)
        (common/error-response (or (:status session-res) 502)
                               (or (:error session-res) "Terminal service unavailable"))

        (nil? app-port)
        (common/error-response 404 "Session app port unavailable")

        :else
        (let [host (request-host request (:config state))
              scheme (request-scheme request (:config state))
              url (str scheme "://" host ":" app-port "/")]
          {:status 302
           :headers {"Location" url}})))))

 (defn create-session-handler
   [state]
   (fn [request]
     (let [name (or (get-in request [:body-params :name])
                    (get-in request [:body-params "name"]))
           session-type (or (get-in request [:body-params :type])
                            (get-in request [:body-params "type"]))
           dev-bot? (or (get-in request [:body-params :dev-bot?])
                        (get-in request [:body-params "dev-bot?"])
                        (get-in request [:body-params :dev-bot])
                        (get-in request [:body-params "dev-bot"]))]
       (handle-terminal-result
        (terminal/request-with-timeout (terminal-config state) :post "/sessions"
                                        (cond-> {}
                                          name (assoc :name name)
                                          session-type (assoc :type session-type)
                                          (some? dev-bot?) (assoc :dev-bot? dev-bot?))
                                        create-session-timeout-ms)))))

 (defn session-detail-handler
   [state]
   (fn [request]
     (let [session-id (get-in request [:path-params :id])]
      (handle-terminal-result
       (terminal/request (terminal-config state) :get (str "/sessions/" session-id))))))

 (defn send-input-handler
   [state]
   (fn [request]
     (let [session-id (get-in request [:path-params :id])
           text (or (get-in request [:body-params :text])
                    (get-in request [:body-params "text"]))]
      (handle-terminal-result
       (terminal/request (terminal-config state) :post (str "/sessions/" session-id "/input") {:text text})))))

(defn send-keys-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          keys (or (get-in request [:body-params :keys])
                   (get-in request [:body-params "keys"]))]
    (handle-terminal-result
     (terminal/request (terminal-config state) :post (str "/sessions/" session-id "/keys") {:keys keys})))))

 (defn output-handler
   [state]
   (fn [request]
     (let [session-id (get-in request [:path-params :id])
           cursor (qparam (:query-params request) :cursor)
           qs (when cursor (str "?cursor=" (str cursor)))]
      (handle-terminal-result
       (terminal/request (terminal-config state) :get (str "/sessions/" session-id "/output" qs))))))

 (defn complete-handler
   [state]
   (fn [request]
     (let [session-id (get-in request [:path-params :id])]
      (handle-terminal-result
       (terminal/request (terminal-config state) :post (str "/sessions/" session-id "/complete"))))))

(defn verify-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])]
     (handle-terminal-result
      (terminal/request (terminal-config state) :post (str "/sessions/" session-id "/verify"))))))

(defn resume-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])]
     (handle-terminal-result
      (terminal/request (terminal-config state) :post (str "/sessions/" session-id "/resume"))))))

(defn restart-app-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])]
     (handle-terminal-result
      (terminal/request (terminal-config state) :post (str "/sessions/" session-id "/restart-app"))))))

(defn interrupt-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])]
     (handle-terminal-result
      (terminal/request (terminal-config state) :post (str "/sessions/" session-id "/interrupt"))))))

(defn command-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          raw-body (or (:body-params request) {})
          command (or (:command raw-body) (get raw-body "command") raw-body)
          command-id (or (:id command) (get command "id") (:command/id command))
          command-type (or (:type command) (get command "type") (:command/type command))
          input (or (:input command) (get command "input") (:command/input command) {})
          actor (actions/actor-from-session (:auth/session request))]
      (cond
        (str/blank? (str command-id)) (common/error-response 400 "Command id is required")
        (str/blank? (str command-type)) (common/error-response 400 "Command type is required")
        :else
        (let [command {:id (str command-id)
                       :type command-type
                       :input input}
              res (terminal/request (terminal-config state) :post
                                    (str "/sessions/" session-id "/commands/run")
                                    {:command command
                                     :actor actor})]
          (if (:error res)
            (common/error-response (or (:status res) 502)
                                   (or (:error res) "Unable to run command"))
            {:status 200
             :body (:body res)}))))))

(defn public-routes
  [state]
  [["/terminal/sessions/:id/app"
    {:middleware [common/require-session
                  (common/require-roles #{:role/codex-terminal})]
     :get (app-link-handler state)}]])

(defn routes
  [state]
  [["/terminal"
    {:middleware [common/require-session
                  (common/require-roles #{:role/codex-terminal})]}
    ["/sessions" {:get (list-sessions-handler state)
                  :post (create-session-handler state)}]
    ["/sessions/:id" {:get (session-detail-handler state)}]
    ["/sessions/:id/input" {:post (send-input-handler state)}]
    ["/sessions/:id/keys" {:post (send-keys-handler state)}]
    ["/sessions/:id/output" {:get (output-handler state)}]
    ["/sessions/:id/complete" {:post (complete-handler state)}]
    ["/sessions/:id/verify" {:post (verify-handler state)}]
    ["/sessions/:id/resume" {:post (resume-handler state)}]
    ["/sessions/:id/restart-app" {:post (restart-app-handler state)}]
    ["/sessions/:id/interrupt" {:post (interrupt-handler state)}]
    ["/sessions/:id/commands" {:post (command-handler state)}]
    ["/backend"
     {:middleware [(common/require-roles #{:role/admin})]
      :get (backend-handler state)
      :post (backend-update-handler state)}]]])
