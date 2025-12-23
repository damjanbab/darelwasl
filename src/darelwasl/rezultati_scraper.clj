(ns darelwasl.rezultati-scraper
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private default-timeout-ms 3000)
(def ^:private default-cache-ttl-ms 30000)
(def ^:private default-sports-cache-ttl-ms 3600000)
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

(defn- sport-key-from-path
  [sport-path]
  (let [clean (-> (or sport-path "")
                  str/trim
                  (str/replace #"^/" "")
                  (str/replace #"/+$" ""))]
    (if (str/blank? clean) "soccer" clean)))

(defn- sport-title-from-path
  [sport-path]
  (let [clean (sport-key-from-path sport-path)]
    (-> clean
        (str/replace #"-" " ")
        str/capitalize)))

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

(def ^:private sports-menu-re
  #"<p class=\"p-set menu\">(.*?)</p>")

(def ^:private sports-link-re
  #"<a href=\"([^\"]+)\"[^>]*>([^<]+)</a>")

(def ^:private other-sports-menu-re
  #"<ul class=\"other-sports-menu\">(.*?)</ul>")

(defn- parse-sports-menu
  [html]
  (let [clean (normalize-html html)
        menu (some-> (re-find sports-menu-re clean) second)
        links (when menu (re-seq sports-link-re menu))]
    (->> links
         (keep (fn [[_ href title]]
                 (let [path (normalize-sport-path href)
                       label (some-> title unescape-html str/trim)]
                   (when (and (seq label)
                              (str/starts-with? (or href "") "/")
                              (not (str/includes? path "ostali-sportovi")))
                     {:sport-path path
                      :sport-title label}))))
         vec)))

(defn- parse-other-sports
  [html]
  (let [clean (normalize-html html)
        menu (some-> (re-find other-sports-menu-re clean) second)
        links (when menu (re-seq sports-link-re menu))]
    (->> links
         (keep (fn [[_ href title]]
                 (let [path (normalize-sport-path href)
                       label (some-> title unescape-html str/trim)]
                   (when (and (seq label)
                              (str/starts-with? (or href "") "/"))
                     {:sport-path path
                      :sport-title label}))))
         vec)))

(def ^:private league-re
  #"<h4>\s*([^<]+)")

(def ^:private match-line-re
  #"<span[^>]*>([^<]+)(?:<span[^>]*>[^<]*</span>)?</span>\s*([^<]+?)\s*-\s*([^<]+?)\s*<a href=\"/utakmica/([A-Za-z0-9]+)/\?[^\"#]*\"[^>]*class=\"([^\"]+)\"[^>]*>([^<]*)</a>\s*<span class=\"mobi-odds\">\s*\[\s*<a[^>]*>([^<]+)</a>\s*\|\s*<a[^>]*>([^<]+)</a>\s*\|\s*<a[^>]*>([^<]+)</a>")

(defn- status-from-class
  [class-name]
  (let [cls (str/lower-case (or class-name ""))]
    (cond
      (str/includes? cls "live") :live
      (str/includes? cls "fin") :final
      :else :scheduled)))

(defn- parse-score
  [value]
  (let [clean (some-> value str/trim)]
    (when (and (seq clean) (re-find #"\d+\s*:\s*\d+" clean))
      (str/replace clean #"\s+" ""))))

(defn- parse-match-line
  [line league]
  (when-let [[_ time home away match-id class-name score odds-home odds-draw odds-away]
             (re-find match-line-re line)]
    (let [odds {:home (parse-odds-value odds-home)
                :draw (parse-odds-value odds-draw)
                :away (parse-odds-value odds-away)}]
      (when (every? number? (vals odds))
        {:match-id match-id
         :time (some-> time str/trim)
         :home (unescape-html (str/trim home))
         :away (unescape-html (str/trim away))
         :league (some-> league unescape-html str/trim)
         :status (status-from-class class-name)
         :score (parse-score score)
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

(defn- dedupe-sports
  [sports]
  (let [seen (atom #{})]
    (->> sports
         (filter (fn [{:keys [sport-path]}]
                   (let [k (or sport-path "")]
                     (when-not (contains? @seen k)
                       (swap! seen conj k)
                       true))))
         vec)))

(defn fetch-sports-index
  "Fetch sports menu entries from Rezultati."
  ([]
   (fetch-sports-index {}))
  ([cfg]
   (let [base-resp (request-html cfg "/")
         other-resp (request-html cfg "/ostali-sportovi/")]
     (if (and (:error base-resp) (:error other-resp))
       base-resp
       (let [base-sports (if (:error base-resp) [] (parse-sports-menu (:body base-resp)))
             other-sports (if (:error other-resp) [] (parse-other-sports (:body other-resp)))]
         {:status :ok
          :url (:url base-resp)
          :data {:sports (dedupe-sports (concat base-sports other-sports))}})))))

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
  ([cfg {:keys [day-offset sport-path sport-title] :or {day-offset 0 sport-path ""}}]
   (let [sport (normalize-sport-path sport-path)
         day (int day-offset)
         path (str sport "/?d=" day "&s=5")
         resp (request-html cfg path)]
     (if (:error resp)
       resp
       (let [{:keys [matches groups]} (parse-odds-list (:body resp))
             title (or (some-> sport-title str/trim) (sport-title-from-path sport))
             key (sport-key-from-path sport)
             matches' (mapv (fn [match]
                              (assoc match
                                     :sport-key key
                                     :sport-title title
                                     :sport-path sport))
                            matches)
             groups' (mapv (fn [group]
                             (-> group
                                 (assoc :sport-key key
                                        :sport-title title
                                        :sport-path sport)
                                 (update :matches (fn [ms]
                                                    (mapv (fn [m]
                                                            (assoc m
                                                                   :sport-key key
                                                                   :sport-title title
                                                                   :sport-path sport))
                                                          ms)))))
                           groups)]
         {:status :ok
          :url (:url resp)
          :data {:day-offset day
                 :sport-path sport
                 :sport-title title
                 :sport-key key
                 :matches matches'
                 :groups groups'}})))))

(defn- cache-key
  [kind opts]
  (case kind
    :daily [:daily (select-keys opts [:day-offset :sport-path])]
    :daily-all [:daily-all (select-keys opts [:day-offset])]
    :sports [:sports]
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

(defn fetch-sports-index-cached
  "Fetch the sports index with a longer cache."
  ([] (fetch-sports-index-cached {}))
  ([cfg]
   (let [now-ms (System/currentTimeMillis)
         ttl-ms (or (:sports-cache-ttl-ms cfg) default-sports-cache-ttl-ms)
         key (cache-key :sports {})
         cached (cache-get key ttl-ms now-ms)]
     (if cached
       (assoc (:value cached) :cached? true :fetched-at-ms (:fetched-at-ms cached))
       (let [res (fetch-sports-index cfg)]
         (if (:error res)
           res
           (let [value (assoc res :cached? false :fetched-at-ms now-ms)]
             (cache-put! key now-ms value)
             value)))))))

(defn fetch-daily-odds-all-sports-cached
  "Fetch daily odds across all sports with caching."
  ([] (fetch-daily-odds-all-sports-cached {} {}))
  ([cfg] (fetch-daily-odds-all-sports-cached cfg {}))
  ([cfg {:keys [refresh?] :as opts}]
   (let [now-ms (System/currentTimeMillis)
         ttl-ms (or (:cache-ttl-ms cfg) default-cache-ttl-ms)
         key (cache-key :daily-all opts)
         cached (when-not refresh? (cache-get key ttl-ms now-ms))]
     (if cached
       (assoc (:value cached) :cached? true :fetched-at-ms (:fetched-at-ms cached))
       (let [sports-res (fetch-sports-index-cached cfg)
             sports (get-in sports-res [:data :sports])
             results (mapv (fn [{:keys [sport-path sport-title]}]
                             (fetch-daily-odds-cached cfg (assoc opts
                                                                :sport-path sport-path
                                                                :sport-title sport-title)))
                           sports)
             successes (filter #(= :ok (:status %)) results)
             matches (mapcat #(get-in % [:data :matches]) successes)
             groups (mapcat #(get-in % [:data :groups]) successes)
             value {:status :ok
                    :data {:day-offset (:day-offset opts)
                           :matches (vec matches)
                           :groups (vec groups)
                           :sports (vec sports)}}]
         (cache-put! key now-ms (assoc value :cached? false :fetched-at-ms now-ms))
         (assoc value :cached? false :fetched-at-ms now-ms))))))

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
