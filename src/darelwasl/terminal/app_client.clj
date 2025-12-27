(ns darelwasl.terminal.app-client
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

(defn- normalize-base-url
  [raw]
  (some-> raw str/trim (str/replace #"/+$" "")))

(defn- ensure-config
  [cfg]
  (let [base-url (normalize-base-url (:main-app-url cfg))
        token (:admin-token cfg)]
    (cond
      (str/blank? (str base-url))
      {:error {:status 500 :message "Main app URL missing"}}

      (str/blank? (str token))
      {:error {:status 500 :message "Admin token missing"}}

      :else
      {:base-url base-url
       :token token})))

(defn- request
  [base-url token method path body {:keys [timeout-ms workspace-id]}]
  (let [timeout-ms (or timeout-ms default-timeout-ms)
        url (str base-url path)]
    (try
      (let [resp (http/request {:method method
                                :url url
                                :throw-exceptions false
                                :socket-timeout timeout-ms
                                :conn-timeout timeout-ms
                                :headers (cond-> {"Accept" "application/json"
                                                  "X-Terminal-Admin-Token" token}
                                           workspace-id (assoc "X-Workspace-Id" workspace-id))
                                :content-type :json
                                :as :byte-array
                                :body (when body (json/write-str body))})
            status (:status resp)
            parsed (parse-json (:body resp))]
        (if (<= 200 status 299)
          {:status status
           :body parsed}
          {:error (or (:error parsed) "Main app error")
           :status status
           :body parsed}))
      (catch Exception e
        (log/warn e "Main app request failed" {:url url})
        {:error "Main app unavailable"
         :status 502}))))

(defn- action-id->string
  [action-id]
  (cond
    (keyword? action-id)
    (-> (str action-id)
        (subs 1)
        (str/replace "/" "."))

    (string? action-id) action-id
    :else (str action-id)))

(defn execute-action
  ([state action-id input actor] (execute-action state action-id input actor nil))
  ([state action-id input actor workspace-id]
   (let [cfg (:terminal/config state)
         {:keys [error base-url token]} (ensure-config cfg)]
     (if error
       {:error error}
       (let [payload (cond-> {}
                       (some? input) (assoc :input input)
                       (some? actor) (assoc :actor actor))
             resp (request base-url token :post
                           (str "/api/system/actions/" (action-id->string action-id))
                           payload
                           {:workspace-id workspace-id})]
         (if-let [err (:error resp)]
           {:error {:status (or (:status resp) 502)
                    :message err
                    :details (:body resp)}}
           (let [body (:body resp)]
             (if-let [action-err (:error body)]
               {:error action-err}
               {:result (:result body)}))))))))
