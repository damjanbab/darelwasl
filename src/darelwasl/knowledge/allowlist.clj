(ns darelwasl.knowledge.allowlist)

(def sources
  [{:id :source/umm-al-qura
    :name "Umm Al-Qura (Official Gazette)"
    :base-url "https://uqn.gov.sa/"}
   {:id :source/boe
    :name "BOE Laws Portal"
    :base-url "https://laws.boe.gov.sa/"}
   {:id :source/ncar
    :name "NCAR Repository"
    :base-url "https://ncar.gov.sa/"}
   {:id :source/ncar-api
    :name "NCAR API"
    :base-url "https://ncar.gov.sa/api/"}
   {:id :source/zatca
    :name "ZATCA"
    :base-url "https://zatca.gov.sa/"}
   {:id :source/gstc
    :name "GSTC"
    :base-url "https://gstc.gov.sa/"}
   {:id :source/mc
    :name "Ministry of Commerce"
    :base-url "https://mc.gov.sa/"}
   {:id :source/business-center
    :name "Saudi Business Center"
    :base-url "https://business.gov.sa/"}
   {:id :source/misa
    :name "MISA"
    :base-url "https://misa.gov.sa/"}
   {:id :source/hrsd
    :name "HRSD"
    :base-url "https://hrsd.gov.sa/"}
   {:id :source/sama-rulebook
    :name "SAMA Rulebook"
    :base-url "https://rulebook.sama.gov.sa/"}
   {:id :source/cma
    :name "CMA"
    :base-url "https://cma.org.sa/"}
   {:id :source/sdaia
    :name "SDAIA"
    :base-url "https://sdaia.gov.sa/"}
   {:id :source/nca
    :name "NCA"
    :base-url "https://nca.gov.sa/"}
   {:id :source/mof
    :name "Ministry of Finance"
    :base-url "https://mof.gov.sa/"}
   {:id :source/etimad
    :name "Etimad"
    :base-url "https://etimad.sa/"}
   {:id :source/cst
    :name "CST"
    :base-url "https://cst.gov.sa/"}
   {:id :source/sfda
    :name "SFDA"
    :base-url "https://www.sfda.gov.sa/"}
   {:id :source/saso
    :name "SASO"
    :base-url "https://saso.gov.sa/"}
   {:id :source/mewa
    :name "MEWA"
    :base-url "https://www.mewa.gov.sa/"}
   {:id :source/saip
    :name "SAIP"
    :base-url "https://saip.gov.sa/"}
   {:id :source/moenergy
    :name "Ministry of Energy"
    :base-url "https://www.moenergy.gov.sa/"}
   {:id :source/gcc
    :name "GCC Secretariat General"
    :base-url "https://www.gcc-sg.org/"}
   {:id :source/oecd
    :name "OECD"
    :base-url "https://www.oecd.org/"}
   {:id :source/data-gov
    :name "Saudi Open Data Portal"
    :base-url "https://data.gov.sa/"}
   {:id :source/api-gov
    :name "Saudi API Inventory"
    :base-url "https://api.gov.sa/"}])

(def allowlist-hosts
  (->> sources
       (map (fn [src]
              (let [url (java.net.URI. (:base-url src))]
                (.getHost url))))
       set))

(defn allowlisted-host?
  [host]
  (contains? allowlist-hosts host))

(defn allowlisted-url?
  [^java.net.URI uri]
  (when uri
    (allowlisted-host? (.getHost uri))))

(defn source-by-host
  [host]
  (some #(when (= host (-> (java.net.URI. (:base-url %)) .getHost)) %) sources))
