(ns darelwasl.http.routes.clients
  (:require [darelwasl.actions :as actions]
            [darelwasl.clients :as clients]
            [darelwasl.http.common :as common]))

(def ^:private client-id-path
  "/:id{[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}")

(defn- client-id-param
  [request]
  (or (get-in request [:path-params :id])
      (get-in request [:path-params "id"])
      (get-in request [:parameters :path :id])
      (get-in request [:parameters :path "id"])
      (some-> request :path-params vals first)))

(defn list-clients-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (clients/list-clients (get-in state [:db :conn])
                           (:query-params request)
                           (common/workspace-id request)))))

(defn client-detail-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (clients/fetch-client (get-in state [:db :conn])
                           (client-id-param request)
                           (common/workspace-id request)))))

(defn create-client-handler
  [state]
  (fn [request]
    (let [workspace (common/workspace-id request)
          action-res (actions/execute! state {:action/id :cap/action/client-create
                                              :actor (actions/actor-from-session (:auth/session request) workspace)
                                              :input (or (:body-params request) {})})
          res (if (:error action-res) {:error (:error action-res)} (:result action-res))]
      (common/handle-task-result res 201))))

(defn update-client-handler
  [state]
  (fn [request]
    (let [workspace (common/workspace-id request)
          client-id (client-id-param request)
          action-res (actions/execute! state {:action/id :cap/action/client-update
                                              :actor (actions/actor-from-session (:auth/session request) workspace)
                                              :input (assoc (or (:body-params request) {})
                                                            :client/id client-id)})
          res (if (:error action-res) {:error (:error action-res)} (:result action-res))]
      (common/handle-task-result res))))

(defn routes
  [state]
  [["/clients"
    {:middleware [common/require-session]}
    ["" {:get (list-clients-handler state)
         :post (create-client-handler state)}]
    [client-id-path {:get (client-detail-handler state)
                     :put (update-client-handler state)}]]])
