(ns darelwasl.http.routes.agent-control
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.auth :as auth]
            [darelwasl.http.common :as common])
  (:import (java.io File)
           (java.time Instant ZoneOffset)
           (java.util UUID)))

(def ^:private lock (Object.))

(defn- now-iso []
  (.toString (Instant/now)))

(defn- data-dir []
  (or (some-> (System/getenv "AGENT_CONTROL_DATA_DIR") str/trim not-empty)
      "data/agent-control"))

(defn- runs-dir []
  (io/file (data-dir) "runs"))

(defn- run-dir [run-id]
  (io/file (runs-dir) run-id))

(defn- run-path [run-id]
  (io/file (run-dir run-id) "run.json"))

(defn- ensure-parent! [^File file]
  (let [parent (.getParentFile file)]
    (when (and parent (not (.exists parent)))
      (.mkdirs parent))
    file))

(defn- read-json [^File file]
  (try
    (when (.exists file)
      (json/read-str (slurp file) :key-fn keyword))
    (catch Exception e
      (log/warn e "Failed to read json" {:path (.getPath file)})
      nil)))

(defn- write-json! [^File file data]
  (let [tmp (io/file (str (.getPath file) ".tmp"))]
    (ensure-parent! file)
    (spit tmp (json/write-str data :escape-slash false))
    (.renameTo tmp file)))

