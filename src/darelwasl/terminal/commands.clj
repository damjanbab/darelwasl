(ns darelwasl.terminal.commands
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [darelwasl.terminal.app-client :as app-client]
            [darelwasl.terminal.client :as terminal]
            [darelwasl.terminal.plan :as plan]
            [darelwasl.terminal.spec :as spec]
            [darelwasl.terminal.store :as store]
            [darelwasl.validation :as v])
  (:import (java.nio.file Files)
           (java.security MessageDigest)
           (java.util Base64 UUID)))

(defn- error
  [status message & [details]]
  {:status status
   :message message
   :details details})

(defn- normalize-type
  [value]
  (let [raw (cond
              (keyword? value) (name value)
              (string? value) value
              :else nil)]
    (some-> raw str/trim str/lower-case)))

(def ^:private action-types
  {"task.create" :cap/action/task-create
   "task.update" :cap/action/task-update
   "task.set-status" :cap/action/task-set-status
   "task.assign" :cap/action/task-assign
   "task.set-due" :cap/action/task-set-due
   "task.set-tags" :cap/action/task-set-tags
   "task.archive" :cap/action/task-archive
   "task.delete" :cap/action/task-delete
   "file.update" :cap/action/file-update
   "file.delete" :cap/action/file-delete
   "workspace.promote" :cap/action/workspace-promote})

(defn- run-action
  [state action-id actor input workspace-id]
  (app-client/execute-action state action-id (or input {}) actor workspace-id))

(defn- encode-base64
  [^bytes bytes]
  (when bytes
    (.encodeToString (Base64/getEncoder) bytes)))

(defn- read-file-bytes
  [^java.io.File file]
  (try
    (Files/readAllBytes (.toPath file))
    (catch Exception _
      nil)))

(defn- detect-mime
  [^java.io.File file]
  (try
    (Files/probeContentType (.toPath file))
    (catch Exception _
      nil)))

(defn- now-ms
  []
  (System/currentTimeMillis))

