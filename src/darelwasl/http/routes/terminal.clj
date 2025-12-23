(ns darelwasl.http.routes.terminal
   (:require [clj-http.client :as http]
             [clojure.string :as str]
             [darelwasl.http.common :as common]
             [darelwasl.terminal.client :as terminal]))

 (defn- qparam
   [query key]
   (or (get query key)
       (get query (name key))))

 (defn- handle-terminal-result
   [result]
   (if-let [err (:error result)]
     (common/error-response (or (:status result) 502) err (:body result))
     {:status 200
      :body (:body result)}))

(def ^:private hop-headers
  #{"connection" "keep-alive" "proxy-authenticate" "proxy-authorization"
    "te" "trailer" "transfer-encoding" "upgrade"})

(defn- strip-hop-headers
  [headers]
  (reduce-kv (fn [acc k v]
               (let [key (str/lower-case (name k))]
                 (if (contains? hop-headers key)
                   acc
                   (assoc acc (name k) v))))
             {}
             (or headers {})))

(defn- session-by-id
  [state session-id]
  (when session-id
    (let [result (terminal/request (:config state) :get (str "/sessions/" session-id))]
      (when (= :ok (:status result))
        (get-in result [:body :session])))))

(defn- proxy-session-handler
  [state kind]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          session (session-by-id state session-id)]
      (if-not session
        (common/error-response 404 "Session not found")
        (let [port (get-in session [:ports kind])
              host (get-in (:config state) [:terminal :host] "127.0.0.1")
              raw-path (or (get-in request [:path-params :path]) "")
              path (if (str/blank? raw-path) "/" (str "/" raw-path))
              qs (:query-string request)
              url (str "http://" host ":" port path (when qs (str "?" qs)))
              resp (http/request {:method (:request-method request)
                                  :url url
                                  :throw-exceptions false
                                  :as :stream
                                  :headers (strip-hop-headers (:headers request))
                                  :body (:body request)})
              headers (strip-hop-headers (:headers resp))]
          {:status (:status resp)
           :headers headers
           :body (:body resp)})))))

 (defn list-sessions-handler
   [state]
   (fn [_request]
     (handle-terminal-result
      (terminal/request (:config state) :get "/sessions"))))

 (defn create-session-handler
   [state]
   (fn [request]
     (let [name (or (get-in request [:body-params :name])
                    (get-in request [:body-params "name"]))]
       (handle-terminal-result
        (terminal/request (:config state) :post "/sessions" (when name {:name name}))))))

 (defn session-detail-handler
   [state]
   (fn [request]
     (let [session-id (get-in request [:path-params :id])]
       (handle-terminal-result
        (terminal/request (:config state) :get (str "/sessions/" session-id))))))

 (defn send-input-handler
   [state]
   (fn [request]
     (let [session-id (get-in request [:path-params :id])
           text (or (get-in request [:body-params :text])
                    (get-in request [:body-params "text"]))]
       (handle-terminal-result
        (terminal/request (:config state) :post (str "/sessions/" session-id "/input") {:text text})))))

(defn send-keys-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])
          keys (or (get-in request [:body-params :keys])
                   (get-in request [:body-params "keys"]))]
      (handle-terminal-result
       (terminal/request (:config state) :post (str "/sessions/" session-id "/keys") {:keys keys})))))

 (defn output-handler
   [state]
   (fn [request]
     (let [session-id (get-in request [:path-params :id])
           cursor (qparam (:query-params request) :cursor)
           qs (when cursor (str "?cursor=" (str cursor)))]
       (handle-terminal-result
        (terminal/request (:config state) :get (str "/sessions/" session-id "/output" qs))))))

 (defn complete-handler
   [state]
   (fn [request]
     (let [session-id (get-in request [:path-params :id])]
       (handle-terminal-result
        (terminal/request (:config state) :post (str "/sessions/" session-id "/complete"))))))

(defn verify-handler
  [state]
  (fn [request]
    (let [session-id (get-in request [:path-params :id])]
      (handle-terminal-result
       (terminal/request (:config state) :post (str "/sessions/" session-id "/verify"))))))

 (defn routes
   [state]
   [["/terminal"
     {:middleware [common/require-session
                   (common/require-roles #{:role/codex-terminal})]}
     ["/sessions" {:get (list-sessions-handler state)
                   :post (create-session-handler state)}]
     ["/sessions/:id" {:get (session-detail-handler state)}]
     ["/sessions/:id/input" {:post (send-input-handler state)}]
     ["/sessions/:id/keys" {:post (send-keys-handler state)}]
     ["/sessions/:id/output" {:get (output-handler state)}]
     ["/sessions/:id/complete" {:post (complete-handler state)}]
     ["/sessions/:id/verify" {:post (verify-handler state)}]
     ["/sessions/:id/app" {:handler (proxy-session-handler state :app)}]
     ["/sessions/:id/app/*path" {:handler (proxy-session-handler state :app)}]
     ["/sessions/:id/site" {:handler (proxy-session-handler state :site)}]
     ["/sessions/:id/site/*path" {:handler (proxy-session-handler state :site)}]]])
