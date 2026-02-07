(ns darelwasl.telegram
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.actions :as actions]
            [darelwasl.clients :as clients]
            [darelwasl.db :as db]
            [darelwasl.files :as files]
            [darelwasl.outbox :as outbox]
            [darelwasl.events :as events]
            [darelwasl.users :as users]
            [darelwasl.tasks :as tasks]
            [darelwasl.provenance :as prov])
  (:import (java.time Duration Instant LocalDate ZoneId)
           (java.util UUID Date)))

(def ^:private default-timeout-ms 3000)

(def ^:private allowed-commands
  #{:start :help :tasks :task :stop :new :edit :note :note-edit :docs})

(defn- present-string?
  [v]
  (and (string? v) (not (str/blank? v))))

(def ^:private capture-ttl-ms
  (* 15 60 1000))

(defonce pending-captures
  (atom {}))

(defonce pending-reasons
  (atom {}))

(defonce pending-client-links
  (atom {}))

(defonce pending-next-actions
  (atom {}))

(defonce pending-edits
  (atom {}))

(def ^:private docs-ttl-ms
  (* 60 60 1000))

(defonce docs-sessions
  (atom {}))
(def ^:private max-capture-preview 10)
(def ^:private max-capture-cards 5)
(def ^:private status-emoji
  {:todo "🔵"
   :in-progress "🟡"
   :pending "🔴"
   :done "🟢"})
(def ^:private tasks-list-limit 200)
(declare ensure-conn)
(declare bind-chat-for-user!)
(declare send-document-file!)
(declare inline-button)
(declare send-message!)
(declare edit-message!)
(declare docs-menu-inline-keyboard)
(declare docs-payment-reference-inline-keyboard)

(defn- prune-captures!
  []
  (let [cutoff (- (System/currentTimeMillis) capture-ttl-ms)]
    (swap! pending-captures
           (fn [entries]
             (into {}
                   (filter (fn [[_ v]]
                             (let [ts (:created-at v 0)]
                               (>= ts cutoff))))
                   entries)))))

(defn- prune-pending-reasons!
  []
  (let [cutoff (- (System/currentTimeMillis) capture-ttl-ms)]
    (swap! pending-reasons
           (fn [entries]
             (into {}
                   (filter (fn [[_ v]]
                             (let [ts (:created-at v 0)]
                               (>= ts cutoff))))
                   entries)))))

(defn- prune-pending-client-links!
  []
  (let [cutoff (- (System/currentTimeMillis) capture-ttl-ms)]
    (swap! pending-client-links
           (fn [entries]
             (into {}
                   (filter (fn [[_ v]]
                             (let [ts (:created-at v 0)]
                               (>= ts cutoff))))
                   entries)))))

(defn- prune-pending-next-actions!
  []
  (let [cutoff (- (System/currentTimeMillis) capture-ttl-ms)]
    (swap! pending-next-actions
           (fn [entries]
             (into {}
                   (filter (fn [[_ v]]
                             (let [ts (:created-at v 0)]
                               (>= ts cutoff))))
                   entries)))))

(defn- prune-pending-edits!
  []
  (let [cutoff (- (System/currentTimeMillis) capture-ttl-ms)]
    (swap! pending-edits
           (fn [entries]
             (into {}
                   (filter (fn [[_ v]]
                             (let [ts (:created-at v 0)]
                               (>= ts cutoff))))
                   entries)))))

(defn- capture-key
  [chat-id message-id]
  (str chat-id ":" message-id))
(defn- normalize-task-item
  [item]
  (let [trimmed (-> item str str/trim)
        cleaned (str/replace trimmed #"^(?:[-*•])\s+" "")]
    (when-not (str/blank? cleaned)
      cleaned)))

(defn- parse-task-entry
  [item]
  (let [[title desc] (if (str/includes? item "|")
                       (map str/trim (str/split item #"\|" 2))
                       [item nil])
        title (when-not (str/blank? title) title)
        desc (when-not (str/blank? desc) desc)]
    (when title
      {:title title
       :desc desc})))

(defn- parse-task-entries
  [text]
  (let [raw (str/trim (or text ""))
        items (->> (str/split raw #"\r?\n")
                   (map normalize-task-item)
                   (remove nil?)
                   vec)
        items (if (> (count items) 1)
                items
                (let [semi (->> (str/split raw #"\s*;\s*")
                                (map normalize-task-item)
                                (remove nil?)
                                vec)]
                  (if (> (count semi) 1) semi items)))]
    (->> items
         (map parse-task-entry)
         (remove nil?)
         vec)))

(defn- capture-summary
  [entries]
  (let [titles (map :title entries)
        preview (take max-capture-preview titles)
        remainder (- (count titles) (count preview))]
    (str "Capture multiple tasks:\n"
         (str/join "\n" (map #(str "- " %) preview))
         (when (pos? remainder)
           (str "\n- ...and " remainder " more"))
         "\n\nSave these tasks?")))

(defn- task-body
  [chat-user {:keys [title desc client-id]}]
  (cond-> {:task/title title
           :task/description (or desc (str "Captured via chat: " title))
           :task/status :todo
           :task/priority :medium
           :task/assignee (:user/id chat-user)}
    client-id (assoc :task/client client-id)))

(defn- parse-task-command
  [rest]
  (let [raw (str/trim (or rest ""))
        [id-str body] (str/split raw #"\s+" 2)
        task-id (when (and id-str (not (str/blank? id-str)))
                  (try
                    (UUID/fromString id-str)
                    (catch Exception _ nil)))
        body (some-> body str/trim)]
    {:task-id task-id
     :body body}))
(defn- save-capture!
  [chat-id message-id payload]
  (prune-captures!)
  (swap! pending-captures assoc (capture-key chat-id message-id) (assoc payload :created-at (System/currentTimeMillis))))

(defn- take-capture!
  [chat-id message-id]
  (prune-captures!)
  (let [k (capture-key chat-id message-id)
        value (get @pending-captures k)]
    (swap! pending-captures dissoc k)
    value))

(defn- pending-reason-key
  [chat-id]
  (str chat-id))

(defn- save-pending-reason!
  [chat-id payload]
  (prune-pending-reasons!)
  (swap! pending-reasons assoc (pending-reason-key chat-id) (assoc payload :created-at (System/currentTimeMillis))))

(defn- get-pending-reason!
  [chat-id]
  (prune-pending-reasons!)
  (get @pending-reasons (pending-reason-key chat-id)))

(defn- pending-client-key
  [chat-id]
  (str chat-id))

(defn- save-pending-client!
  [chat-id payload]
  (prune-pending-client-links!)
  (swap! pending-client-links assoc (pending-client-key chat-id) (assoc payload :created-at (System/currentTimeMillis))))

(defn- get-pending-client!
  [chat-id]
  (prune-pending-client-links!)
  (get @pending-client-links (pending-client-key chat-id)))

(defn- take-pending-client!
  [chat-id]
  (prune-pending-client-links!)
  (let [k (pending-client-key chat-id)
        value (get @pending-client-links k)]
    (swap! pending-client-links dissoc k)
    value))

(defn- pending-next-action-key
  [chat-id]
  (str chat-id))

(defn- save-pending-next-action!
  [chat-id payload]
  (prune-pending-next-actions!)
  (swap! pending-next-actions assoc (pending-next-action-key chat-id) (assoc payload :created-at (System/currentTimeMillis))))

(defn- get-pending-next-action!
  [chat-id]
  (prune-pending-next-actions!)
  (get @pending-next-actions (pending-next-action-key chat-id)))

(defn- take-pending-next-action!
  [chat-id]
  (prune-pending-next-actions!)
  (let [k (pending-next-action-key chat-id)
        value (get @pending-next-actions k)]
    (swap! pending-next-actions dissoc k)
    value))

(defn- pending-edit-key
  [chat-id]
  (str chat-id))

(defn- save-pending-edit!
  [chat-id payload]
  (prune-pending-edits!)
  (swap! pending-edits assoc (pending-edit-key chat-id) (assoc payload :created-at (System/currentTimeMillis))))

(defn- get-pending-edit!
  [chat-id]
  (prune-pending-edits!)
  (get @pending-edits (pending-edit-key chat-id)))

(defn- log-telegram-message!
  "Persist a minimal telegram message fact with provenance. Direction is :inbound or :outbound."
  [state {:keys [chat-id from-id text update-id message-id direction]}]
  (when-let [conn (ensure-conn state)]
    (let [prov (prov/provenance {:actor/type :integration
                                 :integration/id :integration/telegram}
                                :adapter/telegram)
          trimmed (when text
                    (let [s (str text)]
                      (if (> (count s) 2000)
                        (subs s 0 2000)
                        s)))
          base {:telegram.message/id (UUID/randomUUID)
                :entity/type :entity.type/telegram-message
                :telegram.message/chat-id (str chat-id)
                :telegram.message/update-id update-id
                :telegram.message/direction direction
                :telegram.message/created-at (Date.)}
          tx (cond-> base
               (some? from-id) (assoc :telegram.message/from-id (long from-id))
               (some? trimmed) (assoc :telegram.message/text trimmed)
               (some? message-id) (assoc :telegram.message/message-id (long message-id)))
          tx (prov/enrich-tx tx prov)]
      (try
        (db/transact! conn {:tx-data [tx]})
        (catch Exception e
          (log/warn e "Failed to log Telegram message" {:chat-id chat-id :direction direction})))
      nil)))

(defn- take-pending-reason!
  [chat-id]
  (prune-pending-reasons!)
  (let [k (pending-reason-key chat-id)
        value (get @pending-reasons k)]
    (swap! pending-reasons dissoc k)
    value))

(defn- take-pending-edit!
  [chat-id]
  (prune-pending-edits!)
  (let [k (pending-edit-key chat-id)
        value (get @pending-edits k)]
    (swap! pending-edits dissoc k)
    value))

(defn- truncate-text
  [s max-len]
  (if (and (string? s) (pos? max-len) (> (count s) max-len))
    (let [trim-len (max 0 (- max-len 3))]
      (str (subs s 0 trim-len) "..."))
    s))

(defn- now-inst
  []
  (Date/from (Instant/now)))

(defn- followup-date
  [option]
  (case option
    :tomorrow (Date/from (.plus (Instant/now) (Duration/ofDays 1)))
    :in-3-days (Date/from (.plus (Instant/now) (Duration/ofDays 3)))
    :next-week (Date/from (.plus (Instant/now) (Duration/ofDays 7)))
    nil))

(defn- parse-followup-date
  [text]
  (let [raw (str/trim (or text ""))]
    (or (try
          (Date/from (Instant/parse raw))
          (catch Exception _ nil))
        (try
          (let [date (LocalDate/parse raw)
                zoned (.atStartOfDay date (ZoneId/systemDefault))]
            (Date/from (.toInstant zoned)))
          (catch Exception _ nil)))))

(defn- yyyy-mm
  [^LocalDate d]
  (format "%04d-%02d" (.getYear d) (.getMonthValue d)))

(defn- yyyy-mm-dd
  [^LocalDate d]
  (format "%04d-%02d-%02d" (.getYear d) (.getMonthValue d) (.getDayOfMonth d)))

(defn- parse-ym
  [s]
  (try
    (LocalDate/parse (str (str/trim (or s "")) "-01"))
    (catch Exception _ nil)))

(defn- local-date->date
  [^LocalDate d]
  (when d
    (let [zoned (.atStartOfDay d (ZoneId/systemDefault))]
      (Date/from (.toInstant zoned)))))

(defn- parse-ymd->date
  [s]
  (try
    (local-date->date (LocalDate/parse (str/trim (or s ""))))
    (catch Exception _ nil)))

(defn- hhmm->local-time
  "Parse a callback time token like \"0930\" into a LocalTime."
  [s]
  (let [raw (str/trim (or s ""))]
    (when (re-matches #"\d{4}" raw)
      (let [h (Integer/parseInt (subs raw 0 2))
            m (Integer/parseInt (subs raw 2 4))]
        (when (and (<= 0 h 23) (<= 0 m 59))
          (java.time.LocalTime/of h m))))))

(defn- local-date-time->date
  [^LocalDate d ^java.time.LocalTime t]
  (when (and d t)
    (let [ldt (.atTime d t)
          zoned (.atZone ldt (ZoneId/systemDefault))]
      (Date/from (.toInstant zoned)))))

(defn- prune-docs-sessions!
  []
  (let [cutoff (- (System/currentTimeMillis) docs-ttl-ms)]
    (swap! docs-sessions
           (fn [entries]
             (into {}
                   (filter (fn [[_ v]]
                             (let [ts (:created-at v 0)]
                               (>= ts cutoff))))
                   entries)))))

(defn- save-docs-session!
  [chat-id session]
  (prune-docs-sessions!)
  (swap! docs-sessions assoc (str chat-id) (assoc session :created-at (System/currentTimeMillis))))

(defn- get-docs-session!
  [chat-id]
  (prune-docs-sessions!)
  (get @docs-sessions (str chat-id)))

(defn- take-docs-session!
  [chat-id]
  (prune-docs-sessions!)
  (let [k (str chat-id)
        value (get @docs-sessions k)]
    (swap! docs-sessions dissoc k)
    value))

(defn- docs-send-file!
  [state chat-id actor file & {:keys [caption]}]
  (let [cfg (get-in state [:config :telegram])
        conn (ensure-conn state)
        storage-dir (get-in state [:config :files :storage-dir])
        file-id (:file/id file)
        workspace (:actor/workspace actor)]
    (cond
      (nil? file-id) {:error "Missing file id"}
      (str/blank? (str storage-dir)) {:error "File storage not configured"}
      :else
      (let [res (files/fetch-file conn file-id workspace)]
        (if-let [err (:error res)]
          {:error (:message err)}
          (let [{:file/keys [storage-path name]} (:file res)
                path (when storage-path (.getPath (io/file storage-dir storage-path)))]
            (if (and path (.exists (io/file path)))
              (send-document-file! cfg {:chat-id (str chat-id)
                                        :file (io/file path)
                                        :filename name
                                        :caption caption})
              {:error "Stored file not found"})))))))

(defn- date-picker-inline-keyboard
  [{:keys [month quicks allow-skip? skip-label extra-rows]}]
  (let [month (or month (LocalDate/now (ZoneId/systemDefault)))
        month-start (-> month (.withDayOfMonth 1))
        offset (dec (.getValue (.getDayOfWeek month-start)))
        days-in-month (.lengthOfMonth month-start)
        blanks (repeat (max 0 offset) nil)
        days (map (fn [day]
                    (let [d (.withDayOfMonth month-start (int day))]
                      {:text (str day)
                       :callback_data (str "dp:day:" (yyyy-mm-dd d))}))
                  (range 1 (inc days-in-month)))
        cells (concat blanks days)
        pad (mod (- 7 (mod (count cells) 7)) 7)
        cells (concat cells (repeat pad nil))
        weeks (partition 7 cells)
        month-label (.format month-start (java.time.format.DateTimeFormatter/ofPattern "MMM yyyy"))
        prev-month (.minusMonths month-start 1)
        next-month (.plusMonths month-start 1)
        nav-row [{:text "‹" :callback_data (str "dp:nav:" (yyyy-mm prev-month))}
                 {:text month-label :callback_data "dp:noop"}
                 {:text "›" :callback_data (str "dp:nav:" (yyyy-mm next-month))}]
        header-row (mapv (fn [label] {:text label :callback_data "dp:noop"})
                         ["Mon" "Tue" "Wed" "Thu" "Fri" "Sat" "Sun"])
        week-rows (mapv (fn [week]
                          (mapv (fn [cell]
                                  (if (nil? cell)
                                    {:text " " :callback_data "dp:noop"}
                                    cell))
                                week))
                        weeks)
        quicks (or quicks [])
        quick-rows (when (seq quicks)
                     (->> quicks
                          (map (fn [{:keys [id label]}]
                                 {:text label :callback_data (str "dp:quick:" (name id))}))
                          (partition-all 2)
                          (mapv vec)))
        skip-row (when allow-skip?
                   [[{:text (or skip-label "Skip") :callback_data "dp:skip"}]])
        rows (vec (concat [nav-row header-row]
                          week-rows
                          (or quick-rows [])
                          (or skip-row [])
                          (or extra-rows [])))]
    {:inline_keyboard rows}))

(defn- time-picker-inline-keyboard
  "Inline keyboard for picking a time-of-day (hour first). Uses callback data:
  - tp:hour:<HH>
  - tp:now
  - tp:skip"
  [{:keys [allow-skip? skip-label extra-rows]}]
  (let [hours (mapv (fn [h]
                      (let [hh (format "%02d" (int h))]
                        (inline-button hh (str "tp:hour:" hh))))
                    (range 0 24))
        hour-rows (mapv vec (partition-all 4 hours))
        util-row (cond-> [(inline-button "Now" "tp:now")]
                   allow-skip? (conj (inline-button (or skip-label "Skip time") "tp:skip")))
        rows (vec (concat hour-rows
                          [util-row]
                          (or extra-rows [])))]
    {:inline_keyboard rows}))

(defn- time-picker-minutes-inline-keyboard
  [{:keys [hour allow-skip? skip-label extra-rows]}]
  (let [hh (format "%02d" (int hour))
        minute-row (mapv (fn [mm]
                           (inline-button mm (str "tp:set:" hh mm)))
                         ["00" "15" "30" "45"])
        nav-row [(inline-button "Back" "tp:back")
                 (inline-button "Now" "tp:now")]
        skip-row (when allow-skip?
                   [[(inline-button (or skip-label "Skip time") "tp:skip")]])
        rows (vec (concat [minute-row nav-row]
                          (or skip-row [])
                          (or extra-rows [])))]
    {:inline_keyboard rows}))

(defn- apply-docs-time-picker-set!
  [state {:keys [chat-id chat-user message-id raw]}]
  (let [cfg (get-in state [:config :telegram])
        session (get-docs-session! chat-id)
        t (hhmm->local-time raw)
        fmt (java.time.format.DateTimeFormatter/ofPattern "HH:mm")]
    (when (and session t)
      (cond
        (= (:stage session) :docs/payment-time)
        (let [ymd (get-in session [:draft :payment/paid-date])
              d (when ymd (try (LocalDate/parse (str ymd)) (catch Exception _ nil)))
              picked (when d (local-date-time->date d t))]
          (when picked
            (save-docs-session! chat-id (-> session
                                            (assoc :stage :docs/payment-reference)
                                            (update :draft dissoc :payment/paid-date)
                                            (assoc-in [:draft :payment/paid-at] picked)))
            (edit-message! cfg {:chat-id chat-id
                                :message-id message-id
                                :text (str "Payment time set: " (.format t fmt))
                                :reply-markup {:inline_keyboard []}})
            (send-message! cfg {:chat-id chat-id
                                :text "Send the payment reference (optional):"
                                :message-key (str "docs-payment-ref-" (System/currentTimeMillis))
                                :reply-markup (docs-payment-reference-inline-keyboard)})))

        (= (:stage session) :docs/inv-due-time)
        (let [ymd (get-in session [:draft :invoice/due-date])
              d (when ymd (try (LocalDate/parse (str ymd)) (catch Exception _ nil)))
              picked (when d (local-date-time->date d t))]
          (when picked
            (let [draft (:draft session)
                  actor (actions/actor-from-telegram chat-user)
                  input (cond-> {:client/id (:client-id session)
                                 :invoice/number (:invoice/number draft)
                                 :invoice/total-amount (:invoice/total-amount draft)
                                 :invoice/status (:invoice/status draft)
                                 :invoice/due-at picked}
                          (nil? (:invoice/status draft)) (dissoc :invoice/status))
                  res (actions/execute! state {:action/id :cap/action/invoice-create
                                               :actor actor
                                               :input input})]
              (save-docs-session! chat-id (assoc session :stage :docs/menu :draft nil))
              (if-let [err (:error res)]
                (send-message! cfg {:chat-id chat-id
                                    :text (str "Unable to add invoice: " (:message err))
                                    :message-key (str "docs-inv-create-error-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)})
                (do
                  (edit-message! cfg {:chat-id chat-id
                                      :message-id message-id
                                      :text (str "Invoice due time set: " (.format t fmt))
                                      :reply-markup {:inline_keyboard []}})
                  (send-message! cfg {:chat-id chat-id
                                      :text "Invoice added."
                                      :message-key (str "docs-inv-added-" (System/currentTimeMillis))
                                      :reply-markup (docs-menu-inline-keyboard)}))))))

        :else nil))))

(defn- latest-pending-reason
  [db task-id]
  (when (and db task-id)
    (let [rows (seq (d/q '[:find ?body ?created
                           :in $ ?tid
                           :where [?t :task/id ?tid]
                                  [?n :note/subject ?t]
                                  [?n :note/type :note.type/pending-reason]
                                  [?n :note/body ?body]
                                  [?n :note/created-at ?created]]
                         db task-id))]
      (some->> rows
               (sort-by second #(compare %2 %1))
               ffirst))))

(defn- pending-reason-for-task
  [db task]
  (when (and (= :pending (:task/status task))
             (:task/id task))
    (latest-pending-reason db (:task/id task))))

(declare inline-button)

(def ^:private list-ttl-ms
  (* 30 60 1000))

(defonce task-list-state
  (atom {}))

(defn- prune-task-lists!
  []
  (let [cutoff (- (System/currentTimeMillis) list-ttl-ms)]
    (swap! task-list-state
           (fn [entries]
             (into {}
                   (filter (fn [[_ v]]
                             (let [ts (:created-at v 0)]
                               (>= ts cutoff))))
                   entries)))))

(defn- task-list-key
  [chat-id message-id]
  (str chat-id ":" message-id))

(defn- save-task-list!
  [chat-id message-id filters]
  (prune-task-lists!)
  (swap! task-list-state assoc (task-list-key chat-id message-id) (assoc filters :created-at (System/currentTimeMillis))))

(defn- get-task-list!
  [chat-id message-id]
  (prune-task-lists!)
  (get @task-list-state (task-list-key chat-id message-id)))

(defn- capture-inline-keyboard
  [message-id]
  {:inline_keyboard
   [[(inline-button "Task" (str "capture:task:" message-id))
     (inline-button "Client" (str "capture:client:" message-id))
     (inline-button "Dismiss" (str "capture:cancel:" message-id))]]})

(defn- link-client-inline-keyboard
  [task-id]
  {:inline_keyboard
   [[(inline-button "Pick client" (str "task:client:pick:" task-id))
     (inline-button "Create client" (str "task:client:create:" task-id))]
    [(inline-button "Skip" (str "task:client:skip:" task-id))]]})

(defn- client-pick-inline-keyboard
  [task-id clients]
  (let [buttons (map (fn [{:client/keys [id name]}]
                       (inline-button (truncate-text (or name "Client") 22)
                                      (str "task:client:set:" task-id ":" id)))
                     clients)
        rows (->> buttons
                  (partition-all 2)
                  (mapv vec))]
    {:inline_keyboard
     (conj rows [(inline-button "Create client" (str "task:client:create:" task-id))
                 (inline-button "Cancel" (str "task:client:cancel:" task-id))])}))

(defn- docs-client-pick-inline-keyboard
  [clients]
  (let [buttons (map (fn [{:client/keys [id name]}]
                       (inline-button (truncate-text (or name "Client") 22)
                                      (str "docs:client:set:" id)))
                     clients)
        rows (->> buttons
                  (partition-all 2)
                  (mapv vec))]
    {:inline_keyboard
     (conj rows [(inline-button "Cancel" "docs:cancel")])}))

(defn- docs-invoice-pick-inline-keyboard
  [invoices on-pick-data & {:keys [cancel-data skip-data]}]
  (let [buttons (map (fn [inv]
                       (let [iid (:invoice/id inv)
                             num (or (:invoice/number inv) (subs (str iid) 0 8))
                             status (some-> (:invoice/status inv) name)
                             label (truncate-text (str num (when status (str " · " status))) 28)]
                         (inline-button label (format on-pick-data iid))))
                     (or invoices []))
        rows (->> buttons
                  (partition-all 2)
                  (mapv vec))
        tail (cond-> []
               skip-data (conj (inline-button "Skip" skip-data))
               cancel-data (conj (inline-button "Cancel" cancel-data)))]
    {:inline_keyboard
     (cond
       (empty? tail) rows
       (empty? rows) [(vec tail)]
       :else (conj rows (vec tail)))}))

(defn- docs-payment-pick-inline-keyboard
  [payments on-pick-data & {:keys [cancel-data]}]
  (let [buttons (map (fn [p]
                       (let [pid (:payment/id p)
                             amt (or (:payment/amount p) "")
                             paid-at (or (:payment/paid-at p) "")
                             label (truncate-text (str amt " " paid-at) 28)]
                         (inline-button label (format on-pick-data pid))))
                     (or payments []))
        rows (->> buttons
                  (partition-all 2)
                  (mapv vec))
        rows (if cancel-data
               (conj rows [(inline-button "Cancel" cancel-data)])
               rows)]
    {:inline_keyboard (vec rows)}))

(defn- docs-menu-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "Company name" "docs:field:company-name")
     (inline-button "Currency" "docs:field:currency")]
    [(inline-button "Services included" "docs:field:services")
     (inline-button "Payment plan" "docs:field:payment-plan")]
    [(inline-button "Status notes" "docs:field:status-notes")]
    [(inline-button "Add invoice" "docs:invoice:add")
     (inline-button "Add payment" "docs:payment:add")]
    [(inline-button "Issue proposal PDF" "docs:generate:proposal")
     (inline-button "Issue status report PDF" "docs:generate:status-report")]
    [(inline-button "Invoice PDF (latest)" "docs:generate:invoice:pick")
     (inline-button "Receipt PDF (latest)" "docs:generate:receipt:pick")]
    [(inline-button "Change client" "docs:client:pick")
     (inline-button "Close" "docs:cancel")]]})

(defn- docs-currency-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "SAR" "docs:currency:SAR")
     (inline-button "USD" "docs:currency:USD")
     (inline-button "EUR" "docs:currency:EUR")]
    [(inline-button "Other..." "docs:currency:other")
     (inline-button "Cancel" "docs:menu")]]})

(defn- docs-skip-cancel-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "Skip" "docs:skip")
     (inline-button "Cancel" "docs:menu")]]})

(defn- docs-invoice-status-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "Draft" "docs:invoice:status:draft")
     (inline-button "Sent" "docs:invoice:status:sent")]
    [(inline-button "Paid" "docs:invoice:status:paid")
     (inline-button "Void" "docs:invoice:status:void")]
    [(inline-button "Cancel" "docs:menu")]]})

