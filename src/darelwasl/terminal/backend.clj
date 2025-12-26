(ns darelwasl.terminal.backend
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private default-backend :stable)

(defn- normalize-backend
  [value]
  (let [raw (cond
              (keyword? value) (name value)
              (string? value) value
              :else nil)
        raw (some-> raw str/trim str/lower-case)]
    (case raw
      "stable" :stable
      "canary" :canary
      nil)))

(defn- backend-file
  [cfg]
  (or (get-in cfg [:terminal :backend-file])
      "data/terminal/backend.edn"))

(defn- read-backend-file
  [path]
  (try
    (when-let [file (some-> path io/file)]
      (when (.exists file)
        (when-let [content (slurp file :encoding "UTF-8")]
          (edn/read-string content))))
    (catch Exception e
      (log/warn e "Failed to read terminal backend file" {:path path})
      nil)))

(defn- write-backend-file!
  [path value]
  (let [file (io/file path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file (pr-str value) :encoding "UTF-8")))

(defn active-backend
  [cfg]
  (let [path (backend-file cfg)
        data (read-backend-file path)
        backend (or (normalize-backend (:active data))
                    (normalize-backend (get data "active"))
                    default-backend)]
    backend))

(defn set-active-backend!
  [cfg backend]
  (let [backend (or (normalize-backend backend) default-backend)
        path (backend-file cfg)]
    (write-backend-file! path {:active backend})
    backend))

(defn backend-urls
  [cfg]
  {:stable (get-in cfg [:terminal :base-url])
   :canary (or (get-in cfg [:terminal :canary-base-url])
               (get-in cfg [:terminal :base-url]))})

(defn backend-url
  [cfg backend]
  (let [backend (or (normalize-backend backend) default-backend)
        urls (backend-urls cfg)]
    (get urls backend (:stable urls))))

(defn config-with-backend
  [cfg backend]
  (assoc-in cfg [:terminal :base-url] (backend-url cfg backend)))

(defn config-with-active
  [cfg]
  (let [backend (active-backend cfg)]
    (config-with-backend cfg backend)))

(defn backend-info
  [cfg]
  (let [active (active-backend cfg)
        urls (backend-urls cfg)]
    {:active (name active)
     :stable-url (:stable urls)
     :canary-url (:canary urls)}))
