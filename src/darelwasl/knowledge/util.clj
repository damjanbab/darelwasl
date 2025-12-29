(ns darelwasl.knowledge.util
  (:require [clojure.string :as str])
  (:import (java.net URI)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.time Instant)
           (java.util UUID)
           (java.math BigInteger)))

(defn now-inst
  []
  (java.util.Date/from (Instant/now)))

(defn name-uuid
  ^UUID
  [s]
  (UUID/nameUUIDFromBytes (.getBytes (str s) StandardCharsets/UTF_8)))

(defn sha256-bytes
  [^bytes bytes]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest bytes 0 (alength bytes))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn normalize-url
  [value]
  (when (some? value)
    (let [raw (str/trim (str value))]
      (when-not (str/blank? raw)
        (try
          (let [uri (URI. raw)
                scheme (or (.getScheme uri) "https")
                host (.getHost uri)]
            (when (and host (#{"http" "https"} (str/lower-case scheme)))
              (let [path (or (.getPath uri) "")
                    query (.getQuery uri)
                    clean-path (if (str/blank? path) "/" path)
                    normalized (-> (URI. scheme host clean-path query nil)
                                   .normalize
                                   str)]
                {:uri (URI. normalized)
                 :normalized normalized})))
          (catch Exception _ nil))))))

(defn host-from-url
  [value]
  (some-> (normalize-url value) :uri .getHost))

(defn normalize-text
  [s]
  (-> (or s "")
      str/trim
      (str/replace #"\s+" " ")
      str/lower-case))

(defn slugify
  [s]
  (let [slug (-> s
                 (or "")
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"(^-|-$)" ""))]
    (if (str/blank? slug) "item" slug)))
