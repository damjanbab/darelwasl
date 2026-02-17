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

(defn- work-status
  [entry]
  (some-> entry :data :work/status))

(defn- list-open-work-items
  [entries]
  (->> entries
       (filter #(= (:kind %) :work-item))
       (filter #(= "open" (work-status %)))
       (map :id)
       sort))

(defn- render-system-generated
  [catalog]
  (let [entries (:entries catalog)
        registry-section (str (registry-summary entries :schema "Schema")
                              (registry-summary entries :action "Actions")
                              (registry-summary entries :view "Views")
                              (registry-summary entries :integration "Integrations")
                              (registry-summary entries :agent "Agents")
                              (registry-summary entries :policy "Policies")
                              (registry-summary entries :internal "Internal capabilities")
                              (registry-summary entries :service "Services")
                              (registry-summary entries :contract "Contracts")
                              (registry-summary entries :recipe "Recipes")
                              (registry-summary entries :tooling "Tooling")
                              (registry-summary entries :theme "Theme")
                              (registry-summary entries :automation "Automations"))
        routes (list-routes entries)
        envs (list-envs entries)
        scripts (list-by-kind entries :script)
        namespaces (list-by-kind entries :namespace)
        open-work-items (list-open-work-items entries)
        query-section (str "## Querying the Codebase (protocol)\n\n"
                           "Start with the generated catalog, then narrow to source files.\n\n"
                           "- Inventory snapshot: `docs/system.generated.md`\n"
                           "- Machine-readable catalog: `docs/catalog.edn`\n"
                           "- Query tool (catalog-backed): `scripts/query.sh`\n\n"
                           "Common queries:\n\n"
                           "- Find anything by keyword: `scripts/query.sh TERM`\n"
                           "- Filter by kind: `scripts/query.sh --kind action TERM`\n"
                           "- Exact lookup by catalog id: `scripts/query.sh id action/action/auth-login`\n"
                           "- Exact lookup by registry id: `scripts/query.sh registry-id :cap/action/auth-login`\n"
                           "- Show only file paths: `scripts/query.sh --paths TERM`\n\n")]
    (str "# System Inventory (generated)\n\n"
         "This file is auto-generated. Do not edit by hand.\n\n"
         "- Catalog version: `" (:version catalog) "`\n"
         "- Catalog file: `" catalog-path "`\n\n"
         "## Registry Summary\n\n"
         registry-section
         query-section
         "## Routes\n\n"
         (bullet-list routes)
         "\n## Config Env Vars (from config.clj)\n\n"
         (bullet-list envs)
         "\n## Scripts\n\n"
         (bullet-list scripts)
         "\n## Namespaces\n\n"
         (bullet-list namespaces)
         "\n## Work items (open)\n\n"
         "Work items live in `docs/work/` and can be queried via:\n\n"
         "- `scripts/query.sh --kind work-item TERM`\n"
         "- `scripts/work.sh list --open`\n\n"
         (bullet-list open-work-items))))

(defn write-system-generated!
  [path catalog]
  (spit path (render-system-generated catalog)))

(defn -main
  [& _args]
  (try
    (let [catalog (catalog-gen/write-catalog! catalog-path)]
      (write-system-generated! system-generated-path catalog)
      (println "Generated" system-generated-path "and" catalog-path)
      (shutdown-agents)
      (System/exit 0))
    (catch Throwable t
      (binding [*out* *err*]
        (println "Failed to generate docs:" (.getMessage t)))
      (shutdown-agents)
      (System/exit 1))))
