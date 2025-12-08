(ns darelwasl.http
  (:require [darelwasl.auth :as auth]
            [darelwasl.db :as db]
            [darelwasl.tasks :as tasks]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.middleware.session :as session]
            [ring.middleware.session.memory :refer [memory-store]])
  (:import (java.util UUID)))

(defn- health-response
  [state]
  {:status 200
   :body {:service "darelwasl"
          :status "ok"
          :datastore (db/status (:db state))}})

(def session-opts
  {:store (memory-store)
   :cookie-attrs {:http-only true
                  :same-site :lax
                  :path "/"}})

(defn- error-response
  [status message & [details]]
  (cond-> {:status status
           :body {:error message}}
    details (assoc-in [:body :details] details)))

(defn- login-handler
  [state]
  (fn [request]
    (let [user-index (:auth/user-index state)
          body (or (:body-params request) {})
          username (or (:user/username body)
                       (get body "user/username"))
          password (or (:user/password body)
                       (get body "user/password"))]
      (cond
        (or (nil? user-index) (empty? user-index))
        (error-response 500 "Auth not configured")

        :else
        (let [{:keys [user error message]} (auth/authenticate user-index username password)]
          (cond
            error (if (= error :invalid-input)
                    (error-response 400 message)
                    (error-response 401 message))

            :else
            (let [existing-session (:session request)
                  token (or (:session/token existing-session)
                            (str (UUID/randomUUID)))
                  session-data {:session/token token
                                :user/id (:user/id user)
                                :user/username (:user/username user)}]
              {:status 200
               :body {:session/token token
                      :user/id (:user/id user)
                      :user/username (:user/username user)}
               :session session-data})))))))

(defn- require-session
  [handler]
  (fn [request]
    (let [session (:session request)]
      (if (and session (:session/token session) (:user/id session))
        (handler (assoc request :auth/session session))
        (error-response 401 "Unauthorized")))))

(defn- handle-task-result
  [result & [success-status]]
  (if-let [err (:error result)]
    (error-response (or (:status err) 500)
                    (:message err)
                    (:details err))
    {:status (or success-status 200)
     :body result}))

(defn- task-id-param
  [request]
  (get-in request [:path-params :id]))

(defn- list-tasks-handler
  [state]
  (fn [request]
    (handle-task-result
     (tasks/list-tasks (get-in state [:db :conn])
                       (:query-params request)))))

(defn- create-task-handler
  [state]
  (fn [request]
    (handle-task-result
     (tasks/create-task! (get-in state [:db :conn])
                         (or (:body-params request) {})
                         (:auth/session request))
     201)))

(defn- update-task-handler
  [state]
  (fn [request]
    (handle-task-result
     (tasks/update-task! (get-in state [:db :conn])
                         (task-id-param request)
                         (or (:body-params request) {})
                         (:auth/session request)))))

(defn- set-status-handler
  [state]
  (fn [request]
    (handle-task-result
     (tasks/set-status! (get-in state [:db :conn])
                        (task-id-param request)
                        (or (:body-params request) {})
                        (:auth/session request)))))

(defn- assign-task-handler
  [state]
  (fn [request]
    (handle-task-result
     (tasks/assign-task! (get-in state [:db :conn])
                         (task-id-param request)
                         (or (:body-params request) {})
                         (:auth/session request)))))

(defn- due-date-handler
  [state]
  (fn [request]
    (handle-task-result
     (tasks/set-due-date! (get-in state [:db :conn])
                          (task-id-param request)
                          (or (:body-params request) {})
                          (:auth/session request)))))

(defn- tags-handler
  [state]
  (fn [request]
    (handle-task-result
     (tasks/set-tags! (get-in state [:db :conn])
                      (task-id-param request)
                      (or (:body-params request) {})
                      (:auth/session request)))))

(defn- archive-handler
  [state]
  (fn [request]
    (handle-task-result
     (tasks/archive-task! (get-in state [:db :conn])
                          (task-id-param request)
                          (or (:body-params request) {})
                          (:auth/session request)))))

(defn- delete-task-handler
  [state]
  (fn [request]
    (handle-task-result
     (tasks/delete-task! (get-in state [:db :conn])
                         (task-id-param request)
                         (:auth/session request)))))

(defn- list-tags-handler
  [state]
  (fn [_request]
    (handle-task-result
     (tasks/list-tags (get-in state [:db :conn])))))

(defn- create-tag-handler
  [state]
  (fn [request]
    (handle-task-result
     (tasks/create-tag! (get-in state [:db :conn])
                        (or (:body-params request) {}))
     201)))

(defn- update-tag-handler
  [state]
  (fn [request]
    (handle-task-result
     (tasks/rename-tag! (get-in state [:db :conn])
                        (task-id-param request)
                        (or (:body-params request) {})))))

(defn- delete-tag-handler
  [state]
  (fn [request]
    (handle-task-result
     (tasks/delete-tag! (get-in state [:db :conn])
                        (task-id-param request)))))

(defn app
  "Build the Ring handler with shared middleware and routes."
  [state]
  (let [muuntaja-instance (m/create m/default-options)]
    (ring/ring-handler
     (ring/router
      [["/health" {:get (fn [_request] (health-response state))}]
       ["/api"
        ["/login" {:post (login-handler state)}]
        ["/tasks"
         {:middleware [require-session]}
         ["" {:get (list-tasks-handler state)
              :post (create-task-handler state)}]
         ["/:id" {:put (update-task-handler state)
                  :delete (delete-task-handler state)}]
         ["/:id/status" {:post (set-status-handler state)}]
         ["/:id/assignee" {:post (assign-task-handler state)}]
         ["/:id/due-date" {:post (due-date-handler state)}]
         ["/:id/tags" {:post (tags-handler state)}]
         ["/:id/archive" {:post (archive-handler state)}]]
        ["/tags"
         {:middleware [require-session]}
         ["" {:get (list-tags-handler state)
              :post (create-tag-handler state)}]
         ["/:id" {:put (update-tag-handler state)
                  :delete (delete-tag-handler state)}]]]]
     {:data {:muuntaja muuntaja-instance
             :middleware [[session/wrap-session session-opts]
                          parameters/parameters-middleware
                          muuntaja/format-negotiate-middleware
                          muuntaja/format-response-middleware
                          muuntaja/format-request-middleware
                          exception/exception-middleware]}})
     (ring/routes
      (ring/create-file-handler {:path "/"
                                 :root "public"})
      (ring/create-default-handler)))))
