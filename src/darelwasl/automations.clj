(ns darelwasl.automations
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.clients :as clients])
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
  [_state {:keys [id] :as automation} event]
  (let [user-id (get-in event [:event/payload :user/id])]
    (when (and user-id (not (str/blank? (str user-id))))
      [{:action/id :cap/action/task-create
        :actor (automation-actor automation)
        :input {:task/title "Telegram linked — try /tasks"
                :task/description "You successfully linked Telegram. Try: /tasks and /new <title> | <desc>."
                :task/status :todo
                :task/priority :low
                :task/client clients/default-client-id
                :task/assignee user-id
                :task/automation-key (str (name id) ":" user-id)}}])))

(defn- task-telegram-notify
  [state automation event]
  (let [conn (get-in state [:db :conn])
        task-id (get-in event [:event/payload :task/id])
        event-type (:event/type event)]
    (when (and conn task-id)
      (try
        (let [db (d/db conn)
              task (ffirst (d/q '[:find (pull ?t [:task/id :task/title :task/status :task/due-date
                                                   {:task/client [:client/name]}
                                                   {:task/assignee [:user/id]}])
                                 :in $ ?tid
                                 :where [?t :task/id ?tid]]
                               db task-id))
              assignee-id (get-in task [:task/assignee :user/id])]
          (when assignee-id
            (let [title (or (:task/title task) "Task")
                  status (some-> (:task/status task) name)
                  due (or (some-> (:task/due-date task) str) "—")
                  client (or (get-in task [:task/client :client/name]) "—")
                  short-id (subs (str task-id) 0 8)
                  header (case event-type
                           :task/assigned "Task assigned"
                           :task/status-changed "Task status updated"
                           :task/due-changed "Task due date updated"
                           "Task updated")
                  text (str header " (" short-id ")\n"
                            title "\n"
                            "Client: " client "\n"
                            "Status: " (or status "—") "\n"
                            "Due: " due)
                  message-key (str "task:" (name event-type) ":" task-id ":" (or status "na") ":" due)]
              [{:action/id :cap/action/telegram-notify
                :actor (automation-actor automation)
                :input {:user/id assignee-id
                        :telegram/text text
                        :telegram/message-key message-key}}])))
        (catch Exception e
          (log/warn e "Failed to derive task telegram notification")
          [])))))

(def ^:private handlers
  {:cap/automation.handler/telegram-onboarding-task telegram-onboarding-task
   :cap/automation.handler/task-telegram-notify task-telegram-notify})

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
                         (or (handler state automation event) [])
                         (catch Exception e
                           (log/warn e "Automation handler crashed" {:automation (:id automation)})
                           []))))))
         (remove nil?)
         vec)))
