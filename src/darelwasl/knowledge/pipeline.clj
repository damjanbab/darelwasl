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

(defn- run-adapter
  [state adapter max-urls started-at]
  (let [discovery ((:discover adapter))
        {:keys [urls error errors]} (if (map? discovery)
                                      discovery
                                      {:urls discovery})
        urls (vec (or urls []))
        urls (cond-> urls
               (and max-urls (pos? max-urls)) (->> (take max-urls) vec))
        discovery-errors (vec (concat (when error [error]) (or errors [])))
        discovery-blocker (some-> error
                                  (select-keys [:error-type :url :error])
                                  (assoc :type (:error-type error)
                                         :reason :discovery))
        crawl (if (seq urls)
                (crawler/crawl-urls state urls)
                {:state state
                 :metrics {:fetched 0 :ok 0 :failed 0 :blocked 0}
                 :errors []})
        metrics (assoc (:metrics crawl)
                       :discovered (count urls)
                       :limit max-urls)
        success-at (when (pos? (:ok metrics)) (now))
        network-error (first (or (when discovery-blocker [discovery-blocker])
                                 (->> (:errors crawl)
                                      (keep :blocker)
                                      (filter #(contains? #{:dns :timeout :ipv6} (:type %)))
                                      vec)))
        status (cond
                 (pos? (:ok metrics)) :source.status/reachable
                 (and (zero? (:ok metrics)) (pos? (:blocked metrics)))
                 :source.status/allowlist-blocked
                 network-error :source.status/network-blocked
                 :else :source.status/network-blocked)
        allowlist-blocker (first (->> (:errors crawl)
                                      (keep :blocker)
                                      (filter #(= :allowlist (:type %)))
                                      vec))
        blocker (cond
                  (= status :source.status/network-blocked)
                  (or network-error
                      (first (->> (:errors crawl)
                                  (keep :blocker)
                                  vec)))
                  (= status :source.status/allowlist-blocked)
                  (or allowlist-blocker
                      {:type :allowlist :reason :not-allowlisted})
                  :else nil)
        entry {:adapter (:id adapter)
               :source (:source adapter)
               :status status
               :blocker blocker
               :last-attempt-at started-at
               :last-success-at success-at
               :metrics metrics
               :discovery-errors discovery-errors}]
    {:state (:state crawl)
     :entry entry
     :errors (concat discovery-errors (:errors crawl))}))

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
            adapter-index (zipmap (map :id adapters/adapters) adapters/adapters)
            results (reduce (fn [acc adapter]
                              (let [{:keys [state entry errors]} (run-adapter (:state acc) adapter max-urls started-at)
                                    acc (-> acc
                                            (assoc :state state)
                                            (update :results conj entry)
                                            (update :errors into errors))
                                    mirror-ids (seq (:mirrors adapter))
                                    mirror-results (when (and (= :source.status/network-blocked (:status entry))
                                                              mirror-ids)
                                                     (reduce (fn [macc mirror-id]
                                                               (if-let [mirror (get adapter-index mirror-id)]
                                                                 (let [{:keys [state entry errors]} (run-adapter (:state macc)
                                                                                                                mirror
                                                                                                                max-urls
                                                                                                                started-at)
                                                                       mirror-entry (assoc entry :mirror-for (:id adapter))]
                                                                   (-> macc
                                                                       (assoc :state state)
                                                                       (update :results conj mirror-entry)
                                                                       (update :errors into errors)))
                                                                 macc))
                                                             {:state (:state acc)
                                                              :results []
                                                              :errors []}
                                                             mirror-ids))]
                                (if mirror-results
                                  (-> acc
                                      (assoc :state (:state mirror-results))
                                      (update :results into (:results mirror-results))
                                      (update :errors into (:errors mirror-results)))
                                  acc)))
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
