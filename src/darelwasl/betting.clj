(ns darelwasl.betting
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.db :as db]
            [darelwasl.entity :as entity]
            [darelwasl.rezultati-scraper :as rezultati]
            [darelwasl.validation :as v])
  (:import (java.time LocalDate LocalTime ZoneId ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.util Date UUID)))

(def ^:private iso-formatter DateTimeFormatter/ISO_INSTANT)
(def ^:private time-formatter (DateTimeFormatter/ofPattern "H:mm"))
(def ^:private kickoff-formatter (DateTimeFormatter/ofPattern "dd.MM.yyyy H:mm"))

(def ^:private market-1x2 "1x2")
(def ^:private draw-label "Draw")
(def ^:private default-bookmaker-key "bet365")
(def ^:private default-reference-books ["pinnacle" "betfair" "sbobet" "bet365" "unibet"])
(def ^:private default-fallback-books ["bet365" "unibet"])
(def ^:private default-execution-book "supersport.hr")

(def ^:private param-value v/param-value)
(def ^:private normalize-string v/normalize-string)

(defn- resolve-event-id
  [db value]
  (entity/resolve-id db :betting.event/id value "event id"))

(defn- resolve-bet-id
  [db value]
  (entity/resolve-id db :betting.bet/id value "bet id"))

(defn- error
  [status message & [details]]
  {:error {:status status
           :message message
           :details details}})

(defn- ensure-conn
  [conn]
  (when-not conn
    (error 500 "Database not ready")))

(defn- now-inst
  []
  (Date.))

(defn- format-inst
  [^Date inst]
  (when inst
    (.format iso-formatter (.toInstant inst))))

