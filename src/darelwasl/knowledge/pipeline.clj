(ns darelwasl.knowledge.pipeline
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.config :as config]
            [darelwasl.db :as db]
            [darelwasl.schema :as schema]
            [darelwasl.knowledge.adapters :as adapters]
            [darelwasl.knowledge.crawler :as crawler]
            [darelwasl.knowledge.store :as store])
  (:import (java.util UUID)
           (java.time Instant)))

(defn- now
  []
  (java.util.Date/from (Instant/now)))

(defn- git-sha
  []
  (System/getenv "GIT_SHA"))

(defn run-crawl!
  []
  (let [cfg (config/load-config)
        datomic (:datomic cfg)
        state (db/connect! datomic)]
    (if-let [err (:error state)]
      {:error err}
      (let [conn (:conn state)
            _ (schema/load-schema! conn)
            run-id (UUID/randomUUID)
            started-at (now)
            max-urls (get-in cfg [:knowledge :crawl-max-urls])
            adapter-filter (set (map (fn [val]
                                       (-> (or val "")
                                           str/lower-case
                                           (str/replace #"^:" "")))
                                     (or (get-in cfg [:knowledge :crawl-adapters]) [])))
            _ (store/create-crawl-run! conn {:run-id run-id
                                             :started-at started-at
                                             :status :crawl.run.status/running})
            base-state (assoc (crawler/start-state conn) :run-id [:crawl.run/id run-id])
            adapters (if (seq adapter-filter)
                       (filter (fn [adapter]
                                 (let [short-id (str/lower-case (name (:id adapter)))
                                       full-id (-> (:id adapter) str str/lower-case (str/replace #"^:" ""))]
                                   (or (contains? adapter-filter short-id)
                                       (contains? adapter-filter full-id))))
                               adapters/adapters)
                       adapters/adapters)
            results (reduce (fn [acc adapter]
                              (let [urls ((:discover adapter))
                                    urls (cond-> urls
                                           (and max-urls (pos? max-urls)) (->> (take max-urls) vec))
                                    crawl (crawler/crawl-urls (:state acc) urls)
                                    metrics (assoc (:metrics crawl)
                                                   :discovered (count urls)
                                                   :limit max-urls)
                                    entry {:adapter (:id adapter)
                                           :source (:source adapter)
                                           :metrics metrics}]
                                {:state (:state crawl)
                                 :results (conj (:results acc) entry)
                                 :errors (into (:errors acc) (:errors crawl))}))
                            {:state base-state :results [] :errors []}
                            adapters)
            finished-at (now)
            status (if (seq (:errors results)) :crawl.run.status/failed :crawl.run.status/succeeded)
            _ (store/finish-crawl-run! conn {:run-id run-id
                                             :finished-at finished-at
                                             :status status
                                             :metrics (:results results)
                                             :errors (:errors results)
                                             :git-sha (git-sha)})]
        {:run-id run-id
         :status status
         :metrics (:results results)
         :errors (:errors results)}))))

(defn -main
  [& _]
  (let [res (run-crawl!)]
    (if (:error res)
      (do
        (println "Crawl failed" (:error res))
        (System/exit 1))
      (do
        (println "Crawl completed" (select-keys res [:run-id :status]))
        (System/exit 0)))))
