(ns darelwasl.knowledge.adapters
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [darelwasl.knowledge.http :as http]
            [darelwasl.knowledge.util :as kutil])
  (:import (java.net URI)))

(defn- parse-sitemap-urls
  [xml]
  (when (seq xml)
    (->> (re-seq #"<loc>([^<]+)</loc>" xml)
         (map second)
         (remove str/blank?)
         vec)))

(defn- sitemap-index?
  [xml]
  (boolean (re-find #"<sitemapindex" (or xml ""))))

(defn- fetch-sitemap
  [url]
  (let [{:keys [status body]} (http/request-text url {:timeout-ms 8000})]
    (when (and status (<= 200 status 299))
      body)))

(defn discover-sitemap
  [base-url]
  (let [root (str (str/replace base-url #"/+$" "") "/sitemap.xml")
        xml (fetch-sitemap root)]
    (when (seq xml)
      (if (sitemap-index? xml)
        (->> (parse-sitemap-urls xml)
             (mapcat (fn [loc]
                       (let [child (fetch-sitemap loc)]
                         (parse-sitemap-urls child))))
             vec)
        (parse-sitemap-urls xml)))))

(defn- normalize-url-list
  [urls]
  (->> urls
       (keep (fn [u]
               (when-let [{:keys [normalized]} (kutil/normalize-url u)]
                 normalized)))
       distinct
       vec))

(defn discover-zatca
  []
  (normalize-url-list (discover-sitemap "https://zatca.gov.sa/")))

(defn discover-gstc
  []
  (let [sitemap (discover-sitemap "https://gstc.gov.sa/")
        library-paths ["https://gstc.gov.sa/en/Pages/default.aspx"
                       "https://gstc.gov.sa/ar/Pages/default.aspx"]]
    (normalize-url-list (concat sitemap library-paths))))

(defn- parse-json-urls
  [body]
  (try
    (let [data (json/read-str body)
          urls (atom [])]
      (letfn [(walk [v]
                (cond
                  (map? v) (doseq [[k val] v]
                             (when (and (string? val)
                                        (re-find #"https?://" val))
                               (swap! urls conj val))
                             (walk val))
                  (sequential? v) (doseq [item v] (walk item))
                  :else nil))]
        (walk data))
      @urls)
    (catch Exception _ [])))

(defn discover-ncar-api
  []
  (let [endpoint "https://ncar.gov.sa/"
        {:keys [status body]} (http/request-text endpoint {:timeout-ms 8000})
        api-links (when (and status (<= 200 status 299))
                    (->> (re-seq #"https?://[^\"']+/api/[^\"]+" (or body ""))
                         (map first)))]
    (normalize-url-list (concat api-links (parse-json-urls body)))))

(defn discover-ncar-repo
  []
  (normalize-url-list (discover-sitemap "https://ncar.gov.sa/")))

(defn discover-umm-al-qura
  []
  (normalize-url-list (discover-sitemap "https://uqn.gov.sa/")))

(defn discover-boe
  []
  (normalize-url-list (discover-sitemap "https://laws.boe.gov.sa/")))

(def adapters
  [{:id :adapter/zatca
    :source "ZATCA"
    :base-url "https://zatca.gov.sa/"
    :discover discover-zatca}
   {:id :adapter/gstc
    :source "GSTC"
    :base-url "https://gstc.gov.sa/"
    :discover discover-gstc}
   {:id :adapter/ncar-api
    :source "NCAR API"
    :base-url "https://ncar.gov.sa/api/"
    :discover discover-ncar-api}
   {:id :adapter/ncar-repo
    :source "NCAR Repository"
    :base-url "https://ncar.gov.sa/"
    :discover discover-ncar-repo}
   {:id :adapter/umm-al-qura
    :source "Umm Al-Qura"
    :base-url "https://uqn.gov.sa/"
    :discover discover-umm-al-qura}
   {:id :adapter/boe
    :source "BOE"
    :base-url "https://laws.boe.gov.sa/"
    :discover discover-boe}
   {:id :adapter/sama
    :source "SAMA Rulebook"
    :base-url "https://rulebook.sama.gov.sa/"
    :discover #(normalize-url-list (discover-sitemap "https://rulebook.sama.gov.sa/"))}
   {:id :adapter/cma
    :source "CMA"
    :base-url "https://cma.org.sa/"
    :discover #(normalize-url-list (discover-sitemap "https://cma.org.sa/"))}
   {:id :adapter/misa
    :source "MISA"
    :base-url "https://misa.gov.sa/"
    :discover #(normalize-url-list (discover-sitemap "https://misa.gov.sa/"))}
   {:id :adapter/mc
    :source "Ministry of Commerce"
    :base-url "https://mc.gov.sa/"
    :discover #(normalize-url-list (discover-sitemap "https://mc.gov.sa/"))}
   {:id :adapter/business-center
    :source "Saudi Business Center"
    :base-url "https://business.gov.sa/"
    :discover #(normalize-url-list (discover-sitemap "https://business.gov.sa/"))}
   {:id :adapter/hrsd
    :source "HRSD"
    :base-url "https://hrsd.gov.sa/"
    :discover #(normalize-url-list (discover-sitemap "https://hrsd.gov.sa/"))}
   {:id :adapter/sdaia
    :source "SDAIA"
    :base-url "https://sdaia.gov.sa/"
    :discover #(normalize-url-list (discover-sitemap "https://sdaia.gov.sa/"))}
   {:id :adapter/nca
    :source "NCA"
    :base-url "https://nca.gov.sa/"
    :discover #(normalize-url-list (discover-sitemap "https://nca.gov.sa/"))}
   {:id :adapter/mof
    :source "Ministry of Finance"
    :base-url "https://mof.gov.sa/"
    :discover #(normalize-url-list (discover-sitemap "https://mof.gov.sa/"))}
   {:id :adapter/etimad
    :source "Etimad"
    :base-url "https://etimad.sa/"
    :discover #(normalize-url-list (discover-sitemap "https://etimad.sa/"))}
   {:id :adapter/cst
    :source "CST"
    :base-url "https://cst.gov.sa/"
    :discover #(normalize-url-list (discover-sitemap "https://cst.gov.sa/"))}
   {:id :adapter/sfda
    :source "SFDA"
    :base-url "https://www.sfda.gov.sa/"
    :discover #(normalize-url-list (discover-sitemap "https://www.sfda.gov.sa/"))}
   {:id :adapter/saso
    :source "SASO"
    :base-url "https://saso.gov.sa/"
    :discover #(normalize-url-list (discover-sitemap "https://saso.gov.sa/"))}
   {:id :adapter/mewa
    :source "MEWA"
    :base-url "https://www.mewa.gov.sa/"
    :discover #(normalize-url-list (discover-sitemap "https://www.mewa.gov.sa/"))}
   {:id :adapter/saip
    :source "SAIP"
    :base-url "https://saip.gov.sa/"
    :discover #(normalize-url-list (discover-sitemap "https://saip.gov.sa/"))}
   {:id :adapter/moenergy
    :source "Ministry of Energy"
    :base-url "https://www.moenergy.gov.sa/"
    :discover #(normalize-url-list (discover-sitemap "https://www.moenergy.gov.sa/"))}
   {:id :adapter/gcc
    :source "GCC Secretariat General"
    :base-url "https://www.gcc-sg.org/"
    :discover #(normalize-url-list (discover-sitemap "https://www.gcc-sg.org/"))}
   {:id :adapter/oecd
    :source "OECD"
    :base-url "https://www.oecd.org/"
    :discover #(normalize-url-list (discover-sitemap "https://www.oecd.org/"))}
   {:id :adapter/data-gov
    :source "Saudi Open Data Portal"
    :base-url "https://data.gov.sa/"
    :discover #(normalize-url-list (discover-sitemap "https://data.gov.sa/"))}
   {:id :adapter/api-gov
    :source "Saudi API Inventory"
    :base-url "https://api.gov.sa/"
    :discover #(normalize-url-list (discover-sitemap "https://api.gov.sa/"))}])
