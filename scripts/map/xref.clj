(ns darelwasl.map.xref
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def registry-files
  ["registries/schema.edn"
   "registries/actions.edn"
   "registries/views.edn"
   "registries/integrations.edn"
   "registries/agents.edn"
   "registries/policies.edn"
   "registries/internal.edn"
   "registries/services.edn"
   "registries/contracts.edn"
   "registries/recipes.edn"
   "registries/tooling.edn"
   "registries/theme.edn"
   "registries/automations.edn"])

(defn- read-single-edn!
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (let [first (edn/read {:eof ::eof} r)
          second (edn/read {:eof ::eof} r)]
      (cond
        (= first ::eof) (throw (ex-info "Empty EDN file" {:path path}))
        (not= second ::eof) (throw (ex-info "Trailing forms detected" {:path path}))
        :else first))))

(defn- kw-str
  [x]
  (cond
    (keyword? x) (str x)
    (string? x) x
    :else (pr-str x)))

(defn- add-edge
  [edges from to type source]
  (conj edges {:from (kw-str from)
               :to (kw-str to)
               :type type
               :source source}))

(defn- edges-from-actions
  [actions]
  (reduce
    (fn [acc {:keys [id related tooling adapter]}]
      (let [schema (or (get related :schema) [])
            views (or (get related :views) [])
            adapter-integration (get adapter :integration)]
        (-> acc
            (into (map #(hash-map :from (kw-str id) :to (kw-str %) :type "action/schema" :source "registries/actions.edn") schema))
            (into (map #(hash-map :from (kw-str id) :to (kw-str %) :type "action/view" :source "registries/actions.edn") views))
            (into (map #(hash-map :from (kw-str id) :to (kw-str %) :type "action/tooling" :source "registries/actions.edn") (or tooling [])))
            (#(if adapter-integration
                (add-edge % id adapter-integration "action/adapter" "registries/actions.edn")
                %)))))
    []
    (or actions [])))

(defn- edges-from-views
  [views]
  (reduce
    (fn [acc {:keys [id actions related]}]
      (let [schemas (or (get related :schema) [])]
        (-> acc
            (into (map #(hash-map :from (kw-str id) :to (kw-str %) :type "view/action" :source "registries/views.edn") (or actions [])))
            (into (map #(hash-map :from (kw-str id) :to (kw-str %) :type "view/schema" :source "registries/views.edn") schemas)))))
    []
    (or views [])))

(defn- edges-from-integrations
  [integrations]
  (reduce
    (fn [acc {:keys [id related auth]}]
      (let [actions (or (get related :actions) [])
            schemas (or (get related :schema) [])
            vars (or (get auth :vars) [])]
        (-> acc
            (into (map #(hash-map :from (kw-str id) :to (kw-str %) :type "integration/action" :source "registries/integrations.edn") actions))
            (into (map #(hash-map :from (kw-str id) :to (kw-str %) :type "integration/schema" :source "registries/integrations.edn") schemas))
            (into (map #(hash-map :from (kw-str id) :to (str "env/" %) :type "integration/env" :source "registries/integrations.edn") vars)))))
    []
    (or integrations [])))

(defn- edges-from-agents
  [agents]
  (reduce
    (fn [acc {:keys [id policies]}]
      (into acc
            (map #(hash-map :from (kw-str id) :to (kw-str %) :type "agent/policy" :source "registries/agents.edn")
                 (or policies []))))
    []
    (or agents [])))

(defn- edges-from-policies
  [policies]
  (reduce
    (fn [acc {:keys [id policy-path]}]
      (if (and id (string? policy-path) (not (str/blank? policy-path)))
        (add-edge acc id policy-path "policy/file" "registries/policies.edn")
        acc))
    []
    (or policies [])))

(defn- edges-from-tooling
  [tooling]
  (mapcat
    (fn [{:keys [id related enforces files]}]
      (let [caps (or (get related :capabilities) [])
            enforced (cond
                       (vector? enforces) enforces
                       (map? enforces) (or (:capabilities enforces) [])
                       :else [])]
        (concat
          (for [cap caps]
            {:from (kw-str id) :to (kw-str cap) :type "tooling/capability" :source "registries/tooling.edn"})
          (for [t enforced]
            {:from (kw-str id) :to (kw-str t) :type "tooling/enforces" :source "registries/tooling.edn"})
          (for [f (or files [])]
            {:from (kw-str id) :to (kw-str f) :type "tooling/file" :source "registries/tooling.edn"}))))
    (or tooling [])))

(defn- dedupe-edges
  [edges]
  (->> edges
       (map #(select-keys % [:from :to :type :source]))
       distinct
       vec))

(defn- counts-by-type
  [edges]
  (reduce (fn [m {:keys [type]}] (update m type (fnil inc 0))) {} edges))

(defn- sort-edges
  [edges]
  (sort-by (juxt :type :from :to :source) edges))

(defn- parse-args
  [args]
  (let [argv (vec (map str args))
        flags (set (map str/trim argv))
        parse-int (fn [s]
                    (try
                      (Integer/parseInt s)
                      (catch Exception _
                        nil)))
        sample-n (let [i (.indexOf argv "--sample")
                       raw (when (and (<= 0 i) (< (inc i) (count argv)))
                             (nth argv (inc i)))]
                   (or (some-> raw str/trim parse-int)
                       25))]
    {:mode (cond
             (contains? flags "--full") :full
             (contains? flags "--summary") :summary
             :else :summary)
     :sample-n sample-n}))

(defn- topn
  [m n]
  (->> m
       (sort-by (fn [[k v]] [(- (long v)) (str k)]))
       (take n)
       (map (fn [[k v]] {:id (str k) :count v}))
       vec))

(defn -main
  [& args]
  (let [{:keys [mode sample-n]} (parse-args args)
        run-dir (or (System/getenv "DARELWASL_MAP_RUN_DIR") nil)
        actions (read-single-edn! "registries/actions.edn")
        views (read-single-edn! "registries/views.edn")
        integrations (read-single-edn! "registries/integrations.edn")
        agents (read-single-edn! "registries/agents.edn")
        policies (read-single-edn! "registries/policies.edn")
        tooling (read-single-edn! "registries/tooling.edn")
        edges (-> []
                  (into (edges-from-actions actions))
                  (into (edges-from-views views))
                  (into (edges-from-integrations integrations))
                  (into (edges-from-agents agents))
                  (into (edges-from-policies policies))
                  (into (edges-from-tooling tooling))
                  dedupe-edges
                  sort-edges
                  vec)
        out-deg (reduce (fn [m {:keys [from]}] (update m from (fnil inc 0))) {} edges)
        in-deg (reduce (fn [m {:keys [to]}] (update m to (fnil inc 0))) {} edges)
        edge-counts {:total (count edges)
                     :by_type (into (sorted-map) (counts-by-type edges))}
        result {:run_dir run-dir
                :mode (name mode)
                :edge_counts edge-counts
                :hubs {:top_from (topn out-deg 20)
                       :top_to (topn in-deg 20)}
                :edges_sample (when (= mode :summary) (vec (take (max 0 (long sample-n)) edges)))
                :regenerate {:full_command (str "DARELWASL_MAP_RUN_DIR="
                                                (or run-dir "<RUN_DIR>")
                                                " clojure -M scripts/map/xref.clj --full")
                             :summary_command (str "DARELWASL_MAP_RUN_DIR="
                                                   (or run-dir "<RUN_DIR>")
                                                   " clojure -M scripts/map/xref.clj --summary --sample "
                                                   (max 0 (long sample-n)))}
                :notes ["Edges derived from registries/{actions,views,integrations,agents,policies,tooling}.edn via deterministic parsing."
                        "Registry recipes/services/schema/contracts/internal/theme/automations are not yet edge-expanded in this script."]}]
    (println (json/write-str result :escape-slash false))))

(apply -main *command-line-args*)
