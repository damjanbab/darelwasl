(ns darelwasl.webterm.http
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [darelwasl.webterm.config :as cfg]
            [darelwasl.webterm.fs :as fs]
            [darelwasl.webterm.tmux :as tmux]
            [darelwasl.webterm.ui :as ui]
            [ring.middleware.cookies :as cookies]
            [ring.middleware.multipart-params :as multipart]
            [ring.middleware.params :as params]
            [ring.util.codec :as codec]
            [ring.util.response :as resp])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.nio.file Files Path Paths)
           (java.time Instant)
           (java.util.concurrent TimeUnit)))

(defn- parse-int
  [s default]
  (try
    (Integer/parseInt (str s))
    (catch Exception _ default)))

(defn- clamp-session
  [cfg n]
  (fs/clamp n 1 (:terminal-count cfg)))

(defn- cookie
  [req k]
  (get-in req [:cookies k :value]))

(defn- lab-session
  [cfg req]
  (let [stable (:lab-stable-session cfg)
        qp (get-in req [:query-params "session"])
        n (when (some? qp) (parse-int qp stable))
        n (if (some? qp) n (parse-int (cookie req "dw_lab_session") stable))]
    (clamp-session cfg n)))

(defn- ui-role
  [cfg]
  (if (= (:public-base-path cfg) "/canary") "canary" "stable"))

(defn- run-proc
  [{:keys [cmd timeout-ms cwd]}]
  (let [pb (ProcessBuilder. ^java.util.List cmd)
        _ (.redirectErrorStream pb false)
        _ (when cwd (.directory pb (java.io.File. (str cwd))))
        proc (.start pb)
        out (ByteArrayOutputStream.)
        err (ByteArrayOutputStream.)]
    (future (with-open [is (.getInputStream proc)] (.transferTo is out)))
    (future (with-open [is (.getErrorStream proc)] (.transferTo is err)))
    (let [ok (.waitFor proc (long timeout-ms) TimeUnit/MILLISECONDS)]
      (when-not ok
        (.destroyForcibly proc)
        (throw (ex-info "process timeout" {:cmd cmd :timeout-ms timeout-ms :cwd cwd})))
      {:exit (.exitValue proc)
       :out (.toString out "UTF-8")
       :err (.toString err "UTF-8")})))

(defn json-response
  [payload]
  (-> (resp/response (json/write-str payload))
      (resp/content-type "application/json; charset=utf-8")
      (resp/header "Cache-Control" "no-store, max-age=0")
      (resp/header "Pragma" "no-cache")))

(defn text-response
  [s]
  (-> (resp/response (str s))
      (resp/content-type "text/plain; charset=utf-8")
      (resp/header "Cache-Control" "no-store, max-age=0")
      (resp/header "Pragma" "no-cache")))

(defn html-response
  [s]
  (-> (resp/response s)
      (resp/content-type "text/html; charset=utf-8")
      (resp/header "Cache-Control" "no-store, max-age=0")
      (resp/header "Pragma" "no-cache")))