(defn- docs-payment-method-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "Cash" "docs:payment:method:cash")
     (inline-button "Transfer" "docs:payment:method:transfer")]
    [(inline-button "Card" "docs:payment:method:card")
     (inline-button "Other" "docs:payment:method:other")]
    [(inline-button "Cancel" "docs:menu")]]})

(defn- docs-payment-invoice-attach-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "Attach to invoice" "docs:payment:invoice:pick")
     (inline-button "No invoice" "docs:payment:invoice:skip")]
    [(inline-button "Cancel" "docs:menu")]]})

(defn- docs-payment-reference-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "Skip" "docs:payment:ref:skip")
     (inline-button "Cancel" "docs:menu")]]})

(defn- docs-payment-note-inline-keyboard
  []
  {:inline_keyboard
   [[(inline-button "Skip" "docs:payment:note:skip")
     (inline-button "Cancel" "docs:menu")]]})

(defn- client-cancel-inline-keyboard
  [task-id]
  {:inline_keyboard
   [[(inline-button "Cancel" (str "task:client:cancel:" task-id))]]})

(defn- client-next-action-inline-keyboard
  [client-id]
  {:inline_keyboard
   [[(inline-button "Call client" (str "client:action:" client-id ":call"))
     (inline-button "Request docs" (str "client:action:" client-id ":docs"))]
    [(inline-button "Follow up" (str "client:action:" client-id ":followup"))
     (inline-button "Schedule meeting" (str "client:action:" client-id ":meeting"))]
    [(inline-button "Custom..." (str "client:action:" client-id ":custom"))
     (inline-button "Dismiss" (str "client:action:" client-id ":dismiss"))]]})

(defn- client-action-cancel-inline-keyboard
  [client-id]
  {:inline_keyboard
   [[(inline-button "Cancel" (str "client:action:" client-id ":dismiss"))]]})

(def ^:private pending-reason-options
  [{:id :client-waiting :label "Waiting on client"}
   {:id :docs :label "Need documents"}
   {:id :schedule :label "Scheduling"}
   {:id :payment :label "Payment pending"}
   {:id :custom :label "Custom..."}])

(defn- pending-reason-text
  [reason-id]
  (let [rid (cond
              (keyword? reason-id) (name reason-id)
              (string? reason-id) reason-id
              :else nil)]
    (or (some (fn [{:keys [id label]}]
                (when (= (name id) rid) label))
              pending-reason-options)
        rid)))

(def ^:private pending-followup-options
  [{:id :tomorrow :label "Tomorrow"}
   {:id :in-3-days :label "In 3 days"}
   {:id :next-week :label "Next week"}
   {:id :pick-date :label "Pick date"}])

(defn- pending-reason-inline-keyboard
  [task-id]
  (let [buttons (map (fn [{:keys [id label]}]
                       (inline-button label (str "pending:reason:" task-id ":" (name id))))
                     pending-reason-options)
        rows (->> buttons
                  (partition-all 2)
                  (mapv vec))]
    {:inline_keyboard
     (conj rows [(inline-button "Cancel" (str "pending:cancel:" task-id))])}))

(defn- pending-followup-inline-keyboard
  [task-id]
  (let [buttons (map (fn [{:keys [id label]}]
                       (inline-button label (str "pending:followup:" task-id ":" (name id))))
                     pending-followup-options)
        rows (->> buttons
                  (partition-all 2)
                  (mapv vec))]
    {:inline_keyboard
     (conj rows [(inline-button "Cancel" (str "pending:cancel:" task-id))])}))

(defn- pending-edit-inline-keyboard
  [task-id]
  {:inline_keyboard
   [[(inline-button "Cancel" (str "task:edit:cancel:" task-id))]]})

(defn- inline-button [text data]
  {:text text
   :callback_data data})

(defn- task-inline-keyboard
  [task]
  (let [id (str (:task/id task))
        archived? (:task/archived? task)]
    {:inline_keyboard
     [[(inline-button "🔵 Todo" (str "task:status:" id ":todo"))
       (inline-button "🟡 In progress" (str "task:status:" id ":in-progress"))]
      [(inline-button "🔴 Pending" (str "task:status:" id ":pending"))
       (inline-button "🟢 Done" (str "task:status:" id ":done"))]
      [(inline-button "Edit title" (str "task:edit:title:" id))
       (inline-button "Edit desc" (str "task:edit:desc:" id))]
      [(inline-button "Add note" (str "task:note:add:" id))
       (inline-button "Edit note" (str "task:note:edit:" id))]
      [(inline-button "Delete note" (str "task:note:delete:" id))
       (inline-button "Delete task" (str "task:delete:" id))]
      [(inline-button "Tasks" "filter:refresh")
       (inline-button (if archived? "Unarchive" "Archive")
                      (str "task:archive:" id ":" (if archived? "false" "true")))]
      [(inline-button "Refresh" (str "task:view:" id))]]}))

(defn webhook-enabled?
  [cfg]
  (true? (:webhook-enabled? cfg)))

(defn polling-enabled?
  [cfg]
  (true? (:polling-enabled? cfg)))

(defn- auto-bind-user
  [state db chat-id]
  (let [username (get-in state [:config :telegram :auto-bind-username])]
    (when (and (present-string? username) db)
      (when-let [user (users/user-by-username db username)]
        (when-let [res (bind-chat-for-user! state {:user user :chat-id chat-id})]
          (:user res))))))

(defn commands-enabled?
  [cfg]
  (true? (:commands-enabled? cfg)))

(defn notifications-enabled?
  [cfg]
  (true? (:notifications-enabled? cfg)))

(defn- bot-url
  [cfg path]
  (when-let [token (:bot-token cfg)]
    (str "https://api.telegram.org/bot" token "/" path)))

(defn- ensure-conn
  [state]
  (get-in state [:db :conn]))

(defn- request-json
  [cfg path payload]
  (if (str/blank? (:bot-token cfg))
    {:error "Telegram bot token not configured"}
    (let [timeout (:http-timeout-ms cfg default-timeout-ms)]
      (letfn [(parse-json-body [body]
                (try
                  (cond
                    (string? body) (json/read-str body :key-fn keyword)
                    (map? body) body
                    :else nil)
                  (catch Exception _ nil)))
              (parse-int* [v]
                (when (and v (not (str/blank? (str v))))
                  (try
                    (Long/parseLong (str/trim (str v)))
                    (catch Exception _ nil))))
              (retry-after-ms [resp]
                (when-let [raw (get-in resp [:headers "retry-after"])]
                  (when-let [secs (parse-int* raw)]
                    (* 1000 secs))))
              (base-backoff-ms [attempt]
                (+ (* attempt 300)
                   (rand-int 200)))
              (should-retry? [resp]
                (let [status (:status resp)]
                  (or (= status 429)
                      (and (number? status) (<= 500 status)))))
              (attempt! [attempt]
                (try
                  (let [resp (http/post (bot-url cfg path)
                                        {:headers {"Content-Type" "application/json"
                                                   "Accept" "application/json"}
                                         :body (json/write-str payload)
                                         :socket-timeout timeout
                                         :conn-timeout timeout
                                         :throw-exceptions false
                                         :as :text})
                        body (parse-json-body (:body resp))]
                    (cond
                      (true? (:ok body))
                      {:result (:result body)}

                      (and (< attempt 3) (should-retry? resp))
                      (let [sleep-ms (or (retry-after-ms resp)
                                         (base-backoff-ms attempt))]
                        (log/warnf "Telegram API transient error; retrying path=%s status=%s attempt=%s sleep_ms=%s"
                                   path (:status resp) attempt sleep-ms)
                        (Thread/sleep sleep-ms)
                        (attempt! (inc attempt)))

                      :else
                      {:error (or (:description body)
                                  "Telegram API error")
                       :details (select-keys body [:description :error_code])
                       :status (:status resp)}))
                  (catch Exception e
                    (if (< attempt 3)
                      (do
                        (log/warn e "Telegram API request failed; retrying" {:path path :attempt attempt})
                        (Thread/sleep (base-backoff-ms attempt))
                        (attempt! (inc attempt)))
                      (do
                        (log/warn e "Telegram API request failed" {:path path})
                        {:error "Telegram API request failed"})))))]
        (attempt! 1)))))

(defn get-updates!
  "Fetch Telegram updates. Returns {:updates [...], :next-offset n} or {:error ...}."
  [cfg {:keys [offset limit timeout-ms]}]
  (let [timeout-secs (when (number? timeout-ms)
                       (max 0 (long (Math/ceil (/ timeout-ms 1000.0)))))
        payload (cond-> {}
                  (some? offset) (assoc :offset (long offset))
                  (some? limit) (assoc :limit (long limit))
                  (some? timeout-secs) (assoc :timeout timeout-secs))
        resp (request-json cfg "getUpdates" payload)]
    (if-let [err (:error resp)]
      resp
      (let [updates (vec (or (:result resp) []))
            last-id (when (seq updates)
                      (apply max (map :update_id updates)))
            next-offset (when last-id (inc (long last-id)))]
        {:updates updates
         :next-offset next-offset}))))

(defn send-message!
  "Send a Telegram message to chat-id. Returns {:telegram/message-id ...} or {:error ...}."
  [cfg {:keys [chat-id text parse-mode message-key reply-markup]}]
  (cond
    (str/blank? chat-id) {:error "Missing chat id"}
    (str/blank? text) {:error "Missing message text"}
    (str/blank? message-key) {:error "Missing message key for idempotency"}
    :else
    (let [payload (cond-> {:chat_id chat-id
                           :text text}
                    parse-mode (assoc :parse_mode (name parse-mode))
                    reply-markup (assoc :reply_markup reply-markup))
          resp (request-json cfg "sendMessage" payload)]
      (if-let [err (:error resp)]
        resp
        {:telegram/message-id (get-in resp [:result :message_id])}))))

(defn edit-message!
  "Edit an existing Telegram message text (and optional keyboard). Returns {:telegram/message-id ...} or {:error ...}."
  [cfg {:keys [chat-id message-id text parse-mode reply-markup]}]
  (cond
    (str/blank? chat-id) {:error "Missing chat id"}
    (nil? message-id) {:error "Missing message id"}
    (str/blank? text) {:error "Missing message text"}
    :else
    (let [payload (cond-> {:chat_id chat-id
                           :message_id message-id
                           :text text}
                    parse-mode (assoc :parse_mode (name parse-mode))
                    reply-markup (assoc :reply_markup reply-markup))
          resp (request-json cfg "editMessageText" payload)]
      (if-let [err (:error resp)]
        resp
        {:telegram/message-id (get-in resp [:result :message_id])}))))

