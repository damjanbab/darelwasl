(ns darelwasl.knowledge.classify
  (:require [clojure.string :as str]))

(def doc-type-rank
  {:doc.type/law 90
   :doc.type/implementing-regulation 80
   :doc.type/treaty 75
   :doc.type/regulatory-rule 70
   :doc.type/guidance 60
   :doc.type/decision 50
   :doc.type/procedure 40
   :doc.type/dataset 30
   :doc.type/api 30
   :doc.type/draft 10
   :doc.type/unknown 0})

(def host-topics
  {"zatca.gov.sa" #{:topic/tax :topic/zakat :topic/customs}
   "gstc.gov.sa" #{:topic/disputes}
   "mc.gov.sa" #{:topic/corporate}
   "business.gov.sa" #{:topic/business-services}
   "misa.gov.sa" #{:topic/investment}
   "hrsd.gov.sa" #{:topic/labor}
   "rulebook.sama.gov.sa" #{:topic/finance :topic/banking}
   "cma.org.sa" #{:topic/capital-markets}
   "sdaia.gov.sa" #{:topic/privacy :topic/data}
   "nca.gov.sa" #{:topic/cybersecurity}
   "mof.gov.sa" #{:topic/procurement :topic/finance}
   "etimad.sa" #{:topic/procurement}
   "cst.gov.sa" #{:topic/telecom}
   "www.sfda.gov.sa" #{:topic/health :topic/food-drug}
   "saso.gov.sa" #{:topic/standards}
   "www.mewa.gov.sa" #{:topic/environment}
   "saip.gov.sa" #{:topic/ip}
   "www.moenergy.gov.sa" #{:topic/energy}
   "uqn.gov.sa" #{:topic/official-gazette}
   "laws.boe.gov.sa" #{:topic/laws}
   "data.gov.sa" #{:topic/open-data}
   "api.gov.sa" #{:topic/open-data}})

(defn- contains-any?
  [s patterns]
  (when (seq s)
    (some #(re-find % s) patterns)))

(defn topics-for
  [{:keys [host title]}]
  (let [host-topics (get host-topics host #{})
        title (some-> title str/lower-case)
        inferred (cond-> #{}
                   (contains-any? title [#"tax" #"vat" #"zakat" #"ضريبة" #"زكاة"]) (conj :topic/tax)
                   (contains-any? title [#"labor" #"employment" #"عمل" #"عمال"]) (conj :topic/labor)
                   (contains-any? title [#"privacy" #"data protection" #"البيانات"]) (conj :topic/privacy)
                   (contains-any? title [#"cyber" #"security" #"الأمن السيبراني"]) (conj :topic/cybersecurity)
                   (contains-any? title [#"procurement" #"tenders" #"المشتريات" #"المنافسات"]) (conj :topic/procurement)
                   (contains-any? title [#"bank" #"finance" #"insurance" #"مالية"]) (conj :topic/finance)
                   (contains-any? title [#"capital market" #"securities" #"الأوراق المالية"]) (conj :topic/capital-markets))]
    (vec (distinct (concat host-topics inferred)))))

(defn infer-doc-type
  [{:keys [host url title]}]
  (let [title (some-> title str/lower-case)
        url (some-> url str/lower-case)
        host (some-> host str/lower-case)]
    (cond
      (contains-any? host [#"data\.gov\.sa" #"api\.gov\.sa"]) (if (contains-any? host [#"api\.gov\.sa"]) :doc.type/api :doc.type/dataset)
      (contains-any? url [#"/api/" #"api\.gov\.sa"]) :doc.type/api
      (contains-any? url [#"/dataset" #"/data" #"data\.gov\.sa"]) :doc.type/dataset
      (contains-any? title [#"draft" #"مسودة"]) :doc.type/draft
      (contains-any? title [#"executive regulation" #"implementing regulation" #"اللائحة التنفيذية"]) :doc.type/implementing-regulation
      (contains-any? title [#"regulation" #"rulebook" #"rules" #"لائحة" #"نظام"])
      (if (contains-any? title [#"نظام" #"law" #"act"]) :doc.type/law :doc.type/regulatory-rule)
      (contains-any? title [#"guidance" #"manual" #"faq" #"guide" #"إرشادات" #"دليل"]) :doc.type/guidance
      (contains-any? title [#"decision" #"قرار" #"الحكم" #"رأي" #"ruling"]) :doc.type/decision
      (contains-any? title [#"procedure" #"procedural" #"إجراءات"]) :doc.type/procedure
      (contains-any? title [#"treaty" #"agreement" #"اتفاقية"]) :doc.type/treaty
      :else :doc.type/unknown)))

(defn authority-rank
  [doc-type]
  (get doc-type-rank doc-type 0))
