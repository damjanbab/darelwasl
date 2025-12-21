(ns darelwasl.site.templates
  (:require [clojure.string :as str]))

(defn- escape-html [s]
  (let [text (str (or s ""))]
    (str/escape text {\& "&amp;"
                      \< "&lt;"
                      \> "&gt;"
                      \" "&quot;"
                      \' "&#x27;"})))

(defn- evidence-pill [label]
  (format "<span class=\"evidence-pill\">%s</span>" (escape-html label)))

(defn ref-id
  [entry kw]
  (cond
    (map? entry) (get entry kw)
    (vector? entry) (second entry)
    :else entry))

(defn nav-links
  [links current-path]
  (->> links
       (map (fn [{:keys [path label cta?]}]
              (let [active (= path current-path)]
                (format "<a href=\"%s\" class=\"nav-link %s %s\" aria-current=\"%s\">%s</a>"
                        (escape-html path)
                        (if active "active" "")
                        (when cta? "primary-cta")
                        (if active "page" "false")
                        (escape-html label)))))
       (apply str)))

(defn layout
  [title nav body footer-cta]
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>" (escape-html title) "</title>"
       "<link rel=\"stylesheet\" href=\"/css/theme.css\">"
       "<link rel=\"stylesheet\" href=\"/css/main.css\">"
       "<link rel=\"stylesheet\" href=\"/css/site.css\">"
       "<script>function toggleMenu(){document.body.classList.toggle('mobile-open');var btn=document.getElementById('mobile-toggle');if(btn){var open=document.body.classList.contains('mobile-open');btn.setAttribute('aria-expanded',open);if(!open){btn.focus();}}}</script>"
       "</head>"
       "<body data-theme=\"site-premium\">"
       "<header class=\"site-header\"><div class=\"shell\"><div class=\"nav-bar\">"
       "<div class=\"brand\" aria-label=\"Dar Alwasl\"><img src=\"/logo.jpg\" alt=\"Dar Alwasl logo\" loading=\"lazy\"></div>"
       "<nav class=\"nav-links\" aria-label=\"Primary\">" nav "</nav>"
       "<button id=\"mobile-toggle\" class=\"mobile-toggle\" type=\"button\" aria-expanded=\"false\" aria-controls=\"mobile-menu\" onclick=\"toggleMenu()\">Menu</button>"
       "</div>"
       "<nav id=\"mobile-menu\" class=\"mobile-menu\" aria-label=\"Mobile\">" nav "</nav>"
       "</div></header>"
       "<main>" body "</main>"
       (or footer-cta "")
       "<footer class=\"site-footer\"><div class=\"footer-content\"><div>(c) Dar Alwasl - Public site</div><div><a class=\"nav-link\" href=\"/contact\">Contact</a><a class=\"nav-link\" href=\"/about\">About</a></div></div></footer>"
       "</body></html>"))

(defn- render-list
  [items]
  (when (seq items)
    (format "<ul>%s</ul>"
            (apply str (map (fn [item]
                              (format "<li>%s</li>" (escape-html item)))
                            items)))))

(defn render-hero
  [business hero-stats hero-flows]
  (let [headline (or (:business/hero-headline business)
                     (:business/name business)
                     "Business setup in Saudi Arabia")
        strapline (or (:business/hero-strapline business)
                      (:business/tagline business)
                      (:business/summary business)
                      "Clear, sequenced paths for licensing and activation.")
        nav-label (or (:business/nav-label business) "Market entry partners")
        stats-view (when (seq hero-stats)
                     (format "<div class=\"stats\">%s</div>"
                             (apply str
                                    (map (fn [s]
                                           (format "<div class=\"stat-card\"><div class=\"label\">%s</div><div class=\"value\">%s</div><div class=\"hint\">%s</div></div>"
                                                   (escape-html (:hero.stat/label s))
                                                   (escape-html (:hero.stat/value s))
                                                   (escape-html (:hero.stat/hint s))))
                                         hero-stats))))
        flows-view (when (seq hero-flows)
                     (format "<div class=\"steps\">%s</div>"
                             (apply str
                                    (map-indexed (fn [idx f]
                                                   (format "<div class=\"step\"><div class=\"step-index\">%s</div><div><div class=\"label\">%s</div><div class=\"muted\">%s</div></div></div>"
                                                           (inc idx)
                                                           (escape-html (:hero.flow/title f))
                                                           (escape-html (:hero.flow/detail f))))
                                                hero-flows))))]
    (format "<section class=\"hero\"><p class=\"pill\">%s</p><h1 class=\"headline\">%s</h1><p class=\"strapline\">%s</p>%s<div class=\"meta\">Gate review: leave with your next step + required inputs.</div>%s</section>"
            (escape-html nav-label)
            (escape-html headline)
            (escape-html strapline)
            (or stats-view "")
            (or flows-view ""))))

(defn render-hero-light
  [title subtitle]
  (format "<section class=\"hero-light\"><h1>%s</h1><p>%s</p></section>"
          (escape-html title)
          (escape-html subtitle)))

(defn render-funnel
  [current]
  (let [steps [{:id :select :label "1 Select" :href "/services"}
               {:id :compare :label "2 Compare" :href "/comparison"}
               {:id :schedule :label "3 Schedule" :href "/contact"}]]
    (str "<div class=\"funnel\">"
         (str/join "<span class=\"arrow\">&rarr;</span>"
                   (map (fn [{:keys [id label href]}]
                          (format "<span class=\"step %s\"><a href=\"%s\">%s</a></span>"
                                  (when (= id current) "active")
                                  (escape-html href)
                                  (escape-html label)))
                        steps))
         "</div>")))

(defn render-how-it-works
  [hero-flows]
  (let [steps (->> hero-flows
                   (sort-by #(or (:hero.flow/order %) Long/MAX_VALUE))
                   (take 3)
                   (map-indexed (fn [idx flow]
                                  (format "<div class=\"step\"><div class=\"step-index\">%s</div><div><strong>%s</strong><div class=\"muted\">%s</div></div></div>"
                                          (inc idx)
                                          (escape-html (:hero.flow/title flow))
                                          (escape-html (:hero.flow/detail flow))))))
        rail (str "<div class=\"step-rail\">"
                  (str/join "" (for [idx (range (count steps))]
                                 (format "<div class=\"node %s\"></div>" (if (zero? idx) "active" ""))))
                  "</div>")]
    (when (seq steps)
      (format "<section><div class=\"section-title\"><h2>How it works</h2>%s</div>%s<div class=\"steps\">%s</div></section>"
              (evidence-pill "Process")
              rail
              (apply str steps)))))

(defn render-trust-strip
  [stats comparison-rows]
  (let [copy (or (some-> stats first :hero.stat/label)
                 "Trusted by founders moving fast in KSA")
        comparator (some-> comparison-rows first :comparison.row/criterion)]
    (format "<section><div class=\"section-title\"><h2>%s</h2><span class=\"pill\">%s</span></div><div class=\"meta\">%s</div></section>"
            (escape-html copy)
            "Evidence"
            (escape-html (or comparator "Private advisory; no cross-sell")))))

(defn render-path-selector-teaser
  [licenses]
  (let [entries (->> licenses
                     (sort-by #(or (:license/order %) Long/MAX_VALUE))
                     (take 3)
                     (map (fn [lic]
                            (format "<div class=\"card\"><div class=\"badge\">%s</div><h3>%s</h3><p>%s</p><ul>%s</ul></div>"
                                    (escape-html (name (:license/type lic)))
                                    (escape-html (:license/label lic))
                                    (escape-html (:license/processing-time lic))
                                    (render-list (:license/activities lic))))))]
    (when (seq entries)
      (format "<section><div class=\"section-title\"><h2>Choose a path</h2>%s</div><div class=\"meta\">Pick a path to see requirements in detail.</div><div class=\"card-grid\">%s</div></section>"
              (evidence-pill "Pathways")
              (apply str entries)))))

(defn render-offer-overview
  [licenses]
  (let [cards (->> licenses
                   (sort-by #(or (:license/order %) Long/MAX_VALUE))
                   (take 3)
                   (map (fn [lic]
                          (format "<div class=\"card\"><div class=\"badge\">%s</div><h3>%s</h3><div class=\"meta\">%s</div>%s</div>"
                                  (escape-html (name (:license/type lic)))
                                  (escape-html (:license/label lic))
                                  (escape-html (:license/processing-time lic))
                                  (render-list (:license/activities lic))))))]
    (when (seq cards)
      (format "<section><div class=\"section-title\"><h2>Offer overview</h2>%s</div><div class=\"card-grid\">%s</div></section>"
              (evidence-pill "3 tracks")
              (apply str cards)))))

(defn render-outcomes
  [values]
  (let [cards (->> values
                   (sort-by #(or (:value/order %) Long/MAX_VALUE))
                   (map (fn [v]
                          (format "<div class=\"value\"><h3>%s</h3><p>%s</p></div>"
                                  (escape-html (:value/title v))
                                  (escape-html (:value/copy v))))))]
    (when (seq cards)
      (format "<section><div class=\"section-title\"><h2>Outcomes you should expect</h2><span class=\"pill\">Results</span></div><div class=\"values\">%s</div></section>"
              (apply str cards)))))

(defn render-license-tabs
  [licenses selected-type]
  (let [ordered (sort-by #(or (:license/order %) Long/MAX_VALUE) licenses)
        selected (or (some #(when (= (:license/type %) selected-type) %) ordered)
                     (first ordered))
        tab-links (apply str
                         (map (fn [lic]
                                (let [active (= (:license/id lic) (:license/id selected))
                                      t (some-> lic :license/type name (str/replace "license.type/" ""))
                                      href (str "/services?type=" t)]
                                  (format "<a href=\"%s\" class=\"tab %s\">%s</a>"
                                          (escape-html href)
                                          (if active "active" "")
                                          (escape-html (:license/label lic)))))
                              ordered))
        details (when selected
                  (format "<div class=\"card\"><h3>%s</h3><div class=\"meta\">%s</div>%s%s%s</div>"
                          (escape-html (:license/label selected))
                          (escape-html (:license/processing-time selected))
                          (render-list (:license/pricing-lines selected))
                          (render-list (:license/document-checklist selected))
                          (render-list (:license/who selected))))]
    (when (seq ordered)
      (format "<section><div class=\"section-title\"><h2>License selector</h2>%s</div><div class=\"tabs\">%s</div>%s</section>"
              (evidence-pill "Compare")
              tab-links
              (or details "")))))

(defn render-proof
  [comparison-rows]
  (let [sample (some->> comparison-rows first :comparison.row/criterion)]
    (when sample
      (format "<section><div class=\"section-title\"><h2>Proof, not promises</h2><span class=\"pill\">Evidence</span></div><div class=\"meta\">Latest comparison focus: %s</div></section>"
              (escape-html sample)))))

(defn render-comparison
  [comparison-rows]
  (let [rows (->> comparison-rows
                  (sort-by #(or (:comparison.row/order %) Long/MAX_VALUE)))
        table-rows (apply str
                          (map (fn [row]
                                 (format "<tr><th>%s</th><td>%s</td><td class=\"recommended\">%s</td><td>%s</td></tr>"
                                         (escape-html (:comparison.row/criterion row))
                                         (escape-html (:comparison.row/general row))
                                         (escape-html (:comparison.row/entrepreneur row))
                                         (escape-html (:comparison.row/gcc row))))
                               rows))]
    (when (seq rows)
      (format "<section><div class=\"section-title\"><h2>Comparison table</h2><span class=\"pill\">Side-by-side</span></div><table class=\"table\"><thead><tr><th>Criteria</th><th>General</th><th>Entrepreneur</th><th>GCC</th></tr></thead><tbody>%s</tbody></table></section>"
              table-rows))))

(defn render-journey
  [journey-phases activation-steps]
  (let [phase-cards (apply str
                           (map (fn [phase]
                                  (format "<div class=\"phase\"><div class=\"badge\">%s</div><h3>%s</h3>%s</div>"
                                          (escape-html (name (:journey.phase/kind phase)))
                                          (escape-html (:journey.phase/title phase))
                                          (render-list (:journey.phase/bullets phase))))
                                journey-phases))
        activation (apply str
                          (map (fn [step]
                                 (format "<div class=\"card\"><div class=\"badge\">Activation</div><h3>%s</h3></div>"
                                         (escape-html (:activation.step/title step))))
                               activation-steps))]
    (format "<section><div class=\"section-title\"><h2>Journey phases</h2><span class=\"pill\">Timeline</span></div><div class=\"timeline\">%s</div></section><section><div class=\"section-title\"><h2>Activation steps</h2><span class=\"pill\">Checklist</span></div><div class=\"card-grid\">%s</div></section>"
            phase-cards
            activation)))

(defn render-faqs
  [faqs]
  (let [items (->> faqs
                   (sort-by #(or (:faq/order %) Long/MAX_VALUE))
                   (map (fn [f]
                          (format "<details><summary>%s</summary><p>%s</p></details>"
                                  (escape-html (:faq/question f))
                                  (escape-html (:faq/answer f))))))]
    (when (seq items)
      (format "<section class=\"faqs\"><div class=\"section-title\"><h2>FAQs</h2><span class=\"pill\">Answers</span></div><div class=\"stack\">%s</div></section>"
              (apply str items)))))

(defn render-about-overview
  [business]
  (let [mission (:business/mission business)
        vision (:business/vision business)]
    (when (or mission vision)
      (format "<section><div class=\"section-title\"><h2>Principles</h2><span class=\"pill\">Operating model</span></div><div class=\"stack\">%s%s</div></section>"
              (if mission
                (format "<div class=\"card\"><h3>Mission</h3><p>%s</p></div>" (escape-html mission))
                "")
              (if vision
                (format "<div class=\"card\"><h3>Vision</h3><p>%s</p></div>" (escape-html vision))
                "")))))

(defn render-values-team
  [values team-members]
  (let [values-view (when (seq values)
                      (format "<section><div class=\"section-title\"><h2>Values</h2><span class=\"pill\">Core</span></div><div class=\"values\">%s</div></section>"
                              (apply str
                                     (map (fn [v]
                                            (format "<div class=\"value\"><h3>%s</h3><p>%s</p></div>"
                                                    (escape-html (:value/title v))
                                                    (escape-html (:value/copy v))))
                                          values))))
        team-view (when (seq team-members)
                    (format "<section><div class=\"section-title\"><h2>Team</h2><span class=\"pill\">Leadership</span></div><div class=\"team\">%s</div></section>"
                            (apply str
                                   (map (fn [member]
                                          (format "<div class=\"member\"><h3>%s</h3><p>%s</p></div>"
                                                  (escape-html (:team.member/name member))
                                                  (escape-html (:team.member/title member))))
                                        team-members))))]
    (str (or values-view "") (or team-view ""))))

(defn render-contact
  [business contact]
  (let [summary (or (:business/summary business)
                    "Share your structure, activities, and timing to get a plotted roadmap.")
        email (:contact/email contact)
        phone (:contact/phone contact)
        primary-label (:contact/primary-cta-label contact)
        primary-url (:contact/primary-cta-url contact)
        secondary-label (:contact/secondary-cta-label contact)
        secondary-url (:contact/secondary-cta-url contact)
        inputs "<ul><li>Activities (1-3 lines)</li><li>Ownership (individual / parent company / GCC)</li><li>Timing target (weeks or month)</li><li>Documents status (ready / in progress)</li></ul>"]
    (format "<section class=\"contact\"><div><div class=\"section-title\"><h2>Schedule a meeting</h2><span class=\"pill\">REQUIREMENTS</span></div><p>%s</p><div class=\"meta\"><strong>Include:</strong>%s</div></div><div class=\"stack\"><div class=\"meta\">Email: %s</div><div class=\"meta\">Phone: %s</div><div class=\"ctas\">%s%s</div><div class=\"meta\">Gate review: leave with your next step + required inputs.</div></div></section>"
            (escape-html summary)
            inputs
            (escape-html email)
            (escape-html phone)
            (if primary-label
              (format "<a class=\"cta primary\" href=\"%s\">%s</a>"
                      (escape-html primary-url)
                      (escape-html primary-label))
              "")
            (if secondary-label
              (format "<a class=\"cta secondary\" href=\"%s\">%s</a>"
                      (escape-html secondary-url)
                      (escape-html secondary-label))
              ""))))

(defn render-footer-cta
  [business contact]
  (let [headline (or (:business/hero-headline business) "Ready to start your Saudi setup?")
        strapline (or (:business/tagline business) "Schedule a meeting to map your path.")
        primary-label (or (:contact/primary-cta-label contact) "Schedule a meeting")
        primary-url (or (:contact/primary-cta-url contact) "/contact")
        secondary-label (:contact/secondary-cta-label contact)
        secondary-url (:contact/secondary-cta-url contact)]
    (format "<div class=\"footer-cta\"><div class=\"inner\"><div><h3>%s</h3><p>%s</p></div><div class=\"actions\"><a class=\"cta primary\" href=\"%s\">%s</a>%s</div></div></div>"
            (escape-html headline)
            (escape-html strapline)
            (escape-html primary-url)
            (escape-html primary-label)
            (if secondary-label
              (format "<a class=\"cta secondary\" href=\"%s\">%s</a>"
                      (escape-html secondary-url)
                      (escape-html secondary-label))
              ""))))

(defn render-not-found
  [path nav]
  {:status 404
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (layout "Not found"
                 nav
                 (format "<h1>Page not found</h1><p>No content found at <strong>%s</strong>.</p>"
                         (escape-html path))
                 "")})

(defn html-response
  [title nav body footer-cta]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (layout title nav body footer-cta)})