(defn send-document-file!
  "Send a local file (document) to chat-id via Telegram. Best-effort. Returns {:telegram/message-id ...} or {:error ...}."
  [cfg {:keys [chat-id file filename caption]}]
  (cond
    (str/blank? chat-id) {:error "Missing chat id"}
    (nil? file) {:error "Missing file"}
    :else
    (let [url (bot-url cfg "sendDocument")
          timeout (:http-timeout-ms cfg default-timeout-ms)
          payload {:multipart (cond-> [{:name "chat_id" :content chat-id}
                                       {:name "document" :content file :filename (or filename (.getName ^java.io.File file))}]
                                (present-string? caption)
                                (conj {:name "caption" :content caption}))}
          resp (try
                 (http/post url (merge payload
                                       {:socket-timeout timeout
                                        :conn-timeout timeout
                                        :throw-exceptions false
                                        :as :text}))
                 (catch Exception e
                   (log/warn e "Telegram sendDocument failed")
                   {:error "Telegram sendDocument failed"}))]
      (if (:error resp)
        resp
        (let [body (try
                     (json/read-str (or (:body resp) "") :key-fn keyword)
                     (catch Exception _ nil))]
          (if (and (map? body) (true? (:ok body)))
            {:telegram/message-id (get-in body [:result :message_id])}
            {:error (or (:description body) "Telegram sendDocument error")
             :details (select-keys body [:description :error_code])
             :status (:status resp)}))))))

(defn answer-callback!
  [cfg {:keys [callback-id text show-alert?]}]
  (when (not (str/blank? callback-id))
    (request-json cfg "answerCallbackQuery" (cond-> {:callback_query_id callback-id}
                                              (some? text) (assoc :text text)
                                              show-alert? (assoc :show_alert true)))))
(defn set-webhook!
  [cfg {:keys [webhook-url secret-token]}]
  (cond
    (str/blank? webhook-url) {:error "Missing webhook URL"}
    (str/blank? secret-token) {:error "Missing webhook secret token"}
    :else
    (request-json cfg "setWebhook" {:url webhook-url
                                    :secret_token secret-token
                                    :allowed_updates ["message" "callback_query"]})))

(defn auto-set-webhook!
  "If enabled and configured, call setWebhook on startup."
  [cfg]
  (let [{:keys [webhook-enabled? auto-set-webhook? webhook-base-url webhook-secret]} cfg]
    (when (and webhook-enabled? auto-set-webhook? (present-string? webhook-base-url))
      (let [url (str (str/replace (str/trim webhook-base-url) #"/+$" "") "/api/telegram/webhook")]
        (log/info "Setting Telegram webhook" {:url url})
        (let [res (set-webhook! cfg {:webhook-url url
                                     :secret-token webhook-secret})]
          (when-let [err (:error res)]
            (log/error "Failed to set Telegram webhook" {:error err :url url})
            res))))))

(defn ensure-link-token!
  "Generate and persist a new link token for the given user id. Returns {:token \"...\"} or {:error ...}."
  [state user-id]
  (if-let [conn (ensure-conn state)]
    (try
      (let [token (str (UUID/randomUUID))]
        (db/transact! conn {:tx-data [[:db/add [:user/id user-id] :user/telegram-link-token token]
                                    [:db/add [:user/id user-id] :user/telegram-link-token-created-at (java.util.Date.)]]})
        {:token token})
      (catch Exception e
        (log/warn e "Failed to create Telegram link token" {:user-id user-id})
        {:error "Unable to create link token"}))
    {:error "No database connection"}))

(defn- user-by-link-token
  [db token]
  (when-not (str/blank? token)
    (-> (d/q '[:find (pull ?u [:user/id
                               :user/username
                               :user/name
                               :user/telegram-link-token
                               :user/telegram-link-token-created-at
                               :user/telegram-chat-id])
               :in $ ?token
               :where [?u :user/telegram-link-token ?token]]
             db token)
        ffirst)))

(defn- user-by-chat-id
  [db chat-id]
  (when-not (str/blank? chat-id)
    (-> (d/q '[:find (pull ?u [:user/id :user/username :user/name :user/telegram-chat-id])
               :in $ ?chat
               :where [?u :user/telegram-chat-id ?chat]]
             db chat-id)
        ffirst)))

(defn- user-by-telegram-user-id
  [db telegram-user-id]
  (when telegram-user-id
    (-> (d/q '[:find (pull ?u [:user/id :user/username :user/name :user/telegram-user-id :user/telegram-chat-id])
               :in $ ?tid
               :where [?u :user/telegram-user-id ?tid]]
             db telegram-user-id)
        ffirst)))

(defn- chat-id-by-user-id
  [db user-id]
  (when user-id
    (-> (d/q '[:find ?chat
               :in $ ?id
               :where [?u :user/id ?id]
                      [?u :user/telegram-chat-id ?chat]]
             db user-id)
        ffirst)))

(defn bind-chat!
  "Bind chat-id to the user that owns the link token; clears the token. Returns {:user user} or {:error ...}."
  [state {:keys [token chat-id]}]
  (cond
    (str/blank? token) {:error "Missing link token"}
    (str/blank? chat-id) {:error "Missing chat id"}
    :else
    (if-let [conn (ensure-conn state)]
      (try
        (let [db (d/db conn)
              user (user-by-link-token db token)]
          (if-not user
            {:error "Invalid or expired link token"}
            (let [issued-at (:user/telegram-link-token-created-at user)
                  ttl-ms (get-in state [:config :telegram :link-token-ttl-ms] 900000)
                  now-ms (System/currentTimeMillis)
                  issued-ms (when issued-at (.getTime ^java.util.Date issued-at))]
              (cond
                (nil? issued-ms) {:error "Invalid or expired link token"}
                (> (- now-ms issued-ms) ttl-ms) {:error "Invalid or expired link token"}
                :else
                (let [existing (user-by-chat-id db chat-id)
                      same-user? (and existing (= (:user/id existing) (:user/id user)))
                      already-linked? (or (= (:user/telegram-chat-id user) chat-id)
                                          (and same-user? (= (:user/telegram-chat-id existing) chat-id)))
                      retract-existing (when (and existing (not same-user?))
                                         [[:db/retract [:user/id (:user/id existing)]
                                           :user/telegram-chat-id
                                           (:user/telegram-chat-id existing)]])
                      retract-user-chat (when (and (:user/telegram-chat-id user) (not already-linked?))
                                          [[:db/retract [:user/id (:user/id user)]
                                            :user/telegram-chat-id
                                            (:user/telegram-chat-id user)]])
                      retract-token (cond-> []
                                      (:user/telegram-link-token user)
                                      (conj [:db/retract [:user/id (:user/id user)]
                                             :user/telegram-link-token
                                             (:user/telegram-link-token user)])
                                      (:user/telegram-link-token-created-at user)
                                      (conj [:db/retract [:user/id (:user/id user)]
                                             :user/telegram-link-token-created-at
                                             (:user/telegram-link-token-created-at user)]))
                      add-chat (when (not already-linked?)
                                 [[:db/add [:user/id (:user/id user)] :user/telegram-chat-id chat-id]])]
                  (doseq [tx [retract-existing retract-user-chat retract-token add-chat]]
                    (when (seq tx)
                      (db/transact! conn {:tx-data tx})))
                  {:user (assoc user :user/telegram-chat-id chat-id)})))))
        (catch Exception e
          (log/warn e "Failed to bind Telegram chat" {:chat-id chat-id})
          {:error "Unable to bind chat"}))
      {:error "No database connection"})))

(defn bind-chat-for-user!
  "Bind chat-id to a known user entity (no token)."
  [state {:keys [user chat-id]}]
  (cond
    (nil? user) {:error "Missing user"}
    (str/blank? chat-id) {:error "Missing chat id"}
    :else
    (if-let [conn (ensure-conn state)]
      (try
        (let [db (d/db conn)
              existing (user-by-chat-id db chat-id)
              same-user? (and existing (= (:user/id existing) (:user/id user)))
              already-linked? (or (= (:user/telegram-chat-id user) chat-id)
                                  (and same-user? (= (:user/telegram-chat-id existing) chat-id)))
              retract-existing (when (and existing (not same-user?))
                                 [[:db/retract [:user/id (:user/id existing)]
                                   :user/telegram-chat-id
                                   (:user/telegram-chat-id existing)]])
              retract-user-chat (when (and (:user/telegram-chat-id user) (not already-linked?))
                                  [[:db/retract [:user/id (:user/id user)]
                                    :user/telegram-chat-id
                                    (:user/telegram-chat-id user)]])
              add-chat (when (not already-linked?)
                         [[:db/add [:user/id (:user/id user)] :user/telegram-chat-id chat-id]])]
          (doseq [tx [retract-existing retract-user-chat add-chat]]
            (when (seq tx)
              (db/transact! conn {:tx-data tx})))
          {:user (assoc user :user/telegram-chat-id chat-id)})
        (catch Exception e
          (log/warn e "Failed to bind Telegram chat (auto)" {:chat-id chat-id})
          {:error "Unable to bind chat"}))
      {:error "No database connection"})))

(defn recognize-user!
  "Set telegram user id for a given app user. Returns {:status :ok} or {:error ...}."
  [state user-id telegram-user-id]
  (if-let [conn (ensure-conn state)]
    (try
      (db/transact! conn {:tx-data [[:db/add [:user/id user-id] :user/telegram-user-id telegram-user-id]]})
      {:status :ok
       :user/id user-id
       :telegram/user-id telegram-user-id}
      (catch Exception e
        (log/warn e "Failed to set telegram user id" {:user-id user-id :telegram-user-id telegram-user-id})
        {:error "Unable to set telegram user id"}))
    {:error "No database connection"}))

(defn unbind-chat!
  "Clear chat binding for the given chat-id. Returns {:status :ok} or {:error ...}."
  [state chat-id]
  (if-let [conn (ensure-conn state)]
    (try
      (let [db (d/db conn)
            user (user-by-chat-id db chat-id)]
        (if-not user
          {:error "No chat binding found"}
          (do
            (db/transact! conn {:tx-data [[:db/retract [:user/id (:user/id user)] :user/telegram-chat-id chat-id]]})
            {:status :ok
             :user user})))
      (catch Exception e
        (log/warn e "Failed to unbind Telegram chat" {:chat-id chat-id})
        {:error "Unable to unbind chat"}))
    {:error "No database connection"}))

