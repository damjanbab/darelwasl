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

(defn list-licenses-handler [state]
  (fn [_request]
    (common/handle-task-result
     (content/list-licenses (get-in state [:db :conn])))))

(defn create-license-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/upsert-license! (get-in state [:db :conn])
                              (or (:body-params request) {})
                              (:auth/session request))
     201)))

(defn update-license-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/upsert-license! (get-in state [:db :conn])
                              (assoc (or (:body-params request) {}) :license/id (common/task-id-param request))
                              (:auth/session request)))))

(defn delete-license-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/delete-license! (get-in state [:db :conn])
                              (common/task-id-param request)
                              (:auth/session request)))))

(defn list-comparison-rows-handler [state]
  (fn [_request]
    (common/handle-task-result
     (content/list-comparison-rows (get-in state [:db :conn])))))

(defn create-comparison-row-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/upsert-comparison-row! (get-in state [:db :conn])
                                     (or (:body-params request) {})
                                     (:auth/session request))
     201)))

(defn update-comparison-row-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/upsert-comparison-row! (get-in state [:db :conn])
                                     (assoc (or (:body-params request) {}) :comparison.row/id (common/task-id-param request))
                                     (:auth/session request)))))

(defn delete-comparison-row-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/delete-comparison-row! (get-in state [:db :conn])
                                     (common/task-id-param request)
                                     (:auth/session request)))))

(defn list-journey-phases-handler [state]
  (fn [_request]
    (common/handle-task-result
     (content/list-journey-phases (get-in state [:db :conn])))))

(defn create-journey-phase-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/upsert-journey-phase! (get-in state [:db :conn])
                                    (or (:body-params request) {})
                                    (:auth/session request))
     201)))

(defn update-journey-phase-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/upsert-journey-phase! (get-in state [:db :conn])
                                    (assoc (or (:body-params request) {}) :journey.phase/id (common/task-id-param request))
                                    (:auth/session request)))))

(defn delete-journey-phase-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/delete-journey-phase! (get-in state [:db :conn])
                                    (common/task-id-param request)
                                    (:auth/session request)))))

(defn list-activation-steps-handler [state]
  (fn [_request]
    (common/handle-task-result
     (content/list-activation-steps (get-in state [:db :conn])))))

(defn create-activation-step-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/upsert-activation-step! (get-in state [:db :conn])
                                      (or (:body-params request) {})
                                      (:auth/session request))
     201)))

(defn update-activation-step-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/upsert-activation-step! (get-in state [:db :conn])
                                      (assoc (or (:body-params request) {}) :activation.step/id (common/task-id-param request))
                                      (:auth/session request)))))

(defn delete-activation-step-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/delete-activation-step! (get-in state [:db :conn])
                                      (common/task-id-param request)
                                      (:auth/session request)))))

(defn list-personas-handler [state]
  (fn [_request]
    (common/handle-task-result
     (content/list-personas (get-in state [:db :conn])))))

(defn create-persona-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/upsert-persona! (get-in state [:db :conn])
                              (or (:body-params request) {})
                              (:auth/session request))
     201)))

(defn update-persona-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/upsert-persona! (get-in state [:db :conn])
                              (assoc (or (:body-params request) {}) :persona/id (common/task-id-param request))
                              (:auth/session request)))))

(defn delete-persona-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/delete-persona! (get-in state [:db :conn])
                              (common/task-id-param request)
                              (:auth/session request)))))

(defn list-support-entries-handler [state]
  (fn [_request]
    (common/handle-task-result
     (content/list-support-entries (get-in state [:db :conn])))))

(defn create-support-entry-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/upsert-support-entry! (get-in state [:db :conn])
                                    (or (:body-params request) {})
                                    (:auth/session request))
     201)))

(defn update-support-entry-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/upsert-support-entry! (get-in state [:db :conn])
                                    (assoc (or (:body-params request) {}) :support.entry/id (common/task-id-param request))
                                    (:auth/session request)))))

(defn delete-support-entry-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/delete-support-entry! (get-in state [:db :conn])
                                    (common/task-id-param request)
                                    (:auth/session request)))))

(defn list-contacts-handler [state]
  (fn [_request]
    (common/handle-task-result
     (content/list-contacts (get-in state [:db :conn])))))

(defn create-contact-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/upsert-contact! (get-in state [:db :conn])
                              (or (:body-params request) {})
                              (:auth/session request))
     201)))

(defn update-contact-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/upsert-contact! (get-in state [:db :conn])
                              (assoc (or (:body-params request) {}) :contact/id (common/task-id-param request))
                              (:auth/session request)))))

(defn list-businesses-handler [state]
  (fn [_request]
    (common/handle-task-result
     (content/list-businesses (get-in state [:db :conn])))))

(defn create-business-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/upsert-business! (get-in state [:db :conn])
                               (or (:body-params request) {})
                               (:auth/session request))
     201)))

(defn update-business-handler [state]
  (fn [request]
    (common/handle-task-result
     (content/upsert-business! (get-in state [:db :conn])
                               (assoc (or (:body-params request) {}) :business/id (common/task-id-param request))
                               (:auth/session request)))))

(defn list-v2-handler
  [state]
  (fn [_request]
    (common/handle-task-result
     (content/list-content-v2 (get-in state [:db :conn])))))

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
                 :delete (delete-block-handler state)}]]
    ["/licenses"
     ["" {:get (list-licenses-handler state)
          :post (create-license-handler state)}]
     [uuid-path {:put (update-license-handler state)
                 :delete (delete-license-handler state)}]]
    ["/comparison-rows"
     ["" {:get (list-comparison-rows-handler state)
          :post (create-comparison-row-handler state)}]
     [uuid-path {:put (update-comparison-row-handler state)
                 :delete (delete-comparison-row-handler state)}]]
    ["/journey-phases"
     ["" {:get (list-journey-phases-handler state)
          :post (create-journey-phase-handler state)}]
     [uuid-path {:put (update-journey-phase-handler state)
                 :delete (delete-journey-phase-handler state)}]]
    ["/activation-steps"
     ["" {:get (list-activation-steps-handler state)
          :post (create-activation-step-handler state)}]
     [uuid-path {:put (update-activation-step-handler state)
                 :delete (delete-activation-step-handler state)}]]
    ["/personas"
     ["" {:get (list-personas-handler state)
          :post (create-persona-handler state)}]
     [uuid-path {:put (update-persona-handler state)
                 :delete (delete-persona-handler state)}]]
    ["/support-entries"
     ["" {:get (list-support-entries-handler state)
          :post (create-support-entry-handler state)}]
     [uuid-path {:put (update-support-entry-handler state)
                 :delete (delete-support-entry-handler state)}]]
    ["/contacts"
     ["" {:get (list-contacts-handler state)
          :post (create-contact-handler state)}]
     [uuid-path {:put (update-contact-handler state)}]]
    ["/businesses"
     ["" {:get (list-businesses-handler state)
          :post (create-business-handler state)}]
     [uuid-path {:put (update-business-handler state)}]]
    ["/v2" {:get (list-v2-handler state)}]]])
