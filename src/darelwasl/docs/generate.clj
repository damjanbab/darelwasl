(ns darelwasl.docs.generate
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [darelwasl.catalog.generate :as catalog-gen]))

(def ^:private system-generated-path "docs/system.generated.md")
(def ^:private catalog-path "docs/catalog.edn")

(defn- bullet-list
  [items]
  (if (seq items)
    (str (str/join "\n" (map #(str "- " %) items)) "\n")
    ""))

(defn- registry-summary
  [entries kind label]
  (let [items (->> entries
                   (filter #(= (:kind %) kind))
                   (map :registry-id)
                   (remove nil?)
                   (map name)
                   sort)
        header (str "### " label " (" (count items) ")\n")]
    (str header (bullet-list items) "\n")))

(defn- list-by-kind
  [entries kind]
  (->> entries
       (filter #(= (:kind %) kind))
       (map (fn [entry]
              (or (:name entry) (:id entry))))
       sort))

(defn- list-routes
  [entries]
  (->> entries
       (filter #(= (:kind %) :route))
       (map :path)
       sort))

(defn- list-envs
  [entries]
  (->> entries
       (filter #(= (:kind %) :config-env))
       (map :name)
       sort))

(defn- render-system-generated
  [catalog]
  (let [entries (:entries catalog)
        registry-section (str (registry-summary entries :schema "Schema")
                              (registry-summary entries :action "Actions")
                              (registry-summary entries :view "Views")
                              (registry-summary entries :integration "Integrations")
                              (registry-summary entries :tooling "Tooling")
                              (registry-summary entries :theme "Theme")
                              (registry-summary entries :automation "Automations"))
        session-types (list-by-kind entries :terminal-session-type)
        command-types (list-by-kind entries :terminal-command)
        routes (list-routes entries)
        envs (list-envs entries)
        scripts (list-by-kind entries :script)
        namespaces (list-by-kind entries :namespace)]
    (str "# System Inventory (generated)\n\n"
         "This file is auto-generated. Do not edit by hand.\n\n"
         "- Catalog version: `" (:version catalog) "`\n"
         "- Catalog file: `" catalog-path "`\n\n"
         "## Registry Summary\n\n"
         registry-section
         "## Terminal\n\n"
         "### Session types\n\n"
         (bullet-list session-types)
         "\n### Command types\n\n"
         (bullet-list command-types)
         "\n## Routes\n\n"
         (bullet-list routes)
         "\n## Config Env Vars (from config.clj)\n\n"
         (bullet-list envs)
         "\n## Scripts\n\n"
         (bullet-list scripts)
         "\n## Namespaces\n\n"
         (bullet-list namespaces))))

(defn write-system-generated!
  [path catalog]
  (spit path (render-system-generated catalog)))

(defn -main
  [& _args]
  (let [catalog (catalog-gen/write-catalog! catalog-path)]
    (write-system-generated! system-generated-path catalog)
    (println "Generated" system-generated-path "and" catalog-path)))

