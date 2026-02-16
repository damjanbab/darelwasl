(ns darelwasl.webterm.fs
  (:require [clojure.string :as str])
  (:import (java.nio.file Files Path Paths StandardCopyOption LinkOption StandardOpenOption)
           (java.nio.file.attribute FileTime)))

(defn clamp
  [n lo hi]
  (-> n (max lo) (min hi)))

(defn safe-name
  [name default]
  (let [base (some-> name str (str/replace "\\" "/") (str/split #"/") last str/trim)]
    (cond
      (or (nil? base) (str/blank? base)) default
      (#{ "." ".."} base) default
      :else
      (let [cleaned (->> base
                         (map (fn [ch]
                                (if (or (Character/isLetterOrDigit ^char ch)
                                        (#{\. \_ \- \space} ch))
                                  ch
                                  \_)))
                         (apply str)
                         (str/trim)
                         (str/split #"\s+")
                         (str/join " "))]
        (cond
          (str/blank? cleaned) default
          (> (count cleaned) 200) (subs cleaned 0 200)
          :else cleaned)))))

(defn ensure-dirs!
  [^String lab-dir ^String session-name]
  (let [root (Paths/get lab-dir (into-array String [session-name]))
        inbox (.resolve root "inbox")
        outbox (.resolve root "outbox")]
    (Files/createDirectories inbox (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/createDirectories outbox (make-array java.nio.file.attribute.FileAttribute 0))
    {:root root :inbox inbox :outbox outbox}))

(defn unique-path
  ^Path
  [^Path dir ^String filename]
  (let [dot (.lastIndexOf filename ".")
        base (if (neg? dot) filename (subs filename 0 dot))
        ext (if (neg? dot) "" (subs filename dot))]
    (loop [idx 0]
      (let [candidate (if (zero? idx) filename (str base "-" idx ext))
            p (.resolve dir candidate)]
        (if (Files/exists p (make-array LinkOption 0))
          (recur (inc idx))
          p)))))

(defn write-upload!
  [^Path dir filename ^java.io.File tempfile]
  (let [dest (unique-path dir filename)]
    (Files/move (.toPath tempfile) dest (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
    (.getFileName dest)))

(defn write-bytes!
  [^Path dir filename ^bytes bytes]
  (let [dest (unique-path dir filename)]
    (Files/write dest bytes (into-array java.nio.file.OpenOption [StandardOpenOption/CREATE_NEW
                                                                 StandardOpenOption/WRITE]))
    (.getFileName dest)))

(defn list-files
  [^Path dir]
  (let [ds (Files/newDirectoryStream dir)]
    (try
      (->> ds
           (.iterator)
           (iterator-seq)
           (map (fn [^Path p]
                  (when (Files/isRegularFile p (make-array LinkOption 0))
                    (let [st (Files/readAttributes p "basic:*" (make-array LinkOption 0))
                          size (.size st)
                          ^FileTime ft (.lastModifiedTime st)
                          mtime-ms (.toMillis ft)]
                      {:name (str (.getFileName p))
                       :size_bytes size
                       :mtime_ms mtime-ms}))))
           (remove nil?)
           (sort-by :mtime_ms >)
           (vec))
      (finally
        (.close ds)))))

(defn resolve-outbox-path
  ^Path
  [^String lab-dir ^String session-name ^String name]
  (when-let [n (some-> name str str/trim)]
    (when (and (not (str/blank? n))
               (not (#{ "." ".."} n))
               (not (str/includes? n "/"))
               (not (str/includes? n "\\"))
               (<= (count n) 260))
      (let [{:keys [outbox]} (ensure-dirs! lab-dir session-name)
            candidate (.normalize (.resolve outbox n))]
        (when (.startsWith candidate outbox)
          candidate)))))
