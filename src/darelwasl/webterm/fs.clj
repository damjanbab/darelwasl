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

(defn safe-segment
  "Returns a safe path segment (no slashes, no spaces) or nil."
  [s]
  (let [s (some-> s str str/trim)]
    (when (and (some? s)
               (not (str/blank? s))
               (re-matches #"(?i)^[a-z0-9][a-z0-9._-]{0,200}$" s))
      s)))

(defn ensure-dirs!
  [^String lab-dir ^String session-name]
  (let [root (Paths/get lab-dir (into-array String [session-name]))
        inbox (.resolve root "inbox")
        outbox (.resolve root "outbox")
        work-root (.resolve outbox "work")]
    (Files/createDirectories inbox (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/createDirectories outbox (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/createDirectories work-root (make-array java.nio.file.attribute.FileAttribute 0))
    {:root root :inbox inbox :outbox outbox :work-root work-root}))

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

(defn ensure-work-dir!
  ^Path
  [^String lab-dir ^String session-name ^String work-id]
  (when-let [wid (safe-segment work-id)]
    (let [{:keys [work-root]} (ensure-dirs! lab-dir session-name)
          dir (.resolve work-root wid)]
      (Files/createDirectories dir (make-array java.nio.file.attribute.FileAttribute 0))
      dir)))

(defn list-files
  [^Path dir]
  (let [ds (Files/newDirectoryStream dir)]
    (try
      (->> ds
           (.iterator)
           (iterator-seq)
           (map (fn [^Path p]
                  (when (Files/isRegularFile p (make-array LinkOption 0))
                    (let [^java.nio.file.attribute.BasicFileAttributes st (Files/readAttributes p java.nio.file.attribute.BasicFileAttributes (make-array LinkOption 0))
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

(defn list-recent-work-files
  "Returns recent files across all work dirs under outbox/work."
  [^Path work-root limit]
  (let [limit (-> (or limit 80) (max 1) (min 500))
        dirs (Files/newDirectoryStream work-root)]
    (try
      (->> dirs
           (.iterator)
           (iterator-seq)
           (map (fn [^Path d]
                  (when (Files/isDirectory d (make-array LinkOption 0))
                    (let [wid (str (.getFileName d))
                          files (list-files d)]
                      (for [f files]
                        (assoc f :work_id wid :ref (str "work:" wid "/" (:name f))))))))
           (remove nil?)
           (apply concat)
           (sort-by :mtime_ms >)
           (take limit)
           (vec))
      (finally (.close dirs)))))

(defn resolve-work-ref
  "Resolves a ref like 'work:<id>/<filename>' into a Path under outbox/work/<id>/.
  Returns nil when invalid."
  ^Path
  [^String lab-dir ^String session-name ^String ref]
  (let [raw (some-> ref str str/trim)]
    (when (and raw (not (str/blank? raw)))
      (let [raw (if (str/starts-with? raw "work:") (subs raw 5) raw)
            parts (->> (str/split raw #"/") (remove str/blank?) (vec))]
        (when (= 2 (count parts))
          (let [[wid name] parts
                wid (safe-segment wid)
                fname (safe-name name "file")]
            (when (and wid (not (str/blank? fname)))
              (let [{:keys [work-root]} (ensure-dirs! lab-dir session-name)
                    p (.normalize (.resolve (.resolve work-root wid) fname))]
                (when (.startsWith p (.resolve work-root wid))
                  p)))))))))

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
