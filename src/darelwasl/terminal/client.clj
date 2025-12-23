(ns darelwasl.terminal.client
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private default-timeout-ms 5000)

(defn- response-body->string
  [body]
  (cond
    (nil? body) nil
    (string? body) body
    (instance? (Class/forName "[B") body) (String. ^bytes body "UTF-8")
    (instance? java.io.InputStream body) (slurp body :encoding "UTF-8")
    :else (str body)))

(defn- parse-json
  [body]
  (let [text (some-> body response-body->string)]
    (when (and text (not (str/blank? text)))
      (json/read-str text :key-fn keyword))))

(defn request
  [cfg method path & [body]]
  (let [base-url (get-in cfg [:terminal :base-url])
        url (str base-url path)]
    (try
      (let [resp (http/request {:method method
                                :url url
                                :throw-exceptions false
                                :socket-timeout default-timeout-ms
                                :conn-timeout default-timeout-ms
                                :headers {"Accept" "application/json"}
                                :content-type :json
                                :as :byte-array
                                :body (when body (json/write-str body))})
            status (:status resp)
            parsed (parse-json (:body resp))]
        (if (<= 200 status 299)
          {:status :ok
           :body parsed}
          {:error (or (:error parsed) "Terminal service error")
           :status status
           :body parsed}))
      (catch Exception e
        (log/warn e "Terminal request failed" {:url url})
        {:error "Terminal service unavailable"
         :status 502}))))
