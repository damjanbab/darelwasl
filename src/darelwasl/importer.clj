(ns darelwasl.importer
  "Land registry importer: reads the HRIB parcel ownership CSV, normalizes
  people/parcels, and transacts person/parcel/ownership entities into Datomic
  with deterministic IDs."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.config :as config]
            [darelwasl.db :as db]
            [darelwasl.schema :as schema])
  (:import (java.nio.charset StandardCharsets)
           (java.util UUID)))

(def default-csv-path "data/land/hrib_parcele_upisane_osobe.csv")

(defn- assoc-some
  [m k v]
  (if (some? v) (assoc m k v) m))

(defn- name-uuid ^UUID [s]
  (UUID/nameUUIDFromBytes (.getBytes (str s) StandardCharsets/UTF_8)))

(defn- normalize-text [s]
  (-> (or s "")
      str/trim
      (str/replace #"[\\.,]" "")
      (str/replace #"\s+" " ")
      str/upper-case))

(defn- parse-long-safe [s]
  (when-let [t (some-> s str/trim not-empty)]
    (Long/parseLong t)))

(defn- parse-double-safe [s]
  (when-let [t (some-> s str/trim not-empty)]
    (Double/parseDouble t)))

(defn- read-rows [path]
  (let [file (io/file path)]
    (when-not (.exists file)
      (throw (ex-info (str "CSV file not found: " (.getAbsolutePath file))
                      {:path (.getAbsolutePath file)})))
    (with-open [r (io/reader file)]
      (let [rows (csv/read-csv r)
            header (first rows)
            data (map #(zipmap header %) (rest rows))]
        (when (empty? data)
          (throw (ex-info "CSV file is empty" {:path (.getAbsolutePath file)})))
        (vec data)))))

(defn- row->entities
  [{:keys [persons parcels ownerships share-sums]} row]
  (let [person-name (get row "upisana_osoba_ime_prezime_naziv")
        person-address (get row "upisana_osoba_adresa")
        person-name-norm (normalize-text person-name)
        person-address-norm (normalize-text person-address)
        person-key (str person-name-norm "|" person-address-norm)
        person-id (name-uuid person-key)
        person (or (persons person-key)
                   {:person/id person-id
                    :person/name person-name
                    :person/name-normalized person-name-norm
                    :person/address person-address
                    :person/address-normalized person-address-norm
                    :person/source-ref (str (name-uuid (str "person:" person-key)))
                    :entity/type :entity.type/person})

        cadastral-id (str/trim (get row "katastarska_opcina_id"))
        cadastral-name (str/trim (get row "katastarska_opcina"))
        parcel-number (str/trim (get row "k_c_br"))
        parcel-key (str cadastral-id "|" parcel-number)
        parcel-id (name-uuid (str "parcel:" parcel-key))
        parcel-area (parse-double-safe (get row "povrsina_m2"))
        parcel (or (parcels parcel-key)
                   {:parcel/id parcel-id
                    :parcel/cadastral-id cadastral-id
                    :parcel/cadastral-name cadastral-name
                    :parcel/number parcel-number
                    :parcel/book-number (str/trim (get row "broj_posjedovnog_lista"))
                    :parcel/address (str/trim (get row "adresa_parcele"))
                    :parcel/area-m2 parcel-area
                    :parcel/source-ref (str (name-uuid (str "parcel:" parcel-key)))
                    :entity/type :entity.type/parcel})

        num (or (parse-long-safe (get row "suvlasnicki_dio_brojnik")) 0)
        den (or (parse-long-safe (get row "suvlasnicki_dio_nazivnik")) 1)
        _ (when (<= den 0)
            (throw (ex-info "Invalid ownership denominator" {:row row})))
        share (/ (double num) (double den))
        list-order (parse-long-safe (get row "upisana_osoba_redni_broj"))
        position (parse-long-safe (get row "upisana_osoba_pozicija_u_listi"))
        ownership-id (name-uuid (str "owner:" person-key "|" parcel-key "|" num "|" den "|" list-order "|" position))
        area-share (when (and parcel-area (> parcel-area 0)) (* parcel-area share))
        ownership (-> {:ownership/id ownership-id
                       :ownership/person [:person/id person-id]
                       :ownership/parcel [:parcel/id parcel-id]
                       :ownership/share-numerator num
                       :ownership/share-denominator den
                       :ownership/share share
                       :ownership/source-raw (get row "suvlasnicki_dio_raw")
                       :ownership/source-ref (str (name-uuid (str "own:" (pr-str row))))
                       :entity/type :entity.type/ownership}
                      (assoc-some :ownership/area-share-m2 area-share)
                      (assoc-some :ownership/list-order list-order)
                      (assoc-some :ownership/position-in-list position))
        share-sum (+ share (get share-sums parcel-key 0.0))]
    {:persons (assoc persons person-key person)
     :parcels (assoc parcels parcel-key parcel)
     :ownerships (conj ownerships ownership)
     :share-sums (assoc share-sums parcel-key share-sum)}))

(defn- prepare-import [rows]
  (let [{:keys [persons parcels ownerships share-sums]} (reduce row->entities {:persons {} :parcels {} :ownerships [] :share-sums {}} rows)
        tolerance 1e-6
        incomplete (->> share-sums
                        (remove (fn [[_ sum]] (< (Math/abs (- sum 1.0)) tolerance)))
                        seq)]
    (when incomplete
      (throw (ex-info "Parcel share totals do not sum to 1" {:parcels incomplete})))
    {:persons (vals persons)
     :parcels (vals parcels)
     :ownerships ownerships
     :stats {:persons (count persons)
             :parcels (count parcels)
             :ownerships (count ownerships)}}))

(defn import-data!
  [{:keys [conn dry-run? file]}]
  (let [rows (read-rows file)
        {:keys [persons parcels ownerships stats]} (prepare-import rows)
        tx-persons-parcels (concat persons parcels)
        start (System/nanoTime)]
    (log/infof "Prepared import (persons=%s parcels=%s ownerships=%s) from %s"
               (:persons stats) (:parcels stats) (:ownerships stats) file)
    (when-not dry-run?
      (log/info "Transacting persons/parcels...")
      (db/transact! conn {:tx-data tx-persons-parcels})
      (log/info "Transacting ownerships...")
      (db/transact! conn {:tx-data ownerships})
      (let [dur-ms (/ (double (- (System/nanoTime) start)) 1e6)]
        (log/infof "Import transaction complete dur=%.1fms" dur-ms)))
    (merge stats {:status :ok
                  :duration-ms (/ (double (- (System/nanoTime) start)) 1e6)})))

(defn run-import
  "Entry point for the importer. Options:
  {:file path :temp? bool :dry-run? bool}
  Uses dev-local config by default; :temp? spins a :mem DB that is cleaned up."
  [{:keys [file temp? dry-run?] :or {file default-csv-path temp? false dry-run? false}}]
  (let [cfg (config/load-config)
        datomic-cfg (:datomic cfg)
        state (if temp?
                (schema/temp-db-with-schema! (assoc datomic-cfg :storage-dir :mem))
                (let [state (db/connect! datomic-cfg)]
                  (if-let [err (:error state)]
                    state
                    (merge state (schema/load-schema! (:conn state))))))]
    (try
      (if-let [err (:error state)]
        err
        (import-data! {:conn (:conn state)
                       :file file
                       :dry-run? dry-run?}))
      (finally
        (when temp?
          (db/delete-database! state))))))

(defn -main
  "Run importer from CLI.
  Usage: clojure -M:import [--file path] [--temp] [--dry-run]
  --temp runs against a temp in-memory DB (default false)
  --dry-run parses/validates only"
  [& args]
  (let [arg-set (set args)
        temp? (contains? arg-set "--temp")
        dry-run? (contains? arg-set "--dry-run")
        file-idx (.indexOf args "--file")
        file (if (neg? file-idx)
               default-csv-path
               (nth args (inc file-idx) default-csv-path))
        result (run-import {:file file :temp? temp? :dry-run? dry-run?})]
    (if (:status result)
      (do
        (println "Import ready" (select-keys result [:persons :parcels :ownerships]) (if dry-run? "(dry-run)" ""))
        (shutdown-agents)
        (System/exit 0))
      (do
        (println "Import failed" result)
        (shutdown-agents)
        (System/exit 1)))))
