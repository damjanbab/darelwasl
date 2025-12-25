(ns darelwasl.entity
  "Small shared helpers for working with entities keyed by :entity/type. Keep
  overrides/behavior in calling namespaces; this is just basic list/pull and
  type enforcement."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]))

(def ^:private ref-alphabet "23456789ABCDEFGHJKLMNPQRSTUVWXYZ")
(def ^:private ref-length 5)
(def ^:private ref-pattern #"^[A-Z]{1,4}-[A-Z0-9]{4,8}$")

(defn ref?
  [value]
  (and (string? value)
       (re-matches ref-pattern value)))

(defn- prefix-for-type
  [entity-type]
  (let [base (name entity-type)
        tokens (->> (str/split base #"-")
                    (remove str/blank?))]
    (->> tokens
         (map #(subs % 0 1))
         (apply str)
         str/upper-case)))

(defn- random-code
  []
  (apply str (repeatedly ref-length #(rand-nth ref-alphabet))))

(defn- ref-exists?
  [db ref]
  (boolean
   (seq (d/q '[:find ?e
               :in $ ?ref
               :where [?e :entity/ref ?ref]]
             db ref))))

(defn unique-ref
  ([db entity-type] (unique-ref db entity-type #{}))
  ([db entity-type reserved]
   (let [prefix (prefix-for-type entity-type)]
     (loop [attempt 0]
       (when (> attempt 20)
         (throw (ex-info "Failed to allocate unique entity ref"
                         {:entity/type entity-type})))
       (let [candidate (str prefix "-" (random-code))]
         (if (or (contains? reserved candidate)
                 (ref-exists? db candidate))
           (recur (inc attempt))
           candidate))))))

(defn with-ref
  [db entity]
  (if (or (:entity/ref entity) (nil? (:entity/type entity)))
    entity
    (assoc entity :entity/ref (unique-ref db (:entity/type entity)))))

(defn lookup-id-by-ref
  [db id-attr ref]
  (ffirst
   (d/q '[:find ?id
          :in $ ?attr ?ref
          :where [?e :entity/ref ?ref]
                 [?e ?attr ?id]]
        db id-attr ref)))

(defn resolve-id
  "Resolve an id value that may be a UUID or an :entity/ref string."
  [db id-attr value label]
  (let [raw (if (sequential? value) (first value) value)]
    (cond
      (nil? raw) {:value nil}
      (uuid? raw) {:value raw}
      (string? raw)
      (try
        {:value (java.util.UUID/fromString (str/trim raw))}
        (catch Exception _
          (if (ref? raw)
            (if-let [resolved (lookup-id-by-ref db id-attr raw)]
              {:value resolved}
              {:error (str (str/capitalize label) " not found")})
            {:error (str "Invalid " label)})))
      :else {:error (str "Invalid " label)})))

(defn eids-by-type
  "Return all entity ids for a given :entity/type keyword."
  [db entity-type]
  (map first
       (d/q '[:find ?e
              :in $ ?type
              :where [?e :entity/type ?type]]
            db entity-type)))
