(ns darelwasl.http.routes.knowledge
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [darelwasl.http.common :as common]
            [darelwasl.knowledge.query :as kq]))

(defn- parse-int
  [value default]
  (try
    (Integer/parseInt (str value))
    (catch Exception _ default)))

(defn- parse-uuid
  [value]
  (try
    (java.util.UUID/fromString (str value))
    (catch Exception _ nil)))

(defn- keyword-or-nil
  [value]
  (when (and value (not (str/blank? (str value))))
    (keyword (str value))))

(defn- keyword-with-ns
  [value ns-name]
  (when (and value (not (str/blank? (str value))))
    (keyword ns-name (str value))))

(defn search-handler
  [state]
  (fn [request]
    (let [params (merge (:query-params request) (:params request))
          q (get params "q")
          doc-type (keyword-with-ns (get params "type") "doc.type")
          org (get params "org")
          language (keyword-with-ns (get params "language") "lang")
          topic (get params "topic")
          authority-band (keyword-or-nil (get params "authority"))
          issued-from (get params "issued_from")
          issued-to (get params "issued_to")
          effective-from (get params "effective_from")
          effective-to (get params "effective_to")
          publication-from (get params "publication_from")
          publication-to (get params "publication_to")
          has-decisions? (= "true" (str/lower-case (str (get params "has_decisions"))))
          limit (parse-int (get params "limit") 50)
          db (d/db (get-in state [:db :conn]))
          entries (kq/search-docs db {:q q
                                      :doc-type doc-type
                                      :org org
                                      :language language
                                      :topic topic
                                      :authority-band authority-band
                                      :issued-from issued-from
                                      :issued-to issued-to
                                      :effective-from effective-from
                                      :effective-to effective-to
                                      :publication-from publication-from
                                      :publication-to publication-to
                                      :has-decisions? has-decisions?
                                      :limit limit})]
      {:status 200
       :body {:entries entries}})))

(defn instrument-handler
  [state]
  (fn [request]
    (let [raw (or (get-in request [:path-params :id]) (get-in request [:path-params "id"]))
          iid (parse-uuid raw)
          db (d/db (get-in state [:db :conn]))]
      (if-let [instrument (and iid (kq/instrument-detail db iid))]
        {:status 200 :body instrument}
        (common/error-response 404 "Instrument not found")))))

(defn instrument-text-handler
  [state]
  (fn [request]
    (let [raw (or (get-in request [:path-params :id]) (get-in request [:path-params "id"]))
          version (get-in request [:query-params :version])
          iid (parse-uuid raw)
          db (d/db (get-in state [:db :conn]))]
      (if-not iid
        (common/error-response 400 "Invalid instrument id")
        (let [detail (kq/instrument-detail db iid)
              version-id (or (some-> version parse-uuid)
                             (-> detail :versions first :instrument.version/id))]
          (if (and detail version-id)
            {:status 200
             :body {:instrument (:instrument detail)
                    :version-id version-id
                    :sections (kq/instrument-sections db version-id)}}
            (common/error-response 404 "Instrument version not found")))))))

(defn decision-handler
  [state]
  (fn [request]
    (let [raw (or (get-in request [:path-params :id]) (get-in request [:path-params "id"]))
          did (parse-uuid raw)
          db (d/db (get-in state [:db :conn]))]
      (if-let [decision (and did (kq/decision-detail db did))]
        {:status 200 :body decision}
        (common/error-response 404 "Decision not found")))))

(defn sources-handler
  [state]
  (fn [_request]
    (let [db (d/db (get-in state [:db :conn]))
          runs (kq/list-sources db)]
      {:status 200
       :body {:runs runs}})))

(defn routes
  [state]
  [["/knowledge"
    {:middleware [common/require-session]}
    ["/search" {:get (search-handler state)}]
    ["/instruments/:id" {:get (instrument-handler state)}]
    ["/instruments/:id/text" {:get (instrument-text-handler state)}]
    ["/decisions/:id" {:get (decision-handler state)}]
    ["/sources" {:get (sources-handler state)}]]])
