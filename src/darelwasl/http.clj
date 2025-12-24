(ns darelwasl.http
  (:require [darelwasl.http.common :as common]
            [darelwasl.http.routes.actions :as actions-routes]
            [darelwasl.http.routes.auth :as auth-routes]
            [darelwasl.http.routes.betting :as betting-routes]
            [darelwasl.http.routes.content :as content-routes]
            [darelwasl.http.routes.events :as events-routes]
            [darelwasl.http.routes.files :as files-routes]
            [darelwasl.http.routes.github :as github-routes]
            [darelwasl.http.routes.land :as land-routes]
            [darelwasl.http.routes.registries :as registries-routes]
            [darelwasl.http.routes.system :as system-routes]
            [darelwasl.http.routes.tasks :as task-routes]
            [darelwasl.http.routes.terminal :as terminal-routes]
            [darelwasl.http.routes.telegram :as telegram-routes]
            [darelwasl.http.routes.users :as users-routes]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.middleware.multipart-params :as multipart]
            [ring.middleware.session :as session]))

(def default-middleware
  [[common/wrap-logging]
   [session/wrap-session common/session-opts]
   multipart/wrap-multipart-params
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
         (actions-routes/routes state)
         (betting-routes/routes state)
         (events-routes/routes state)
         (files-routes/routes state)
         (github-routes/routes state)
         (task-routes/routes state)
         (content-routes/routes state)
         (registries-routes/routes state)
         (system-routes/routes state)
         (terminal-routes/routes state)
         (telegram-routes/routes state)
         (users-routes/routes state)
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
