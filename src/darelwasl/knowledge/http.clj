(ns darelwasl.knowledge.http
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private default-timeout-ms 5000)
(def ^:private default-user-agent
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")

(defn- normalize-url
  [url]
  (-> (or url "")
      str/trim
      (str/replace #"\s+" "")))

(defn request-bytes
  [url {:keys [timeout-ms headers]}]
  (let [url (normalize-url url)
        timeout (or timeout-ms default-timeout-ms)]
    (try
      (let [resp (http/get url {:as :byte-array
                                :throw-exceptions false
                                :socket-timeout timeout
                                :conn-timeout timeout
                                :headers (merge {"User-Agent" default-user-agent
                                                 "Accept" "*/*"}
                                                headers)})
            status (:status resp)
            headers (:headers resp)
            body (:body resp)]
        (if (<= 200 status 299)
          {:status status
           :headers headers
           :body body}
          {:status status
           :headers headers
           :error "Request failed"}))
      (catch Exception e
        (log/warn e "HTTP request failed" {:url url})
        {:status nil
         :error "Request failed"}))))

(defn request-text
  [url opts]
  (let [{:keys [status headers body error]} (request-bytes url opts)
        charset (some->> (get headers "content-type")
                         (re-find #"charset=([^;]+)")
                         second)
        text (when body
               (try
                 (String. ^bytes body (or charset "UTF-8"))
                 (catch Exception _ (String. ^bytes body "UTF-8"))))]
    {:status status
     :headers headers
     :body text
     :error error}))
