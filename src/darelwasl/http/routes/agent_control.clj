(ns darelwasl.http.routes.agent-control
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.actions :as actions]
            [darelwasl.auth :as auth]
            [darelwasl.http.common :as common])
  (:import (java.io File)
           (java.time Instant ZoneOffset)
           (java.util UUID)))

(def ^:private lock (Object.))

(defn- now-iso []
  (.toString (Instant/now)))

(defn- storage-dir
  [state]
  (get-in state [:config :files :storage-dir]))

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

(defn- clamp
  [s max-len]
  (let [t (str (or s ""))]
    (if (<= (count t) max-len) t (subs t 0 max-len))))

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

(defn- latest-job
  [run]
  (when (map? run)
    (let [jobs (vec (or (:jobs run) []))]
      (when (seq jobs) (last jobs)))))

(defn- any-job-running?
  [run]
  (boolean
   (some #(= "running" (:status %))
         (or (:jobs run) []))))

(defn- can-promote?
  [run]
  (let [status (or (:status run) "")
        preview-status (get-in run [:preview :status])
        job (latest-job run)]
    (and (= status "waiting_review")
         (= preview-status "ready")
         (not (any-job-running? run))
         (or (nil? job)
             (and (= "done" (:status job))
                  (or (nil? (:exit job)) (zero? (long (:exit job)))))))))

(defn- decorate-run
  [run]
  (when (map? run)
    (let [job (latest-job run)]
      (assoc run
             :can_promote (boolean (can-promote? run))
             :latest_job (when (map? job)
                           (select-keys job [:id :kind :status :started_at :finished_at :exit :error :log_path]))))))

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
          title (some-> (or (:title body) (get body "title")) str)
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
                   :title title
                   :message (or message "")
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
        {:status 200 :body (decorate-run data)}
        (common/error-response 404 "Run not found")))))

(defn- list-runs-handler
  [_state]
  (fn [_request]
    {:status 200
     :body {:runs (mapv decorate-run (list-runs))}}))

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
                                             {:at (now-iso) :message (clamp msg 2000)}))))]
      {:status 200 :body (decorate-run updated)})))

(defn- clear-site-refs-handler
  [_state]
  (fn [request]
    (let [run-id (get-in request [:path-params :id])
          updated (upsert-run! run-id (fn [r] (assoc r :site_refs [])))]
      {:status 200 :body (decorate-run updated)})))

