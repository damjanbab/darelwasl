;; GitHub API client helpers for PR overview.
(ns darelwasl.github
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private default-timeout-ms 3000)
(def ^:private default-base-url "https://api.github.com")
(def ^:private default-user-agent "darelwasl-app")

(defn- error
  [status message & [details]]
  {:error {:status status
           :message message
           :details details}})

(defn- normalize-base-url
  [base-url]
  (-> (or base-url default-base-url)
      str/trim
      (str/replace #"/+$" "")))

(defn- normalize-state
  [value]
  (let [raw (-> (or value "open") str/trim str/lower-case)]
    (cond
      (#{"open" "closed" "all"} raw) raw
      :else nil)))

(defn- parse-github-repo
  [remote-url]
  (when-let [match (re-find #"github\.com[:/](.+)$" (str remote-url))]
    (let [path (-> (second match)
                   (str/replace #"\.git$" ""))
          [owner repo] (str/split path #"/" 2)]
      (when (and owner repo)
        {:owner owner :repo repo}))))

(defn- git-config-origin-url
  []
  (let [cfg (io/file ".git" "config")]
    (when (.exists cfg)
      (let [raw (slurp cfg)
            match (re-find #"(?m)^\s*url\s*=\s*(.+)$" raw)]
        (some-> match second str/trim)))))

(defn- resolve-repo
  [cfg]
  (let [github (:github cfg)
        owner (:repo-owner github)
        repo (:repo-name github)
        terminal-url (get-in cfg [:terminal :repo-url])]
    (cond
      (and (seq (or owner "")) (seq (or repo "")))
      {:owner owner :repo repo}

      (seq terminal-url)
      (parse-github-repo terminal-url)

      :else
      (some-> (git-config-origin-url) parse-github-repo))))

(defn- request-json
  [{:keys [api-url token timeout-ms]} path query]
  (let [url (str (normalize-base-url api-url) path)
        headers (cond-> {"Accept" "application/vnd.github+json"
                         "User-Agent" default-user-agent}
                  (and token (not (str/blank? token)))
                  (assoc "Authorization" (str "token " token)))]
    (try
      (let [resp (http/get url {:as :text
                                :throw-exceptions false
                                :socket-timeout (or timeout-ms default-timeout-ms)
                                :conn-timeout (or timeout-ms default-timeout-ms)
                                :headers headers
                                :query-params query})
            status (:status resp)
            raw (:body resp)
            body (when (and raw (not (str/blank? raw)))
                   (try
                     (json/read-str raw :key-fn keyword)
                     (catch Exception _ nil)))]
        (if (<= 200 status 299)
          {:status status :body body}
          (error status "GitHub request failed" {:url url :status status :body body})))
      (catch Exception e
        (log/warn e "GitHub request failed" {:path path})
        (error 502 "GitHub request failed")))))

(defn- pr-summary
  [pr commits]
  {:pr/number (:number pr)
   :pr/title (:title pr)
   :pr/state (:state pr)
   :pr/url (:html_url pr)
   :pr/author (get-in pr [:user :login])
   :pr/created-at (:created_at pr)
   :pr/updated-at (:updated_at pr)
   :pr/merged-at (:merged_at pr)
   :pr/head-ref (get-in pr [:head :ref])
   :pr/base-ref (get-in pr [:base :ref])
   :pr/commits commits})

(defn- commit-summary
  [commit]
  (let [info (:commit commit)
        message (or (:message info) "")
        first-line (first (str/split message #"\n"))
        author (or (get-in commit [:author :login])
                   (get-in info [:author :name]))]
    {:commit/sha (:sha commit)
     :commit/message (str/trim (or first-line message))
     :commit/author author
     :commit/date (get-in info [:author :date])}))

(defn list-pulls
  [cfg params]
  (let [github (:github cfg)
        resolved (resolve-repo cfg)
        state (normalize-state (or (:pr/state params) (:state params)))]
    (cond
      (nil? resolved)
      (error 500 "GitHub repo not configured")

      (nil? state)
      (error 400 "Invalid PR state")

      :else
      (let [{:keys [owner repo]} resolved
            per-page (max 1 (or (:prs-per-page github) 20))
            commits-limit (max 1 (or (:commits-per-pr github) 10))
            pulls-resp (request-json github
                                     (str "/repos/" owner "/" repo "/pulls")
                                     {:state state
                                      :per_page per-page})
            pulls (when-not (:error pulls-resp) (:body pulls-resp))]
        (if-let [err (:error pulls-resp)]
          {:error err}
          (let [items (mapv (fn [pr]
                              (let [commits-resp (request-json github
                                                               (str "/repos/" owner "/" repo "/pulls/" (:number pr) "/commits")
                                                               {:per_page commits-limit})
                                    commits (if (:error commits-resp)
                                              (do
                                                (log/warn "Failed to fetch commits for PR"
                                                          {:pr (:number pr)
                                                           :error (:error commits-resp)})
                                                [])
                                              (mapv commit-summary (:body commits-resp)))]
                                (pr-summary pr commits)))
                            pulls)]
            {:pulls items}))))))
