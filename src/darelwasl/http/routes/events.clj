(ns darelwasl.http.routes.events
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [darelwasl.actions :as actions]
            [darelwasl.events :as events]
            [darelwasl.http.common :as common]))

(defn- parse-event-type
  "Parse a URL/JSON-safe event type string like `task.created` into a keyword
  like `:task/created`."
  [raw]
  (cond
    (keyword? raw) raw
    (string? raw)
    (let [s (str/trim raw)
          s (cond
              (str/starts-with? s ":") (subs s 1)
              :else s)
          s (str/replace s "." "/")]
      (when (and (not (str/blank? s))
                 (re-matches #"[A-Za-z0-9_:/-]+" s))
        (try
          (let [kw (edn/read-string (str ":" s))]
            (when (keyword? kw) kw))
          (catch Exception _ nil))))
    :else nil))

(defn ingest-handler
  [state]
  (fn [request]
    (let [body (or (:body-params request) {})
          raw-type (or (:event/type body) (get body "event/type") (:type body) (get body "type"))
          event-type (parse-event-type raw-type)
          payload (or (:event/payload body) (get body "event/payload") (:payload body) (get body "payload") {})
          subject (or (:event/subject body) (get body "event/subject") (:subject body) (get body "subject"))
          source (or (:event/source body) (get body "event/source") (:source body) (get body "source"))
          event (events/new-event (cond-> {:event/type event-type
                                           :event/payload payload
                                           :actor (actions/actor-from-session (:auth/session request))}
                                    subject (assoc :event/subject subject)
                                    (string? source) (assoc :event/source source)))]
      (if-let [err (:error event)]
        (common/error-response (or (:status err) 400) (:message err) (:details err))
        {:status 200
         :body (actions/apply-event! state event)}))))

(defn routes
  [state]
  [["/events"
    {:middleware [common/require-session]}
    ["" {:post (ingest-handler state)}]]])

