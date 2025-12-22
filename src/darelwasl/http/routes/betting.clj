(ns darelwasl.http.routes.betting
  (:require [clojure.string :as str]
            [darelwasl.betting :as betting]
            [darelwasl.http.common :as common]
            [darelwasl.rezultati-scraper :as rezultati]))

(defn- qparam
  [query key]
  (or (get query key)
      (get query (name key))))

(defn- parse-int
  [value default]
  (try
    (Integer/parseInt (str value))
    (catch Exception _
      default)))

(defn- truthy?
  [value]
  (when (some? value)
    (contains? #{"1" "true" "yes" "y" "on"} (str/lower-case (str value)))))

(defn- path-param
  [request key]
  (or (get-in request [:path-params key])
      (get-in request [:path-params (name key)])
      (get-in request [:parameters :path key])
      (get-in request [:parameters :path (name key)])))

(defn- error-status
  [res]
  (or (:status res)
      (when (= "Missing match id" (:error res)) 400)
      502))

(defn- handle-rezultati-result
  [res]
  (if-let [err (:error res)]
    (common/error-response (error-status res)
                           err
                           (dissoc res :error :status))
    {:status 200
     :body res}))

(defn- handle-domain-result
  [result]
  (common/handle-task-result result))

(defn rezultati-daily-handler
  [state]
  (fn [request]
    (let [query (:query-params request)
          day (parse-int (or (qparam query :day) (qparam query :d)) 0)
          sport (or (qparam query :sport) "")
          refresh? (truthy? (or (qparam query :refresh) (qparam query :force)))
          cfg (get-in state [:config :rezultati])
          res (rezultati/fetch-daily-odds-cached cfg {:day-offset day
                                                      :sport-path sport
                                                      :refresh? refresh?})]
      (handle-rezultati-result res))))

(defn rezultati-match-handler
  [state]
  (fn [request]
    (let [query (:query-params request)
          match-id (path-param request :id)
          sport (or (qparam query :sport) "")
          refresh? (truthy? (or (qparam query :refresh) (qparam query :force)))
          cfg (get-in state [:config :rezultati])
          res (rezultati/fetch-match-odds-cached cfg match-id {:sport-path sport
                                                               :refresh? refresh?})]
      (handle-rezultati-result res))))

(defn betting-events-handler
  [state]
  (fn [request]
    (let [query (:query-params request)
          day (parse-int (or (qparam query :day) (qparam query :d)) 0)
          sport (or (qparam query :sport) "")
          refresh? (truthy? (or (qparam query :refresh) (qparam query :force)))
          res (betting/list-events (get-in state [:db :conn])
                                   (:config state)
                                   {:day-offset day
                                    :sport-path sport
                                    :refresh? refresh?})]
      (handle-domain-result res))))

(defn betting-odds-handler
  [state]
  (fn [request]
    (let [query (:query-params request)
          event-id (path-param request :id)
          sport (or (qparam query :sport) "")
          refresh? (truthy? (or (qparam query :refresh) (qparam query :force)))
          res (betting/fetch-event-odds! (get-in state [:db :conn])
                                         (:config state)
                                         {:event-id event-id
                                          :sport-path sport
                                          :refresh? refresh?})]
      (handle-domain-result res))))

(defn betting-close-handler
  [state]
  (fn [request]
    (let [query (:query-params request)
          event-id (path-param request :id)
          sport (or (qparam query :sport) "")
          refresh? (truthy? (or (qparam query :refresh) (qparam query :force)))
          res (betting/capture-close! (get-in state [:db :conn])
                                      (:config state)
                                      {:event-id event-id
                                       :sport-path sport
                                       :refresh? refresh?})]
      (handle-domain-result res))))

(defn betting-bets-handler
  [state]
  (fn [request]
    (let [query (:query-params request)
          event-id (or (qparam query :event-id) (qparam query :event))]
      (handle-domain-result
       (betting/list-bets (get-in state [:db :conn])
                          (:config state)
                          {:event-id event-id})))))

(defn betting-log-bet-handler
  [state]
  (fn [request]
    (let [body (or (:body-params request) {})]
      (handle-domain-result
       (betting/log-bet! (get-in state [:db :conn])
                         (:config state)
                         {:event-id (or (:event-id body) (get body "event-id") (:betting.event/id body) (get body "betting.event/id"))
                          :market-key (or (:market-key body) (get body "market-key") (:betting.bet/market-key body))
                          :selection (or (:selection body) (get body "selection") (:betting.bet/selection body))
                          :odds (or (:odds body) (get body "odds") (:betting.bet/odds-decimal body))
                          :bookmaker-key (or (:bookmaker-key body) (get body "bookmaker-key"))})))))

(defn betting-settle-handler
  [state]
  (fn [request]
    (let [body (or (:body-params request) {})
          bet-id (path-param request :id)
          status (or (:status body) (get body "status") (:betting.bet/status body))]
      (handle-domain-result
       (betting/settle-bet! (get-in state [:db :conn])
                            (:config state)
                            {:bet-id bet-id
                             :status status})))))

(defn routes
  [state]
  [["/betting"
    {:middleware [common/require-session
                  (common/require-roles #{:role/betting-engineer})]}
    ["/events" {:get (betting-events-handler state)}]
    ["/events/:id/odds" {:get (betting-odds-handler state)}]
    ["/events/:id/close" {:post (betting-close-handler state)}]
    ["/bets" {:get (betting-bets-handler state)
              :post (betting-log-bet-handler state)}]
    ["/bets/:id/settle" {:post (betting-settle-handler state)}]
    ["/rezultati/daily" {:get (rezultati-daily-handler state)}]
    ["/rezultati/match/:id" {:get (rezultati-match-handler state)}]]])
