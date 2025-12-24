;; HTTP routes for the file library.
(ns darelwasl.http.routes.files
  (:require [clojure.java.io :as io]
            [darelwasl.actions :as actions]
            [darelwasl.files :as files]
            [darelwasl.http.common :as common]
            [ring.util.response :as response]))

(def ^:private file-id-path
  "/:id{[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}")

(defn- storage-dir
  [state]
  (get-in state [:config :files :storage-dir]))

(defn list-files-handler
  [state]
  (fn [request]
    (common/handle-task-result
     (files/list-files (get-in state [:db :conn])
                       (:query-params request)))))

(defn upload-file-handler
  [state]
  (fn [request]
    (let [params (merge (:params request) (:multipart-params request))
          upload (or (get params "file") (get params :file))
          slug (or (get params "slug") (get params :slug))
          res (actions/execute! state {:action/id :cap/action/file-upload
                                       :actor (actions/actor-from-session (:auth/session request))
                                       :input {:file/upload upload
                                               :file/slug slug
                                               :storage-dir (storage-dir state)}})
          payload (if (:error res) {:error (:error res)} (:result res))]
      (common/handle-task-result payload 201))))

(defn delete-file-handler
  [state]
  (fn [request]
    (let [file-id (common/task-id-param request)
          res (actions/execute! state {:action/id :cap/action/file-delete
                                       :actor (actions/actor-from-session (:auth/session request))
                                       :input {:file/id file-id
                                               :storage-dir (storage-dir state)}})
          payload (if (:error res) {:error (:error res)} (:result res))]
      (common/handle-task-result payload))))

(defn file-content-handler
  [state]
  (fn [request]
    (let [file-id (common/task-id-param request)
          res (files/fetch-file (get-in state [:db :conn]) file-id)]
      (if-let [err (:error res)]
        (common/error-response (or (:status err) 500)
                               (:message err)
                               (:details err))
        (let [{:file/keys [storage-path mime name]} (:file res)
              root (storage-dir state)
              path (when (and root storage-path)
                     (.getPath (io/file root storage-path)))]
          (if (and path (.exists (io/file path)))
            (-> (response/file-response path)
                (response/content-type (or mime "application/octet-stream"))
                (response/header "Content-Disposition"
                                 (str "inline; filename=\"" (or name "file") "\"")))
            (common/error-response 404 "File content not found")))))))

(defn routes
  [state]
  [["/files"
    {:middleware [common/require-session
                  (common/require-roles #{:role/file-library :role/admin})]}
    ["" {:get (list-files-handler state)
         :post (upload-file-handler state)}]
    [file-id-path {:delete (delete-file-handler state)}]
    [(str file-id-path "/content") {:get (file-content-handler state)}]]])
