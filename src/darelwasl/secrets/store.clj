(ns darelwasl.secrets.store
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [darelwasl.db :as db]
            [darelwasl.entity :as entity]
            [darelwasl.secrets.crypto :as crypto])
  (:import (java.nio.charset StandardCharsets)
           (java.util Base64 Date UUID)))

(defn- now [] (Date.))

(defn- utf8-bytes
  ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- b64
  ^String [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- b64->bytes
  ^bytes [^String s]
  (.decode (Base64/getUrlDecoder) s))

(defn- error
  [status message & [details]]
  {:error {:status status :message message :details details}})

(defn- secret-eid
  [db k]
  (ffirst
   (d/q '[:find ?e
          :in $ ?k
          :where [?e :secret/key ?k]]
        db k)))

(defn put-secret!
  "Store a new secret version encrypted under the master key and set it active.
  Never stores plaintext in Datomic."
  [conn cfg {:keys [key plaintext description]}]
  (let [k (some-> (or key "") str/trim)]
    (cond
      (str/blank? k) (error 400 "Secret key is required")
      (not (string? plaintext)) (error 400 "Plaintext must be a string")
      :else
      (let [dbv (d/db conn)
            existing-eid (secret-eid dbv k)
            existed? (boolean existing-eid)
            sid (UUID/randomUUID)
            vid (UUID/randomUUID)
            aad (utf8-bytes k)
            enc (crypto/encrypt-bytes cfg aad (utf8-bytes plaintext))
            created (now)
            secret-id (if existed? existing-eid -1)
            version-id -2
            secret-base {:db/id secret-id
                         :entity/type :entity.type/secret
                         :secret/key k
                         :secret/updated-at created
                         :secret/active-version version-id}
            secret-base (cond-> secret-base
                          (not existed?) (assoc :secret/id sid :secret/created-at created)
                          (and (string? description) (not (str/blank? description)))
                          (assoc :secret/description (str/trim description)))
            secret (if existed?
                     secret-base
                     (entity/with-ref dbv secret-base))
            version (entity/with-ref
                     dbv
                     {:db/id version-id
                      :entity/type :entity.type/secret-version
                      :secret.version/id vid
                      :secret.version/secret secret-id
                      :secret.version/format (:format enc)
                      :secret.version/kid (:kid enc)
                      :secret.version/nonce (b64 (:nonce enc))
                      :secret.version/ciphertext (b64 (:ciphertext enc))
                      :secret.version/created-at created})]
        (try
          (db/transact! conn {:tx-data [secret version]})
          {:status :ok
           :secret/key k
           :secret/version-id vid
           :secret/kid (:kid enc)}
          (catch Exception e
            (let [data (ex-data e)]
              (error 500
                     "Failed to store secret"
                     (cond-> {:message (.getMessage e)}
                       (map? data)
                       (assoc :datomic/anomaly
                              (select-keys data
                                            [:cognitect.anomalies/category
                                             :cognitect.anomalies/message
                                             :db/error
                                            :entity])))))))))))

(defn get-secret
  "Read and decrypt the active secret value for key. Returns {:secret/value \"...\"} or {:error ...}."
  [db cfg key]
  (let [k (some-> (or key "") str/trim)]
    (cond
      (str/blank? k) (error 400 "Secret key is required")
      :else
      (if-let [eid (secret-eid db k)]
        (let [ent (d/pull db [:secret/key
                              {:secret/active-version [:secret.version/format
                                                      :secret.version/kid
                                                      :secret.version/nonce
                                                      :secret.version/ciphertext]}]
                          eid)
              ver (:secret/active-version ent)]
          (if-not (and ver (:secret.version/nonce ver) (:secret.version/ciphertext ver))
            (error 500 "Secret has no active version")
            (try
              (let [aad (utf8-bytes k)
                    plaintext (crypto/decrypt-bytes cfg aad
                                                    {:format (:secret.version/format ver)
                                                     :nonce (b64->bytes (:secret.version/nonce ver))
                                                     :ciphertext (b64->bytes (:secret.version/ciphertext ver))})]
                {:secret/key k
                 :secret/value (String. ^bytes plaintext StandardCharsets/UTF_8)
                 :secret/kid (:secret.version/kid ver)})
              (catch Exception e
                (error 500 "Failed to decrypt secret" {:message (.getMessage e)})))))
        (error 404 "Secret not found")))))

(defn list-secrets
  "List secret keys (metadata only)."
  [db]
  {:secrets
   (->> (d/q '[:find ?k ?updated
               :where
               [?e :secret/key ?k]
               [?e :secret/updated-at ?updated]]
             db)
        (map (fn [[k updated]]
               {:secret/key k
                :secret/updated-at updated}))
        (sort-by :secret/updated-at #(compare %2 %1))
        vec)})
