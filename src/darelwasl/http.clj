(ns darelwasl.http
  (:require [darelwasl.http.common :as common]
            [darelwasl.http.routes.auth :as auth-routes]
            [darelwasl.http.routes.land :as land-routes]
            [darelwasl.http.routes.tasks :as task-routes]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.middleware.session :as session]))

(def default-middleware
  [[session/wrap-session common/session-opts]
   parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   muuntaja/format-request-middleware
   exception/exception-middleware])

(defn health-route
  [state]
  ["/health" {:get (fn [_request] (common/health-response state))}])

(defn api-routes
  [state]
  (into ["/api"]
        (concat
         (auth-routes/routes state)
         (task-routes/routes state)
         (land-routes/routes state))))

(defn app
  "Build the Ring handler with shared middleware and domain routers."
  [state]
  (let [muuntaja-instance (m/create m/default-options)]
    (ring/ring-handler
     (ring/router
      [(health-route state)
       (api-routes state)]
      {:conflicts nil
       :data {:muuntaja muuntaja-instance
              :middleware default-middleware}})
     (ring/routes
      (ring/create-file-handler {:path "/"
                                 :root "public"})
      (ring/create-default-handler)))))
