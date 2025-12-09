(ns darelwasl.http.routes.land
  (:require [darelwasl.http.common :as common]
            [darelwasl.land :as land])
  (:import (java.util UUID)))

(defn list-people-handler
  [state]
  (fn [request]
    {:status 200
     :body {:people (land/people (get-in state [:db :conn]) (:query-params request))}}))

(defn person-detail-handler
  [state]
  (fn [request]
    (try
      (let [id-str (common/task-id-param request)
            person-id (UUID/fromString (str id-str))
            result (land/person-detail (get-in state [:db :conn]) person-id)]
        (if result
          {:status 200 :body result}
          (common/error-response 404 "Person not found")))
      (catch Exception _
        (common/error-response 400 "Invalid person id")))))

(defn list-parcels-handler
  [state]
  (fn [request]
    {:status 200
     :body {:parcels (land/parcels (get-in state [:db :conn]) (:query-params request))}}))

(defn parcel-detail-handler
  [state]
  (fn [request]
    (try
      (let [id-str (common/task-id-param request)
            parcel-id (UUID/fromString (str id-str))
            result (land/parcel-detail (get-in state [:db :conn]) parcel-id)]
        (if result
          {:status 200 :body result}
          (common/error-response 404 "Parcel not found")))
      (catch Exception _
        (common/error-response 400 "Invalid parcel id")))))

(defn stats-handler
  [state]
  (fn [_request]
    {:status 200
     :body (land/stats (get-in state [:db :conn]))}))

(defn routes
  [state]
  [["/land"
    {:middleware [common/require-session]}
    ["/people" {:get (list-people-handler state)}]
    ["/people/:id" {:get (person-detail-handler state)}]
    ["/parcels" {:get (list-parcels-handler state)}]
    ["/parcels/:id" {:get (parcel-detail-handler state)}]
    ["/stats" {:get (stats-handler state)}]]])
