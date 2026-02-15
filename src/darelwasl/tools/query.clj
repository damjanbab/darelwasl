(ns darelwasl.tools.query
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private default-catalog-path "docs/catalog.edn")

(defn- usage
  []
  (str "Usage: scripts/query.sh [--catalog PATH] [--kind KIND] [--limit N] [--full] [--paths] TERM\n"
       "       scripts/query.sh id ID\n"
       "       scripts/query.sh registry-id KW\n"
       "       scripts/query.sh --self-check\n"
       "\n"
       "Examples:\n"
       "  scripts/query.sh auth-login\n"
       "  scripts/query.sh --kind action auth-login\n"
       "  scripts/query.sh id action/action/auth-login\n"
       "  scripts/query.sh registry-id :cap/action/auth-login\n"
       "  scripts/query.sh --paths \"/api\" \n"))

(defn- die!
  ([msg] (die! 2 msg))
  ([code msg]
   (binding [*out* *err*]
     (println msg))
   (shutdown-agents)
   (System/exit code)))

(defn- parse-int
  [s]
  (try
    (Integer/parseInt (str s))
    (catch Exception _
      nil)))

(defn- ->kind
  [s]
  (let [s (str/trim (str s))
        s (str/replace s #"^:" "")]
    (when-not (str/blank? s)
      (keyword s))))

(defn- read-catalog!
  [path]
  (let [f (io/file path)]
    (when-not (.exists f)
      (die! (str "Catalog not found at " path ". Run scripts/generate-docs.sh first.")))
    (when-not (pos? (.length f))
      (die! (str "Catalog is empty at " path ". Run scripts/generate-docs.sh first.")))
    (try
      (with-open [r (java.io.PushbackReader. (io/reader f))]
        (let [data (edn/read {:eof ::eof} r)]
          (when (= data ::eof)
            (die! (str "Catalog is unreadable at " path ". Run scripts/generate-docs.sh first.")))
          data))
      (catch Exception e
        (die! (str "Failed to read catalog at " path ": " (.getMessage e)))))))

(defn- entry->sources
  [entry]
  (let [s (:source entry)]
    (cond
      (nil? s) []
      (string? s) [s]
      (sequential? s) (mapv str s)
      :else [(pr-str s)])))

(defn- score-entry
  [term-lc entry]
  (let [id (str (:id entry))
        title (str (:name entry))
        registry-id (:registry-id entry)
        registry-id-name (some-> registry-id name)
        route-path (some-> (:path entry) str)
        sources (str/join " " (entry->sources entry))
        exact? (fn [s] (and s (= term-lc (str/lower-case (str s)))))
        contains? (fn [s] (and s (str/includes? (str/lower-case (str s)) term-lc)))]
    (cond
      (exact? id) 100
      (exact? registry-id-name) 95
      (exact? route-path) 92
      (contains? id) 60
      (contains? registry-id-name) 55
      (contains? title) 40
      (contains? route-path) 35
      (contains? sources) 10
      :else 0)))

(defn- summarize-entry
  [entry]
  (let [summary {:kind (:kind entry)
                 :id (:id entry)
                 :registry-id (:registry-id entry)
                 :name (:name entry)
                 :path (:path entry)
                 :source (:source entry)}]
    (pr-str (into (sorted-map) (remove (comp nil? val) summary)))))

(defn- parse-args
  [args]
  (loop [state {:mode :search
                :catalog default-catalog-path
                :kinds #{}
                :limit 30
                :full? false
                :paths? false
                :term nil
                :id nil
                :registry-id nil
                :self-check? false}
         xs args]
    (if (empty? xs)
      state
      (let [[x & more] xs]
        (cond
          (or (= x "-h") (= x "--help"))
          (recur (assoc state :mode :help) more)

          (= x "--catalog")
          (let [p (first more)]
            (when-not p (die! (usage)))
            (recur (assoc state :catalog p) (rest more)))

          (= x "--kind")
          (let [k (first more)
                kind (->kind k)]
            (when-not kind (die! (usage)))
            (recur (update state :kinds conj kind) (rest more)))

          (= x "--limit")
          (let [n (parse-int (first more))]
            (when-not n (die! (usage)))
            (recur (assoc state :limit n) (rest more)))

          (= x "--full")
          (recur (assoc state :full? true) more)

          (= x "--paths")
          (recur (assoc state :paths? true) more)

          (= x "--self-check")
          (recur (assoc state :self-check? true) more)

          (= x "id")
          (let [id (first more)]
            (when-not id (die! (usage)))
            (recur (assoc state :mode :id :id id) (rest more)))

          (= x "registry-id")
          (let [rid (first more)]
            (when-not rid (die! (usage)))
            (recur (assoc state :mode :registry-id :registry-id rid) (rest more)))

          :else
          (if (:term state)
            (die! (usage))
            (recur (assoc state :term x) more)))))))

(defn- filter-by-kinds
  [entries kinds]
  (if (seq kinds)
    (filter (fn [e] (contains? kinds (:kind e))) entries)
    entries))

(defn- run-self-check!
  [{:keys [catalog]}]
  (let [data (read-catalog! catalog)
        entries (:entries data)
        kinds (->> entries (map :kind) set)]
    (when-not (map? data)
      (die! (str "Catalog is not a map at " catalog)))
    (when-not (vector? entries)
      (die! (str "Catalog :entries is not a vector at " catalog)))
    (when-not (pos? (count entries))
      (die! (str "Catalog has no entries at " catalog)))
    (doseq [k [:schema :action :view :route :namespace :script]]
      (when-not (contains? kinds k)
        (die! (str "Catalog self-check failed: missing kind " k))))
    (println (pr-str {:ok true
                      :catalog catalog
                      :entries (count entries)
                      :kinds (sort (map name kinds))}))))

(defn- run-id!
  [{:keys [catalog id full?]}]
  (let [entries (:entries (read-catalog! catalog))
        match (first (filter (fn [e] (= id (:id e))) entries))]
    (when-not match
      (die! 1 (str "No entry found with id=" id)))
    (println (if full? (pr-str match) (summarize-entry match)))))

(defn- run-registry-id!
  [{:keys [catalog registry-id full?]}]
  (let [entries (:entries (read-catalog! catalog))
        rid (try
              (edn/read-string registry-id)
              (catch Exception _
                registry-id))
        match (first (filter (fn [e] (= rid (:registry-id e))) entries))]
    (when-not match
      (die! 1 (str "No entry found with registry-id=" registry-id)))
    (println (if full? (pr-str match) (summarize-entry match)))))

(defn- run-search!
  [{:keys [catalog term kinds limit full? paths?]}]
  (when-not term
    (die! (usage)))
  (let [entries (:entries (read-catalog! catalog))
        entries (filter-by-kinds entries kinds)
        term-lc (str/lower-case (str term))
        scored (->> entries
                    (map (fn [e] (assoc e ::score (score-entry term-lc e))))
                    (filter (fn [e] (pos? (::score e))))
                    (sort-by (juxt (comp - ::score) :id))
                    (take limit))]
    (when-not (seq scored)
      (die! 1 (str "No matches for " (pr-str term) " (kinds=" (pr-str (sort (map name kinds))) ")")))
    (cond
      paths?
      (doseq [path (->> scored
                        (mapcat entry->sources)
                        (remove str/blank?)
                        distinct
                        sort)]
        (println path))

      full?
      (doseq [e scored]
        (println (pr-str e)))

      :else
      (doseq [e scored]
        (println (summarize-entry e))))))

(defn -main
  [& args]
  (let [opts (parse-args args)]
    (cond
      (= (:mode opts) :help) (do (println (usage)) (System/exit 0))
      (:self-check? opts) (do (run-self-check! opts) (System/exit 0))
      (= (:mode opts) :id) (do (run-id! opts) (System/exit 0))
      (= (:mode opts) :registry-id) (do (run-registry-id! opts) (System/exit 0))
      :else (do (run-search! opts) (System/exit 0)))))
