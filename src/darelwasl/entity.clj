(ns darelwasl.entity
  "Small shared helpers for working with entities keyed by :entity/type. Keep
  overrides/behavior in calling namespaces; this is just basic list/pull and
  type enforcement."
  (:require [datomic.client.api :as d]))

(defn eids-by-type
  "Return all entity ids for a given :entity/type keyword."
  [db entity-type]
  (map first
       (d/q '[:find ?e
              :in $ ?type
              :where [?e :entity/type ?type]]
            db entity-type)))

(defn pull-by-type
  "Pull entities of a given :entity/type using the provided pull pattern."
  [db entity-type pull-pattern]
  (->> (eids-by-type db entity-type)
       (map #(d/pull db pull-pattern %))
       (remove nil?)))

(defn ensure-type
  "Assoc :entity/type on an entity map if missing."
  [entity entity-type]
  (if (:entity/type entity)
    entity
    (assoc entity :entity/type entity-type)))
