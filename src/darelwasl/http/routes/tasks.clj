(ns darelwasl.http.routes.tasks
  (:require [clojure.tools.logging :as log]
            [darelwasl.actions :as actions]
            [darelwasl.http.common :as common]
            [darelwasl.telegram :as telegram]
            [darelwasl.tasks :as tasks]))

(def ^:private task-id-path
  "/:id{[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}")

(defn list-tasks-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (tasks/list-tasks (get-in state [:db :conn])
                       (:query-params request)
                       (common/workspace-id request)))))

(defn recent-tasks-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (tasks/recent-tasks (get-in state [:db :conn])
                         {:limit (some-> (get-in request [:query-params :limit]) Integer/parseInt)
                          :include-archived? (= "true" (get-in request [:query-params :archived]))}
                         (common/workspace-id request)))))

(defn task-counts-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (tasks/task-status-counts (get-in state [:db :conn])
                               {:include-archived? (= "true" (get-in request [:query-params :archived]))}
                               (common/workspace-id request)))))

(defn create-task-handler
  [state]
  (fn [request]
    (let [workspace (common/workspace-id request)
          action-res (actions/execute! state {:action/id :cap/action/task-create
                                              :actor (actions/actor-from-session (:auth/session request) workspace)
                                              :input (or (:body-params request) {})})
          res (if (:error action-res) {:error (:error action-res)} (:result action-res))]
      (when-not (:error res)
        (telegram/notify-task-event! state {:event :task/created
                                            :task (:task res)
                                            :actor (:auth/session request)}))
      (common/handle-task-result res 201))))

(defn update-task-handler
  [state]
  (fn [request]
    (let [workspace (common/workspace-id request)
          task-id (common/task-id-param request)
          action-res (actions/execute! state {:action/id :cap/action/task-update
                                              :actor (actions/actor-from-session (:auth/session request) workspace)
                                              :input (assoc (or (:body-params request) {})
                                                            :task/id task-id)})
          res (if (:error action-res) {:error (:error action-res)} (:result action-res))]
      (common/handle-task-result res))))

(defn set-status-handler
  [state]
  (fn [request]
    (let [workspace (common/workspace-id request)
          task-id (common/task-id-param request)
          action-res (actions/execute! state {:action/id :cap/action/task-set-status
                                              :actor (actions/actor-from-session (:auth/session request) workspace)
                                              :input (assoc (or (:body-params request) {})
                                                            :task/id task-id)})
          res (if (:error action-res) {:error (:error action-res)} (:result action-res))]
      (when-not (:error res)
        (telegram/notify-task-event! state {:event :task/status-changed
                                            :task (:task res)
                                            :actor (:auth/session request)}))
      (common/handle-task-result res))))

(defn assign-task-handler
  [state]
  (fn [request]
    (let [workspace (common/workspace-id request)
          task-id (common/task-id-param request)
          action-res (actions/execute! state {:action/id :cap/action/task-assign
                                              :actor (actions/actor-from-session (:auth/session request) workspace)
                                              :input (assoc (or (:body-params request) {})
                                                            :task/id task-id)})
          res (if (:error action-res) {:error (:error action-res)} (:result action-res))]
      (when-not (:error res)
        (telegram/notify-task-event! state {:event :task/assigned
                                            :task (:task res)
                                            :actor (:auth/session request)}))
      (common/handle-task-result res))))

(defn due-date-handler
  [state]
  (fn [request]
    (let [workspace (common/workspace-id request)
          task-id (common/task-id-param request)
          action-res (actions/execute! state {:action/id :cap/action/task-set-due
                                              :actor (actions/actor-from-session (:auth/session request) workspace)
                                              :input (assoc (or (:body-params request) {})
                                                            :task/id task-id)})
          res (if (:error action-res) {:error (:error action-res)} (:result action-res))]
      (when-not (:error res)
        (telegram/notify-task-event! state {:event :task/due-changed
                                            :task (:task res)
                                            :actor (:auth/session request)}))
      (common/handle-task-result res))))

(defn tags-handler
  [state]
  (fn [request]
    (let [workspace (common/workspace-id request)
          task-id (common/task-id-param request)
          action-res (actions/execute! state {:action/id :cap/action/task-set-tags
                                              :actor (actions/actor-from-session (:auth/session request) workspace)
                                              :input (assoc (or (:body-params request) {})
                                                            :task/id task-id)})
          res (if (:error action-res) {:error (:error action-res)} (:result action-res))]
      (common/handle-task-result res))))

(defn archive-handler
  [state]
  (fn [request]
    (let [workspace (common/workspace-id request)
          task-id (common/task-id-param request)
          action-res (actions/execute! state {:action/id :cap/action/task-archive
                                              :actor (actions/actor-from-session (:auth/session request) workspace)
                                              :input (assoc (or (:body-params request) {})
                                                            :task/id task-id)})
          res (if (:error action-res) {:error (:error action-res)} (:result action-res))]
      (common/handle-task-result res))))

(defn delete-task-handler
  [state]
  (fn [request]
    (let [workspace (common/workspace-id request)
          path-id (common/task-id-param request)]
      (when-not path-id
        (log/warnf "delete-task-handler: missing path id in request %s path-params=%s parameters=%s"
                   (:uri request) (:path-params request) (:parameters request)))
      (let [action-res (actions/execute! state {:action/id :cap/action/task-delete
                                                :actor (actions/actor-from-session (:auth/session request) workspace)
                                                :input {:task/id path-id}})
            res (if (:error action-res) {:error (:error action-res)} (:result action-res))]
        (common/handle-task-result res)))))

(defn list-tags-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (tasks/list-tags (get-in state [:db :conn])
                      (common/workspace-id request))))))

(defn create-tag-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (tasks/create-tag! (get-in state [:db :conn])
                        (or (:body-params request) {})
                        (actions/actor-from-session (:auth/session request)
                                                    (common/workspace-id request)))
     201)))

(defn update-tag-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (tasks/rename-tag! (get-in state [:db :conn])
                        (common/task-id-param request)
                        (or (:body-params request) {})
                        (actions/actor-from-session (:auth/session request)
                                                    (common/workspace-id request))))))

(defn delete-tag-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (tasks/delete-tag! (get-in state [:db :conn])
                        (common/task-id-param request)
                        (actions/actor-from-session (:auth/session request)
                                                    (common/workspace-id request))))))

(defn routes
  [state]
  [["/tasks"
    {:middleware [common/require-session]}
    ["" {:get (list-tasks-handler state)
         :post (create-task-handler state)}]
    ["/recent" {:get (recent-tasks-handler state)}]
    ["/counts" {:get (task-counts-handler state)}]
    [task-id-path {:put (update-task-handler state)
                   :delete (delete-task-handler state)}]
    [(str task-id-path "/status") {:post (set-status-handler state)}]
    [(str task-id-path "/assignee") {:post (assign-task-handler state)}]
    [(str task-id-path "/due-date") {:post (due-date-handler state)}]
    [(str task-id-path "/tags") {:post (tags-handler state)}]
    [(str task-id-path "/archive") {:post (archive-handler state)}]]
   ["/tags"
    {:middleware [common/require-session]}
    ["" {:get (list-tags-handler state)
         :post (create-tag-handler state)}]
    ["/:id" {:put (update-tag-handler state)
             :delete (delete-tag-handler state)}]]])
