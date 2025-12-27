(ns darelwasl.workspaces
  (:require [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.db :as db]
            [darelwasl.workspace :as workspace]))

(defn- error
  [status message & [details]]
  {:error {:status status
           :message message
           :details details}})

(defn- ensure-conn
  [conn]
  (when-not conn
    (error 500 "Database not ready")))

(defn promote-workspace!
  [conn {:keys [workspace-id target]}]
  (or (ensure-conn conn)
      (let [workspace-id (workspace/resolve-id workspace-id)
            target (workspace/resolve-id target)
            db (d/db conn)
            task-eids (map first (d/q '[:find ?e
                                        :in $ ?ws
                                        :where [?e :task/id _]
                                               [?e :fact/workspace ?ws]]
                                      db workspace-id))
            tag-eids (map first (d/q '[:find ?e
                                       :in $ ?ws
                                       :where [?e :tag/id _]
                                              [?e :tag/workspace ?ws]]
                                     db workspace-id))
            file-eids (map first (d/q '[:find ?e
                                        :in $ ?ws
                                        :where [?e :file/id _]
                                               [?e :file/workspace ?ws]]
                                      db workspace-id))
            note-eids (->> (concat
                            (map first (d/q '[:find ?e
                                              :in $ ?ws
                                              :where [?e :note/id _]
                                                     [?e :note/workspace ?ws]]
                                            db workspace-id))
                            (map first (d/q '[:find ?e
                                              :in $ ?ws
                                              :where [?e :note/id _]
                                                     [?e :fact/workspace ?ws]]
                                            db workspace-id)))
                           set)
            tx-data (vec (concat
                          (map (fn [e] [:db/add e :fact/workspace target]) task-eids)
                          (map (fn [e] [:db/add e :tag/workspace target]) tag-eids)
                          (map (fn [e] [:db/add e :file/workspace target]) file-eids)
                          (mapcat (fn [e]
                                    [[:db/add e :note/workspace target]
                                     [:db/add e :fact/workspace target]])
                                  note-eids)))]
        (try
          (when (seq tx-data)
            (db/transact! conn {:tx-data tx-data}))
          {:promotion {:workspace/id workspace-id
                       :workspace/target target
                       :moved {:tasks (count task-eids)
                               :tags (count tag-eids)
                               :files (count file-eids)
                               :notes (count note-eids)}
                       :tx-count (count tx-data)}}
          (catch Exception e
            (log/error e "Workspace promotion failed")
            {:error {:status 500
                     :message "Workspace promotion failed"
                     :details (.getMessage e)}}))))) 
