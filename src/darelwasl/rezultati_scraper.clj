(ns darelwasl.rezultati-scraper
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private default-timeout-ms 3000)
(def ^:private default-cache-ttl-ms 30000)
(def ^:private default-base-url "https://m.rezultati.com")
(def ^:private default-user-agent
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")

(defonce ^:private cache-state
  (atom {}))

(defn- normalize-base-url
  [base-url]
  (-> (or base-url default-base-url)
      str/trim
      (str/replace #"/+$" "")))

(defn- blankish?
  [value]
  (or (nil? value)
      (and (string? value) (str/blank? value))))

(defn- normalize-sport-path
  [sport-path]
  (let [p (str/trim (or sport-path ""))]
    (cond
      (str/blank? p) ""
      (str/starts-with? p "/") (str/replace p #"/+$" "")
      :else (str "/" (str/replace p #"/+$" "")))))

(defn- parse-odds-value
  [value]
  (when-not (blankish? value)
    (let [raw (-> value str str/trim (str/replace #"," "."))]
      (try
        (Double/parseDouble raw)
        (catch Exception _ nil)))))

(defn- unescape-html
  [value]
  (-> (or value "")
      (str/replace "&amp;" "&")
      (str/replace "&quot;" "\"")
      (str/replace "&#39;" "'")
      (str/replace "&#039;" "'")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")))

(defn- request-html
  [cfg path]
  (let [timeout (or (:http-timeout-ms cfg) default-timeout-ms)
        base-url (normalize-base-url (:base-url cfg))
        url (str base-url path)]
    (try
      (let [resp (http/get url {:as :text
                                :throw-exceptions false
                                :socket-timeout timeout
                                :conn-timeout timeout
                                :headers {"User-Agent" default-user-agent
                                          "Accept" "text/html"
                                          "Accept-Language" "hr,en;q=0.8"}})
            status (:status resp)]
        (if (<= 200 status 299)
          {:status :ok
           :url url
           :body (:body resp)}
          {:error "Rezultati request failed"
           :status status
           :url url}))
      (catch Exception e
        (log/warn e "Rezultati request failed" {:path path})
        {:error "Rezultati request failed"}))))

(defn- normalize-html
  [html]
  (-> (or html "")
      (str/replace #"\r?\n" " ")
      (str/replace #"<img[^>]*>" "")
      (str/replace #"</?b>" "")
      (str/replace #"&nbsp;" " ")
      (str/replace #"\s+" " ")))

(def ^:private league-re
  #"<h4>\s*([^<]+)")

(def ^:private match-line-re
  #"<span[^>]*>([^<]+)(?:<span[^>]*>[^<]*</span>)?</span>\s*([^<]+?)\s*-\s*([^<]+?)\s*<a href=\"/utakmica/([A-Za-z0-9]+)/\?s=5\"[^>]*class=\"([^\"]+)\"[^>]*>[^<]*</a>\s*<span class=\"mobi-odds\">\s*\[\s*<a[^>]*>([^<]+)</a>\s*\|\s*<a[^>]*>([^<]+)</a>\s*\|\s*<a[^>]*>([^<]+)</a>")

(defn- status-from-class
  [class-name]
  (let [cls (str/lower-case (or class-name ""))]
    (cond
      (str/includes? cls "live") :live
      (str/includes? cls "fin") :final
      :else :scheduled)))

(defn- parse-match-line
  [line league]
  (when-let [[_ time home away match-id class-name odds-home odds-draw odds-away] (re-find match-line-re line)]
    (let [odds {:home (parse-odds-value odds-home)
                :draw (parse-odds-value odds-draw)
                :away (parse-odds-value odds-away)}]
      (when (every? number? (vals odds))
        {:match-id match-id
         :time (str/trim time)
         :home (unescape-html (str/trim home))
         :away (unescape-html (str/trim away))
         :league (some-> league unescape-html str/trim)
         :status (status-from-class class-name)
         :odds odds
         :market :1x2
         :bookmaker "bet365"
         :bookmakers [{:key "bet365"
                       :title "bet365"
                       :odds odds}]}))))

(defn- parse-odds-list
  [html]
  (let [clean (normalize-html html)]
    (let [parts (str/split clean #"<br\s*/>")
          state (reduce (fn [{:keys [league matches]} part]
                          (let [next-league (or (some-> (re-find league-re part) second) league)
                                match (parse-match-line part next-league)
                                updated (cond-> matches match (conj match))]
                            {:league next-league
                             :matches updated}))
                        {:league nil :matches []}
                        parts)
          matches (:matches state)]
      {:matches matches
       :groups (reduce (fn [acc match]
                         (let [league (or (:league match) "Other")
                               last-group (peek acc)]
                           (if (and last-group (= league (:league last-group)))
                             (conj (pop acc) (update last-group :matches conj match))
                             (conj acc {:league league
                                        :matches [match]}))))
                       []
                       matches)})))

(def ^:private match-teams-re
  #"<h3>\s*<a[^>]*>([^<]+)</a>\s*-\s*<a[^>]*>([^<]+)</a>\s*</h3>")

(def ^:private match-odds-re
  #"<p class=\"p-set odds-detail[^>]*>\s*<a[^>]*>([^<]+)</a>\s*\|\s*<a[^>]*>([^<]+)</a>\s*\|\s*<a[^>]*>([^<]+)</a>")

(def ^:private match-kickoff-re
  #"<div class=\"detail\">([^<]+)</div>")

(defn- parse-match-page
  [html match-id]
  (let [clean (normalize-html html)
        teams (re-find match-teams-re clean)
        odds-match (re-find match-odds-re clean)
        kickoff (second (re-find match-kickoff-re clean))
        odds (when odds-match
               {:home (parse-odds-value (nth odds-match 1 nil))
                :draw (parse-odds-value (nth odds-match 2 nil))
                :away (parse-odds-value (nth odds-match 3 nil))})]
    {:match-id match-id
     :home (some-> teams second str/trim unescape-html)
     :away (some-> teams (nth 2 nil) str/trim unescape-html)
     :kickoff (some-> kickoff str/trim)
     :odds (when (and odds (every? number? (vals odds))) odds)
     :market :1x2
     :bookmaker "bet365"
     :bookmakers (when (and odds (every? number? (vals odds)))
                   [{:key "bet365"
                     :title "bet365"
                     :odds odds}])}))

(defn fetch-daily-odds
  "Fetch a list of matches with 1X2 odds from the mobile odds page."
  ([] (fetch-daily-odds {} {}))
  ([cfg] (fetch-daily-odds cfg {}))
  ([cfg {:keys [day-offset sport-path] :or {day-offset 0 sport-path ""}}]
   (let [sport (normalize-sport-path sport-path)
         day (int day-offset)
         path (str sport "/?d=" day "&s=5")
         resp (request-html cfg path)]
     (if (:error resp)
       resp
       (let [{:keys [matches groups]} (parse-odds-list (:body resp))]
         {:status :ok
          :url (:url resp)
          :data {:day-offset day
                 :matches matches
                 :groups groups}})))))

(defn- cache-key
  [kind opts]
  (case kind
    :daily [:daily (select-keys opts [:day-offset :sport-path])]
    :match [:match (select-keys opts [:match-id :sport-path])]
    [kind opts]))

(defn- cache-get
  [key ttl-ms now-ms]
  (when-let [{:keys [fetched-at-ms value]} (get @cache-state key)]
    (when (and fetched-at-ms (<= (- now-ms fetched-at-ms) ttl-ms))
      {:fetched-at-ms fetched-at-ms
       :value value})))

(defn- cache-put!
  [key now-ms value]
  (swap! cache-state assoc key {:fetched-at-ms now-ms
                                :value value}))

(defn fetch-daily-odds-cached
  "Fetch daily odds with a small in-memory cache. Pass :refresh? true to bypass."
  ([] (fetch-daily-odds-cached {} {}))
  ([cfg] (fetch-daily-odds-cached cfg {}))
  ([cfg {:keys [refresh?] :as opts}]
   (let [now-ms (System/currentTimeMillis)
         ttl-ms (or (:cache-ttl-ms cfg) default-cache-ttl-ms)
         key (cache-key :daily opts)
         cached (when-not refresh? (cache-get key ttl-ms now-ms))]
     (if cached
       (assoc (:value cached)
              :cached? true
              :fetched-at-ms (:fetched-at-ms cached))
       (let [res (fetch-daily-odds cfg (dissoc opts :refresh?))]
         (if (:error res)
           res
           (let [value (assoc res :cached? false :fetched-at-ms now-ms)]
             (cache-put! key now-ms value)
             value)))))))

(defn fetch-match-odds
  "Fetch odds for a specific match id."
  ([cfg match-id] (fetch-match-odds cfg match-id {}))
  ([cfg match-id {:keys [sport-path] :or {sport-path ""}}]
   (if (blankish? match-id)
     {:error "Missing match id"}
     (let [sport (normalize-sport-path sport-path)
           path (str sport "/utakmica/" match-id "/?s=5")
           resp (request-html cfg path)]
       (if (:error resp)
         resp
         (let [data (parse-match-page (:body resp) match-id)]
           (cond-> {:status :ok
                    :url (:url resp)
                    :data data}
             (nil? (:odds data))
             (assoc :warning "Match odds not found"))))))))

(defn fetch-match-odds-cached
  "Fetch match odds with a small in-memory cache. Pass :refresh? true to bypass."
  ([cfg match-id] (fetch-match-odds-cached cfg match-id {}))
  ([cfg match-id {:keys [refresh?] :as opts}]
   (let [now-ms (System/currentTimeMillis)
         ttl-ms (or (:cache-ttl-ms cfg) default-cache-ttl-ms)
         key (cache-key :match (assoc (select-keys opts [:sport-path]) :match-id match-id))
         cached (when-not refresh? (cache-get key ttl-ms now-ms))]
     (if cached
       (assoc (:value cached)
              :cached? true
              :fetched-at-ms (:fetched-at-ms cached))
       (let [res (fetch-match-odds cfg match-id (dissoc opts :refresh?))]
         (if (:error res)
           res
           (let [value (assoc res :cached? false :fetched-at-ms now-ms)]
             (cache-put! key now-ms value)
             value)))))))

(defn match-id-from-url
  [value]
  (when (and (string? value) (not (str/blank? value)))
    (second (re-find #"/utakmica/([A-Za-z0-9]+)/" value))))
