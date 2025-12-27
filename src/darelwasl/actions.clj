(ns darelwasl.actions
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.automations :as automations]
            [darelwasl.betting :as betting]
            [darelwasl.events :as events]
            [darelwasl.files :as files]
            [darelwasl.github :as github]
            [darelwasl.tasks :as tasks]
            [darelwasl.users :as users]
            [darelwasl.validation :as v]
            [darelwasl.workspace :as workspace]
            [darelwasl.workspaces :as workspaces])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util Base64)))

(defn actor-from-session
  "Normalize an HTTP session map into an actor map."
  ([session] (actor-from-session session nil))
  ([session workspace-id]
   (when (map? session)
     (cond-> session
       (nil? (:actor/type session)) (assoc :actor/type :actor.type/user)
       (nil? (:actor/surface session)) (assoc :actor/surface :surface/http)
       :always (assoc :actor/workspace (workspace/resolve-id workspace-id))))))

(defn actor-from-telegram
  "Normalize a Telegram-linked user map into an actor map."
  ([user] (actor-from-telegram user nil))
  ([user workspace-id]
   (when (map? user)
     (cond-> user
       (nil? (:actor/type user)) (assoc :actor/type :actor.type/user)
       (nil? (:actor/surface user)) (assoc :actor/surface :surface/telegram)
       :always (assoc :actor/workspace (workspace/resolve-id workspace-id))))))

(defn parse-action-id
  "Parse a URL-safe action id string like `cap.action.task-create` into a keyword
  like `:cap/action/task-create`. Returns nil for invalid inputs."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (let [trimmed (str/trim s)]
      (when (re-matches #"[A-Za-z0-9._:-]+" trimmed)
        (try
          (let [kw-str (str ":" (str/replace trimmed "." "/"))
                v (edn/read-string kw-str)]
            (when (keyword? v) v))
          (catch Exception _ nil))))))

(defn- conn
  [state]
  (get-in state [:db :conn]))

(defn- task-create
  [state {:keys [input actor]}]
  (tasks/create-task! (conn state) (or input {}) actor))

(defn- task-update
  [state {:keys [input actor]}]
  (let [body (or input {})
        task-id (:task/id body)]
    (tasks/update-task! (conn state) task-id (dissoc body :task/id) actor)))

(defn- task-set-status
  [state {:keys [input actor]}]
  (let [body (or input {})
        task-id (:task/id body)]
    (tasks/set-status! (conn state) task-id (dissoc body :task/id) actor)))

(defn- task-assign
  [state {:keys [input actor]}]
  (let [body (or input {})
        task-id (:task/id body)]
    (tasks/assign-task! (conn state) task-id (dissoc body :task/id) actor)))

(defn- task-set-due
  [state {:keys [input actor]}]
  (let [body (or input {})
        task-id (:task/id body)]
    (tasks/set-due-date! (conn state) task-id (dissoc body :task/id) actor)))

(defn- task-set-tags
  [state {:keys [input actor]}]
  (let [body (or input {})
        task-id (:task/id body)]
    (tasks/set-tags! (conn state) task-id (dissoc body :task/id) actor)))

(defn- task-archive
  [state {:keys [input actor]}]
  (let [body (or input {})
        task-id (:task/id body)]
    (tasks/archive-task! (conn state) task-id (dissoc body :task/id) actor)))

(defn- task-add-note
  [state {:keys [input actor]}]
  (tasks/add-note! (conn state) (or input {}) actor))

(defn- task-edit-note
  [state {:keys [input actor]}]
  (tasks/edit-note! (conn state) (or input {}) actor))

(defn- task-delete-note
  [state {:keys [input actor]}]
  (tasks/delete-note! (conn state) (or input {}) actor))

(defn- task-delete
  [state {:keys [input actor]}]
  (let [body (or input {})
        task-id (:task/id body)]
    (tasks/delete-task! (conn state) task-id actor)))

