(ns darelwasl.knowledge.extract
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import (org.jsoup Jsoup)
           (org.apache.pdfbox.pdmodel PDDocument)
           (org.apache.pdfbox.text PDFTextStripper)))

(defn- clean-line
  [s]
  (let [v (some-> s str/trim (str/replace #"\s+" " "))]
    (when-not (str/blank? v) v)))

(defn extract-html
  [html]
  (let [doc (Jsoup/parse (or html ""))
        title (some-> (.title doc) clean-line)
        elements (.select doc "h1,h2,h3,h4,h5,h6,p,li,article,section")
        lines (->> elements
                   (map #(.text %))
                   (map clean-line)
                   (remove nil?))
        spans (vec (map-indexed (fn [idx line]
                                  {:page 1 :idx (inc idx) :text line})
                                lines))
        text (->> lines (str/join "\n"))]
    {:title title
     :text text
     :spans spans}))

(defn extract-pdf
  [^bytes bytes]
  (with-open [doc (PDDocument/load bytes)]
    (let [pages (.getNumberOfPages doc)
          stripper (doto (PDFTextStripper.)
                     (.setSortByPosition true))
          results (loop [page 1
                         spans []
                         texts []]
                    (if (> page pages)
                      {:spans spans :texts texts}
                      (do
                        (.setStartPage stripper page)
                        (.setEndPage stripper page)
                        (let [text (.getText stripper doc)
                              lines (->> (str/split-lines (or text ""))
                                         (map clean-line)
                                         (remove nil?))
                              page-spans (map-indexed (fn [idx line]
                                                        {:page page
                                                         :idx (inc idx)
                                                         :text line})
                                                      lines)]
                          (recur (inc page)
                                 (into spans page-spans)
                                 (conj texts (str/join "\n" lines)))))))]
      {:text (str/join "\n\n" (:texts results))
       :spans (vec (:spans results))})))

(def article-patterns
  [#"^(?i)(article|art\.)\s+([0-9]+[A-Za-z-]*)[:.\s-]*(.*)$"
   #"^(?i)(section)\s+([0-9]+[A-Za-z-]*)[:.\s-]*(.*)$"
   #"^(?i)(chapter)\s+([0-9]+[A-Za-z-]*)[:.\s-]*(.*)$"
   #"^(?i)(part)\s+([0-9]+[A-Za-z-]*)[:.\s-]*(.*)$"
   #"^(?i)(المادة)\s+([0-9]+)[:.\s-]*(.*)$"
   #"^(?i)(الفصل)\s+([0-9]+)[:.\s-]*(.*)$"
   #"^(?i)(الباب)\s+([0-9]+)[:.\s-]*(.*)$"])

(defn- heading?
  [line]
  (some (fn [re]
          (when-let [[_ label num title] (re-find re line)]
            {:label label :number num :title (clean-line title)}))
        article-patterns))

(defn sectionize
  "Build a section list from spans. Returns [{:title :number :level :text :spans}]."
  [spans]
  (let [default-level 2]
    (loop [remaining spans
           current nil
           sections []
           order 0
           stack []]
      (if (empty? remaining)
        (cond-> sections current (conj current))
        (let [{:keys [text] :as span} (first remaining)
              heading (heading? text)]
          (if heading
            (let [level (case (str/lower-case (:label heading))
                          ("chapter" "part" "الفصل" "الباب") 1
                          default-level)
                  base {:title (or (:title heading) text)
                        :number (:number heading)
                        :level level
                        :order (inc order)
                        :spans [span]
                        :text ""}
                  [stack current] (loop [stack stack]
                                     (if (empty? stack)
                                       [stack current]
                                       (let [top (peek stack)]
                                         (if (< (:level top) level)
                                           [stack current]
                                           (recur (pop stack))))))
                  parent (peek stack)
                  base (cond-> base parent (assoc :parent-index (:order parent)))]
              (recur (rest remaining)
                     base
                     (cond-> sections current (conj current))
                     (inc order)
                     (conj stack base)))
            (let [next-current (if current
                                 (-> current
                                     (update :text #(str (or % "") (when (seq %) "\n") text))
                                     (update :spans conj span))
                                 current)]
              (recur (rest remaining) next-current sections order stack))))))))

(defn extract-xrefs
  [text spans]
  (let [lines (str/split-lines (or text ""))
        decree-re #"(?i)royal decree\s+no\.\s*([A-Za-z0-9/\-]+)"
        decree-ar-re #"(?i)المرسوم\s+الملكي\s+رقم\s*([0-9A-Za-z/\-]+)"
        article-re #"(?i)article\s+([0-9]+[A-Za-z-]*)"]
    (->> (map-indexed (fn [idx line]
                        (let [sp (nth spans idx nil)
                              refs (concat
                                    (map (fn [[_ num]] {:kind :decree :value num}) (re-seq decree-re line))
                                    (map (fn [[_ num]] {:kind :decree :value num}) (re-seq decree-ar-re line))
                                    (map (fn [[_ num]] {:kind :article :value num}) (re-seq article-re line)))]
                          (map (fn [ref]
                                 {:text line
                                  :span sp
                                  :ref ref})
                               refs)))
                      lines)
         (apply concat)
         vec)))

(defn safe-extract
  [mime bytes html]
  (try
    (cond
      (and mime (str/starts-with? mime "application/pdf")) (extract-pdf bytes)
      (seq html) (extract-html html)
      :else {:text "" :spans []})
    (catch Exception e
      (log/warn e "Extraction failed")
      {:error "Extraction failed"})))
