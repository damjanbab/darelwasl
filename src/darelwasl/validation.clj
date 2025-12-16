(ns darelwasl.validation
  (:require [clojure.string :as str])
  (:import (java.util UUID)))

(defn param-value
  "Fetch a value from a map regardless of keyword/string key usage."
  [m k]
  (let [kname (name k)]
    (or (get m k)
        (get m (keyword kname))
        (get m kname))))

(defn normalize-string
  [v label {:keys [required allow-blank?] :or {allow-blank? false}}]
  (let [raw (if (sequential? v) (first v) v)]
    (cond
      (and required (nil? raw)) {:error (str label " is required")}
      (nil? raw) {:value nil}
      (not (string? raw)) {:error (str label " must be a string")}
      :else (let [s (str/trim raw)]
              (cond
                (and (not allow-blank?) (str/blank? s)) {:error (str label " cannot be blank")}
                :else {:value s})))))

(defn normalize-uuid
  [v label]
  (let [raw (if (sequential? v) (first v) v)]
    (cond
      (nil? raw) {:value nil}
      (uuid? raw) {:value raw}
      (string? raw) (try
                      {:value (UUID/fromString (str/trim raw))}
                      (catch Exception _
                        {:error (str "Invalid " label)}))
      :else {:error (str "Invalid " label)})))

(defn normalize-boolean
  [v label {:keys [default allow-all?]}]
  (let [raw (if (sequential? v) (first v) v)]
    (cond
      (keyword? raw) (recur (name raw) label {:default default
                                              :allow-all? allow-all?})
      (nil? raw) {:value default}
      (boolean? raw) {:value raw}
      (string? raw) (let [s (str/lower-case (str/trim raw))]
                      (cond
                        (#{"true" "1" "yes" "on"} s) {:value true}
                        (#{"false" "0" "no" "off"} s) {:value false}
                        (and allow-all? (= s "all")) {:value :all}
                        :else {:error (str label " must be " (if allow-all?
                                                               "true, false, or all"
                                                               "true or false"))}))
      :else {:error (str label " must be " (if allow-all?
                                             "true, false, or all"
                                             "true or false"))})))

(defn normalize-long
  [v label]
  (let [raw (if (sequential? v) (first v) v)]
    (cond
      (nil? raw) {:value nil}
      (integer? raw) {:value (long raw)}
      (number? raw) {:value (long raw)}
      (string? raw) (try
                      {:value (Long/parseLong (str/trim raw))}
                      (catch Exception _
                        {:error (str label " must be a number")}))
      :else {:error (str label " must be a number")})))

(defn- to-keyword
  [v]
  (cond
    (keyword? v) v
    (string? v) (let [s (-> v str/trim (str/replace #"^:" ""))]
                  (when-not (str/blank? s)
                    (keyword s)))
    :else nil))

(defn normalize-keyword
  [v label allowed]
  (let [kw (to-keyword (if (sequential? v) (first v) v))]
    (cond
      (nil? kw) {:error (str "Invalid " label)}
      (and allowed (not (contains? allowed kw))) {:error (str "Unsupported " label)}
      :else {:value kw})))

(defn normalize-enum
  [value allowed label]
  (let [raw (if (sequential? value) (first value) value)]
    (cond
      (nil? raw) {:value nil}
      :else
      (let [kw (to-keyword raw)]
        (cond
          (nil? kw) {:error (str "Invalid " label)}
          (not (contains? allowed kw)) {:error (str "Unsupported " label ": " (name kw))}
          :else {:value kw})))))

(defn normalize-string-list
  [v label]
  (cond
    (nil? v) {:value []}
    (string? v) (let [s (str/trim v)]
                  (if (str/blank? s)
                    {:value []}
                    {:value [s]}))
    (sequential? v) (let [vals (->> v
                                    (keep (fn [item]
                                            (when (string? item)
                                              (let [s (str/trim item)]
                                                (when-not (str/blank? s) s)))))
                                    vec)]
                     {:value vals})
    :else {:error (str label " must be a string or list of strings")}))

(defn normalize-ref-list
  [items ref-key label]
  (let [vals (or items [])]
    (reduce (fn [acc v]
              (if-let [{id :value err :error} (normalize-uuid v label)]
                (if err
                  (reduced {:error err})
                  (update acc :value conj [ref-key id]))
                acc))
            {:value []}
            vals)))