(defn- parse-double
  [value label]
  (let [raw (if (sequential? value) (first value) value)]
    (cond
      (nil? raw) {:value nil}
      (number? raw) {:value (double raw)}
      (string? raw) (let [s (-> raw str/trim (str/replace #"," "."))]
                      (try
                        {:value (Double/parseDouble s)}
                        (catch Exception _
                          {:error (str "Invalid " label)})))
      :else {:error (str "Invalid " label)})))

(defn- parse-positive
  [value label]
  (let [{:keys [value error]} (parse-double value label)]
    (cond
      error {:error error}
      (or (nil? value) (not (pos? value))) {:error (str label " must be positive")}
      :else {:value value})))

(defn- parse-optional-positive
  [value label]
  (let [{:keys [value error]} (parse-double value label)]
    (cond
      error {:error error}
      (nil? value) {:value nil}
      (pos? value) {:value value}
      :else {:error (str label " must be positive")})))

(defn- raw-implied-prob
  [odds]
  (when (and (number? odds) (pos? odds))
    (/ 1.0 odds)))

(defn- no-vig-probs
  [{:keys [home draw away]}]
  (let [q-home (raw-implied-prob home)
        q-draw (raw-implied-prob draw)
        q-away (raw-implied-prob away)
        sum (when (and q-home q-draw q-away)
              (+ q-home q-draw q-away))]
    (when (and sum (pos? sum))
      {:home (/ q-home sum)
       :draw (/ q-draw sum)
       :away (/ q-away sum)})))

(defn- prob->odds
  [prob]
  (when (and (number? prob) (pos? prob))
    (/ 1.0 prob)))

(defn- median
  [values]
  (let [sorted (sort (filter number? values))
        n (count sorted)]
    (when (pos? n)
      (if (odd? n)
        (nth sorted (quot n 2))
        (/ (+ (nth sorted (dec (quot n 2)))
              (nth sorted (quot n 2)))
           2.0)))))

(defn- normalize-bookmaker-key
  [value]
  (some-> value str str/trim str/lower-case))

(defn- betting-config
  [cfg]
  (or (:betting cfg) cfg))

(defn- rezultati-config
  [cfg]
  (or (:rezultati cfg) cfg))

(defn- reference-books
  [cfg]
  (->> (or (:reference-books (betting-config cfg))
           default-reference-books)
       (map normalize-bookmaker-key)
       (remove str/blank?)
       vec))

(defn- fallback-books
  [cfg]
  (->> (or (:fallback-books (betting-config cfg))
           default-fallback-books)
       (map normalize-bookmaker-key)
       (remove str/blank?)
       vec))

(defn- execution-book
  [cfg]
  (or (some-> (betting-config cfg) :execution-book normalize-bookmaker-key)
      default-execution-book))

(defn- selection-labels
  [event]
  {:home (:betting.event/home-team event)
   :draw draw-label
   :away (:betting.event/away-team event)})

(defn- selection-slot
  [labels selection]
  (some (fn [[slot label]]
          (when (= label selection) slot))
        labels))

(defn- normalize-bookmaker
  [{:keys [key title odds]}]
  (let [normalized (normalize-bookmaker-key (or key title))]
    (when (and normalized odds)
      {:key normalized
       :title (or title key normalized)
       :odds odds})))

(defn- normalize-bookmakers
  [data]
  (let [bookmakers (:bookmakers data)]
    (cond
      (seq bookmakers) (vec (keep normalize-bookmaker bookmakers))
      (map? (:odds data)) (let [bookmaker (or (:bookmaker data) default-bookmaker-key)]
                            (vec (keep normalize-bookmaker
                                       [{:key bookmaker
                                         :title bookmaker
                                         :odds (:odds data)}])))
      :else [])))

(defn- reference-book-keys
  [bookmakers cfg]
  (let [available (set (map :key bookmakers))
        preferred (filter available (reference-books cfg))
        fallback (filter available (fallback-books cfg))
        selected (cond
                   (seq preferred) (vec preferred)
                   (seq fallback) (vec fallback)
                   :else (vec available))
        source (cond
                 (seq preferred) :reference
                 (seq fallback) :fallback
                 :else :all)]
    {:books selected
     :source source}))

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

(defn- match-external-id
  [match-id]
  (str "rezultati:" match-id))

(defn- match-id-from-external
  [external-id]
  (when (and (string? external-id) (str/starts-with? external-id "rezultati:"))
    (subs external-id (count "rezultati:"))))

(defn- parse-commence-time
  [day-offset time-str]
  (let [raw (-> (or time-str "") str/trim (str/replace #"[^0-9:]" ""))]
    (when (str/includes? raw ":")
      (try
        (let [time (LocalTime/parse raw time-formatter)
              date (.plusDays (LocalDate/now) (long (or day-offset 0)))
              zdt (ZonedDateTime/of date time (ZoneId/systemDefault))]
          (Date/from (.toInstant zdt)))
        (catch Exception _ nil)))))

(defn- parse-kickoff
  [kickoff]
  (let [raw (-> (or kickoff "") str/trim)]
    (when (seq raw)
      (try
        (let [ldt (java.time.LocalDateTime/parse raw kickoff-formatter)
              zdt (ZonedDateTime/of ldt (ZoneId/systemDefault))]
          (Date/from (.toInstant zdt)))
        (catch Exception _ nil)))))

(defn- lookup-event-id
  [db event-id]
  (when event-id
    (ffirst (d/q '[:find ?e
                   :in $ ?id
                   :where [?e :betting.event/id ?id]]
                 db event-id))))

(defn- event-by-id
  [db event-id]
  (when-let [eid (lookup-event-id db event-id)]
    (d/pull db [:betting.event/id
                :betting.event/external-id
                :betting.event/home-team
                :betting.event/away-team
                :betting.event/commence-time
                :betting.event/status
                :betting.event/last-updated]
            eid)))

(defn- existing-event-ids
  [db external-ids]
  (if (seq external-ids)
    (->> (d/q '[:find ?ext ?id
                :in $ [?ext ...]
                :where [?e :betting.event/external-id ?ext]
                       [?e :betting.event/id ?id]]
              db external-ids)
         (into {}))
    {}))

(defn- upsert-events!
  [conn {:keys [day-offset]} matches]
  (let [db (d/db conn)
        external-ids (mapv (comp match-external-id :match-id) matches)
        existing (existing-event-ids db external-ids)
        now (now-inst)
        tx-data (mapv (fn [{:keys [match-id home away time status sport-key sport-title sport-path]}]
                        (let [external-id (match-external-id match-id)
                              existing-id (get existing external-id)
                              event-id (or existing-id (UUID/randomUUID))
                              commence (or (parse-commence-time day-offset time) now)
                              key (or sport-key (sport-key-from-path sport-path))
                              title (or sport-title (sport-title-from-path sport-path))]
                          (entity/with-ref db
                                           {:betting.event/id event-id
                                            :entity/type :entity.type/betting-event
                                            :betting.event/external-id external-id
                                            :betting.event/sport-key key
                                            :betting.event/sport-title title
                                            :betting.event/commence-time commence
                                            :betting.event/home-team home
                                            :betting.event/away-team away
                                            :betting.event/status (or status :scheduled)
                                            :betting.event/last-updated now})))
                      matches)]
    (when (seq tx-data)
      (db/transact! conn {:tx-data tx-data}))
    (let [updated (existing-event-ids (d/db conn) external-ids)]
      (mapv (fn [match]
              (let [external-id (match-external-id (:match-id match))]
                (assoc match :event-id (get updated external-id))))
            matches))))

(defn- group-by-sport
  [groups]
  (->> groups
       (group-by (fn [group]
                   [(:sport-key group) (:sport-title group) (:sport-path group)]))
       (mapv (fn [[[sport-key sport-title sport-path] items]]
               {:sport-key sport-key
                :sport-title sport-title
                :sport-path sport-path
                :leagues (mapv (fn [group]
                                 {:league (:league group)
                                  :matches (vec (:matches group))})
                               items)}))
       (sort-by (fn [group] (str (:sport-title group) (:sport-key group))))))

(defn- lookup-bookmaker-id
  [db key]
  (when (and (string? key) (not (str/blank? key)))
    (ffirst (d/q '[:find ?e
                   :in $ ?key
                   :where [?e :betting.bookmaker/key ?key]]
                 db key))))

(defn- ensure-bookmaker!
  [conn key title]
  (let [db (d/db conn)
        existing (lookup-bookmaker-id db key)]
    (if existing
      (d/pull db [:betting.bookmaker/id :betting.bookmaker/key :betting.bookmaker/title] existing)
      (let [bookmaker-id (UUID/randomUUID)
            tx (entity/with-ref db
                                {:betting.bookmaker/id bookmaker-id
                                 :entity/type :entity.type/betting-bookmaker
                                 :betting.bookmaker/key key
                                 :betting.bookmaker/title title})]
        (db/transact! conn {:tx-data [tx]})
        (assoc tx :betting.bookmaker/id bookmaker-id)))))

(defn- build-quote-tx
  [db event-id bookmaker-id selection odds prob close? captured-at]
  (let [quote-id (UUID/randomUUID)
        quote (entity/with-ref db
                               {:betting.quote/id quote-id
                                :entity/type :entity.type/betting-quote
                                :betting.quote/event [:betting.event/id event-id]
                                :betting.quote/bookmaker [:betting.bookmaker/id bookmaker-id]
                                :betting.quote/market-key market-1x2
                                :betting.quote/selection selection
                                :betting.quote/odds-decimal odds
                                :betting.quote/implied-prob prob
                                :betting.quote/close? close?
                                :betting.quote/captured-at captured-at})
        fact (entity/with-ref db
                              {:betting.fact/id (UUID/randomUUID)
                               :entity/type :entity.type/betting-fact
                               :betting.fact/type (if close? :close-capture :quote-capture)
                               :betting.fact/event [:betting.event/id event-id]
                               :betting.fact/quote [:betting.quote/id quote-id]
                               :betting.fact/created-at captured-at})]
    {:quote quote
     :fact fact}))

(defn- quote-selections
  [{:keys [home away odds]}]
  (let [odds-home (:home odds)
        odds-draw (:draw odds)
        odds-away (:away odds)
        probs (or (no-vig-probs odds)
                  {:home (raw-implied-prob odds-home)
                   :draw (raw-implied-prob odds-draw)
                   :away (raw-implied-prob odds-away)})]
    [{:selection home :odds odds-home :prob (:home probs)}
     {:selection draw-label :odds odds-draw :prob (:draw probs)}
     {:selection away :odds odds-away :prob (:away probs)}]))

(defn- latest-quotes
  [db event-id market close? labels]
  (when (and event-id market)
    (let [rows (d/q '[:find ?bk-key ?bk-title ?sel ?odds ?prob ?ts
                      :in $ ?eid ?market ?close?
                      :where [?e :betting.event/id ?eid]
                             [?q :betting.quote/event ?e]
                             [?q :betting.quote/market-key ?market]
                             [?q :betting.quote/selection ?sel]
                             [?q :betting.quote/odds-decimal ?odds]
                             [?q :betting.quote/implied-prob ?prob]
                             [?q :betting.quote/captured-at ?ts]
                             [?q :betting.quote/close? ?close?]
                             [?q :betting.quote/bookmaker ?bk]
                             [?bk :betting.bookmaker/key ?bk-key]
                             [?bk :betting.bookmaker/title ?bk-title]]
                    db event-id market close?)]
      (->> rows
           (keep (fn [[bk-key bk-title sel odds prob ts]]
                   (when-let [slot (selection-slot labels sel)]
                     {:bookmaker-key bk-key
                      :bookmaker-title bk-title
                      :selection sel
                      :slot slot
                      :odds odds
                      :prob prob
                      :captured-at ts})))
           (reduce (fn [acc row]
                     (let [k [(:bookmaker-key row) (:slot row)]
                           prev (get acc k)
                           newer? (or (nil? prev)
                                      (.after ^Date (:captured-at row)
                                              ^Date (:captured-at prev)))]
                       (if newer?
                         (assoc acc k row)
                         acc)))
                   {})
           vals))))

(defn- bookmakers-summary
  [quotes]
  (->> quotes
       (group-by :bookmaker-key)
       (map (fn [[bk-key rows]]
              (let [base {:home nil :draw nil :away nil}
                    odds (reduce (fn [acc row]
                                   (assoc acc (:slot row) (:odds row)))
                                 base rows)
                    probs (reduce (fn [acc row]
                                    (assoc acc (:slot row) (:prob row)))
                                  base rows)
                    captured-at (last (sort (keep :captured-at rows)))]
                {:key bk-key
                 :title (:bookmaker-title (first rows))
                 :odds odds
                 :probs probs
                 :captured-at captured-at})))
       (sort-by :title)
       vec))

(defn- best-odds-summary
  [bookmakers]
  (into {}
        (map (fn [slot]
               (let [best (->> bookmakers
                               (keep (fn [bk]
                                       (when-let [odds (get-in bk [:odds slot])]
                                         {:bookmaker (:key bk)
                                          :title (:title bk)
                                          :odds odds})))
                               (sort-by :odds)
                               last)]
                 [slot best])))
        [:home :draw :away]))

(defn- execution-summary
  [bookmakers exec-key]
  (when exec-key
    (when-let [bk (some #(when (= (:key %) exec-key) %) bookmakers)]
      {:bookmaker exec-key
       :title (:title bk)
       :odds (:odds bk)})))

(defn- odds-summary
  [db cfg event market close?]
  (let [labels (selection-labels event)
        quotes (latest-quotes db (:betting.event/id event) market close? labels)
        bookmakers (bookmakers-summary quotes)
        {:keys [books source]} (reference-book-keys bookmakers cfg)
        books-set (set books)
        reference-probs (into {}
                              (map (fn [slot]
                                     (let [vals (->> bookmakers
                                                     (filter #(contains? books-set (:key %)))
                                                     (map #(get-in % [:probs slot]))
                                                     (filter number?))]
                                       [slot (median vals)])))
                              [:home :draw :away])
        captured-at (last (sort (keep :captured-at bookmakers)))]
    (when (seq bookmakers)
      {:market market
       :captured-at captured-at
       :reference {:books books
                   :source source
                   :probs reference-probs}
       :best-odds (best-odds-summary bookmakers)
       :execution (execution-summary bookmakers (execution-book cfg))
       :bookmakers bookmakers})))

(defn- present-odds-summary
  [summary]
  (when summary
    (-> summary
        (update :captured-at format-inst)
        (update :bookmakers (fn [books]
                              (mapv (fn [bk]
                                      (update bk :captured-at format-inst))
                                    books))))))

(defn- clv-value
  [bet-prob close-prob]
  (when (and (number? bet-prob) (pos? bet-prob) (number? close-prob))
    (- close-prob bet-prob)))

(defn- clv-status
  [clv]
  (cond
    (nil? clv) :pending
    (> clv 0.0001) :ahead
    (< clv -0.0001) :behind
    :else :at-close))

(defn- present-event
  [event]
  (when event
    (-> event
        (update :betting.event/commence-time format-inst)
        (update :betting.event/last-updated format-inst))))

(defn- close-details
  [summary slot]
  (let [prob (get-in summary [:reference :probs slot])]
    (when (and summary (number? prob))
      {:betting.quote/implied-prob prob
       :betting.quote/odds-decimal (prob->odds prob)
       :betting.quote/captured-at (:captured-at summary)})))

(defn- present-bet
  [bet close]
  (let [bet-prob (:betting.bet/implied-prob bet)
        close-prob (:betting.quote/implied-prob close)
        clv (clv-value bet-prob close-prob)]
    (-> bet
        (update :betting.bet/placed-at format-inst)
        (update :betting.bet/settled-at format-inst)
        (update :betting.bet/event #(when % (select-keys % [:betting.event/id
                                                           :betting.event/home-team
                                                           :betting.event/away-team
                                                           :betting.event/commence-time])))
        (update :betting.bet/event #(when %
                                      (update % :betting.event/commence-time format-inst)))
        (update :betting.bet/bookmaker #(when % (select-keys % [:betting.bookmaker/id
                                                               :betting.bookmaker/key
                                                               :betting.bookmaker/title])))
        (assoc :betting.bet/close-implied-prob close-prob
               :betting.bet/close-odds (some-> close :betting.quote/odds-decimal)
               :betting.bet/close-captured-at (some-> close :betting.quote/captured-at format-inst)
               :betting.bet/clv clv
               :betting.bet/clv-status (clv-status clv)))))

