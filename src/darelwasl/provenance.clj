(ns darelwasl.provenance
  "Small helper for attaching provenance metadata to tx maps."
  (:require [clojure.string :as str]
            [darelwasl.workspace :as workspace])
  (:import (java.util Date)))

(def ^:private surface->adapter
  {:surface/http :adapter/web-ui
   :surface/telegram :adapter/telegram
   :surface/rule :adapter/rule
   :surface/import :adapter/import})

(defn- adapter-from-actor
  [actor]
  (or (:actor/adapter actor)
      (get surface->adapter (:actor/surface actor))))

(defn provenance
  "Build a provenance map for writes. Adapter defaults to web-ui; workspace defaults to \"main\".
  Optionally accepts a run-id for batch/rule contexts."
  ([actor] (provenance actor (or (adapter-from-actor actor) :adapter/web-ui) nil))
  ([actor adapter] (provenance actor adapter nil))
  ([actor adapter run-id]
   (into {}
         (remove (comp nil? val)
                 {:fact/source-id (str (or (:user/id actor) (:user/username actor) "system"))
                  :fact/source-type (if (:user/id actor) :user :system)
                  :fact/adapter adapter
                  :fact/run-id run-id
                  :fact/created-at (Date.)
                  :fact/valid-from (Date.)
                  :fact/workspace (workspace/actor-workspace actor)}))))

(defn enrich-tx
  "Attach provenance to tx maps; leave retracts and datoms untouched."
  [tx prov]
  (if (map? tx) (merge tx prov) tx))
