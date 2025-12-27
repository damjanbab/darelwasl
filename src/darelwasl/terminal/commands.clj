(ns darelwasl.terminal.commands
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [darelwasl.terminal.app-client :as app-client]
            [darelwasl.terminal.client :as terminal]
            [darelwasl.validation :as v])
  (:import (java.nio.file Files)
           (java.util Base64)))

(defn- error
  [status message & [details]]
  {:error {:status status
           :message message
           :details details}})

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
  (let [{:keys [message details]} err]
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