(defn- delete-site-ref-handler
  [_state]
  (fn [request]
    (let [run-id (get-in request [:path-params :id])
          ref-id (get-in request [:path-params :ref])
          updated (upsert-run! run-id
                               (fn [r]
                                 (update r :site_refs
                                         (fn [refs]
                                           (->> (or refs [])
                                                (remove #(= (str (:id %)) (str ref-id)))
                                                vec)))))]
      {:status 200 :body (decorate-run updated)})))

(defn- clear-site-assets-handler
  [_state]
  (fn [request]
    (let [run-id (get-in request [:path-params :id])
          updated (upsert-run! run-id (fn [r] (assoc r :site_assets [])))]
      {:status 200 :body (decorate-run updated)})))

(defn- delete-site-asset-handler
  [_state]
  (fn [request]
    (let [run-id (get-in request [:path-params :id])
          asset-id (get-in request [:path-params :asset])
          updated (upsert-run! run-id
                               (fn [r]
                                 (update r :site_assets
                                         (fn [assets]
                                           (->> (or assets [])
                                                (remove #(= (str (:id %)) (str asset-id)))
                                                vec)))))]
      {:status 200 :body (decorate-run updated)})))

(defn- upload-site-asset-handler
  [state]
  (fn [request]
    (let [run-id (get-in request [:path-params :id])
          params (merge (:params request) (:multipart-params request))
          upload (or (get params "file") (get params :file))
          note (some-> (or (get params "note") (get params :note)) str)
          slug (some-> (or (get params "slug") (get params :slug)) str)
          run (read-json (run-path run-id))]
      (cond
        (nil? run)
        (common/error-response 404 "Run not found")

        (nil? upload)
        (common/error-response 400 "File is required")

        :else
        (let [workspace (common/workspace-id request)
              res (actions/execute! state {:action/id :cap/action/file-upload
                                           :actor (actions/actor-from-session (:auth/session request) workspace)
                                           :input {:file/upload upload
                                                   :file/slug slug
                                                   :storage-dir (storage-dir state)}})]
          (if-let [err (:error res)]
            (common/error-response (or (:status err) 500)
                                   (:message err)
                                   (:details err))
            (let [file (:file (:result res))
                  asset {:id (str (:file/id file))
                         :at (now-iso)
                         :name (:file/name file)
                         :ref (:file/ref file)
                         :mime (:file/mime file)
                         :url (:file/url file)
                         :note note}
                  updated (upsert-run! run-id (fn [r] (update r :site_assets (fnil conj []) asset)))]
              {:status 201 :body (decorate-run updated)})))))))

(defn- start-preview-handler
  [_state]
  (fn [request]
    (let [run-id (get-in request [:path-params :id])
          body (or (:body-params request) {})
          mode (str/lower-case (str (or (:mode body) (get body "mode") "both")))
          apply? (boolean (or (:apply body) (get body "apply")))
          new-message (some-> (or (:message body) (get body "message")) str)
          run0 (read-json (run-path run-id))
          run (if (and (not (nil? run0)) (not (str/blank? (str/trim (or new-message "")))))
                (upsert-run! run-id (fn [r] (assoc r :message new-message)))
                run0)]
      (cond
        (nil? run)
        (common/error-response 404 "Run not found")

        :else
        (let [request-text (or (some-> new-message str/trim not-empty)
                               (some-> (:message run) str/trim not-empty)
                               "")
              refs (vec (or (:site_refs run) []))
              refs-with-notes (->> refs (filter (fn [r] (not (str/blank? (some-> (:note r) str/trim))))) vec)
              assets (vec (or (:site_assets run) []))
              assets-with-notes (->> assets (filter (fn [a] (not (str/blank? (some-> (:note a) str/trim))))) vec)
              base-request (cond
                             (not (str/blank? request-text)) request-text
                             (seq refs-with-notes) "Apply the changes described in the reference points below."
                             (seq assets-with-notes) "Use the uploaded assets (and their notes) below to make the requested changes."
                             :else "")
              refs-block (when (seq refs)
                           (str "\n\nReference points (selected on preview):\n"
                                (apply str
                                       (for [r refs]
                                         (str "- url: " (or (:url r) "") "\n"
                                              "  text: " (or (:text r) "") "\n"
                                              (when-not (str/blank? (or (:selector r) ""))
                                                (str "  selector: " (:selector r) "\n"))
                                              (when-not (str/blank? (or (:note r) ""))
                                                (str "  note: " (:note r) "\n")))))))
              assets-block (when (seq assets)
                             (str "\n\nAssets uploaded for this run:\n"
                                  (apply str
                                         (for [a assets]
                                           (str "- name: " (or (:name a) "") "\n"
                                                "  ref: " (or (:ref a) "") "\n"
                                                (when-not (str/blank? (or (:url a) ""))
                                                  (str "  url: " (:url a) "\n"))
                                                (when-not (str/blank? (or (:local_path a) ""))
                                                  (str "  local_path: " (:local_path a) "\n"))
                                                (when-not (str/blank? (or (:note a) ""))
                                                  (str "  note: " (:note a) "\n")))))))
              website-request (when (and apply?
                                         (or (= mode "site") (= mode "both"))
                                         (not (str/blank? base-request)))
                                (str base-request (or refs-block "") (or assets-block "")))
              preview-manifest (io/file "target/previews" run-id "preview.json")
              reset-to-ref? (not (.exists ^File preview-manifest))]
          (if (and apply?
                   (or (= mode "site") (= mode "both"))
                   (str/blank? (or website-request "")))
            (common/error-response 400 "Nothing to apply yet. Add a Request or add notes to reference points, then click Apply changes.")
            (let [_ (when (and (or (= mode "site") (= mode "both"))
                               (not (str/blank? (or website-request ""))))
                      (upsert-run! run-id (fn [r] (assoc r :message request-text))))
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
                                     " --ref " (pr-str "main")
                                     (when reset-to-ref?
                                       " --reset-to-ref")
                                     " --mode " (pr-str mode)
                                     (when (and (or (= mode "site") (= mode "both"))
                                                (not (str/blank? (or website-request ""))))
                                       (str " --website-request " (pr-str website-request)
                                            " --website-agent-json " (pr-str "agents/website/AGENT.json")))
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
              {:status 202 :body {:status "accepted" :job job-id :run_id run-id}})))))))

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
          run (read-json (run-path run-id))]
      (cond
        (nil? run)
        (common/error-response 404 "Run not found")

        (not (can-promote? run))
        (common/error-response 400 "Run is not ready to promote. Wait for a ready preview and completed jobs, then accept.")

        :else
        (let [job-id (str (UUID/randomUUID))
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
          {:status 202 :body {:status "accepted" :job job-id :run_id run-id}})))))

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
    ["/runs/:id/site-refs/clear" {:post (clear-site-refs-handler state)}]
    ["/runs/:id/site-refs/:ref" {:delete (delete-site-ref-handler state)}]
    ["/runs/:id/site-assets/clear" {:post (clear-site-assets-handler state)}]
    ["/runs/:id/site-assets" {:post (upload-site-asset-handler state)}]
    ["/runs/:id/site-assets/:asset" {:delete (delete-site-asset-handler state)}]
    ["/runs/:id/preview/start" {:post (start-preview-handler state)}]
    ["/runs/:id/accept" {:post (accept-run-handler state)}]
    ["/runs/:id/trash" {:post (trash-run-handler state)}]
    ["/runs/:id/jobs/:job/log" {:get (job-log-handler state)}]]])
