(in-ns 'darelwasl.telegram)

(comment "Ephemeral Telegram state/session storage.")

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

(defonce services-sessions
  (atom {}))
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

(defn- prune-services-sessions!
  []
  (let [cutoff (- (System/currentTimeMillis) docs-ttl-ms)]
    (swap! services-sessions
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

(defn- save-services-session!
  [chat-id session]
  (prune-services-sessions!)
  (swap! services-sessions assoc (str chat-id) (assoc session :created-at (System/currentTimeMillis))))

(defn- get-services-session!
  [chat-id]
  (prune-services-sessions!)
  (get @services-sessions (str chat-id)))

(defn- take-services-session!
  [chat-id]
  (prune-services-sessions!)
  (let [k (str chat-id)
        value (get @services-sessions k)]
    (swap! services-sessions dissoc k)
    value))
