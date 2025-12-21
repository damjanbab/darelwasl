(ns darelwasl.site.http
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.content :as content]
            [darelwasl.site.templates :as templates]
            [ring.util.codec :as codec]
            [ring.util.response :as resp]))

(defn handle-request
  [{:keys [db]} request]
  (let [start (System/nanoTime)
        conn (:conn db)
        raw-path (:uri request)
        query (codec/form-decode (or (:query-string request) ""))
        clean-path (if (and (not= raw-path "/") (str/ends-with? raw-path "/"))
                     (subs raw-path 0 (dec (count raw-path)))
                     raw-path)]
    (if (or (str/starts-with? clean-path "/css/")
            (= clean-path "/logo.jpg"))
      (let [static-resp (resp/file-response (subs clean-path 1) {:root "public"})]
        (if static-resp
          (if (str/starts-with? clean-path "/css/")
            (resp/content-type static-resp "text/css")
            static-resp)
          (templates/render-not-found clean-path "")))
      (let [data (content/list-content-v2 conn)
        {:keys [error licenses comparison-rows journey-phases activation-steps personas support-entries hero-stats hero-flows faqs values team-members businesses contacts]} data
       nav-items [{:path "/" :label "Home"}
                   {:path "/services" :label "Services"}
                   {:path "/comparison" :label "Comparison"}
                   {:path "/process" :label "Process"}
                   {:path "/about" :label "About"}
                   {:path "/contact" :label "Contact"}
                   {:path "/contact" :label "Schedule a meeting" :cta? true}]
        nav (templates/nav-links nav-items clean-path)
        response (cond
                   error
                   {:status 500
                    :headers {"Content-Type" "text/plain; charset=utf-8"}
                    :body (str "Content unavailable: " (:message error "unexpected error"))}

                   :else
                   (let [visible-licenses (->> licenses (filter #(not= false (:license/visible? %))) (sort-by #(or (:license/order %) Long/MAX_VALUE)))
                         visible-personas (->> personas (filter #(not= false (:persona/visible? %))) (sort-by #(or (:persona/order %) Long/MAX_VALUE)))
                         visible-faqs (->> faqs (filter #(not= false (:faq/visible? %))) (sort-by #(or (:faq/order %) Long/MAX_VALUE)))
                         sorted-comparison (sort-by #(or (:comparison.row/order %) Long/MAX_VALUE) comparison-rows)
                         sorted-phases (sort-by #(or (:journey.phase/order %) Long/MAX_VALUE) journey-phases)
                         sorted-activation (sort-by #(or (:activation.step/order %) Long/MAX_VALUE) activation-steps)
                         sorted-support (sort-by #(or (:support.entry/order %) Long/MAX_VALUE) support-entries)
                         sorted-values (sort-by #(or (:value/order %) Long/MAX_VALUE) values)
                         sorted-team (sort-by #(or (:team.member/order %) Long/MAX_VALUE) team-members)
                         stat-index (into {} (map (fn [s] [(:hero.stat/id s) s]) hero-stats))
                         flow-index (into {} (map (fn [f] [(:hero.flow/id f) f]) hero-flows))
                         contact-index (into {} (map (fn [c] [(:contact/id c) c]) contacts))
                         business (or (first (filter #(not= false (:business/visible? %)) businesses))
                                      (first businesses)
                                      {})
                         selected-contact (or (some-> business :business/contact (templates/ref-id :contact/id) contact-index)
                                              (first contacts)
                                              {})
                         linked-stats (->> (:business/hero-stats business)
                                           (map #(get stat-index (templates/ref-id % :hero.stat/id)))
                                           (remove nil?)
                                           (sort-by #(or (:hero.stat/order %) Long/MAX_VALUE)))
                         linked-flows (->> (:business/hero-flows business)
                                           (map #(get flow-index (templates/ref-id % :hero.flow/id)))
                                           (remove nil?)
                                           (sort-by #(or (:hero.flow/order %) Long/MAX_VALUE)))
                       nav (templates/nav-links nav-items clean-path)
                       footer-cta (templates/render-footer-cta business selected-contact)]
                     (case clean-path
                       "/"
                       (templates/html-response (str (or (:business/name business) "Dar Alwasl") " - Home")
                                      nav
                                      (apply str (remove nil?
                                                         [(templates/render-hero business linked-stats linked-flows)
                                                          (templates/render-funnel :select)
                                                          (templates/render-trust-strip linked-stats sorted-comparison)
                                                          (templates/render-offer-overview visible-licenses)
                                                          (templates/render-how-it-works linked-flows)
                                                          (templates/render-path-selector-teaser visible-licenses)
                                                          (templates/render-faqs (take 3 visible-faqs))]))
                                      footer-cta)

                       "/services"
                       (templates/html-response (str (or (:business/name business) "Dar Alwasl") " - Services")
                                      nav
                                      (apply str (remove nil?
                                                         [(templates/render-hero-light "Licensing and activation services" "Select the license that fits, compare requirements, and book a call.")
                                                          (templates/render-funnel :select)
                                                          (templates/render-license-tabs visible-licenses
                                                                               (case (get query "type")
                                                                                 "entrepreneur" :license.type/entrepreneur
                                                                                 "gcc" :license.type/gcc
                                                                                 "general" :license.type/general
                                                                                 nil))
                                                          (templates/render-outcomes sorted-values)
                                                          (templates/render-faqs visible-faqs)]))
                                      footer-cta)

                       "/comparison"
                       (templates/html-response (str (or (:business/name business) "Dar Alwasl") " - Comparison")
                                      nav
                                      (apply str (remove nil?
                                                         [(templates/render-hero-light "Compare license paths" "Side-by-side details across processing, cost, ownership, and required documents.")
                                                          (templates/render-funnel :compare)
                                                          (templates/render-comparison sorted-comparison)]))
                                      footer-cta)

                       "/process"
                       (templates/html-response (str (or (:business/name business) "Dar Alwasl") " - Process")
                                      nav
                                      (apply str (remove nil?
                                                         [(templates/render-hero-light "Process and activation" "Understand the phases, inputs, and outputs for going live in KSA.")
                                                          (templates/render-journey sorted-phases sorted-activation)]))
                                      footer-cta)

                       "/about"
                       (templates/html-response (str (or (:business/name business) "Dar Alwasl") " - About")
                                      nav
                                      (apply str (remove nil?
                                                         [(templates/render-hero-light "About Dar Alwasl" "Principles and operating model for calm, evidence-led execution.")
                                                          (templates/render-about-overview business)
                                                          (templates/render-values-team sorted-values sorted-team)]))
                                      footer-cta)

                      "/contact"
                      (templates/html-response (str (or (:business/name business) "Dar Alwasl") " - Contact")
                                      nav
                                      (apply str [(templates/render-hero-light "Talk to the team" "Schedule a meeting or email us with your activities and timing.")
                                                  (templates/render-funnel :schedule)
                                                  (templates/render-contact business selected-contact)])
                                      footer-cta)

                       (templates/render-not-found clean-path nav))))
        dur-ms (/ (double (- (System/nanoTime) start)) 1e6)]
    (log/infof "site request path=%s status=%s dur=%.1fms content={licenses %s, journey %s, personas %s, faqs %s}"
               clean-path
               (:status response)
               dur-ms
               (count licenses)
               (count journey-phases)
               (count personas)
               (count faqs))
    response))))

(defn app
  "Ring handler for the public site process."
  [state]
  (fn [request]
    (handle-request state request)))
