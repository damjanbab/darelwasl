#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() {
  echo "Usage: scripts/theme-css-vars.sh [theme-id] [output-file]"
  echo "Generate CSS variables from registries/theme.edn. Defaults to :theme/default and prints to stdout."
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

THEME_ID="${1:-:theme/default}"
OUTPUT_PATH="${2:-}"

if [ ! -f "$ROOT/registries/theme.edn" ]; then
  echo "Missing theme registry at $ROOT/registries/theme.edn" >&2
  exit 1
fi

export ROOT
export THEME_ID

generate() {
  clojure -M - <<'CLJ'
(require '[clojure.edn :as edn])
(require '[clojure.string :as str])

(defn normalize-key [s]
  (-> s
      (str/replace "/" "-")
      (str/replace "_" "-")
      (str/replace "?" "")
      (str/lower-case)))

(let [root (System/getenv "ROOT")
      theme-file (str root "/registries/theme.edn")
      themes (edn/read-string (slurp theme-file))
      default-id (read-string (System/getenv "THEME_ID"))
      token-keys [:colors :typography :spacing :radius :shadows :motion :components]
      flatten-tokens (fn flatten [prefix data]
                       (cond
                         (map? data) (mapcat (fn [[k v]]
                                               (let [key-str (if (keyword? k) (name k) (str k))
                                                     next-prefix (if prefix
                                                                   (str prefix "-" (normalize-key key-str))
                                                                   (normalize-key key-str))]
                                                 (flatten next-prefix v)))
                                             data)
                         (sequential? data) (map-indexed (fn [idx v]
                                                           [(str prefix "-" (inc idx)) v])
                                                         data)
                         :else [[prefix data]]))
      emit-theme (fn [{:keys [id] :as theme}]
                   (let [slug (normalize-key (name id))
                         selector (if (= id default-id)
                                    (str ":root, [data-theme=\"" slug "\"]")
                                    (str "[data-theme=\"" slug "\"]"))
                         tokens (->> token-keys
                                     (mapcat (fn [k]
                                               (when-let [v (get theme k)]
                                                 (flatten-tokens (normalize-key (name k)) v)))))]
                     (println (str selector " {"))
                     (doseq [[k v] tokens]
                       (let [value (cond
                                     (string? v) v
                                     (keyword? v) (name v)
                                     (number? v) (str v "px")
                                     :else (str v))]
                         (println (format "  --%s: %s;" k value))))
                     (println "}")))]
  (when-not (some #(= (:id %) default-id) themes)
    (binding [*out* *err*]
      (println "Theme not found:" default-id))
    (System/exit 1))

  (doseq [theme themes]
    (emit-theme theme)))
CLJ
}

if [ -n "$OUTPUT_PATH" ]; then
  generate >"$OUTPUT_PATH"
  echo "Wrote CSS variables for $THEME_ID to $OUTPUT_PATH"
else
  generate
fi
