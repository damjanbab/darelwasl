(ns darelwasl.http.routes.telegram
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
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
        (try
          (let [res (telegram/handle-update state (:body-params request))]
            (when-let [err (:error res)]
              (log/warn "Telegram webhook handler returned error" {:error err}))
            {:status 200
             :body (select-keys res [:status :telegram/command :telegram/message-id :error :reason])})
          (catch Throwable t
            (log/error t "Unhandled throwable while processing Telegram webhook")
            {:status 200
             :body {:status :error
                    :error "Unhandled throwable"}}))))))

(defn- parse-uuid-value
  [v]
  (when v
    (try
      (java.util.UUID/fromString (str/trim (str v)))
      (catch Exception _ nil))))

(defn- parse-long-value
  [v]
  (when v
    (try
      (Long/parseLong (str/trim (str v)))
      (catch Exception _ nil))))

(defn link-token-handler
  [state]
  (fn [request]
    (let [session (:auth/session request)
          session-user-id (:user/id session)
          session-roles (set (:user/roles session))
          target-id (or (parse-uuid-value (get-in request [:body-params :user/id]))
                        (parse-uuid-value (get-in request [:body-params "user/id"]))
                        session-user-id)
          admin? (contains? session-roles :role/admin)]
      (cond
        (nil? target-id) (common/error-response 400 "user/id is required")
        (and (not admin?) (not= target-id session-user-id))
        (common/error-response 403 "Forbidden: can only generate tokens for yourself unless admin")
        :else
        (let [res (telegram/ensure-link-token! state target-id)]
          (if-let [err (:error res)]
            (common/error-response 500 err)
            {:status 200
             :body {:token (:token res)
                    :user/id target-id
                    :ttl-ms (get-in state [:config :telegram :link-token-ttl-ms] 900000)}}))))))

(defn recognize-handler
  [state]
  (fn [request]
    (let [session (:auth/session request)
          session-user-id (:user/id session)
          session-roles (set (:user/roles session))
          target-id (or (parse-uuid-value (get-in request [:body-params :user/id]))
                        (parse-uuid-value (get-in request [:body-params "user/id"]))
                        session-user-id)
          telegram-user-id (or (parse-long-value (get-in request [:body-params :telegram/user-id]))
                               (parse-long-value (get-in request [:body-params "telegram/user-id"]))
                               (parse-long-value (get-in request [:body-params :telegram-user-id]))
                               (parse-long-value (get-in request [:body-params "telegram-user-id"])))
          admin? (contains? session-roles :role/admin)]
      (cond
        (nil? target-id) (common/error-response 400 "user/id is required")
        (nil? telegram-user-id) (common/error-response 400 "telegram/user-id is required")
        (and (not admin?) (not= target-id session-user-id))
        (common/error-response 403 "Forbidden: can only set telegram user id for yourself unless admin")
        :else
        (let [res (telegram/recognize-user! state target-id telegram-user-id)]
          (if-let [err (:error res)]
            (common/error-response 500 err)
            {:status 200
             :body {:status :ok
                    :user/id target-id
                    :telegram/user-id telegram-user-id}}))))))

(defn routes
  [state]
  [["/telegram"
    ["/webhook" {:post (webhook-handler state)}]
    ["/link-token" {:middleware [common/require-session]
                    :post (link-token-handler state)}]
    ["/recognize" {:middleware [common/require-session]
                   :post (recognize-handler state)}]]])
