(ns darelwasl.knowledge.http
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import (java.net Inet4Address Inet6Address InetAddress URI UnknownHostException SocketTimeoutException ConnectException)
           (org.apache.http.conn DnsResolver)
           (org.xbill.DNS ARecord AAAARecord Lookup SimpleResolver Type)))

(def ^:private default-timeout-ms 5000)
(def ^:private retry-timeout-ms 60000)
(def ^:private dns-cache-ttl-ms 300000)
(def ^:private dns-alt-resolvers ["8.8.8.8" "1.1.1.1"])
(def ^:private dns-timeout-secs 5)
(def ^:private dns-timeout-secs-alt 10)
(def ^:private default-user-agent
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")

(def ^:private dns-cache (atom {}))

(defn- normalize-url
  [url]
  (-> (or url "")
      str/trim
      (str/replace #"\s+" "")))

(defn- now-ms
  []
  (System/currentTimeMillis))

(defn- cache-valid?
  [{:keys [resolved-at]}]
  (and resolved-at (< (- (now-ms) resolved-at) dns-cache-ttl-ms)))

(defn- record->address
  [record]
  (cond
    (instance? ARecord record) (.getAddress ^ARecord record)
    (instance? AAAARecord record) (.getAddress ^AAAARecord record)
    :else nil))

(defn- lookup-host
  [host resolver timeout-secs]
  (let [resolver (doto resolver (.setTimeout timeout-secs))
        a-lookup (doto (Lookup. host Type/A) (.setResolver resolver))
        aaaa-lookup (doto (Lookup. host Type/AAAA) (.setResolver resolver))
        records (concat (seq (.run a-lookup)) (seq (.run aaaa-lookup)))]
    (->> records
         (keep record->address)
         vec)))

(defn- prefer-ipv4
  [addresses]
  (let [v4 (filter #(instance? Inet4Address %) addresses)
        v6 (filter #(instance? Inet6Address %) addresses)]
    (vec (concat v4 v6))))

(defn- families-for
  [addresses]
  (set (keep (fn [addr]
               (cond
                 (instance? Inet4Address addr) :ipv4
                 (instance? Inet6Address addr) :ipv6
                 :else nil))
             addresses)))

(defn- resolve-host
  [host {:keys [force-ipv4?]}]
  (when (str/blank? host)
    (throw (UnknownHostException. "empty-host")))
  (let [cached (get @dns-cache host)]
    (if (cache-valid? cached)
      (let [addresses (:addresses cached)
            addresses (if force-ipv4?
                        (vec (filter #(instance? Inet4Address %) addresses))
                        (prefer-ipv4 addresses))]
        (when-not (seq addresses)
          (throw (UnknownHostException. host)))
        {:addresses addresses
         :families (families-for addresses)
         :resolver (:resolver cached)})
      (try
        (let [addresses (prefer-ipv4 (vec (InetAddress/getAllByName host)))
              addresses (if force-ipv4?
                          (vec (filter #(instance? Inet4Address %) addresses))
                          addresses)]
          (when-not (seq addresses)
            (throw (UnknownHostException. host)))
          (swap! dns-cache assoc host {:addresses addresses
                                       :families (families-for addresses)
                                       :resolver :default
                                       :resolved-at (now-ms)})
          {:addresses addresses
           :families (families-for addresses)
           :resolver :default})
        (catch UnknownHostException _
          (let [resolver (some (fn [addr]
                                 (try
                                   (let [res (lookup-host host (SimpleResolver. addr) dns-timeout-secs-alt)]
                                     (when (seq res)
                                       {:addresses (prefer-ipv4 res)
                                        :resolver addr}))
                                   (catch Exception __ nil)))
                               dns-alt-resolvers)]
            (if resolver
              (let [addresses (:addresses resolver)
                    addresses (if force-ipv4?
                                (vec (filter #(instance? Inet4Address %) addresses))
                                addresses)]
                (when-not (seq addresses)
                  (throw (UnknownHostException. host)))
                (swap! dns-cache assoc host {:addresses addresses
                                             :families (families-for addresses)
                                             :resolver :alternate
                                             :resolved-at (now-ms)})
                {:addresses addresses
                 :families (families-for addresses)
                 :resolver :alternate})
              (throw (UnknownHostException. host)))))))))

(defn- static-resolver
  [addresses]
  (reify DnsResolver
    (resolve [_ host]
      (if (seq addresses)
        (into-array InetAddress addresses)
        (throw (UnknownHostException. host))))))

(defn- classify-error
  [error]
  (cond
    (instance? UnknownHostException error) :dns
    (instance? SocketTimeoutException error) :timeout
    (instance? ConnectException error) :timeout
    :else :unknown))

(defn- request-once
  [url {:keys [timeout-ms conn-timeout-ms dns] :as opts}]
  (let [timeout (or timeout-ms default-timeout-ms)
        conn-timeout (or conn-timeout-ms timeout)]
    (try
      (let [resp (http/get url {:as :byte-array
                                :throw-exceptions false
                                :socket-timeout timeout
                                :conn-timeout conn-timeout
                                :dns-resolver (:resolver dns)
                                :headers (merge {"User-Agent" default-user-agent
                                                 "Accept" "*/*"}
                                                (:headers opts))})
            status (:status resp)
            headers (:headers resp)
            body (:body resp)]
        (if (<= 200 status 299)
          {:status status
           :headers headers
           :body body
           :dns dns}
          {:status status
           :headers headers
           :error "Request failed"
           :dns dns}))
      (catch Exception e
        (log/warn e "HTTP request failed" {:url url})
        {:status nil
         :error "Request failed"
         :error-type (classify-error e)
         :dns dns}))))

(defn request-bytes
  [url {:keys [timeout-ms headers]}]
  (let [url (normalize-url url)
        host (try
               (some-> (URI. url) .getHost)
               (catch Exception _ nil))]
    (try
      (let [resolution (resolve-host host {:force-ipv4? false})
            dns {:resolver (static-resolver (:addresses resolution))
                 :families (:families resolution)
                 :resolver-source (:resolver resolution)}
            first-attempt (request-once url {:timeout-ms timeout-ms
                                             :conn-timeout-ms timeout-ms
                                             :headers headers
                                             :dns dns})]
        (if (and (= (:error-type first-attempt) :timeout)
                 (= (:families resolution) #{:ipv6}))
          (let [retry-resolution (resolve-host host {:force-ipv4? true})
                retry-dns {:resolver (static-resolver (:addresses retry-resolution))
                           :families (:families retry-resolution)
                           :resolver-source (:resolver retry-resolution)
                           :retry :ipv4-preferred}
                retry (request-once url {:timeout-ms retry-timeout-ms
                                         :conn-timeout-ms retry-timeout-ms
                                         :headers headers
                                         :dns retry-dns})
                retry (if (and (nil? (:status retry))
                               (contains? #{:dns :timeout} (:error-type retry)))
                        (assoc retry :error-type :ipv6)
                        retry)]
            (assoc retry :attempts 2))
          (assoc first-attempt :attempts 1)))
      (catch UnknownHostException _
        {:status nil
         :error "DNS resolution failed"
         :error-type :dns
         :attempts 1})
      (catch Exception e
        (log/warn e "HTTP request failed" {:url url})
        {:status nil
         :error "Request failed"
         :error-type (classify-error e)
         :attempts 1}))))

(defn request-text
  [url opts]
  (let [{:keys [status headers body error error-type attempts dns]} (request-bytes url opts)
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
     :error error
     :error-type error-type
     :attempts attempts
     :dns dns}))
