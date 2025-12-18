(ns darelwasl.http.routes.actions
  (:require [darelwasl.actions :as actions]
            [darelwasl.http.common :as common]))

(def ^:private created-actions
  #{:cap/action/task-create
    :cap/action/tag-create})

(defn- handle-action-result
  [action-result]
  (if-let [err (:error action-result)]
    (common/error-response (or (:status err) 500)
                           (:message err)
                           (:details err))
    {:status 200
     :body action-result}))

(defn invoke-handler
  [state]
  (fn [request]
    (let [raw-id (or (get-in request [:path-params :id])
                     (get-in request [:path-params "id"])
                     (get-in request [:parameters :path :id])
                     (get-in request [:parameters :path "id"]))
          action-id (actions/parse-action-id (some-> raw-id str))
          body (or (:body-params request) {})
          input (or (:input body) (get body "input") body)
          res (actions/execute! state {:action/id action-id
                                       :actor (actions/actor-from-session (:auth/session request))
                                       :input input})]
      (if-let [err (:error res)]
        (handle-action-result res)
        (cond-> (handle-action-result res)
          (created-actions (:action/id res)) (assoc :status 201))))))

(defn routes
  [state]
  [["/actions"
    {:middleware [common/require-session]}
    ["/:id" {:post (invoke-handler state)}]]])
