(ns darelwasl.http.routes.catalog
  (:require [clojure.string :as str]
            [darelwasl.catalog :as catalog]
            [darelwasl.http.common :as common]
            [datomic.client.api :as d]))

(defn- parse-int
  [value default]
  (try
    (Integer/parseInt (str value))
    (catch Exception _
      default)))

(defn- normalize-kind
  [value]
  (when value
    (let [raw (str/trim (str value))]
      (when-not (str/blank? raw)
        raw))))

(defn- list-handler
  [_state]
  (fn [request]
    (let [q (or (get-in request [:query-params :q])
                (get-in request [:query-params "q"]))
          kind (normalize-kind (or (get-in request [:query-params :kind])
                                   (get-in request [:query-params "kind"])))
          limit (parse-int (or (get-in request [:query-params :limit])
                               (get-in request [:query-params "limit"]))
                           60)
          res (catalog/list-catalog {:q q :kind kind :limit limit})]
      (if (seq (:entries res))
        {:status 200
         :body {:entries (:entries res)
                :version (:version res)}}
        {:status 200
         :body {:entries []
                :version (:version res)}}))))

(defn- detail-handler
  [_state]
  (fn [request]
    (let [entry-id (or (get-in request [:path-params :id])
                       (get-in request [:path-params "id"]))
          entry (catalog/find-entry entry-id)]
      (if entry
        {:status 200 :body {:entry entry}}
        (common/error-response 404 "Catalog entry not found")))))

(defn- data-handler
  [state]
  (fn [request]
    (let [q (or (get-in request [:query-params :q])
                (get-in request [:query-params "q"]))
          limit (parse-int (or (get-in request [:query-params :limit])
                               (get-in request [:query-params "limit"]))
                           50)
          q (some-> q str/trim)]
      (cond
        (str/blank? (str q)) (common/error-response 400 "Query is required for data search")
        :else
        (let [db (d/db (get-in state [:db :conn]))
              res (catalog/search-data db {:q q :limit limit})]
          {:status 200
           :body {:entries (:entries res)}})))))

(defn routes
  [state]
  [["/catalog"
    {:middleware [common/require-session
                  (common/require-roles #{:role/codex-terminal})]}
    ["" {:get (list-handler state)}]
    ["/data" {:get (data-handler state)}]
    ["/:id" {:get (detail-handler state)}]]])
