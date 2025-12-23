(ns darelwasl.features.betting
  (:require [clojure.string :as str]
            [darelwasl.ui.components :as ui]
            [darelwasl.ui.shell :as shell]
            [re-frame.core :as rf]))

(defn- format-odds
  [value]
  (if (number? value)
    (.toFixed value 2)
    "—"))

(defn- format-prob
  [value]
  (if (number? value)
    (str (.toFixed (* 100 value) 1) "%")
    "—"))

(defn- format-clv
  [value]
  (when (number? value)
    (let [pct (* 100 value)
          sign (if (pos? pct) "+" "")]
      (str sign (.toFixed pct 1) "pp"))))

(defn- status-label
  [status]
  (case status
    :live "Live"
    :final "Final"
    :scheduled "Upcoming"
    "Upcoming"))

(defn- clv-label
  [bet]
  (let [clv (:betting.bet/clv bet)]
    (or (format-clv clv) "Awaiting close")))

(defn- clv-class
  [bet]
  (case (:betting.bet/clv-status bet)
    :ahead "clv-ahead"
    :behind "clv-behind"
    :at-close "clv-even"
    "clv-pending"))

(defn- odds-string
  [odds]
  (let [home (format-odds (:home odds))
        draw (format-odds (:draw odds))
        away (format-odds (:away odds))]
    (str home " · " draw " · " away)))

(defn- day-label
  [day]
  (cond
    (= day 0) "Today"
    (= day -1) "Yesterday"
    (= day 1) "Tomorrow"
    (pos? day) (str "+" day " days")
    :else (str day " days")))

(defn- match-meta
  [{:keys [time status score]}]
  (let [status-text (status-label status)
        time-text (some-> time str/trim)
        score-text (when (and score (not= status :scheduled)) score)]
    (cond
      (= status :scheduled)
      (str (or time-text "—") " · " status-text)

      (= status :live)
      (str status-text
           (when (seq time-text) (str " · " time-text))
           (when score-text (str " · " score-text)))

      (= status :final)
      (str status-text (when score-text (str " · " score-text)))

      :else status-text)))

(defn- format-odds-with-book
  [odds book]
  (if (number? odds)
    (str (format-odds odds)
         (when (seq book) (str " · " book)))
    "—"))

(defn- prob-string
  [probs]
  (let [home (format-prob (:home probs))
        draw (format-prob (:draw probs))
        away (format-prob (:away probs))]
    (str home " · " draw " · " away)))

