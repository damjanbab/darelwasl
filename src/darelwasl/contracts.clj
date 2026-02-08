;; Data-driven service definitions and workflow contracts.
(ns darelwasl.contracts
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io PushbackReader)))

(def default-services-path "registries/services.edn")
(def default-contracts-path "registries/contracts.edn")

(defn- read-edn
  [path]
  (let [file (io/file path)]
    (when-not (.exists file)
      (throw (ex-info "Registry not found" {:path path})))
    (with-open [r (PushbackReader. (io/reader file))]
      (edn/read r))))

(defn read-services
  ([] (read-services default-services-path))
  ([path]
   (let [items (read-edn path)]
     (if (vector? items) items (vec (or items []))))))

(defn read-contracts
  ([] (read-contracts default-contracts-path))
  ([path]
   (let [items (read-edn path)]
     (if (vector? items) items (vec (or items []))))))

(defn- normalize-version
  [v]
  (let [s (some-> v str str/trim)]
    (when-not (str/blank? s) s)))

(defn services-index
  ([] (services-index (read-services)))
  ([services]
   (into {}
         (keep (fn [s]
                 (when (keyword? (:id s))
                   [(:id s) s])))
         services)))

(defn contracts-index
  ([] (contracts-index (read-contracts)))
  ([contracts]
   (->> contracts
        (filter #(and (map? %) (keyword? (:id %)) (keyword? (:service/id %))))
        (group-by :service/id)
        (into {} (map (fn [[sid items]]
                        [sid (vec items)]))))))

(defn latest-contract-for-service
  "Return the latest (by lexicographic version, fallback stable order) contract for a service id."
  ([service-id] (latest-contract-for-service service-id (contracts-index)))
  ([service-id idx]
   (let [items (get idx service-id)]
     (when (seq items)
       (let [with-v (map (fn [c] (assoc c ::ver (normalize-version (:version c)))) items)
             sorted (sort-by (fn [c] [(or (::ver c) "") (name (:id c))]) with-v)]
         (-> (last sorted)
             (dissoc ::ver)))))))

(def ^:private public-phase-order
  [:public.phase/onboarding
   :public.phase/licensing
   :public.phase/activation
   :public.phase/banking
   :public.phase/closed])

(defn public-phase-rank
  [phase]
  (let [idx (.indexOf public-phase-order phase)]
    (if (neg? idx) 0 idx)))

(defn public-phases
  []
  public-phase-order)

