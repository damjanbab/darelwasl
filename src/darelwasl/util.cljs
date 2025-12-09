(ns darelwasl.util
  (:require [clojure.string :as str]))

(defn distinct-by
  [f coll]
  (loop [seen #{}
         xs coll
         out []]
    (if-let [item (first xs)]
      (let [k (f item)]
        (if (contains? seen k)
          (recur seen (rest xs) out)
          (recur (conj seen k) (rest xs) (conj out item))))
      out)))

(defn clean-tag-name [s]
  (-> s (or "") str/trim))

(defn tag-slug [s]
  (-> s clean-tag-name str/lower-case))

(defn find-tag-by-name
  [tags name]
  (let [needle (tag-slug name)]
    (some #(when (= needle (tag-slug (:tag/name %))) %) tags)))

(defn remove-tag-from-tasks
  [tasks tag-id]
  (mapv (fn [task]
          (update task :task/tags
                  (fn [tags]
                    (->> tags
                         (remove #(= (:tag/id %) tag-id))
                         vec))))
        tasks))

(defn rename-tag-in-tasks
  [tasks tag-id new-name]
  (mapv (fn [task]
          (update task :task/tags
                  (fn [tags]
                    (mapv (fn [t]
                            (if (= (:tag/id t) tag-id)
                              (assoc t :tag/name new-name)
                              t))
                          tags))))
        tasks))

(defn normalize-tag-list [tags]
  (->> tags
       (keep (fn [t]
               (when (and (:tag/id t) (:tag/name t))
                 {:tag/id (:tag/id t)
                  :tag/name (clean-tag-name (:tag/name t))})))
       (sort-by (comp tag-slug :tag/name))
       vec))

(defn format-date
  [iso-str]
  (when (and iso-str (not (str/blank? iso-str)))
    (let [d (js/Date. iso-str)]
      (.toLocaleDateString d "en-US" #js {:month "short" :day "numeric"}))))

(defn status-label [s] (get {:todo "To do" :in-progress "In progress" :done "Done"} s "Unknown"))
(defn priority-label [p] (get {:high "High" :medium "Medium" :low "Low"} p "Unknown"))

(defn truncate
  [s n]
  (if (and s (> (count s) n))
    (str (subs s 0 n) "…")
    s))

(defn pct
  [share]
  (when (some? share)
    (str (js/Math.round (* 100 share)) "%")))

(defn format-area
  [m2]
  (let [n (or m2 0)]
    (-> n js/Math.round (.toLocaleString "en-US"))))

(defn parcel-title
  [{:parcel/keys [cadastral-id number cadastral-name address id]}]
  (or (when (or cadastral-id number)
        (str (or cadastral-id "–") "/" (or number "–")))
      cadastral-name
      address
      (when id (str id))
      "Parcel"))

(defn person-title
  [{:person/keys [name id]}]
  (or name (when id (str id)) "Owner"))

(defn iso->input-date
  [iso-str]
  (when (and iso-str (not (str/blank? iso-str)))
    (subs (.toISOString (js/Date. iso-str)) 0 10)))

(defn input-date->iso
  [date-str]
  (when (and date-str (not (str/blank? date-str)))
    (.toISOString (js/Date. (str date-str "T00:00:00Z")))))

(defn ->keyword-or-nil [v]
  (let [trimmed (str/trim (or v ""))]
    (when-not (str/blank? trimmed)
      (keyword trimmed))))
