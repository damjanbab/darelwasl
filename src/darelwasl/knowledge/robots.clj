(ns darelwasl.knowledge.robots
  (:require [clojure.string :as str]))

(defn- blankish?
  [s]
  (or (nil? s) (and (string? s) (str/blank? s))))

(defn parse-robots
  "Parse robots.txt into {:rules [{:agents #{*} :allow [...] :disallow [...] :crawl-delay n}]}"
  [body]
  (let [lines (->> (str/split-lines (or body ""))
                   (map #(str/trim (str/replace % #"#.*$" "")))
                   (remove blankish?))]
    (loop [remaining lines
           current {:agents #{} :allow [] :disallow [] :crawl-delay nil}
           rules []]
      (if (empty? remaining)
        (let [rules (cond-> rules (seq (:agents current)) (conj current))]
          {:rules rules})
        (let [[line & more] remaining
              [k v] (->> (str/split line #":" 2)
                         (map str/trim))
              key (str/lower-case (or k ""))]
          (cond
            (= key "user-agent")
            (let [agent (str/lower-case (or v ""))
                  next-rule (if (seq (:agents current))
                              {:agents #{agent} :allow [] :disallow [] :crawl-delay nil}
                              (update current :agents conj agent))
                  next-rules (if (seq (:agents current))
                               (conj rules current)
                               rules)]
              (recur more next-rule next-rules))

            (= key "disallow")
            (recur more (update current :disallow conj (or v "")) rules)

            (= key "allow")
            (recur more (update current :allow conj (or v "")) rules)

            (= key "crawl-delay")
            (let [delay (try
                          (Long/parseLong (or v ""))
                          (catch Exception _ nil))]
              (recur more (assoc current :crawl-delay delay) rules))

            :else
            (recur more current rules)))))))

(defn- match-prefix
  [path rule]
  (and (seq rule)
       (str/starts-with? path rule)))

(defn- best-match
  [path rules]
  (let [matches (filter #(match-prefix path %) rules)]
    (when (seq matches)
      (apply max-key count matches))))

(defn- rules-for-agent
  [robots agent]
  (let [agent (str/lower-case (or agent ""))]
    (or (some (fn [rule]
                (when (contains? (:agents rule) agent) rule))
              (:rules robots))
        (some (fn [rule]
                (when (contains? (:agents rule) "*") rule))
              (:rules robots))
        {:agents #{"*"} :allow [] :disallow [] :crawl-delay nil})))

(defn crawl-delay
  [robots agent default-delay]
  (or (:crawl-delay (rules-for-agent robots agent))
      default-delay))

(defn allowed?
  [robots agent path]
  (let [rule (rules-for-agent robots agent)
        path (or path "/")
        allow (best-match path (:allow rule))
        disallow (best-match path (:disallow rule))]
    (cond
      (and allow disallow) (>= (count allow) (count disallow))
      disallow false
      :else true)))
