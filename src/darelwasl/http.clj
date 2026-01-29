(ns darelwasl.http
  (:require [clojure.string :as str]
            [darelwasl.http.common :as common]
            [darelwasl.http.routes.actions :as actions-routes]
            [darelwasl.http.routes.agent-control :as agent-control-routes]
            [darelwasl.http.routes.auth :as auth-routes]
            [darelwasl.http.routes.betting :as betting-routes]
            [darelwasl.http.routes.catalog :as catalog-routes]
            [darelwasl.http.routes.clients :as clients-routes]
            [darelwasl.http.routes.content :as content-routes]
            [darelwasl.http.routes.events :as events-routes]
            [darelwasl.http.routes.files :as files-routes]
            [darelwasl.http.routes.github :as github-routes]
            [darelwasl.http.routes.land :as land-routes]
            [darelwasl.http.routes.preview :as preview-routes]
            [darelwasl.http.routes.registries :as registries-routes]
            [darelwasl.http.routes.system :as system-routes]
            [darelwasl.http.routes.tasks :as task-routes]
            [darelwasl.http.routes.telegram :as telegram-routes]
            [darelwasl.http.routes.users :as users-routes]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.middleware.cookies :as cookies]
            [ring.middleware.multipart-params :as multipart]
            [ring.middleware.session :as session]))

(def default-middleware
  [[common/wrap-logging]
   cookies/wrap-cookies
   [session/wrap-session common/session-opts]
   multipart/wrap-multipart-params
   parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   muuntaja/format-request-middleware
   exception/exception-middleware])

(def preview-middleware
  "Preview proxy routes must preserve raw request bodies for upstream apps.
  Do not include body-parsing middleware (e.g. muuntaja/format-request)."
  [[common/wrap-logging]
   cookies/wrap-cookies
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
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
         (catalog-routes/routes state)
         (clients-routes/routes state)
         (events-routes/routes state)
         (files-routes/routes state)
         (github-routes/routes state)
         (task-routes/routes state)
         (content-routes/routes state)
         (registries-routes/routes state)
         (system-routes/routes state)
         (agent-control-routes/routes state)
         (telegram-routes/routes state)
         (users-routes/routes state)
         (land-routes/routes state))))

(defn app
  "Build the Ring handler with shared middleware and domain routers."
  [state]
  (let [muuntaja-instance (m/create m/default-options)]
    (let [preview-router (ring/router
                          (preview-routes/routes state)
                          {:conflicts nil
                           :data {:muuntaja muuntaja-instance
                                  :middleware preview-middleware}})
          preview-handler (ring/ring-handler preview-router)
          app-router (ring/router
                      (concat
                       [(health-route state)]
                       [(api-routes state)])
                      {:conflicts nil
                       :data {:muuntaja muuntaja-instance
                              :middleware default-middleware}})
          app-handler (ring/ring-handler
                       app-router
                       (ring/routes
                        (ring/create-file-handler {:path "/"
                                                   :root "public"})
                        (ring/create-default-handler)))]
      (fn [request]
        ;; Avoid running preview requests through body-parsing middleware.
        ;; Match by URI prefix instead of reitit internals to keep this fast and robust.
        (if (some-> (:uri request) (str/starts-with? "/_preview"))
          (preview-handler request)
          (app-handler request))))))
