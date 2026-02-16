(ns darelwasl.secrets.cli
  (:require [clojure.string :as str]
            [darelwasl.bootstrap :as bootstrap]
            [darelwasl.config :as config]
            [darelwasl.secrets.store :as store]
            [datomic.client.api :as d])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Paths StandardOpenOption)))

(defn- die!
  [msg & [data]]
  (binding [*out* *err*]
    (println msg)
    (when data
      (println (pr-str data))))
  (System/exit 2))

(defn- parse-args
  [argv]
  (loop [m {:args []} xs argv]
    (if (empty? xs)
      m
      (let [a (first xs)
            b (second xs)
            rest (nnext xs)]
        (cond
          (= a "--key") (recur (assoc m :key b) rest)
          (= a "--description") (recur (assoc m :description b) rest)
          (= a "--path") (recur (assoc m :path b) rest)
          (= a "--show") (recur (assoc m :show true) (next xs))
          :else (recur (update m :args conj a) (next xs)))))))

(defn- read-stdin-bytes
  []
  (let [in (System/in)
        buf (.readAllBytes in)]
    buf))

(defn- write-0600!
  [^String path ^bytes content]
  (let [p (Paths/get path (make-array String 0))
        parent (.getParent p)]
    (when parent
      (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0)))
    (Files/write p content (into-array StandardOpenOption [StandardOpenOption/CREATE
                                                          StandardOpenOption/TRUNCATE_EXISTING
                                                          StandardOpenOption/WRITE]))
    (try
      (let [f (.toFile p)]
        (.setReadable f false false)
        (.setWritable f false false)
        (.setExecutable f false false)
        (.setReadable f true true)
        (.setWritable f true true))
      (catch Exception _ nil))))

(defn- ensure-db!
  [cfg]
  (let [{:keys [conn error] :as state} (bootstrap/initialize-db! cfg {:context "secrets-cli"})]
    (when error
      (die! "Datomic not ready" {:message (.getMessage ^Exception error)}))
    (when-not conn
      (die! "Datomic connection missing"))
    state))

(defn- cmd-set
  [cfg {:keys [key description]}]
  (when (str/blank? (or key ""))
    (die! "--key is required"))
  (let [{:keys [conn]} (ensure-db! cfg)
        raw (String. ^bytes (read-stdin-bytes) StandardCharsets/UTF_8)
        plaintext (str/trim (str/replace raw #"\r\n$" "\n"))
        res (store/put-secret! conn cfg {:key key :plaintext plaintext :description description})]
    (if-let [err (:error res)]
      (die! "Failed" err)
      (do
        (println "ok" (:secret/key res) (:secret/kid res))
        (System/exit 0)))))

(defn- cmd-get
  [cfg {:keys [key show]}]
  (when (str/blank? (or key ""))
    (die! "--key is required"))
  (let [{:keys [conn]} (ensure-db! cfg)
        db (d/db conn)
        res (store/get-secret db cfg key)]
    (if-let [err (:error res)]
      (die! "Failed" err)
      (do
        (if show
          (print (:secret/value res))
          (println "ok" (:secret/key res) (:secret/kid res)))
        (System/exit 0)))))

(defn- cmd-materialize
  [cfg {:keys [key path]}]
  (when (str/blank? (or key ""))
    (die! "--key is required"))
  (when (str/blank? (or path ""))
    (die! "--path is required"))
  (let [{:keys [conn]} (ensure-db! cfg)
        db (d/db conn)
        res (store/get-secret db cfg key)]
    (if-let [err (:error res)]
      (die! "Failed" err)
      (let [content (.getBytes ^String (:secret/value res) StandardCharsets/UTF_8)]
        (write-0600! path content)
        (println "ok" (:secret/key res) "->" path)
        (System/exit 0)))))

(defn- cmd-list
  [cfg]
  (let [{:keys [conn]} (ensure-db! cfg)
        db (d/db conn)]
    (doseq [{:secret/keys [key updated-at]} (:secrets (store/list-secrets db))]
      (println key "\t" updated-at))
    (System/exit 0)))

(defn -main
  [& argv]
  (let [cfg (config/load-config)
        {:keys [args] :as opts} (parse-args (vec argv))
        cmd (first args)]
    (case cmd
      "set" (cmd-set cfg opts)
      "get" (cmd-get cfg opts)
      "materialize" (cmd-materialize cfg opts)
      "list" (cmd-list cfg)
      (die! "Usage: clojure -M -m darelwasl.secrets.cli <set|get|materialize|list> [--key K] [--path P] [--show]" {:argv argv}))))