(defn- sha256-hex
  [^bytes bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn- session-call
  [sym & args]
  (apply (requiring-resolve sym) args))

(defn- prepare-upload
  [input]
  (let [path (v/param-value input :path)
        filename (or (v/param-value input :filename)
                     (when (string? path) (.getName (io/file path))))
        mime (v/param-value input :mime)
        slug (v/param-value input :slug)
        content-b64 (or (v/param-value input :content_base64)
                        (v/param-value input :content-base64))
        content (v/param-value input :content)
        file (when path (io/file path))]
    (cond
      (and (nil? path) (nil? content-b64) (nil? content))
      (error 400 "file.upload requires path, content_base64, or content")

      (and path (not (.exists file)))
      (error 400 "Upload path not found")

      (and path (not (.isFile file)))
      (error 400 "Upload path must be a file")

      :else
      (let [bytes (cond
                    content-b64 nil
                    (string? content) (.getBytes (str content) "UTF-8")
                    file (read-file-bytes file)
                    :else nil)
            computed-mime (or mime (when file (detect-mime file)))
            filename (or filename (some-> file .getName))
            content-b64 (or content-b64 (encode-base64 bytes))]
        (cond
          (str/blank? (str content-b64)) (error 400 "Unable to prepare upload data")
          (str/blank? (str computed-mime)) (error 400 "mime is required for file uploads")
          (str/blank? (str filename)) (error 400 "filename is required for file uploads")
          :else {:content-base64 content-b64
                 :filename filename
                 :mime computed-mime
                 :slug slug})))))

(defn- file-upload
  [state actor input workspace-id]
  (let [{:keys [error filename mime slug content-base64]} (prepare-upload input)]
    (if error
      {:error error}
      (run-action state :cap/action/file-upload actor {:file/filename filename
                                                       :file/mime mime
                                                       :file/content-base64 content-base64
                                                       :file/slug slug}
                  workspace-id))))

(defn- task-summary
  [task]
  (let [ref (:entity/ref task)
        id (:task/id task)
        title (:task/title task)
        status (:task/status task)
        priority (:task/priority task)]
    (str "task " (or ref id)
         (when title (str " — " title))
         (when status (str " [" (name status) "]"))
         (when priority (str " " (name priority))))))

(defn- file-summary
  [file]
  (let [ref (or (:file/ref file)
                (when-let [slug (:file/slug file)] (str "file:" slug)))
        id (:file/id file)
        name (:file/name file)
        mime (:file/mime file)]
    (str "file " (or ref id)
         (when name (str " — " name))
         (when mime (str " (" mime ")")))))

(defn- format-result
  [command-type result]
  (cond
    (:task result) (task-summary (:task result))
    (:file result) (file-summary (:file result))
    (:file/id result) (str "file " (:file/id result))
    (:task/id result) (str "task " (:task/id result))
    (:promotion result)
    (let [promotion (:promotion result)
          workspace (:workspace/id promotion)
          target (:workspace/target promotion)
          moved (:moved promotion)
          parts [(when (some? (:tasks moved)) (str "tasks " (:tasks moved)))
                 (when (some? (:tags moved)) (str "tags " (:tags moved)))
                 (when (some? (:files moved)) (str "files " (:files moved)))
                 (when (some? (:notes moved)) (str "notes " (:notes moved)))]
          counts (->> parts (remove nil?) (str/join ", "))]
      (str "workspace " (or workspace "-")
           " -> " (or target "main")
           (when (seq counts) (str " (" counts ")"))))
    :else (str (or command-type "command") " ok")))

(defn- format-error
  [command-type err]
  (let [err (if (and (map? err) (contains? err :error))
              (:error err)
              err)
        {:keys [message details status]} err
        message (cond
                  (string? message) (when-not (str/blank? message) message)
                  (some? message) (str message)
                  :else nil)
        message (or message (when status (str "status " status)) "unknown error")]
    (str (or command-type "command") " failed: " message
         (when (and details (not (str/blank? (str details))))
           (str " (" details ")")))))

(defn- context-from-task
  [state input workspace-id]
  (let [task-id (or (v/param-value input :task/id)
                    (v/param-value input :task/ref)
                    (v/param-value input :id))
        res (app-client/execute-action state :cap/action/task-read {:task/id task-id} nil workspace-id)]
    (if-let [err (:error res)]
      {:error err}
      (let [task (get-in res [:result :task])]
        {:message (str "Context: " (task-summary task) "\n"
                       "title: " (:task/title task) "\n"
                       "description: " (:task/description task) "\n"
                       "status: " (name (:task/status task))) }))))

(defn- context-from-file
  [state input workspace-id]
  (let [file-id (or (v/param-value input :file/id)
                    (v/param-value input :file/ref)
                    (v/param-value input :id))
        res (app-client/execute-action state :cap/action/file-read {:file/id file-id} nil workspace-id)]
    (if-let [err (:error res)]
      {:error err}
      (let [file (get-in res [:result :file])]
        {:message (str "Context: " (file-summary file) "\n"
                       "slug: " (:file/slug file) "\n"
                       "mime: " (:file/mime file))}))))

(defn- context-add
  [state input workspace-id]
  (let [text (v/param-value input :text)
        task-id (or (v/param-value input :task/id)
                    (v/param-value input :task/ref))
        file-id (or (v/param-value input :file/id)
                    (v/param-value input :file/ref))]
    (cond
      (and text (not (str/blank? (str text))))
      {:message (str "Context: " text)}
      task-id (context-from-task state input workspace-id)
      file-id (context-from-file state input workspace-id)
      :else (error 400 "context.add requires text, task, or file reference"))))

(defn- submit-spec
  [state input]
  (let [value (or (v/param-value input :spec)
                  (v/param-value input :text)
                  (v/param-value input :value))
        {:keys [status errors spec]} (spec/validate-spec value)]
    (cond
      (= status :invalid)
      {:error (error 400 "Invalid spec" errors)}

      :else
      (let [spec (assoc spec :status status)
            stored (store/upsert-spec! (:terminal/store state) {:id (:spec/id spec)
                                                                :created-at (:spec/created-at spec)
                                                                :status status
                                                                :spec spec})]
        {:message (str "Spec " (:id stored) " accepted")
         :result {:spec stored}}))))

(defn- generate-plan
  [state input]
  (let [spec-id (or (v/param-value input :spec/id)
                    (v/param-value input :spec-id)
                    (v/param-value input :id))
        spec-record (store/get-spec (:terminal/store state) spec-id)]
    (cond
      (nil? spec-id) {:error (error 400 "spec.id is required")}
      (nil? spec-record) {:error (error 404 "Spec not found")}
      :else
      (let [plan (plan/generate-plan (:spec spec-record))
            stored (store/upsert-plan! (:terminal/store state)
                                       (assoc plan :status :plan.status/generated))]
        {:message (str "Plan " (:id stored) " generated")
         :result {:plan stored}}))))

(defn- attach-artifact
  [state input]
  (let [path (v/param-value input :path)
        uri (v/param-value input :uri)
        artifact-id (or (v/param-value input :id) (str (UUID/randomUUID)))
        artifact-type (or (v/param-value input :type) (v/param-value input :artifact/type))
        file (when path (io/file path))]
    (cond
      (and (nil? path) (nil? uri))
      {:error (error 400 "artifact.attach requires path or uri")}

      (and path (not (.exists file)))
      {:error (error 404 "Artifact path not found")}

      :else
      (let [bytes (when path (read-file-bytes file))
            sha (when bytes (sha256-hex bytes))
            size (when bytes (count bytes))
            stored (store/upsert-artifact! (:terminal/store state)
                                           {:id artifact-id
                                            :created-at (now-ms)
                                            :type artifact-type
                                            :path path
                                            :uri uri
                                            :sha256 sha
                                            :size-bytes size
                                            :step-id (v/param-value input :step/id)
                                            :session-id (v/param-value input :session-id)})]
        {:message (str "Artifact " (:id stored) " attached")
         :result {:artifact stored}}))))

(defn- update-step
  [state input status]
  (let [plan-id (or (v/param-value input :plan/id)
                    (v/param-value input :plan-id))
        step-id (or (v/param-value input :step/id)
                    (v/param-value input :step-id))
        plan (store/get-plan (:terminal/store state) plan-id)]
    (cond
      (nil? plan-id) {:error (error 400 "plan.id is required")}
      (nil? step-id) {:error (error 400 "step.id is required")}
      (nil? plan) {:error (error 404 "Plan not found")}
      :else
      (let [next-plan (plan/update-step-status plan step-id status)
            stored (store/upsert-plan! (:terminal/store state) next-plan)]
        {:message (str "Step " step-id " set to " (name status))
         :result {:plan stored}}))))

(defn- create-agent-run
  [state parent-session-id input]
  (let [store (:terminal/store state)
        cfg (:terminal/config state)
        name (or (v/param-value input :name) "subagent")
        prompt (or (v/param-value input :prompt)
                   (v/param-value input :input)
                   (v/param-value input :spec))
        session (session-call 'darelwasl.terminal.session/create-session!
                              store cfg {:name name :type "subagent" :dev-bot? false})
        agentrun-id (str (UUID/randomUUID))
        agent-run {:id agentrun-id
                   :created-at (now-ms)
                   :status :running
                   :parent-session-id parent-session-id
                   :session-id (:id session)
                   :spec prompt}]
    (when (and prompt (not (str/blank? (str prompt))))
      (session-call 'darelwasl.terminal.session/send-input! session (str prompt)))
    (store/upsert-agent-run! store agent-run)
    {:message (str "Subagent " agentrun-id " started")
     :result {:agent-run agent-run
              :session (session-call 'darelwasl.terminal.session/present-session session)}}))

(defn- agent-status
  [state input]
  (let [store (:terminal/store state)
        agentrun-id (or (v/param-value input :agentrun/id)
                        (v/param-value input :agentrun-id)
                        (v/param-value input :id))
        agent-run (store/get-agent-run store agentrun-id)
        session-id (:session-id agent-run)
        session (when session-id (store/get-session store session-id))]
    (cond
      (nil? agentrun-id) {:error (error 400 "agentrun.id is required")}
      (nil? agent-run) {:error (error 404 "Agent run not found")}
      :else
      {:message (str "Subagent " agentrun-id " status " (name (:status agent-run)))
       :result {:agent-run agent-run
                :session (when session
                           (session-call 'darelwasl.terminal.session/present-session session))}})))

(defn- read-chat-tail
  [path max-bytes]
  (let [file (io/file path)]
    (when (.exists file)
      (let [bytes (Files/readAllBytes (.toPath file))
            len (count bytes)
            start (max 0 (- len max-bytes))]
        (String. bytes start (- len start) "UTF-8")))))

(defn- agent-collect
  [state input]
  (let [store (:terminal/store state)
        agentrun-id (or (v/param-value input :agentrun/id)
                        (v/param-value input :agentrun-id)
                        (v/param-value input :id))
        max-bytes (or (v/param-value input :max-bytes) 8000)
        agent-run (store/get-agent-run store agentrun-id)
        session-id (:session-id agent-run)
        session (when session-id (store/get-session store session-id))]
    (cond
      (nil? agentrun-id) {:error (error 400 "agentrun.id is required")}
      (nil? agent-run) {:error (error 404 "Agent run not found")}
      (nil? session) {:error (error 404 "Subagent session not found")}
      :else
      (let [output (read-chat-tail (:chat-log session) max-bytes)]
        {:message (str "Subagent " agentrun-id " output collected")
         :result {:agent-run agent-run
                  :output output}}))))

(defn- agent-cancel
  [state input]
  (let [store (:terminal/store state)
        agentrun-id (or (v/param-value input :agentrun/id)
                        (v/param-value input :agentrun-id)
                        (v/param-value input :id))
        agent-run (store/get-agent-run store agentrun-id)
        session-id (:session-id agent-run)
        session (when session-id (store/get-session store session-id))]
    (cond
      (nil? agentrun-id) {:error (error 400 "agentrun.id is required")}
      (nil? agent-run) {:error (error 404 "Agent run not found")}
      (nil? session) {:error (error 404 "Subagent session not found")}
      :else
      (do
        (session-call 'darelwasl.terminal.session/interrupt-session! store session)
        (store/upsert-agent-run! store (assoc agent-run :status :blocked))
        {:message (str "Subagent " agentrun-id " interrupted")
         :result {:agent-run (store/get-agent-run store agentrun-id)}}))))

(defn- devbot-reset
  [state session-id input]
  (let [force? (boolean (v/param-value input :force))
        sessions-res (terminal/request (:config state) :get "/sessions")
        sessions (get-in sessions-res [:body :sessions] [])
        dev-sessions (filter :telegram/dev-bot? sessions)
        running (some #(when (:running? %) %) dev-sessions)
        target (or (some #(when (= (:id %) session-id) %) dev-sessions)
                   running)]
    (cond
      (:error sessions-res) (error 502 "Terminal service unavailable")
      (empty? dev-sessions) (error 404 "No dev bot session found")
      (and running (not= (:id running) session-id) (not force?))
      (error 409 (str "Dev bot running in session " (:name running) "; use force to reset"))
      (nil? target) (error 404 "Dev bot session not found")
      :else
      (let [res (terminal/request (:config state) :post (str "/sessions/" (:id target) "/restart-app"))]
        (if (:error res)
          (error 502 "Failed to restart dev bot session")
          {:message (str "Dev bot reset in session " (:name target))
           :result {:session (:id target)}})))))

(defn execute-command!
  [state session-id command actor]
  (let [command-type (normalize-type (:type command))
        input (or (:input command) {})
        workspace-id (or (v/param-value input :workspace/id)
                         (v/param-value input :workspace-id)
                         (v/param-value input :workspace)
                         session-id)]
    (cond
      (str/blank? command-type)
      (error 400 "Command type is required")

      (= command-type "spec.submit")
      (submit-spec state input)

      (= command-type "plan.generate")
      (generate-plan state input)

      (= command-type "artifact.attach")
      (attach-artifact state input)

      (= command-type "step.verify")
      (update-step state input :step.status/verified)

      (= command-type "agent.run")
      (create-agent-run state session-id input)

      (= command-type "agent.status")
      (agent-status state input)

      (= command-type "agent.collect")
      (agent-collect state input)

      (= command-type "agent.cancel")
      (agent-cancel state input)

      (= command-type "context.add")
      (context-add state input workspace-id)

      (= command-type "file.upload")
      (file-upload state actor input workspace-id)

      (= command-type "devbot.reset")
      (devbot-reset state session-id input)

      :else
      (if-let [action-id (get action-types command-type)]
        (run-action state action-id actor input workspace-id)
        (error 400 (str "Unsupported command type: " command-type))))))

(defn command->message
  [command result]
  (let [command-type (normalize-type (:type command))]
    (cond
      (:message result) (str "[command] " (:message result))
      (:error result) (str "[command-error] " (format-error command-type (:error result)))
      (:result result) (str "[command-ok] " (format-result command-type (:result result)))
      :else (str "[command-ok] " (or command-type "command") " completed"))))
