(ns darelwasl.http.routes.registries
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [darelwasl.http.common :as common])
  (:import (java.io PushbackReader)))

(def ^:private registry-files
  {:schema "registries/schema.edn"
   :actions "registries/actions.edn"
   :views "registries/views.edn"
   :integrations "registries/integrations.edn"
   :tooling "registries/tooling.edn"
   :theme "registries/theme.edn"
   :automations "registries/automations.edn"})

(defn- read-registry
  [path]
  (with-open [r (PushbackReader. (io/reader (io/file path)))]
    (edn/read r)))

(defn- normalize-name
  [raw]
  (when raw
    (let [value (str raw)
          trimmed (str/trim value)]
      (when-not (str/blank? trimmed)
        (keyword trimmed)))))

(defn list-registries-handler
  [_state]
  (fn [request]
    (let [raw (or (get-in request [:query-params :name])
                  (get-in request [:query-params "name"]))
          reg-key (normalize-name raw)]
      (cond
        (and reg-key (not (contains? registry-files reg-key)))
        (common/error-response 400 "Unknown registry name" {:name reg-key})

        reg-key
        (let [entries (read-registry (get registry-files reg-key))]
          {:status 200
           :body {:registry reg-key
                  :entries entries}})

        :else
        (let [registries (into {}
                               (map (fn [[k path]]
                                      [k (read-registry path)]))
                               registry-files)]
          {:status 200
           :body {:registries registries}})))))

(defn routes
  [state]
  [["/registries"
    {:middleware [common/require-session
                  (common/require-roles #{:role/admin})]}
    ["" {:get (list-registries-handler state)}]]])
