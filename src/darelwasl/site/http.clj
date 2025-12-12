(ns darelwasl.site.http
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.content :as content]
            [ring.util.codec :as codec]
            [ring.util.response :as resp]))

(declare render-comparison escape-html)

(defn- evidence-pill [label]
  (format "<span class=\"evidence-pill\">%s</span>" (escape-html label)))

(defn- escape-html [s]
  (let [text (str (or s ""))]
    (str/escape text {\& "&amp;"
                      \< "&lt;"
                      \> "&gt;"
                      \" "&quot;"
                      \' "&#x27;"})))

(defn- ref-id
  [entry kw]
  (cond
    (map? entry) (get entry kw)
    (vector? entry) (second entry)
    :else entry))

(defn- nav-links
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

(defn- layout
  [title nav body footer-cta]
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>" (escape-html title) "</title>"
       "<link rel=\"stylesheet\" href=\"/css/theme.css\">"
       "<link rel=\"stylesheet\" href=\"/css/main.css\">"
       "<style>"
       "body{margin:0;background:var(--colors-background);color:var(--colors-text-primary);font-family:var(--typography-font-family);line-height:var(--typography-line-heights-body);}"
       "header.site-header{position:sticky;top:0;z-index:20;background:var(--colors-surface);border-bottom:1px solid var(--colors-border);backdrop-filter:blur(10px);} "
       ".shell{max-width:1160px;margin:0 auto;padding:0 var(--spacing-scale-4);} "
       ".nav-bar{display:flex;align-items:center;justify-content:space-between;gap:var(--spacing-scale-4);padding:var(--spacing-scale-4) 0;} "
       ".brand{font-weight:700;letter-spacing:-0.01em;font-size:var(--typography-font-sizes-section);color:var(--colors-text-primary);} "
       ".nav-links{display:flex;gap:var(--spacing-scale-3);align-items:center;} "
       ".nav-link{padding:12px 16px;border-radius:var(--radius-md);text-decoration:none;color:var(--colors-text-secondary);border:1px solid transparent;font-weight:600;transition:all var(--motion-transition);} "
       ".nav-link:hover{border-color:var(--colors-focus);color:var(--colors-text-primary);} "
       ".nav-link.active{color:var(--colors-text-primary);border-color:var(--colors-focus);} "
       ".nav-link.primary-cta{background:var(--colors-accent);color:var(--colors-text-on-accent);box-shadow:var(--shadows-card);} "
       ".nav-link.primary-cta:hover{transform:translateY(-1px);} "
       ".mobile-toggle{display:none;background:none;border:1px solid var(--colors-border);border-radius:var(--radius-md);padding:10px 12px;color:var(--colors-text-primary);} "
       ".mobile-menu{display:none;flex-direction:column;gap:var(--spacing-scale-2);padding:var(--spacing-scale-3) 0;} "
       ".mobile-open .mobile-menu{display:flex;} "
       ".mobile-open .nav-bar{border-bottom:1px solid var(--colors-border);} "
       "main{max-width:1160px;margin:0 auto;padding:var(--spacing-scale-8) var(--spacing-scale-4) var(--spacing-scale-9);display:flex;flex-direction:column;gap:var(--spacing-scale-7);} "
       "section{background:var(--colors-surface);border:1px solid var(--colors-border);border-radius:var(--radius-lg);padding:var(--spacing-scale-8) var(--spacing-scale-6);box-shadow:none;} "
       ".hero{width:100vw;margin-left:calc(50% - 50vw);margin-right:calc(50% - 50vw);min-height:70vh;display:grid;grid-template-columns:1.6fr 1fr;gap:var(--spacing-scale-6);align-items:center;padding:var(--spacing-scale-9) var(--spacing-scale-6);background:linear-gradient(135deg,var(--colors-surface-muted) 0%, var(--colors-accent-strong) 100%);color:var(--colors-text-on-accent);border:none;border-radius:0;box-shadow:none;} "
       ".hero .pill{background:rgba(255,255,255,0.12);color:var(--colors-text-on-accent);} "
       ".hero .headline{font-size:clamp(48px,5vw,56px);line-height:1.15;margin:0 0 var(--spacing-scale-2);} "
       ".hero .strapline{color:rgba(248,251,252,0.86);font-size:var(--typography-font-sizes-section);margin:0 0 var(--spacing-scale-3);} "
       ".hero .stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:var(--spacing-scale-3);} "
       ".stat-card{background:rgba(255,255,255,0.08);border:1px solid rgba(255,255,255,0.18);border-radius:var(--radius-md);padding:var(--spacing-scale-3);color:var(--colors-text-on-accent);box-shadow:none;} "
       ".stat-card .label{color:rgba(248,251,252,0.7);font-size:var(--typography-font-sizes-meta);text-transform:uppercase;letter-spacing:0.08em;margin-bottom:var(--spacing-scale-1);} "
       ".stat-card .value{font-size:clamp(24px,2.6vw,30px);font-weight:700;} "
       ".stat-card .hint{color:rgba(248,251,252,0.7);font-size:var(--typography-font-sizes-meta);} "
       ".hero .steps{display:flex;flex-direction:column;gap:var(--spacing-scale-3);} "
       ".step{display:flex;gap:var(--spacing-scale-2);align-items:flex-start;padding:var(--spacing-scale-2);border-radius:var(--radius-md);border:1px solid rgba(255,255,255,0.2);background:rgba(255,255,255,0.04);color:var(--colors-text-on-accent);} "
       ".step .step-index{width:36px;height:36px;border-radius:999px;background:var(--colors-accent);color:var(--colors-text-on-accent);display:flex;align-items:center;justify-content:center;font-weight:700;} "
       ".hero-light{background:var(--colors-surface);color:var(--colors-text-primary);border:1px solid var(--colors-border);border-radius:var(--radius-lg);padding:var(--spacing-scale-7) var(--spacing-scale-6);box-shadow:var(--shadows-card);} "
       ".hero-light h1{margin:0 0 var(--spacing-scale-2);font-size:clamp(36px,4vw,48px);line-height:1.2;} "
       ".hero-light p{margin:0;color:var(--colors-text-secondary);font-size:var(--typography-font-sizes-body);} "
       ".section-title{display:flex;align-items:center;justify-content:space-between;gap:var(--spacing-scale-3);margin:0 0 var(--spacing-scale-4);} "
       ".section-title h2{margin:0;font-size:var(--typography-font-sizes-section);line-height:var(--typography-line-heights-section);} "
       ".pill{display:inline-flex;align-items:center;padding:8px 12px;border-radius:999px;background:var(--colors-surface-muted);color:var(--colors-text-secondary);font-weight:600;font-size:var(--typography-font-sizes-meta);} "
       ".card-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:var(--spacing-scale-4);} "
       ".card{border:none;border-radius:var(--radius-lg);padding:var(--spacing-scale-5);background:var(--colors-surface);display:flex;flex-direction:column;gap:var(--spacing-scale-2);box-shadow:var(--shadows-card);} "
       ".card h3{margin:0;font-size:var(--typography-font-sizes-section);} "
       ".card ul{margin:0;padding-left:18px;color:var(--colors-text-secondary);} "
       ".meta{display:flex;flex-wrap:wrap;gap:var(--spacing-scale-2);font-size:var(--typography-font-sizes-meta);color:var(--colors-text-secondary);} "
       ".table{width:100%;border-collapse:collapse;} .table th,.table td{border:1px solid var(--colors-border);padding:14px;vertical-align:top;text-align:left;} "
       ".table th{background:var(--colors-background);font-weight:700;} "
       ".table .recommended{background:var(--colors-surface);font-weight:700;}"
       ".timeline{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:var(--spacing-scale-4);} "
       ".timeline .phase{border:1px solid var(--colors-border);border-radius:var(--radius-md);padding:var(--spacing-scale-4);background:var(--colors-surface);} "
       ".badge{display:inline-flex;align-items:center;gap:8px;padding:6px 10px;border-radius:10px;background:var(--colors-background);color:var(--colors-text-primary);font-size:var(--typography-font-sizes-meta);text-transform:uppercase;letter-spacing:0.06em;} "
       ".stack{display:flex;flex-direction:column;gap:var(--spacing-scale-3);} "
       ".list-inline{display:flex;flex-wrap:wrap;gap:var(--spacing-scale-2);padding:0;margin:var(--spacing-scale-2) 0 0;list-style:none;} "
       ".list-inline li{background:var(--colors-background);border-radius:var(--radius-md);padding:8px 12px;} "
       ".faqs details{border:1px solid var(--colors-border);border-radius:var(--radius-md);padding:var(--spacing-scale-3);background:var(--colors-surface);} "
       ".faqs summary{cursor:pointer;font-weight:700;font-size:var(--typography-font-sizes-body);} "
       ".values{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:var(--spacing-scale-4);} "
       ".values .value{border:1px solid var(--colors-border);border-radius:var(--radius-md);padding:var(--spacing-scale-4);background:var(--colors-surface);} "
       ".team{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:var(--spacing-scale-4);} "
       ".team .member{border:1px solid var(--colors-border);border-radius:var(--radius-md);padding:var(--spacing-scale-4);background:var(--colors-surface);} "
       ".contact{display:grid;grid-template-columns:2fr 1fr;gap:var(--spacing-scale-4);align-items:center;} "
       ".ctas{display:flex;gap:var(--spacing-scale-3);flex-wrap:wrap;} "
       ".cta{text-decoration:none;padding:12px 16px;border-radius:var(--radius-md);font-weight:700;border:1px solid var(--colors-border);} "
       ".cta.primary{background:var(--colors-accent);color:var(--colors-text-on-accent);box-shadow:var(--shadows-card);} "
       ".cta.secondary{background:var(--colors-surface);color:var(--colors-text-primary);} "
       ".tabs{display:flex;gap:var(--spacing-scale-2);margin-bottom:var(--spacing-scale-3);flex-wrap:wrap;} "
       ".tab{padding:10px 14px;border-radius:var(--radius-md);border:1px solid var(--colors-border);background:var(--colors-surface);color:var(--colors-text-secondary);cursor:pointer;text-decoration:none;font-weight:600;} "
       ".tab.active{background:var(--colors-accent);color:var(--colors-text-on-accent);border-color:var(--colors-accent);} "
       ".step-rail{display:flex;align-items:center;gap:10px;margin:var(--spacing-scale-3) 0;}"
       ".step-rail .node{width:14px;height:14px;border-radius:50%;border:2px solid var(--colors-border);background:var(--colors-surface);}"
       ".step-rail .node.active{background:var(--colors-accent);border-color:var(--colors-accent);} "
       ".step-rail .node a{display:block;width:100%;height:100%;border-radius:50%;}"
       ".funnel{display:flex;align-items:center;gap:var(--spacing-scale-2);margin:var(--spacing-scale-4) 0;}"
       ".funnel .step{display:flex;align-items:center;gap:8px;font-weight:700;color:var(--colors-text-secondary);}"
       ".funnel .step a{color:inherit;text-decoration:none;}"
       ".funnel .step.active{color:var(--colors-text-primary);}"
       ".funnel .arrow{color:var(--colors-text-secondary);}"
       ".evidence-pill{display:inline-flex;align-items:center;gap:8px;padding:6px 10px;border-radius:999px;background:var(--colors-background);color:var(--colors-text-secondary);font-size:var(--typography-font-sizes-meta);text-transform:uppercase;letter-spacing:0.08em;font-weight:700;} "
       ".footer-cta{width:100vw;margin-left:calc(50% - 50vw);margin-right:calc(50% - 50vw);background:var(--colors-surface-muted);padding:var(--spacing-scale-7) var(--spacing-scale-4);border-top:1px solid var(--colors-border);} "
       ".footer-cta .inner{max-width:1160px;margin:0 auto;display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:var(--spacing-scale-4);} "
       ".footer-cta h3{margin:0;font-size:var(--typography-font-sizes-section);line-height:var(--typography-line-heights-section);color:var(--colors-text-on-accent);} "
       ".footer-cta p{margin:0;color:var(--colors-text-on-accent);} "
       ".footer-cta .actions{display:flex;gap:var(--spacing-scale-3);flex-wrap:wrap;} "
       ".footer-cta .cta.primary{border:none;} "
       "@media (max-width:900px){.hero{grid-template-columns:1fr;min-height:auto;padding:var(--spacing-scale-7) var(--spacing-scale-4);}.contact{grid-template-columns:1fr;}} "
       "footer.site-footer{border-top:1px solid var(--colors-border);background:var(--colors-surface-muted);padding:var(--spacing-scale-5) 0;margin-top:var(--spacing-scale-7);color:var(--colors-text-on-accent);} "
       ".footer-content{max-width:1160px;margin:0 auto;padding:0 var(--spacing-scale-4);display:flex;flex-wrap:wrap;gap:var(--spacing-scale-4);align-items:center;justify-content:space-between;font-size:var(--typography-font-sizes-meta);} "
       ".footer-content a{color:var(--colors-text-on-accent);} "
       "@media (max-width:768px){.nav-links{display:none;}.mobile-toggle{display:block;}.mobile-menu a{display:block;} .nav-link{width:100%;}}"
       "</style>"
       "<script>function toggleMenu(){document.body.classList.toggle('mobile-open');var btn=document.getElementById('mobile-toggle');if(btn){var open=document.body.classList.contains('mobile-open');btn.setAttribute('aria-expanded',open);if(!open){btn.focus();}}}</script>"
       "</head>"
       "<body data-theme=\"site-premium\">"
       "<header class=\"site-header\"><div class=\"shell\"><div class=\"nav-bar\">"
       "<div class=\"brand\">Darel Wasl</div>"
       "<nav class=\"nav-links\" aria-label=\"Primary\">" nav "</nav>"
       "<button id=\"mobile-toggle\" class=\"mobile-toggle\" type=\"button\" aria-expanded=\"false\" aria-controls=\"mobile-menu\" onclick=\"toggleMenu()\">Menu</button>"
       "</div>"
       "<nav id=\"mobile-menu\" class=\"mobile-menu\" aria-label=\"Mobile\">" nav "</nav>"
       "</div></header>"
       "<main>" body "</main>"
       (or footer-cta "")
       "<footer class=\"site-footer\"><div class=\"footer-content\"><div>© Darel Wasl · Public site</div><div><a class=\"nav-link\" href=\"/contact\">Contact</a><a class=\"nav-link\" href=\"/about\">About</a></div></div></footer>"
       "</body></html>"))

(defn- render-hero
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
    (format "<section class=\"hero\"><p class=\"pill\">%s</p><h1 class=\"headline\">%s</h1><p class=\"strapline\">%s</p>%s%s</section>"
            (escape-html nav-label)
            (escape-html headline)
            (escape-html strapline)
            (or stats-view "")
            (or flows-view ""))))

(defn- render-hero-light
  [title subtitle]
  (format "<section class=\"hero-light\"><h1>%s</h1><p>%s</p></section>"
          (escape-html title)
          (escape-html subtitle)))

(defn- render-funnel
  [current]
  (let [steps [{:id :select :label "1 Select" :href "/services"}
               {:id :compare :label "2 Compare" :href "/comparison"}
               {:id :schedule :label "3 Schedule" :href "/contact"}]]
    (str "<div class=\"funnel\">"
         (str/join "<span class=\"arrow\">→</span>"
                   (map (fn [{:keys [id label href]}]
                          (format "<span class=\"step %s\"><a href=\"%s\">%s</a></span>"
                                  (when (= id current) "active")
                                  (escape-html href)
                                  (escape-html label)))
                        steps))
         "</div>")))

(defn- render-how-it-works
  [flows]
  (when (seq flows)
    (let [cards (->> flows
                     (sort-by #(or (:hero.flow/order %) Long/MAX_VALUE))
                     (take 5)
                     (map-indexed (fn [idx f]
                                    (format "<div class=\"card\"><div class=\"badge\">Step %s</div><h3>%s</h3><p>%s</p></div>"
                                            (inc idx)
                                            (escape-html (:hero.flow/title f))
                                            (escape-html (:hero.flow/detail f)))))
                     (apply str))]
      (format "<section><div class=\"section-title\"><div><h2>How it works</h2>%s</div><span class=\"pill\">Sequenced setup</span></div><div class=\"step-rail\"><span class=\"node active\"></span><span class=\"node\"></span><span class=\"node\"></span></div><div class=\"card-grid\">%s</div></section>"
              (evidence-pill "Typical timeline")
              cards))))

(defn- render-trust-strip
  [hero-stats comparison-rows]
  (let [stats (take 3 (sort-by #(or (:hero.stat/order %) Long/MAX_VALUE) hero-stats))
        claims (when (seq stats)
                 (apply str
                        (map (fn [s]
                               (format "<div class=\"card\"><div class=\"label\">%s</div><div class=\"value\">%s</div><div class=\"hint\">%s</div></div>"
                                       (escape-html (:hero.stat/label s))
                                       (escape-html (:hero.stat/value s))
                                       (escape-html (:hero.stat/hint s))))
                             stats)))
        comparison-note (when (seq comparison-rows)
                          "<div class=\"card\"><div class=\"label\">Transparent choices</div><div class=\"hint\">Side-by-side comparison of license paths with processing, ownership, and renewal details.</div></div>")]
    (when (or claims comparison-note)
      (format "<section class=\"trust-strip\"><div class=\"section-title\"><div><h2>Proof you can see</h2>%s</div><span class=\"pill\">Confidence builders</span></div><div class=\"card-grid\">%s%s</div></section>"
              (evidence-pill "Evidence-based")
              (or claims "")
              (or comparison-note "")))))

(defn- license-type-slug [t]
  (some-> t name (str/split #"/") last))

(defn- render-path-selector-teaser
  [licenses]
  (when (seq licenses)
    (let [cards (->> licenses
                     (sort-by #(or (:license/order %) Long/MAX_VALUE))
                     (take 3)
                     (map (fn [l]
                            (format "<div class=\"card\"><div class=\"badge\">%s</div><h3>%s</h3><p>%s</p><div class=\"meta\"><span>%s</span><span>%s</span></div></div>"
                                    (-> (:license/type l) license-type-slug str/capitalize)
                                    (escape-html (:license/label l))
                                    (escape-html (or (first (:license/activities l))
                                                     (first (:license/who l))
                                                     "Structured entry with compliance handled."))
                                    (escape-html (:license/processing-time l))
                                    (escape-html (:license/ownership l)))))
                     (apply str))]
      (format "<section><div class=\"section-title\"><div><h2>Choose a path</h2>%s</div><span class=\"pill\">Three clear options</span></div><div class=\"step-rail\"><span class=\"node\"></span><span class=\"node active\"></span><span class=\"node\"></span></div><div class=\"card-grid\">%s</div><div class=\"ctas\"><a class=\"cta secondary\" href=\"/comparison\">Compare details</a><a class=\"cta primary\" href=\"/services\">Explore services</a></div></section>"
              (evidence-pill "Recommendation")
              cards))))

(defn- render-list
  [items]
  (when (seq items)
    (format "<ul>%s</ul>"
            (apply str (map (fn [item] (format "<li>%s</li>" (escape-html item))) items)))))

(defn- render-licenses
  [licenses]
  (when (seq licenses)
    (let [cards (apply str
                       (map (fn [l]
                              (str
                               (format "<div class=\"card license\"><div class=\"section-title\"><h3>%s</h3><span class=\"pill\">%s</span></div>"
                                       (escape-html (:license/label l))
                                       (escape-html (name (:license/type l))))
                               (format "<div class=\"meta\"><span>Processing: %s</span><span>Ownership: %s</span><span>Renewal: %s</span></div>%s%s%s%s</div>"
                                       (escape-html (:license/processing-time l))
                                       (escape-html (:license/ownership l))
                                       (escape-html (:license/renewal-cost l))
                                       (if-let [pricing (:license/pricing-lines l)]
                                         (format "<div><strong>Pricing</strong>%s</div>"
                                                 (render-list pricing))
                                         "")
                                       (if-let [activities (:license/activities l)]
                                         (format "<div><strong>Core activities</strong>%s</div>"
                                                 (render-list activities))
                                         "")
                                       (if-let [who (:license/who l)]
                                         (format "<div><strong>Best for</strong>%s</div>"
                                                 (render-list who))
                                         "")
                                       (if-let [docs (:license/document-checklist l)]
                                         (format "<div><strong>Checklist</strong>%s</div>"
                                                 (render-list docs))
                                         ""))))
                            licenses))]
      (format "<section><div class=\"section-title\"><h2>License paths</h2><span class=\"pill\">Choose the route that fits</span></div><div class=\"card-grid\">%s</div></section>"
              cards))))

(defn- render-offer-overview
  [licenses]
  (when (seq licenses)
    (let [cards (apply str
                       (map (fn [l]
                              (format "<div class=\"card\"><h3>%s</h3><div class=\"meta\"><span>%s</span><span>%s</span></div><p>%s</p></div>"
                                      (escape-html (:license/label l))
                                      (escape-html (name (:license/type l)))
                                      (escape-html (:license/processing-time l))
                                      (escape-html (or (first (:license/activities l))
                                                       (first (:license/who l))
                                                       (or (:license/ownership l) "")))))
                            (take 3 licenses)))]
      (format "<section><div class=\"section-title\"><h2>What we offer</h2><span class=\"pill\">Specialized entry paths</span></div><div class=\"card-grid\">%s</div><div class=\"ctas\"><a class=\"nav-link primary-cta\" href=\"/services\">See all services</a></div></section>"
              cards))))

(defn- render-pillars
  [licenses]
  (when (seq licenses)
    (let [by-type (group-by :license/type licenses)
          pillars (apply str
                         (map (fn [[t ls]]
                                (let [items (apply str
                                                   (map (fn [l]
                                                          (format "<li>%s · %s</li>"
                                                                  (escape-html (:license/label l))
                                                                  (escape-html (:license/processing-time l))))
                                                        (take 4 ls)))]
                                  (format "<div class=\"card\"><h3>%s</h3><ul>%s</ul></div>"
                                          (escape-html (name t))
                                          items)))
                              by-type))]
      (format "<section><div class=\"section-title\"><h2>Services by pillar</h2><span class=\"pill\">Choose your path</span></div><div class=\"card-grid\">%s</div></section>"
              pillars))))

(defn- render-outcomes
  [values]
  (when (seq values)
    (let [cards (apply str
                       (map (fn [v]
                              (format "<div class=\"card\"><h3>%s</h3><p>%s</p></div>"
                                      (escape-html (:value/title v))
                                      (escape-html (:value/copy v))))
                            (take 4 values)))]
      (format "<section><div class=\"section-title\"><div><h2>Outcomes we drive</h2>%s</div><span class=\"pill\">What you get</span></div><div class=\"card-grid\">%s</div></section>"
              (evidence-pill "Impact")
              cards))))

(defn- render-license-tabs
  [licenses selected-type]
  (when (seq licenses)
    (let [ordered (sort-by #(or (:license/order %) Long/MAX_VALUE) licenses)
          selected (or (some #(when (= (:license/type %) selected-type) %) ordered)
                       (first ordered))
          types [:license.type/general :license.type/entrepreneur :license.type/gcc]
          tabs (->> types
                    (map (fn [t]
                           (when-let [lic (some #(when (= (:license/type %) t) %) ordered)]
                             (format "<a class=\"tab %s\" href=\"/services?type=%s\">%s</a>"
                                     (if (= (:license/type lic) (:license/type selected)) "active" "")
                                     (license-type-slug t)
                                     (escape-html (:license/label lic))))))
                    (remove nil?)
                    (apply str))
          detail (when selected
                   (let [pricing (render-list (:license/pricing-lines selected))
                         activities (render-list (:license/activities selected))
                         who (render-list (:license/who selected))
                         checklist (render-list (:license/document-checklist selected))]
                     (format "<div class=\"card\"><div class=\"section-title\"><h3>%s</h3><span class=\"pill\">%s</span></div><div class=\"meta\"><span>%s</span><span>%s</span><span>%s</span></div>%s%s%s%s<div class=\"ctas\"><a class=\"cta secondary\" href=\"/comparison\">Compare paths</a><a class=\"cta primary\" href=\"/contact\">Talk to an expert</a></div></div>"
                             (escape-html (:license/label selected))
                             (str (-> (:license/type selected) license-type-slug str/capitalize) " path")
                             (escape-html (:license/processing-time selected))
                             (escape-html (:license/ownership selected))
                             (escape-html (:license/renewal-cost selected))
                             (if pricing (str "<div><strong>Pricing</strong>" pricing "</div>") "")
                             (if activities (str "<div><strong>Activities</strong>" activities "</div>") "")
                             (if who (str "<div><strong>Best for</strong>" who "</div>") "")
                             (if checklist (str "<div><strong>Checklist</strong>" checklist "</div>") ""))))]
      (when (seq tabs)
        (format "<section><div class=\"section-title\"><div><h2>Select a license</h2>%s</div><span class=\"pill\">Guided recommendations</span></div><div class=\"tabs\">%s</div>%s</section>"
                (evidence-pill "Engineered flow")
                tabs
                (or detail ""))))))

(defn- render-proof
  [hero-stats comparison-rows]
  (or (render-trust-strip hero-stats comparison-rows)
      (render-comparison comparison-rows)))

(defn- render-proof-section
  [hero-stats comparison-rows]
  (when (or (seq hero-stats) (seq comparison-rows))
    (let [metrics (->> hero-stats
                       (sort-by #(or (:hero.stat/order %) Long/MAX_VALUE))
                       (take 3)
                       (map (fn [s]
                              (format "<div class=\"card\"><div class=\"label\">%s</div><div class=\"value\">%s</div><div class=\"hint\">%s</div></div>"
                                      (escape-html (:hero.stat/label s))
                                      (escape-html (:hero.stat/value s))
                                      (escape-html (:hero.stat/hint s)))))
                       (apply str))]
      (format "<section><div class=\"section-title\"><div><h2>Proof in practice</h2>%s</div><span class=\"pill\">Evidence</span></div><div class=\"card-grid\">%s%s</div></section>"
              (evidence-pill "Evidence-based")
              (or metrics "")
              (or (when (seq comparison-rows)
                    "<div class=\"card\"><h3>Side-by-side clarity</h3><p>We compare processing, ownership, and renewal across license paths so you can decide with confidence.</p><a class=\"cta secondary\" href=\"/comparison\">View comparison</a></div>")
                    "")))))

(defn- render-comparison
  [rows]
  (when (seq rows)
    (let [body (apply str
                      (map (fn [r]
                             (format "<tr><td>%s</td><td class=\"recommended\">%s</td><td>%s</td><td>%s</td></tr>"
                                     (escape-html (:comparison.row/criterion r))
                                     (escape-html (:comparison.row/entrepreneur r))
                                     (escape-html (:comparison.row/general r))
                                     (escape-html (:comparison.row/gcc r))))
                           rows))]
      (format "<section><div class=\"section-title\"><div><h2>Compare side by side</h2>%s</div><span class=\"pill\">Evidence-based decision</span></div><p>How to read this: pick the column that matches your ownership and capital reality; compare processing, cost, and requirements at a glance.</p><table class=\"table\"><thead><tr><th>Criterion</th><th class=\"recommended\">Entrepreneur</th><th>General Investment</th><th>GCC National</th></tr></thead><tbody>%s</tbody></table></section>"
              (evidence-pill "Proof")
              body))))

(defn- render-journey
  [phases activation-steps]
  (when (or (seq phases) (seq activation-steps))
    (let [phases-view (when (seq phases)
                        (format "<div class=\"timeline\">%s</div>"
                                (apply str
                                       (map (fn [p]
                                              (format "<div class=\"phase\"><div class=\"badge\">%s</div><h3>%s</h3>%s</div>"
                                                      (escape-html (name (:journey.phase/kind p)))
                                                      (escape-html (:journey.phase/title p))
                                                      (render-list (:journey.phase/bullets p))))
                                            phases))))
          activation-view (when (seq activation-steps)
                            (format "<div class=\"stack\"><div class=\"badge\">Activation</div><ul class=\"list-inline\">%s</ul></div>"
                                    (apply str
                                           (map (fn [s]
                                                  (format "<li>%s</li>" (escape-html (:activation.step/title s))))
                                                activation-steps))))]
      (format "<section><div class=\"section-title\"><h2>Journey and activation</h2><span class=\"pill\">Sequenced steps</span></div>%s%s</section>"
              (or phases-view "")
              (or activation-view "")))))

(defn- render-personas
  [personas support-entries]
  (when (or (seq personas) (seq support-entries))
    (let [personas-view (when (seq personas)
                          (let [cards (apply str
                                             (map (fn [p]
                                                    (format "<div class=\"card\"><h3>%s</h3><p>%s</p></div>"
                                                            (escape-html (:persona/title p))
                                                            (escape-html (:persona/detail p))))
                                                  personas))]
                            (format "<div class=\"card-grid\">%s</div>" cards)))
          by-role (group-by :support.entry/role support-entries)
          support-we (when-let [entries (seq (get by-role :support/we))]
                       (format "<div class=\"card\"><h3>%s</h3><ul>%s</ul></div>"
                               "We handle"
                               (apply str
                                      (map (fn [e]
                                             (format "<li>%s</li>" (escape-html (:support.entry/text e))))
                                           entries))))
          support-you (when-let [entries (seq (get by-role :support/you))]
                         (format "<div class=\"card\"><h3>%s</h3><ul>%s</ul></div>"
                                 "You handle"
                                 (apply str
                                        (map (fn [e]
                                               (format "<li>%s</li>" (escape-html (:support.entry/text e))))
                                             entries))))
          support-view (when (or support-we support-you)
                         (format "<div class=\"card-grid\">%s%s</div>" (or support-we "") (or support-you "")))]
      (format "<section><div class=\"section-title\"><h2>Who we guide</h2><span class=\"pill\">Tailored for foreign founders</span></div>%s%s</section>"
              (or personas-view "")
              (or support-view "")))))

(defn- render-faqs
  [faqs]
  (when (seq faqs)
    (format "<section class=\"faqs\"><div class=\"section-title\"><h2>FAQs</h2><span class=\"pill\">Upfront answers</span></div>%s</section>"
            (apply str
                   (map (fn [f]
                          (format "<details><summary>%s</summary><p>%s</p></details>"
                                  (escape-html (:faq/question f))
                                  (escape-html (:faq/answer f))))
                        faqs)))))

(defn- render-values-team
  [values team-members]
  (when (or (seq values) (seq team-members))
    (let [values-view (when (seq values)
                        (format "<div class=\"values\">%s</div>"
                                (apply str
                                       (map (fn [v]
                                              (format "<div class=\"value\"><h3>%s</h3><p>%s</p></div>"
                                      (escape-html (:value/title v))
                                      (escape-html (:value/copy v))))
                            values))))]
      (format "<section><div class=\"section-title\"><div><h2>How we operate</h2>%s</div><span class=\"pill\">Principles</span></div>%s</section>"
              (evidence-pill "Principles")
              (or values-view "")))))

(defn- render-contact
  [business contact]
  (let [summary (or (:business/summary business)
                    "Share your structure, activities, and timing to get a plotted roadmap.")
        email (:contact/email contact)
        phone (:contact/phone contact)
        primary-label (:contact/primary-cta-label contact)
        primary-url (:contact/primary-cta-url contact)
        secondary-label (:contact/secondary-cta-label contact)
        secondary-url (:contact/secondary-cta-url contact)]
    (format "<section class=\"contact\"><div><div class=\"section-title\"><h2>Talk to the team</h2><span class=\"pill\">Let's map your path</span></div><p>%s</p></div><div class=\"stack\"><div class=\"meta\">Email: %s</div><div class=\"meta\">Phone: %s</div><div class=\"ctas\">%s%s</div></div></section>"
            (escape-html summary)
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

(defn- render-footer-cta
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

(defn- render-not-found
  [path nav]
  {:status 404
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (layout "Not found"
                 nav
                 (format "<h1>Page not found</h1><p>No content found at <strong>%s</strong>.</p>"
                         (escape-html path))
                 "")})

(defn- html-response
  [title nav body footer-cta]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (layout title nav body footer-cta)})

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
            (= clean-path "/favicon.ico"))
      (let [static-resp (resp/file-response (subs clean-path 1) {:root "public"})]
        (if static-resp
          (if (str/starts-with? clean-path "/css/")
            (resp/content-type static-resp "text/css")
            static-resp)
          (render-not-found clean-path "")))
      (let [data (content/list-content-v2 conn)
        {:keys [error licenses comparison-rows journey-phases activation-steps personas support-entries hero-stats hero-flows faqs values team-members businesses contacts]} data
       nav-items [{:path "/" :label "Home"}
                   {:path "/services" :label "Services"}
                   {:path "/comparison" :label "Comparison"}
                   {:path "/process" :label "Process"}
                   {:path "/about" :label "About"}
                   {:path "/contact" :label "Contact"}
                   {:path "/contact" :label "Schedule a meeting" :cta? true}]
        nav (nav-links nav-items clean-path)
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
                         selected-contact (or (some-> business :business/contact (ref-id :contact/id) contact-index)
                                              (first contacts)
                                              {})
                         linked-stats (->> (:business/hero-stats business)
                                           (map #(get stat-index (ref-id % :hero.stat/id)))
                                           (remove nil?)
                                           (sort-by #(or (:hero.stat/order %) Long/MAX_VALUE)))
                         linked-flows (->> (:business/hero-flows business)
                                           (map #(get flow-index (ref-id % :hero.flow/id)))
                         (remove nil?)
                                          (sort-by #(or (:hero.flow/order %) Long/MAX_VALUE)))
                       nav (nav-links nav-items clean-path)
                       footer-cta (render-footer-cta business selected-contact)]
                     (case clean-path
                       "/"
                       (html-response (str (or (:business/name business) "Darel Wasl") " · Home")
                                      nav
                                      (apply str (remove nil?
                                                         [(render-hero business linked-stats linked-flows)
                                                          (render-funnel :select)
                                                          (render-trust-strip linked-stats sorted-comparison)
                                                          (render-offer-overview visible-licenses)
                                                          (render-how-it-works linked-flows)
                                                          (render-path-selector-teaser visible-licenses)
                                                          (render-proof-section linked-stats sorted-comparison)
                                                          (render-faqs (take 3 visible-faqs))]))
                                      footer-cta)

                       "/services"
                       (html-response (str (or (:business/name business) "Darel Wasl") " · Services")
                                      nav
                                      (apply str (remove nil?
                                                         [(render-hero-light "Licensing and activation services" "Select the license that fits, compare requirements, and book a call.")
                                                          (render-funnel :select)
                                                          (render-license-tabs visible-licenses
                                                                               (case (get query "type")
                                                                                 "entrepreneur" :license.type/entrepreneur
                                                                                 "gcc" :license.type/gcc
                                                                                 "general" :license.type/general
                                                                                 nil))
                                                          (render-outcomes sorted-values)
                                                          (render-faqs visible-faqs)]))
                                      footer-cta)

                       "/comparison"
                       (html-response (str (or (:business/name business) "Darel Wasl") " · Comparison")
                                      nav
                                      (apply str (remove nil?
                                                         [(render-hero-light "Compare license paths" "Side-by-side details across processing, cost, ownership, and required documents.")
                                                          (render-funnel :compare)
                                                          (render-comparison sorted-comparison)]))
                                      footer-cta)

                       "/process"
                       (html-response (str (or (:business/name business) "Darel Wasl") " · Process")
                                      nav
                                      (apply str (remove nil?
                                                         [(render-hero-light "Process and activation" "Understand the phases, inputs, and outputs for going live in KSA.")
                                                          (render-journey sorted-phases sorted-activation)]))
                                      footer-cta)

                       "/about"
                       (html-response (str (or (:business/name business) "Darel Wasl") " · About")
                                      nav
                                      (apply str (remove nil?
                                                         [(render-hero-light "About Darel Wasl" "Principles and operating model for calm, evidence-led execution.")
                                                          (render-values-team sorted-values sorted-team)]))
                                      footer-cta)

                       "/contact"
                       (html-response (str (or (:business/name business) "Darel Wasl") " · Contact")
                                      nav
                                      (apply str [(render-hero-light "Talk to the team" "Schedule a meeting or email us with your activities and timing.")
                                                  (render-funnel :schedule)
                                                  (render-contact business selected-contact)])
                                      footer-cta)

                       (render-not-found clean-path nav))))
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
