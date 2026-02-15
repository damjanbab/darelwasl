(ns darelwasl.map.routes-map
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- read-single-edn!
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (let [first (edn/read {:eof ::eof} r)
          second (edn/read {:eof ::eof} r)]
      (cond
        (= first ::eof) (throw (ex-info "Empty EDN file" {:path path}))
        (not= second ::eof) (throw (ex-info "Trailing forms detected" {:path path}))
        :else first))))

(def ^:private method-keys
  #{:get :post :put :delete :patch :head :options})

(defn- join-path
  [prefix p]
  (cond
    (or (nil? prefix) (= prefix "")) p
    (= p "") prefix
    (str/starts-with? p "/") (str prefix p)
    :else (str prefix "/" p)))

(defn- handler-sym
  [h]
  (when h
    (let [cn (.getName (class h))
          parts (str/split cn #"\$")
          ns-part (first parts)
          cand (if (>= (count parts) 2)
                 (nth parts (- (count parts) 2))
                 (last parts))]
      (str (str/replace ns-part "_" "-")
           "/"
           (str/replace cand "_" "-")))))

(defn- endpoint-handlers
  "Return {path #{handler-sym ...}} by walking route data structures."
  [state]
  (require 'darelwasl.http)
  (require 'darelwasl.http.routes.preview)
  (let [http (find-ns 'darelwasl.http)
        preview (find-ns 'darelwasl.http.routes.preview)
        health-route (ns-resolve http 'health-route)
        verify-route (ns-resolve http 'verify-route)
        api-routes (ns-resolve http 'api-routes)
        preview-routes (ns-resolve preview 'routes)
        roots [(health-route state)
               (verify-route state)
               (api-routes state)
               (preview-routes state)]]
    (letfn [(emit [m full data]
              (reduce
                (fn [acc k]
                  (if-not (contains? data k)
                    acc
                    (let [v (get data k)
                          h (if (map? v) (:handler v) v)
                          hs (handler-sym h)]
                      (if (and hs (not (str/blank? hs)))
                        (update acc full (fnil conj #{}) hs)
                        acc))))
                m
                method-keys))
            (walk [m prefix node]
              (cond
                (and (vector? node) (string? (first node)))
                (let [p (first node)
                      full (join-path prefix p)
                      rest (rest node)
                      data (apply merge (filter map? rest))
                      children (filter vector? rest)
                      m2 (emit m full data)]
                  (reduce (fn [acc c] (walk acc full c)) m2 children))

                (sequential? node)
                (reduce (fn [acc x] (walk acc prefix x)) m node)

                :else m))]
      (walk {} "" roots))))

(defn -main
  [& _args]
  (let [run-dir (or (System/getenv "DARELWASL_MAP_RUN_DIR") nil)
        catalog (read-single-edn! "docs/catalog.edn")
        route-entries (->> (:entries catalog)
                           (filter #(= :route (:kind %)))
                           (map (fn [e]
                                  {:path (:path e)
                                   :sources (->> (or (:source e) [])
                                                 (map str)
                                                 (remove str/blank?)
                                                 distinct
                                                 sort
                                                 vec)}))
                           vec)
        state {:db {:conn nil} :config {}}
        handlers-by-path (endpoint-handlers state)
        routes (->> route-entries
                    (map (fn [{:keys [path sources]}]
                           (let [handlers (->> (get handlers-by-path path #{})
                                                sort
                                                vec)]
                             {:path (str path)
                              :sources sources
                              :handlers handlers})))
                    vec)
        unique-sources (->> routes (mapcat :sources) distinct count)
        routes-with-handlers (count (filter (fn [r] (seq (:handlers r))) routes))
        result {:run_dir run-dir
                :routes routes
                :counts {:routes (count routes)
                         :unique_sources unique-sources
                         :routes_with_handlers routes-with-handlers}
                :notes ["Routes and sources come from docs/catalog.edn entries where :kind is :route."
                        "Handlers are inferred by walking the route data returned from darelwasl.http and darelwasl.http.routes.preview with a dummy state; mapping is exact path match only."]}]
    (println (json/write-str result :escape-slash false))))

(apply -main *command-line-args*)