(defn- task-read
  [state {:keys [input actor]}]
  (let [body (or input {})
        task-id (or (v/param-value body :task/id)
                    (v/param-value body :task/ref)
                    (v/param-value body :id))]
    (tasks/fetch-task (conn state) task-id (workspace/actor-workspace actor))))

(defn- betting-events
  [state {:keys [input]}]
  (let [body (or input {})]
    (betting/list-events (conn state)
                         (:config state)
                         {:day-offset (or (:day-offset body) (:day body) 0)
                          :sport-path (or (:sport-path body) (:sport body) "")
                          :refresh? (:refresh? body)})))

(defn- betting-odds
  [state {:keys [input]}]
  (let [body (or input {})]
    (betting/fetch-event-odds! (conn state)
                               (:config state)
                               {:event-id (or (:event-id body) (:betting.event/id body))
                                :sport-path (or (:sport-path body) (:sport body) "")
                                :refresh? (:refresh? body)})))

(defn- betting-bet-log
  [state {:keys [input]}]
  (let [body (or input {})]
    (betting/log-bet! (conn state)
                      (:config state)
                      {:event-id (or (:event-id body) (:betting.event/id body))
                       :market-key (or (:market-key body) (:betting.bet/market-key body))
                       :selection (or (:selection body) (:betting.bet/selection body))
                       :odds (or (:odds body) (:betting.bet/odds-decimal body))
                       :bookmaker-key (:bookmaker-key body)})))

(defn- betting-close
  [state {:keys [input]}]
  (let [body (or input {})]
    (betting/capture-close! (conn state)
                            (:config state)
                            {:event-id (or (:event-id body) (:betting.event/id body))
                             :sport-path (or (:sport-path body) (:sport body) "")
                             :refresh? (:refresh? body)})))

(defn- betting-settle
  [state {:keys [input]}]
  (let [body (or input {})]
    (betting/settle-bet! (conn state)
                         (:config state)
                         {:bet-id (or (:bet-id body) (:betting.bet/id body))
                          :status (or (:status body) (:betting.bet/status body))})))

(defn- decode-base64
  [value]
  (try
    (.decode (Base64/getDecoder) (str value))
    (catch Exception _
      nil)))

