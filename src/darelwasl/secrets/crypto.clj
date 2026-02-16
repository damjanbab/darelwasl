(ns darelwasl.secrets.crypto
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.security MessageDigest SecureRandom)
           (java.util Base64)
           (javax.crypto Cipher)
           (javax.crypto.spec GCMParameterSpec SecretKeySpec)))

(def ^:private gcm-tag-bits 128)
(def ^:private nonce-bytes 12)
(def ^:private fmt :dw.secrets/aes-256-gcm-v1)

(defn format-keyword [] fmt)

(defn- sha256
  ^bytes [^bytes bs]
  (.digest (MessageDigest/getInstance "SHA-256") bs))

(defn master-key-id
  "Return a short stable key id for logging/metadata (not secret)."
  [^bytes key-bytes]
  (let [digest (sha256 key-bytes)
        head (byte-array 9)]
    (System/arraycopy digest 0 head 0 (alength head))
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) head)))

(defn load-master-key-bytes
  "Load the 32-byte master key from either:
  - cfg.secrets.master-key-b64 (preferred for ephemeral/test)
  - cfg.secrets.master-key-file (base64 on disk)

  Returns raw bytes or nil."
  [cfg]
  (let [b64 (some-> (get-in cfg [:secrets :master-key-b64]) str/trim not-empty)
        path (some-> (get-in cfg [:secrets :master-key-file]) str/trim not-empty)]
    (cond
      b64
      (try
        (.decode (Base64/getDecoder) ^String b64)
        (catch Exception _
          nil))

      path
      (let [f (io/file path)]
        (when (.exists f)
          (let [raw (some-> (slurp f) str/trim)]
            (when-not (str/blank? raw)
              (try
                (.decode (Base64/getDecoder) ^String raw)
                (catch Exception _
                  nil))))))

      :else nil)))

(defn- require-key
  ^bytes [cfg]
  (let [key-bytes (load-master-key-bytes cfg)]
    (when-not (and key-bytes (= 32 (alength ^bytes key-bytes)))
      (throw (ex-info "Secrets master key missing or invalid"
                      {:expected-bytes 32
                       :master-key-file (get-in cfg [:secrets :master-key-file])
                       :has-master-key-b64 (boolean (get-in cfg [:secrets :master-key-b64]))})))
    key-bytes))

(defn encrypt-bytes
  "Encrypt plaintext bytes with AES-256-GCM.
  Returns {:format kw :kid str :nonce bytes :ciphertext bytes}."
  [cfg ^bytes aad-bytes ^bytes plaintext]
  (let [key-bytes (require-key cfg)
        kid (master-key-id key-bytes)
        nonce (byte-array nonce-bytes)
        rnd (SecureRandom.)]
    (.nextBytes rnd nonce)
    (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")
          spec (GCMParameterSpec. gcm-tag-bits nonce)
          key (SecretKeySpec. key-bytes "AES")]
      (.init cipher Cipher/ENCRYPT_MODE key spec)
      (when (and aad-bytes (pos? (alength ^bytes aad-bytes)))
        (.updateAAD cipher aad-bytes))
      {:format fmt
       :kid kid
       :nonce nonce
       :ciphertext (.doFinal cipher plaintext)})))

(defn decrypt-bytes
  "Decrypt {:format :kid :nonce :ciphertext} with AES-256-GCM.
  Returns plaintext bytes."
  [cfg ^bytes aad-bytes {:keys [format nonce ciphertext]}]
  (when-not (= format fmt)
    (throw (ex-info "Unsupported secret ciphertext format" {:format format})))
  (let [key-bytes (require-key cfg)
        cipher (Cipher/getInstance "AES/GCM/NoPadding")
        spec (GCMParameterSpec. gcm-tag-bits ^bytes nonce)
        key (SecretKeySpec. key-bytes "AES")]
    (.init cipher Cipher/DECRYPT_MODE key spec)
    (when (and aad-bytes (pos? (alength ^bytes aad-bytes)))
      (.updateAAD cipher aad-bytes))
    (.doFinal cipher ^bytes ciphertext)))

