(ns darelwasl.http.routes.telegram
  (:require [clojure.string :as str]
            [darelwasl.http.common :as common]
            [darelwasl.telegram :as telegram]))

(defn- valid-secret?
  [state provided]
  (let [expected (get-in state [:config :telegram :webhook-secret])]
    (and (not (str/blank? expected))
         (= expected provided))))

(defn webhook-handler
  [state]
  (fn [request]
    (let [cfg (get-in state [:config :telegram])
          provided (get-in request [:headers "x-telegram-bot-api-secret-token"])]
      (cond
        (not (telegram/webhook-enabled? cfg))
        (common/error-response 503 "Telegram webhook disabled")

        (not (valid-secret? state provided))
        (common/error-response 401 "Invalid webhook secret")

        :else
        (let [res (telegram/handle-update state (:body-params request))]
          (if-let [err (:error res)]
            (common/error-response 400 err)
            {:status 200
             :body (select-keys res [:status :telegram/command :telegram/message-id])}))))))

(defn routes
  [state]
  [["/telegram"
    ["/webhook" {:post (webhook-handler state)}]]])
