;; Account statement PDF generation + file library persistence.
(ns darelwasl.account-statement
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [darelwasl.files :as files])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time LocalDate ZoneId)
           (java.time.format DateTimeFormatter)
           (java.util Date UUID)))

(def ^:private iso-date (DateTimeFormatter/ofPattern "yyyy-MM-dd"))
(def ^:private pretty-date (DateTimeFormatter/ofPattern "d MMMM yyyy"))

(defn- now-local-date
  []
  (LocalDate/now (ZoneId/systemDefault)))

(defn- present-str?
  [v]
  (and (string? v) (not (str/blank? v))))

(defn- normalize-text
  [v]
  (let [s (some-> v str str/trim)]
    (when-not (str/blank? s) s)))

(defn- parse-date
  "Accept either yyyy-MM-dd or a freeform string (stored as-is if not parseable)."
  [v]
  (let [s (normalize-text v)]
    (when s
      (try
        (LocalDate/parse s iso-date)
        (catch Exception _
          nil)))))

(defn- normalize-money
  [v]
  (cond
    (number? v) v
    (string? v) (let [s (str/trim v)
                      s (str/replace s #"[^0-9.+-]" "")]
                  (when-not (str/blank? s)
                    (try
                      (Double/parseDouble s)
                      (catch Exception _ nil))))
    :else nil))

(defn- temp-dir!
  []
  (-> (Files/createTempDirectory "account-statement-" (make-array FileAttribute 0))
      (.toFile)))

(defn- write-json!
  [path data]
  (with-open [w (io/writer path)]
    (.write w (json/write-str data))))

(defn- render-pdf!
  [payload]
  (let [dir (temp-dir!)
        input-path (.getPath (io/file dir "input.json"))
        out-path (.getPath (io/file dir "account-statement.pdf"))]
    (write-json! input-path payload)
    (let [res (sh/sh "node" "scripts/account-statement-pdf.js"
                     "--input" input-path
                     "--out" out-path)]
      (when-not (zero? (:exit res))
        (throw (ex-info "Account statement PDF render failed"
                        {:exit (:exit res)
                         :out (:out res)
                         :err (:err res)})))
    {:dir dir
     :pdf-file (io/file out-path)})))

(defn generate!
  "Generate and store an account statement PDF in the file library.

  Input keys (all are optional unless noted):
  - :statement/company-name (required)
  - :statement/date (default today)
  - :statement/client-name (required)
  - :statement/currency (default \"SAR\")
  - :statement/total-contract-amount
  - :statement/total-amount-received
  - :statement/outstanding-balance (default computed when possible)
  - :statement/payments (vector of maps with :date :description :amount :mode :status)
  - :statement/remarks
  - :statement/slug (optional file slug)
  Returns {:file <presented-file>} or {:error ...}."
  [state {:keys [input actor]}]
  (let [body (or input {})
        company (normalize-text (or (:statement/company-name body) (:company-name body) (:companyName body)))
        client (normalize-text (or (:statement/client-name body) (:client-name body) (:clientName body)))
        currency (normalize-text (or (:statement/currency body) (:currency body))) 
        date-raw (or (:statement/date body) (:date body))
        date-parsed (parse-date date-raw)
        date-text (or (some-> date-parsed (.format pretty-date))
                      (normalize-text date-raw)
                      (-> (now-local-date) (.format pretty-date)))
        total-contract (normalize-money (or (:statement/total-contract-amount body) (:total-contract-amount body) (:totalContractAmount body)))
        total-received (normalize-money (or (:statement/total-amount-received body) (:total-amount-received body) (:totalAmountReceived body)))
        outstanding (normalize-money (or (:statement/outstanding-balance body) (:outstanding-balance body) (:outstandingBalance body)))
        outstanding (or outstanding (when (and (number? total-contract) (number? total-received))
                                      (- total-contract total-received)))
        payments (or (:statement/payments body) (:payments body) [])
        payments (if (sequential? payments) (vec payments) [])
        remarks (normalize-text (or (:statement/remarks body) (:remarks body)))
        slug (normalize-text (or (:statement/slug body) (:file/slug body) (:slug body)))
        statement-id (str (UUID/randomUUID))
        payload {:title "ACCOUNT STATEMENT"
                 :companyName company
                 :date date-text
                 :clientName client
                 :currency (or currency "SAR")
                 :totalContractAmount total-contract
                 :totalAmountReceived total-received
                 :outstandingBalance outstanding
                 :payments (mapv (fn [p]
                                   {:date (normalize-text (or (:date p) (:payment/date p)))
                                    :description (normalize-text (or (:description p) (:desc p) (:payment/description p)))
                                    :amount (normalize-money (or (:amount p) (:payment/amount p)))
                                    :mode (normalize-text (or (:mode p) (:payment/mode p)))
                                    :status (normalize-text (or (:status p) (:payment/status p)))})
                                 payments)
                 :remarks remarks
                 :statementId (subs statement-id 0 8)}
        storage-dir (get-in state [:config :files :storage-dir])]
    (cond
      (not (present-str? company))
      {:error {:status 400 :message "statement/company-name is required"}}

      (not (present-str? client))
      {:error {:status 400 :message "statement/client-name is required"}}

      :else
      (let [{:keys [dir pdf-file]} (render-pdf! payload)
            filename (str "account-statement-" (str/replace (str/lower-case client) #"[^a-z0-9]+" "-") "-" (-> (now-local-date) (.format iso-date)) ".pdf")
            upload {:filename filename
                    :content-type "application/pdf"
                    :tempfile pdf-file
                    :size (.length ^java.io.File pdf-file)}]
        (try
          (files/create-file! (get-in state [:db :conn])
                              {:file upload
                               :slug slug}
                              actor
                              storage-dir)
          (finally
            (try
              (doseq [entry (reverse (file-seq dir))]
                (when (.isFile entry)
                  (.delete entry)))
              (.delete dir)
              (catch Exception _))))))))