(defn list-events
  [conn cfg {:keys [day-offset sport-path refresh?] :or {day-offset 0 sport-path ""}}]
  (if-let [err (ensure-conn conn)]
    err
    (try
      (let [cfg* (rezultati-config cfg)
            use-all? (str/blank? sport-path)
            res (if use-all?
                  (rezultati/fetch-daily-odds-all-sports-cached cfg* {:day-offset day-offset
                                                                      :refresh? refresh?})
                  (rezultati/fetch-daily-odds-cached cfg* {:day-offset day-offset
                                                          :sport-path sport-path
                                                          :refresh? refresh?}))]
        (if-let [err (:error res)]
          (error (or (:status res) 502) err (dissoc res :error))
          (let [data (:data res)
                matches (:matches data)
                groups (:groups data)
                matches' (upsert-events! conn {:day-offset day-offset} matches)
                match-map (into {} (map (fn [m] [(:match-id m) m]) matches'))
                groups' (mapv (fn [group]
                                (update group :matches
                                        (fn [ms]
                                          (mapv (fn [m] (get match-map (:match-id m) m)) ms))))
                              groups)
                sport-groups (group-by-sport groups')]
            {:day-offset day-offset
             :sport-path (if use-all? "all" sport-path)
             :cached? (:cached? res)
             :fetched-at-ms (:fetched-at-ms res)
             :matches matches'
             :groups sport-groups
             :sports (:sports data)})))
      (catch clojure.lang.ExceptionInfo e
        (log/error e "Failed to list betting events" (ex-data e))
        (error 500 "Betting events failed" {:exception (ex-message e)
                                            :data (ex-data e)}))
      (catch Exception e
        (log/error e "Failed to list betting events")
        (error 500 "Betting events failed" {:exception (ex-message e)})))))

(defn fetch-event-odds!
  [conn cfg {:keys [event-id refresh? sport-path]}]
  (if-let [err (ensure-conn conn)]
    err
    (try
      (let [db (d/db conn)
            {:keys [value error]} (resolve-event-id db event-id)]
        (if error
          (error 400 error)
          (let [event (event-by-id db value)
                external-id (:betting.event/external-id event)
                match-id (match-id-from-external external-id)]
            (cond
              (nil? event) (error 404 "Event not found")
              (nil? match-id) (error 400 "Event is missing Rezultati match id")
              :else
              (let [res (rezultati/fetch-match-odds-cached (rezultati-config cfg) match-id {:sport-path sport-path
                                                                         :refresh? refresh?})]
                (if-let [err (:error res)]
                  (error (or (:status res) 502) err (dissoc res :error))
                  (let [{:keys [home away kickoff] :as data} (:data res)
                        now (now-inst)
                        kickoff-inst (or (parse-kickoff kickoff) (:betting.event/commence-time event))
                        home-team (or home (:betting.event/home-team event))
                        away-team (or away (:betting.event/away-team event))
                        event-update {:db/id [:betting.event/id value]
                                      :betting.event/home-team home-team
                                      :betting.event/away-team away-team
                                      :betting.event/commence-time (or kickoff-inst now)
                                      :betting.event/last-updated now}
                        bookmakers (normalize-bookmakers data)
                        quotes (mapcat (fn [{:keys [key title odds]}]
                                         (when-let [bookmaker (and key (ensure-bookmaker! conn key title))]
                                           (let [selections (quote-selections {:home home-team
                                                                               :away away-team
                                                                               :odds odds})]
                                             (keep (fn [{:keys [selection odds prob]}]
                                                     (when (and selection (number? odds) (pos? odds)
                                                                (number? prob) (pos? prob))
                                                       (build-quote-tx db value
                                                                       (:betting.bookmaker/id bookmaker)
                                                                       selection
                                                                       odds
                                                                       prob
                                                                       false
                                                                       now)))
                                                   selections))))
                                       bookmakers)
                        quote-txs (mapv :quote quotes)
                        fact-txs (mapv :fact quotes)]
                    (db/transact! conn {:tx-data (into [event-update] quote-txs)})
                    (when (seq fact-txs)
                      (db/transact! conn {:tx-data fact-txs}))
                    (let [db' (d/db conn)
                          event' (event-by-id db' value)
                          summary (odds-summary db' cfg event' market-1x2 false)]
                      {:event (present-event event')
                       :odds (present-odds-summary summary)
                       :captured-at (format-inst now)}))))))))
      (catch clojure.lang.ExceptionInfo e
        (log/error e "Failed to fetch betting odds" (ex-data e))
        (error 500 "Betting odds failed" {:exception (ex-message e)
                                          :data (ex-data e)}))
      (catch Exception e
        (log/error e "Failed to fetch betting odds")
        (error 500 "Betting odds failed" {:exception (ex-message e)})))))
(defn capture-close!
  [conn cfg {:keys [event-id refresh? sport-path]}]
  (if-let [err (ensure-conn conn)]
    err
    (try
      (let [db (d/db conn)
            {:keys [value error]} (resolve-event-id db event-id)]
        (if error
          (error 400 error)
          (let [event (event-by-id db value)
                external-id (:betting.event/external-id event)
                match-id (match-id-from-external external-id)]
            (cond
              (nil? event) (error 404 "Event not found")
              (nil? match-id) (error 400 "Event is missing Rezultati match id")
              :else
              (let [res (rezultati/fetch-match-odds-cached (rezultati-config cfg) match-id {:sport-path sport-path
                                                                         :refresh? (if (nil? refresh?) true refresh?)})]
                (if-let [err (:error res)]
                  (error (or (:status res) 502) err (dissoc res :error))
                  (let [{:keys [home away kickoff] :as data} (:data res)
                        now (now-inst)
                        kickoff-inst (or (parse-kickoff kickoff) (:betting.event/commence-time event))
                        home-team (or home (:betting.event/home-team event))
                        away-team (or away (:betting.event/away-team event))
                        event-update {:db/id [:betting.event/id value]
                                      :betting.event/home-team home-team
                                      :betting.event/away-team away-team
                                      :betting.event/commence-time (or kickoff-inst now)
                                      :betting.event/last-updated now}
                        bookmakers (normalize-bookmakers data)
                        quotes (mapcat (fn [{:keys [key title odds]}]
                                         (when-let [bookmaker (and key (ensure-bookmaker! conn key title))]
                                           (let [selections (quote-selections {:home home-team
                                                                               :away away-team
                                                                               :odds odds})]
                                             (keep (fn [{:keys [selection odds prob]}]
                                                     (when (and selection (number? odds) (pos? odds)
                                                                (number? prob) (pos? prob))
                                                       (build-quote-tx db value
                                                                       (:betting.bookmaker/id bookmaker)
                                                                       selection
                                                                       odds
                                                                       prob
                                                                       true
                                                                       now)))
                                                   selections))))
                                       bookmakers)
                        quote-txs (mapv :quote quotes)
                        fact-txs (mapv :fact quotes)]
                    (db/transact! conn {:tx-data (into [event-update] quote-txs)})
                    (when (seq fact-txs)
                      (db/transact! conn {:tx-data fact-txs}))
                    (let [db' (d/db conn)
                          event' (event-by-id db' value)
                          summary (odds-summary db' cfg event' market-1x2 true)]
                      {:event (present-event event')
                       :odds (present-odds-summary summary)
                       :close-captured-at (format-inst now)}))))))))
      (catch clojure.lang.ExceptionInfo e
        (log/error e "Failed to capture betting close" (ex-data e))
        (error 500 "Betting close failed" {:exception (ex-message e)
                                           :data (ex-data e)}))
      (catch Exception e
        (log/error e "Failed to capture betting close")
        (error 500 "Betting close failed" {:exception (ex-message e)})))))

(defn log-bet!
  [conn cfg {:keys [event-id market-key selection odds bookmaker-key]}]
  (if-let [err (ensure-conn conn)]
    err
    (let [db (d/db conn)
          {event-id* :value event-err :error} (resolve-event-id db event-id)
          {selection* :value sel-err :error} (normalize-string selection "selection" {:required true})
          {odds* :value odds-err :error} (parse-optional-positive odds "odds")]
      (cond
        event-err (error 400 event-err)
        sel-err (error 400 sel-err)
        odds-err (error 400 odds-err)
        :else
        (let [event (event-by-id db event-id*)
              now (now-inst)
              market (or market-key market-1x2)]
          (if-not event
            (error 404 "Event not found")
            (let [labels (selection-labels event)
                  slot (selection-slot labels selection*)
                  summary (odds-summary db cfg event market false)
                  summary (if (and slot (nil? (get-in summary [:reference :probs slot])))
                            (let [res (fetch-event-odds! conn cfg {:event-id event-id*
                                                                   :sport-path ""
                                                                   :refresh? true})]
                              (if (:error res)
                                summary
                                (odds-summary (d/db conn) cfg (event-by-id (d/db conn) event-id*) market false)))
                            summary)
                  entry-prob (when slot (get-in summary [:reference :probs slot]))
                  execution (or odds*
                                (get-in summary [:execution :odds slot]))
                  exec-key (normalize-bookmaker-key (or bookmaker-key (execution-book cfg)))
                  bookmaker (when exec-key
                              (ensure-bookmaker! conn exec-key exec-key))
                  bet-id (UUID/randomUUID)
                  base (cond-> {:betting.bet/id bet-id
                                :entity/type :entity.type/betting-bet
                                :betting.bet/event [:betting.event/id event-id*]
                                :betting.bet/market-key market
                                :betting.bet/selection selection*
                                :betting.bet/implied-prob entry-prob
                                :betting.bet/placed-at now
                                :betting.bet/status :pending}
                         (and execution (number? execution) (pos? execution))
                         (assoc :betting.bet/odds-decimal execution)
                         bookmaker
                         (assoc :betting.bet/bookmaker [:betting.bookmaker/id (:betting.bookmaker/id bookmaker)]))
                  tx (entity/with-ref db base)
                  fact (entity/with-ref db
                                        {:betting.fact/id (UUID/randomUUID)
                                         :entity/type :entity.type/betting-fact
                                         :betting.fact/type :bet-log
                                         :betting.fact/event [:betting.event/id event-id*]
                                         :betting.fact/bet [:betting.bet/id bet-id]
                                         :betting.fact/created-at now})]
              (cond
                (nil? slot) (error 400 "Selection not recognized for this match")
                (nil? entry-prob) (error 409 "Reference odds unavailable; refresh odds first")
                :else
                (do
                  (db/transact! conn {:tx-data [tx]})
                  (db/transact! conn {:tx-data [fact]})
                  (let [bet (d/pull (d/db conn)
                                    [:betting.bet/id
                                     {:betting.bet/event [:betting.event/id
                                                          :betting.event/home-team
                                                          :betting.event/away-team
                                                          :betting.event/commence-time]}
                                     {:betting.bet/bookmaker [:betting.bookmaker/id
                                                              :betting.bookmaker/key
                                                              :betting.bookmaker/title]}
                                     :betting.bet/market-key
                                     :betting.bet/selection
                                     :betting.bet/odds-decimal
                                     :betting.bet/implied-prob
                                     :betting.bet/placed-at
                                     :betting.bet/status]
                                    [:betting.bet/id bet-id])
                        close-summary (odds-summary (d/db conn) cfg event market true)
                        close (close-details close-summary slot)]
                    {:bet (present-bet bet close)}))))))))))

(defn list-bets
  [conn cfg {:keys [event-id]}]
  (if-let [err (ensure-conn conn)]
    err
    (let [db (d/db conn)
          {event-id* :value event-err :error} (resolve-event-id db event-id)]
      (if event-err
        (error 400 event-err)
        (let [bet-eids (if event-id*
                         (map first
                              (d/q '[:find ?b
                                     :in $ ?eid
                                     :where [?e :betting.event/id ?eid]
                                            [?b :betting.bet/event ?e]]
                                   db event-id*))
                         (map first
                              (d/q '[:find ?b
                                     :where [?b :betting.bet/id _]]
                                   db)))
              bets (mapv #(d/pull db [:betting.bet/id
                                      {:betting.bet/event [:betting.event/id
                                                           :betting.event/home-team
                                                           :betting.event/away-team
                                                           :betting.event/commence-time]}
                                      {:betting.bet/bookmaker [:betting.bookmaker/id
                                                               :betting.bookmaker/key
                                                               :betting.bookmaker/title]}
                                      :betting.bet/market-key
                                      :betting.bet/selection
                                      :betting.bet/odds-decimal
                                      :betting.bet/implied-prob
                                      :betting.bet/placed-at
                                      :betting.bet/status
                                      :betting.bet/settled-at]
                                     %)
                         bet-eids)
              close-cache (atom {})
              close-summary (fn [event market]
                              (let [key [(:betting.event/id event) market]]
                                (if (contains? @close-cache key)
                                  (get @close-cache key)
                                  (let [summary (odds-summary db cfg event market true)]
                                    (swap! close-cache assoc key summary)
                                    summary))))
              enriched (mapv (fn [bet]
                               (let [event (:betting.bet/event bet)
                                     labels (selection-labels event)
                                     slot (selection-slot labels (:betting.bet/selection bet))
                                     summary (when event
                                               (close-summary event (:betting.bet/market-key bet)))
                                     close (close-details summary slot)]
                                 (present-bet bet close)))
                             bets)
              closed (filter #(some? (:betting.bet/clv %)) enriched)
              closed-count (count closed)
              total (count enriched)
              avg (when (pos? closed-count)
                    (/ (reduce + 0 (map :betting.bet/clv closed)) closed-count))
              ahead (count (filter #(and (:betting.bet/clv %) (pos? (:betting.bet/clv %))) closed))
              coverage (when (pos? total) (/ closed-count total))
              ahead-pct (when (pos? closed-count) (/ ahead closed-count))]
          {:bets enriched
           :scoreboard {:average-clv avg
                        :ahead-pct ahead-pct
                        :close-coverage coverage
                        :total-bets total
                        :closed-bets closed-count}})))))

(defn settle-bet!
  [conn cfg {:keys [bet-id status]}]
  (if-let [err (ensure-conn conn)]
    err
    (let [db (d/db conn)
          {bet-id* :value bet-err :error} (resolve-bet-id db bet-id)
          status-kw (cond
                      (keyword? status) status
                      (string? status) (keyword (str/replace status #"^:" ""))
                      :else nil)]
      (cond
        bet-err (error 400 bet-err)
        (nil? status-kw) (error 400 "Invalid status")
        :else
        (let [eid (ffirst (d/q '[:find ?b
                                 :in $ ?id
                                 :where [?b :betting.bet/id ?id]]
                               db bet-id*))
              now (now-inst)]
          (if-not eid
            (error 404 "Bet not found")
            (do
              (let [fact (entity/with-ref db
                                           {:betting.fact/id (UUID/randomUUID)
                                            :entity/type :entity.type/betting-fact
                                            :betting.fact/type :settle
                                            :betting.fact/bet [:betting.bet/id bet-id*]
                                            :betting.fact/created-at now})]
                (db/transact! conn {:tx-data [{:db/id eid
                                             :betting.bet/status status-kw
                                             :betting.bet/settled-at now}
                                            fact]}))
              (let [bet (d/pull (d/db conn)
                                [:betting.bet/id
                                 {:betting.bet/event [:betting.event/id
                                                      :betting.event/home-team
                                                      :betting.event/away-team
                                                      :betting.event/commence-time]}
                                 {:betting.bet/bookmaker [:betting.bookmaker/id
                                                          :betting.bookmaker/key
                                                          :betting.bookmaker/title]}
                                 :betting.bet/market-key
                                 :betting.bet/selection
                                 :betting.bet/odds-decimal
                                 :betting.bet/implied-prob
                                 :betting.bet/placed-at
                                 :betting.bet/status
                                 :betting.bet/settled-at]
                                [:betting.bet/id bet-id*])
                    event (:betting.bet/event bet)
                    labels (selection-labels event)
                    slot (selection-slot labels (:betting.bet/selection bet))
                    summary (when event (odds-summary (d/db conn) cfg event (:betting.bet/market-key bet) true))
                    close (close-details summary slot)]
                {:bet (present-bet bet close)}))))))))
