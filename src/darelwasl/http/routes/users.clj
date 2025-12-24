(ns darelwasl.http.routes.users
  (:require [darelwasl.http.common :as common]
            [darelwasl.users :as users]))

(def ^:private uuid-path
  "/:id{[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}")

(defn list-users-handler
  [state]
  (fn [_request]
    (common/handle-task-result
     (users/list-users (get-in state [:db :conn])))))

(defn create-user-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (users/create-user! (get-in state [:db :conn])
                         (or (:body-params request) {})
                         (:auth/session request))
     201)))

(defn update-user-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (users/update-user! (get-in state [:db :conn])
                         (common/task-id-param request)
                         (or (:body-params request) {})
                         (:auth/session request)))))

(defn delete-user-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (users/delete-user! (get-in state [:db :conn])
                         (common/task-id-param request)
                         (:auth/session request)))))

(defn routes
  [state]
  [["/users"
    {:middleware [common/require-session
                  (common/require-roles #{:role/admin})]}
    ["" {:get (list-users-handler state)
          :post (create-user-handler state)}]
    [uuid-path {:put (update-user-handler state)
                :delete (delete-user-handler state)}]]])
