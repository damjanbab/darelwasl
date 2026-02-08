(in-ns 'darelwasl.telegram)

(comment "Telegram command parsing + command handlers.")

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

