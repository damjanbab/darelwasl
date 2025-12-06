(ns darelwasl.http
  (:require [darelwasl.auth :as auth]
            [darelwasl.db :as db]
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
  [status message]
  {:status status
   :body {:error message}})

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

(defn app
  "Build the Ring handler with shared middleware and routes."
  [state]
  (let [muuntaja-instance (m/create m/default-options)]
    (ring/ring-handler
     (ring/router
      [["/health" {:get (fn [_request] (health-response state))}]
       ["/api/login" {:post (login-handler state)}]]
      {:data {:muuntaja muuntaja-instance
              :middleware [[session/wrap-session session-opts]
                           parameters/parameters-middleware
                           muuntaja/format-negotiate-middleware
                           muuntaja/format-response-middleware
                           muuntaja/format-request-middleware
                           exception/exception-middleware]}})
     (ring/create-default-handler))))
