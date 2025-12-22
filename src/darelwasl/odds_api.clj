(ns darelwasl.odds-api
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private default-timeout-ms 3000)
(def ^:private default-base-url "https://api.the-odds-api.com/v4")

(defn- normalize-base-url
  [base-url]
  (-> (or base-url default-base-url)
      str/trim
      (str/replace #"/+$" "")))

(defn- parse-long
  [value]
  (when (and (some? value) (not (str/blank? (str value))))
    (try
      (Long/parseLong (str/trim (str value)))
      (catch Exception _ nil))))

(defn- blankish?
  [value]
  (or (nil? value)
      (and (string? value) (str/blank? value))))

(defn- compact-map
  [m]
  (into {}
        (remove (comp nil? val))
        m))

(defn- rate-limit-info
  [resp]
  (let [headers (:headers resp)]
    (compact-map
     {:remaining (parse-long (get headers "x-requests-remaining"))
      :used (parse-long (get headers "x-requests-used"))
      :reset-seconds (parse-long (get headers "x-requests-reset"))})))

(defn- parse-json-body
  [body]
  (try
    (cond
      (string? body) (json/read-str body :key-fn keyword)
      (map? body) body
      :else nil)
    (catch Exception _ nil)))

(defn- request-json
  [cfg path {:keys [query] :as _opts}]
  (if (str/blank? (:api-key cfg))
    {:error "Odds API key not configured"}
    (let [timeout (or (:http-timeout-ms cfg) default-timeout-ms)
          base-url (normalize-base-url (:base-url cfg))
          url (str base-url (if (str/starts-with? path "/") path (str "/" path)))
          query-params (assoc (or query {}) "apiKey" (:api-key cfg))]
      (try
        (let [resp (http/get url {:as :text
                                  :throw-exceptions false
                                  :socket-timeout timeout
                                  :conn-timeout timeout
                                  :query-params query-params})
              status (:status resp)
              body (parse-json-body (:body resp))
              rate-limit (rate-limit-info resp)
              quota-exhausted? (and (number? (:remaining rate-limit))
                                    (<= (:remaining rate-limit) 0))]
          (cond
            (<= 200 status 299)
            (do
              (when quota-exhausted?
                (log/warn "Odds API quota exhausted" {:path path :rate-limit rate-limit}))
              {:status :ok
               :data body
               :rate-limit rate-limit
               :quota-exhausted? quota-exhausted?})

            (= status 429)
            {:error "Odds API rate limit exceeded"
             :status status
             :details body
             :rate-limit rate-limit}

            :else
            {:error "Odds API request failed"
             :status status
             :details body
             :rate-limit rate-limit}))
        (catch Exception e
          (log/warn e "Odds API request failed" {:path path})
          {:error "Odds API request failed"})))))

(defn fetch-events
  "Fetch events for a sport key."
  ([cfg sport-key] (fetch-events cfg sport-key {}))
  ([cfg sport-key {:keys [query] :as opts}]
   (if (blankish? sport-key)
     {:error "Missing sport key"}
     (request-json cfg
                   (str "/sports/" (str sport-key) "/events")
                   (assoc opts :query (merge {"dateFormat" "iso"} (or query {})))))))

(defn fetch-event-odds
  "Fetch odds for a specific event id."
  ([cfg sport-key event-id] (fetch-event-odds cfg sport-key event-id {}))
  ([cfg sport-key event-id {:keys [query] :as opts}]
   (cond
     (blankish? sport-key) {:error "Missing sport key"}
     (blankish? event-id) {:error "Missing event id"}
     :else
     (request-json cfg
                   (str "/sports/" (str sport-key) "/events/" (str event-id) "/odds")
                   (assoc opts :query (merge {"dateFormat" "iso"
                                              "oddsFormat" "decimal"}
                                             (or query {})))))))

(defn fetch-scores
  "Fetch scores for a sport key."
  ([cfg sport-key] (fetch-scores cfg sport-key {}))
  ([cfg sport-key {:keys [query] :as opts}]
   (if (blankish? sport-key)
     {:error "Missing sport key"}
     (request-json cfg
                   (str "/sports/" (str sport-key) "/scores")
                   (assoc opts :query (merge {"dateFormat" "iso"} (or query {})))))))
