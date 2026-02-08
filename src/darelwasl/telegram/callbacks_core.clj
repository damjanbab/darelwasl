(in-ns 'darelwasl.telegram)

(comment "Callback dispatch core (ctx + defmulti + handle-callback).")

(defn-
 callback-ctx
 [state chat-id {:as callback, :keys [message-id callback-id data]}]
 (let
  [conn
   (ensure-conn state)
   db
   (when conn (d/db conn))
   chat-user
   (when db (user-by-chat-id db chat-id))
   parsed
   (parse-callback data)
   cfg
   (get-in state [:config :telegram])]
  {:cfg cfg,
   :message-id message-id,
   :chat-user chat-user,
   :db db,
   :state state,
   :callback-id callback-id,
   :chat-id chat-id,
   :callback callback,
   :parsed parsed,
   :conn conn,
   :data data}))

(defmulti
 handle-callback-dispatch
 (fn [{:keys [parsed]}] (:type parsed)))

(defmethod
 handle-callback-dispatch
 :default
 [{:keys [data parsed chat-id]}]
 (log/warn
  "Unhandled telegram callback"
  {:data data, :parsed parsed, :chat-id chat-id})
 nil)

(defn-
 handle-callback
 [state chat-id callback]
 (let
  [{:keys [callback-id cfg], :as ctx}
   (callback-ctx state chat-id callback)]
  (when callback-id (answer-callback! cfg {:callback-id callback-id}))
  (handle-callback-dispatch ctx)))

