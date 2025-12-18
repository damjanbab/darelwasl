(ns darelwasl.automations
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import (java.io PushbackReader)))

(def default-registry-path "registries/automations.edn")

(defn read-registry
  ([] (read-registry default-registry-path))
  ([path]
   (let [file (io/file path)]
     (when-not (.exists file)
       [])
     (with-open [r (PushbackReader. (io/reader file))]
       (edn/read r)))))

(defn- enabled?
  [automation]
  (true? (:enabled automation)))

(defn- trigger-matches?
  [event trigger]
  (= (:event/type event) (:event/type trigger)))

(defn matching-automations
  [automations event]
  (->> automations
       (filter enabled?)
       (filter (fn [a]
                 (some #(trigger-matches? event %) (:triggers a))))))

(defn- automation-actor
  [automation]
  {:actor/type :actor.type/automation
   :automation/id (:id automation)
   :actor/surface :surface/automation})

(defn- telegram-onboarding-task
  [{:keys [id] :as automation} event]
  (let [user-id (get-in event [:event/payload :user/id])]
    (when (and user-id (not (str/blank? (str user-id))))
      [{:action/id :cap/action/task-create
        :actor (automation-actor automation)
        :input {:task/title "Telegram linked — try /tasks"
                :task/description "You successfully linked Telegram. Try: /tasks and /new <title> | <desc>."
                :task/status :todo
                :task/priority :low
                :task/assignee user-id
                :task/automation-key (str (name id) ":" user-id)}}])))

(def ^:private handlers
  {:cap/automation.handler/telegram-onboarding-task telegram-onboarding-task})

(defn derive-invocations
  "Return a vector of action invocations for this event. Rules are code-first
  for now: the registry selects a handler, and handlers emit action invocations."
  [state event]
  (let [automations (read-registry)
        matches (matching-automations automations event)]
    (->> matches
         (mapcat (fn [automation]
                   (let [handler-id (:handler automation)
                         handler (get handlers handler-id)]
                     (cond
                       (nil? handler)
                       (do
                         (log/warn "Missing automation handler" {:handler handler-id :automation (:id automation)})
                         [])

                       :else
                       (try
                         (or (handler automation event) [])
                         (catch Exception e
                           (log/warn e "Automation handler crashed" {:automation (:id automation)})
                           []))))))
         (remove nil?)
         vec)))