(defn- file-response
  [^Path path content-type disposition filename]
  (let [^java.nio.file.attribute.BasicFileAttributes st (Files/readAttributes path java.nio.file.attribute.BasicFileAttributes (make-array java.nio.file.LinkOption 0))
        size (.size st)
        body (Files/newInputStream path (make-array java.nio.file.OpenOption 0))]
    {:status 200
     :headers {"Content-Type" content-type
               "Content-Length" (str size)
               "Content-Disposition" (str disposition "; filename=\"" (str/replace filename #"\"" "_") "\"")}
     :body body}))

(defn- guess-content-type
  [name]
  (let [ext (some-> name str/lower-case (re-find #"\.[a-z0-9]+$"))]
    (cond
      (= ext ".pdf") "application/pdf"
      (= ext ".png") "image/png"
      (or (= ext ".jpg") (= ext ".jpeg")) "image/jpeg"
      (= ext ".gif") "image/gif"
      (= ext ".webp") "image/webp"
      (= ext ".svg") "image/svg+xml"
      (contains? #{".txt" ".md" ".markdown" ".log" ".json" ".edn" ".csv"} ext) "text/plain; charset=utf-8"
      :else "application/octet-stream")))

(defn- ensure-lab!
  [cfg sess]
  (let [name (tmux/session-name cfg sess)]
    (tmux/ensure-session! cfg sess)
    (fs/ensure-dirs! (:lab-dir cfg) name)
    name))

(defn- repo-path
  ^Path
  [cfg & parts]
  (Paths/get (:repo-root cfg) (into-array String parts)))

(defn- work-file-path
  ^Path
  [cfg id]
  (repo-path cfg "docs" "work" (str id ".md")))

(defn- read-work-header
  [cfg id]
  (let [p (work-file-path cfg id)]
    (when (Files/exists p (make-array java.nio.file.LinkOption 0))
      (try
        (with-open [r (java.io.BufferedReader.
                       (java.io.InputStreamReader.
                        (Files/newInputStream p (make-array java.nio.file.OpenOption 0)) "UTF-8"))]
          (loop [i 0 acc {}]
            (if (>= i 120)
              acc
              (if-let [line (.readLine r)]
                (let [line (str/trim line)]
                  (if (str/blank? line)
                    acc
                    (if-let [[_ k v] (re-matches #"^([a-zA-Z0-9_./-]+):\\s*(.*)$" line)]
                      (recur (inc i) (assoc acc (keyword k) (str/trim v)))
                      (recur (inc i) acc))))
                acc))))
        (catch Exception _ nil)))))

(def ^:private canary-proctor-paths
  ["src/darelwasl/webterm/"
   "ops/webterm-ui/"
   "scripts/webterm-ui.sh"
   "docs/ops/code-haloeddepth-com.md"
   "policies/lab-canary-upgrades.md"])

(defn- starts-with-any?
  [s prefixes]
  (some (fn [p]
          (or (= s p) (str/starts-with? s p)))
        prefixes))

(defn- changed-files
  [worktree base]
  (let [base (or (some-> base str/trim) "main")
        try-ref (fn [ref]
                  (try
                    (let [{:keys [exit out]} (run-proc {:cmd ["git" "diff" "--name-only" (str ref "...HEAD")]
                                                        :cwd worktree
                                                        :timeout-ms 15000})]
                      (when (zero? exit)
                        (->> (str/split-lines (or out ""))
                             (map str/trim)
                             (remove str/blank?)
                             (vec))))
                    (catch Exception _ nil)))]
    (or (try-ref base)
        (try-ref (str "origin/" base))
        [])))

(defn- required-proctor
  [cfg work-id header]
  (let [explicit (some-> (get header :work/proctor) str/lower-case str/trim)]
    (cond
      (#{"stable" "canary"} explicit)
      {:required explicit
       :reason "work/proctor"}

      :else
      (let [wt (get header :work/worktree)
            base (get header :work/base)
            files (when (and (string? wt) (not (str/blank? wt))) (changed-files wt base))
            trigger (first (filter #(starts-with-any? % canary-proctor-paths) files))]
        (if trigger
          {:required "canary"
           :reason (str "changed " trigger)
           :changed files}
          {:required "stable"
           :reason "default"
           :changed files})))))

(defn- proctor-href
  [cfg which]
  (if (= which "canary")
    (str "/canary/lab?session=" (:lab-canary-session cfg))
    (str "/lab?session=" (:lab-stable-session cfg))))

(defn- handle-work-requirements
  [cfg req]
  (let [id (fs/safe-segment (get-in req [:query-params "id"]))
        header (when id (read-work-header cfg id))]
    (if-not (and id header)
      (-> (json-response {:ok false :message "work not found"}) (assoc :status 404))
      (let [{:keys [required reason]} (required-proctor cfg id header)
            current (ui-role cfg)]
        (json-response {:ok (= required current)
                        :work_id id
                        :required_proctor required
                        :current_proctor current
                        :reason reason
                        :required_href (proctor-href cfg required)})))))

(defn- read-json-body
  [req]
  (let [body-text (try
                    (let [b (:body req)]
                      (cond
                        (nil? b) ""
                        (instance? java.io.InputStream b) (with-open [r (java.io.InputStreamReader. ^java.io.InputStream b "UTF-8")] (slurp r))
                        (instance? java.io.Reader b) (slurp ^java.io.Reader b)
                        :else (str b)))
                    (catch Exception _ ""))]
    (try (json/read-str body-text) (catch Exception _ {}))))

(defn- take-lines
  [s n]
  (->> (str/split-lines (or s ""))
       (take n)
       (str/join "\n")))

(defn- json-error
  [status payload]
  (-> (json-response payload)
      (assoc :status status)))

(defn- handle-work-signoff
  [cfg req]
  (let [obj (read-json-body req)
        id (fs/safe-segment (or (get obj "id") (get obj "work") (get-in req [:query-params "id"])))
        header (when id (read-work-header cfg id))
        base-err (cond
                   (nil? id) (json-error 400 {:ok false :message "missing work id"})
                   (nil? header) (json-error 404 {:ok false :message "work not found" :work_id id})
                   :else nil)]
    (if base-err
      base-err
      (let [{:keys [required reason]} (required-proctor cfg id header)
            current (ui-role cfg)
            required-href (proctor-href cfg required)
            wt (some-> (get header :work/worktree) str/trim)
            wt-path (when (and (string? wt) (not (str/blank? wt))) (Paths/get wt (make-array String 0)))
            err (cond
                  (not= required current)
                  (json-error 409 {:ok false
                                   :message "wrong proctor channel"
                                   :work_id id
                                   :required_proctor required
                                   :current_proctor current
                                   :required_href required-href})

                  (str/blank? wt)
                  (json-error 412 {:ok false
                                   :message "work has no worktree (run: scripts/work.sh start <id>)"
                                   :work_id id})

                  (not (and wt-path
                            (Files/isDirectory wt-path (make-array java.nio.file.LinkOption 0))
                            (Files/exists (.resolve wt-path ".git") (make-array java.nio.file.LinkOption 0))))
                  (json-error 412 {:ok false
                                   :message "worktree path missing or not a git checkout"
                                   :work_id id})

                  :else nil)]
        (if err
          err
          (let [{:keys [exit out]} (run-proc {:cmd ["git" "status" "--porcelain=v1"]
                                              :cwd wt
                                              :timeout-ms 15000})
                err (cond
                      (not (zero? exit)) (json-error 500 {:ok false :message "git status failed" :work_id id})
                      (not (str/blank? (str/trim out))) (json-error 412 {:ok false :message "worktree is dirty; commit first" :work_id id})
                      :else nil)]
            (if err
              err
              (let [gov (run-proc {:cmd ["bash" "scripts/checks.sh" "governance"]
                                   :cwd wt
                                   :timeout-ms 600000})
                    err (when-not (zero? (:exit gov))
                          (json-error 412 {:ok false
                                           :message "checks failed: governance"
                                           :details (take-lines (str (:out gov) "\n" (:err gov)) 80)
                                           :work_id id}))]
                (if err
                  err
                  (let [docs (run-proc {:cmd ["bash" "scripts/checks.sh" "docs"]
                                        :cwd wt
                                        :timeout-ms 600000})
                        err (when-not (zero? (:exit docs))
                              (json-error 412 {:ok false
                                               :message "checks failed: docs"
                                               :details (take-lines (str (:out docs) "\n" (:err docs)) 80)
                                               :work_id id}))]
                    (if err
                      err
                      (let [pr (run-proc {:cmd ["bash" "scripts/work.sh" "pr-create" id]
                                          :cwd wt
                                          :timeout-ms 240000})
                            pr-url (some->> (str (:out pr) "\n" (:err pr))
                                            (re-find #"https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/pull/\d+"))
                            err (when-not (and (zero? (:exit pr)) (string? pr-url) (not (str/blank? pr-url)))
                                  (json-error 500 {:ok false
                                                   :message "PR create failed"
                                                   :details (take-lines (str (:out pr) "\n" (:err pr)) 120)
                                                   :work_id id}))]
                        (if err
                          err
                          (let [sess (lab-session cfg req)
                                sname (ensure-lab! cfg sess)
                                dir (fs/ensure-work-dir! (:lab-dir cfg) sname id)
                                sha (let [{:keys [exit out]} (run-proc {:cmd ["git" "rev-parse" "HEAD"]
                                                                        :cwd wt
                                                                        :timeout-ms 15000})]
                                      (when (zero? exit) (str/trim out)))
                                content (str "# Sign off\n\n"
                                             "- work/id: " id "\n"
                                             "- pr: " pr-url "\n"
                                             "- proctor/required: " required "\n"
                                             "- proctor/current: " current "\n"
                                             "- reason: " reason "\n"
                                             (when (and sha (not (str/blank? sha))) (str "- git/sha: " sha "\n"))
                                             "- at: " (str (Instant/now)) "\n")]
                            (when dir
                              (fs/write-bytes! dir (str "signoff-" (System/currentTimeMillis) ".md") (.getBytes content "UTF-8")))
                            (json-response {:ok true
                                            :work_id id
                                            :required_proctor required
                                            :current_proctor current
                                            :pr_url pr-url
                                            :required_href required-href})))))))))))))))

(defn- list-work-items
  [cfg]
  (let [dir (repo-path cfg "docs" "work")]
    (if-not (Files/isDirectory dir (make-array java.nio.file.LinkOption 0))
      []
      (let [ds (Files/newDirectoryStream dir)]
        (try
          (->> ds
               (.iterator)
               (iterator-seq)
               (map (fn [^Path p]
                      (when (and (Files/isRegularFile p (make-array java.nio.file.LinkOption 0))
                                 (str/ends-with? (str (.getFileName p)) ".md"))
                        (let [fname (str (.getFileName p))]
                          (when-not (contains? #{"README.md" ".gitkeep"} fname)
                            (let [id (subs fname 0 (- (count fname) 3))
                                  ^java.nio.file.attribute.BasicFileAttributes st (Files/readAttributes p java.nio.file.attribute.BasicFileAttributes (make-array java.nio.file.LinkOption 0))
                                  ^java.nio.file.attribute.FileTime ft (.lastModifiedTime st)
                                  mtime-ms (.toMillis ft)
                                  header (try
                                           (with-open [r (java.io.BufferedReader.
                                                          (java.io.InputStreamReader.
                                                           (Files/newInputStream p (make-array java.nio.file.OpenOption 0)) "UTF-8"))]
                                             (loop [i 0 acc {}]
                                               (if (>= i 60)
                                                 acc
                                                 (if-let [line (.readLine r)]
                                                   (let [line (str/trim line)]
                                                     (if (str/blank? line)
                                                       acc
                                                       (if-let [[_ k v] (re-matches #"^([a-zA-Z0-9_./-]+):\\s*(.*)$" line)]
                                                         (recur (inc i) (assoc acc (keyword k) (str/trim v)))
                                                         (recur (inc i) acc))))
                                                   acc))))
                                           (catch Exception _ {}))]
                              (merge {:id id
                                      :mtime_ms mtime-ms}
                                     (when-let [s (get header :work/summary)] {:summary s})
                                     (when-let [t (get header :work/type)] {:type t})
                                     (when-let [st (get header :work/status)] {:status st})
                                     (when-let [pb (get header :work/playbook)] {:playbook pb})
                                     (when-let [br (get header :work/branch)] {:branch br})
                                     (when-let [ua (get header :work/updated_at)] {:updated_at ua})
                                     (when-let [wt (get header :work/worktree)] {:worktree wt})
                                     (when-let [bp (get header :work/base)] {:base bp}))))))))
               (remove nil?)
               (sort-by :mtime_ms >)
               (vec))
          (finally (.close ds)))))))

(defn- handle-work-list
  [cfg _req]
  (json-response {:items (list-work-items cfg)}))

(defn- handle-work-file
  [cfg req]
  (let [id (fs/safe-segment (get-in req [:query-params "id"]))
        p (when id (repo-path cfg "docs" "work" (str id ".md")))]
    (if-not (and p (Files/exists p (make-array java.nio.file.LinkOption 0)))
      (resp/not-found "not found\n")
      (-> (resp/response (slurp (str p) :encoding "UTF-8"))
          (resp/content-type "text/plain; charset=utf-8")))))

(defn- handle-sessions
  [cfg _req]
  (let [sessions (tmux/list-sessions cfg)]
    (json-response {:count (:terminal-count cfg)
                    :sessions (vec (for [n (range 1 (inc (:terminal-count cfg)))]
                                     {:n n
                                      :name (tmux/session-name cfg n)
                                      :exists (contains? sessions (tmux/session-name cfg n))
                                      :url (ui/xterm-url cfg n)}))})))

(defn- next-available
  [cfg]
  (let [sessions (tmux/list-sessions cfg)]
    (first (for [n (range 1 (inc (:terminal-count cfg)))
                 :let [name (tmux/session-name cfg n)]
                 :when (not (contains? sessions name))]
             n))))

(defn- redirect
  [location]
  {:status 302
   :headers {"Location" location}
   :body ""})

(defn- handle-lab
  [cfg req build-stamp]
  (let [sess (lab-session cfg req)]
    (ensure-lab! cfg sess)
    (-> (html-response (ui/lab-page cfg {:sess sess :build-stamp build-stamp :ui-role (ui-role cfg)}))
        (resp/header "Set-Cookie" (str "dw_lab_session=" sess "; Path=/; Max-Age=31536000; SameSite=Lax")))))

(defn- list-dir
  [cfg sess which]
  (let [sname (ensure-lab! cfg sess)
        {:keys [inbox outbox]} (fs/ensure-dirs! (:lab-dir cfg) sname)]
    (json-response
     {:lab_session sess
      :tmux_session sname
      which (fs/list-files (if (= which :inbox) inbox outbox))})))

(defn- handle-library-list
  [cfg req]
  (let [sess (lab-session cfg req)
        sname (ensure-lab! cfg sess)
        wid (fs/safe-segment (get-in req [:query-params "work"]))]
    (if-not wid
      (-> (text-response "missing work\n") (assoc :status 400))
      (let [dir (fs/ensure-work-dir! (:lab-dir cfg) sname wid)]
        (json-response {:lab_session sess
                        :tmux_session sname
                        :work_id wid
                        :items (mapv (fn [it] (assoc it :ref (str "work:" wid "/" (:name it))))
                                     (fs/list-files dir))})))))

(defn- handle-library-recent
  [cfg req]
  (let [sess (lab-session cfg req)
        sname (ensure-lab! cfg sess)
        limit (parse-int (get-in req [:query-params "limit"]) 80)
        {:keys [work-root]} (fs/ensure-dirs! (:lab-dir cfg) sname)]
    (json-response {:lab_session sess
                    :tmux_session sname
                    :items (fs/list-recent-work-files work-root limit)})))

(defn- handle-library-upload
  [cfg req]
  (let [sess (lab-session cfg req)
        sname (ensure-lab! cfg sess)
        wid (fs/safe-segment (get-in req [:query-params "work"]))
        f (get-in req [:params "file"])
        size (some-> f :size long)
        tempfile (some-> f :tempfile)
        filename (fs/safe-name (some-> f :filename) "upload")]
    (when-not wid
      (throw (ex-info "missing work" {:status 400})))
    (when (or (nil? tempfile) (not (.exists ^java.io.File tempfile)))
      (throw (ex-info "missing file" {})))
    (when (and size (> size (:lab-max-upload-bytes cfg)))
      (throw (ex-info "upload too large" {:status 413})))
    (let [dir (fs/ensure-work-dir! (:lab-dir cfg) sname wid)
          saved (fs/write-upload! dir filename tempfile)
          fname (str saved)]
      (json-response {:ok true
                      :work_id wid
                      :name fname
                      :ref (str "work:" wid "/" fname)
                      :size_bytes size
                      :written_at (str (Instant/now))}))))

(defn- handle-library-paste
  [cfg req]
  (let [sess (lab-session cfg req)
        sname (ensure-lab! cfg sess)
        body-text (try
                    (let [b (:body req)]
                      (cond
                        (nil? b) ""
                        (instance? java.io.InputStream b) (with-open [r (java.io.InputStreamReader. ^java.io.InputStream b "UTF-8")] (slurp r))
                        (instance? java.io.Reader b) (slurp ^java.io.Reader b)
                        :else (str b)))
                    (catch Exception _ ""))
        obj (try (json/read-str body-text) (catch Exception _ {}))
        wid (fs/safe-segment (get obj "work"))
        name (fs/safe-name (get obj "name") (str "paste-" (System/currentTimeMillis) ".txt"))
        content (or (get obj "content") "")
        raw (.getBytes (str content) "UTF-8")]
    (when-not wid
      (throw (ex-info "missing work" {:status 400})))
    (let [dir (fs/ensure-work-dir! (:lab-dir cfg) sname wid)
          saved (fs/write-bytes! dir name raw)
          fname (str saved)]
      (json-response {:ok true
                      :work_id wid
                      :name fname
                      :ref (str "work:" wid "/" fname)
                      :size_bytes (alength ^bytes raw)
                      :written_at (str (Instant/now))}))))

(defn- handle-library-file
  [cfg req disposition]
  (let [sess (lab-session cfg req)
        sname (ensure-lab! cfg sess)
        ref (get-in req [:query-params "ref"])
        p (fs/resolve-work-ref (:lab-dir cfg) sname ref)]
    (if-not (and p
                 (Files/exists p (make-array java.nio.file.LinkOption 0))
                 (Files/isRegularFile p (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS]))
                 (not (Files/isSymbolicLink p)))
      (resp/not-found "not found\n")
      (let [fn (fs/safe-name (str (.getFileName p)) "download")
            ctype (guess-content-type fn)]
        (file-response p ctype disposition fn)))))

(defn- handle-upload
  [cfg req build-stamp]
  (let [sess (lab-session cfg req)
        sname (ensure-lab! cfg sess)
        dir (some-> (get-in req [:query-params "dir"]) str/lower-case str/trim)
        dir (if (#{"inbox" "outbox"} dir) dir "outbox")
        {:keys [inbox outbox]} (fs/ensure-dirs! (:lab-dir cfg) sname)
        f (get-in req [:params "file"])
        size (some-> f :size long)
        tempfile (some-> f :tempfile)
        filename (fs/safe-name (some-> f :filename) "upload")]
    (when (or (nil? tempfile) (not (.exists ^java.io.File tempfile)))
      (throw (ex-info "missing file" {})))
    (when (and size (> size (:lab-max-upload-bytes cfg)))
      (throw (ex-info "upload too large" {:status 413})))
    (let [dest-dir (if (= dir "inbox") inbox outbox)
          saved (fs/write-upload! dest-dir filename tempfile)]
      (handle-lab cfg req build-stamp))))

(defn- handle-paste
  [cfg req]
  (let [sess (lab-session cfg req)
        sname (ensure-lab! cfg sess)
        {:keys [inbox outbox]} (fs/ensure-dirs! (:lab-dir cfg) sname)
        body-text (try
                    (let [b (:body req)]
                      (cond
                        (nil? b) ""
                        (instance? java.io.InputStream b) (with-open [r (java.io.InputStreamReader. ^java.io.InputStream b "UTF-8")] (slurp r))
                        (instance? java.io.Reader b) (slurp ^java.io.Reader b)
                        :else (str b)))
                    (catch Exception _ ""))
        obj (try (json/read-str body-text) (catch Exception _ {}))
        dir (some-> (get obj "dir") str/lower-case str/trim)
        dir (if (#{"inbox" "outbox"} dir) dir "inbox")
        name (fs/safe-name (get obj "name") (str "paste-" (System/currentTimeMillis) ".txt"))
        content (or (get obj "content") "")
        raw (.getBytes (str content) "UTF-8")
        dest-dir (if (= dir "inbox") inbox outbox)
        dest (fs/write-bytes! dest-dir name raw)]
    (json-response {:dir dir
                    :name (str dest)
                    :size_bytes (alength ^bytes raw)
                    :written_at (str (Instant/now))})))

(defn- handle-history
  [cfg req]
  (let [sess (lab-session cfg req)
        sname (ensure-lab! cfg sess)
        lines (parse-int (get-in req [:query-params "lines"]) (:lab-default-history-lines cfg))
        text (tmux/capture-history cfg sess lines)]
    (json-response {:lab_session sess
                    :tmux_session sname
                    :captured_at (str (Instant/now))
                    :lines_requested lines
                    :text text})))

(defn- handle-terminal-clear
  [cfg req]
  (let [sess (lab-session cfg req)
        sname (ensure-lab! cfg sess)]
    (tmux/clear-terminal! cfg sess)
    (json-response {:ok true :lab_session sess :tmux_session sname})))

(defn- handle-outbox-download
  [cfg req disposition]
  (let [sess (lab-session cfg req)
        sname (ensure-lab! cfg sess)
        name (get-in req [:query-params "name"])
        path (fs/resolve-outbox-path (:lab-dir cfg) sname name)]
    (if-not (and path
                 (Files/exists path (make-array java.nio.file.LinkOption 0))
                 (Files/isRegularFile path (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS]))
                 (not (Files/isSymbolicLink path)))
      (resp/not-found "not found\n")
      (let [fn (fs/safe-name name "download")
            ctype (guess-content-type fn)]
        (file-response path ctype disposition fn)))))

(defn app
  "Returns a Ring handler for the webterm UI (standalone service)."
  []
  (let [cfg (cfg/config)
        build-stamp (str (Instant/now))]
    (-> (fn [req]
          (try
            (let [uri (:uri req)
                  m (:request-method req)]
              (cond
                (and (= m :get) (= uri "/"))
                (if (= (ui-role cfg) "canary")
                  (redirect (ui/ui-url cfg (str "/lab?session=" (:lab-canary-session cfg))))
                  (html-response (ui/terminals-page cfg)))
                (and (= m :get) (= uri "/api/sessions")) (handle-sessions cfg req)
                (and (= m :get) (= uri "/api/work/list")) (handle-work-list cfg req)
                (and (= m :get) (= uri "/api/work/file")) (handle-work-file cfg req)
                (and (= m :get) (= uri "/api/work/requirements")) (handle-work-requirements cfg req)
                (and (= m :post) (= uri "/api/work/signoff")) (handle-work-signoff cfg req)
                (and (= m :get) (= uri "/new"))
                (if-let [n (next-available cfg)]
                  (do (tmux/ensure-session! cfg n) (redirect (ui/xterm-url cfg n)))
                  (-> (text-response "No free terminals.\n") (assoc :status 409)))
                (and (= m :get) (= uri "/open"))
                (let [n (parse-int (get-in req [:query-params "n"]) 0)]
                  (if (<= 1 n (:terminal-count cfg))
                    (do (tmux/ensure-session! cfg n) (redirect (ui/xterm-url cfg n)))
                    (-> (text-response "bad n\n") (assoc :status 400))))
                (and (= m :get) (= uri "/codex"))
                (let [n (parse-int (get-in req [:query-params "n"]) 0)]
                  (if (<= 1 n (:terminal-count cfg))
                    (do (tmux/ensure-session! cfg n) (tmux/start-codex! cfg n) (redirect (ui/xterm-url cfg n)))
                    (-> (text-response "bad n\n") (assoc :status 400))))
                (and (= m :get) (= uri "/kill"))
                (let [n (parse-int (get-in req [:query-params "n"]) 0)]
                  (if (<= 1 n (:terminal-count cfg))
                    (do (tmux/kill-session! cfg n) (html-response (ui/terminals-page cfg)))
                    (-> (text-response "bad n\n") (assoc :status 400))))

                (and (= m :get) (= uri "/lab")) (handle-lab cfg req build-stamp)
                (and (= m :get) (= uri "/api/lab/inbox")) (list-dir cfg (lab-session cfg req) :inbox)
                (and (= m :get) (= uri "/api/lab/outbox")) (list-dir cfg (lab-session cfg req) :outbox)
                (and (= m :get) (= uri "/api/lab/outbox/download")) (handle-outbox-download cfg req "attachment")
                (and (= m :get) (= uri "/api/lab/outbox/view")) (handle-outbox-download cfg req "inline")
                (and (= m :get) (= uri "/api/lab/history")) (handle-history cfg req)
                (and (= m :post) (= uri "/api/lab/terminal/clear")) (handle-terminal-clear cfg req)
                (and (= m :post) (= uri "/api/lab/upload")) (handle-upload cfg req build-stamp)
                (and (= m :post) (= uri "/api/lab/paste")) (handle-paste cfg req)

                (and (= m :get) (= uri "/api/library/list")) (handle-library-list cfg req)
                (and (= m :get) (= uri "/api/library/recent")) (handle-library-recent cfg req)
                (and (= m :get) (= uri "/api/library/view")) (handle-library-file cfg req "inline")
                (and (= m :get) (= uri "/api/library/download")) (handle-library-file cfg req "attachment")
                (and (= m :post) (= uri "/api/library/upload")) (handle-library-upload cfg req)
                (and (= m :post) (= uri "/api/library/paste")) (handle-library-paste cfg req)

                :else (resp/not-found "not found\n")))
            (catch clojure.lang.ExceptionInfo e
              (let [{:keys [status]} (ex-data e)]
                (-> (text-response (or (.getMessage e) "error"))
                    (assoc :status (or status 500)))))
            (catch Exception e
              (-> (text-response (or (.getMessage e) "error"))
                  (assoc :status 500)))))
        cookies/wrap-cookies
        params/wrap-params
        multipart/wrap-multipart-params)))
