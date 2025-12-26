(ns darelwasl.catalog.generate
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.math BigInteger)
           (java.security MessageDigest)))

(def ^:private registry-files
  {:schema "registries/schema.edn"
   :actions "registries/actions.edn"
   :views "registries/views.edn"
   :integrations "registries/integrations.edn"
   :tooling "registries/tooling.edn"
   :theme "registries/theme.edn"
   :automations "registries/automations.edn"})

(def ^:private terminal-session-types
  [{:id :feature :label "Feature Build"}
   {:id :bugfix :label "Bugfix/Hotfix"}
   {:id :research :label "Research/Spike"}
   {:id :integrator :label "Integrator"}
   {:id :ops :label "Ops/Admin"}
   {:id :main-ops :label "Main Ops (Data)"}])

(def ^:private command-prefixes
  ["task." "file." "context." "devbot." "session." "library."])

(def ^:private route-root-paths
  ["src/darelwasl/http/routes"
   "src/darelwasl/http.clj"
   "src/darelwasl/terminal/http.clj"])

(defn- read-edn
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader (io/file path)))]
    (edn/read r)))

(defn- sha256
  [text]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md (.getBytes (str text) "UTF-8"))
    (format "%064x" (BigInteger. 1 (.digest md)))))

(defn- normalize-source
  [file]
  (-> file io/file .getPath))

(defn- file->ns
  [file]
  (let [text (slurp file)
        match (re-find #"\(ns\s+([^\s\)]+)" text)]
    (second match)))

(defn- list-namespaces
  []
  (let [root (io/file "src")]
    (->> (file-seq root)
         (filter #(.isFile ^java.io.File %))
         (filter #(re-find #"\.(clj|cljs|cljc)$" (.getName ^java.io.File %)))
         (map (fn [file]
                (when-let [ns-name (file->ns file)]
                  {:id (str "ns/" ns-name)
                   :kind :namespace
                   :name ns-name
                   :source (normalize-source file)})))
         (remove nil?))))

(defn- registry-kind
  [k]
  (case k
    :schema :schema
    :actions :action
    :views :view
    :integrations :integration
    :tooling :tooling
    :theme :theme
    :automations :automation
    :registry))

(defn- registry-entries
  [k entries path]
  (let [kind (registry-kind k)]
    (map (fn [entry]
           (let [id (:id entry)
                 title (or (:title entry) (some-> id name str/capitalize))]
             {:id (str (name kind) "/" (name id))
              :kind kind
              :name title
              :registry k
              :registry-id id
              :source path
              :data entry}))
         entries)))

(defn- registry-catalog
  []
  (mapcat (fn [[k path]]
            (registry-entries k (read-edn path) path))
          registry-files))

(defn- config-env-vars
  []
  (let [text (slurp "src/darelwasl/config.clj")
        vars (->> (re-seq #"get env \"([A-Z0-9_]+)\"" text)
                  (map second)
                  set
                  sort)]
    (map (fn [var]
           {:id (str "env/" var)
            :kind :config-env
            :name var
            :source "src/darelwasl/config.clj"})
         vars)))

(defn- command-types
  []
  (let [text (slurp "src/darelwasl/terminal/commands.clj")
        raw (->> (re-seq #"\"([a-z][a-z0-9\.\-]+)\"" text)
                 (map second))
        commands (->> raw
                      (filter (fn [value]
                                (some #(str/starts-with? value %) command-prefixes)))
                      distinct
                      sort)]
    (map (fn [cmd]
           {:id (str "terminal-command/" cmd)
            :kind :terminal-command
            :name cmd
            :source "src/darelwasl/terminal/commands.clj"})
         commands)))

(defn- session-type-entries
  []
  (map (fn [{:keys [id label]}]
         {:id (str "terminal-session/" (name id))
          :kind :terminal-session-type
          :name label
          :value id
          :source "src/darelwasl/features/terminal.cljs"})
       terminal-session-types))

(defn- script-entries
  []
  (let [root (io/file "scripts")]
    (->> (file-seq root)
         (filter #(.isFile ^java.io.File %))
         (map (fn [file]
                (let [name (.getName ^java.io.File file)]
                  {:id (str "script/" name)
                   :kind :script
                   :name name
                   :source (normalize-source file)}))))))

(defn- route-paths
  []
  (let [files (map io/file route-root-paths)
        targets (mapcat (fn [f]
                          (if (.isDirectory ^java.io.File f)
                            (file-seq f)
                            [f]))
                        files)]
    (->> targets
         (filter #(.isFile ^java.io.File %))
         (filter #(re-find #"\.clj$" (.getName ^java.io.File %)))
         (mapcat (fn [file]
                   (let [text (slurp file)]
                     (for [match (re-seq #"\"(/[^\"]+)\"" text)]
                       {:path (second match)
                        :source (normalize-source file)}))))
         (reduce (fn [acc {:keys [path source]}]
                   (update acc path (fnil conj #{}) source))
                 {})
         (map (fn [[path sources]]
                {:id (str "route" path)
                 :kind :route
                 :name path
                 :path path
                 :source (vec (sort sources))}))
         (sort-by :id))))

(defn- sort-map-recursive
  [value]
  (cond
    (map? value) (into (sorted-map) (map (fn [[k v]] [k (sort-map-recursive v)])) value)
    (sequential? value) (mapv sort-map-recursive value)
    :else value))

(defn build-catalog
  []
  (let [entries (->> (concat
                      (registry-catalog)
                      (list-namespaces)
                      (config-env-vars)
                      (command-types)
                      (session-type-entries)
                      (script-entries)
                      (route-paths))
                     (remove nil?)
                     (sort-by :id)
                     vec)
        fingerprint (sha256 (pr-str (sort-map-recursive entries)))]
    {:version fingerprint
     :entries entries}))

(defn write-catalog!
  [path]
  (let [catalog (build-catalog)]
    (spit path (pr-str catalog))
    catalog))
