(ns darelwasl.http.routes.auth
  (:require [darelwasl.auth :as auth]
            [darelwasl.http.common :as common])
  (:import (java.util UUID)))

(defn login-handler
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
        (common/error-response 500 "Auth not configured")

        :else
        (let [{:keys [user error message]} (auth/authenticate user-index username password)]
          (cond
            error (if (= error :invalid-input)
                    (common/error-response 400 message)
                    (common/error-response 401 message))

            :else
            (let [existing-session (:session request)
                  token (or (:session/token existing-session)
                            (str (UUID/randomUUID)))
                  session-data {:session/token token
                                :user/id (:user/id user)
                                :user/username (:user/username user)
                                :user/roles (:user/roles user)}]
              {:status 200
               :body {:session/token token
                      :user/id (:user/id user)
                      :user/username (:user/username user)
                      :user/roles (:user/roles user)}
               :session session-data})))))))

(defn session-handler
  [_state]
  (fn [request]
    (let [session (:auth/session request)]
      {:status 200
       :body {:session/token (:session/token session)
              :user/id (:user/id session)
              :user/username (:user/username session)
              :user/roles (:user/roles session)}})))

(defn routes
  [state]
  [["/login" {:post (login-handler state)}]
   ["/session" {:middleware [common/require-session]
                :get (session-handler state)}]])
