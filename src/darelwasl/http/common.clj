(ns darelwasl.http.common
  (:require [darelwasl.db :as db]
            [ring.middleware.session.memory :refer [memory-store]]))

(def session-opts
  {:store (memory-store)
   :cookie-attrs {:http-only true
                  :same-site :lax
                  :path "/"}})

(defn error-response
  [status message & [details]]
  (cond-> {:status status
           :body {:error message}}
    details (assoc-in [:body :details] details)))

(defn health-response
  [state]
  {:status 200
   :body {:service "darelwasl"
          :status "ok"
          :datastore (db/status (:db state))}})

(defn require-session
  [handler]
  (fn [request]
    (let [session (:session request)]
      (if (and session (:session/token session) (:user/id session))
        (handler (assoc request :auth/session session))
        (error-response 401 "Unauthorized")))))

(defn handle-task-result
  [result & [success-status]]
  (if-let [err (:error result)]
    (error-response (or (:status err) 500)
                    (:message err)
                    (:details err))
    {:status (or success-status 200)
     :body result}))

(defn task-id-param
  [request]
  (or (get-in request [:path-params :id])
      (get-in request [:path-params "id"])
      (get-in request [:parameters :path :id])
      (get-in request [:parameters :path "id"])
      (some-> request :path-params vals first)))
