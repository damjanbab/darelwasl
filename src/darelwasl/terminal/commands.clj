(ns darelwasl.terminal.commands
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.actions :as actions]
            [darelwasl.files :as files]
            [darelwasl.tasks :as tasks]
            [darelwasl.terminal.client :as terminal]
            [darelwasl.terminal.promote :as promote]
            [darelwasl.validation :as v])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util Base64)))

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
   "file.delete" :cap/action/file-delete})

(def ^:private library-command-types
  ["library.add"])

(defn- run-action
  [state action-id actor input]
  (let [res (actions/execute! state {:action/id action-id
                                     :actor actor
                                     :input (or input {})})]
    (if-let [err (:error res)]
      {:error err}
      {:result (:result res)})))

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
        path (Files/createTempFile "terminal-upload-" (or suffix "") attrs)]
    (with-open [out (io/output-stream (.toFile path))]
      (.write out bytes))
    (.toFile path)))

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
        content (v/param-value input :content)]
    (cond
      (and (nil? path) (nil? content-b64) (nil? content))
      {:error (error 400 "file.upload requires path, content_base64, or content")}

      (and path (not (.exists (io/file path))))
      {:error (error 400 "Upload path not found")}

      (and path (not (.isFile (io/file path))))
      {:error (error 400 "Upload path must be a file")}

      :else
      (let [file (cond
                   path (io/file path)
                   content-b64 (let [bytes (decode-base64 content-b64)]
                                 (when bytes
                                   (temp-file! (or filename "upload.bin") bytes)))
                   (string? content) (temp-file! (or filename "upload.txt")
                                                 (.getBytes (str content) "UTF-8"))
                   :else nil)
            computed-mime (or mime (when file (detect-mime file)))
            filename (or filename (some-> file .getName))
            size (when file (.length ^java.io.File file))]
        (cond
          (nil? file) {:error (error 400 "Unable to prepare upload data")}
          (str/blank? (str computed-mime)) {:error (error 400 "mime is required for file uploads")}
          (str/blank? (str filename)) {:error (error 400 "filename is required for file uploads")}
          :else {:file file
                 :filename filename
                 :mime computed-mime
                 :size size
                 :slug slug
                 :temp? (not (some? path))})))))

(defn- file-upload
  [state actor input]
  (let [{:keys [error file filename mime size slug temp?]} (prepare-upload input)]
    (if error
      {:error error}
      (try
        (let [upload {:filename filename
                      :content-type mime
                      :tempfile file
                      :size size}
              res (run-action state :cap/action/file-upload actor {:file/upload upload
                                                                  :file/slug slug})]
          res)
        (finally
          (when temp?
            (try
              (io/delete-file file true)
              (catch Exception e
                (log/warn e "Failed to delete temp upload file")))))))))

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
  [state input]
  (let [task-id (or (v/param-value input :task/id)
                    (v/param-value input :task/ref)
                    (v/param-value input :id))
        res (tasks/fetch-task (get-in state [:db :conn]) task-id)]
    (if-let [err (:error res)]
      {:error err}
      (let [task (:task res)]
        {:message (str "Context: " (task-summary task) "\n"
                       "title: " (:task/title task) "\n"
                       "description: " (:task/description task) "\n"
                       "status: " (name (:task/status task))) }))))

(defn- context-from-file
  [state input]
  (let [file-id (or (v/param-value input :file/id)
                    (v/param-value input :file/ref)
                    (v/param-value input :id))
        res (files/fetch-file (get-in state [:db :conn]) file-id)]
    (if-let [err (:error res)]
      {:error err}
      (let [file (:file res)]
        {:message (str "Context: " (file-summary file) "\n"
                       "slug: " (:file/slug file) "\n"
                       "mime: " (:file/mime file))}))))

(defn- context-add
  [state input]
  (let [text (v/param-value input :text)
        task-id (or (v/param-value input :task/id)
                    (v/param-value input :task/ref))
        file-id (or (v/param-value input :file/id)
                    (v/param-value input :file/ref))]
    (cond
      (and text (not (str/blank? (str text))))
      {:message (str "Context: " text)}
      task-id (context-from-task state input)
      file-id (context-from-file state input)
      :else {:error (error 400 "context.add requires text, task, or file reference")})))

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
      (:error sessions-res) {:error (error 502 "Terminal service unavailable")}
      (empty? dev-sessions) {:error (error 404 "No dev bot session found")}
      (and running (not= (:id running) session-id) (not force?))
      {:error (error 409 (str "Dev bot running in session " (:name running) "; use force to reset"))}
      (nil? target) {:error (error 404 "Dev bot session not found")}
      :else
      (let [res (terminal/request (:config state) :post (str "/sessions/" (:id target) "/restart-app"))]
        (if (:error res)
          {:error (error 502 "Failed to restart dev bot session")}
          {:message (str "Dev bot reset in session " (:name target))
           :result {:session (:id target)}})))))

(defn- session-data-promote
  [state session-id input]
  (let [target (or (v/param-value input :session/id)
                   (v/param-value input :session-id)
                   (v/param-value input :session_id)
                   (v/param-value input :id)
                   session-id)]
    (if (str/blank? (str target))
      {:error (error 400 "Session id is required")}
      (let [session-res (terminal/request (:config state) :get (str "/sessions/" target))
            logs-dir (get-in session-res [:body :session :logs-dir])]
        (cond
          (:error session-res)
          (let [status (or (:status session-res) 502)
                message (if (= status 404) "Session not found" "Terminal service unavailable")]
            {:error (error status message)})
          (str/blank? (str logs-dir)) {:error (error 404 "Session logs directory not found")}
          :else
          (try
            (promote/promote-session-data! state target logs-dir)
            (catch Exception e
              (let [data (ex-data e)
                    status (or (:status data) 500)
                    message (or (:message data) (.getMessage e))
                    details (:details data)]
                {:error (error status message details)}))))))))

(defn execute-command!
  [state session-id command actor]
  (let [command-type (normalize-type (:type command))
        input (or (:input command) {})]
    (cond
      (str/blank? command-type)
      {:error (error 400 "Command type is required")}

      (= command-type "context.add")
      (context-add state input)

      (= command-type "file.upload")
      (file-upload state actor input)

      (= command-type "devbot.reset")
      (devbot-reset state session-id input)

      (= command-type "session.data-promote")
      (session-data-promote state session-id input)

      :else
      (if-let [action-id (get action-types command-type)]
        (run-action state action-id actor input)
        {:error (error 400 (str "Unsupported command type: " command-type))}))))

(defn command->message
  [command result]
  (let [command-type (normalize-type (:type command))]
    (cond
      (:message result) (str "[command] " (:message result))
      (:error result) (str "[command-error] " (format-error command-type (:error result)))
      (:result result) (str "[command-ok] " (format-result command-type (:result result)))
      :else (str "[command-ok] " (or command-type "command") " completed"))))
