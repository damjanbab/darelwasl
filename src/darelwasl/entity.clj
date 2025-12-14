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