(defn- temp-file!
  [filename bytes]
  (let [suffix (when (and (string? filename) (str/includes? filename "."))
                 (str "." (last (str/split filename #"\."))))
        attrs (make-array FileAttribute 0)
        path (Files/createTempFile "action-upload-" (or suffix "") attrs)]
    (with-open [out (io/output-stream (.toFile path))]
      (.write out bytes))
    (.toFile path)))

(defn- prepare-file-upload
  [body]
  (let [upload (:file/upload body)
        filename (or (v/param-value body :file/filename)
                     (v/param-value body :file/name)
                     (v/param-value body :filename))
        mime (or (v/param-value body :file/mime)
                 (v/param-value body :mime))
        content-b64 (or (v/param-value body :file/content-base64)
                        (v/param-value body :file/content_base64)
                        (v/param-value body :content_base64)
                        (v/param-value body :content-base64))]
    (cond
      upload {:upload upload :temp? false}

      (str/blank? (str content-b64))
      {:error {:status 400
               :message "file.upload requires file/upload or base64 content"}}

      (str/blank? (str filename))
      {:error {:status 400
               :message "file.upload requires filename"}}

      (str/blank? (str mime))
      {:error {:status 400
               :message "file.upload requires mime"}}

      :else
      (if-let [bytes (decode-base64 content-b64)]
        (let [file (temp-file! filename bytes)]
          {:upload {:filename filename
                    :content-type mime
                    :tempfile file
                    :size (.length ^java.io.File file)}
           :temp? true})
        {:error {:status 400
                 :message "file.upload base64 content invalid"}}))))

(defn- file-upload
  [state {:keys [input actor]}]
  (let [body (or input {})
        storage-dir (or (:storage-dir body)
                        (get-in state [:config :files :storage-dir]))]
    (let [{:keys [upload error temp?]} (prepare-file-upload body)]
      (if error
        {:error error}
        (try
          (files/create-file! (conn state)
                              {:file upload
                               :slug (:file/slug body)}
                              actor
                              storage-dir)
          (finally
            (when (and temp? upload)
              (try
                (io/delete-file (:tempfile upload) true)
                (catch Exception e
                  (log/warn e "Failed to delete temp upload file"))))))))))

(defn- file-delete
  [state {:keys [input actor]}]
  (let [body (or input {})
        storage-dir (or (:storage-dir body)
                        (get-in state [:config :files :storage-dir]))]
    (files/delete-file! (conn state)
                        (:file/id body)
                        storage-dir
                        actor)))

(defn- file-update
  [state {:keys [input actor]}]
  (let [body (or input {})
        file-id (:file/id body)
        slug (v/param-value body :file/slug)
        ref (v/param-value body :file/ref)]
    (files/update-file! (conn state)
                        file-id
                        {:slug slug
                         :ref ref}
                        actor)))

(defn- file-read
  [state {:keys [input actor]}]
  (let [body (or input {})
        file-id (or (v/param-value body :file/id)
                    (v/param-value body :file/ref)
                    (v/param-value body :id))]
    (files/fetch-file (conn state) file-id (workspace/actor-workspace actor))))

(defn- tag-create
  [state {:keys [input actor]}]
  (tasks/create-tag! (conn state) (or input {}) actor))

(defn- tag-update
  [state {:keys [input actor]}]
  (let [body (or input {})
        tag-id (:tag/id body)]
    (tasks/rename-tag! (conn state) tag-id (dissoc body :tag/id) actor)))

(defn- tag-delete
  [state {:keys [input actor]}]
  (let [body (or input {})]
    (tasks/delete-tag! (conn state) (:tag/id body) actor)))

(defn- workspace-promote
  [state {:keys [input actor]}]
  (let [body (or input {})
        workspace-id (or (:workspace/id body)
                         (:workspace-id body)
                         (:workspace body)
                         (:actor/workspace actor))
        target (or (:workspace/target body)
                   (:target body)
                   "main")]
    (workspaces/promote-workspace! (conn state)
                                   {:workspace-id workspace-id
                                    :target target})))

(defn- user-list
  [state _]
  (users/list-users (conn state)))

(defn- user-create
  [state {:keys [input actor]}]
  (users/create-user! (conn state) (or input {}) actor))

(defn- user-update
  [state {:keys [input actor]}]
  (let [body (or input {})
        user-id (:user/id body)]
    (users/update-user! (conn state) user-id (dissoc body :user/id) actor)))

(defn- user-delete
  [state {:keys [input actor]}]
  (let [body (or input {})
        user-id (:user/id body)]
    (users/delete-user! (conn state) user-id actor)))
(defn- github-pulls
  [state {:keys [input]}]
  (let [body (or input {})]
    (github/list-pulls (:config state) body)))

(def ^:private handlers
  {:cap/action/task-create task-create
   :cap/action/task-update task-update
   :cap/action/task-set-status task-set-status
   :cap/action/task-assign task-assign
   :cap/action/task-set-due task-set-due
   :cap/action/task-set-tags task-set-tags
   :cap/action/task-archive task-archive
   :cap/action/task-add-note task-add-note
   :cap/action/task-edit-note task-edit-note
   :cap/action/task-delete-note task-delete-note
   :cap/action/task-delete task-delete
   :cap/action/task-read task-read
   :cap/action/betting-events betting-events
   :cap/action/betting-odds betting-odds
   :cap/action/betting-bet-log betting-bet-log
   :cap/action/betting-close betting-close
   :cap/action/betting-settle betting-settle
   :cap/action/file-upload file-upload
   :cap/action/file-update file-update
   :cap/action/file-delete file-delete
   :cap/action/file-read file-read
   :cap/action/tag-create tag-create
   :cap/action/tag-update tag-update
   :cap/action/tag-delete tag-delete
   :cap/action/workspace-promote workspace-promote
   :cap/action/github-pulls github-pulls
   :cap/action/user-list user-list
   :cap/action/user-create user-create
   :cap/action/user-update user-update
   :cap/action/user-delete user-delete})

(defn dispatch!
  "Execute an action invocation and return a uniform action result:
  - success: {:action/id kw :result <domain-result>}
  - error:   {:action/id kw :error {:status .. :message .. :details ..}}

  The handler result is expected to be a domain map (e.g. {:task ...}) or
  {:error {:status ...}}."
  [state {:keys [action-id actor input] :as invocation}]
  (let [resolved-id (or (:action/id invocation) action-id)
        handler (get handlers resolved-id)]
    (cond
      (nil? resolved-id)
      {:error {:status 400 :message "Missing action id"}}

      (nil? handler)
      {:action/id resolved-id
       :error {:status 404
               :message "Unknown action"
               :details {:action/id resolved-id}}}

      :else
      (try
        (let [domain-res (handler state {:action/id resolved-id
                                         :actor actor
                                         :input input})]
          (if-let [err (:error domain-res)]
            {:action/id resolved-id
             :error err}
            {:action/id resolved-id
             :result domain-res}))
        (catch Exception e
          (log/error e "Action handler crashed" {:action/id resolved-id})
          {:action/id resolved-id
           :error {:status 500
                   :message "Action failed"}})))))

(defn- task-event
  [event-type actor task]
  (let [task-id (:task/id task)]
    (events/new-event {:event/type event-type
                       :event/subject {:subject/type :subject.type/task
                                      :subject/id task-id}
                       :event/payload {:task/id task-id
                                      :task/status (:task/status task)
                                      :task/assignee (get-in task [:task/assignee :user/id])}
                       :actor actor})))

(defn- emit-events
  [action-id actor domain-result]
  (let [task (:task domain-result)]
    (cond
      (and (= action-id :cap/action/task-create) task) [(task-event :task/created actor task)]
      (and (= action-id :cap/action/task-update) task) [(task-event :task/updated actor task)]
      (and (= action-id :cap/action/task-set-status) task) [(task-event :task/status-changed actor task)]
      (and (= action-id :cap/action/task-assign) task) [(task-event :task/assigned actor task)]
      (and (= action-id :cap/action/task-set-due) task) [(task-event :task/due-changed actor task)]
      (and (= action-id :cap/action/task-set-tags) task) [(task-event :task/tags-changed actor task)]
      (and (= action-id :cap/action/task-archive) task) [(task-event :task/archived actor task)]
      (and (= action-id :cap/action/task-delete) (:task domain-result))
      [(events/new-event {:event/type :task/deleted
                          :event/payload {:task/id (get-in domain-result [:task :task/id])}
                          :actor actor})]
      :else [])))

(defn apply-event!
  "Run automations for an external/internal event and execute the resulting
  action invocations. Returns {:event .. :automation/invocations .. :automation/results ..}."
  [state event]
  (let [invocations (automations/derive-invocations state event)
        results (mapv (fn [inv] (dispatch! state inv)) invocations)]
    {:event event
     :automation/invocations invocations
     :automation/results results}))

(defn execute!
  "Execute an action and (Path A) run automations derived from its emitted events.
  Uses one-level automation execution to avoid accidental loops."
  [state invocation]
  (let [res (dispatch! state invocation)]
    (if-let [err (:error res)]
      res
      (let [actor (:actor invocation)
            events (->> (emit-events (:action/id res) actor (:result res))
                        (remove :error)
                        vec)
            automation (mapv #(apply-event! state %) events)]
        (assoc res
               :events events
               :automation automation)))))

(defn dispatch-result!
  "Compatibility helper: returns the domain result map directly (or {:error ...})."
  [state invocation]
  (let [res (dispatch! state invocation)]
    (if-let [err (:error res)]
      {:error err}
      (:result res))))
