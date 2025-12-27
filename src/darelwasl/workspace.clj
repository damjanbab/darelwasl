(ns darelwasl.workspace
  (:require [clojure.string :as str]))

(def ^:private default-workspace "main")

(defn default-id
  []
  default-workspace)

(defn resolve-id
  [value]
  (let [raw (cond
              (nil? value) nil
              (string? value) value
              (keyword? value) (name value)
              (uuid? value) (str value)
              :else (str value))
        trimmed (some-> raw str/trim)]
    (if (str/blank? trimmed)
      default-workspace
      trimmed)))

(defn actor-workspace
  [actor]
  (resolve-id (when (map? actor)
                (or (:actor/workspace actor)
                    (:workspace/id actor)
                    (get actor "workspace/id")))))
