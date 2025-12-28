;; HTTP routes for GitHub PR overview.
(ns darelwasl.http.routes.github
  (:require [clojure.string :as str]
            [darelwasl.actions :as actions]
            [darelwasl.http.common :as common]))

(defn pulls-handler
  [state]
  (fn [request]
    (let [state-param (get-in request [:query-params :state])
          action-res (actions/execute! state {:action/id :cap/action/github-pulls
                                              :actor (actions/actor-from-session (:auth/session request))
                                              :input (cond-> {}
                                                       state-param (assoc :pr/state state-param))})
          res (if (:error action-res) {:error (:error action-res)} (:result action-res))]
      (common/handle-task-result res))))

(defn close-pr-handler
  [state]
  (fn [request]
    (let [raw (get-in request [:path-params :number])
          trimmed (some-> raw str/trim)
          pr-number (when (and trimmed (re-matches #"\d+" trimmed))
                      (Long/parseLong trimmed))]
      (if (nil? pr-number)
        (common/error-response 400 "Invalid PR number")
        (let [action-res (actions/execute! state {:action/id :cap/action/github-close-pr
                                                  :actor (actions/actor-from-session (:auth/session request))
                                                  :input {:pr/number pr-number}})
              res (if (:error action-res) {:error (:error action-res)} (:result action-res))]
          (common/handle-task-result res))))))

(defn routes
  [state]
  [["/github"
    {:middleware [common/require-session
                  (common/require-roles #{:role/admin})]}
    ["/pulls" {:get (pulls-handler state)}]
    ["/pulls/:number/close" {:post (close-pr-handler state)}]]])