(defn- parse-command
  [text]
  (when text
    (let [trimmed (str/trim text)]
      (when (str/starts-with? trimmed "/")
        (let [[raw-cmd rest] (str/split trimmed #"\s+" 2)
              raw-cmd (subs raw-cmd 1)
              cmd (some-> raw-cmd
                          (str/split #"@" 2)
                          first
                          str/lower-case
                          keyword)
              rest (some-> rest str/trim)]
          (when (and cmd (allowed-commands cmd))
            {:command cmd
             :rest (when-not (str/blank? rest) rest)}))))))

(defn- extract-update
  [update]
  (let [message (or (:message update) (get update "message"))
        callback (:callback_query update)
        update-id (or (:update_id update) (get update "update_id"))
        chat-id (or (get-in message [:chat :id])
                    (get-in message ["chat" "id"])
                    (get-in callback [:message :chat :id])
                    (get-in callback ["message" "chat" "id"]))
        message-id (or (get-in message [:message_id])
                       (get-in message ["message_id"])
                       (get-in callback [:message :message_id])
                       (get-in callback ["message" "message_id"]))
        from-id (or (get-in message [:from :id])
                    (get-in message ["from" "id"])
                    (get-in callback [:from :id])
                    (get-in callback ["from" "id"]))
        from-username (or (get-in message [:from :username])
                          (get-in message ["from" "username"])
                          (get-in callback [:from :username])
                          (get-in callback ["from" "username"]))
        text (or (:text message) (get message "text"))
        {:keys [command rest]} (parse-command text)]
    {:update-id update-id
     :chat-id (some-> chat-id str)
     :message-id message-id
     :from-id from-id
     :from-username from-username
     :text text
     :command command
     :rest rest
     :callback (when callback
                 {:callback-id (or (:id callback) (get callback "id"))
                  :data (or (:data callback) (get callback "data"))
                  :message-id (or (get-in callback [:message :message_id])
                                  (get-in callback ["message" "message_id"]))
                  :chat-id chat-id})}))

(defn- status-label
  [status]
  (let [label (or (some-> status name) "unknown")
        icon (get status-emoji status "⚪️")]
    (str icon " " label)))

(defn- format-task-line
  [task pending-reason]
  (let [status (status-label (:task/status task))
        due (or (:task/due-date task) "none")
        title (or (:task/title task) "Untitled task")
        client-name (get-in task [:task/client :client/name])]
    (str "- " status " " title " (due " due ")"
         (when client-name
           (str " · " client-name))
         (when pending-reason
           (str " · Reason: " (truncate-text pending-reason 80))))))

(defn- tasks-summary-text
  [tasks pending-reasons]
  (if (empty? tasks)
    "You have no tasks assigned."
    (str "Your tasks:\n"
         (str/join "\n"
                   (map (fn [task]
                          (format-task-line task (get pending-reasons (:task/id task))))
                        tasks)))))

(defn- tasks-filter-rows
  [{:keys [status archived]}]
  (let [status-label (fn [label key]
                       (if (= status key) (str label " ✅") label))
        arch-label (fn [label key]
                     (if (= archived key) (str label " ✅") label))]
    [[(inline-button (status-label "All" nil) "filter:status:all")
      (inline-button (status-label "🔵 Todo" :todo) "filter:status:todo")
      (inline-button (status-label "🟡 In prog" :in-progress) "filter:status:in-progress")]
     [(inline-button (status-label "🔴 Pending" :pending) "filter:status:pending")
      (inline-button (status-label "🟢 Done" :done) "filter:status:done")]
     [(inline-button (arch-label "Active" :active) "filter:archived:active")
      (inline-button (arch-label "All" :all) "filter:archived:all")
      (inline-button (arch-label "Archived" :archived) "filter:archived:archived")]
     [(inline-button "Refresh" "filter:refresh")]]))

(defn- task-open-button
  [task]
  (let [id (str (:task/id task))
        title (or (:task/title task) "Task")
        label (if (> (count title) 22) (str (subs title 0 19) "…") title)
        status (get status-emoji (:task/status task))]
    (inline-button (str (when status (str status " ")) label) (str "task:view:" id))))

(defn- tasks-list-keyboard
  [tasks filters]
  {:inline_keyboard
   (vec (concat
         (tasks-filter-rows filters)
         (mapv (fn [task] [(task-open-button task)]) tasks)))})

(defn- task-detail-text
  [task pending-reason]
  (if-not task
    "Task not found or not assigned to you."
    (let [status (status-label (:task/status task))
          due (or (:task/due-date task) "none")
          assignee (get-in task [:task/assignee :user/username] "n/a")
          client-name (get-in task [:task/client :client/name])]
      (str (or (:task/title task) "Untitled task") "\n"
           "Status: " status "\n"
           (when client-name (str "Client: " client-name "\n"))
           (when pending-reason (str "Pending reason: " (truncate-text pending-reason 120) "\n"))
           "Due: " due "\n"
           "Assignee: " assignee))))

(defn- task-notification-text
  [event title status due actor-name]
  (case event
    :task/created (str "New task assigned by " actor-name ":\n" title "\nStatus: " status "\nDue: " due)
    :task/assigned (str "Task assigned by " actor-name ":\n" title "\nStatus: " status "\nDue: " due)
    :task/status-changed (str "Task status updated by " actor-name ":\n" title "\nStatus: " status "\nDue: " due)
    :task/due-changed (str "Task due date updated by " actor-name ":\n" title "\nStatus: " status "\nDue: " due)
    (str "Task update:\n" title "\nStatus: " status "\nDue: " due)))

(defn- send-task-card!
  [state chat-id task {:keys [reply-to-message-id] :as opts}]
    (let [cfg (get-in state [:config :telegram])
          conn (ensure-conn state)
          db (when conn (d/db conn))
          pending-reason (pending-reason-for-task db task)
          text (task-detail-text task pending-reason)
          keyboard (task-inline-keyboard task)
          message-key (str "task-card:" (:task/id task) ":" (System/currentTimeMillis))]
      (if reply-to-message-id
        (edit-message! cfg {:chat-id chat-id
                            :message-id reply-to-message-id
                            :text text
                            :reply-markup keyboard})
        (send-message! cfg {:chat-id chat-id
                            :text text
                            :message-key message-key
                            :reply-markup keyboard}))))

(defn- ensure-default-client-id
  [state workspace]
  (when-let [conn (ensure-conn state)]
    (or (:client/id (clients/ensure-default-client! conn workspace))
        clients/default-client-id)))

(defn- prompt-link-client!
  [state chat-id task]
  (let [cfg (get-in state [:config :telegram])
        task-id (str (:task/id task))
        title (or (:task/title task) "Task")
        prompt (str "Link to client for:\n" title)
        keyboard (link-client-inline-keyboard task-id)]
    (send-message! cfg {:chat-id chat-id
                        :text prompt
                        :message-key (str "task-link-client-" task-id "-" (System/currentTimeMillis))
                        :reply-markup keyboard})))

(defn- prompt-client-pick!
  [state chat-id task-id]
  (let [cfg (get-in state [:config :telegram])
        conn (ensure-conn state)
        workspace nil
        res (clients/list-clients conn {:limit 6} workspace)
        client-list (or (:clients res) [])
        prompt (if (seq client-list)
                 "Pick a client:"
                 "No clients available yet. Create one?")
        keyboard (client-pick-inline-keyboard task-id client-list)]
    (send-message! cfg {:chat-id chat-id
                        :text prompt
                        :message-key (str "client-pick-" task-id "-" (System/currentTimeMillis))
                        :reply-markup keyboard})))

(defn- prompt-docs-client-pick!
  [state chat-id]
  (let [cfg (get-in state [:config :telegram])
        conn (ensure-conn state)
        workspace nil
        res (clients/list-clients conn {:limit 6} workspace)
        client-list (or (:clients res) [])
        prompt (if (seq client-list)
                 "Pick a client:"
                 "No clients available yet.")
        keyboard (docs-client-pick-inline-keyboard client-list)]
    (send-message! cfg {:chat-id chat-id
                        :text prompt
                        :message-key (str "docs-client-pick-" (System/currentTimeMillis))
                        :reply-markup keyboard})))

(defn- prompt-next-action!
  [state chat-id client]
  (let [cfg (get-in state [:config :telegram])
        client-id (str (:client/id client))
        name (or (:client/name client) "Client")
        prompt (str "Next action for " name "?")
        keyboard (client-next-action-inline-keyboard client-id)]
    (send-message! cfg {:chat-id chat-id
                        :text prompt
                        :message-key (str "client-next-action-" client-id "-" (System/currentTimeMillis))
                        :reply-markup keyboard})))

(defn- list-user-tasks
  [conn user-id {:keys [status archived limit] :or {limit tasks-list-limit}}]
  (let [archived (case archived
                   :archived true
                   :all :all
                   :active false
                   false)
        params (cond-> {:assignee user-id
                        :archived archived
                        :limit limit
                        :offset 0
                        :sort :updated
                        :order :desc}
                 status (assoc :status status))]
    (tasks/list-tasks conn params)))

(defn- find-user-task
  [conn user-id task-id]
  (let [res (list-user-tasks conn user-id {:limit tasks-list-limit
                                           :archived :all})]
    (when-not (:error res)
      (->> (:tasks res)
           (filter #(= (:task/id %) task-id))
           first))))

(defn- handle-freeform-message
  [state chat-user chat-id text]
  (let [cfg (get-in state [:config :telegram])
        prompt (str "Create from text?\n\n" text)
        send-res (send-message! cfg {:chat-id chat-id
                                     :text prompt
                                     :message-key (str "capture-" (System/currentTimeMillis))
                                     :reply-markup (capture-inline-keyboard "pending")})]
    (if-let [err (:error send-res)]
      (send-message! cfg {:chat-id chat-id
                          :text (str "Unable to capture: " err)
                          :message-key (str "capture-error-" (System/currentTimeMillis))})
      (let [message-id (:telegram/message-id send-res)]
        (save-capture! chat-id message-id {:user chat-user
                                           :text text})
        (edit-message! cfg {:chat-id chat-id
                            :message-id message-id
                            :text prompt
                            :reply-markup (capture-inline-keyboard message-id)})))))

(defn- handle-pending-reason-message
  [state chat-user chat-id text]
  (let [cfg (get-in state [:config :telegram])
        pending (get-pending-reason! chat-id)]
    (when pending
      (let [task-id (:task-id pending)
            stage (:stage pending)
            reason (:reason pending)
            trimmed (some-> text str/trim)]
        (cond
          (#{:followup-date :followup-date-picker} stage)
          (let [{:keys [text quicks]} (:picker pending)
                text (or text "Pick the follow-up date:")
                quicks (or quicks [{:id :tomorrow :label "Tomorrow"}
                                   {:id :in-3-days :label "In 3 days"}
                                   {:id :next-week :label "Next week"}])]
            (send-message! cfg {:chat-id chat-id
                                :text "Use the buttons to choose a date."
                                :message-key (str "pending-followup-date-click-" (System/currentTimeMillis))
                                :reply-markup (date-picker-inline-keyboard {:month (LocalDate/now (ZoneId/systemDefault))
                                                                            :quicks quicks
                                                                            :extra-rows [[(inline-button "Cancel" (str "pending:cancel:" task-id))]]})}))

          (= stage :followup)
          (send-message! cfg {:chat-id chat-id
                              :text "Use the buttons to choose a follow-up option."
                              :message-key (str "pending-followup-button-" (System/currentTimeMillis))
                              :reply-markup (pending-followup-inline-keyboard task-id)})

          :else
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Pending reason cannot be blank. Reply with a reason or tap Cancel."
                                :message-key (str "pending-reason-empty-" (System/currentTimeMillis))})
            (do
              (save-pending-reason! chat-id (assoc pending :stage :followup :reason trimmed))
              (send-message! cfg {:chat-id chat-id
                                  :text "When should I follow up?"
                                  :message-key (str "pending-followup-" (System/currentTimeMillis))
                                  :reply-markup (pending-followup-inline-keyboard task-id)}))))))))

(defn- handle-pending-client-message
  [state chat-user chat-id text]
  (let [cfg (get-in state [:config :telegram])
        pending (get-pending-client! chat-id)
        name (some-> text str/trim)]
    (when pending
      (if (str/blank? name)
        (send-message! cfg {:chat-id chat-id
                            :text "Client name cannot be blank. Reply with a name or tap Cancel."
                            :message-key (str "client-name-empty-" (System/currentTimeMillis))})
        (let [res (actions/execute! state {:action/id :cap/action/client-create
                                           :actor (actions/actor-from-telegram chat-user)
                                           :input {:client/name name}})
              client (get-in res [:result :client])
              task-id (:task-id pending)
              mode (:mode pending)]
          (take-pending-client! chat-id)
          (if-let [err (:error res)]
            (send-message! cfg {:chat-id chat-id
                                :text (str "Unable to create client: " (:message err))
                                :message-key (str "client-create-error-" (System/currentTimeMillis))})
            (if (and (= mode :link) task-id)
              (let [link-res (actions/execute! state {:action/id :cap/action/task-set-client
                                                      :actor (actions/actor-from-telegram chat-user)
                                                      :input {:task/id task-id
                                                              :task/client (:client/id client)}})]
                (if-let [link-err (:error link-res)]
                  (send-message! cfg {:chat-id chat-id
                                      :text (str "Unable to link client: " (:message link-err))
                                      :message-key (str "client-link-error-" (System/currentTimeMillis))})
                  (do
                    (send-message! cfg {:chat-id chat-id
                                        :text (str "Client linked: " name)
                                        :message-key (str "client-linked-" (System/currentTimeMillis))})
                    (send-task-card! state chat-id (get-in link-res [:result :task]) {}))))
              (do
                (send-message! cfg {:chat-id chat-id
                                    :text (str "Client created: " name)
                                    :message-key (str "client-created-" (System/currentTimeMillis))})
                (prompt-next-action! state chat-id client)))))))))

(defn- handle-pending-next-action-message
  [state chat-user chat-id text]
  (let [cfg (get-in state [:config :telegram])
        pending (get-pending-next-action! chat-id)
        title (some-> text str/trim)]
    (when pending
      (if (str/blank? title)
        (send-message! cfg {:chat-id chat-id
                            :text "Action cannot be blank. Reply with a title or tap Cancel."
                            :message-key (str "client-action-empty-" (System/currentTimeMillis))})
        (let [client-id (:client-id pending)
              desc (str "Next action for " (or (:client-name pending) "client"))
              body (task-body chat-user {:title title
                                         :desc desc
                                         :client-id client-id})
              res (actions/execute! state {:action/id :cap/action/task-create
                                           :actor (actions/actor-from-telegram chat-user)
                                           :input body})]
          (take-pending-next-action! chat-id)
          (if-let [err (:error res)]
            (send-message! cfg {:chat-id chat-id
                                :text (str "Unable to create task: " (:message err))
                                :message-key (str "client-action-error-" (System/currentTimeMillis))})
            (send-task-card! state chat-id (get-in res [:result :task]) {})))))))

(defn- handle-docs-message
  [state chat-user chat-id text]
  (let [cfg (get-in state [:config :telegram])
        session (get-docs-session! chat-id)
        trimmed (some-> text str/trim)]
    (when session
      (let [stage (:stage session)
            client-id (:client-id session)
            actor (actions/actor-from-telegram chat-user)]
        (case stage
          :docs/field-company-name
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send company name, or tap Skip."
                                :message-key (str "docs-company-empty-" (System/currentTimeMillis))
                                :reply-markup (docs-skip-cancel-inline-keyboard)})
            (let [res (actions/execute! state {:action/id :cap/action/doc-pack-upsert
                                               :actor actor
                                               :input {:client/id client-id
                                                       :doc.pack/company-name trimmed}})]
              (save-docs-session! chat-id (assoc session :stage :docs/menu))
              (if-let [err (:error res)]
                (send-message! cfg {:chat-id chat-id
                                    :text (str "Unable to save: " (:message err))
                                    :message-key (str "docs-company-error-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)})
                (send-message! cfg {:chat-id chat-id
                                    :text "Saved."
                                    :message-key (str "docs-company-ok-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)}))))

          :docs/field-services
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send services included text, or tap Skip."
                                :message-key (str "docs-services-empty-" (System/currentTimeMillis))
                                :reply-markup (docs-skip-cancel-inline-keyboard)})
            (let [res (actions/execute! state {:action/id :cap/action/doc-pack-upsert
                                               :actor actor
                                               :input {:client/id client-id
                                                       :doc.pack/services-included trimmed}})]
              (save-docs-session! chat-id (assoc session :stage :docs/menu))
              (if-let [err (:error res)]
                (send-message! cfg {:chat-id chat-id
                                    :text (str "Unable to save: " (:message err))
                                    :message-key (str "docs-services-error-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)})
                (send-message! cfg {:chat-id chat-id
                                    :text "Saved."
                                    :message-key (str "docs-services-ok-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)}))))

          :docs/field-payment-plan
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send payment plan text, or tap Skip."
                                :message-key (str "docs-plan-empty-" (System/currentTimeMillis))
                                :reply-markup (docs-skip-cancel-inline-keyboard)})
            (let [res (actions/execute! state {:action/id :cap/action/doc-pack-upsert
                                               :actor actor
                                               :input {:client/id client-id
                                                       :doc.pack/payment-plan trimmed}})]
              (save-docs-session! chat-id (assoc session :stage :docs/menu))
              (if-let [err (:error res)]
                (send-message! cfg {:chat-id chat-id
                                    :text (str "Unable to save: " (:message err))
                                    :message-key (str "docs-plan-error-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)})
                (send-message! cfg {:chat-id chat-id
                                    :text "Saved."
                                    :message-key (str "docs-plan-ok-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)}))))

          :docs/field-status-notes
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send status notes text, or tap Skip."
                                :message-key (str "docs-status-empty-" (System/currentTimeMillis))
                                :reply-markup (docs-skip-cancel-inline-keyboard)})
            (let [res (actions/execute! state {:action/id :cap/action/doc-pack-upsert
                                               :actor actor
                                               :input {:client/id client-id
                                                       :doc.pack/status-notes trimmed}})]
              (save-docs-session! chat-id (assoc session :stage :docs/menu))
              (if-let [err (:error res)]
                (send-message! cfg {:chat-id chat-id
                                    :text (str "Unable to save: " (:message err))
                                    :message-key (str "docs-status-error-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)})
                (send-message! cfg {:chat-id chat-id
                                    :text "Saved."
                                    :message-key (str "docs-status-ok-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)}))))

          :docs/field-currency-other
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send currency code (e.g. SAR), or tap Skip."
                                :message-key (str "docs-currency-empty-" (System/currentTimeMillis))
                                :reply-markup (docs-skip-cancel-inline-keyboard)})
            (let [res (actions/execute! state {:action/id :cap/action/doc-pack-upsert
                                               :actor actor
                                               :input {:client/id client-id
                                                       :doc.pack/currency trimmed}})]
              (save-docs-session! chat-id (assoc session :stage :docs/menu))
              (if-let [err (:error res)]
                (send-message! cfg {:chat-id chat-id
                                    :text (str "Unable to save: " (:message err))
                                    :message-key (str "docs-currency-error-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)})
                (send-message! cfg {:chat-id chat-id
                                    :text "Saved."
                                    :message-key (str "docs-currency-ok-" (System/currentTimeMillis))
                                    :reply-markup (docs-menu-inline-keyboard)}))))

          :docs/inv-number
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send invoice number."
                                :message-key (str "docs-inv-num-empty-" (System/currentTimeMillis))})
            (do
              (save-docs-session! chat-id (-> session
                                              (assoc :stage :docs/inv-total)
                                              (assoc :draft {:invoice/number trimmed})))
              (send-message! cfg {:chat-id chat-id
                                  :text "Send invoice total amount:"
                                  :message-key (str "docs-inv-total-" (System/currentTimeMillis))
                                  :reply-markup {:inline_keyboard [[(inline-button "Cancel" "docs:menu")]]}})))

          :docs/inv-total
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send invoice total amount."
                                :message-key (str "docs-inv-total-empty-" (System/currentTimeMillis))})
            (do
              (save-docs-session! chat-id (-> session
                                              (assoc :stage :docs/inv-status)
                                              (assoc-in [:draft :invoice/total-amount] trimmed)))
              (send-message! cfg {:chat-id chat-id
                                  :text "Choose invoice status:"
                                  :message-key (str "docs-inv-status-" (System/currentTimeMillis))
                                  :reply-markup (docs-invoice-status-inline-keyboard)})))

          :docs/payment-amount
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send the payment amount."
                                :message-key (str "docs-payment-amount-empty-" (System/currentTimeMillis))})
            (do
              (save-docs-session! chat-id (-> session
                                              (assoc :stage :docs/payment-method)
                                              (assoc-in [:draft :payment/amount] trimmed)))
              (send-message! cfg {:chat-id chat-id
                                  :text "Choose payment method:"
                                  :message-key (str "docs-payment-method-" (System/currentTimeMillis))
                                  :reply-markup (docs-payment-method-inline-keyboard)})))

          :docs/payment-reference
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send the payment reference, or tap Skip."
                                :message-key (str "docs-payment-ref-empty-" (System/currentTimeMillis))
                                :reply-markup (docs-payment-reference-inline-keyboard)})
            (let [draft (:draft session)
                  paid-at (:payment/paid-at draft)]
              (if-not paid-at
                (send-message! cfg {:chat-id chat-id
                                    :text "Pick payment date first (buttons)."
                                    :message-key (str "docs-payment-date-missing-" (System/currentTimeMillis))})
                (do
                  (save-docs-session! chat-id (-> session
                                                  (assoc :stage :docs/payment-note)
                                                  (assoc-in [:draft :payment/reference] (when (present-string? trimmed) trimmed))))
                  (send-message! cfg {:chat-id chat-id
                                      :text "Payment note (optional). You can include who sent/received the money, bank details, anything:"
                                      :message-key (str "docs-payment-note-" (System/currentTimeMillis))
                                      :reply-markup (docs-payment-note-inline-keyboard)})))))

          :docs/payment-note
          (if (str/blank? trimmed)
            (send-message! cfg {:chat-id chat-id
                                :text "Send the payment note, or tap Skip."
                                :message-key (str "docs-payment-note-empty-" (System/currentTimeMillis))
                                :reply-markup (docs-payment-note-inline-keyboard)})
            (let [draft (:draft session)
                  paid-at (:payment/paid-at draft)]
              (if-not paid-at
                (send-message! cfg {:chat-id chat-id
                                    :text "Pick payment date first (buttons)."
                                    :message-key (str "docs-payment-date-missing-" (System/currentTimeMillis))})
                (let [input (cond-> {:client/id client-id
                                     :payment/amount (:payment/amount draft)
                                     :payment/method (:payment/method draft)
                                     :payment/paid-at paid-at}
                              (:invoice/id draft) (assoc :invoice/id (:invoice/id draft))
                              (present-string? (:payment/reference draft)) (assoc :payment/reference (:payment/reference draft))
                              (present-string? trimmed) (assoc :payment/note trimmed))
                      res (actions/execute! state {:action/id :cap/action/payment-create
                                                   :actor actor
                                                   :input input})]
                  (save-docs-session! chat-id (assoc session :stage :docs/menu :draft nil))
                  (if-let [err (:error res)]
                    (send-message! cfg {:chat-id chat-id
                                        :text (str "Unable to add payment: " (:message err))
                                        :message-key (str "docs-payment-create-error-" (System/currentTimeMillis))
                                        :reply-markup (docs-menu-inline-keyboard)})
                    (send-message! cfg {:chat-id chat-id
                                        :text "Payment added."
                                        :message-key (str "docs-payment-added-" (System/currentTimeMillis))
                                        :reply-markup (docs-menu-inline-keyboard)}))))))

          :docs/payment-time
          (send-message! cfg {:chat-id chat-id
                              :text "Pick payment time using the buttons (optional)."
                              :message-key (str "docs-payment-time-click-" (System/currentTimeMillis))
                              :reply-markup (time-picker-inline-keyboard {:allow-skip? true
                                                                          :skip-label "Skip time"
                                                                          :extra-rows [[(inline-button "Cancel" "docs:menu")]]})})

          :docs/payment-date
          (let [month (LocalDate/now (ZoneId/systemDefault))
                quicks (or (get-in session [:picker :quicks])
                           [{:id :today :label "Today"}
                            {:id :yesterday :label "Yesterday"}])]
            (send-message! cfg {:chat-id chat-id
                                :text "Use the calendar buttons to choose a payment date."
                                :message-key (str "docs-payment-date-click-" (System/currentTimeMillis))
                                :reply-markup (date-picker-inline-keyboard {:month month
                                                                            :quicks quicks
                                                                            :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))

          :docs/inv-due-date
          (let [month (LocalDate/now (ZoneId/systemDefault))
                quicks (or (get-in session [:picker :quicks])
                           [{:id :in-7-days :label "+7 days"}
                            {:id :in-14-days :label "+14 days"}
                            {:id :in-30-days :label "+30 days"}])]
            (send-message! cfg {:chat-id chat-id
                                :text "Use the calendar buttons to choose a due date."
                                :message-key (str "docs-inv-due-click-" (System/currentTimeMillis))
                                :reply-markup (date-picker-inline-keyboard {:month month
                                                                            :quicks quicks
                                                                            :allow-skip? true
                                                                            :skip-label "No due date"
                                                                            :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))

          :docs/inv-due-time
          (send-message! cfg {:chat-id chat-id
                              :text "Pick invoice due time using the buttons (optional)."
                              :message-key (str "docs-inv-time-click-" (System/currentTimeMillis))
                              :reply-markup (time-picker-inline-keyboard {:allow-skip? true
                                                                          :skip-label "Skip time"
                                                                          :extra-rows [[(inline-button "Cancel" "docs:menu")]]})})

          :docs/payment-invoice-attach
          (send-message! cfg {:chat-id chat-id
                              :text "Use the buttons to choose invoice attachment."
                              :message-key (str "docs-payment-inv-attach-click-" (System/currentTimeMillis))
                              :reply-markup (docs-payment-invoice-attach-inline-keyboard)})

          :docs/payment-method
          (send-message! cfg {:chat-id chat-id
                              :text "Use the buttons to choose a payment method."
                              :message-key (str "docs-payment-method-click-" (System/currentTimeMillis))
                              :reply-markup (docs-payment-method-inline-keyboard)})

          :docs/inv-status
          (send-message! cfg {:chat-id chat-id
                              :text "Use the buttons to choose invoice status."
                              :message-key (str "docs-inv-status-click-" (System/currentTimeMillis))
                              :reply-markup (docs-invoice-status-inline-keyboard)})

          :docs/menu
          (send-message! cfg {:chat-id chat-id
                              :text "Use the buttons."
                              :message-key (str "docs-menu-click-" (System/currentTimeMillis))
                              :reply-markup (docs-menu-inline-keyboard)})

		          (send-message! cfg {:chat-id chat-id
		                              :text "Use /docs to start."
		                              :message-key (str "docs-unknown-" (System/currentTimeMillis))}))))))

(defn- edit-prompt-text
  [edit-type title]
  (case edit-type
    :edit-title (str "Send the new title for:\n" title)
    :edit-desc (str "Send the new description for:\n" title)
    :note-add (str "Send a new note for:\n" title)
    :note-edit (str "Send the updated note for your latest comment on:\n" title)
    (str "Send the update for:\n" title)))

(defn- handle-pending-edit-message
  [state chat-user chat-id text]
  (let [cfg (get-in state [:config :telegram])
        pending (get-pending-edit! chat-id)]
    (when pending
      (if (str/blank? text)
        (send-message! cfg {:chat-id chat-id
                            :text "Reply with a value or tap Cancel."
                            :message-key (str "edit-empty-" (System/currentTimeMillis))})
        (let [{:keys [task-id type]} pending
              action (case type
                       :edit-title {:action/id :cap/action/task-update
                                    :input {:task/id task-id
                                            :task/title text}}
                       :edit-desc {:action/id :cap/action/task-update
                                   :input {:task/id task-id
                                           :task/description text}}
                       :note-add {:action/id :cap/action/task-add-note
                                  :input {:task/id task-id
                                          :note/body text}}
                       :note-edit {:action/id :cap/action/task-edit-note
                                   :input {:task/id task-id
                                           :note/body text}}
                       nil)]
          (if-not action
            (do
              (take-pending-edit! chat-id)
              (send-message! cfg {:chat-id chat-id
                                  :text "Unknown edit action."
                                  :message-key (str "edit-unknown-" (System/currentTimeMillis))}))
            (let [res (actions/execute! state (assoc action :actor (actions/actor-from-telegram chat-user)))]
              (if-let [err (:error res)]
                (do
                  (take-pending-edit! chat-id)
                  (send-message! cfg {:chat-id chat-id
                                      :text (str "Unable to update: " (:message err))
                                      :message-key (str "edit-error-" (System/currentTimeMillis))}))
                (do
                  (take-pending-edit! chat-id)
                  (cond
                    (#{:edit-title :edit-desc} type)
                    (send-task-card! state chat-id (get-in res [:result :task]) {})

                    (= :note-add type)
                    (send-message! cfg {:chat-id chat-id
                                        :text "Note added to task."
                                        :message-key (str "note-add-" (System/currentTimeMillis))})

                    (= :note-edit type)
                    (send-message! cfg {:chat-id chat-id
                                        :text "Latest note updated."
                                        :message-key (str "note-edit-" (System/currentTimeMillis))})

                    :else
                    (send-message! cfg {:chat-id chat-id
                                        :text "Update saved."
                                        :message-key (str "edit-ok-" (System/currentTimeMillis))})))))))))))

(defn- start-pending-edit!
  [state chat-id chat-user task-id edit-type message-id]
  (let [cfg (get-in state [:config :telegram])
        conn (ensure-conn state)
        task (find-user-task conn (:user/id chat-user) task-id)]
    (if-not task
      (send-message! cfg {:chat-id chat-id
                          :text "Task not found."
                          :message-key (str "edit-task-missing-" (or message-id (System/currentTimeMillis)))})
      (let [title (or (:task/title task) "Task")
            prompt (edit-prompt-text edit-type title)
            send-res (send-message! cfg {:chat-id chat-id
                                         :text prompt
                                         :message-key (str "edit-prompt-" (name edit-type) "-" task-id "-" (System/currentTimeMillis))
                                         :reply-markup (pending-edit-inline-keyboard task-id)})]
        (if-let [err (:error send-res)]
          send-res
          (do
            (take-pending-reason! chat-id)
            (save-pending-edit! chat-id {:task-id task-id
                                         :type edit-type
                                         :user chat-user})
            send-res))))))

(declare parse-callback)

(defn- handle-callback
  [state chat-id {:keys [message-id callback-id data]}]
  (let [conn (ensure-conn state)
        db (when conn (d/db conn))
        chat-user (when db (user-by-chat-id db chat-id))
        parsed (parse-callback data)
        cfg (get-in state [:config :telegram])]
    (when callback-id
      (answer-callback! cfg {:callback-id callback-id}))
    (condp = (:type parsed)
      :capture/task (let [capture (take-capture! chat-id message-id)
                          actor (actions/actor-from-telegram (:user capture))]
                      (if (and capture actor)
                        (let [default-client (ensure-default-client-id state (:actor/workspace actor))
                              body (task-body (:user capture) {:title (:text capture)
                                                               :desc nil
                                                               :client-id default-client})
                              res (actions/execute! state {:action/id :cap/action/task-create
                                                           :actor actor
                                                           :input body})]
                          (if-let [err (:error res)]
                            (send-message! cfg {:chat-id chat-id
                                                :text (str "Unable to create task: " (:message err))
                                                :message-key (str "capture-create-error-" message-id)})
                            (let [task (get-in res [:result :task])]
                              (send-task-card! state chat-id task {:reply-to-message-id message-id})
                              (prompt-link-client! state chat-id task))))
                        (edit-message! cfg {:chat-id chat-id
                                            :message-id message-id
                                            :text "Capture expired."
                                            :reply-markup {:inline_keyboard []}})))
      :capture/client (let [capture (take-capture! chat-id message-id)
                            actor (actions/actor-from-telegram (:user capture))
                            name (some-> (:text capture) str/trim)]
                        (if (and capture actor (present-string? name))
                          (let [res (actions/execute! state {:action/id :cap/action/client-create
                                                             :actor actor
                                                             :input {:client/name name}})]
                            (if-let [err (:error res)]
                              (send-message! cfg {:chat-id chat-id
                                                  :text (str "Unable to create client: " (:message err))
                                                  :message-key (str "capture-client-error-" message-id)})
                              (do
                                (edit-message! cfg {:chat-id chat-id
                                                    :message-id message-id
                                                    :text (str "Client created: " name)
                                                    :reply-markup {:inline_keyboard []}})
                                (prompt-next-action! state chat-id (get-in res [:result :client])))))
                          (edit-message! cfg {:chat-id chat-id
                                              :message-id message-id
                                              :text "Capture expired."
                                              :reply-markup {:inline_keyboard []}})))
      :capture/cancel (do
                        (take-capture! chat-id message-id)
                        (edit-message! cfg {:chat-id chat-id
                                            :message-id message-id
                                            :text "Capture dismissed."
                                            :reply-markup {:inline_keyboard []}}))
      :pending/cancel (do
                         (take-pending-reason! chat-id)
                         (edit-message! cfg {:chat-id chat-id
                                             :message-id message-id
                                             :text "Pending reason cancelled."
                                             :reply-markup {:inline_keyboard []}}))
      :docs/cancel (do
                     (take-docs-session! chat-id)
                     (edit-message! cfg {:chat-id chat-id
                                         :message-id message-id
                                         :text "Docs closed."
                                         :reply-markup {:inline_keyboard []}}))
      :docs/menu (let [session (get-docs-session! chat-id)]
                   (if (and chat-user session (:client-id session))
                     (do
                       (save-docs-session! chat-id (assoc session :stage :docs/menu))
                       (edit-message! cfg {:chat-id chat-id
                                           :message-id message-id
                                           :text "Documents:"
                                           :reply-markup (docs-menu-inline-keyboard)}))
                     (prompt-docs-client-pick! state chat-id)))
      :docs/client-pick (prompt-docs-client-pick! state chat-id)
      :docs/client-set (let [cid (:client-id parsed)
                             client-id (when cid (try (UUID/fromString (str cid)) (catch Exception _ nil)))
                             client (when (and db client-id) (clients/client-by-id db client-id nil))
                             name (or (:client/name client) "Client")]
                         (if (and chat-user client-id)
                           (do
                             (save-docs-session! chat-id {:stage :docs/menu
                                                          :user chat-user
                                                          :client-id client-id})
                             (edit-message! cfg {:chat-id chat-id
                                                 :message-id message-id
                                                 :text (str "Documents for " name ":")
                                                 :reply-markup (docs-menu-inline-keyboard)}))
                           (send-message! cfg {:chat-id chat-id
                                               :text "Invalid client."
                                               :message-key (str "docs-client-invalid-" (System/currentTimeMillis))})))
      :docs/skip (let [session (get-docs-session! chat-id)]
                   (when session
                     (save-docs-session! chat-id (assoc session :stage :docs/menu))
                     (edit-message! cfg {:chat-id chat-id
                                         :message-id message-id
                                         :text "Skipped."
                                         :reply-markup (docs-menu-inline-keyboard)})))
      :docs/field (let [session (get-docs-session! chat-id)
                        field (:value parsed)]
                    (if (and chat-user session (:client-id session))
                      (case field
                        "company-name" (do
                                         (save-docs-session! chat-id (assoc session :stage :docs/field-company-name))
                                         (edit-message! cfg {:chat-id chat-id
                                                             :message-id message-id
                                                             :text "Send company name:"
                                                             :reply-markup (docs-skip-cancel-inline-keyboard)}))
                        "currency" (do
                                     (save-docs-session! chat-id (assoc session :stage :docs/field-currency))
                                     (edit-message! cfg {:chat-id chat-id
                                                         :message-id message-id
                                                         :text "Choose currency:"
                                                         :reply-markup (docs-currency-inline-keyboard)}))
                        "services" (do
                                     (save-docs-session! chat-id (assoc session :stage :docs/field-services))
                                     (edit-message! cfg {:chat-id chat-id
                                                         :message-id message-id
                                                         :text "Send services included text:"
                                                         :reply-markup (docs-skip-cancel-inline-keyboard)}))
                        "payment-plan" (do
                                         (save-docs-session! chat-id (assoc session :stage :docs/field-payment-plan))
                                         (edit-message! cfg {:chat-id chat-id
                                                             :message-id message-id
                                                             :text "Send payment plan text:"
                                                             :reply-markup (docs-skip-cancel-inline-keyboard)}))
                        "status-notes" (do
                                         (save-docs-session! chat-id (assoc session :stage :docs/field-status-notes))
                                         (edit-message! cfg {:chat-id chat-id
                                                             :message-id message-id
                                                             :text "Send status notes text:"
                                                             :reply-markup (docs-skip-cancel-inline-keyboard)}))
                        (send-message! cfg {:chat-id chat-id
                                            :text "Unknown field."
                                            :message-key (str "docs-field-unknown-" (System/currentTimeMillis))}))
                      (prompt-docs-client-pick! state chat-id)))
      :docs/currency (let [session (get-docs-session! chat-id)
                           v (:value parsed)
                           actor (actions/actor-from-telegram chat-user)]
                       (if (and chat-user session (:client-id session) (present-string? v))
                         (if (= v "other")
                           (do
                             (save-docs-session! chat-id (assoc session :stage :docs/field-currency-other))
                             (edit-message! cfg {:chat-id chat-id
                                                 :message-id message-id
                                                 :text "Send currency code (e.g. SAR):"
                                                 :reply-markup (docs-skip-cancel-inline-keyboard)}))
                           (let [res (actions/execute! state {:action/id :cap/action/doc-pack-upsert
                                                              :actor actor
                                                              :input {:client/id (:client-id session)
                                                                      :doc.pack/currency v}})]
                             (save-docs-session! chat-id (assoc session :stage :docs/menu))
                             (if-let [err (:error res)]
                               (send-message! cfg {:chat-id chat-id
                                                   :text (str "Unable to save: " (:message err))
                                                   :message-key (str "docs-currency-error-" (System/currentTimeMillis))
                                                   :reply-markup (docs-menu-inline-keyboard)})
                               (edit-message! cfg {:chat-id chat-id
                                                   :message-id message-id
                                                   :text "Saved."
                                                   :reply-markup (docs-menu-inline-keyboard)}))))
                         (send-message! cfg {:chat-id chat-id
                                             :text "Invalid currency."
                                             :message-key (str "docs-currency-invalid-" (System/currentTimeMillis))})))
      :docs/invoice-add (let [session (get-docs-session! chat-id)]
                          (if (and chat-user session (:client-id session))
                            (do
                              (save-docs-session! chat-id (assoc session :stage :docs/inv-number :draft {}))
                              (edit-message! cfg {:chat-id chat-id
                                                  :message-id message-id
                                                  :text "Send invoice number:"
                                                  :reply-markup {:inline_keyboard [[(inline-button "Cancel" "docs:menu")]]}}))
                            (prompt-docs-client-pick! state chat-id)))
      :docs/invoice-status (let [session (get-docs-session! chat-id)
                                 status-raw (:value parsed)
                                 status (when status-raw (keyword status-raw))
                                 quicks [{:id :in-7-days :label "+7 days"}
                                         {:id :in-14-days :label "+14 days"}
                                         {:id :in-30-days :label "+30 days"}]
                                 month (LocalDate/now (ZoneId/systemDefault))]
                             (if (and chat-user session (:client-id session) status (= (:stage session) :docs/inv-status))
                               (do
                                 (save-docs-session! chat-id (-> session
                                                                 (assoc :stage :docs/inv-due-date)
                                                                 (assoc :picker {:kind :docs/invoice-due
                                                                                 :text "Pick invoice due date (or No due date):"
                                                                                 :quicks quicks})
                                                                 (assoc-in [:draft :invoice/status] status)))
                                 (edit-message! cfg {:chat-id chat-id
                                                     :message-id message-id
                                                     :text "Pick invoice due date (or No due date):"
                                                     :reply-markup (date-picker-inline-keyboard {:month month
                                                                                                 :quicks quicks
                                                                                                 :allow-skip? true
                                                                                                 :skip-label "No due date"
                                                                                                 :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))
                               (send-message! cfg {:chat-id chat-id
                                                   :text "Invalid invoice status."
                                                   :message-key (str "docs-inv-status-invalid-" (System/currentTimeMillis))})))
      :docs/payment-add (let [session (get-docs-session! chat-id)]
                          (if (and chat-user session (:client-id session))
                            (do
                              (save-docs-session! chat-id (assoc session :stage :docs/payment-amount :draft {:client/id (:client-id session)}))
                              (edit-message! cfg {:chat-id chat-id
                                                  :message-id message-id
                                                  :text "Send payment amount:"
                                                  :reply-markup {:inline_keyboard [[(inline-button "Cancel" "docs:menu")]]}}))
                            (prompt-docs-client-pick! state chat-id)))
      :docs/payment-method (let [session (get-docs-session! chat-id)
                                 method-raw (:value parsed)
                                 method (when method-raw (keyword method-raw))
                                 ]
                             (if (and chat-user session (:client-id session) method)
                               (do
                                 (save-docs-session! chat-id (-> session
                                                                 (assoc :stage :docs/payment-invoice-attach)
                                                                 (assoc-in [:draft :payment/method] method)))
                                 (edit-message! cfg {:chat-id chat-id
                                                     :message-id message-id
                                                     :text "Attach this payment to an invoice?"
                                                     :reply-markup (docs-payment-invoice-attach-inline-keyboard)}))
                               (send-message! cfg {:chat-id chat-id
                                                   :text "Invalid payment method."
                                                   :message-key (str "docs-payment-method-invalid-" (System/currentTimeMillis))})))
      :docs/payment-invoice-skip (let [session (get-docs-session! chat-id)
                                       quicks [{:id :today :label "Today"}
                                               {:id :yesterday :label "Yesterday"}]
                                       month (LocalDate/now (ZoneId/systemDefault))]
                                   (if (and chat-user session (:client-id session))
                                     (do
                                       (save-docs-session! chat-id (-> session
                                                                       (assoc :stage :docs/payment-date)
                                                                       (assoc :picker {:kind :docs/payment
                                                                                       :text "Pick payment date:"
                                                                                       :quicks quicks})))
                                       (edit-message! cfg {:chat-id chat-id
                                                           :message-id message-id
                                                           :text "Pick payment date:"
                                                           :reply-markup (date-picker-inline-keyboard {:month month
                                                                                                       :quicks quicks
                                                                                                       :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))
                                     (prompt-docs-client-pick! state chat-id)))
      :docs/payment-invoice-pick (let [session (get-docs-session! chat-id)
                                       actor (actions/actor-from-telegram chat-user)]
                                   (if (and chat-user session (:client-id session))
                                     (let [res (actions/execute! state {:action/id :cap/action/invoice-list
                                                                        :actor actor
                                                                        :input {:client/id (:client-id session)}})
                                           invoices (get-in res [:result :invoices] [])
                                           kb (docs-invoice-pick-inline-keyboard invoices "docs:payment:invoice:set:%s"
                                                                                 :cancel-data "docs:menu"
                                                                                 :skip-data "docs:payment:invoice:skip")]
                                       (save-docs-session! chat-id (assoc session :stage :docs/payment-invoice-pick))
                                       (edit-message! cfg {:chat-id chat-id
                                                           :message-id message-id
                                                           :text "Pick invoice to attach:"
                                                           :reply-markup kb}))
                                     (prompt-docs-client-pick! state chat-id)))
      :docs/payment-invoice-set (let [session (get-docs-session! chat-id)
                                      raw (:invoice-id parsed)
                                      invoice-id (when raw (try (UUID/fromString (str raw)) (catch Exception _ nil)))
                                      quicks [{:id :today :label "Today"}
                                              {:id :yesterday :label "Yesterday"}]
                                      month (LocalDate/now (ZoneId/systemDefault))]
                                  (if (and chat-user session (:client-id session) invoice-id)
                                    (do
                                      (save-docs-session! chat-id (-> session
                                                                      (assoc :stage :docs/payment-date)
                                                                      (assoc :picker {:kind :docs/payment
                                                                                      :text "Pick payment date:"
                                                                                      :quicks quicks})
                                                                      (assoc-in [:draft :invoice/id] invoice-id)))
                                      (edit-message! cfg {:chat-id chat-id
                                                          :message-id message-id
                                                          :text "Pick payment date:"
                                                          :reply-markup (date-picker-inline-keyboard {:month month
                                                                                                      :quicks quicks
                                                                                                      :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))
                                    (send-message! cfg {:chat-id chat-id
                                                        :text "Invalid invoice."
                                                        :message-key (str "docs-payment-invoice-invalid-" (System/currentTimeMillis))})))
	      :docs/payment-ref-skip (let [session (get-docs-session! chat-id)]
	                               (if (and chat-user session (:client-id session))
	                                 (if-not (get-in session [:draft :payment/paid-at])
	                                   (send-message! cfg {:chat-id chat-id
	                                                       :text "Pick payment date first."
	                                                       :message-key (str "docs-payment-date-missing-" (System/currentTimeMillis))})
	                                   (do
	                                     (save-docs-session! chat-id (assoc session :stage :docs/payment-note))
	                                     (send-message! cfg {:chat-id chat-id
	                                                         :text "Payment note (optional). You can include who sent/received the money, bank details, anything:"
	                                                         :message-key (str "docs-payment-note-" (System/currentTimeMillis))
	                                                         :reply-markup (docs-payment-note-inline-keyboard)})))
	                                 (prompt-docs-client-pick! state chat-id)))
	      :docs/payment-note-skip (let [session (get-docs-session! chat-id)
	                                    draft (:draft session)
	                                    client-id (:client-id session)
	                                    paid-at (:payment/paid-at draft)
	                                    input (cond-> {:client/id client-id
	                                                   :payment/amount (:payment/amount draft)
	                                                   :payment/method (:payment/method draft)
	                                                   :payment/paid-at paid-at}
	                                            (:invoice/id draft) (assoc :invoice/id (:invoice/id draft))
	                                            (present-string? (:payment/reference draft)) (assoc :payment/reference (:payment/reference draft)))
	                                    res (when (and chat-user session (:client-id session) paid-at)
	                                          (actions/execute! state {:action/id :cap/action/payment-create
	                                                                   :actor (actions/actor-from-telegram chat-user)
	                                                                   :input input}))]
	                                (if (and chat-user session (:client-id session))
	                                  (if-not paid-at
	                                    (send-message! cfg {:chat-id chat-id
	                                                        :text "Pick payment date first."
	                                                        :message-key (str "docs-payment-date-missing-" (System/currentTimeMillis))})
	                                    (do
	                                      (save-docs-session! chat-id (assoc session :stage :docs/menu :draft nil))
	                                      (if-let [err (:error res)]
	                                        (send-message! cfg {:chat-id chat-id
	                                                            :text (str "Unable to add payment: " (:message err))
	                                                            :message-key (str "docs-payment-create-error-" (System/currentTimeMillis))
	                                                            :reply-markup (docs-menu-inline-keyboard)})
	                                        (send-message! cfg {:chat-id chat-id
	                                                            :text "Payment added."
	                                                            :message-key (str "docs-payment-added-" (System/currentTimeMillis))
	                                                            :reply-markup (docs-menu-inline-keyboard)}))))
	                                  (prompt-docs-client-pick! state chat-id)))
      :docs/generate (let [session (get-docs-session! chat-id)
                           client-id (:client-id session)
                           actor (actions/actor-from-telegram chat-user)
                           type (:value parsed)
                           action-id (case type
                                       :proposal :cap/action/proposal-generate
                                       :status-report :cap/action/status-report-generate
                                       nil)]
                       (if (and chat-user session client-id action-id)
                         (let [res (actions/execute! state {:action/id action-id
                                                            :actor actor
                                                            :input {:client/id client-id}})
                               file (get-in res [:result :file])
                               send-res (when (and file (not (:error res)))
                                          (docs-send-file! state chat-id actor file :caption (case type
                                                                                               :proposal "Proposal"
                                                                                               :status-report "Status report"
                                                                                               "Document")))]
                           (save-docs-session! chat-id (assoc session :stage :docs/menu))
                           (if-let [err (:error res)]
                             (send-message! cfg {:chat-id chat-id
                                                 :text (str "Unable to generate: " (:message err))
                                                 :message-key (str "docs-generate-error-" (System/currentTimeMillis))
                                                 :reply-markup (docs-menu-inline-keyboard)})
                             (if-let [send-err (:error send-res)]
                               (send-message! cfg {:chat-id chat-id
                                                   :text (str "Generated, but could not send PDF: " send-err)
                                                   :message-key (str "docs-generate-send-error-" (System/currentTimeMillis))
                                                   :reply-markup (docs-menu-inline-keyboard)})
                               (send-message! cfg {:chat-id chat-id
                                                   :text "PDF sent."
                                                   :message-key (str "docs-generate-ok-" (System/currentTimeMillis))
                                                   :reply-markup (docs-menu-inline-keyboard)}))))
                         (prompt-docs-client-pick! state chat-id)))
      :docs/generate-invoice-pick (let [session (get-docs-session! chat-id)
                                        actor (actions/actor-from-telegram chat-user)]
                                    (if (and chat-user session (:client-id session))
                                      (let [res (actions/execute! state {:action/id :cap/action/invoice-list
                                                                         :actor actor
                                                                         :input {:client/id (:client-id session)}})
                                            invoices (get-in res [:result :invoices] [])
                                            kb (docs-invoice-pick-inline-keyboard invoices "docs:generate:invoice:set:%s"
                                                                                  :cancel-data "docs:menu")]
                                        (save-docs-session! chat-id (assoc session :stage :docs/generate-invoice-pick))
                                        (edit-message! cfg {:chat-id chat-id
                                                            :message-id message-id
                                                            :text "Pick invoice:"
                                                            :reply-markup kb}))
                                      (prompt-docs-client-pick! state chat-id)))
      :docs/generate-invoice-set (let [session (get-docs-session! chat-id)
                                       raw (:invoice-id parsed)
                                       invoice-id (when raw (try (UUID/fromString (str raw)) (catch Exception _ nil)))
                                       actor (actions/actor-from-telegram chat-user)]
                                   (if (and chat-user session (:client-id session) invoice-id)
                                     (let [res (actions/execute! state {:action/id :cap/action/invoice-pdf-generate
                                                                        :actor actor
                                                                        :input {:client/id (:client-id session)
                                                                                :invoice/id invoice-id}})
                                           file (get-in res [:result :file])
                                           send-res (when (and file (not (:error res)))
                                                      (docs-send-file! state chat-id actor file :caption "Invoice"))]
                                       (save-docs-session! chat-id (assoc session :stage :docs/menu))
                                       (if-let [err (:error res)]
                                         (send-message! cfg {:chat-id chat-id
                                                             :text (str "Unable to generate invoice PDF: " (:message err))
                                                             :message-key (str "docs-invoice-pdf-error-" (System/currentTimeMillis))
                                                             :reply-markup (docs-menu-inline-keyboard)})
                                         (if-let [send-err (:error send-res)]
                                           (send-message! cfg {:chat-id chat-id
                                                               :text (str "Generated, but could not send PDF: " send-err)
                                                               :message-key (str "docs-invoice-pdf-send-error-" (System/currentTimeMillis))
                                                               :reply-markup (docs-menu-inline-keyboard)})
                                           (send-message! cfg {:chat-id chat-id
                                                               :text "PDF sent."
                                                               :message-key (str "docs-invoice-pdf-ok-" (System/currentTimeMillis))
                                                               :reply-markup (docs-menu-inline-keyboard)}))))
                                     (send-message! cfg {:chat-id chat-id
                                                         :text "Invalid invoice."
                                                         :message-key (str "docs-invoice-invalid-" (System/currentTimeMillis))})))
      :docs/generate-receipt-pick (let [session (get-docs-session! chat-id)
                                        actor (actions/actor-from-telegram chat-user)]
                                    (if (and chat-user session (:client-id session))
                                      (let [res (actions/execute! state {:action/id :cap/action/payment-list
                                                                         :actor actor
                                                                         :input {:client/id (:client-id session)}})
                                            payments (get-in res [:result :payments] [])
                                            kb (docs-payment-pick-inline-keyboard payments "docs:generate:receipt:set:%s"
                                                                                  :cancel-data "docs:menu")]
                                        (save-docs-session! chat-id (assoc session :stage :docs/generate-receipt-pick))
                                        (edit-message! cfg {:chat-id chat-id
                                                            :message-id message-id
                                                            :text "Pick payment:"
                                                            :reply-markup kb}))
                                      (prompt-docs-client-pick! state chat-id)))
      :docs/generate-receipt-set (let [session (get-docs-session! chat-id)
                                       raw (:payment-id parsed)
                                       payment-id (when raw (try (UUID/fromString (str raw)) (catch Exception _ nil)))
                                       actor (actions/actor-from-telegram chat-user)]
                                   (if (and chat-user session (:client-id session) payment-id)
                                     (let [res (actions/execute! state {:action/id :cap/action/receipt-generate
                                                                        :actor actor
                                                                        :input {:client/id (:client-id session)
                                                                                :payment/id payment-id}})
                                           file (get-in res [:result :file])
                                           send-res (when (and file (not (:error res)))
                                                      (docs-send-file! state chat-id actor file :caption "Receipt"))]
                                       (save-docs-session! chat-id (assoc session :stage :docs/menu))
                                       (if-let [err (:error res)]
                                         (send-message! cfg {:chat-id chat-id
                                                             :text (str "Unable to generate receipt PDF: " (:message err))
                                                             :message-key (str "docs-receipt-error-" (System/currentTimeMillis))
                                                             :reply-markup (docs-menu-inline-keyboard)})
                                         (if-let [send-err (:error send-res)]
                                           (send-message! cfg {:chat-id chat-id
                                                               :text (str "Generated, but could not send PDF: " send-err)
                                                               :message-key (str "docs-receipt-send-error-" (System/currentTimeMillis))
                                                               :reply-markup (docs-menu-inline-keyboard)})
                                           (send-message! cfg {:chat-id chat-id
                                                               :text "PDF sent."
                                                               :message-key (str "docs-receipt-ok-" (System/currentTimeMillis))
                                                               :reply-markup (docs-menu-inline-keyboard)}))))
                                     (send-message! cfg {:chat-id chat-id
                                                         :text "Invalid payment."
                                                         :message-key (str "docs-payment-invalid-" (System/currentTimeMillis))})))
      :date-picker/noop nil
      :date-picker/nav (let [ym (:value parsed)
                             month (or (parse-ym ym) (LocalDate/now (ZoneId/systemDefault)))
                             pending (get-pending-reason! chat-id)
                             session (get-docs-session! chat-id)]
                         (cond
                           (and pending (= (:stage pending) :followup-date-picker))
                           (let [tid (str (:task-id pending))
                                 text (get-in pending [:picker :text] "Pick the follow-up date:")
                                 quicks (or (get-in pending [:picker :quicks])
                                            [{:id :tomorrow :label "Tomorrow"}
                                             {:id :in-3-days :label "In 3 days"}
                                             {:id :next-week :label "Next week"}])]
                             (edit-message! cfg {:chat-id chat-id
                                                 :message-id message-id
                                                 :text text
                                                 :reply-markup (date-picker-inline-keyboard {:month month
                                                                                             :quicks quicks
                                                                                             :extra-rows [[(inline-button "Cancel" (str "pending:cancel:" tid))]]})}))
                           (and session (= (:stage session) :docs/payment-date))
                           (let [text (get-in session [:picker :text] "Pick payment date:")
                                 quicks (or (get-in session [:picker :quicks])
                                            [{:id :today :label "Today"}
                                             {:id :yesterday :label "Yesterday"}])]
                             (edit-message! cfg {:chat-id chat-id
                                                 :message-id message-id
                                                 :text text
                                                 :reply-markup (date-picker-inline-keyboard {:month month
                                                                                             :quicks quicks
                                                                                             :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))
                           (and session (= (:stage session) :docs/inv-due-date))
                           (let [text (get-in session [:picker :text] "Pick invoice due date:")
                                 quicks (or (get-in session [:picker :quicks])
                                            [{:id :in-7-days :label "+7 days"}
                                             {:id :in-14-days :label "+14 days"}
                                             {:id :in-30-days :label "+30 days"}])]
                             (edit-message! cfg {:chat-id chat-id
                                                 :message-id message-id
                                                 :text text
                                                 :reply-markup (date-picker-inline-keyboard {:month month
                                                                                             :quicks quicks
                                                                                             :allow-skip? true
                                                                                             :skip-label "No due date"
                                                                                             :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))
                           :else nil))
      :date-picker/day (let [ymd (:value parsed)
                             picked (parse-ymd->date ymd)
                             pending (get-pending-reason! chat-id)
                             session (get-docs-session! chat-id)]
                         (cond
                           (and picked pending (= (:stage pending) :followup-date-picker) (present-string? (:reason pending)))
                           (let [task-id (:task-id pending)
                                 res (actions/execute! state {:action/id :cap/action/task-set-status
                                                              :actor (actions/actor-from-telegram chat-user)
                                                              :input {:task/id task-id
                                                                      :task/status :pending
                                                                      :note/body (:reason pending)
                                                                      :note/last-contact (now-inst)
                                                                      :note/next-followup picked}})]
                             (take-pending-reason! chat-id)
                             (if-let [err (:error res)]
                               (send-message! cfg {:chat-id chat-id
                                                   :text (str "Unable to set pending: " (:message err))
                                                   :message-key (str "pending-followup-error-" (System/currentTimeMillis))})
                               (do
                                 (edit-message! cfg {:chat-id chat-id
                                                     :message-id message-id
                                                     :text (str "Follow-up date set: " ymd)
                                                     :reply-markup {:inline_keyboard []}})
                                 (send-task-card! state chat-id (get-in res [:result :task]) {}))))
	                           (and picked session (= (:stage session) :docs/payment-date))
	                           (do
	                             (save-docs-session! chat-id (-> session
	                                                             (assoc :stage :docs/payment-time)
	                                                             (update :draft dissoc :payment/paid-at :payment/paid-date)
	                                                             (assoc-in [:draft :payment/paid-date] ymd)))
	                             (edit-message! cfg {:chat-id chat-id
	                                                 :message-id message-id
	                                                 :text "Pick payment time (optional):"
	                                                 :reply-markup (time-picker-inline-keyboard {:allow-skip? true
	                                                                                            :skip-label "Skip time"
	                                                                                            :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))
	                           (and picked session (= (:stage session) :docs/inv-due-date))
	                           (do
	                             (save-docs-session! chat-id (-> session
	                                                             (assoc :stage :docs/inv-due-time)
	                                                             (update :draft dissoc :invoice/due-at :invoice/due-date)
	                                                             (assoc-in [:draft :invoice/due-date] ymd)))
	                             (edit-message! cfg {:chat-id chat-id
	                                                 :message-id message-id
	                                                 :text "Pick invoice due time (optional):"
	                                                 :reply-markup (time-picker-inline-keyboard {:allow-skip? true
	                                                                                            :skip-label "Skip time"
	                                                                                            :extra-rows [[(inline-button "Cancel" "docs:menu")]]})}))
	                           :else nil))
      :date-picker/quick (let [quick-id (some-> (:value parsed) keyword)
                               today (LocalDate/now (ZoneId/systemDefault))
                               pending (get-pending-reason! chat-id)
                               session (get-docs-session! chat-id)
                               picked-day (cond
                                            (and pending (= (:stage pending) :followup-date-picker))
                                            (case quick-id
                                              :tomorrow (.plusDays today 1)
                                              :in-3-days (.plusDays today 3)
                                              :next-week (.plusDays today 7)
                                              nil)
                                            (and session (= (:stage session) :docs/payment-date))
                                            (case quick-id
                                              :today today
                                              :yesterday (.minusDays today 1)
                                              nil)
                                            (and session (= (:stage session) :docs/inv-due-date))
                                            (case quick-id
                                              :in-7-days (.plusDays today 7)
                                              :in-14-days (.plusDays today 14)
                                              :in-30-days (.plusDays today 30)
                                              nil)
                                            :else nil)
                               ymd (when picked-day (yyyy-mm-dd picked-day))]
                           (when ymd
                             (handle-callback state chat-id {:message-id message-id
                                                            :callback-id nil
                                                            :data (str "dp:day:" ymd)})))
      :date-picker/skip (let [pending (get-pending-reason! chat-id)]
                          (when (and pending (= (:stage pending) :followup-date-picker) (present-string? (:reason pending)))
                            (let [task-id (:task-id pending)
                                  res (actions/execute! state {:action/id :cap/action/task-set-status
                                                               :actor (actions/actor-from-telegram chat-user)
                                                               :input {:task/id task-id
                                                                       :task/status :pending
                                                                       :note/body (:reason pending)
                                                                       :note/last-contact (now-inst)}})]
                              (take-pending-reason! chat-id)
                              (if-let [err (:error res)]
                                (send-message! cfg {:chat-id chat-id
                                                    :text (str "Unable to set pending: " (:message err))
                                                    :message-key (str "pending-followup-error-" (System/currentTimeMillis))})
                                (do
                                  (edit-message! cfg {:chat-id chat-id
                                                      :message-id message-id
                                                      :text "Follow-up set without a date."
                                                      :reply-markup {:inline_keyboard []}})
                                  (send-task-card! state chat-id (get-in res [:result :task]) {})))))
                          (let [session (get-docs-session! chat-id)]
                            (when (and session (= (:stage session) :docs/inv-due-date))
                              (let [draft (:draft session)
                                    actor (actions/actor-from-telegram chat-user)
                                    input (cond-> {:client/id (:client-id session)
                                                   :invoice/number (:invoice/number draft)
                                                   :invoice/total-amount (:invoice/total-amount draft)
                                                   :invoice/status (:invoice/status draft)}
                                            (nil? (:invoice/status draft)) (dissoc :invoice/status))
                                    res (actions/execute! state {:action/id :cap/action/invoice-create
                                                                 :actor actor
                                                                 :input input})]
                                (save-docs-session! chat-id (assoc session :stage :docs/menu :draft nil))
                                (if-let [err (:error res)]
                                  (send-message! cfg {:chat-id chat-id
                                                      :text (str "Unable to add invoice: " (:message err))
                                                      :message-key (str "docs-inv-create-error-" (System/currentTimeMillis))
                                                      :reply-markup (docs-menu-inline-keyboard)})
                                  (do
                                    (edit-message! cfg {:chat-id chat-id
                                                        :message-id message-id
                                                        :text "No due date set."
                                                        :reply-markup {:inline_keyboard []}})
		                                    (send-message! cfg {:chat-id chat-id
		                                                        :text "Invoice added."
		                                                        :message-key (str "docs-inv-added-" (System/currentTimeMillis))
		                                                        :reply-markup (docs-menu-inline-keyboard)}))))))
                          )
      :time-picker/now (let [now-time (.toLocalTime (java.time.ZonedDateTime/now (ZoneId/systemDefault)))
                             raw (.format now-time (java.time.format.DateTimeFormatter/ofPattern "HHmm"))]
                         (apply-docs-time-picker-set! state {:chat-id chat-id
                                                             :chat-user chat-user
                                                             :message-id message-id
                                                             :raw raw}))
      :time-picker/hour (let [session (get-docs-session! chat-id)
                              raw (:value parsed)
                              hour (when (re-matches #"\d{2}" (str raw))
                                     (try (Integer/parseInt (str raw)) (catch Exception _ nil)))]
                          (when (and session (number? hour) (<= 0 hour 23)
                                     (#{:docs/payment-time :docs/inv-due-time} (:stage session)))
                            (edit-message! cfg {:chat-id chat-id
                                                :message-id message-id
                                                :text (str "Pick minutes for " (format "%02d" (int hour)) ":")
                                                :reply-markup (time-picker-minutes-inline-keyboard {:hour hour
                                                                                                    :allow-skip? true
                                                                                                    :skip-label "Skip time"
                                                                                                    :extra-rows [[(inline-button "Cancel" "docs:menu")]]})})))
      :time-picker/back (let [session (get-docs-session! chat-id)]
                          (when (and session (#{:docs/payment-time :docs/inv-due-time} (:stage session)))
                            (edit-message! cfg {:chat-id chat-id
                                                :message-id message-id
                                                :text "Pick time (optional):"
                                                :reply-markup (time-picker-inline-keyboard {:allow-skip? true
                                                                                            :skip-label "Skip time"
                                                                                            :extra-rows [[(inline-button "Cancel" "docs:menu")]]})})))
      :time-picker/set (apply-docs-time-picker-set! state {:chat-id chat-id
                                                           :chat-user chat-user
                                                           :message-id message-id
                                                           :raw (:value parsed)})
      :time-picker/skip (apply-docs-time-picker-set! state {:chat-id chat-id
                                                            :chat-user chat-user
                                                            :message-id message-id
                                                            :raw "0000"})
		      :pending/reason (let [tid (:task-id parsed)
		                            task-id (try (UUID/fromString tid) (catch Exception _ nil))
		                            reason-id (:value parsed)
		                            current (get-pending-reason! chat-id)]
                        (if (and chat-user task-id (or (nil? current) (= (:task-id current) task-id)))
                          (if (= "custom" reason-id)
                            (let [task (find-user-task conn (:user/id chat-user) task-id)
                                  title (or (:task/title task) "Task")
                                  prompt (str "Send a pending reason for:\n" title)]
                              (take-pending-edit! chat-id)
                              (save-pending-reason! chat-id {:task-id task-id
                                                             :user chat-user
                                                             :stage :reason})
                              (send-message! cfg {:chat-id chat-id
                                                  :text prompt
                                                  :message-key (str "pending-custom-" tid)
                                                  :reply-markup (pending-reason-inline-keyboard tid)}))
                            (let [reason (pending-reason-text reason-id)]
                              (if (str/blank? (str reason))
                                (send-message! cfg {:chat-id chat-id
                                                    :text "Choose a valid pending reason."
                                                    :message-key (str "pending-reason-invalid-" tid)})
                                (do
                                  (save-pending-reason! chat-id {:task-id task-id
                                                                 :user chat-user
                                                                 :stage :followup
                                                                 :reason reason})
                                  (send-message! cfg {:chat-id chat-id
                                                      :text "When should I follow up?"
                                                      :message-key (str "pending-followup-" tid)
                                                      :reply-markup (pending-followup-inline-keyboard tid)})))))
                          (send-message! cfg {:chat-id chat-id
                                              :text "Pending reason expired."
                                              :message-key (str "pending-reason-expired-" (System/currentTimeMillis))})))
      :pending/followup (let [tid (:task-id parsed)
                              task-id (try (UUID/fromString tid) (catch Exception _ nil))
                              followup-id (:value parsed)
                              pending (get-pending-reason! chat-id)]
                          (if (and chat-user task-id pending (= (:task-id pending) task-id))
                            (if (present-string? (:reason pending))
                              (if (= "pick-date" followup-id)
                                (do
                                  (let [text "Pick the follow-up date:"
                                        quicks [{:id :tomorrow :label "Tomorrow"}
                                                {:id :in-3-days :label "In 3 days"}
                                                {:id :next-week :label "Next week"}]]
                                    (save-pending-reason! chat-id (assoc pending
                                                                         :stage :followup-date-picker
                                                                         :picker {:kind :pending/followup
                                                                                  :text text
                                                                                  :quicks quicks}))
                                    (send-message! cfg {:chat-id chat-id
                                                        :text text
                                                        :message-key (str "pending-followup-date-" tid)
                                                        :reply-markup (date-picker-inline-keyboard {:month (LocalDate/now (ZoneId/systemDefault))
                                                                                                    :quicks quicks
                                                                                                    :extra-rows [[(inline-button "Cancel" (str "pending:cancel:" tid))]]})})))
                                (let [followup (followup-date (keyword followup-id))
                                      res (actions/execute! state {:action/id :cap/action/task-set-status
                                                                   :actor (actions/actor-from-telegram chat-user)
                                                                   :input (cond-> {:task/id task-id
                                                                                   :task/status :pending
                                                                                   :note/body (:reason pending)
                                                                                   :note/last-contact (now-inst)}
                                                                            followup (assoc :note/next-followup followup))})]
                                  (take-pending-reason! chat-id)
                                  (if-let [err (:error res)]
                                    (send-message! cfg {:chat-id chat-id
                                                        :text (str "Unable to set pending: " (:message err))
                                                        :message-key (str "pending-followup-error-" tid)})
                                    (send-task-card! state chat-id (get-in res [:result :task]) {:reply-to-message-id message-id}))))
                              (send-message! cfg {:chat-id chat-id
                                                  :text "Pending reason missing. Start again."
                                                  :message-key (str "pending-reason-missing-" (System/currentTimeMillis))}))
                            (send-message! cfg {:chat-id chat-id
                                                :text "Pending reason expired."
                                                :message-key (str "pending-followup-expired-" (System/currentTimeMillis))})))
      :task/edit-cancel (do
                          (take-pending-edit! chat-id)
                          (edit-message! cfg {:chat-id chat-id
                                              :message-id message-id
                                              :text "Edit cancelled."
                                              :reply-markup {:inline_keyboard []}}))
      :task/edit-title (let [tid (:task-id parsed)
                             task-id (try (UUID/fromString tid) (catch Exception _ nil))]
                         (if (and chat-user task-id)
                           (start-pending-edit! state chat-id chat-user task-id :edit-title message-id)
                           (send-message! cfg {:chat-id chat-id
                                               :text "Cannot edit task."
                                               :message-key (str "task-edit-title-error-" (System/currentTimeMillis))})))
      :task/edit-desc (let [tid (:task-id parsed)
                            task-id (try (UUID/fromString tid) (catch Exception _ nil))]
                        (if (and chat-user task-id)
                          (start-pending-edit! state chat-id chat-user task-id :edit-desc message-id)
                          (send-message! cfg {:chat-id chat-id
                                              :text "Cannot edit task."
                                              :message-key (str "task-edit-desc-error-" (System/currentTimeMillis))})))
      :task/note-add (let [tid (:task-id parsed)
                           task-id (try (UUID/fromString tid) (catch Exception _ nil))]
                       (if (and chat-user task-id)
                         (start-pending-edit! state chat-id chat-user task-id :note-add message-id)
                         (send-message! cfg {:chat-id chat-id
                                             :text "Cannot add note."
                                             :message-key (str "task-note-add-error-" (System/currentTimeMillis))})))
      :task/note-edit (let [tid (:task-id parsed)
                            task-id (try (UUID/fromString tid) (catch Exception _ nil))]
                        (if (and chat-user task-id)
                          (start-pending-edit! state chat-id chat-user task-id :note-edit message-id)
                          (send-message! cfg {:chat-id chat-id
                                              :text "Cannot edit note."
                                              :message-key (str "task-note-edit-error-" (System/currentTimeMillis))})))
      :task/note-delete (let [tid (:task-id parsed)
                              task-id (try (UUID/fromString tid) (catch Exception _ nil))]
                          (if (and chat-user task-id)
                            (let [res (actions/execute! state {:action/id :cap/action/task-delete-note
                                                               :actor (actions/actor-from-telegram chat-user)
                                                               :input {:task/id task-id}})]
                              (if-let [err (:error res)]
                                (send-message! cfg {:chat-id chat-id
                                                    :text (str "Unable to delete note: " (:message err))
                                                    :message-key (str "task-note-delete-error-" (System/currentTimeMillis))})
                                (send-message! cfg {:chat-id chat-id
                                                    :text "Latest note deleted."
                                                    :message-key (str "task-note-delete-" (System/currentTimeMillis))})))
                            (send-message! cfg {:chat-id chat-id
                                                :text "Cannot delete note."
                                                :message-key (str "task-note-delete-invalid-" (System/currentTimeMillis))})))
      :task/delete (let [tid (:task-id parsed)
                         task-id (try (UUID/fromString tid) (catch Exception _ nil))]
                     (if (and chat-user task-id)
                       (let [res (actions/execute! state {:action/id :cap/action/task-delete
                                                          :actor (actions/actor-from-telegram chat-user)
                                                          :input {:task/id task-id}})]
                         (if-let [err (:error res)]
                           (send-message! cfg {:chat-id chat-id
                                               :text (str "Unable to delete task: " (:message err))
                                               :message-key (str "task-delete-error-" (System/currentTimeMillis))})
                           (if message-id
                             (edit-message! cfg {:chat-id chat-id
                                                 :message-id message-id
                                                 :text "Task deleted."
                                                 :reply-markup {:inline_keyboard []}})
                             (send-message! cfg {:chat-id chat-id
                                                 :text "Task deleted."
                                                 :message-key (str "task-delete-" (System/currentTimeMillis))}))))
                       (send-message! cfg {:chat-id chat-id
                                           :text "Cannot delete task."
                                           :message-key (str "task-delete-invalid-" (System/currentTimeMillis))})))
      :task/client-pick (let [tid (:task-id parsed)
                              task-id (try (UUID/fromString tid) (catch Exception _ nil))]
                          (if (and chat-user task-id)
                            (prompt-client-pick! state chat-id (str task-id))
                            (send-message! cfg {:chat-id chat-id
                                                :text "Cannot pick client."
                                                :message-key (str "task-client-pick-error-" (System/currentTimeMillis))})))
      :task/client-create (let [tid (:task-id parsed)
                                task-id (try (UUID/fromString tid) (catch Exception _ nil))]
                            (if (and chat-user task-id)
                              (let [task (find-user-task conn (:user/id chat-user) task-id)
                                    title (or (:task/title task) "Task")
                                    prompt (str "Send a client name to link for:\n" title)]
                                (save-pending-client! chat-id {:task-id task-id
                                                               :user chat-user
                                                               :mode :link})
                                (send-message! cfg {:chat-id chat-id
                                                    :text prompt
                                                    :message-key (str "task-client-create-" tid)
                                                    :reply-markup (client-cancel-inline-keyboard tid)}))
                              (send-message! cfg {:chat-id chat-id
                                                  :text "Cannot create client."
                                                  :message-key (str "task-client-create-error-" (System/currentTimeMillis))})))
      :task/client-skip (do
                          (take-pending-client! chat-id)
                          (send-message! cfg {:chat-id chat-id
                                              :text "Client link skipped."
                                              :message-key (str "task-client-skip-" (System/currentTimeMillis))}))
      :task/client-cancel (do
                            (take-pending-client! chat-id)
                            (edit-message! cfg {:chat-id chat-id
                                                :message-id message-id
                                                :text "Client link cancelled."
                                                :reply-markup {:inline_keyboard []}}))
      :task/client-set (let [tid (:task-id parsed)
                             task-id (try (UUID/fromString tid) (catch Exception _ nil))
                             cid (:client-id parsed)
                             client-id (try (UUID/fromString (str cid)) (catch Exception _ nil))]
                         (if (and chat-user task-id client-id)
                           (let [res (actions/execute! state {:action/id :cap/action/task-set-client
                                                              :actor (actions/actor-from-telegram chat-user)
                                                              :input {:task/id task-id
                                                                      :task/client client-id}})]
                             (if-let [err (:error res)]
                               (send-message! cfg {:chat-id chat-id
                                                   :text (str "Unable to link client: " (:message err))
                                                   :message-key (str "task-client-set-error-" (System/currentTimeMillis))})
                               (send-task-card! state chat-id (get-in res [:result :task]) {:reply-to-message-id message-id})))
                           (send-message! cfg {:chat-id chat-id
                                               :text "Invalid client selection."
                                               :message-key (str "task-client-set-invalid-" (System/currentTimeMillis))})))
      :tasks/filter (let [filters (or (get-task-list! chat-id message-id)
                                      {:status nil :archived :active :limit tasks-list-limit})
                          new-filters (case (:filter parsed)
                                        :status (assoc filters :status (:value parsed))
                                        :archived (assoc filters :archived (:value parsed))
                                        :refresh filters
                                        filters)]
                      (if chat-user
                        (let [resp (list-user-tasks conn (:user/id chat-user) new-filters)
                              tasks (:tasks resp)
                              pending-reasons (into {}
                                                    (keep (fn [task]
                                                            (when-let [reason (pending-reason-for-task db task)]
                                                              [(:task/id task) reason])))
                                                    tasks)
                              text (tasks-summary-text tasks pending-reasons)
                              header (str "Tasks"
                                          (when-let [status (:status new-filters)]
                                            (str " • " (name status)))
                                          (case (:archived new-filters)
                                            :archived " • archived"
                                            :all " • all"
                                            ""))
                              body (if (seq tasks) (str header "\n" text) (str header "\nNo tasks found."))
                              keyboard (tasks-list-keyboard tasks new-filters)]
                          (save-task-list! chat-id message-id new-filters)
                          (edit-message! cfg {:chat-id chat-id
                                              :message-id message-id
                                              :text body
                                              :reply-markup keyboard}))
                        (send-message! cfg {:chat-id chat-id
                                            :text "Chat not linked."
                                            :message-key (str "tasks-filter-unlinked-" message-id)})))
      :task/view (let [tid (:task-id parsed)
                       task-id (try (UUID/fromString tid) (catch Exception _ nil))]
                   (if (and chat-user task-id)
                     (if-let [task (find-user-task conn (:user/id chat-user) task-id)]
                       (send-task-card! state chat-id task {:reply-to-message-id message-id})
                       (send-message! cfg {:chat-id chat-id
                                           :text "Task not found."
                                           :message-key (str "task-not-found-" tid)}))
                     (send-message! cfg {:chat-id chat-id
                                         :text "Cannot view task."
                                         :message-key (str "task-view-error-" (System/currentTimeMillis))})))
      :task/status (let [tid (:task-id parsed)
                         task-id (try (UUID/fromString tid) (catch Exception _ nil))
                         new-status (keyword (:value parsed))]
                     (if (and chat-user task-id new-status)
                       (if (= new-status :pending)
                         (if-let [task (find-user-task conn (:user/id chat-user) task-id)]
                           (let [title (or (:task/title task) "Task")
                                 prompt (str "Why is this pending?\n" title)
                                 send-res (send-message! cfg {:chat-id chat-id
                                                              :text prompt
                                                              :message-key (str "pending-reason-" tid)
                                                              :reply-markup (pending-reason-inline-keyboard tid)})]
                             (when-not (:error send-res)
                               (take-pending-edit! chat-id)
                               (save-pending-reason! chat-id {:task-id task-id
                                                              :user chat-user
                                                              :stage :reason}))
                             send-res)
                           (send-message! cfg {:chat-id chat-id
                                               :text "Task not found."
                                               :message-key (str "pending-task-missing-" tid)}))
                         (let [res (actions/execute! state {:action/id :cap/action/task-set-status
                                                            :actor (actions/actor-from-telegram chat-user)
                                                            :input {:task/id task-id
                                                                    :task/status new-status}})]
                           (if-let [err (:error res)]
                             (send-message! cfg {:chat-id chat-id
                                                 :text (str "Unable to update status: " (:message err))
                                                 :message-key (str "task-status-error-" tid)})
                             (send-task-card! state chat-id (get-in res [:result :task]) {:reply-to-message-id message-id}))))
                       (send-message! cfg {:chat-id chat-id
                                           :text "Invalid status action."
                                           :message-key (str "task-status-invalid-" (System/currentTimeMillis))})))
      :task/archive (let [tid (:task-id parsed)
                          task-id (try (UUID/fromString tid) (catch Exception _ nil))
                          archived? (= "true" (:value parsed))]
                      (if (and chat-user task-id (some? archived?))
                        (let [res (actions/execute! state {:action/id :cap/action/task-archive
                                                           :actor (actions/actor-from-telegram chat-user)
                                                           :input {:task/id task-id
                                                                   :task/archived? archived?}})]
                          (if-let [err (:error res)]
                            (send-message! cfg {:chat-id chat-id
                                                :text (str "Unable to update archive: " (:message err))
                                                :message-key (str "task-archive-error-" tid)})
                            (send-task-card! state chat-id (get-in res [:result :task]) {:reply-to-message-id message-id})))
                        (send-message! cfg {:chat-id chat-id
                                            :text "Invalid archive action."
                                            :message-key (str "task-archive-invalid-" (System/currentTimeMillis))})))
      :client/action (let [cid (:client-id parsed)
                           client-id (try (UUID/fromString (str cid)) (catch Exception _ nil))
                           action-key (:value parsed)
                           client (when (and client-id db) (clients/client-by-id db client-id nil))
                           name (or (:client/name client) "client")]
                       (if (and chat-user client-id action-key)
                         (case action-key
                           "custom" (do
                                      (save-pending-next-action! chat-id {:client-id client-id
                                                                          :user chat-user
                                                                          :client-name name})
                                      (send-message! cfg {:chat-id chat-id
                                                          :text (str "Send the next action for " name ".")
                                                          :message-key (str "client-custom-" (System/currentTimeMillis))
                                                          :reply-markup (client-action-cancel-inline-keyboard client-id)}))
                           "dismiss" (do
                                       (take-pending-next-action! chat-id)
                                       (send-message! cfg {:chat-id chat-id
                                                           :text "Next action skipped."
                                                           :message-key (str "client-dismiss-" (System/currentTimeMillis))}))
                           (let [[title desc] (case action-key
                                                "call" [(str "Call " name) (str "Call " name)]
                                                "docs" [(str "Request docs from " name) (str "Request docs from " name)]
                                                "followup" [(str "Follow up with " name) (str "Follow up with " name)]
                                                "meeting" [(str "Schedule meeting with " name) (str "Schedule meeting with " name)]
                                                [(str "Follow up with " name) (str "Follow up with " name)])
                                 body (task-body chat-user {:title title
                                                            :desc desc
                                                            :client-id client-id})
                                 res (actions/execute! state {:action/id :cap/action/task-create
                                                              :actor (actions/actor-from-telegram chat-user)
                                                              :input body})]
                             (if-let [err (:error res)]
                               (send-message! cfg {:chat-id chat-id
                                                   :text (str "Unable to create task: " (:message err))
                                                   :message-key (str "client-action-error-" (System/currentTimeMillis))})
                               (send-task-card! state chat-id (get-in res [:result :task]) {:reply-to-message-id message-id}))))
                         (send-message! cfg {:chat-id chat-id
                                             :text "Invalid client action."
                                                :message-key (str "client-action-invalid-" (System/currentTimeMillis))})))
		      (do
		        (log/warn "Unhandled telegram callback" {:data data :parsed parsed :chat-id chat-id})
		        nil))))
(defn- parse-callback
  [data]
  (when (present-string? data)
    (let [parts (str/split data #":")]
      (case (first parts)
        "dp"
        (case (second parts)
          "noop" {:type :date-picker/noop}
          "nav" {:type :date-picker/nav
                 :value (nth parts 2 nil)}
          "day" {:type :date-picker/day
                 :value (nth parts 2 nil)}
          "quick" {:type :date-picker/quick
                   :value (nth parts 2 nil)}
          "skip" {:type :date-picker/skip}
          nil)
        "tp"
        (case (second parts)
          "now" {:type :time-picker/now}
          "hour" {:type :time-picker/hour
                  :value (nth parts 2 nil)}
          "back" {:type :time-picker/back}
          "set" {:type :time-picker/set
                 :value (nth parts 2 nil)}
          "skip" {:type :time-picker/skip}
          nil)
        "docs"
        (case (second parts)
          "cancel" {:type :docs/cancel}
          "menu" {:type :docs/menu}
          "skip" {:type :docs/skip}
          "client" (case (nth parts 2 nil)
                     "set" {:type :docs/client-set
                            :client-id (nth parts 3 nil)}
                     "pick" {:type :docs/client-pick}
                     nil)
          "field" {:type :docs/field
                   :value (nth parts 2 nil)}
          "currency" {:type :docs/currency
                      :value (nth parts 2 nil)}
          "invoice" (case (nth parts 2 nil)
                      "add" {:type :docs/invoice-add}
                      "status" {:type :docs/invoice-status
                                :value (nth parts 3 nil)}
                      nil)
	          "payment" (case (nth parts 2 nil)
	                      "add" {:type :docs/payment-add}
	                      "method" {:type :docs/payment-method
	                                :value (nth parts 3 nil)}
	                      "note" (case (nth parts 3 nil)
	                               "skip" {:type :docs/payment-note-skip}
	                               nil)
	                      "invoice" (case (nth parts 3 nil)
	                                  "pick" {:type :docs/payment-invoice-pick}
	                                  "set" {:type :docs/payment-invoice-set
	                                         :invoice-id (nth parts 4 nil)}
	                                  "skip" {:type :docs/payment-invoice-skip}
	                                  nil)
	                      "ref" (case (nth parts 3 nil)
	                              "skip" {:type :docs/payment-ref-skip}
	                              nil)
	                      nil)
          "generate" (case (nth parts 2 nil)
                       "proposal" {:type :docs/generate
                                   :value :proposal}
                       "status-report" {:type :docs/generate
                                        :value :status-report}
                       "invoice" (case (nth parts 3 nil)
                                   "pick" {:type :docs/generate-invoice-pick}
                                   "set" {:type :docs/generate-invoice-set
                                          :invoice-id (nth parts 4 nil)}
                                   nil)
                       "receipt" (case (nth parts 3 nil)
                                   "pick" {:type :docs/generate-receipt-pick}
                                   "set" {:type :docs/generate-receipt-set
                                          :payment-id (nth parts 4 nil)}
                                   nil)
                       nil)
          nil)
        "filter"
        (case (second parts)
          "status" {:type :tasks/filter
                    :filter :status
                    :value (when-let [v (nth parts 2 nil)]
                             (when-not (= v "all") (keyword v)))}
          "archived" {:type :tasks/filter
                      :filter :archived
                      :value (keyword (or (nth parts 2 nil) "active"))}
          "refresh" {:type :tasks/filter
                     :filter :refresh
                     :value nil}
          nil)
        "capture"
        (case (second parts)
          "task" {:type :capture/task}
          "client" {:type :capture/client}
          "cancel" {:type :capture/cancel}
          nil)
        "pending"
        (case (second parts)
          "reason" {:type :pending/reason
                    :task-id (nth parts 2 nil)
                    :value (nth parts 3 nil)}
          "followup" {:type :pending/followup
                      :task-id (nth parts 2 nil)
                      :value (nth parts 3 nil)}
          "cancel" {:type :pending/cancel
                    :task-id (nth parts 2 nil)}
          nil)
        "task"
        (case (second parts)
          "status" {:type :task/status
                    :task-id (nth parts 2 nil)
                    :value (nth parts 3 nil)}
          "client" (case (nth parts 2 nil)
                     "pick" {:type :task/client-pick
                             :task-id (nth parts 3 nil)}
                     "create" {:type :task/client-create
                               :task-id (nth parts 3 nil)}
                     "skip" {:type :task/client-skip
                             :task-id (nth parts 3 nil)}
                     "set" {:type :task/client-set
                            :task-id (nth parts 3 nil)
                            :client-id (nth parts 4 nil)}
                     "cancel" {:type :task/client-cancel
                               :task-id (nth parts 3 nil)}
                     nil)
          "archive" {:type :task/archive
                     :task-id (nth parts 2 nil)
                     :value (nth parts 3 nil)}
          "delete" {:type :task/delete
                    :task-id (nth parts 2 nil)}
          "view" {:type :task/view
                  :task-id (nth parts 2 nil)}
          "edit" (case (nth parts 2 nil)
                   "title" {:type :task/edit-title
                            :task-id (nth parts 3 nil)}
                   "desc" {:type :task/edit-desc
                           :task-id (nth parts 3 nil)}
                   "cancel" {:type :task/edit-cancel
                             :task-id (nth parts 3 nil)}
                   nil)
          "note" (case (nth parts 2 nil)
                   "add" {:type :task/note-add
                          :task-id (nth parts 3 nil)}
                   "edit" {:type :task/note-edit
                           :task-id (nth parts 3 nil)}
                   "delete" {:type :task/note-delete
                             :task-id (nth parts 3 nil)}
                   nil)
          nil)
        "client"
        (case (second parts)
          "action" {:type :client/action
                    :client-id (nth parts 2 nil)
                    :value (nth parts 3 nil)}
          nil)
        nil))))

(defn- handle-command
  [state chat-id {:keys [command rest text from-id]}]
  (let [cfg (get-in state [:config :telegram])
        conn (ensure-conn state)
        db (when conn (d/db conn))
        chat-user (when db (user-by-chat-id db chat-id))
        chat-user (or chat-user
                      (when (and db from-id)
                        (when-let [auto-user (user-by-telegram-user-id db (long from-id))]
                          (when-let [res (bind-chat-for-user! state {:user auto-user :chat-id chat-id})]
                            (:user res))))
                      (auto-bind-user state db chat-id))]
    (case command
      :help {:text (str "Commands: /start <link-token>, /help, /tasks, /task <uuid>, "
                        "/new <title> [| description], /edit <task-id> <title> [| description], "
                        "/note <task-id> <comment>, /note-edit <task-id> <comment>, /stop.\n"
                        "Link chat with /start using a token from the app. Notifications require flags on.")}
      :start (let [token (or (some-> rest (str/split #"\s+" 2) first)
                             (some->> text
                                      (re-matches #"^/start(?:@[A-Za-z0-9_]+)?\s+(.*)$")
                                      second
                                      str/trim))
                   res (bind-chat! state {:token token :chat-id chat-id})]
               (if-let [err (:error res)]
                 (if (= err "Missing link token")
                   {:text "Missing link token. Generate one in the app (POST /api/telegram/link-token) and send: /start <token>."}
                   {:text (str "Cannot link chat: " err)})
                 (do
                   (let [user (:user res)
                         event (events/new-event {:event/type :telegram/linked
                                                  :event/source "telegram"
                                                  :event/payload {:user/id (:user/id user)
                                                                  :chat-id chat-id}
                                                  :actor (actions/actor-from-telegram user)})]
                     (when-not (:error event)
                       (actions/apply-event! state event)))
                   {:text (str "Chat linked to " (get-in res [:user :user/username]) ". Notifications remain gated by flags.")})))
      :stop (let [res (unbind-chat! state chat-id)]
              (if-let [err (:error res)]
                {:text (str "Cannot stop: " err)}
                {:text "Chat unlinked. Notifications stopped."}))
      :tasks (if-not chat-user
               {:text "Chat not linked. Use /start <token> from the app to link."}
               (let [filters {:status nil :archived :active :limit tasks-list-limit}
                     resp (list-user-tasks conn (:user/id chat-user) filters)
                     tasks (:tasks resp)
                     pending-reasons (into {}
                                           (keep (fn [task]
                                                   (when-let [reason (pending-reason-for-task db task)]
                                                     [(:task/id task) reason])))
                                           tasks)]
                 (if-let [err (:error resp)]
                   {:text (str "Unable to list tasks: " (:message err))}
                   {:task-list {:tasks (:tasks resp)
                                :filters filters
                                   :pending-reasons pending-reasons}})))
	      :docs (if-not chat-user
	              {:text "Chat not linked. Use /start <token> from the app to link."}
	              (let [session (get-docs-session! chat-id)]
	                (if (and session (:client-id session))
	                  {:text "Documents:"
	                   :reply-markup (docs-menu-inline-keyboard)}
	                  (let [workspace nil
	                        res (clients/list-clients conn {:limit 6} workspace)
	                        client-list (or (:clients res) [])
	                        prompt (if (seq client-list) "Pick a client:" "No clients available yet.")
	                        keyboard (docs-client-pick-inline-keyboard client-list)]
	                    (save-docs-session! chat-id {:stage :docs/client-pick
	                                                 :user chat-user})
	                    {:text prompt
	                     :reply-markup keyboard}))))
      :task (if-not chat-user
              {:text "Chat not linked. Use /start <token> from the app to link."}
              (let [raw (some-> rest (str/split #"\s+" 2) first)
                    task-id (when raw (try (UUID/fromString (str/trim raw)) (catch Exception _ nil)))]
                (cond
                  (nil? task-id) {:text "Invalid task id. Use /task <uuid>."}
                  :else
                  (let [task (find-user-task conn (:user/id chat-user) task-id)]
                    {:task task}))))
      :edit (if-not chat-user
              {:text "Chat not linked. Use /start <token> from the app to link."}
              (let [{:keys [task-id body]} (parse-task-command rest)]
                (cond
                  (nil? task-id) {:text "Usage: /edit <task-id> <title> [| description]"}
                  (str/blank? body) {:text "Usage: /edit <task-id> <title> [| description]"}
                  :else
                  (let [[title desc] (if (str/includes? body "|")
                                       (map str/trim (str/split body #"\|" 2))
                                       [body nil])
                        title (when-not (str/blank? title) title)
                        desc (when-not (str/blank? desc) desc)
                        input (cond-> {:task/id task-id}
                                title (assoc :task/title title)
                                desc (assoc :task/description desc))]
                    (if (and (nil? title) (nil? desc))
                      {:text "Provide a new title and/or description to edit the task."}
                      (let [action-res (actions/execute! state {:action/id :cap/action/task-update
                                                                :actor (actions/actor-from-telegram chat-user)
                                                                :input input})]
                        (if-let [err (:error action-res)]
                          {:text (str "Unable to edit task: " (:message err))}
                          {:task (get-in action-res [:result :task])})))))))
      :note (if-not chat-user
              {:text "Chat not linked. Use /start <token> from the app to link."}
              (let [{:keys [task-id body]} (parse-task-command rest)]
                (cond
                  (nil? task-id) {:text "Usage: /note <task-id> <comment>"}
                  (str/blank? body) {:text "Usage: /note <task-id> <comment>"}
                  :else
                  (let [action-res (actions/execute! state {:action/id :cap/action/task-add-note
                                                            :actor (actions/actor-from-telegram chat-user)
                                                            :input {:task/id task-id
                                                                    :note/body body}})]
                    (if-let [err (:error action-res)]
                      {:text (str "Unable to add note: " (:message err))}
                      {:text "Note added to task."})))))
      :note-edit (if-not chat-user
                   {:text "Chat not linked. Use /start <token> from the app to link."}
                   (let [{:keys [task-id body]} (parse-task-command rest)]
                     (cond
                       (nil? task-id) {:text "Usage: /note-edit <task-id> <comment>"}
                       (str/blank? body) {:text "Usage: /note-edit <task-id> <comment>"}
                       :else
                       (let [action-res (actions/execute! state {:action/id :cap/action/task-edit-note
                                                                 :actor (actions/actor-from-telegram chat-user)
                                                                 :input {:task/id task-id
                                                                         :note/body body}})]
                         (if-let [err (:error action-res)]
                           {:text (str "Unable to edit note: " (:message err))}
                           {:text "Latest note updated."})))))
      :new (if-not chat-user
             {:text "Chat not linked. Use /start <token> from the app to link."}
             (let [raw (str/trim (or rest ""))
                   [title desc] (if (str/includes? raw "|")
                                  (map str/trim (str/split raw #"\|" 2))
                                  [(str/trim raw) nil])
                   title (when-not (str/blank? title) title)
                   desc (when-not (str/blank? desc) desc)
                   actor (actions/actor-from-telegram chat-user)
                   default-client (ensure-default-client-id state (:actor/workspace actor))]
               (cond
                 (nil? title) {:text "Usage: /new <title> [| description]"}
                 (nil? default-client) {:text "Unable to resolve default client."}
                 :else
                 (let [body {:task/title title
                             :task/description (or desc (str "Created via Telegram: " title))
                             :task/status :todo
                             :task/priority :medium
                             :task/assignee (:user/id chat-user)
                             :task/client default-client}
                       action-res (actions/execute! state {:action/id :cap/action/task-create
                                                          :actor actor
                                                          :input body})]
                   (if-let [err (:error action-res)]
                     {:text (str "Unable to create task: " (:message err))}
                     {:task (get-in action-res [:result :task])
                      :link-task (get-in action-res [:result :task])})))))
      {:text "Unknown command. Send /help for available commands."})))

(defn notify-task-event!
  "Best-effort notification helper. Does nothing unless notifications are enabled and assignee has a chat id mapping."
  [state {:keys [event task actor]}]
  (let [cfg (get-in state [:config :telegram])]
    (when (and (notifications-enabled? cfg) task)
      (when-let [conn (ensure-conn state)]
        (try
          (let [db (d/db conn)
                assignee-id (get-in task [:task/assignee :user/id])
                chat-id (chat-id-by-user-id db assignee-id)]
            (when (and (not (str/blank? chat-id))
                       (not (str/blank? (:bot-token cfg))))
              (let [task-id (str (:task/id task))
                    title (:task/title task)
                    status-key (or (some-> (:task/status task) name) "unknown")
                    status (status-label (:task/status task))
                    due (or (:task/due-date task) "none")
                    actor-name (or (:user/username actor)
                                   (:user/name actor)
                                   (some-> (:user/id actor) str)
                                   "system")
                    text (task-notification-text event title status due actor-name)
                    message-key (case event
                                  :task/created (str "task-created:" task-id)
                                  :task/assigned (str "task-assigned:" task-id ":" (or (some-> assignee-id str) "none"))
                                  :task/status-changed (str "task-status:" task-id ":" status-key)
                                  :task/due-changed (str "task-due:" task-id ":" due)
                                  (str "task-update:" task-id))]
                (when-let [err (:error (outbox/enqueue! conn {:integration :integration/telegram
                                                              :payload {:chat-id chat-id
                                                                        :text text
                                                                        :message-key message-key}
                                                              :dedupe-key message-key}))]
                  (log/warn "Telegram notification enqueue failed" {:event event :error err})))))
          (catch Exception e
            (log/warn e "Telegram notification failed")))))))

(defn handle-update
  "Process a Telegram update payload. Returns {:status ...} or {:error ...}."
  [state update]
  (let [cfg (get-in state [:config :telegram])]
    (cond
      (and (not (webhook-enabled? cfg))
           (not (polling-enabled? cfg))) {:status :ignored :reason :webhook-disabled}
      (not (commands-enabled? cfg)) {:status :ignored :reason :commands-disabled}
      :else
      (let [{:keys [update-id chat-id command callback text] :as parsed} (extract-update update)]
        (cond
          (nil? update-id) {:error "Missing update id"}
          (str/blank? chat-id) {:error "Missing chat id"}
          (and (nil? command) (nil? callback) (str/blank? text)) {:status :ignored :reason :unsupported-command}
          :else
          (do
            (log-telegram-message! state {:chat-id chat-id
                                          :from-id (:from-id parsed)
                                          :text (or text (some-> command name))
                                          :update-id update-id
                                          :message-id (:message-id parsed)
                                          :direction :inbound})
            (let [response (cond
                           callback (handle-callback state chat-id callback)
                           command (handle-command state chat-id parsed)
                           :else (let [conn (ensure-conn state)
                                       db (when conn (d/db conn))
                                       chat-user (when db (user-by-chat-id db chat-id))
                                       chat-user (or chat-user
                                                     (when (and db (:from-id parsed))
                                                       (when-let [auto-user (user-by-telegram-user-id db (long (:from-id parsed)))]
                                                         (when-let [res (bind-chat-for-user! state {:user auto-user :chat-id chat-id})]
                                                           (:user res))))
                                                     (auto-bind-user state db chat-id))]
                                   (if chat-user
                                     (if (get-pending-edit! chat-id)
                                       (handle-pending-edit-message state chat-user chat-id text)
                                       (if (get-pending-reason! chat-id)
                                         (handle-pending-reason-message state chat-user chat-id text)
                                         (if (get-pending-client! chat-id)
                                           (handle-pending-client-message state chat-user chat-id text)
                                             (if (get-pending-next-action! chat-id)
                                               (handle-pending-next-action-message state chat-user chat-id text)
                                             (if (get-docs-session! chat-id)
                                               (handle-docs-message state chat-user chat-id text)
                                               (handle-freeform-message state chat-user chat-id text))))))
                                     {:text "Chat not linked. Use /start <token> to link."})))
                {:keys [text tasks task task-list link-task]} response]
            (cond
              task-list (let [{:keys [tasks filters pending-reasons]} task-list
                              header (str "Tasks"
                                          (when-let [status (:status filters)]
                                            (str " • " (name status)))
                                          (case (:archived filters)
                                            :archived " • archived"
                                            :all " • all"
                                            ""))
                              body (if (seq tasks)
                                     (str header "\n" (tasks-summary-text tasks pending-reasons))
                                     (str header "\nNo tasks found."))
                              send-res (send-message! cfg {:chat-id chat-id
                                                           :text body
                                                           :message-key (str "task-list-" update-id)
                                                           :reply-markup (tasks-list-keyboard tasks filters)})]
                          (when-let [mid (:telegram/message-id send-res)]
                            (save-task-list! chat-id mid filters))
                          {:status :handled})
              task (do
                     (send-task-card! state chat-id task {})
                     (when link-task
                       (prompt-link-client! state chat-id link-task))
                     {:status :handled})
              tasks (do
                      (doseq [t tasks]
                        (send-task-card! state chat-id t {}))
                      (when (empty? tasks)
                        (send-message! cfg {:chat-id chat-id
                                            :text "No tasks found."
                                            :message-key (str "tasks-empty-" update-id)}))
                      {:status :handled})
              text (let [send-res (send-message! cfg {:chat-id chat-id
                                                      :text text
                                                      :message-key (str "update-" update-id "-" (or (some-> command name) "text"))
                                                      :reply-markup (:reply-markup response)})]
                     (if (:error send-res)
                       send-res
                       {:status :handled
                        :telegram/command command
                        :telegram/message-id (:telegram/message-id send-res)}))
              :else {:status :handled}))))))))
