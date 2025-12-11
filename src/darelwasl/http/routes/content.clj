(ns darelwasl.http.routes.content
  (:require [clojure.string :as str]
            [darelwasl.content :as content]
            [darelwasl.http.common :as common]))

(def ^:private uuid-path
  "/:id{[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}")

(defn- boolean-param
  [raw default]
  (let [val (some-> raw str/lower-case str/trim)]
    (cond
      (nil? val) default
      (#{"false" "0" "no" "off"} val) false
      (#{"true" "1" "yes" "on"} val) true
      :else default)))

(defn list-tags-handler
  [state]
  (fn [_request]
    (common/handle-task-result
     (content/list-tags (get-in state [:db :conn])))))

(defn create-tag-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (content/create-tag! (get-in state [:db :conn])
                          (or (:body-params request) {})
                          (:auth/session request))
     201)))

(defn update-tag-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (content/update-tag! (get-in state [:db :conn])
                          (common/task-id-param request)
                          (or (:body-params request) {})
                          (:auth/session request)))))

(defn delete-tag-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (content/delete-tag! (get-in state [:db :conn])
                          (common/task-id-param request)
                          (:auth/session request)))))

(defn list-pages-handler
  [state]
  (fn [request]
    (let [with-blocks? (boolean-param (get-in request [:query-params :with-blocks]) true)]
      (common/handle-task-result
       (content/list-pages (get-in state [:db :conn]) {:with-blocks? with-blocks?})))))

(defn create-page-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (content/create-page! (get-in state [:db :conn])
                           (or (:body-params request) {})
                           (:auth/session request))
     201)))

(defn update-page-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (content/update-page! (get-in state [:db :conn])
                           (common/task-id-param request)
                           (or (:body-params request) {})
                           (:auth/session request)))))

(defn delete-page-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (content/delete-page! (get-in state [:db :conn])
                           (common/task-id-param request)
                           (:auth/session request)))))

(defn list-blocks-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (content/list-blocks (get-in state [:db :conn])
                          {:page-id (or (get-in request [:query-params :page-id])
                                        (get-in request [:query-params "page-id"]))}))))

(defn create-block-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (content/create-block! (get-in state [:db :conn])
                            (or (:body-params request) {})
                            (:auth/session request))
     201)))

(defn update-block-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (content/update-block! (get-in state [:db :conn])
                            (common/task-id-param request)
                            (or (:body-params request) {})
                            (:auth/session request)))))

(defn delete-block-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (content/delete-block! (get-in state [:db :conn])
                            (common/task-id-param request)
                            (:auth/session request)))))

(defn routes
  [state]
  [["/content"
    {:middleware [common/require-session
                  (common/require-roles #{:role/content-editor :role/admin})]}
    ["/tags"
     ["" {:get (list-tags-handler state)
          :post (create-tag-handler state)}]
     [uuid-path {:put (update-tag-handler state)
                 :delete (delete-tag-handler state)}]]
    ["/pages"
     ["" {:get (list-pages-handler state)
          :post (create-page-handler state)}]
     [uuid-path {:put (update-page-handler state)
                 :delete (delete-page-handler state)}]]
    ["/blocks"
     ["" {:get (list-blocks-handler state)
          :post (create-block-handler state)}]
     [uuid-path {:put (update-block-handler state)
                 :delete (delete-block-handler state)}]]]])
