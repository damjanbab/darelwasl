(ns darelwasl.http.routes.terminal
   (:require [clojure.string :as str]
             [darelwasl.http.common :as common]
             [darelwasl.terminal.client :as terminal]))

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
                    (get-in request [:body-params "name"]))]
       (handle-terminal-result
        (terminal/request (:config state) :post "/sessions" (when name {:name name}))))))

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

 (defn routes
   [state]
   [["/terminal"
     {:middleware [common/require-session
                   (common/require-roles #{:role/codex-terminal})]}
     ["/sessions" {:get (list-sessions-handler state)
                   :post (create-session-handler state)}]
     ["/sessions/:id" {:get (session-detail-handler state)}]
     ["/sessions/:id/input" {:post (send-input-handler state)}]
     ["/sessions/:id/output" {:get (output-handler state)}]
     ["/sessions/:id/complete" {:post (complete-handler state)}]]])