(defn- kebab-id? [s]
  (boolean (re-matches #"[a-z0-9][a-z0-9-]{2,48}" (str s))))

(defn- new-run-id []
  (let [suffix (subs (str (UUID/randomUUID)) 0 8)
        ts (.format (.withZone (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss") ZoneOffset/UTC) (Instant/now))]
    (str "run-" ts "-" suffix)))

(defn- admin-usernames []
  (let [raw (or (System/getenv "AGENT_CONTROL_ADMIN_USERNAMES") "damjan,huda")]
    (->> (str/split raw #",")
         (map str/trim)
         (remove str/blank?)
         set)))

(defn- require-agent-admin
  [handler]
  (fn [request]
    (let [session (:auth/session request)
          uname (:user/username session)
          allowed? (contains? (admin-usernames) uname)]
      (if allowed?
        (handler request)
        (common/error-response 403 "Forbidden: admin allowlist required")))))

(defn- list-runs []
  (let [dir (runs-dir)]
    (when-not (.exists ^File dir)
      (.mkdirs ^File dir))
    (->> (.listFiles ^File dir)
         (filter #(.isDirectory ^File %))
         (map (fn [^File d]
                (or (read-json (io/file d "run.json"))
                    {:id (.getName d) :status "missing"})))
         (sort-by (fn [m] (or (:updated_at m) (:created_at m) "")))
         reverse
         vec)))

(defn- upsert-run!
  [run-id f]
  (locking lock
    (let [path (run-path run-id)
          current (or (read-json path) {:id run-id})
          next (-> (f current)
                   (assoc :id run-id)
                   (assoc :updated_at (now-iso)))]
      (write-json! path next)
      next)))

(defn- jobs-dir [run-id]
  (io/file (run-dir run-id) "jobs"))

(defn- job-log-path [run-id job-id]
  (io/file (jobs-dir run-id) (str job-id ".log")))

(defn- append-job!
  [run-id job]
  (upsert-run! run-id
               (fn [r]
                 (update r :jobs (fnil conj []) job))))

(defn- update-job!
  [run-id job-id f]
  (upsert-run! run-id
               (fn [r]
                 (update r :jobs
                         (fn [jobs]
                           (mapv (fn [j]
                                   (if (= (:id j) job-id)
                                     (f j)
                                     j))
                                 (or jobs [])))))))

(defn- create-run-handler
  [_state]
  (fn [request]
    (let [body (or (:body-params request) {})
          requested-id (some-> (or (:id body) (get body "id")) str/trim not-empty)
          message (some-> (or (:message body) (get body "message")) str)
          mode (keyword (or (:mode body) (get body "mode") "both"))
          run-id (or requested-id (new-run-id))]
      (cond
        (not (kebab-id? run-id))
        (common/error-response 400 "Invalid run id (use kebab-case a-z0-9- length 3..49)")

        (.exists (run-path run-id))
        (common/error-response 409 "Run id already exists")

        :else
        (let [run {:id run-id
                   :created_at (now-iso)
                   :updated_at (now-iso)
                   :status "draft"
                   :mode (name mode)
                   :message message
                   :revisions []
                   :preview {:status "none"}
                   :jobs []}]
          (write-json! (run-path run-id) run)
          {:status 201 :body run})))))

(defn- get-run-handler
  [_state]
  (fn [request]
    (let [run-id (get-in request [:path-params :id])
          data (read-json (run-path run-id))]
      (if data
        {:status 200 :body data}
        (common/error-response 404 "Run not found")))))

(defn- list-runs-handler
  [_state]
  (fn [_request]
    {:status 200
     :body {:runs (list-runs)}}))

(defn- revise-run-handler
  [_state]
  (fn [request]
    (let [run-id (get-in request [:path-params :id])
          body (or (:body-params request) {})
          msg (some-> (or (:message body) (get body "message")) str)
          updated (upsert-run! run-id
                               (fn [r]
                                 (-> r
                                     (assoc :status "changes_requested")
                                     (update :revisions (fnil conj [])
                                             {:at (now-iso) :message msg}))))]
      {:status 200 :body updated})))

(defn- start-preview-handler
  [_state]
  (fn [request]
    (let [run-id (get-in request [:path-params :id])
          body (or (:body-params request) {})
          mode (str/lower-case (str (or (:mode body) (get body "mode") "both")))
          job-id (str (UUID/randomUUID))
          log-file (job-log-path run-id job-id)
          job {:id job-id
               :kind "preview_start"
               :status "running"
               :started_at (now-iso)
               :log_path (.getPath ^File log-file)}
          _ (append-job! run-id job)
          _ (upsert-run! run-id (fn [r] (assoc r :status "previewing")))]
      (future
        (try
          (ensure-parent! log-file)
          (spit log-file (str "[agent-control] starting preview " run-id " mode=" mode "\n") :append true)
          (let [cmd-str (str "scripts/preview start " (pr-str run-id)
                             " --mode " (pr-str mode)
                             " --public-host https://haloeddepth.com")
                cmd ["bash" "-lc" cmd-str]
                pb (ProcessBuilder. ^java.util.List cmd)]
            (.directory pb (io/file (System/getProperty "user.dir")))
            (.redirectErrorStream pb true)
            (.redirectOutput pb log-file)
            (let [p (.start pb)
                  code (.waitFor p)]
              (if (zero? code)
                (let [out (read-json (io/file "target/previews" run-id "preview.json"))
                      expires-at (:expires_at out)
                      urls (:urls out)
                      last-updated (:last_preview_updated_at out)]
                  (upsert-run! run-id
                               (fn [r]
                                 (-> r
                                     (assoc :status "waiting_review")
                                     (assoc :preview {:status "ready"
                                                      :urls urls
                                                      :expires_at expires-at
                                                      :last_updated_at last-updated})))))
                (upsert-run! run-id (fn [r] (assoc r :status "error" :error "Preview start failed"))))
              (update-job! run-id job-id
                           (fn [j]
                             (assoc j
                                    :status (if (zero? code) "done" "error")
                                    :finished_at (now-iso)
                                    :exit code)))))
          (catch Exception e
            (log/error e "preview start job failed")
            (update-job! run-id job-id
                         (fn [j]
                           (assoc j :status "error"
                                    :finished_at (now-iso)
                                    :error (.getMessage e))))
            (upsert-run! run-id (fn [r] (assoc r :status "error" :error (.getMessage e)))))))
      {:status 202 :body {:status "accepted" :job job-id :run_id run-id}})))

(defn- trash-run-handler
  [_state]
  (fn [request]
    (let [run-id (get-in request [:path-params :id])
          job-id (str (UUID/randomUUID))
          log-file (job-log-path run-id job-id)
          job {:id job-id :kind "trash" :status "running" :started_at (now-iso) :log_path (.getPath ^File log-file)}]
      (append-job! run-id job)
      (future
        (try
          (ensure-parent! log-file)
          (spit log-file (str "[agent-control] trash " run-id "\n") :append true)
          (let [cmd-str (str "scripts/preview stop " (pr-str run-id))
                cmd ["bash" "-lc" cmd-str]
                pb (ProcessBuilder. ^java.util.List cmd)]
            (.directory pb (io/file (System/getProperty "user.dir")))
            (.redirectErrorStream pb true)
            (.redirectOutput pb log-file)
            (let [p (.start pb)
                  code (.waitFor p)]
              (upsert-run! run-id (fn [r] (assoc r :status "trashed" :trashed_at (now-iso))))
              (update-job! run-id job-id (fn [j] (assoc j :status "done" :finished_at (now-iso) :exit code)))))
          (catch Exception e
            (log/error e "trash job failed")
            (update-job! run-id job-id (fn [j] (assoc j :status "error" :finished_at (now-iso) :error (.getMessage e)))))))
      {:status 202 :body {:status "accepted" :job job-id :run_id run-id}})))

(defn- accept-run-handler
  [_state]
  (fn [request]
    (let [run-id (get-in request [:path-params :id])
          job-id (str (UUID/randomUUID))
          log-file (job-log-path run-id job-id)
          job {:id job-id :kind "promote" :status "running" :started_at (now-iso) :log_path (.getPath ^File log-file)}]
      (append-job! run-id job)
      (upsert-run! run-id (fn [r] (assoc r :status "accepted" :accepted_at (now-iso))))
      (future
        (try
          (ensure-parent! log-file)
          (spit log-file (str "[agent-control] accept+promote " run-id "\n") :append true)
          (let [cmd-str (str "scripts/preview respond " (pr-str run-id) " accept")
                cmd ["bash" "-lc" cmd-str]
                pb (ProcessBuilder. ^java.util.List cmd)]
            (.directory pb (io/file (System/getProperty "user.dir")))
            (.redirectErrorStream pb true)
            (.redirectOutput pb log-file)
            (let [p (.start pb)
                  code (.waitFor p)]
              (upsert-run! run-id (fn [r] (assoc r :status (if (zero? code) "promoted" "error"))))
              (update-job! run-id job-id (fn [j] (assoc j :status (if (zero? code) "done" "error") :finished_at (now-iso) :exit code)))))
          (catch Exception e
            (log/error e "promote job failed")
            (update-job! run-id job-id (fn [j] (assoc j :status "error" :finished_at (now-iso) :error (.getMessage e))))
            (upsert-run! run-id (fn [r] (assoc r :status "error" :error (.getMessage e)))))))
      {:status 202 :body {:status "accepted" :job job-id :run_id run-id}})))

(defn- job-log-handler
  [_state]
  (fn [request]
    (let [run-id (get-in request [:path-params :id])
          job-id (get-in request [:path-params :job])
          file (job-log-path run-id job-id)]
      (if-not (.exists ^File file)
        (common/error-response 404 "Job log not found")
        {:status 200
         :headers {"Content-Type" "text/plain; charset=utf-8"}
         :body (slurp file)}))))

(defn- admins-handler
  [state]
  (fn [_request]
    (let [users (:auth/users state)
          allow (admin-usernames)
          known (->> users
                     (map auth/sanitize-user)
                     (map (fn [u]
                            (assoc u :agent-control/admin? (contains? allow (:user/username u)))))
                     vec)]
      {:status 200
       :body {:admins (vec allow)
              :users known}})))

(defn routes
  [state]
  [["/agent-control"
    {:middleware [common/require-session
                  (common/require-roles #{:role/admin})
                  require-agent-admin]}
    ["/admins" {:get (admins-handler state)}]
    ["/runs" {:get (list-runs-handler state)
              :post (create-run-handler state)}]
    ["/runs/:id" {:get (get-run-handler state)}]
    ["/runs/:id/revise" {:post (revise-run-handler state)}]
    ["/runs/:id/preview/start" {:post (start-preview-handler state)}]
    ["/runs/:id/accept" {:post (accept-run-handler state)}]
    ["/runs/:id/trash" {:post (trash-run-handler state)}]
    ["/runs/:id/jobs/:job/log" {:get (job-log-handler state)}]]])