(defn- match-row
  [match selected?]
  (let [{:keys [home away odds]} match
        meta (match-meta match)
        trailing (odds-string odds)]
    [ui/list-row {:title (str home " - " away)
                  :meta meta
                  :trailing trailing
                  :selected? selected?
                  :class "betting-match-row"
                  :on-click #(rf/dispatch [:darelwasl.app/select-betting-match match])}]))

(defn- betting-list
  []
  (let [{:keys [groups status error cached? selected day]} @(rf/subscribe [:darelwasl.app/betting])
        loading? (= status :loading)
        selected-id (:event-id selected)]
    [:div.panel.betting-list-panel
     [:div.section-header
      [:div
       [:h2 "Matches"]
       [:span.meta (if cached? "Cached feed" "Live fetch")]]
      [:div.controls
       [:div.betting-day-nav
        [ui/button {:variant :secondary
                    :disabled loading?
                    :on-click #(rf/dispatch [:darelwasl.app/shift-betting-day -1])}
         "<"]
        [:span.meta (day-label (or day 0))]
        [ui/button {:variant :secondary
                    :disabled loading?
                    :on-click #(rf/dispatch [:darelwasl.app/shift-betting-day 1])}
         ">"]]
       [ui/button {:variant :secondary
                   :disabled loading?
                   :on-click #(rf/dispatch [:darelwasl.app/fetch-betting-events])}
        "Refresh"]]]
     (case status
       :loading [ui/loading-state "Loading odds list..."]
       :error [ui/error-state error #(rf/dispatch [:darelwasl.app/fetch-betting-events])]
       :empty [ui/empty-state "No odds yet" "Try refreshing the feed."]
       [:div.betting-leagues
        (for [{:keys [sport-title sport-key leagues]} groups]
          ^{:key (str "sport-" (or sport-key sport-title))}
          [:div.betting-sport
           [:div.betting-sport__title (or sport-title "Sport")]
           (for [{:keys [league matches]} leagues]
             ^{:key (str "league-" league)}
             [:div.betting-league
              [:div.betting-league__title (or league "League")]
              (for [match matches]
                ^{:key (str "match-" (:match-id match))}
                [match-row match (= (:event-id match) selected-id)])])])])]))

(defn- odds-board
  [match summary captured-at loading?]
  (let [labels {:home (:home match)
                :draw "Draw"
                :away (:away match)}
        reference (get-in summary [:reference :probs])
        ref-books (get-in summary [:reference :books])
        ref-source (get-in summary [:reference :source])
        best (get-in summary [:best-odds])
        execution (:execution summary)
        exec-odds (:odds execution)
        exec-title (or (:title execution) (:bookmaker execution))
        ref-label (when (seq ref-books)
                    (str "Ref: " (str/join ", " ref-books)
                         (case ref-source
                           :fallback " (fallback)"
                           :all " (all books)"
                           "")))
        meta-line (str (if captured-at (str "Updated " captured-at) "No capture yet")
                       (when ref-label (str " · " ref-label)))]
    [:div.betting-odds-board
     [:div.section-header
      [:div
       [:h3 "Reference true %"]
       [:span.meta meta-line]]
      [:div.controls
       [ui/button {:variant :secondary
                   :disabled loading?
                   :on-click #(rf/dispatch [:darelwasl.app/fetch-betting-odds nil true])}
        "Refresh odds"]
       [ui/button {:disabled loading?
                   :on-click #(rf/dispatch [:darelwasl.app/capture-betting-close])}
        "Capture close"]]]
     [:div.betting-odds-grid
      (for [slot [:home :draw :away]
            :let [label (get labels slot)
                  ref (get reference slot)
                  best-entry (get best slot)
                  best-text (format-odds-with-book (:odds best-entry)
                                                  (or (:title best-entry) (:bookmaker best-entry)))
                  exec-text (format-odds-with-book (get exec-odds slot) exec-title)]]
        ^{:key (str "odds-" label)}
        [:div.betting-odds-card
         [:div.betting-odds-label label]
         [:div.betting-odds-value (format-prob ref)]
         [:div.betting-odds-meta (str "Best " best-text)]
         [:div.betting-odds-meta (str "Exec " exec-text)]])]]))

(defn- betting-form
  [match summary form]
  (let [selection (:selection form)
        options [(or (:home match) "Home")
                 "Draw"
                 (or (:away match) "Away")]
        saving? (= (:status form) :loading)
        labels {:home (:home match)
                :draw "Draw"
                :away (:away match)}
        slot (some (fn [[k v]] (when (= selection v) k)) labels)
        ref (get-in summary [:reference :probs slot])
        best-entry (get-in summary [:best-odds slot])
        exec-entry (:execution summary)
        exec-odds (get-in summary [:execution :odds slot])
        exec-title (or (:title exec-entry) (:bookmaker exec-entry))
        best-text (format-odds-with-book (:odds best-entry)
                                         (or (:title best-entry) (:bookmaker best-entry)))
        exec-text (format-odds-with-book exec-odds exec-title)]
    [:div.betting-form
     [:h3 "Log bet"]
     [:div.betting-form__selection
      (for [opt options]
        ^{:key (str "sel-" opt)}
        [ui/button {:variant (when-not (= selection opt) :secondary)
                    :class (when (= selection opt) "active")
                    :disabled saving?
                    :on-click #(rf/dispatch [:darelwasl.app/update-betting-form :selection opt])}
         opt])]
     [:div.betting-form__summary
      [:div.meta (str "Reference now: " (format-prob ref))]
      [:div.meta (str "Best now: " best-text)]
      [:div.meta (str "Execution: " exec-text)]]
     (when-let [err (:error form)]
       [:div.form-error err])
     [ui/button {:on-click #(rf/dispatch [:darelwasl.app/submit-betting-bet])
                 :disabled saving?}
      "Log bet"]]))

(defn- bet-row
  [bet]
  (let [selection (:betting.bet/selection bet)
        entry (format-prob (:betting.bet/implied-prob bet))
        odds (:betting.bet/odds-decimal bet)
        exec-book (or (get-in bet [:betting.bet/bookmaker :betting.bookmaker/title])
                      (get-in bet [:betting.bet/bookmaker :betting.bookmaker/key]))
        exec-text (format-odds-with-book odds exec-book)
        status (:betting.bet/status bet)
        trailing (clv-label bet)]
    [ui/list-row {:title selection
                  :meta (str "Entry " entry " · Exec " exec-text)
                  :description (str "Status: " (name status))
                  :trailing [:span {:class (str "clv-pill " (clv-class bet))} trailing]
                  :class "betting-bet-row"}]))

(defn- betting-scoreboard
  [scoreboard]
  (let [{:keys [average-clv ahead-pct close-coverage total-bets]} scoreboard
        fmt (fn [v] (if (number? v) (format-clv v) "—"))
        fmt-share (fn [v] (if (number? v) (str (.toFixed (* 100 v) 1) "%") "—"))]
    [:div.betting-scoreboard
     [:div.betting-score-card
      [:div.betting-score-label "Average CLV (pp)"]
      [:div.betting-score-value (fmt average-clv)]]
     [:div.betting-score-card
      [:div.betting-score-label "% Ahead of close"]
      [:div.betting-score-value (fmt-share ahead-pct)]]
     [:div.betting-score-card
      [:div.betting-score-label "Close coverage"]
      [:div.betting-score-value (fmt-share close-coverage)]]
     [:div.betting-score-card
      [:div.betting-score-label "Total bets"]
      [:div.betting-score-value (or total-bets 0)]]]))

(defn- bet-log
  [bets-state]
  (let [{:keys [items status error scoreboard]} bets-state]
    [:div.betting-bets
     [:div.section-header
      [:div
       [:h3 "Bet log"]
       [:span.meta "CLV updates once a close snapshot exists."]]]
     [betting-scoreboard (or scoreboard {})]
     (case status
       :loading [ui/loading-state "Loading bets..."]
       :error [ui/error-state error #(rf/dispatch [:darelwasl.app/fetch-betting-bets])]
       :empty [ui/empty-state "No bets yet" "Log a bet to start tracking CLV."]
       [:div.betting-bet-list
        (for [bet items]
          ^{:key (str "bet-" (:betting.bet/id bet))}
          [bet-row bet])])]))

(defn- bookmaker-row
  [book]
  [ui/list-row {:title (:title book)
                :meta (str "True % " (prob-string (:probs book)))
                :trailing (odds-string (:odds book))
                :class "betting-bookmaker-row"}])

(defn- bookmakers-panel
  [summary]
  (let [books (:bookmakers summary)]
    [:div.betting-bookmakers
     [:div.section-header
      [:div
       [:h3 "Bookmakers"]
       [:span.meta "Latest odds and no-vig % per book."]]]
     (if (seq books)
       [:div.betting-bookmaker-list
        (for [book books]
          ^{:key (str "book-" (:key book))}
          [bookmaker-row book])]
       [ui/empty-state "No bookmakers yet" "Refresh odds to load prices."])]))

(defn- betting-detail
  []
  (let [{:keys [selected odds form bets]} @(rf/subscribe [:darelwasl.app/betting])
        summary (:summary odds)
        captured-at (:captured-at odds)
        odds-loading? (= (:status odds) :loading)]
    [:div.panel.betting-detail-panel
     (if-not selected
       [ui/empty-state "Select a match" "Choose a match from the list to view odds and log bets."]
       [:<>
       [:div.betting-header
        [:h2 (str (:home selected) " - " (:away selected))]
        [:div.meta (str (or (:sport-title selected) "Sport")
                        " · "
                        (or (:league selected) "League")
                        " · "
                        (match-meta selected))]]
        [odds-board selected summary captured-at odds-loading?]
        (when summary
          [bookmakers-panel summary])
        [betting-form selected summary form]
        [bet-log bets]])]))

(defn betting-shell
  []
  [shell/app-shell
   [:main.betting-layout
    [betting-list]
    [betting-detail]]])
