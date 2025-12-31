(ns darelwasl.terminal.spec
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:private required-keys
  [:spec/id :spec/title :spec/author :spec/goals :spec/acceptance :spec/skills])

(defn- now-ms
  []
  (System/currentTimeMillis))

(defn- normalize-input
  [value]
  (cond
    (map? value) value
    (string? value)
    (let [raw (str/trim value)
          raw (if (str/starts-with? raw "@spec")
                (str/trim (subs raw 5))
                raw)]
      (edn/read-string raw))
    :else nil))

(defn- spec-id
  []
  (str "spec-" (java.util.UUID/randomUUID)))

(defn- blank?
  [value]
  (or (nil? value)
      (and (string? value) (str/blank? value))))

(defn- ensure-id
  [spec]
  (if (blank? (:spec/id spec))
    (assoc spec :spec/id (spec-id))
    spec))

(defn- append-error
  [errors msg]
  (conj (or errors []) msg))

(defn- validate-required
  [spec errors]
  (reduce (fn [errs k]
            (if (blank? (get spec k))
              (append-error errs (str "Missing " (name k)))
              errs))
          errors
          required-keys))

(defn- validate-types
  [spec errors]
  (cond-> errors
    (not (string? (:spec/id spec)))
    (append-error "spec/id must be a string")

    (not (string? (:spec/title spec)))
    (append-error "spec/title must be a string")

    (not (keyword? (:spec/author spec)))
    (append-error "spec/author must be a keyword")

    (not (vector? (:spec/goals spec)))
    (append-error "spec/goals must be a vector of strings")

    (not (vector? (:spec/acceptance spec)))
    (append-error "spec/acceptance must be a vector of strings")

    (not (vector? (:spec/skills spec)))
    (append-error "spec/skills must be a vector of keywords")

    (and (contains? spec :spec/policies)
         (not (vector? (:spec/policies spec))))
    (append-error "spec/policies must be a vector of keywords")

    (and (contains? spec :spec/change-types)
         (not (set? (:spec/change-types spec))))
    (append-error "spec/change-types must be a set of keywords")

    (and (contains? spec :spec/context)
         (not (map? (:spec/context spec))))
    (append-error "spec/context must be a map")

    (and (contains? spec :spec/knowledge)
         (not (map? (:spec/knowledge spec))))
    (append-error "spec/knowledge must be a map")

    (and (contains? spec :spec/app)
         (not (map? (:spec/app spec))))
    (append-error "spec/app must be a map")))

(defn- validate-cross-fields
  [spec errors]
  (let [change-types (or (:spec/change-types spec) #{})]
    (cond-> errors
      (and (contains? change-types :change/knowledge)
           (nil? (:spec/knowledge spec)))
      (append-error "spec/knowledge is required for change/knowledge")

      (and (contains? change-types :change/app)
           (nil? (:spec/app spec)))
      (append-error "spec/app is required for change/app")

      (and (contains? change-types :change/scrape)
           (let [ctx (:spec/context spec)
                 targets (get ctx :scrape/targets)
                 fields (get ctx :scrape/fields)]
             (or (nil? ctx) (nil? targets) (nil? fields))))
      (append-error "spec/context :scrape/targets and :scrape/fields are required for change/scrape"))))

(defn validate-spec
  [value]
  (try
    (let [raw (normalize-input value)
          spec (some-> raw ensure-id (assoc :spec/created-at (now-ms)))
          errors (cond-> []
                   (nil? spec) (append-error "Spec must be an EDN map")
                   spec (validate-required spec)
                   spec (validate-types spec)
                   spec (validate-cross-fields spec))]
      (cond
        (nil? spec)
        {:status :invalid
         :errors errors}

        (seq errors)
        {:status :invalid
         :errors errors
         :spec spec}

        :else
        {:status :valid
         :errors []
         :spec spec}))
    (catch Exception e
      {:status :invalid
       :errors [(str "Failed to parse spec: " (.getMessage e))]})))
