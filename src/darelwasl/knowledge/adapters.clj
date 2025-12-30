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
  (let [{:keys [status body error error-type attempts dns]} (http/request-text url {:timeout-ms 8000})]
    (if (and status (<= 200 status 299))
      {:status :ok :body body :url url}
      {:status :error
       :url url
       :error (or error "Request failed")
       :error-type error-type
       :attempts attempts
       :dns dns})))

(defn- www-base-url
  [base-url]
  (try
    (let [uri (URI. base-url)
          scheme (or (.getScheme uri) "https")
          host (.getHost uri)
          path (or (.getPath uri) "")]
      (when (and host (not (str/starts-with? host "www.")))
        (str (URI. scheme (str "www." host) (if (str/blank? path) "/" path) nil nil))))
    (catch Exception _ nil)))

(defn discover-sitemap
  [base-url]
  (let [root (str (str/replace base-url #"/+$" "") "/sitemap.xml")
        root-result (fetch-sitemap root)
        root-result (if (= :ok (:status root-result))
                      root-result
                      (if-let [www (www-base-url base-url)]
                        (fetch-sitemap (str (str/replace www #"/+$" "") "/sitemap.xml"))
                        root-result))
        xml (:body root-result)]
    (if-not (seq xml)
      {:urls []
       :error (when (= :error (:status root-result))
                (select-keys root-result [:url :error :error-type :attempts :dns]))}
      (if (sitemap-index? xml)
        (let [children (parse-sitemap-urls xml)
              child-results (map fetch-sitemap children)
              urls (mapcat (fn [{:keys [status body]}]
                             (when (= status :ok)
                               (parse-sitemap-urls body)))
                           child-results)
              errors (->> child-results
                          (filter #(= :error (:status %)))
                          (map #(select-keys % [:url :error :error-type :attempts :dns]))
                          vec)]
          {:urls (vec urls)
           :errors (seq errors)})
        {:urls (parse-sitemap-urls xml)}))))

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
  (let [{:keys [urls error errors]} (discover-sitemap "https://zatca.gov.sa/")]
    {:urls (normalize-url-list urls)
     :error error
     :errors errors}))

(defn discover-gstc
  []
  (let [{:keys [urls error errors]} (discover-sitemap "https://gstc.gov.sa/")
        library-paths ["https://gstc.gov.sa/en/Pages/default.aspx"
                       "https://gstc.gov.sa/ar/Pages/default.aspx"]]
    {:urls (normalize-url-list (concat urls library-paths))
     :error error
     :errors errors}))

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
        {:keys [status body error error-type attempts dns]} (http/request-text endpoint {:timeout-ms 8000})
        api-links (when (and status (<= 200 status 299))
                    (->> (re-seq #"https?://[^\"']+/api/[^\"]+" (or body ""))
                         (map first)))
        urls (normalize-url-list (concat api-links (parse-json-urls body)))]
    (if (seq urls)
      {:urls urls}
      {:urls []
       :error (when-not (and status (<= 200 status 299))
                {:url endpoint
                 :error (or error "Request failed")
                 :error-type error-type
                 :attempts attempts
                 :dns dns})})))

(defn discover-ncar-repo
  []
  (let [{:keys [urls error errors]} (discover-sitemap "https://ncar.gov.sa/")]
    {:urls (normalize-url-list urls)
     :error error
     :errors errors}))

(defn discover-umm-al-qura
  []
  (let [{:keys [urls error errors]} (discover-sitemap "https://uqn.gov.sa/")]
    {:urls (normalize-url-list urls)
     :error error
     :errors errors}))

(defn discover-boe
  []
  (let [{:keys [urls error errors]} (discover-sitemap "https://laws.boe.gov.sa/")]
    {:urls (normalize-url-list urls)
     :error error
     :errors errors}))

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
    :discover discover-boe
    :mirrors [:adapter/ncar-repo]}
   {:id :adapter/sama
   :source "SAMA Rulebook"
   :base-url "https://rulebook.sama.gov.sa/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://rulebook.sama.gov.sa/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/cma
   :source "CMA"
   :base-url "https://cma.org.sa/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://cma.org.sa/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/misa
   :source "MISA"
   :base-url "https://misa.gov.sa/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://misa.gov.sa/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/mc
   :source "Ministry of Commerce"
   :base-url "https://mc.gov.sa/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://mc.gov.sa/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/business-center
   :source "Saudi Business Center"
   :base-url "https://business.gov.sa/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://business.gov.sa/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/hrsd
   :source "HRSD"
   :base-url "https://hrsd.gov.sa/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://hrsd.gov.sa/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/sdaia
   :source "SDAIA"
   :base-url "https://sdaia.gov.sa/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://sdaia.gov.sa/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/nca
   :source "NCA"
   :base-url "https://nca.gov.sa/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://nca.gov.sa/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/mof
   :source "Ministry of Finance"
   :base-url "https://mof.gov.sa/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://mof.gov.sa/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/etimad
   :source "Etimad"
   :base-url "https://etimad.sa/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://etimad.sa/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/cst
   :source "CST"
   :base-url "https://cst.gov.sa/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://cst.gov.sa/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/sfda
   :source "SFDA"
   :base-url "https://www.sfda.gov.sa/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://www.sfda.gov.sa/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/saso
   :source "SASO"
   :base-url "https://saso.gov.sa/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://saso.gov.sa/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/mewa
   :source "MEWA"
   :base-url "https://www.mewa.gov.sa/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://www.mewa.gov.sa/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/saip
   :source "SAIP"
   :base-url "https://saip.gov.sa/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://saip.gov.sa/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/moenergy
   :source "Ministry of Energy"
   :base-url "https://www.moenergy.gov.sa/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://www.moenergy.gov.sa/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/gcc
   :source "GCC Secretariat General"
   :base-url "https://www.gcc-sg.org/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://www.gcc-sg.org/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/oecd
   :source "OECD"
   :base-url "https://www.oecd.org/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://www.oecd.org/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/data-gov
   :source "Saudi Open Data Portal"
   :base-url "https://data.gov.sa/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://data.gov.sa/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}
   {:id :adapter/api-gov
   :source "Saudi API Inventory"
   :base-url "https://api.gov.sa/"
    :discover #(let [{:keys [urls error errors]} (discover-sitemap "https://api.gov.sa/")]
                 {:urls (normalize-url-list urls)
                  :error error
                  :errors errors})}])
