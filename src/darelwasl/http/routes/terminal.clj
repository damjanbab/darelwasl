(ns darelwasl.http.routes.terminal
   (:require [clojure.string :as str]
             [darelwasl.actions :as actions]
             [darelwasl.http.common :as common]
             [darelwasl.terminal.client :as terminal]
             [darelwasl.terminal.commands :as commands]))

(def ^:private create-session-timeout-ms 300000)

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
      (terminal/request (:config state) :get "/sessions"))))

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
        (terminal/request-with-timeout (:config state) :post "/sessions"
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
        (terminal/request (:config state) :get (str "/sessions/" session-id))))))

 (defn send-input-handler
   [state]
   (fn [request]
     (let [session-id (get-in request [:path-params :id])
           text (or (get-in request [:body-params :text])
                    (get-in request [:body-params "text"]))]
       (handle-terminal-result
        (terminal/request (:config state) :post (str "/sessions/" session-id "/input") {:text text})))))

(defn send-keys-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          keys (or (get-in request [:body-params :keys])
                   (get-in request [:body-params "keys"]))]
      (handle-terminal-result
       (terminal/request (:config state) :post (str "/sessions/" session-id "/keys") {:keys keys})))))

 (defn output-handler
   [state]
   (fn [request]
     (let [session-id (get-in request [:path-params :id])
           cursor (qparam (:query-params request) :cursor)
           qs (when cursor (str "?cursor=" (str cursor)))]
       (handle-terminal-result
        (terminal/request (:config state) :get (str "/sessions/" session-id "/output" qs))))))

 (defn complete-handler
   [state]
   (fn [request]
     (let [session-id (get-in request [:path-params :id])]
       (handle-terminal-result
        (terminal/request (:config state) :post (str "/sessions/" session-id "/complete"))))))

(defn verify-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])]
      (handle-terminal-result
       (terminal/request (:config state) :post (str "/sessions/" session-id "/verify"))))))

(defn resume-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])]
      (handle-terminal-result
       (terminal/request (:config state) :post (str "/sessions/" session-id "/resume"))))))

(defn restart-app-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])]
      (handle-terminal-result
       (terminal/request (:config state) :post (str "/sessions/" session-id "/restart-app"))))))

(defn interrupt-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])]
      (handle-terminal-result
       (terminal/request (:config state) :post (str "/sessions/" session-id "/interrupt"))))))

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
        (let [claim (terminal/request (:config state) :post
                                      (str "/sessions/" session-id "/commands/claim")
                                      {:id command-id})]
          (cond
            (:error claim)
            (common/error-response (or (:status claim) 502)
                                   (or (:error claim) "Unable to claim command"))

            (= "duplicate" (get-in claim [:body :status]))
            {:status 200 :body {:status "duplicate"}}

            :else
            (let [command (assoc command :type command-type :input input)
                  result (commands/execute-command! state session-id command actor)
                  message (commands/command->message command result)]
              (terminal/request (:config state) :post
                                (str "/sessions/" session-id "/input")
                                {:text message})
              {:status 200
               :body {:status "ok"
                     :result (or (:result result) (:message result))
                     :error (:error result)}})))))))

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
    ["/sessions/:id/commands" {:post (command-handler state)}]]])
