(ns darelwasl.site.templates
  (:require [clojure.string :as str]))

(def ^:private public-defaults
  {:company-name "Dar El Wasl"
   :site-name "Dar El Wasl"
   :email "admin@darelwasl.com"
   :phone "+966579373003"
   :phone-display "+966 57 937 3003"
   :phone-local "0579373003"
   :area-served "Saudi Arabia"})

(defn- normalize-base-path
  [base-path]
  (let [b (str/trim (str (or base-path "")))]
    (cond
      (or (str/blank? b) (= b "/")) ""
      (str/starts-with? b "/") (str/replace b #"/+$" "")
      :else (str "/" (str/replace b #"/+$" "")))))

(defn- with-base
  [base-path href]
  (let [base (normalize-base-path base-path)
        h (str (or href ""))]
    (cond
      (str/blank? base) h
      (str/blank? h) h
      (or (str/starts-with? h "http://")
          (str/starts-with? h "https://")
          (str/starts-with? h "mailto:")
          (str/starts-with? h "tel:")
          (str/starts-with? h "javascript:")
          (str/starts-with? h "#")) h
      (str/starts-with? h base) h
      (str/starts-with? h "/") (str base h)
      :else (str base "/" h))))

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

(defn select-contact
  "Best-effort extraction of a public contact from DB entities."
  [businesses contacts]
  (let [contact-index (into {} (map (fn [c] [(:contact/id c) c]) (or contacts [])))
        business (or (first (filter #(not= false (:business/visible? %)) (or businesses [])))
                     (first businesses)
                     {})
        selected (or (some-> business :business/contact (ref-id :contact/id) contact-index)
                     (first contacts)
                     {})
        ;; The public site must stay stable even if DB content is stale/misconfigured.
        ;; We only use DB values for naming when present; email/phone remain the canonical site defaults.
        email (:email public-defaults)
        phone (:phone public-defaults)]
    (merge public-defaults
           {:email email
            :phone phone
            :company-name (or (:business/name business) (:company-name public-defaults))
            :site-name (or (:business/name business) (:site-name public-defaults))})))

(defn public-redirect
  [location]
  {:status 302
   :headers {"Location" location}
   :body ""})

(defn- lang-spec
  [lang]
  (case lang
    :ar {:lang "ar" :dir "rtl" :prefix "/ar" :og-locale "ar_SA" :label "AR" :aria "التبديل إلى العربية"}
    :ur {:lang "ur" :dir "rtl" :prefix "/ur" :og-locale "ur_PK" :label "UR" :aria "اردو میں تبدیل کریں"}
    {:lang "en" :dir "ltr" :prefix "" :og-locale "en_US" :label "EN" :aria "Switch to English"}))

(defn- absolute-url
  [public-base-url base-path path]
  (str (str/replace (or public-base-url "") #"/+$" "")
       (normalize-base-path base-path)
       path))

(defn- json-escape
  [s]
  (-> (str (or s ""))
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")))

(defn- strip-lang-prefix
  "Given a full path like /ar/saudi or /saudi, returns the logical path without the lang prefix."
  [full-path]
  (let [p (str (or full-path ""))]
    (cond
      (str/starts-with? p "/ar/") (subs p 3)
      (str/starts-with? p "/ur/") (subs p 3)
      (= p "/ar") "/"
      (= p "/ur") "/"
      :else p)))

(defn- render-seo-head
  [{:keys [title description public-base-url base-path full-path image-path lang]}]
  (let [{:keys [lang dir og-locale]} (lang-spec lang)
        canonical (absolute-url public-base-url base-path full-path)
        logical-path (strip-lang-prefix full-path)
        og-image (when image-path (absolute-url public-base-url base-path image-path))
        preview-mode? (str/starts-with? (normalize-base-path base-path) "/_preview/")]
    (str "<!doctype html><html lang='" (escape-html lang) "' dir='" (escape-html dir) "'>"
         "<head><meta charset='utf-8'>"
         "<meta name='viewport' content='width=device-width,initial-scale=1'>"
         "<title>" (escape-html title) "</title>"
         "<meta name='robots' content='index,follow,max-image-preview:large'>"
         (when (and description (not (str/blank? description)))
           (str "<meta name='description' content='" (escape-html description) "'>"))
         "<meta property='og:title' content='" (escape-html title) "'>"
         (when (and description (not (str/blank? description)))
           (str "<meta property='og:description' content='" (escape-html description) "'>"))
         "<meta property='og:url' content='" (escape-html canonical) "'>"
         "<meta property='og:site_name' content='" (escape-html (:site-name public-defaults)) "'>"
         "<meta property='og:locale' content='" (escape-html og-locale) "'>"
         "<meta property='og:type' content='website'>"
         (when og-image
           (str "<meta property='og:image' content='" (escape-html og-image) "'>"))
         "<meta name='twitter:card' content='summary_large_image'>"
         "<meta name='twitter:title' content='" (escape-html title) "'>"
         (when (and description (not (str/blank? description)))
           (str "<meta name='twitter:description' content='" (escape-html description) "'>"))
         (when og-image
           (str "<meta name='twitter:image' content='" (escape-html og-image) "'>"))
         "<link rel='canonical' href='" (escape-html canonical) "'>"
         "<link rel='alternate' hreflang='en' href='" (escape-html (absolute-url public-base-url base-path logical-path)) "'>"
         "<link rel='alternate' hreflang='ar' href='" (escape-html (absolute-url public-base-url base-path (str "/ar" logical-path))) "'>"
         "<link rel='alternate' hreflang='ur' href='" (escape-html (absolute-url public-base-url base-path (str "/ur" logical-path))) "'>"
         "<link rel='alternate' hreflang='x-default' href='" (escape-html (absolute-url public-base-url base-path logical-path)) "'>"
         "<link rel='icon' href='" (escape-html (with-base base-path "/logo.svg")) "' type='image/svg+xml'>"
         "<link rel='apple-touch-icon' href='" (escape-html (with-base base-path "/logo.svg")) "'>"
         "<link rel='stylesheet' href='" (escape-html (with-base base-path "/css/site.css")) "'>"
         (when preview-mode?
           (str "<link rel='stylesheet' href='" (escape-html (with-base base-path "/css/preview-annotate.css")) "'>"))
         (when preview-mode?
           (str "<script src='" (escape-html (with-base base-path "/preview-annotate.js")) "'></script>"))
         "</head>")))

(defn- render-public-header
  [{:keys [base-path lang path]}]
  (let [{:keys [prefix]} (lang-spec lang)
        full (str prefix path)
        href (fn [p] (with-base base-path (str prefix p)))
        labels (case lang
	                 :ar {:saudi "السعودية"
	                      :saudi-start "ابدأ في السعودية"
	                      :foreign "المستثمر الأجنبي"
	                      :entrepreneur "مسار ريادي"
	                      :gcc "مسار مواطني الخليج"
	                      :pro "خدمات PRO / GRO"
	                      :activation "التفعيل والامتثال"
	                      :uk "المملكة المتحدة"
	                      :uk-start "ابدأ في المملكة المتحدة"
	                      :uk-formation "تأسيس شركة UK Ltd"
	                      :resources "الموارد"
	                      :resources-hub "الموارد"
	                      :saudi-guide "دليل تأسيس شركة في السعودية"
	                      :uk-guide "دليل تأسيس شركة في بريطانيا"
	                      :blog "المدونة"
	                      :faqs "الأسئلة الشائعة"
	                      :about "من نحن"
	                      :contact "تواصل"}
	                 :ur {:saudi "سعودی عرب"
	                      :saudi-start "سعودی عرب میں آغاز"
	                      :foreign "غیر ملکی سرمایہ کار"
	                      :entrepreneur "انٹرپرینیور"
	                      :gcc "GCC نیشنل"
	                      :pro "PRO / GRO سروسز"
	                      :activation "ایکٹیویشن اور کمپلائنس"
	                      :uk "یو کے"
	                      :uk-start "یو کے میں آغاز"
	                      :uk-formation "UK Ltd تشکیل"
	                      :resources "وسائل"
	                      :resources-hub "وسائل"
	                      :saudi-guide "سعودی سیٹ اپ گائیڈ"
	                      :uk-guide "یو کے تشکیل گائیڈ"
	                      :blog "بلاگ"
	                      :faqs "FAQs"
	                      :about "ہمارے بارے میں"
	                      :contact "رابطہ"}
	                 {:saudi "Saudi Arabia"
	                  :saudi-start "Start in Saudi"
	                  :foreign "Foreign Investors"
	                  :entrepreneur "Entrepreneur setup"
	                  :gcc "GCC Nationals setup"
	                  :pro "PRO / GRO Services"
	                  :activation "Activation & Compliance"
	                  :uk "UK"
	                  :uk-start "Start in the UK"
	                  :uk-formation "UK Ltd Company Formation"
	                  :resources "Resources"
	                  :resources-hub "Resources"
	                  :saudi-guide "Saudi Setup Guide"
	                  :uk-guide "UK Formation Guide"
	                  :blog "Blog"
	                  :faqs "FAQs"
	                  :about "About"
	                  :contact "Contact"})
        langs [{:label "EN" :href (with-base base-path path) :active? (nil? lang) :aria "Switch to English"}
               {:label "AR" :href (with-base base-path (str "/ar" path)) :active? (= lang :ar) :aria "التبديل إلى العربية"}
               {:label "UR" :href (with-base base-path (str "/ur" path)) :active? (= lang :ur) :aria "اردو میں تبدیل کریں"}]
        lang-switcher (format "<div class='lang-switcher' aria-label='Language'>%s</div>"
                              (apply str
                                     (for [{:keys [label href active? aria]} langs]
                                       (format "<a class='lang-switch %s' href='%s' aria-label='%s'>%s</a>"
                                               (if active? "active" "")
                                               (escape-html href)
                                               (escape-html aria)
                                               (escape-html label)))))
        group-active (fn [group]
                       (case group
                         :saudi (str/starts-with? path "/saudi")
                         :uk (str/starts-with? path "/uk")
                         :resources (str/starts-with? path "/resources")
                         false))
        link (fn [p label]
               (format "<a class='nav-link %s' href='%s'>%s</a>"
                       (if (= full (str prefix p)) "active" "")
                       (escape-html (href p))
                       (escape-html label)))
        dropdown (fn [group label entries]
                   (format "<div class='nav-item has-dropdown %s'><span class='nav-link nav-trigger'>%s</span><div class='nav-dropdown'>%s</div></div>"
                           (if (group-active group) "active" "")
                           (escape-html label)
                           (apply str entries)))]
    (str "<header class='site-header'><div class='shell'><div class='nav-bar'>"
         "<a class='brand' href='" (escape-html (with-base base-path (str prefix "/"))) "' aria-label='Dar El Wasl'>"
         "<img src='" (escape-html (with-base base-path "/logo.svg")) "' alt='Dar El Wasl logo'></a>"
         "<nav class='nav-links' aria-label='Primary'>"
         (dropdown :saudi (:saudi labels)
                   [(link "/saudi" (:saudi-start labels))
                    (link "/saudi/foreign-investors" (:foreign labels))
                    (link "/saudi/entrepreneur" (:entrepreneur labels))
                    (link "/saudi/gcc" (:gcc labels))
                    (link "/saudi/pro-services" (:pro labels))
                    (link "/saudi/activation" (:activation labels))])
         (dropdown :uk (:uk labels)
                   [(link "/uk" (:uk-start labels))
                    (link "/uk/company-formation" (:uk-formation labels))])
	         (dropdown :resources (:resources labels)
	                   [(link "/resources" (:resources-hub labels))
	                    (link "/resources/saudi-business-setup-guide" (:saudi-guide labels))
	                    (link "/resources/uk-company-formation-guide" (:uk-guide labels))
	                    (link "/resources/blog" (:blog labels))
	                    (link "/resources/faqs" (:faqs labels))])
         (format "<a class='nav-link %s' href='%s'>%s</a>"
                 (if (= full (str prefix "/about")) "active" "")
                 (escape-html (href "/about"))
                 (escape-html (:about labels)))
         (format "<a class='nav-link primary-cta' href='%s'>%s</a>"
                 (escape-html (href "/contact#consultation"))
                 (escape-html (:contact labels)))
         "</nav>"
         "<div class='nav-meta'>" lang-switcher "</div>"
         "<button id='mobile-toggle' class='mobile-toggle' type='button' aria-expanded='false' aria-controls='mobile-menu' onclick='toggleMenu()'>Menu</button>"
         "</div></div></header>"
         "<nav id='mobile-menu' class='mobile-menu' aria-label='Mobile'><div class='shell'>"
         "<div class='mobile-lang'>" lang-switcher "</div>"
         "<div class='mobile-group'><div class='mobile-group__title'>" (escape-html (:saudi labels)) "</div><div class='mobile-group__links'>"
         (link "/saudi" (:saudi-start labels))
         (link "/saudi/foreign-investors" (:foreign labels))
         (link "/saudi/entrepreneur" (:entrepreneur labels))
         (link "/saudi/gcc" (:gcc labels))
         (link "/saudi/pro-services" (:pro labels))
         (format "<a class='nav-link %s' href='%s'>%s</a>"
                 (if (= full (str prefix "/saudi/activation")) "active" "")
                 (escape-html (href "/saudi/activation"))
                 (escape-html (:activation labels)))
         "</div></div>"
         "<div class='mobile-group'><div class='mobile-group__title'>" (escape-html (:uk labels)) "</div><div class='mobile-group__links'>"
         (link "/uk" (:uk-start labels))
         (link "/uk/company-formation" (:uk-formation labels))
         "</div></div>"
	         "<div class='mobile-group'><div class='mobile-group__title'>" (escape-html (:resources labels)) "</div><div class='mobile-group__links'>"
	         (link "/resources" (:resources-hub labels))
	         (link "/resources/saudi-business-setup-guide" (:saudi-guide labels))
	         (link "/resources/uk-company-formation-guide" (:uk-guide labels))
	         (link "/resources/blog" (:blog labels))
	         (link "/resources/faqs" (:faqs labels))
	         "</div></div>"
         (link "/about" (:about labels))
         (format "<a class='nav-link' href='%s'>%s</a>"
                 (escape-html (href "/contact#consultation"))
                 (escape-html (:contact labels)))
         "</div></nav>")))

(defn- current-year []
  (.getYear (java.time.LocalDate/now)))

(defn- render-public-footer
  [base-path lang]
  (let [{:keys [prefix]} (lang-spec lang)
        year (current-year)
        rights (case lang
                 :ar (str "© " year " دار الوصل. جميع الحقوق محفوظة.")
                 :ur (str "© " year " Dar El Wasl. تمام حقوق محفوظ ہیں۔")
                 (str "© " year " Dar El Wasl. All rights reserved."))
        href (fn [p] (with-base base-path (str prefix p)))]
    (str "<footer class='site-footer'><div class='footer-content'><div>"
         (escape-html rights)
         "</div><div>"
         "<a class='nav-link' href='" (escape-html (href "/contact#consultation")) "'>"
         (escape-html (case lang :ar "تواصل" :ur "رابطہ" "Contact"))
         "</a>"
         "<a class='nav-link' href='" (escape-html (href "/about")) "'>"
         (escape-html (case lang :ar "من نحن" :ur "ہمارے بارے میں" "About"))
         "</a>"
         "<a class='nav-link' href='" (escape-html (href "/privacy")) "'>"
         (escape-html (case lang :ar "الخصوصية" :ur "پرائیویسی" "Privacy"))
         "</a>"
         "<a class='nav-link' href='" (escape-html (href "/terms")) "'>"
         (escape-html (case lang :ar "الشروط" :ur "شرائط" "Terms"))
         "</a>"
         "<a class='nav-link' href='" (escape-html (href "/cookies")) "'>"
         (escape-html (case lang :ar "الكوكيز" :ur "کوکیز" "Cookies"))
         "</a>"
         "</div></div></footer>")))

(defn- public-page
  [{:keys [title description public-base-url base-path lang path image-path contact]} body]
  (let [spec (lang-spec lang)
        full-path (str (:prefix spec) path)
        contact' (merge public-defaults (or contact {}))
        schema (format "{\"@context\":\"https://schema.org\",\"@type\":\"ProfessionalService\",\"name\":\"%s\",\"url\":\"%s\",\"logo\":\"%s\",\"areaServed\":\"%s\",\"contactPoint\":[{\"@type\":\"ContactPoint\",\"contactType\":\"sales\",\"email\":\"%s\",\"telephone\":\"%s\"}]}"
                      (json-escape (:site-name contact'))
                      (json-escape (absolute-url public-base-url base-path ""))
                      (json-escape (absolute-url public-base-url base-path "/logo.svg"))
                      (json-escape (:area-served contact'))
                      (json-escape (:email contact'))
                      (json-escape (:phone contact')))
        crumbs (format "{\"@context\":\"https://schema.org\",\"@type\":\"BreadcrumbList\",\"itemListElement\":[{\"@type\":\"ListItem\",\"position\":1,\"name\":\"Home\",\"item\":\"%s\"},{\"@type\":\"ListItem\",\"position\":2,\"name\":\"%s\",\"item\":\"%s\"}]}"
                       (json-escape (absolute-url public-base-url base-path "/"))
                       (json-escape (or title "Page"))
                       (json-escape (absolute-url public-base-url base-path full-path)))]
    (str (render-seo-head {:title title
                           :description description
                           :public-base-url public-base-url
                           :base-path base-path
                           :full-path full-path
                           :image-path image-path
                           :lang lang})
         "<script type='application/ld+json'>" schema "</script>"
         "<script type='application/ld+json'>" crumbs "</script>"
         "<script>(function(){var __dwScrollY=0;var __dwPreventTouch=function(e){var menu=document.getElementById('mobile-menu');if(!menu){return;}if(menu.contains(e.target)){return;}e.preventDefault();};var __dwHeader=function(){return document.querySelector('.site-header');};var __dwHeaderHeight=function(){var h=__dwHeader();return h?h.offsetHeight:72;};var __dwSetHeaderVar=function(){document.documentElement.style.setProperty('--dw-header-h',__dwHeaderHeight()+'px');};window.toggleMenu=function(force){var body=document.body;var btn=document.getElementById('mobile-toggle');var open=(typeof force==='boolean')?force:!body.classList.contains('mobile-open');__dwSetHeaderVar();if(open){__dwScrollY=window.pageYOffset||0;body.classList.add('mobile-open');body.style.position='fixed';body.style.top=(-__dwScrollY)+'px';body.style.left='0';body.style.right='0';body.style.width='100%';if(btn){btn.setAttribute('aria-expanded','true');}document.addEventListener('touchmove',__dwPreventTouch,{passive:false});}else{body.classList.remove('mobile-open');body.style.position='';body.style.top='';body.style.left='';body.style.right='';body.style.width='';if(btn){btn.setAttribute('aria-expanded','false');btn.focus();}document.removeEventListener('touchmove',__dwPreventTouch,{passive:false});window.scrollTo(0,__dwScrollY);} };document.addEventListener('click',function(e){if(!document.body.classList.contains('mobile-open')){return;}var menu=document.getElementById('mobile-menu');if(!menu){return;}var link=e.target&&e.target.closest?e.target.closest('a'):null;if(link&&menu.contains(link)){window.toggleMenu(false);} });window.addEventListener('resize',__dwSetHeaderVar);window.addEventListener('orientationchange',__dwSetHeaderVar);})();</script>"
	         "<body data-theme='site-premium'>"
	         (render-public-header {:base-path base-path :lang lang :path path})
	         "<main>" body "</main>"
	         (render-public-footer base-path lang)
	         "</body></html>")))

(defn- hero-simple
  [headline strapline image alt]
  (let [has-image? (and (not (str/blank? (str image)))
                        (not (str/blank? (str alt))))
        classes (str "hero hero--simple" (when-not has-image? " hero--solo"))]
    (str "<section class='" classes "'>"
         "<div class='hero-copy'><h1 class='headline'>" (escape-html headline) "</h1>"
         "<p class='strapline'>" (escape-html strapline) "</p></div>"
         (or (when has-image?
               (str "<div class='hero-media hero-media--compact'>"
                    "<img src='" (escape-html image) "' alt='" (escape-html alt) "' loading='lazy'>"
                    "</div>"))
             "")
         "</section>")))

(defn- hero-split
  [{:keys [headline-html strapline primary secondary image alt]}]
  (let [has-image? (and (not (str/blank? (str image)))
                        (not (str/blank? (str alt))))
        classes (str "hero hero--split" (when-not has-image? " hero--solo"))]
    (str "<section class='" classes "'>"
         "<div class='hero-copy'>"
         "<h1 class='headline'>" headline-html "</h1>"
         "<p class='strapline'>" (escape-html strapline) "</p>"
         "<div class='hero-actions'>"
         (when primary
           (format "<a class='cta primary' href='%s'>%s</a>"
                   (escape-html (:href primary))
                   (escape-html (:label primary))))
         (when secondary
           (format "<a class='cta secondary' href='%s'>%s</a>"
                   (escape-html (:href secondary))
                   (escape-html (:label secondary))))
         "</div></div>"
         (or (when has-image?
               (str "<div class='hero-media'>"
                    "<img src='" (escape-html image) "' alt='" (escape-html alt) "' loading='lazy'>"
                    "</div>"))
             "")
         "</section>")))

(defn- bullet-list
  [items]
  (format "<ul class='bullet-list'>%s</ul>"
          (apply str (for [item items] (format "<li>%s</li>" (escape-html item))))))

(defn- icon-globe []
  "<svg viewBox='0 0 24 24' aria-hidden='true' focusable='false'><circle cx='12' cy='12' r='9' fill='none' stroke='currentColor' stroke-width='1.5'/><path d='M3 12h18M12 3a15 15 0 0 1 0 18M12 3a15 15 0 0 0 0 18' fill='none' stroke='currentColor' stroke-width='1.2'/></svg>")

(defn- icon-mountain []
  "<svg viewBox='0 0 24 24' aria-hidden='true' focusable='false'><path d='M4 14l4-6 4 6 4-6 4 6' fill='none' stroke='currentColor' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/><path d='M4 18h16' fill='none' stroke='currentColor' stroke-width='1.5' stroke-linecap='round'/></svg>")

(defn- icon-hex []
  "<svg viewBox='0 0 24 24' aria-hidden='true' focusable='false'><path d='M12 3l8 5v8l-8 5-8-5V8l8-5z' fill='none' stroke='currentColor' stroke-width='1.5'/><path d='M12 8v8M8.5 10.5l7 3' fill='none' stroke='currentColor' stroke-width='1.2'/></svg>")

(defn- card-icon
  [{:keys [title description href icon]}]
  (str "<div class='card card--icon'>"
       "<div class='card-icon'>" icon "</div>"
       "<h3>" (escape-html title) "</h3>"
       "<p>" (escape-html description) "</p>"
       "<a class='text-link' href='" (escape-html href) "'>" (escape-html title) "</a>"
       "</div>"))

(defn public-not-found
  [{:keys [public-base-url base-path lang path]}]
  {:status 404
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (public-page {:title "Not found"
                       :description "Page not found."
                       :public-base-url public-base-url
                       :base-path base-path
                       :lang lang
                       :path path
                       :image-path "/logo.jpg"}
                      (str "<section class='section-pad'><h1>Page not found</h1>"
                           "<p>No content found at <strong>" (escape-html path) "</strong>.</p></section>"))})

(defn public-verify
  [{:keys [public-base-url base-path lang path query verification]}]
  (let [spec (lang-spec lang)
        prefix (:prefix spec)
        verify-path (str prefix "/verify")
        action-href (with-base base-path verify-path)
        ref (or (get query "ref") (get query "document") "")
        code (or (get query "code") (get query "verification") "")
        {:keys [valid? facts]} verification
        banner (cond
                 (nil? valid?) ""
                 valid? "<div style='padding:12px 14px;border-radius:14px;border:1px solid #bbf7d0;background:#f0fdf4;color:#14532d;margin:14px 0;'><strong>VALID</strong> Dar El Wasl document.</div>"
                 :else "<div style='padding:12px 14px;border-radius:14px;border:1px solid #fecaca;background:#fef2f2;color:#7f1d1d;margin:14px 0;'><strong>INVALID</strong> document verification code.</div>")
        facts-html (when (and valid? (seq facts))
                     (str "<div style='margin-top:10px;padding:14px 16px;border-radius:14px;border:1px solid #e2e8f0;background:#fff;'>"
                          "<h3 style='margin:0 0 10px;font-size:16px;'>Document details</h3>"
                          "<dl style='display:grid;grid-template-columns: 180px 1fr;gap:8px 14px;margin:0;'>"
                          (apply str (for [[k v] facts]
                                       (str "<dt style='color:#475569;'>" (escape-html (name k)) "</dt>"
                                            "<dd style='margin:0;font-weight:600;'>" (escape-html (str v)) "</dd>")))
                          "</dl></div>"))]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (public-page {:title "Verify document"
                         :description "Verify a Dar El Wasl document."
                         :public-base-url public-base-url
                         :base-path base-path
                         :lang lang
                         :path path
                         :image-path "/logo.jpg"}
                        (str "<section class='section-pad'>"
                             "<h1>Verify a document</h1>"
                             "<p>Enter the document reference and verification code to confirm authenticity.</p>"
                             "<form method='get' action='" (escape-html action-href) "' style='margin-top:14px;display:grid;gap:10px;max-width:520px;'>"
                             "<label>Document reference<br/><input name='ref' value='" (escape-html ref) "' style='width:100%;padding:10px 12px;border:1px solid #cbd5e1;border-radius:12px;'/></label>"
                             "<label>Verification code<br/><input name='code' value='" (escape-html code) "' style='width:100%;padding:10px 12px;border:1px solid #cbd5e1;border-radius:12px;'/></label>"
                             "<button type='submit' style='padding:10px 14px;border-radius:12px;border:1px solid #1f2147;background:#1f2147;color:#fff;font-weight:700;width:max-content;'>Verify</button>"
                             "</form>"
                             banner
                             (or facts-html "")
                             "</section>"))}))

(defn public-route
  [{:keys [public-base-url base-path lang path contact query]}]
  (let [spec (lang-spec lang)
        prefix (:prefix spec)
        href (fn [p]
               (let [p' (str (or p ""))]
                 (with-base base-path
                   (if (or (str/starts-with? p' "/css/")
                           (str/starts-with? p' "/images/")
                           (str/starts-with? p' "/js/")
                           (str/starts-with? p' "/logo."))
                     p'
                     (str prefix p')))))]
    (case path
       "/"
       (let [contact (merge public-defaults (or contact {}))
             email (:email contact)
             phone (:phone contact)
             phone-display (or (:phone-display contact) (:phone contact))
             phone-local (:phone-local contact)
             cta-primary {:label (case lang
                                  :ar "احجز استشارة"
                                  :ur "مشاورت طے کریں"
                                  "Schedule a consultation")
                          :href (href "/contact#consultation")}
             cta-secondary {:label (case lang
                                    :ar "قارن خيارات التأسيس"
                                    :ur "سیٹ اپ کے اختیارات کا موازنہ کریں"
                                    "Compare setup options")
                            :href (href "#setup-options")}
             headline-html (case lang
                             :ar "أسس عملك في السعودية<br>بملكية أجنبية<br>%100"
                             :ur "سعودی عرب میں %100<br>غیر ملکی ملکیت کے ساتھ<br>اپنا کاروبار قائم کریں"
                             "Setup your Business in<br>Saudi Arabia with<br>100% Foreign Ownership")
             strapline (case lang
                        :ar "نساعد المؤسسين والشركات الدولية على تأسيس أعمالهم في السعودية عبر الخيار الصحيح، ثم ننفذ الأعمال الورقية وخدمات PRO/GRO للحفاظ على امتثالك."
                        :ur "ہم بانیوں اور بین الاقوامی کمپنیوں کو سعودی عرب میں درست سیٹ اپ اختیار کے ذریعے قانونی طور پر آغاز کرنے میں مدد دیتے ہیں، پھر کاغذی کارروائی اور PRO/GRO کے کام سنبھالتے ہیں تاکہ آپ مطابقت میں رہیں۔"
                        "We help founders and overseas companies launch legally in Saudi Arabia through the right setup option, then handle the paperwork and PRO/GRO work to keep you compliant.")
             section-title (case lang
                             :ar "اختر خيار التأسيس المناسب لك"
                             :ur "اپنے لیے مناسب سیٹ اپ اختیار منتخب کریں"
                             "Choose the setup option that fits you")
             cards (case lang
                     :ar [{:title "تأسيس المستثمر الأجنبي (مسار وزارة الاستثمار / العام)"
                           :desc "للمؤسسين والشركات الدولية الذين يحتاجون خيارًا واضحًا وخطة تفعيل بعد الترخيص."
                           :href (href "/saudi/foreign-investors")
                           :link "عرض خيار المستثمر الأجنبي"
                           :icon (icon-globe)}
                          {:title "خيار رخصة ريادي"
                           :desc "للشركات الناشئة والمؤسسين عبر مسار ريادي مع الوثائق الداعمة الصحيحة."
                           :href (href "/saudi/entrepreneur")
                           :link "عرض خيار ريادي"
                           :icon (icon-mountain)}
                          {:title "خيار مواطني دول الخليج"
                           :desc "لمواطني الخليج الذين يريدون تأسيسًا سريعًا ومتوافقًا."
                           :href (href "/saudi/gcc")
                           :link "عرض خيار الخليج"
                           :icon (icon-hex)}
                          {:title "خدمات PRO / GRO"
                           :desc "لمعاملات التأشيرات، المنصات الحكومية، التجديدات، ومهام الامتثال المستمرة."
                           :href (href "/saudi/pro-services")
                           :link "عرض خدمات PRO"
                           :icon (icon-mountain)}]
                     :ur [{:title "غیر ملکی سرمایہ کار سیٹ اپ (MISA / جنرل اختیار)"
                           :desc "اوورسیز بانیوں اور بین الاقوامی کمپنیوں کے لیے جو سعودی سیٹ اپ اور ایک واضح ایکٹیویشن پلان چاہتے ہیں۔"
                           :href (href "/saudi/foreign-investors")
                           :link "غیر ملکی سرمایہ کار سیٹ اپ دیکھیں"
                           :icon (icon-globe)}
                          {:title "انٹرپرینیور سیٹ اپ"
                           :desc "اسٹارٹ اپس اور بانیوں کے لیے جو درست سپورٹ ڈاکومنٹس کے ساتھ انٹرپرینیور اختیار اپناتے ہیں۔"
                           :href (href "/saudi/entrepreneur")
                           :link "انٹرپرینیور سیٹ اپ دیکھیں"
                           :icon (icon-mountain)}
                          {:title "GCC نیشنل سیٹ اپ"
                           :desc "خلیجی شہریوں کے لیے جو تیز اور مطابق سیٹ اپ چاہتے ہیں۔"
                           :href (href "/saudi/gcc")
                           :link "GCC سیٹ اپ دیکھیں"
                           :icon (icon-hex)}
                          {:title "PRO / GRO سروسز"
                           :desc "ویزا، حکومتی پورٹلز، تجدیدات، اور بعد از سیٹ اپ کمپلائنس کے لیے۔"
                           :href (href "/saudi/pro-services")
                           :link "PRO سروسز دیکھیں"
                           :icon (icon-mountain)}]
                     [{:title "Foreign Investor Setup (MISA / General option)"
                       :desc "For overseas founders and international companies who want a compliant Saudi option and a clear activation plan."
                       :href (href "/saudi/foreign-investors")
                       :link "View Foreign Investor Setup"
                       :icon (icon-globe)}
                      {:title "Entrepreneur setup"
                       :desc "For startups and founders pursuing an entrepreneur option with the right supporting documents and positioning."
                       :href (href "/saudi/entrepreneur")
                       :link "View Entrepreneur setup"
                       :icon (icon-mountain)}
                      {:title "GCC Nationals setup"
                       :desc "For GCC nationals who want to formalize operations fast with the right option."
                       :href (href "/saudi/gcc")
                       :link "View GCC setup"
                       :icon (icon-hex)}
                      {:title "PRO / GRO Services"
                       :desc "For ongoing visas, government portals, renewals, and compliance tasks after setup."
                       :href (href "/saudi/pro-services")
                       :link "View PRO Services"
                       :icon (icon-mountain)}])
             process-title (case lang
                            :ar "عملية واضحة تقلل المفاجآت"
                            :ur "ایک سادہ عمل جو حیرت کم کرتا ہے"
                            "A simple process that reduces surprises")
             process (case lang
                       :ar [{:title "الاستشارة (15 إلى 30 دقيقة)"
                             :desc "نؤكد الأنشطة والملكية، ونحدد الخيار الأنسب. تحصل على قائمة متطلبات واضحة."}
                            {:title "تنفيذ التأسيس والترخيص"
                             :desc "نرتب الوثائق والترجمات والتقديمات والموافقات خطوة بخطوة."}
                            {:title "التفعيل + استمرارية PRO"
                             :desc "نساعدك للوصول إلى جاهزية التشغيل ونبقي مهامك الحكومية تتحرك."}]
                       :ur [{:title "مشاورت (15 سے 30 منٹ)"
                             :desc "ہم آپ کی سرگرمیوں اور ملکیت کی توثیق کرتے ہیں اور درست اختیار منتخب کرتے ہیں۔ آپ کو تقاضوں کی فہرست ملتی ہے۔"}
                            {:title "انکارپوریشن + لائسنسنگ عمل"
                             :desc "ہم دستاویزات، ترجمہ، جمع کرانے اور منظوریوں کو مرحلہ وار منظم کرتے ہیں۔"}
                            {:title "ایکٹیویشن + PRO تسلسل"
                             :desc "ہم آپ کو آپریشنل ریڈی تک پہنچاتے ہیں اور حکومتی کام جاری رکھتے ہیں۔"}]
                       [{:title "Consultation (15 to 30 minutes)"
                         :desc "We confirm your activities, ownership profile, and the best setup option. You leave with a requirements list."}
                        {:title "Incorporation + licensing execution"
                         :desc "We choreograph documents, submissions, translations, and approvals step-by-step."}
                        {:title "Activation + PRO continuity"
                         :desc "We help you reach operational-ready and keep your government tasks moving."}])
             value-title (case lang
                           :ar "ما يحصل عليه العملاء مع دار الوصل"
                           :ur "Dar El Wasl کے ساتھ آپ کو کیا ملتا ہے"
                           "What clients get with Dar El Wasl")
             values (case lang
                      :ar ["قائمة متطلبات مكتوبة قبل أن تصرف وقتًا/مالًا"
                           "خطوات واضحة، بدون وعود مبهمة"
                           "دعم ثنائي اللغة (عربي/إنجليزي/أردو)"
                           "قدرة PRO/GRO مستمرة بعد التأسيس"
                           "سير عمل هادئ وموثق"]
                      :ur ["خرچ کرنے سے پہلے تحریری تقاضوں کی فہرست"
                           "واضح اگلے اقدامات، مبہم وعدے نہیں"
                           "کثیر لسانی سپورٹ (EN/AR/UR)"
                           "سیٹ اپ کے بعد جاری PRO/GRO صلاحیت"
                           "پُرسکون اور دستاویزی ورک فلو"]
                      ["A written requirements list before you spend time/money"
                       "Clear next steps, not vague promises"
                       "Bilingual support (EN/AR/UR)"
                       "Ongoing PRO/GRO capability after setup"
                       "A calm, documented workflow"])
             intl-title (case lang
                          :ar "مصمم للمؤسسين الدوليين"
                          :ur "بین الاقوامی بانیوں کے لیے بنایا گیا"
                          "Built for international founders")
             intl-copy (case lang
                        :ar "إذا كنت معتادًا على توثيق واضح وتحديثات تقدم، ستشعر بالراحة. هدفنا جعل التأسيس في السعودية منظمًا ومتوقعًا."
                        :ur "اگر آپ واضح دستاویزات اور پروگریس اپڈیٹس کے عادی ہیں تو آپ خود کو گھر جیسا محسوس کریں گے۔ ہمارا مقصد سعودی سیٹ اپ کو منظم اور قابلِ پیش گوئی بنانا ہے۔"
                        "If you’re used to clear documentation and progress updates, you’ll feel at home. Our goal is to make Saudi setup feel structured and predictable.")
             faq-title (case lang
                         :ar "الأسئلة الشائعة"
                         :ur "اکثر پوچھے گئے سوالات"
                         "FAQ")
             faq (case lang
                   :ar [{:q "هل تقدمون دراسات جدوى؟" :a "نركز على الترخيص، التأسيس، التفعيل، وخدمات PRO/GRO التنفيذية. وعند الحاجة يمكننا تنسيق رأي مختصين."}
                        {:q "هل يمكنكم المساعدة إذا كنت خارج السعودية؟" :a "نعم. يمكن تجهيز معظم الخطوات عن بُعد؛ وقد تتطلب بعض الحالات حضورًا حسب الوضع."}
                        {:q "هل تنشرون الأسعار والجداول الزمنية؟" :a "الأسعار والجداول الزمنية تُحدد حسب الأنشطة والملكية. بعد الاستشارة تحصل على نطاق واضح وتوقعات وتقدير مبدئي."}
                        {:q "هل تدعمون العربية والإنجليزية والأردو؟" :a "نعم. دعم متعدد اللغات للتواصل والوثائق حسب الحاجة."}
                        {:q "هل يمكنكم مساعدتي للبدء في المملكة المتحدة أيضًا؟" :a "نعم. يمكننا المساعدة في تأسيس شركة UK Ltd وشرح خطوات الامتثال الأساسية."}
                        {:q "أين تعملون؟" :a "نخدم العملاء عبر المملكة وننسق أي خطوات ميدانية حسب الحالة."}]
                   :ur [{:q "کیا آپ feasibility studies فراہم کرتے ہیں؟" :a "ہم لائسنسنگ، سیٹ اپ، ایکٹیویشن، اور PRO/GRO عملدرآمد پر فوکس کرتے ہیں۔ ضرورت ہو تو ہم متعلقہ ماہرین سے کوآرڈینیٹ کر سکتے ہیں۔"}
                        {:q "کیا آپ سعودی عرب سے باہر ہونے کی صورت میں مدد کر سکتے ہیں؟" :a "جی ہاں۔ زیادہ تر تیاری ریموٹ ہو سکتی ہے؛ کچھ مراحل کیس کے مطابق موجودگی چاہ سکتے ہیں۔"}
                        {:q "کیا آپ قیمتیں اور ٹائم لائنز شائع کرتے ہیں؟" :a "قیمت اور ٹائم لائن آپ کی سرگرمیوں اور ملکیت کے مطابق طے ہوتی ہے۔ مشاورت کے بعد آپ کو واضح اسکوپ، ٹائم لائن اور ابتدائی اندازہ ملتا ہے۔"}
                        {:q "کیا آپ English, Arabic اور Urdu سپورٹ کرتے ہیں؟" :a "جی ہاں۔ ضرورت کے مطابق کثیر لسانی سپورٹ۔"}
                        {:q "کیا آپ UK میں بھی آغاز میں مدد کر سکتے ہیں؟" :a "جی ہاں۔ ہم UK Ltd سیٹ اپ اور بنیادی کمپلائنس اقدامات سمجھا سکتے ہیں۔"}
                        {:q "آپ کہاں کام کرتے ہیں؟" :a "ہم سعودی عرب بھر میں کلائنٹس کی مدد کرتے ہیں اور ضروری آن گراؤنڈ مراحل کیس کے مطابق منظم کرتے ہیں۔"}]
                   [{:q "Do you offer feasibility studies?" :a "We focus on licensing, incorporation, activation, and PRO/GRO execution. If you need a feasibility study, we can coordinate a specialist."}
                    {:q "Can you help if I’m outside Saudi Arabia?" :a "Yes. Most of the process can be prepared remotely; some steps may require presence depending on your case."}
                    {:q "Do you publish prices and timelines?" :a "Pricing and timelines are scoped case-by-case based on your activities and ownership. After the consultation, you receive a clear scope, timeline, and estimate."}
                    {:q "Do you support English, Arabic, and Urdu?" :a "Yes. Multilingual support for communication and documents when needed."}
                    {:q "Can you help me start in the UK too?" :a "Yes. We can set up a UK Ltd and guide you through basic compliance steps."}
                    {:q "Where do you operate?" :a "Saudi-wide. We support clients across the Kingdom and coordinate any on-the-ground steps case-by-case."}])
             footer-title (case lang
                           :ar "هل أنت جاهز للبدء؟"
                           :ur "شروع کرنے کے لیے تیار ہیں؟"
                           "Ready to start?")
             footer-copy (case lang
                          :ar "أرسل أنشطتك وملف الملكية. سنحدد أسرع خيار متوافق."
                          :ur "اپنی سرگرمیاں اور ملکیت کی تفصیلات بھیجیں۔ ہم تیز ترین مطابق اختیار بتائیں گے۔"
                          "Send your activities and ownership profile. We’ll map the fastest compliant setup.")]
         {:status 200
          :headers {"Content-Type" "text/html; charset=utf-8"}
          :body (public-page {:title (case lang
                                       :ar "تأسيس أعمال في السعودية بملكية أجنبية 100% | دار الوصل"
                                       :ur "سعودی عرب میں کاروبار %100 غیر ملکی ملکیت کے ساتھ | Dar El Wasl"
                                       "Saudi Business Setup | Dar El Wasl")
                              :description (case lang
                                             :ar "تأسيس منظم وخدمات PRO/GRO للمؤسسين والشركات الدولية. ابدأ باستشارة للحصول على قائمة متطلبات واضحة."
                                             :ur "منظم سیٹ اپ اور PRO/GRO سروسز۔ تقاضوں کی فہرست کے لیے مشاورت طے کریں۔"
                                             "Structured setup options and PRO/GRO services. Start with a consultation to get a clear checklist.")
                              :public-base-url public-base-url
                              :base-path base-path
                              :lang lang
                              :path path
                              :image-path "/logo.jpg"
                              :contact contact}
                         (str (hero-split {:headline-html headline-html
                                           :strapline strapline
                                           :primary cta-primary
                                           :secondary cta-secondary})
                              "<section id='setup-options'><div class='section-title'><h2>" (escape-html section-title) "</h2></div>"
                              "<div class='card-grid'>"
                              (apply str
                                     (for [{:keys [title desc href link icon]} cards]
                                       (str "<div class='card card--icon'>"
                                            "<div class='card-icon'>" icon "</div>"
                                            "<h3>" (escape-html title) "</h3>"
                                            "<p>" (escape-html desc) "</p>"
                                            "<a class='text-link' href='" (escape-html href) "'>" (escape-html link) "</a>"
                                            "</div>")))
                              "</div></section>"
                              "<section><div class='section-title'><h2>" (escape-html process-title) "</h2></div>"
                              "<div class='steps steps--light'>"
                              (apply str
                                     (map-indexed
                                      (fn [idx {:keys [title desc]}]
                                        (str "<div class='step'>"
                                             "<div class='step-index'>" (inc idx) "</div>"
                                             "<div><div class='label'>" (escape-html title) "</div>"
                                             "<div class='muted'>" (escape-html desc) "</div></div></div>"))
                                      process))
                              "</div></section>"
                              "<section><div class='section-title'><h2>" (escape-html value-title) "</h2></div>"
                              (bullet-list values)
                              "</section>"
                              "<section><div class='section-title'><h2>" (escape-html intl-title) "</h2></div>"
                              "<p>" (escape-html intl-copy) "</p>"
                              "</section>"
                              "<section class='faqs'><div class='section-title'><h2>" (escape-html faq-title) "</h2></div>"
                              "<div class='stack'>"
                              (apply str
                                     (for [{:keys [q a]} faq]
                                       (str "<details><summary>" (escape-html q) "</summary>"
                                            "<p class='muted'>" (escape-html a) "</p></details>")))
                              "</div></section>"
                              "<div class='footer-cta'><div class='inner'>"
                              "<div><h3>" (escape-html footer-title) "</h3>"
                              "<p>" (escape-html footer-copy) "</p></div>"
                              "<div class='actions'>"
                              (format "<a class='cta primary' href='%s'>%s</a>" (escape-html (href "/contact#consultation")) (escape-html (:label cta-primary)))
                              (format "<a class='cta secondary' href='%s'>%s</a>" (escape-html (href "/contact#consultation")) (escape-html (case lang
                                                                                                                              :ar "تواصل معنا"
                                                                                                                              :ur "ہم سے رابطہ کریں"
                                                                                                                              "Contact us")))
                              "</div></div></div>"))})

       "/saudi"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (public-page {:title "Business Setup in Saudi Arabia | Foreign, Entrepreneur & GCC Options"
                     :description "Understand the main Saudi setup options, required inputs, and the activation steps that make your company operational. Start with a consultation to get the right checklist."
                     :public-base-url public-base-url
                     :base-path base-path
                     :lang lang
                     :path path
                     :image-path "/logo.jpg"
                     :contact contact}
                    (str (hero-simple
                          (case lang
                            :ar "بدء الأعمال في السعودية"
                            :ur "سعودی عرب میں آغاز"
                            "Start in Saudi Arabia")
                          (case lang
                            :ar "نظرة عامة على خيارات التأسيس والخطوات التي تجعل شركتك جاهزة للتشغيل."
                            :ur "سیٹ اپ کے اختیارات اور اُن اقدامات کا جائزہ جو آپ کے کاروبار کو آپریشنل بناتے ہیں۔"
                            "An overview of setup options and the activation steps that make your company operational.")
                          nil
                          nil)
                         "<section><div class='section-title'><h2>"
                         (escape-html (case lang
                                        :ar "لمن هذا؟"
                                        :ur "یہ کس کے لیے ہے؟"
                                        "Who this is for"))
                         "</h2></div>"
                         (bullet-list (case lang
                                        :ar ["مؤسسون وشركات دولية تتوسع إلى السعودية"
                                             "مواطنو الخليج الذين يثبتون كيانًا في السعودية"
                                             "شركات ناشئة عبر مسار ريادي"
                                             "أي تأسيس يحتاج استمرارية PRO/GRO"]
                                        :ur ["اوورسیز بانی اور غیر ملکی کمپنیاں جو KSA میں توسیع کر رہی ہیں"
                                             "GCC شہری جو سعودی ادارہ قائم کرنا چاہتے ہیں"
                                             "اسٹارٹ اپس جو انٹرپرینیور اختیار استعمال کرتے ہیں"
                                             "ہر وہ سیٹ اپ جسے PRO/GRO تسلسل چاہیے"]
                                        ["Overseas founders and foreign companies expanding into KSA"
                                         "GCC nationals formalizing a Saudi entity"
                                         "Startups using an entrepreneur option"
                                         "Any setup that needs PRO/GRO continuity"]))
                         "</section>"
                         "<section><div class='section-title'><h2>"
                         (escape-html (case lang
                                        :ar "خيارات التأسيس"
                                        :ur "سیٹ اپ کے اختیارات"
                                        "Setup options"))
                         "</h2></div>"
                         "<div class='card-grid'>"
                         (apply str
                                (map card-icon
                                     (case lang
                                       :ar [{:title "تأسيس المستثمر الأجنبي"
                                             :description "خيار مناسب للمؤسسين والشركات الدولية عبر مسار وزارة الاستثمار / العام."
                                             :href (href "/saudi/foreign-investors")
                                             :icon (icon-globe)}
                                            {:title "مسار ريادي"
                                             :description "للشركات الناشئة والمؤسسين مع الوثائق الداعمة الصحيحة."
                                             :href (href "/saudi/entrepreneur")
                                             :icon (icon-mountain)}
                                            {:title "مسار مواطني الخليج"
                                             :description "لمواطني الخليج الذين يريدون تأسيسًا سريعًا ومتوافقًا."
                                             :href (href "/saudi/gcc")
                                             :icon (icon-hex)}]
                                       :ur [{:title "غیر ملکی سرمایہ کار"
                                             :description "MISA/جنرل اختیار کے تحت اوورسیز بانیوں اور کمپنیوں کے لیے۔"
                                             :href (href "/saudi/foreign-investors")
                                             :icon (icon-globe)}
                                            {:title "انٹرپرینیور اختیار"
                                             :description "درست سپورٹ ڈاکومنٹس والے اسٹارٹ اپس کے لیے۔"
                                             :href (href "/saudi/entrepreneur")
                                             :icon (icon-mountain)}
                                            {:title "GCC نیشنل اختیار"
                                             :description "GCC شہریوں کے لیے تیز اور مطابق سیٹ اپ۔"
                                             :href (href "/saudi/gcc")
                                             :icon (icon-hex)}]
                                       [{:title "Foreign Investor Setup"
                                         :description "For international founders and companies using the MISA / General option."
                                         :href (href "/saudi/foreign-investors")
                                         :icon (icon-globe)}
                                        {:title "Entrepreneur setup"
                                         :description "For founder-led startups with the right support documentation."
                                         :href (href "/saudi/entrepreneur")
                                         :icon (icon-mountain)}
                                        {:title "GCC Nationals setup"
                                         :description "For GCC nationals who want a fast, compliant setup."
                                         :href (href "/saudi/gcc")
                                         :icon (icon-hex)}])))
                         "</div></section>"
                         "<section><div class='section-title'><h2>"
                         (escape-html (case lang
                                        :ar "التفعيل مهم"
                                        :ur "ایکٹیویشن اہم ہے"
                                        "Activation matters"))
                         "</h2></div>"
                         "<p>"
                         (escape-html (case lang
                                        :ar "التأسيس ليس فقط الحصول على ترخيص. الخطر الحقيقي هو تأخر التفعيل: المنصات، التسجيلات، العنوان/الإيجار، والامتثال المستمر. نرشدك لتصبح جاهزًا للتشغيل."
                                        :ur "سیٹ اپ صرف لائسنس حاصل کرنا نہیں ہے۔ اصل تاخیر ایکٹیویشن میں آتی ہے: پورٹلز، رجسٹریشنز، ایڈریس/لیز کے مراحل، اور مسلسل کمپلائنس۔ ہم آپ کو آپریشنل ریڈی تک لے جاتے ہیں۔"
                                        "Company setup isn’t only getting a license. The real risk is delays in activation: portals, registrations, address/lease steps, and ongoing compliance. We guide you to operational-ready."))
                         "</p>"
                         "</section>"
                         "<section><div class='section-title'><h2>"
                         (escape-html (case lang
                                        :ar "ما نحتاجه قبل المكالمة"
                                        :ur "کال سے پہلے ہمیں کیا چاہیے"
                                        "What we need before the call"))
                         "</h2></div>"
                         (bullet-list (case lang
                                        :ar ["الأنشطة (1 إلى 3 أسطر)"
                                             "الملكية (فرد / شركة أم / مساهمون)"
                                             "الجنسية / الإقامة"
                                             "شهر البدء المستهدف"
                                             "حالة الوثائق (جاهز / قيد التجهيز)"
                                             "اللغة المفضلة (EN/AR/UR)"]
                                        :ur ["سرگرمیاں (1 سے 3 لائنیں)"
                                             "ملکیت (انفرادی / پیرنٹ کمپنی / شیئرہولڈرز)"
                                             "قومیت / رہائش"
                                             "ہدف آغاز کا مہینہ"
                                             "دستاویزات کی حالت (تیار / جاری)"
                                             "ترجیحی زبان (EN/AR/UR)"]
                                        ["Activities (1 to 3 lines)"
                                         "Ownership (individual / parent company / shareholders)"
                                         "Nationality / residency"
                                         "Target start month"
                                         "Document readiness (ready / in progress)"
                                         "Preferred language (EN/AR/UR)"]))
                         "</section>"
                         "<section><div class='section-title'><h2>"
                         (escape-html (case lang
                                        :ar "ما الذي تحصل عليه"
                                        :ur "آپ کو کیا ملتا ہے"
                                        "What you get"))
                         "</h2></div>"
                         (bullet-list (case lang
                                        :ar ["المسار الصحيح حسب الأنشطة والملكية"
                                             "قائمة متطلبات مكتوبة"
                                             "تسلسل واضح للخطوات (تأسيس → تفعيل)"
                                             "دعم PRO/GRO مستمر عند الحاجة"]
                                        :ur ["آپ کی سرگرمیوں/ملکیت کے لیے درست راستہ"
                                             "تحریری تقاضوں کی فہرست"
                                             "واضح اسٹیپ سیquence (سیٹ اپ → ایکٹیویشن)"
                                             "ضرورت پر جاری PRO/GRO سپورٹ"]
                                        ["The right route for your activities and ownership"
                                         "A written requirements checklist"
                                         "A clear execution sequence (setup → activation)"
                                         "Optional ongoing PRO/GRO support"]))
                         "</section>"))}

       "/saudi/foreign-investors"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (let [headline (case lang
                              :ar "تأسيس المستثمر الأجنبي"
                              :ur "غیر ملکی سرمایہ کار سیٹ اپ"
                              "Foreign investor setup")
                    strapline (case lang
                               :ar "للمؤسسين والشركات الدولية التي تؤسس أو تتوسع في السعودية."
                               :ur "بین الاقوامی بانیوں اور کمپنیوں کے لیے جو KSA میں سیٹ اپ یا توسیع چاہتے ہیں۔"
                               "For international founders and foreign companies expanding into KSA.")
                    intro (case lang
                            :ar "نؤكد الأهلية والمسار الصحيح (بما في ذلك قيود الملكية الأجنبية)، ثم نحولها إلى قائمة متطلبات وتسلسل تنفيذ واضح."
                            :ur "ہم اہلیت اور درست راستہ کنفرم کرتے ہیں (بشمول غیر ملکی ملکیت کی شرائط)، پھر اسے چیک لسٹ اور واضح ایکزیکیوشن سیquence میں بدلتے ہیں۔"
                            "We confirm route eligibility (including foreign-ownership constraints), then convert it into a written checklist and clear execution sequence.")
                    who-title (case lang :ar "لمن هذا المسار؟" :ur "یہ کس کے لیے ہے؟" "Who this is for")
                    who (case lang
                          :ar ["شركات دولية تؤسس أو تتوسع في السعودية"
                               "مؤسسون من خارج السعودية"
                               "مجموعات تحتاج هيكلًا متوافقًا للتعاقدات والبنك"]
                          :ur ["بین الاقوامی کمپنیاں جو سعودی میں سیٹ اپ یا توسیع چاہتی ہیں"
                               "اوورسیز بانی"
                               "گروپس جنہیں کنٹریکٹس اور بینک کے لیے کمپلائنٹ اسٹرکچر چاہیے"]
                          ["International companies setting up or expanding into Saudi Arabia"
                           "Overseas founders"
                           "Groups that need a compliant structure for contracts and banking"])
                    need-title (case lang :ar "ما نحتاجه منك" :ur "ہمیں آپ سے کیا چاہیے" "What we need from you")
                    need (case lang
                           :ar ["الأنشطة (وصف مختصر)"
                                "الملكية (مساهمون/شركة أم)"
                                "الجنسية / الإقامة"
                                "الهيكل المفضل (شركة/فرع) إن وجد"
                                "شهر البدء المستهدف"
                                "الوثائق المتاحة لديك"]
                           :ur ["سرگرمیاں (مختصر وضاحت)"
                                "ملکیت (شیئرہولڈرز/پیرنٹ کمپنی)"
                                "قومیت / رہائش"
                                "پسندیدہ اسٹرکچر (کمپنی/برانچ) اگر ہو"
                                "ہدف آغاز کا مہینہ"
                                "آپ کے پاس موجود دستاویزات"]
                           ["Activities (short description)"
                            "Ownership (shareholders and structure)"
                            "Nationality / residency"
                            "Preferred structure (company vs branch), if any"
                            "Target start month"
                            "What documents you already have"])
                    get-title (case lang :ar "ما الذي تحصل عليه" :ur "آپ کو کیا ملتا ہے" "What you get")
                    get (case lang
                          :ar ["تأكيد المسار الأنسب للأنشطة والملكية"
                               "قائمة متطلبات مكتوبة قابلة للمراجعة"
                               "خطة تفعيل (منصات/تسجيلات/عنوان) لحالتك"
                               "دعم PRO/GRO مستمر بعد التأسيس عند الحاجة"]
                          :ur ["آپ کی سرگرمیوں/ملکیت کے لیے درست راستہ کنفرم"
                               "تحریری تقاضوں کی چیک لسٹ"
                               "آپ کے کیس کے مطابق ایکٹیویشن پلان"
                               "ضرورت پر سیٹ اپ کے بعد جاری PRO/GRO سپورٹ"]
                          ["The right route confirmed for your activities and ownership"
                           "A reviewable written requirements checklist"
                           "An activation plan (registrations/portals/address) for your case"
                           "Optional ongoing PRO/GRO support after setup"])
                    run-title (case lang :ar "كيف تسير العملية عادة" :ur "عام طور پر پروسس کیسے چلتا ہے" "How it typically runs")
                    run (case lang
                          :ar [{:title "استشارة + تأكيد الأهلية"
                                :desc "نراجع الأنشطة والملكية ونؤكد المسار والمتطلبات."}
                               {:title "مسار الاستثمار/التأسيس"
                                :desc "نجهز الوثائق والتقديمات وننسق مع الجهات ذات العلاقة."}
                               {:title "التفعيل + الجاهزية"
                                :desc "نرتب المنصات والتسجيلات والعنوان لتصبح جاهزًا للتشغيل."}]
                          :ur [{:title "مشاورت + اہلیت"
                                :desc "سرگرمیاں/ملکیت دیکھ کر راستہ اور تقاضے کنفرم کرتے ہیں۔"}
                               {:title "انویسٹمنٹ/سیٹ اپ روٹ"
                                :desc "دستاویزات اور سبمشنز منظم کرتے ہیں۔"}
                               {:title "ایکٹیویشن + ریڈی نس"
                                :desc "پورٹلز/رجسٹریشنز/ایڈریس سیٹ کر کے آپریشنل بناتے ہیں۔"}]
                          [{:title "Consultation + eligibility"
                            :desc "Review activities and ownership, confirm route and requirements."}
                           {:title "Investment/setup route"
                            :desc "Choreograph documents, submissions, and approvals."}
                           {:title "Activation + readiness"
                            :desc "Coordinate portals, registrations, and operational readiness steps."}])
                    note-title (case lang :ar "ملاحظات مهمة" :ur "اہم نوٹس" "Important notes")
                    note (case lang
                           :ar "100% ملكية أجنبية ممكنة في العديد من الأنشطة، لكن بعض الأنشطة مقيدة أو لها شروط. نؤكد التفاصيل قبل التنفيذ. معلومات عامة (ليست استشارة قانونية)."
                           :ur "%100 غیر ملکی ملکیت بہت سی سرگرمیوں میں ممکن ہے، مگر کچھ سرگرمیاں محدود یا شرائط کے ساتھ ہوتی ہیں۔ ہم آگے بڑھنے سے پہلے تفصیل کنفرم کرتے ہیں۔ عمومی معلومات (قانونی مشورہ نہیں)۔"
                           "100% foreign ownership is possible in many activities, but some activities are restricted or have conditions. We confirm details before execution. General information (not legal advice).")
                    next-cta (case lang :ar "احجز استشارة" :ur "مشاورت طے کریں" "Schedule a consultation")]
                (public-page {:title (case lang
                                       :ar "تأسيس المستثمر الأجنبي | دار الوصل"
                                       :ur "غیر ملکی سرمایہ کار | Dar El Wasl"
                                       "Foreign Investor Setup | Dar El Wasl")
                              :description strapline
                              :public-base-url public-base-url
                              :base-path base-path
                              :lang lang
                              :path path
                              :image-path "/logo.jpg"
                              :contact contact}
                           (str (hero-simple headline strapline nil nil)
                                "<section><p>" (escape-html intro) "</p></section>"
                                "<section><div class='section-title'><h2>" (escape-html who-title) "</h2></div>"
                                (bullet-list who)
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html need-title) "</h2></div>"
                                (bullet-list need)
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html get-title) "</h2></div>"
                                (bullet-list get)
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html run-title) "</h2></div>"
                                "<div class='steps steps--light'>"
                                (apply str
                                       (map-indexed
                                        (fn [idx {:keys [title desc]}]
                                          (str "<div class='step'>"
                                               "<div class='step-index'>" (inc idx) "</div>"
                                               "<div><div class='label'>" (escape-html title) "</div>"
                                               "<div class='muted'>" (escape-html desc) "</div></div></div>"))
                                        run))
                                "</div></section>"
                                "<section><div class='section-title'><h2>" (escape-html note-title) "</h2></div>"
                                "<p class='muted'>" (escape-html note) "</p>"
                                "</section>"
                                "<section><div class='section-title'><h2>"
                                (escape-html (case lang :ar "الخطوة التالية" :ur "اگلا قدم" "Next step"))
                                "</h2></div>"
                                "<p class='muted'>" (escape-html (case lang
                                                              :ar "أرسل تفاصيلك وسنرد عليك بقائمة متطلبات وخطوتك التالية."
                                                              :ur "اپنی تفصیلات بھیجیں اور ہم تقاضوں کی چیک لسٹ اور اگلا قدم بھیجیں گے۔"
                                                              "Send your details and we’ll reply with your requirements checklist and next step."))
                                "</p>"
                                "<a class='cta primary' href='" (escape-html (href "/contact#consultation")) "'>" (escape-html next-cta) "</a>"
                                "</section>")))}

       "/saudi/entrepreneur"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (let [headline (case lang
                              :ar "مسار ريادي"
                              :ur "انٹرپرینیور سیٹ اپ"
                              "Entrepreneur setup")
                    strapline (case lang
                               :ar "للشركات الناشئة والمؤسسين عبر مسار ريادي مع الوثائق الداعمة الصحيحة."
                               :ur "بانیوں اور اسٹارٹ اپس کے لیے درست سپورٹ ڈاکومنٹس کے ساتھ۔"
                               "For founder-led startups with the right support documentation.")
                    who-title (case lang :ar "لمن هذا؟" :ur "یہ کس کے لیے ہے؟" "Who this is for")
                    who (case lang
                          :ar ["مؤسسون وشركات ناشئة عبر مسار ريادي"
                               "فرق تحتاج تأسيسًا منظمًا بدون مفاجآت في التفعيل"
                               "شركات تركز على سرعة التنفيذ مع الامتثال"]
                          :ur ["بانی اور اسٹارٹ اپس جو انٹرپرینیور اختیار استعمال کرتے ہیں"
                               "ٹیمیں جو ایکٹیویشن میں حیرت کے بغیر منظم سیٹ اپ چاہتی ہیں"
                               "کمپلائنس کے ساتھ تیز ایگزیکیوشن"]
                          ["Founders and startups using an entrepreneur option"
                           "Teams that want structured activation without surprises"
                           "Companies prioritizing fast execution with compliance"])
                    need-title (case lang :ar "ما نحتاجه منك" :ur "ہمیں آپ سے کیا چاہیے" "What we need from you")
                    need (case lang
                           :ar ["وصف الأنشطة"
                                "الملكية/المساهمون"
                                "الجنسية / الإقامة"
                                "نموذج العمل/المنتج (مستوى عام)"
                                "شهر البدء المستهدف"
                                "ما هي الوثائق المتاحة لديك"]
                           :ur ["سرگرمیوں کی وضاحت"
                                "ملکیت/شیئرہولڈرز"
                                "قومیت / رہائش"
                                "بزنس ماڈل/پروڈکٹ (ہائی لیول)"
                                "ہدف آغاز کا مہینہ"
                                "آپ کے پاس موجود دستاویزات"]
                           ["Activities description"
                            "Ownership/shareholders"
                            "Nationality / residency"
                            "Business model / product (high level)"
                            "Target start month"
                            "What documents you already have"])
                    get-title (case lang :ar "ما الذي تحصل عليه" :ur "آپ کو کیا ملتا ہے" "What you get")
                    get (case lang
                          :ar ["قائمة متطلبات مكتوبة"
                               "تحديد المسار والوثائق الداعمة المطلوبة"
                               "تسلسل خطوات واضح (تأسيس → تفعيل)"
                               "تنسيق تنفيذ هادئ وموثق"]
                          :ur ["تحریری تقاضوں کی چیک لسٹ"
                               "راستہ اور ضروری سپورٹ ڈاکومنٹس کی وضاحت"
                               "واضح اسٹیپ سیquence (سیٹ اپ → ایکٹیویشن)"
                               "پرسکون اور دستاویزی ایگزیکیوشن"]
                          ["A written requirements checklist"
                           "A clear view of route fit and required supporting documentation"
                           "A clear step sequence (setup → activation)"
                           "Calm, documented execution coordination"])
                    run-title (case lang :ar "كيف تسير العملية عادة" :ur "عام طور پر پروسس کیسے چلتا ہے" "How it typically runs")
                    run (case lang
                          :ar [{:title "استشارة + تأكيد المسار"
                                :desc "نراجع الأنشطة والملكية ونحدد المسار والمتطلبات."}
                               {:title "الوثائق + التقديمات"
                                :desc "نجهز الوثائق والترجمات والتقديمات حسب الحالة."}
                               {:title "التفعيل + الجاهزية"
                                :desc "نرتب المنصات والتسجيلات والخطوات التشغيلية."}]
                          :ur [{:title "مشاورت + راستہ"
                                :desc "سرگرمیاں/ملکیت دیکھ کر راستہ اور تقاضے کنفرم کرتے ہیں۔"}
                               {:title "دستاویزات + سبمشنز"
                                :desc "ڈاکس/ترجمہ/سبمشنز کو کیس کے مطابق منظم کرتے ہیں۔"}
                               {:title "ایکٹیویشن + ریڈی نس"
                                :desc "پورٹلز/رجسٹریشنز اور آپریشنل اسٹیپس سیٹ کرتے ہیں۔"}]
                          [{:title "Consultation + route confirmation"
                            :desc "Review activities and ownership, confirm fit and requirements."}
                           {:title "Documents + submissions"
                            :desc "Prepare documents, translations, and submissions case-by-case."}
                           {:title "Activation + readiness"
                            :desc "Coordinate portals, registrations, and operational readiness."}])
                    next-cta (case lang :ar "احجز استشارة" :ur "مشاورت طے کریں" "Schedule a consultation")]
                (public-page {:title (case lang
                                       :ar "مسار ريادي | دار الوصل"
                                       :ur "انٹرپرینیور | Dar El Wasl"
                                       "Entrepreneur setup | Dar El Wasl")
                              :description strapline
                              :public-base-url public-base-url
                              :base-path base-path
                              :lang lang
                              :path path
                              :image-path "/logo.jpg"
                              :contact contact}
                           (str (hero-simple headline strapline nil nil)
                                "<section><div class='section-title'><h2>" (escape-html who-title) "</h2></div>"
                                (bullet-list who)
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html need-title) "</h2></div>"
                                (bullet-list need)
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html get-title) "</h2></div>"
                                (bullet-list get)
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html run-title) "</h2></div>"
                                "<div class='steps steps--light'>"
                                (apply str
                                       (map-indexed
                                        (fn [idx {:keys [title desc]}]
                                          (str "<div class='step'>"
                                               "<div class='step-index'>" (inc idx) "</div>"
                                               "<div><div class='label'>" (escape-html title) "</div>"
                                               "<div class='muted'>" (escape-html desc) "</div></div></div>"))
                                        run))
                                "</div></section>"
                                "<section><div class='section-title'><h2>"
                                (escape-html (case lang :ar "الخطوة التالية" :ur "اگلا قدم" "Next step"))
                                "</h2></div>"
                                "<p class='muted'>" (escape-html (case lang
                                                              :ar "أرسل تفاصيلك وسنرد عليك بقائمة متطلبات وخطوتك التالية."
                                                              :ur "اپنی تفصیلات بھیجیں اور ہم تقاضوں کی چیک لسٹ اور اگلا قدم بھیجیں گے۔"
                                                              "Send your details and we’ll reply with your requirements checklist and next step."))
                                "</p>"
                                "<a class='cta primary' href='" (escape-html (href "/contact#consultation")) "'>" (escape-html next-cta) "</a>"
                                "</section>")))}

       "/saudi/gcc"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (let [headline (case lang
                              :ar "مسار مواطني الخليج"
                              :ur "GCC نیشنل سیٹ اپ"
                              "GCC nationals setup")
                    strapline (case lang
                               :ar "لمواطني الخليج الذين يريدون تأسيسًا سريعًا ومتوافقًا."
                               :ur "خلیجی شہریوں کے لیے تیز اور مطابق سیٹ اپ۔"
                               "For GCC nationals who want a fast, compliant setup.")
                    who-title (case lang :ar "لمن هذا؟" :ur "یہ کس کے لیے ہے؟" "Who this is for")
                    who (case lang
                          :ar ["مواطنو دول الخليج (GCC) الذين يثبتون كيانًا في السعودية"
                               "مؤسسون يريدون مسارًا سريعًا مع تفعيل منظم"
                               "حالات تحتاج استمرارية PRO/GRO بعد التأسيس"]
                          :ur ["GCC شہری جو سعودی ادارہ قائم کرنا چاہتے ہیں"
                               "بانی جو تیز راستہ اور منظم ایکٹیویشن چاہتے ہیں"
                               "سیٹ اپ کے بعد جاری PRO/GRO کی ضرورت والے کیسز"]
                          ["GCC nationals formalizing a Saudi entity"
                           "Founders who want a fast path with structured activation"
                           "Setups that need PRO/GRO continuity after formation"])
                    need-title (case lang :ar "ما نحتاجه منك" :ur "ہمیں آپ سے کیا چاہیے" "What we need from you")
                    need (case lang
                           :ar ["الأنشطة (وصف مختصر)"
                                "الملكية/الشركاء"
                                "الجنسية / الإقامة"
                                "شهر البدء المستهدف"
                                "حالة الوثائق"
                                "تفاصيل التواصل واللغة"]
                           :ur ["سرگرمیاں (مختصر وضاحت)"
                                "ملکیت/شراکت"
                                "قومیت / رہائش"
                                "ہدف آغاز کا مہینہ"
                                "دستاویزات کی حالت"
                                "رابطہ تفصیلات اور زبان"]
                           ["Activities (short description)"
                            "Ownership/partners"
                            "Nationality / residency"
                            "Target start month"
                            "Document readiness"
                            "Contact details and language preference"])
                    get-title (case lang :ar "ما الذي تحصل عليه" :ur "آپ کو کیا ملتا ہے" "What you get")
                    get (case lang
                          :ar ["قائمة متطلبات مكتوبة"
                               "تسلسل خطوات واضح (تأسيس → تفعيل)"
                               "تنسيق المنصات والتسجيلات"
                               "دعم PRO/GRO مستمر عند الحاجة"]
                          :ur ["تحریری تقاضوں کی فہرست"
                               "واضح اسٹیپ سیquence (سیٹ اپ → ایکٹیویشن)"
                               "پورٹلز/رجسٹریشنز کی کوآرڈینیشن"
                               "ضرورت پر جاری PRO/GRO سپورٹ"]
                          ["A written requirements checklist"
                           "A clear step sequence (setup → activation)"
                           "Coordination of portals and registrations"
                           "Optional ongoing PRO/GRO support"])
                    run-title (case lang :ar "كيف تسير العملية عادة" :ur "عام طور پر پروسس کیسے چلتا ہے" "How it typically runs")
                    run (case lang
                          :ar [{:title "استشارة + تحديد المسار"
                                :desc "نراجع الأنشطة والملكية ونحدد المتطلبات."}
                               {:title "التأسيس والترخيص"
                                :desc "نجهز الوثائق ونقدم الطلبات خطوة بخطوة."}
                               {:title "التفعيل + الجاهزية"
                                :desc "نرتب المنصات والتسجيلات وخطوات التشغيل."}]
                          :ur [{:title "مشاورت + راستہ"
                                :desc "سرگرمیاں/ملکیت دیکھ کر تقاضے کنفرم کرتے ہیں۔"}
                               {:title "سیٹ اپ + لائسنسنگ"
                                :desc "ڈاکس اور سبمشنز مرحلہ وار منظم کرتے ہیں۔"}
                               {:title "ایکٹیویشن + ریڈی نس"
                                :desc "پورٹلز/رجسٹریشنز اور آپریشنل اسٹیپس سیٹ کرتے ہیں۔"}]
                          [{:title "Consultation + route"
                            :desc "Review activities and ownership, confirm requirements."}
                           {:title "Formation + licensing"
                            :desc "Prepare documents and submissions step-by-step."}
                           {:title "Activation + readiness"
                            :desc "Coordinate portals, registrations, and operational steps."}])
                    next-cta (case lang :ar "احجز استشارة" :ur "مشاورت طے کریں" "Schedule a consultation")]
                (public-page {:title (case lang
                                       :ar "مسار مواطني الخليج | دار الوصل"
                                       :ur "GCC نیشنل | Dar El Wasl"
                                       "GCC Nationals setup | Dar El Wasl")
                              :description strapline
                              :public-base-url public-base-url
                              :base-path base-path
                              :lang lang
                              :path path
                              :image-path "/logo.jpg"
                              :contact contact}
                           (str (hero-simple headline strapline nil nil)
                                "<section><div class='section-title'><h2>" (escape-html who-title) "</h2></div>"
                                (bullet-list who)
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html need-title) "</h2></div>"
                                (bullet-list need)
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html get-title) "</h2></div>"
                                (bullet-list get)
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html run-title) "</h2></div>"
                                "<div class='steps steps--light'>"
                                (apply str
                                       (map-indexed
                                        (fn [idx {:keys [title desc]}]
                                          (str "<div class='step'>"
                                               "<div class='step-index'>" (inc idx) "</div>"
                                               "<div><div class='label'>" (escape-html title) "</div>"
                                               "<div class='muted'>" (escape-html desc) "</div></div></div>"))
                                        run))
                                "</div></section>"
                                "<section><div class='section-title'><h2>"
                                (escape-html (case lang :ar "الخطوة التالية" :ur "اگلا قدم" "Next step"))
                                "</h2></div>"
                                "<p class='muted'>" (escape-html (case lang
                                                              :ar "أرسل تفاصيلك وسنرد عليك بقائمة متطلبات وخطوتك التالية."
                                                              :ur "اپنی تفصیلات بھیجیں اور ہم تقاضوں کی چیک لسٹ اور اگلا قدم بھیجیں گے۔"
                                                              "Send your details and we’ll reply with your requirements checklist and next step."))
                                "</p>"
                                "<a class='cta primary' href='" (escape-html (href "/contact#consultation")) "'>" (escape-html next-cta) "</a>"
                                "</section>")))}

       "/saudi/pro-services"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (let [headline (case lang
                              :ar "خدمات PRO / GRO"
                              :ur "PRO / GRO سروسز"
                              "PRO / GRO services")
                    strapline (case lang
                               :ar "عمليات مستمرة: تجديدات، منصات حكومية، ومهام امتثال بدون مفاجآت."
                               :ur "جاری آپریشنز: تجدیدات، حکومتی پورٹلز، اور کمپلائنس کام بغیر حیرت کے۔"
                               "Ongoing operations: renewals, portals, and government tasks without surprises.")
                    intro (case lang
                            :ar "بعد التأسيس، العمل لا يتوقف. ندير الامتثال الدوري ومعاملات المنصات بهدوء وبتوثيق."
                            :ur "سیٹ اپ کے بعد کام جاری رہتا ہے۔ ہم بار بار آنے والی کمپلائنس اور پورٹل ٹرانزیکشنز منظم اور دستاویزی انداز میں کرتے ہیں۔"
                            "After setup, work continues. We manage recurring compliance and portal transactions in a calm, documented way.")
                    handle-title (case lang :ar "ما الذي نتعامل معه" :ur "ہم کیا سنبھالتے ہیں" "What we handle")
                    handle (case lang
                             :ar [{:title "امتثال الشركة"
                                   :description "تجديدات، تحديث بيانات، تذكير بالمواعيد، وتوثيق مستمر."
                                   :href (href "/contact#consultation")
                                   :icon (icon-hex)}
                                  {:title "المنصات الحكومية"
                                   :description "تنسيق التسجيلات، المتابعات، وتحديث البيانات حسب الحاجة."
                                   :href (href "/contact#consultation")
                                   :icon (icon-globe)}
                                  {:title "الموظفون والعمليات"
                                   :description "تأمين صحي، تسجيلات الموظفين، وخطوات تشغيلية حسب الحالة."
                                   :href (href "/contact#consultation")
                                   :icon (icon-mountain)}
                                  {:title "التغييرات والتجديدات"
                                   :description "العناوين/الأنشطة/الملكية، وتجديدات الرخص والعضويات."
                                   :href (href "/contact#consultation")
                                   :icon (icon-hex)}]
                             :ur [{:title "کمپنی کمپلائنس"
                                   :description "تجدیدات، ڈیٹا اپڈیٹس، ڈیڈ لائن ریمائنڈرز، اور دستاویزی ورک۔"
                                   :href (href "/contact#consultation")
                                   :icon (icon-hex)}
                                  {:title "حکومتی پورٹلز"
                                   :description "رجسٹریشنز، فالو اپ، اور ڈیٹا اپڈیٹس کیس کے مطابق۔"
                                   :href (href "/contact#consultation")
                                   :icon (icon-globe)}
                                  {:title "ملازمین + آپریشنز"
                                   :description "ہیلتھ انشورنس، رجسٹریشنز، اور آپریشنل ریکوائرمنٹس کیس کے مطابق۔"
                                   :href (href "/contact#consultation")
                                   :icon (icon-mountain)}
                                  {:title "تبدیلیاں + تجدیدات"
                                   :description "ایڈریس/سرگرمیاں/ملکیت، لائسنس اور ممبرشپس کی تجدیدات۔"
                                   :href (href "/contact#consultation")
                                   :icon (icon-hex)}]
                             [{:title "Company compliance"
                               :description "Renewals, entity updates, deadline reminders, and documentation."
                               :href (href "/contact#consultation")
                               :icon (icon-hex)}
                              {:title "Government portals"
                               :description "Coordinate registrations, follow-ups, and data updates."
                               :href (href "/contact#consultation")
                               :icon (icon-globe)}
                              {:title "Employees & operations"
                               :description "Health insurance, employee registrations, and operational requirements (case-by-case)."
                               :href (href "/contact#consultation")
                               :icon (icon-mountain)}
                              {:title "Changes & renewals"
                               :description "Address/activity/ownership changes, plus license/membership renewals."
                               :href (href "/contact#consultation")
                               :icon (icon-hex)}])
                    work-title (case lang :ar "كيف نعمل" :ur "ہم کیسے کام کرتے ہیں" "How we work")
                    work (case lang
                           :ar [{:title "جمع الإدخالات + الوصول"
                                 :desc "نجمع الحسابات والصلاحيات والوثائق المطلوبة لكل منصة."}
                                {:title "تقويم المهام"
                                 :desc "نضع التواريخ والتذكيرات في تقويم واضح."}
                                {:title "تنفيذ + تأكيد"
                                 :desc "ننفذ المعاملات ونشارك التأكيدات والنتائج."}]
                           :ur [{:title "ان پٹس + ایکسس"
                                 :desc "پورٹل اکاؤنٹس/اتھارائزیشنز اور ڈاکس جمع کرتے ہیں۔"}
                                {:title "ٹاسک کیلنڈر"
                                 :desc "ڈیڈ لائنز کو واضح کیلنڈر میں رکھتے ہیں۔"}
                                {:title "ایگزیکیوشن + کنفرم"
                                 :desc "کام مکمل کر کے کنفرمیشنز اور آؤٹ کم شیئر کرتے ہیں۔"}]
                           [{:title "Intake + access"
                             :desc "Collect portal accounts/authorizations and required documents."}
                            {:title "Task calendar"
                             :desc "Put renewals and deadlines into a clear calendar with reminders."}
                            {:title "Execute + confirm"
                             :desc "Execute tasks and share confirmations and outcomes."}])
                    need-title (case lang :ar "ما نحتاجه منك" :ur "ہمیں آپ سے کیا چاہیے" "What we need from you")
                    need (case lang
                           :ar ["الكيان والأنشطة"
                                "جهة التواصل واللغة المفضلة"
                                "وصول/تفويضات المنصات الحكومية (حسب الحالة)"
                                "نطاق ما تريد إدارته"]
                           :ur ["ادارہ اور سرگرمیاں"
                                "کانٹیکٹ اور ترجیحی زبان"
                                "پورٹل ایکسس/اتھارائزیشنز (کیس کے مطابق)"
                                "آپ کیا مینیج کروانا چاہتے ہیں (اسکوپ)"]
                           ["Entity and activities"
                            "Contacts and preferred language"
                            "Portal access/authorizations (case-by-case)"
                            "Scope of what you want us to manage"])
                    notes-title (case lang :ar "ملاحظات" :ur "نوٹس" "Notes")
                    notes (case lang
                            :ar "نطاق PRO/GRO يختلف حسب الأنشطة وحجم العمل والمنصات المستخدمة. معلومات عامة."
                            :ur "PRO/GRO کا اسکوپ سرگرمی، سائز اور پورٹلز کے مطابق بدلتا ہے۔ عمومی معلومات۔"
                            "PRO/GRO scope varies by activity, size, and portals used. General information.")
                    next-cta (case lang :ar "تواصل" :ur "رابطہ" "Contact")]
                (public-page {:title (case lang
                                       :ar "خدمات PRO / GRO | دار الوصل"
                                       :ur "PRO / GRO | Dar El Wasl"
                                       "PRO / GRO services | Dar El Wasl")
                              :description strapline
                              :public-base-url public-base-url
                              :base-path base-path
                              :lang lang
                              :path path
                              :image-path "/logo.jpg"
                              :contact contact}
                           (str (hero-simple headline strapline nil nil)
                                "<section><p>" (escape-html intro) "</p></section>"
                                "<section><div class='section-title'><h2>" (escape-html handle-title) "</h2></div>"
                                "<div class='card-grid'>"
                                (apply str (map card-icon handle))
                                "</div></section>"
                                "<section><div class='section-title'><h2>" (escape-html work-title) "</h2></div>"
                                "<div class='steps steps--light'>"
                                (apply str
                                       (map-indexed
                                        (fn [idx {:keys [title desc]}]
                                          (str "<div class='step'>"
                                               "<div class='step-index'>" (inc idx) "</div>"
                                               "<div><div class='label'>" (escape-html title) "</div>"
                                               "<div class='muted'>" (escape-html desc) "</div></div></div>"))
                                        work))
                                "</div></section>"
                                "<section><div class='section-title'><h2>" (escape-html need-title) "</h2></div>"
                                (bullet-list need)
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html notes-title) "</h2></div>"
                                "<p class='muted'>" (escape-html notes) "</p>"
                                "</section>"
                                "<section><div class='section-title'><h2>"
                                (escape-html (case lang :ar "الخطوة التالية" :ur "اگلا قدم" "Next step"))
                                "</h2></div>"
                                "<p class='muted'>" (escape-html (case lang
                                                              :ar "أرسل تفاصيلك وسنقترح نطاقًا واضحًا وطريقة عمل."
                                                              :ur "تفصیلات بھیجیں اور ہم واضح اسکوپ اور طریقہ کار تجویز کریں گے۔"
                                                              "Send your details and we’ll propose scope and how we work together."))
                                "</p>"
                                "<a class='cta primary' href='" (escape-html (href "/contact#consultation")) "'>" (escape-html next-cta) "</a>"
                                "</section>")))}

       "/saudi/activation"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (let [headline (case lang
                              :ar "التفعيل والامتثال"
                              :ur "ایکٹیویشن اور کمپلائنس"
                              "Activation & compliance")
                    strapline (case lang
                               :ar "إصدار الرخصة بداية فقط. التفعيل هو ما يجعلك جاهزًا للتشغيل."
                               :ur "لائسنس صرف آغاز ہے۔ ایکٹیویشن ہی آپ کو آپریشنل بناتی ہے۔"
                               "License issuance is only the start. Activation makes you operational.")
                    intro (case lang
                            :ar "معظم التأخير يحدث في التفعيل: التسجيلات، العنوان، المنصات، والامتثال المستمر. نرتب التسلسل ونوثق التنفيذ."
                            :ur "زیادہ تر تاخیر ایکٹیویشن میں ہوتی ہے: رجسٹریشنز، ایڈریس، پورٹلز، اور جاری کمپلائنس۔ ہم سیquence سیٹ کرتے ہیں اور ایگزیکیوشن دستاویز کرتے ہیں۔"
                            "Most delays happen in activation: registrations, address steps, portals, and ongoing compliance. We set the sequence and track it.")
                    checklist-title (case lang
                                     :ar "قائمة تفعيل نموذجية (حسب الحالة)"
                                     :ur "عام ایکٹیویشن چیک لسٹ (کیس کے مطابق)"
                                     "Typical activation checklist (case-by-case)")
                    checklist (case lang
                                :ar ["العنوان الوطني (SPL) وتحديث بيانات التواصل"
                                     "ZATCA (زكاة/ضريبة) حسب الحالة"
                                     "GOSI إذا كان لديك موظفون"
                                     "عضوية الغرفة التجارية (إن لزم)"
                                     "إعداد/تحديث المنصات الحكومية"
                                     "الجاهزية البنكية والفوترة"
                                     "تذكيرات الامتثال المستمر (تجديدات/التزامات)"]
                                :ur ["National Address (SPL) اور کانٹیکٹ اپڈیٹس"
                                     "ZATCA (زکاة/ٹیکس) کیس کے مطابق"
                                     "GOSI اگر ملازمین ہوں"
                                     "Chamber membership (اگر درکار ہو)"
                                     "حکومتی پورٹلز سیٹ اپ/اپڈیٹس"
                                     "بینکنگ اور اِن وائسنگ ریڈی نس"
                                     "جاری کمپلائنس ریمائنڈرز (تجدیدات/فائلنگز)"]
                                ["National Address (SPL) and contact details"
                                 "ZATCA (zakat/tax), if applicable"
                                 "GOSI registration if you have employees"
                                 "Chamber of Commerce membership (as required)"
                                 "Government portals setup/updates"
                                 "Banking and invoicing readiness"
                                 "Ongoing compliance reminders (renewals/filings)"])
                    delay-title (case lang
                                 :ar "أسباب شائعة للتأخير"
                                 :ur "تاخیر کی عام وجوہات"
                                 "Common causes of delay")
                    delays (case lang
                             :ar ["وصف نشاط غير واضح أو غير متطابق"
                                  "نقص صلاحيات/تفويضات المنصات"
                                  "العنوان/الإيجار لم يكتمل عند الحاجة"
                                  "إدخالات غير مكتملة (الملكية/البيانات)"]
                             :ur ["سرگرمیوں کی وضاحت غیر واضح یا mismatch"
                                  "پورٹل اتھارائزیشنز کی کمی"
                                  "ایڈریس/لیز اسٹیپس مکمل نہ ہونا"
                                  "ان پٹس نامکمل (ملکیت/تفصیلات)"]
                             ["Unclear or mismatched activity description"
                              "Missing portal access/authorizations"
                              "Address/lease steps not completed when needed"
                              "Inconsistent inputs (ownership/details)"])
                    get-title (case lang :ar "ما الذي تحصل عليه" :ur "آپ کو کیا ملتا ہے" "What you get")
                    get (case lang
                          :ar ["قائمة تفعيل مكتوبة مع تواريخ متوقعة"
                               "تسلسل خطوات واضح لتقليل التأخير"
                               "تنفيذ موثق وتأكيدات"
                               "دعم PRO/GRO مستمر عند الحاجة"]
                          :ur ["تحریری ایکٹیویشن چیک لسٹ (متوقع تاریخوں کے ساتھ)"
                               "واضح سیquence جو تاخیر کم کرے"
                               "دستاویزی ایگزیکیوشن اور کنفرمیشنز"
                               "ضرورت پر جاری PRO/GRO سپورٹ"]
                          ["A written activation checklist with timing"
                           "A clear sequence of steps to reduce delays"
                           "Documented execution and confirmations"
                           "Optional ongoing PRO/GRO support"])
                    note (case lang
                           :ar "معلومات عامة. التفاصيل تختلف حسب الأنشطة ونوع الكيان."
                           :ur "عمومی معلومات۔ تفصیل سرگرمی اور انٹیٹی کے مطابق بدلتی ہے۔"
                           "General information. Details vary by activity and entity type.")
                    next-cta (case lang :ar "احجز استشارة" :ur "مشاورت طے کریں" "Schedule a consultation")]
                (public-page {:title (case lang
                                       :ar "التفعيل والامتثال | دار الوصل"
                                       :ur "ایکٹیویشن | Dar El Wasl"
                                       "Activation & compliance | Dar El Wasl")
                              :description strapline
                              :public-base-url public-base-url
                              :base-path base-path
                              :lang lang
                              :path path
                              :image-path "/logo.jpg"
                              :contact contact}
                           (str (hero-simple headline strapline nil nil)
                                "<section><p>" (escape-html intro) "</p></section>"
                                "<section><div class='section-title'><h2>" (escape-html checklist-title) "</h2></div>"
                                (bullet-list checklist)
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html delay-title) "</h2></div>"
                                (bullet-list delays)
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html get-title) "</h2></div>"
                                (bullet-list get)
                                "<p class='muted'>" (escape-html note) "</p>"
                                "</section>"
                                "<section><div class='section-title'><h2>"
                                (escape-html (case lang :ar "الخطوة التالية" :ur "اگلا قدم" "Next step"))
                                "</h2></div>"
                                "<p class='muted'>" (escape-html (case lang
                                                              :ar "أرسل تفاصيلك وسنحدد لك تسلسل التفعيل والمتطلبات."
                                                              :ur "تفصیلات بھیجیں اور ہم ایکٹیویشن سیquence اور تقاضے واضح کریں گے۔"
                                                              "Send your details and we’ll define your activation sequence and requirements."))
                                "</p>"
                                "<a class='cta primary' href='" (escape-html (href "/contact#consultation")) "'>" (escape-html next-cta) "</a>"
                                "</section>")))}

       "/uk"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (let [headline (case lang
                              :ar "ابدأ في المملكة المتحدة"
                              :ur "یو کے میں آغاز"
                              "Start in the UK")
                    strapline (case lang
                               :ar "تأسيس شركة UK Ltd، مع خطوات جاهزية تقلل المفاجآت."
                               :ur "UK Ltd تشکیل، اور وہ ریڈی نس اسٹیپس جو حیرت کم کرتے ہیں۔"
                               "UK Ltd formation, plus the readiness steps that prevent surprises.")
                    intro (case lang
                            :ar "نؤسس الشركة، ثم نسلمك قائمة جاهزية عملية للبنك والمحاسبة والامتثال الأساسي."
                            :ur "ہم کمپنی بناتے ہیں، پھر بینکنگ، اکاؤنٹنگ اور بنیادی کمپلائنس کے لیے عملی چیک لسٹ دیتے ہیں۔"
                            "We form the company, then hand you a practical readiness checklist for banking, accounting, and baseline compliance.")
                    help-title (case lang
                                 :ar "ما الذي نساعد فيه"
                                 :ur "ہم کیا مدد کرتے ہیں"
                                 "What we help with")
                    help (case lang
                           :ar [{:title "تأسيس الشركة"
                                 :desc "نجهز الإدخالات ونقدم الطلبات ونشاركك التأكيد بعد التأسيس."
                                 :icon (icon-globe)}
                                {:title "حزمة جاهزية"
                                 :desc "قائمة خطوات واضحة للضرائب، المحاسبة، والبنك بعد التأسيس."
                                 :icon (icon-mountain)}
                                {:title "تنسيق الخطوات التالية"
                                 :desc "ننسق معك أو مع مستشارك لما بعد التأسيس وفق وضعك."
                                 :icon (icon-hex)}]
                           :ur [{:title "کمپنی تشکیل"
                                 :desc "ہم ان پٹس منظم کرتے ہیں، فائلنگ کرتے ہیں، اور کنفرمیشن شیئر کرتے ہیں۔"
                                 :icon (icon-globe)}
                                {:title "ریڈی نس ہینڈ آف"
                                 :desc "ٹیکس، اکاؤنٹنگ اور بینک کے لیے واضح نیکسٹ اسٹیپ چیک لسٹ۔"
                                 :icon (icon-mountain)}
                                {:title "نیکسٹ اسٹیپ کوآرڈینیشن"
                                 :desc "آپ کی صورتحال کے مطابق بعد از تشکیل اگلے مراحل کوآرڈینیٹ کرتے ہیں۔"
                                 :icon (icon-hex)}]
                           [{:title "Company formation"
                             :desc "Prepare inputs, file with Companies House, and share your confirmation."
                             :icon (icon-globe)}
                            {:title "Readiness handoff"
                             :desc "A clear next-step checklist for tax, accounting, and banking."
                             :icon (icon-mountain)}
                            {:title "Next-step coordination"
                             :desc "Coordinate with you (or your adviser) for the follow-on steps that match your situation."
                             :icon (icon-hex)}])
                    need-title (case lang
                                 :ar "ما نحتاجه منك"
                                 :ur "ہمیں آپ سے کیا چاہیے"
                                 "What we need from you")
                    need (case lang
                           :ar ["خيارات اسم الشركة"
                                "المديرون والمساهمون ونسبة الملكية"
                                "تفاصيل PSC (الأشخاص ذوو السيطرة المهمة) إن وجدت"
                                "عنوان المكتب المسجل"
                                "نشاط الشركة / رموز SIC"
                                "تفاصيل التواصل وتفضيل اللغة"]
                           :ur ["کمپنی نام کے آپشنز"
                                "ڈائریکٹرز، شیئرہولڈرز، اور ملکیت کی تقسیم"
                                "PSC تفصیلات (اگر لاگو ہو)"
                                "Registered office address"
                                "Business activity / SIC codes"
                                "رابطہ تفصیلات اور زبان کی ترجیح"]
                           ["Company name options"
                            "Directors, shareholders, and ownership split"
                            "PSC details (persons with significant control), if applicable"
                            "Registered office address"
                            "Business activity / SIC codes"
                            "Contact details and language preference"])
                    run-title (case lang
                                :ar "كيف تسير العملية عادة"
                                :ur "عام طور پر پروسس کیسے چلتا ہے"
                                "How it typically runs")
                    run (case lang
                          :ar [{:title "استشارة قصيرة"
                                :desc "10 إلى 20 دقيقة لتأكيد الهيكل والإدخالات والخطوة الأولى."}
                               {:title "تقديم التأسيس"
                                :desc "بمجرد اكتمال الإدخالات، نقدم الطلب ونشاركك التأكيد."}
                               {:title "جاهزية ما بعد التأسيس"
                                :desc "قائمة جاهزية عملية للضرائب والمحاسبة والبنك والالتزامات الأساسية."}]
                          :ur [{:title "مختصر مشاورہ"
                                :desc "10 سے 20 منٹ میں اسٹرکچر اور ان پٹس کنفرم کرتے ہیں۔"}
                               {:title "انکارپوریشن فائلنگ"
                                :desc "ان پٹس مکمل ہونے پر فائل کرتے ہیں اور کنفرمیشن شیئر کرتے ہیں۔"}
                               {:title "بعد از تشکیل ریڈی نس"
                                :desc "ٹیکس، بینکنگ، اکاؤنٹنگ اور جاری فائلنگ کے لیے عملی چیک لسٹ۔"}]
                          [{:title "Short consultation"
                            :desc "10 to 20 minutes to confirm structure, required inputs, and the first step."}
                           {:title "Incorporation filing"
                            :desc "Once inputs are complete, we file and share the confirmation."}
                           {:title "Post-formation readiness"
                            :desc "A practical checklist for tax, banking, accounting, and ongoing filings."}])
                    obligations-title (case lang
                                       :ar "الالتزامات المستمرة (مستوى عام)"
                                       :ur "بنیادی جاری ذمہ داریاں"
                                       "Key ongoing obligations (high level)")
                    obligations (case lang
                                  :ar ["قد تتطلب بعض الإجراءات التحقق من الهوية وفق متطلبات Companies House."
                                       "تقديم Confirmation Statement سنويًا (يمكن التقديم حتى 14 يومًا بعد تاريخ الاستحقاق)."
                                       "الحسابات السنوية للشركات الخاصة عادة خلال 9 أشهر من تاريخ مرجع المحاسبة."
                                       "إبلاغ HMRC بنشاط الشركة خلال 3 أشهر من بدء التداول (Corporation Tax)."
                                       "تقديم الإقرارات ودفع الضرائب وفق مواعيد HMRC."]
                                  :ur ["کچھ مراحل میں Companies House کی شناخت کی تصدیق درکار ہو سکتی ہے۔"
                                       "Confirmation Statement ہر 12 ماہ میں (ڈیڈ لائن کے بعد 14 دن تک فائل ہو سکتا ہے)۔"
                                       "پرائیویٹ کمپنی اکاؤنٹس عموماً accounting reference date کے 9 ماہ کے اندر۔"
                                       "HMRC کو تجارت شروع کرنے کے 3 ماہ کے اندر مطلع کریں (Corporation Tax)۔"
                                       "ریٹرنز اور ادائیگیاں HMRC ڈیڈ لائنز کے مطابق۔"]
                                  ["Identity verification may be required for some filings (Companies House)."
                                   "Confirmation Statement every 12 months (can be filed up to 14 days after the due date)."
                                   "Annual accounts for private companies are typically due within 9 months of the accounting reference date."
                                   "Tell HMRC the company is active within 3 months of starting to trade (Corporation Tax)."
                                   "File returns and pay taxes on time per HMRC deadlines."])
                    disclaimer (case lang
                                 :ar "معلومات عامة. نؤكد التفاصيل حسب حالتك خلال الاستشارة."
                                 :ur "یہ عمومی معلومات ہے۔ ہم آپ کے کیس کے مطابق تفصیل مشاورت میں کنفرم کرتے ہیں۔"
                                 "General information. We confirm details for your case during the consultation.")
                    next-title (case lang
                                :ar "الخطوة التالية"
                                :ur "اگلا قدم"
                                "Next step")
                    next-desc (case lang
                               :ar "أرسل تفاصيلك وسنرد عليك بقائمة متطلبات وخطوتك التالية."
                               :ur "اپنی تفصیلات بھیجیں اور ہم تقاضوں کی چیک لسٹ اور اگلا قدم بھیجیں گے۔"
                               "Send your details and we’ll reply with your requirements checklist and next step.")
                    next-cta (case lang
                               :ar "احجز استشارة"
                               :ur "مشاورت طے کریں"
                               "Schedule a consultation")]
                (public-page {:title (case lang
                                       :ar "ابدأ في المملكة المتحدة | دار الوصل"
                                       :ur "یو کے میں آغاز | Dar El Wasl"
                                       "Start in the UK | Dar El Wasl")
                              :description strapline
                              :public-base-url public-base-url
                              :base-path base-path
                              :lang lang
                              :path path
                              :image-path "/logo.jpg"
                              :contact contact}
                           (str (hero-simple headline strapline nil nil)
                                "<section><p>" (escape-html intro) "</p></section>"
                                "<section><div class='section-title'><h2>" (escape-html help-title) "</h2></div>"
                                "<div class='card-grid'>"
                                (apply str (map card-icon
                                                (for [{:keys [title desc icon]} help]
                                                  {:title title
                                                   :description desc
                                                   :href (href "/contact#consultation")
                                                   :icon icon})))
                                "</div></section>"
                                "<section><div class='section-title'><h2>" (escape-html need-title) "</h2></div>"
                                (bullet-list need)
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html run-title) "</h2></div>"
                                "<div class='steps steps--light'>"
                                (apply str
                                       (map-indexed
                                        (fn [idx {:keys [title desc]}]
                                          (str "<div class='step'>"
                                               "<div class='step-index'>" (inc idx) "</div>"
                                               "<div><div class='label'>" (escape-html title) "</div>"
                                               "<div class='muted'>" (escape-html desc) "</div></div></div>"))
                                        run))
                                "</div></section>"
                                "<section><div class='section-title'><h2>" (escape-html obligations-title) "</h2></div>"
                                (bullet-list obligations)
                                "<p class='muted'>" (escape-html disclaimer) "</p>"
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html next-title) "</h2></div>"
                                "<p class='muted'>" (escape-html next-desc) "</p>"
                                "<a class='cta primary' href='" (escape-html (href "/contact#consultation")) "'>" (escape-html next-cta) "</a>"
                                "</section>")))}

       "/uk/company-formation"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (let [headline (case lang
                              :ar "تأسيس شركة UK Ltd"
                              :ur "UK Ltd تشکیل"
                              "UK Ltd Company Formation")
                    strapline (case lang
                               :ar "إدخالات واضحة، تقديم صحيح، وحزمة جاهزية بعد التأسيس."
                               :ur "واضح ان پٹس، درست فائلنگ، اور بعد از تشکیل ریڈی نس ہینڈ آف۔"
                               "Clear inputs, correct filing, and a clean readiness handoff after incorporation.")
                    who-title (case lang
                               :ar "لمن هذا"
                               :ur "یہ کس کے لیے ہے"
                               "Who this is for")
                    who (case lang
                          :ar ["مؤسسون يحتاجون كيانًا قانونيًا في المملكة المتحدة"
                               "هياكل خدمة/قابضة (حسب الحالة)"
                               "فرق عن بعد تريد تأسيسًا نظيفًا للبنك والمحاسبة"]
                          :ur ["بانی جو یو کے میں قانونی ادارہ چاہتے ہیں"
                               "سروس/ہولڈنگ اسٹرکچرز (کیس کے مطابق)"
                               "ریموٹ ٹیمیں جو بینکنگ/اکاؤنٹنگ کے لیے صاف ہینڈ آف چاہتی ہیں"]
                          ["Founders who need a UK legal entity"
                           "Service/holding structures (case-by-case)"
                           "Remote teams who want a clean handoff for banking and accounting"])
                    inputs-title (case lang
                                  :ar "الإدخالات التي سنطلبها"
                                  :ur "ہم کن ان پٹس کا پوچھیں گے"
                                  "Inputs we’ll ask for")
                    inputs (case lang
                             :ar ["خيارات اسم الشركة"
                                  "بيانات المديرين (الاسم/العنوان/تاريخ الميلاد)"
                                  "المساهمون ونسبة الأسهم"
                                  "تفاصيل PSC (إن وجدت)"
                                  "عنوان المكتب المسجل"
                                  "نشاط الشركة / رموز SIC"
                                  "قد تتطلب بعض الحالات تحقق الهوية/أكواد Companies House للمديرين/PSC"]
                             :ur ["کمپنی نام کے آپشنز"
                                  "ڈائریکٹرز کی تفصیل (نام/ایڈریس/تاریخ پیدائش)"
                                  "شیئرہولڈرز اور شیئر سپلٹ"
                                  "PSC تفصیلات (اگر لاگو ہو)"
                                  "Registered office address"
                                  "Business activity / SIC codes"
                                  "کچھ کیسز میں شناخت کی تصدیق اور Companies House codes درکار ہو سکتے ہیں"]
                             ["Company name options"
                              "Directors details (name/address/date of birth)"
                              "Shareholders and share split"
                              "PSC details (persons with significant control), if applicable"
                              "Registered office address"
                              "Business activity / SIC codes"
                              "Identity verification + Companies House personal codes may be required for directors/PSCs"])
                    outputs-title (case lang
                                   :ar "المخرجات التي تحصل عليها"
                                   :ur "آپ کو کیا ملتا ہے"
                                   "Outputs you get")
                    outputs (case lang
                              :ar ["تأكيد التأسيس ورقم الشركة"
                                   "شهادة التأسيس"
                                   "ملخص للهيكل/الملكية"
                                   "قائمة جاهزية للضرائب والبنك والمحاسبة"]
                              :ur ["انکارپوریشن کنفرمیشن اور کمپنی نمبر"
                                   "انکارپوریشن سرٹیفکیٹ"
                                   "اسٹرکچر/ملکیت کا خلاصہ"
                                   "ٹیکس، بینکنگ اور اکاؤنٹنگ کے لیے ریڈی نس چیک لسٹ"]
                              ["Incorporation confirmation and company number"
                               "Incorporation certificate"
                               "Ownership/structure summary"
                               "A readiness checklist for tax, banking, and accounting"])
                    after-title (case lang
                                 :ar "بعد التأسيس (مستوى عام)"
                                 :ur "بعد از تشکیل (ہائی لیول)"
                                 "After incorporation (high level)")
                    after (case lang
                            :ar ["إبلاغ/تسجيل Corporation Tax خلال 3 أشهر من بدء التداول"
                                 "إعداد المحاسبة ومتطلبات الامتثال"
                                 "بدء مسك الدفاتر من اليوم الأول"
                                 "VAT / PAYE إذا كان مناسبًا"
                                 "Confirmation Statement والحسابات السنوية ضمن المواعيد"]
                            :ur ["Corporation Tax کے لیے 3 ماہ کے اندر رجسٹر کریں"
                                 "اکاؤنٹنگ سیٹ اپ اور کمپلائنس ضروریات"
                                 "شروع سے بک کیپنگ"
                                 "VAT / PAYE اگر لاگو ہو"
                                 "Confirmation Statement اور سالانہ اکاؤنٹس بروقت"]
                            ["Notify/register for Corporation Tax within 3 months of starting to trade"
                             "Accounting setup and compliance requirements"
                             "Bookkeeping from day one"
                             "VAT / PAYE if relevant"
                             "File the Confirmation Statement and annual accounts on time"])
                    timing-title (case lang
                                  :ar "التوقيت"
                                  :ur "ٹائمنگ"
                                  "Timing")
                    timing (case lang
                             :ar "التأسيس غالبًا سريع بعد اكتمال الإدخالات، لكن قد يختلف حسب التحقق والمتطلبات."
                             :ur "ان پٹس مکمل ہونے پر تشکیل عموماً تیز ہوتی ہے، مگر ویریفکیشن اور کیس کے مطابق فرق ہو سکتا ہے۔"
                             "Incorporation is often quick once inputs are complete, but timing can vary based on verification and case details.")
                    next-cta (case lang
                               :ar "احجز استشارة"
                               :ur "مشاورت طے کریں"
                               "Schedule a consultation")]
                (public-page {:title (case lang
                                       :ar "تأسيس شركة UK Ltd | دار الوصل"
                                       :ur "UK Ltd تشکیل | Dar El Wasl"
                                       "UK Ltd Company Formation | Dar El Wasl")
                              :description strapline
                              :public-base-url public-base-url
                              :base-path base-path
                              :lang lang
                              :path path
                              :image-path "/logo.jpg"
                              :contact contact}
                           (str (hero-simple headline strapline nil nil)
                                "<section><div class='section-title'><h2>" (escape-html who-title) "</h2></div>"
                                (bullet-list who)
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html inputs-title) "</h2></div>"
                                (bullet-list inputs)
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html outputs-title) "</h2></div>"
                                (bullet-list outputs)
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html after-title) "</h2></div>"
                                (bullet-list after)
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html timing-title) "</h2></div>"
                                "<p class='muted'>" (escape-html timing) "</p>"
                                "</section>"
                                "<section><div class='section-title'><h2>"
                                (escape-html (case lang :ar "الخطوة التالية" :ur "اگلا قدم" "Next step"))
                                "</h2></div>"
                                "<p class='muted'>" (escape-html (case lang
                                                              :ar "أرسل تفاصيلك وسنرد عليك بقائمة متطلبات وخطوتك التالية."
                                                              :ur "اپنی تفصیلات بھیجیں اور ہم تقاضوں کی چیک لسٹ اور اگلا قدم بھیجیں گے۔"
                                                              "Send your details and we’ll reply with your requirements checklist and next step."))
                                "</p>"
                                "<a class='cta primary' href='" (escape-html (href "/contact#consultation")) "'>" (escape-html next-cta) "</a>"
                                "</section>")))}

       "/resources"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (let [headline (case lang
                              :ar "الموارد"
                              :ur "وسائل"
                              "Resources")
                    strapline (case lang
                               :ar "أدلة قصيرة ونقاط بداية قبل الاستشارة."
                               :ur "مشاورت سے پہلے مختصر گائیڈز اور شروع کرنے کے پوائنٹس۔"
                               "Short guides and starting points before your consultation.")
                    cards (case lang
                            :ar [{:title "السعودية"
                                  :desc "اختر خيار التأسيس واستعد للتفعيل."
                                  :href (href "/resources/saudi-business-setup-guide")
                                  :link "دليل التأسيس"}
                                 {:title "المملكة المتحدة"
                                  :desc "تأسيس UK Ltd + قائمة جاهزية بعد التأسيس."
                                  :href (href "/resources/uk-company-formation-guide")
                                  :link "دليل التأسيس في UK"}
                                 {:title "المدونة"
                                  :desc "ملاحظات عملية قصيرة."
                                  :href (href "/resources/blog")
                                  :link "عرض المدونة"}
                                 {:title "الأسئلة الشائعة"
                                  :desc "إجابات سريعة عن الأسئلة الشائعة."
                                  :href (href "/resources/faqs")
                                  :link "عرض الأسئلة"}]
                            :ur [{:title "سعودی عرب"
                                  :desc "درست سیٹ اپ اختیار منتخب کریں اور ایکٹیویشن کے لیے تیار ہوں۔"
                                  :href (href "/resources/saudi-business-setup-guide")
                                  :link "سعودی گائیڈ"}
                                 {:title "یو کے"
                                  :desc "UK Ltd تشکیل اور بعد از تشکیل چیک لسٹ۔"
                                  :href (href "/resources/uk-company-formation-guide")
                                  :link "یو کے گائیڈ"}
                                 {:title "بلاگ"
                                  :desc "مختصر عملی نوٹس۔"
                                  :href (href "/resources/blog")
                                  :link "بلاگ دیکھیں"}
                                 {:title "FAQs"
                                  :desc "عام سوالات کے تیز جواب۔"
                                  :href (href "/resources/faqs")
                                  :link "FAQs دیکھیں"}]
                            [{:title "Saudi Arabia"
                              :desc "Pick the right setup option and prepare for activation."
                              :href (href "/resources/saudi-business-setup-guide")
                              :link "Saudi setup guide"}
                             {:title "UK"
                              :desc "UK Ltd formation and a readiness checklist after incorporation."
                              :href (href "/resources/uk-company-formation-guide")
                              :link "UK formation guide"}
                             {:title "Blog"
                              :desc "Short practical notes."
                              :href (href "/resources/blog")
                              :link "View blog"}
                             {:title "FAQs"
                              :desc "Quick answers to common questions."
                              :href (href "/resources/faqs")
                              :link "View FAQs"}])
                    next-title (case lang
                                :ar "الخطوة التالية"
                                :ur "اگلا قدم"
                                "Next step")
                    next-desc (case lang
                               :ar "أرسل تفاصيلك وسنرد عليك بقائمة متطلبات."
                               :ur "اپنی تفصیلات بھیجیں اور ہم تقاضوں کی چیک لسٹ کے ساتھ جواب دیں گے۔"
                               "Send your details and we’ll respond with a requirements checklist.")
                    next-cta (case lang
                               :ar "احجز استشارة"
                               :ur "مشاورت طے کریں"
                               "Schedule a consultation")]
                (public-page {:title headline
                              :description strapline
                              :public-base-url public-base-url
                              :base-path base-path
                              :lang lang
                              :path path
                              :image-path "/logo.jpg"
                              :contact contact}
                           (str (hero-simple headline strapline nil nil)
                                "<section><div class='card-grid'>"
                                (apply str
                                       (for [{:keys [title desc href link]} cards]
                                         (str "<div class='card'>"
                                              "<h3>" (escape-html title) "</h3>"
                                              "<p class='muted'>" (escape-html desc) "</p>"
                                              "<a class='text-link' href='" (escape-html href) "'>" (escape-html link) "</a>"
                                              "</div>")))
                                (str "<div class='card'>"
                                     "<h3>" (escape-html next-title) "</h3>"
                                     "<p class='muted'>" (escape-html next-desc) "</p>"
                                     "<a class='cta primary' href='" (escape-html (href "/contact#consultation")) "'>" (escape-html next-cta) "</a>"
                                     "</div>")
                                "</div></section>")))}

       "/resources/saudi-business-setup-guide"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (let [headline (case lang
                              :ar "دليل التأسيس في السعودية"
                              :ur "سعودی سیٹ اپ گائیڈ"
                              "Saudi Setup Guide")
                    strapline (case lang
                               :ar "خيارات التأسيس، ما يجب تحضيره، وما يحدث بعد التأسيس."
                               :ur "سیٹ اپ آپشنز، کیا تیار کرنا ہے، اور تشکیل کے بعد کیا ہوتا ہے۔"
                               "Setup options, what to prepare, and what happens after incorporation.")
                    intro (case lang
                           :ar "هذا الدليل نقطة بداية. بعد الاستشارة، نحوله إلى قائمة متطلبات وتسلسل تنفيذ لحالتك."
                           :ur "یہ گائیڈ ایک آغاز ہے۔ مشاورت کے بعد ہم اسے آپ کے کیس کے مطابق چیک لسٹ اور ایکزیکیوشن سیquence میں بدلتے ہیں۔"
                           "This guide is a starting point. After a consultation, we convert it into a tailored checklist and execution sequence for your case.")
                    before-title (case lang :ar "قبل الاستشارة" :ur "مشاورت سے پہلے" "Before the consultation")
                    before (case lang
                             :ar ["الأنشطة (1 إلى 3 أسطر)"
                                  "الملكية (فرد/شركة/مساهمون)"
                                  "الجنسية / الإقامة"
                                  "شهر البدء المستهدف"
                                  "ما هي الوثائق المتاحة لديك"
                                  "اللغة المفضلة (EN/AR/UR)"]
                             :ur ["سرگرمیاں (1 سے 3 لائنیں)"
                                  "ملکیت (انفرادی/کمپنی/شیئرہولڈرز)"
                                  "قومیت / رہائش"
                                  "ہدف آغاز کا مہینہ"
                                  "کون سی دستاویزات پہلے سے موجود ہیں"
                                  "ترجیحی زبان (EN/AR/UR)"]
                             ["Activities (1 to 3 lines)"
                              "Ownership (individual/company/shareholders)"
                              "Nationality / residency"
                              "Target start month"
                              "What documents you already have"
                              "Preferred language (EN/AR/UR)"])
                    after-title (case lang :ar "بعد التأسيس + التفعيل" :ur "تشکیل کے بعد + ایکٹیویشن" "After setup + activation")
                    after (case lang
                            :ar ["العنوان الوطني (SPL)"
                                 "ZATCA (زكاة/ضريبة) حسب الحالة"
                                 "GOSI إذا كان لديك موظفون"
                                 "عضوية الغرفة التجارية إن لزم"
                                 "إعداد المنصات الحكومية حسب التراخيص"
                                 "جاهزية الفوترة والبنك"]
                            :ur ["National Address (SPL)"
                                 "ZATCA (زکاة/ٹیکس) کیس کے مطابق"
                                 "GOSI اگر ملازمین ہوں"
                                 "Chamber membership اگر درکار ہو"
                                 "حکومتی پورٹلز سیٹ اپ"
                                 "بینکنگ اور اِن وائسنگ ریڈی نس"]
                            ["National Address (SPL)"
                             "ZATCA (zakat/tax), case-by-case"
                             "GOSI if you have employees"
                             "Chamber of Commerce membership (as required)"
                             "Government portals setup"
                             "Banking and invoicing readiness"])
                    receive-title (case lang :ar "ما الذي تستلمه" :ur "آپ کو کیا ملتا ہے" "What you receive")
                    receive (case lang
                              :ar ["المسار الصحيح للأنشطة والملكية"
                                   "قائمة متطلبات مكتوبة"
                                   "تسلسل واضح للخطوات (تأسيس → تفعيل)"
                                   "دعم PRO/GRO مستمر عند الحاجة"]
                              :ur ["آپ کی سرگرمیوں/ملکیت کے لیے درست راستہ"
                                   "تحریری تقاضوں کی فہرست"
                                   "واضح اسٹیپ سیquence (سیٹ اپ → ایکٹیویشن)"
                                   "ضرورت پر جاری PRO/GRO سپورٹ"]
                              ["The right route for your activities and ownership"
                               "A written requirements checklist"
                               "A clear step sequence (setup → activation)"
                               "Optional ongoing PRO/GRO support"])
                    note (case lang
                           :ar "ملاحظة: 100% ملكية أجنبية ممكنة للعديد من الأنشطة، لكن بعض الأنشطة مقيدة أو لها شروط. نؤكد الأهلية قبل التنفيذ. معلومات عامة (ليست استشارة قانونية)."
                           :ur "نوٹ: بہت سی سرگرمیوں میں %100 غیر ملکی ملکیت ممکن ہے، مگر کچھ سرگرمیاں محدود یا شرائط کے ساتھ ہوتی ہیں۔ ہم آگے بڑھنے سے پہلے اہلیت کنفرم کرتے ہیں۔ عمومی معلومات (قانونی مشورہ نہیں)۔"
                           "Note: 100% foreign ownership is possible in many activities, but some activities are restricted or have conditions. We confirm eligibility before execution. General information (not legal advice).")
                    next-cta (case lang :ar "احجز استشارة" :ur "مشاورت طے کریں" "Schedule a consultation")]
                (public-page {:title (case lang
                                       :ar "دليل التأسيس في السعودية | دار الوصل"
                                       :ur "سعودی سیٹ اپ گائیڈ | Dar El Wasl"
                                       "Saudi Setup Guide | Dar El Wasl")
                              :description strapline
                              :public-base-url public-base-url
                              :base-path base-path
                              :lang lang
                              :path path
                              :image-path "/logo.jpg"
                              :contact contact}
                           (str (hero-simple headline strapline nil nil)
                                "<section><p>" (escape-html intro) "</p></section>"
                                "<section><div class='card-grid'>"
                                (str "<div class='card'><h3>" (escape-html before-title) "</h3>" (bullet-list before) "</div>"
                                     "<div class='card'><h3>" (escape-html after-title) "</h3>" (bullet-list after) "</div>"
                                     "<div class='card'><h3>" (escape-html receive-title) "</h3>" (bullet-list receive)
                                     "<p class='muted'>" (escape-html note) "</p></div>")
                                "</div></section>"
                                "<section><div class='section-title'><h2>"
                                (escape-html (case lang :ar "الخطوة التالية" :ur "اگلا قدم" "Next step"))
                                "</h2></div>"
                                "<p class='muted'>" (escape-html (case lang
                                                              :ar "أرسل تفاصيلك وسنرد عليك بقائمة متطلبات وخطوتك التالية."
                                                              :ur "اپنی تفصیلات بھیجیں اور ہم تقاضوں کی چیک لسٹ اور اگلا قدم بھیجیں گے۔"
                                                              "Send your details and we’ll reply with your requirements checklist and next step."))
                                "</p>"
                                "<a class='cta primary' href='" (escape-html (href "/contact#consultation")) "'>" (escape-html next-cta) "</a>"
                                "</section>")))}

       "/resources/uk-company-formation-guide"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (let [headline (case lang
                              :ar "دليل تأسيس شركة في بريطانيا"
                              :ur "یو کے تشکیل گائیڈ"
                              "UK Company Formation Guide")
                    strapline (case lang
                               :ar "إدخالات التأسيس + قائمة جاهزية واضحة بعد التأسيس."
                               :ur "تشکیل کے ان پٹس + بعد از تشکیل صاف ریڈی نس چیک لسٹ۔"
                               "Formation inputs + a clean readiness checklist after incorporation.")
                    intro (case lang
                           :ar "هذا الدليل يوضح ما نحتاجه للتأسيس وما يجب تحضيره مباشرة بعد التأسيس."
                           :ur "یہ گائیڈ بتاتا ہے کہ تشکیل کے لیے کیا چاہیے اور بعد میں کیا تیار کرنا ہے۔"
                           "This guide outlines what we need to incorporate, and what you should prepare immediately after formation.")
                    before-title (case lang :ar "قبل أن نقدم" :ur "فائلنگ سے پہلے" "Before we file")
                    before (case lang
                             :ar ["خيارات اسم الشركة، رموز SIC، وهيكل الملكية"
                                  "المديرون/المساهمون وPSC (إن لزم)"
                                  "عنوان المكتب المسجل"
                                  "قد تتطلب بعض الحالات تحقق الهوية/أكواد Companies House"]
                             :ur ["کمپنی نام، SIC codes، اور ملکیت اسٹرکچر"
                                  "ڈائریکٹرز/شیئرہولڈرز اور PSC (اگر لاگو ہو)"
                                  "Registered office address"
                                  "کچھ کیسز میں شناخت کی تصدیق/Companies House codes"]
                             ["Company name options, SIC codes, and ownership structure"
                              "Directors/shareholders and PSC (if applicable)"
                              "Registered office address"
                              "Identity verification + Companies House personal codes may be required"])
                    after-title (case lang :ar "بعد التأسيس" :ur "تشکیل کے بعد" "After incorporation")
                    after (case lang
                            :ar ["إبلاغ/تسجيل Corporation Tax خلال 3 أشهر من بدء التداول"
                                 "إعداد البنك والمحاسبة والامتثال"
                                 "VAT / PAYE إذا كان مناسبًا"
                                 "Confirmation Statement والحسابات السنوية ضمن المواعيد"]
                            :ur ["Corporation Tax کے لیے 3 ماہ کے اندر رجسٹر کریں"
                                 "بینکنگ/اکاؤنٹنگ/کمپلائنس سیٹ اپ"
                                 "VAT / PAYE اگر لاگو ہو"
                                 "Confirmation Statement اور سالانہ اکاؤنٹس بروقت"]
                            ["Notify/register for Corporation Tax within 3 months of starting to trade"
                             "Banking, accounting setup, and compliance from day one"
                             "VAT / PAYE if relevant"
                             "Confirmation Statement and annual accounts (on schedule)"])
                    receive-title (case lang :ar "ما الذي تستلمه" :ur "آپ کو کیا ملتا ہے" "What you receive")
                    receive (case lang
                              :ar ["تأكيد التأسيس ورقم الشركة"
                                   "ملخص للهيكل/الملكية"
                                   "قائمة جاهزية للمرحلة التالية"
                                   "معلومات عامة (ليست استشارة قانونية)"]
                              :ur ["انکارپوریشن کنفرمیشن اور کمپنی نمبر"
                                   "اسٹرکچر/ملکیت کا خلاصہ"
                                   "اگلے مرحلے کے لیے ریڈی نس چیک لسٹ"
                                   "عمومی معلومات (قانونی مشورہ نہیں)"]
                              ["Incorporation confirmation and company number"
                               "A structure/ownership summary"
                               "A practical checklist for the next phase"
                               "General information (not legal/tax advice)."])
                    next-cta (case lang :ar "احجز استشارة" :ur "مشاورت طے کریں" "Schedule a consultation")]
                (public-page {:title (case lang
                                       :ar "دليل UK | دار الوصل"
                                       :ur "یو کے گائیڈ | Dar El Wasl"
                                       "UK Formation Guide | Dar El Wasl")
                              :description strapline
                              :public-base-url public-base-url
                              :base-path base-path
                              :lang lang
                              :path path
                              :image-path "/logo.jpg"
                              :contact contact}
                           (str (hero-simple headline strapline nil nil)
                                "<section><p>" (escape-html intro) "</p></section>"
                                "<section><div class='card-grid'>"
                                (str "<div class='card'><h3>" (escape-html before-title) "</h3>" (bullet-list before) "</div>"
                                     "<div class='card'><h3>" (escape-html after-title) "</h3>" (bullet-list after) "</div>"
                                     "<div class='card'><h3>" (escape-html receive-title) "</h3>" (bullet-list receive) "</div>")
                                "</div></section>"
                                "<section><div class='section-title'><h2>"
                                (escape-html (case lang :ar "الخطوة التالية" :ur "اگلا قدم" "Next step"))
                                "</h2></div>"
                                "<p class='muted'>" (escape-html (case lang
                                                              :ar "أرسل تفاصيلك وسنرد عليك بقائمة متطلبات وخطوتك التالية."
                                                              :ur "اپنی تفصیلات بھیجیں اور ہم تقاضوں کی چیک لسٹ اور اگلا قدم بھیجیں گے۔"
                                                              "Send your details and we’ll reply with your requirements checklist and next step."))
                                "</p>"
                                "<a class='cta primary' href='" (escape-html (href "/contact#consultation")) "'>" (escape-html next-cta) "</a>"
                                "</section>")))}

       "/resources/blog"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (let [headline (case lang :ar "المدونة" :ur "بلاگ" "Blog")
                    strapline (case lang
                               :ar "ملاحظات قصيرة قبل الاستشارة."
                               :ur "مشاورت سے پہلے مختصر نوٹس۔"
                               "Short notes and updates.")
                    items (case lang
                            :ar [{:title "كيف تستعد للاستشارة"
                                  :desc "أفضل طريقة لتسريع التنفيذ هي تجهيز الإدخالات الأساسية قبل المكالمة."
                                  :bullets ["الأنشطة (1 إلى 3 أسطر)" "الملكية/المساهمون" "التوقيت المستهدف" "حالة الوثائق"]}
                                 {:title "لماذا التفعيل مهم"
                                  :desc "التأسيس وحده لا يكفي. التفعيل هو ما يجعل الشركة جاهزة للتشغيل."
                                  :bullets ["حسابات المنصات" "العنوان/الإيجار" "التسجيلات" "الامتثال المستمر"]}
                                 {:title "ما الذي ستحصل عليه"
                                  :desc "نحول المكالمة إلى قائمة متطلبات مكتوبة وتسلسل واضح للخطوات."
                                  :bullets ["قائمة متطلبات" "تسلسل خطوات" "توقعات وتوقيت" "تنسيق التنفيذ"]}]
                            :ur [{:title "مشاورت کے لیے کیسے تیار ہوں"
                                  :desc "سب سے تیز طریقہ یہ ہے کہ بنیادی ان پٹس پہلے سے تیار ہوں۔"
                                  :bullets ["سرگرمیاں (1 سے 3 لائنیں)" "ملکیت/شیئرہولڈرز" "ہدف ٹائمنگ" "دستاویزات کی حالت"]}
                                 {:title "ایکٹیویشن کیوں اہم ہے"
                                  :desc "سیٹ اپ کے بعد ایکٹیویشن ہی آپ کو آپریشنل بناتی ہے۔"
                                  :bullets ["پورٹل اکاؤنٹس" "ایڈریس/لیز" "رجسٹریشنز" "جاری کمپلائنس"]}
                                 {:title "آپ کو کیا ملتا ہے"
                                  :desc "ہم کال کو تحریری چیک لسٹ اور واضح اگلے اسٹیپس میں بدلتے ہیں۔"
                                  :bullets ["تقاضوں کی فہرست" "مرحلہ وار پلان" "ٹائمنگ/توقعات" "ایگزیکیوشن کوآرڈینیشن"]}]
                            [{:title "How to prepare for the consultation"
                              :desc "The fastest execution starts with clean inputs before the call."
                              :bullets ["Activities (1 to 3 lines)" "Ownership/shareholders" "Target timing" "Document status"]}
                             {:title "Why activation matters"
                              :desc "Formation isn’t the finish line. Activation is what makes the company operational."
                              :bullets ["Portal accounts" "Address/lease" "Registrations" "Ongoing compliance"]}
                             {:title "What you receive"
                              :desc "We turn the call into a written checklist and a clear step sequence."
                              :bullets ["Requirements list" "Step sequence" "Timing expectations" "Execution coordination"]}])]
                (public-page {:title headline
                              :description strapline
                              :public-base-url public-base-url
                              :base-path base-path
                              :lang lang
                              :path path
                              :image-path "/logo.jpg"
                              :contact contact}
                           (str (hero-simple headline strapline nil nil)
                                "<section><div class='card-grid'>"
                                (apply str
                                       (for [{:keys [title desc bullets]} items]
                                         (str "<div class='card'>"
                                              "<h3>" (escape-html title) "</h3>"
                                              "<p class='muted'>" (escape-html desc) "</p>"
                                              (bullet-list bullets)
                                              "</div>")))
                                "</div></section>"
                                "<section><div class='section-title'><h2>"
                                (escape-html (case lang :ar "الخطوة التالية" :ur "اگلا قدم" "Next step"))
                                "</h2></div>"
                                "<p class='muted'>" (escape-html (case lang
                                                              :ar "أرسل تفاصيلك وسنرد عليك بقائمة متطلبات وخطوتك التالية."
                                                              :ur "اپنی تفصیلات بھیجیں اور ہم تقاضوں کی چیک لسٹ اور اگلا قدم بھیجیں گے۔"
                                                              "Send your details and we’ll reply with your requirements checklist and next step."))
                                "</p>"
                                "<a class='cta primary' href='" (escape-html (href "/contact#consultation")) "'>"
                                (escape-html (case lang :ar "احجز استشارة" :ur "مشاورت طے کریں" "Schedule a consultation"))
                                "</a>"
                                "</section>")))}

       "/resources/faqs"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (let [headline (case lang
                              :ar "الأسئلة الشائعة"
                              :ur "FAQs"
                              "FAQs")
                    strapline (case lang
                               :ar "إجابات سريعة عن الأسئلة المتكررة."
                               :ur "عام سوالات کے تیز جواب۔"
                               "Common questions and quick answers.")
                    items (case lang
                            :ar [{:q "هل تقدمون دراسات جدوى؟"
                                  :a "نركز على الترخيص، التأسيس، التفعيل، وخدمات PRO/GRO التنفيذية. وعند الحاجة يمكننا تنسيق رأي مختصين."}
                                 {:q "هل يمكنكم المساعدة إذا كنت خارج السعودية؟"
                                  :a "نعم. يمكن تجهيز معظم الخطوات عن بُعد؛ وقد تتطلب بعض الحالات حضورًا حسب الوضع."}
                                 {:q "كيف تعمل الأسعار والجداول الزمنية؟"
                                  :a "تُحدد الأسعار والجداول حسب الأنشطة والملكية. بعد الاستشارة تحصل على نطاق واضح وتقدير مبدئي وتوقيت متوقع."}
                                 {:q "هل تدعمون العربية والإنجليزية والأردو؟"
                                  :a "نعم. دعم متعدد اللغات للتواصل والوثائق حسب الحاجة."}
                                 {:q "هل يمكنكم مساعدتي للبدء في المملكة المتحدة أيضًا؟"
                                  :a "نعم. يمكننا المساعدة في تأسيس شركة UK Ltd وتوضيح خطوات الامتثال الأساسية."}
                                 {:q "أين تعملون؟"
                                  :a "نخدم العملاء عبر المملكة وننسق أي خطوات ميدانية حسب الحالة."}]
                            :ur [{:q "کیا آپ feasibility studies فراہم کرتے ہیں؟"
                                  :a "ہم لائسنسنگ، سیٹ اپ، ایکٹیویشن، اور PRO/GRO عملدرآمد پر فوکس کرتے ہیں۔ ضرورت ہو تو ہم متعلقہ ماہرین سے کوآرڈینیٹ کر سکتے ہیں۔"}
                                 {:q "کیا آپ سعودی عرب سے باہر ہونے کی صورت میں مدد کر سکتے ہیں؟"
                                  :a "جی ہاں۔ زیادہ تر تیاری ریموٹ ہو سکتی ہے؛ کچھ مراحل کیس کے مطابق موجودگی چاہ سکتے ہیں۔"}
                                 {:q "قیمت اور ٹائم لائن کیسے طے ہوتی ہے؟"
                                  :a "قیمت اور ٹائم لائن آپ کی سرگرمیوں اور ملکیت کے مطابق طے ہوتی ہے۔ مشاورت کے بعد واضح اسکوپ، ٹائم لائن اور ابتدائی اندازہ ملتا ہے۔"}
                                 {:q "کیا آپ English, Arabic اور Urdu سپورٹ کرتے ہیں؟"
                                  :a "جی ہاں۔ ضرورت کے مطابق کثیر لسانی سپورٹ۔"}
                                 {:q "کیا آپ UK میں بھی آغاز میں مدد کر سکتے ہیں؟"
                                  :a "جی ہاں۔ ہم UK Ltd سیٹ اپ اور بنیادی کمپلائنس اقدامات سمجھا سکتے ہیں۔"}
                                 {:q "آپ کہاں کام کرتے ہیں؟"
                                  :a "ہم سعودی عرب بھر میں کلائنٹس کی مدد کرتے ہیں اور ضروری آن گراؤنڈ مراحل کیس کے مطابق منظم کرتے ہیں۔"}]
                            [{:q "Do you offer feasibility studies?"
                              :a "We focus on licensing, incorporation, activation, and PRO/GRO execution. If you need a feasibility study, we can coordinate a specialist."}
                             {:q "Can you help if I’m outside Saudi Arabia?"
                              :a "Yes. Most of the process can be prepared remotely; some steps may require presence depending on your case."}
                             {:q "How do pricing and timelines work?"
                              :a "Pricing and timelines are scoped case-by-case based on your activities and ownership. After the consultation, you receive a clear scope, timeline, and estimate."}
                             {:q "Do you support English, Arabic, and Urdu?"
                              :a "Yes. Multilingual support for communication and documents when needed."}
                             {:q "Can you help me start in the UK too?"
                              :a "Yes. We can set up a UK Ltd and guide you through basic compliance steps."}
                             {:q "Where do you operate?"
                              :a "Saudi-wide. We support clients across the Kingdom and coordinate any on-the-ground steps case-by-case."}])]
                (public-page {:title headline
                              :description strapline
                              :public-base-url public-base-url
                              :base-path base-path
                              :lang lang
                              :path path
                              :image-path "/logo.jpg"
                              :contact contact}
                           (str (hero-simple headline strapline nil nil)
                                "<section class='faqs'><div class='stack'>"
                                (apply str
                                       (for [{:keys [q a]} items]
                                         (str "<details><summary>" (escape-html q) "</summary>"
                                              "<p class='muted'>" (escape-html a) "</p></details>")))
                                "</div></section>"
                                "<section><div class='section-title'><h2>"
                                (escape-html (case lang :ar "الخطوة التالية" :ur "اگلا قدم" "Next step"))
                                "</h2></div>"
                                "<p class='muted'>" (escape-html (case lang
                                                              :ar "إذا كانت لديك تفاصيل جاهزة، أرسلها وسنرد عليك بقائمة متطلبات وخطوتك التالية."
                                                              :ur "اگر آپ کی تفصیلات تیار ہیں تو بھیج دیں، ہم تقاضوں کی چیک لسٹ اور اگلا قدم بھیجیں گے۔"
                                                              "If you have your details ready, send them and we’ll reply with your requirements checklist and next step."))
                                "</p>"
                                "<a class='cta primary' href='" (escape-html (href "/contact#consultation")) "'>"
                                (escape-html (case lang :ar "احجز استشارة" :ur "مشاورت طے کریں" "Schedule a consultation"))
                                "</a>"
                                "</section>")))}

       "/about"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (let [headline (case lang
                              :ar "عن دار الوصل"
                              :ur "Dar El Wasl کے بارے میں"
                              "About Dar El Wasl")
                    strapline (case lang
                               :ar "نحوّل التعقيد إلى خطة واضحة: إدخالات، تسلسل، وتسليم قابل للتنفيذ."
                               :ur "ہم پیچیدگی کو واضح پلان میں بدلتے ہیں: ان پٹس، ترتیب، اور قابلِ عمل ہینڈ آف۔"
                               "We turn complexity into a clear plan: inputs, sequence, and an actionable handoff.")
                    what-title (case lang
                                 :ar "ما الذي نفعله"
                                 :ur "ہم کیا کرتے ہیں"
                                 "What we do")
                    what (case lang
                           :ar ["نساعدك على اختيار المسار الصحيح حسب حالتك"
                                "نحوّل الاستشارة إلى قائمة متطلبات مكتوبة وخطوة تالية واضحة"
                                "ننسق التأسيس/التفعيل ثم نسلمك حزمة جاهزية للبنك والمحاسبة والامتثال الأساسي"]
                           :ur ["آپ کے کیس کے مطابق درست راستہ منتخب کرنے میں مدد"
                                "مشاورت کو تحریری تقاضوں کی چیک لسٹ اور واضح اگلے قدم میں بدلنا"
                                "تشکیل/ایکٹیویشن کوآرڈینیٹ کرنا اور بینک/اکاؤنٹنگ/بنیادی کمپلائنس کے لیے ریڈی نس ہینڈ آف"]
                           ["Help you choose the right setup path for your case"
                            "Convert the consultation into a written requirements checklist and clear next step"
                            "Coordinate formation/activation, then hand off cleanly for banking, accounting, and baseline compliance"])
                    how-title (case lang
                                :ar "كيف نعمل"
                                :ur "ہم کیسے کام کرتے ہیں"
                                "How we work")
                    how (case lang
                          :ar [{:title "الاستشارة"
                                :desc "نجمع الإدخالات الأساسية ونؤكد النطاق."}
                               {:title "قائمة المتطلبات"
                                :desc "قائمة مكتوبة بالوثائق والخطوات الخاصة بحالتك."}
                               {:title "التنفيذ + التسليم"
                                :desc "ننفذ الخطوات ونسلمك حزمة جاهزية عملية."}]
                          :ur [{:title "مشاورت"
                                :desc "بنیادی ان پٹس جمع کرتے ہیں اور اسکوپ کنفرم کرتے ہیں۔"}
                               {:title "تقاضوں کی چیک لسٹ"
                                :desc "آپ کے کیس کے مطابق تحریری ڈاکس اور اسٹیپس۔"}
                               {:title "ایگزیکیوشن + ہینڈ آف"
                                :desc "اسٹیپس مکمل کرتے ہیں اور عملی ریڈی نس پیک دیتے ہیں۔"}]
                          [{:title "Consultation"
                            :desc "Collect key inputs and confirm scope."}
                           {:title "Requirements checklist"
                            :desc "A written list of documents and steps for your case."}
                           {:title "Execution + handoff"
                            :desc "Complete the steps and hand you a practical readiness pack."}])
                    principles-title (case lang
                                       :ar "المبادئ"
                                       :ur "اصول"
                                       "Principles")
                    principles (case lang
                                 :ar [{:title "وضوح وتسلسل"
                                       :desc "الترتيب الصحيح يقلل التأخير وإعادة العمل."}
                                      {:title "قوائم مكتوبة"
                                       :desc "متطلبات قابلة للمراجعة وخطوات تالية واضحة."}
                                      {:title "تنفيذ هادئ"
                                       :desc "تواصل واضح وبدون مبالغة."}]
                                 :ur [{:title "وضاحت اور ترتیب"
                                       :desc "درست ترتیب تاخیر اور ری ورک کم کرتی ہے۔"}
                                      {:title "تحریری چیک لسٹس"
                                       :desc "ریویو کے قابل تقاضے اور واضح اگلے اسٹیپس۔"}
                                      {:title "پرسکون ایگزیکیوشن"
                                       :desc "واضح کمیونیکیشن، بغیر اوور پرامسنگ۔"}]
                                 [{:title "Clarity and sequencing"
                                   :desc "Correct order reduces delays and rework."}
                                  {:title "Written checklists"
                                   :desc "Reviewable requirements and clear next steps."}
                                  {:title "Calm execution"
                                   :desc "Clear communication without overpromising."}])
                    speed-title (case lang
                                  :ar "كيف نحافظ على سرعة التنفيذ"
                                  :ur "ہم ایگزیکیوشن تیز کیسے رکھتے ہیں"
                                  "How we keep execution fast")
                    speed (case lang
                            :ar ["ننسق أي آراء متخصصة عبر شركاء مرخصين عند الحاجة"
                                 "نؤكد المواعيد والمخرجات بعد مراجعة الإدخالات والنطاق"
                                 "قائمة إدخالات قصيرة قبل الاستشارة تمنع التعثر لاحقًا"]
                            :ur ["ضرورت پر لائسنسڈ پارٹنرز کے ذریعے اسپیشلسٹ اوپینین کوآرڈینیٹ کرتے ہیں"
                                 "ان پٹس اور اسکوپ کے بعد ٹائم لائن اور ڈیلیوریبلز کنفرم کرتے ہیں"
                                 "مختصر پری کنسلٹ چیک لسٹ سے بعد میں رکاوٹیں کم ہوتی ہیں"]
                            ["We coordinate specialist opinions through licensed partners when needed"
                             "Timelines and deliverables are confirmed once we review your inputs and scope"
                             "A short pre-consult checklist keeps execution moving fast"])
                    next-cta (case lang
                               :ar "تواصل معنا"
                               :ur "ہم سے رابطہ کریں"
                               "Contact us")]
                (public-page {:title (case lang
                                       :ar "عن دار الوصل | دار الوصل"
                                       :ur "Dar El Wasl کے بارے میں | Dar El Wasl"
                                       "About | Dar El Wasl")
                              :description strapline
                              :public-base-url public-base-url
                              :base-path base-path
                              :lang lang
                              :path path
                              :image-path "/logo.jpg"
                              :contact contact}
                           (str (hero-simple headline strapline nil nil)
                                "<section><div class='section-title'><h2>" (escape-html what-title) "</h2></div>"
                                (bullet-list what)
                                "</section>"
                                "<section><div class='section-title'><h2>" (escape-html how-title) "</h2></div>"
                                "<div class='steps steps--light'>"
                                (apply str
                                       (map-indexed
                                        (fn [idx {:keys [title desc]}]
                                          (str "<div class='step'>"
                                               "<div class='step-index'>" (inc idx) "</div>"
                                               "<div><div class='label'>" (escape-html title) "</div>"
                                               "<div class='muted'>" (escape-html desc) "</div></div></div>"))
                                        how))
                                "</div></section>"
                                "<section><div class='section-title'><h2>" (escape-html principles-title) "</h2></div>"
                                "<div class='card-grid'>"
                                (apply str
                                       (for [{:keys [title desc]} principles]
                                         (str "<div class='card'>"
                                              "<h3>" (escape-html title) "</h3>"
                                              "<p class='muted'>" (escape-html desc) "</p>"
                                              "</div>")))
                                "</div></section>"
                                "<section><div class='section-title'><h2>" (escape-html speed-title) "</h2></div>"
                                (bullet-list speed)
                                "</section>"
                                "<section><div class='section-title'><h2>"
                                (escape-html (case lang :ar "الخطوة التالية" :ur "اگلا قدم" "Next step"))
                                "</h2></div>"
                                "<p class='muted'>" (escape-html (case lang
                                                              :ar "أرسل تفاصيلك وسنرد عليك بقائمة متطلبات وخطوتك التالية."
                                                              :ur "اپنی تفصیلات بھیجیں اور ہم تقاضوں کی چیک لسٹ اور اگلا قدم بھیجیں گے۔"
                                                              "Send your details and we’ll reply with your requirements checklist and next step."))
                                "</p>"
                                "<a class='cta primary' href='" (escape-html (href "/contact#consultation")) "'>" (escape-html next-cta) "</a>"
                                "</section>")))}

       "/contact"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (let [contact (merge public-defaults (or contact {}))
                    email (:email contact)
                    phone (:phone contact)
                    phone-display (or (:phone-display contact) phone)
                    phone-local (:phone-local contact)
                    sent? (= "1" (get (or query {}) "sent"))
                    error? (= "1" (get (or query {}) "error"))
                    page-title (case lang
                                 :ar "احجز استشارة"
                                 :ur "مشاورت طے کریں"
                                 "Schedule a consultation")
                    strapline (case lang
                               :ar "أرسل تفاصيلك وسنرد عليك بقائمة متطلبات مخصصة."
                               :ur "اپنی تفصیلات بھیجیں اور ہم آپ کو مخصوص چیک لسٹ کے ساتھ جواب دیں گے۔"
                               "Send your details and we’ll respond with a tailored checklist.")
                    form-title (case lang
                                 :ar "نموذج الاستشارة"
                                 :ur "مشاورتی فارم"
                                 "Consultation form")
                    form-note (case lang
                               :ar "املأ النموذج وسنرد عليك بقائمة متطلبات مخصصة."
                               :ur "فارم بھریں اور ہم آپ کو مخصوص چیک لسٹ کے ساتھ جواب دیں گے۔"
                               "Fill out the form and we’ll reply with a tailored checklist.")
                    inputs-title (case lang
                                   :ar "احجز استشارة"
                                   :ur "مشاورت طے کریں"
                                   "Schedule a consultation")
                    contact-title (case lang
                                    :ar "طرق التواصل"
                                    :ur "رابطے کے طریقے"
                                    "Contact methods")]
                (public-page {:title (case lang
                                       :ar "تواصل معنا | دار الوصل"
                                       :ur "رابطہ | Dar El Wasl"
                                       "Contact | Dar El Wasl")
                              :description (case lang
                                             :ar "احجز استشارة وأرسل أنشطتك وملف الملكية."
                                             :ur "مشاورت طے کریں اور اپنی سرگرمیاں اور ملکیت کی تفصیل بھیجیں۔"
                                             "Schedule a consultation and send your activities + ownership profile.")
                              :public-base-url public-base-url
                              :base-path base-path
                              :lang lang
                              :path path
                              :image-path "/logo.jpg"
                              :contact contact}
                             (str (hero-simple
                                   page-title
                                   strapline
                                   nil
                                   nil)
                                  "<section id='consultation'><div class='section-title'><h2>" (escape-html form-title) "</h2></div>"
                                  (when sent?
                                    (str "<div class='notice notice--success'>"
                                         (escape-html (case lang
                                                        :ar "تم استلام تفاصيلك. سنرد عليك بقائمة المتطلبات وخطوتك التالية."
                                                        :ur "آپ کی تفصیلات موصول ہو گئیں۔ ہم آپ کو تقاضوں کی چیک لسٹ اور اگلا قدم بھیجیں گے۔"
                                                        "Your details were received. We’ll reply with your requirements checklist and next step."))
                                         "</div>"))
                                  (when (and (not sent?) error?)
                                    (str "<div class='notice notice--error'>"
                                         (escape-html (case lang
                                                        :ar "يرجى إضافة الأنشطة وأحد وسائل التواصل (البريد الإلكتروني أو الهاتف)."
                                                        :ur "براہ کرم سرگرمیاں اور رابطہ (ای میل یا فون) شامل کریں۔"
                                                        "Please include activities and at least one contact method (email or phone)."))
                                         "</div>"))
                                  "<p class='muted'>" (escape-html form-note) "</p>"
                                  "<form class='gate-form' method='post' action='" (escape-html (href "/contact")) "'>"
                                  "<div class='form-grid'>"
                                  "<label>" (escape-html (case lang :ar "الأنشطة" :ur "سرگرمیاں" "Activities"))
                                  "<textarea rows='3' name='activities' placeholder='" (escape-html (case lang :ar "صف أنشطتك (1 إلى 3 أسطر)" :ur "اپنی سرگرمیاں بیان کریں (1 سے 3 لائنیں)" "Describe activities (1 to 3 lines)")) "'></textarea></label>"
                                  "<label>" (escape-html (case lang :ar "الملكية" :ur "ملکیت" "Ownership"))
                                  "<select name='ownership'>"
                                  "<option>" (escape-html (case lang :ar "فرد" :ur "انفرادی" "Individual")) "</option>"
                                  "<option>" (escape-html (case lang :ar "شركة أم" :ur "پیرنٹ کمپنی" "Parent company")) "</option>"
                                  "<option>" (escape-html (case lang :ar "مواطن خليجي" :ur "GCC" "GCC")) "</option>"
                                  "</select></label>"
                                  "<label>" (escape-html (case lang :ar "الجنسية / الإقامة" :ur "قومیت / رہائش" "Nationality / Residency"))
                                  "<select name='residency'>"
                                  "<option>" (escape-html (case lang :ar "سعودي" :ur "سعودی" "Saudi")) "</option>"
                                  "<option>" (escape-html (case lang :ar "مقيم" :ur "ریزیڈنٹ" "Resident")) "</option>"
                                  "<option>" (escape-html (case lang :ar "غير مقيم" :ur "نان ریزیڈنٹ" "Non-resident")) "</option>"
                                  "</select></label>"
                                  "<label>" (escape-html (case lang :ar "شهر البدء المستهدف" :ur "ہدف آغاز کا مہینہ" "Target start month"))
                                  "<input type='month' name='start_month'></label>"
                                  "<label>" (escape-html (case lang :ar "البريد الإلكتروني" :ur "ای میل" "Email"))
                                  "<input type='email' name='email' placeholder='name@example.com'></label>"
                                  "<label>" (escape-html (case lang :ar "الهاتف / واتساب" :ur "فون / واٹس ایپ" "Phone / WhatsApp"))
                                  "<input type='tel' name='phone' placeholder='" (escape-html phone-display) "'></label>"
                                  "<label>" (escape-html (case lang :ar "اللغة المفضلة" :ur "ترجیحی زبان" "Preferred language"))
                                  "<select name='lang'>"
                                  "<option value='en'>English</option>"
                                  "<option value='ar'>العربية</option>"
                                  "<option value='ur'>اردو</option>"
                                  "</select></label>"
                                  "</div>"
                                  "<div class='form-actions'>"
                                  "<button class='cta primary' type='submit'>" (escape-html (case lang :ar "إرسال التفاصيل" :ur "تفصیلات بھیجیں" "Send details")) "</button>"
                                  "<div class='muted'>" (escape-html (case lang :ar "سنرد عليك بقائمة متطلبات وخطوة تالية." :ur "ہم آپ کو چیک لسٹ اور اگلا قدم بھیجیں گے۔" "We respond with a checklist and next step.")) "</div>"
                                  "</div></form></section>"
                                  "<section><div class='section-title'><h2>" (escape-html inputs-title) "</h2></div>"
                                  (bullet-list (case lang
                                                 :ar ["الأنشطة (1 إلى 3 أسطر)"
                                                      "الملكية (فرد / شركة أم / خليجي)"
                                                      "شهر البدء المستهدف (الشهر كافٍ)"
                                                      "حالة الوثائق (جاهز / قيد التجهيز)"
                                                      "اللغة المفضلة (عربي/إنجليزي/أردو)"]
                                                 :ur ["سرگرمیاں (1 سے 3 لائنیں)"
                                                      "ملکیت (انفرادی / پیرنٹ کمپنی / GCC)"
                                                      "ہدف آغاز کی تاریخ (مہینہ کافی ہے)"
                                                      "دستاویزات کی حالت (تیار / جاری)"
                                                      "ترجیحی زبان (EN/AR/UR)"]
                                                 ["Activities (1 to 3 lines)"
                                                  "Ownership (individual / parent company / GCC)"
                                                  "Target start date (month is fine)"
                                                  "Current document status (ready / in progress)"
                                                  "Preferred language (EN/AR/UR)"]))
                                  "</section>"
                                  "<section><div class='section-title'><h2>" (escape-html contact-title) "</h2></div>"
                                  "<div class='contact-methods'>"
                                  "<div><div class='meta-sub'>" (escape-html (case lang :ar "البريد الإلكتروني" :ur "ای میل" "Email")) "</div>"
                                  "<div class='meta'><a class='text-link' href='mailto:" (escape-html email) "'>" (escape-html email) "</a></div></div>"
                                  "<div><div class='meta-sub'>" (escape-html (case lang :ar "الهاتف / واتساب" :ur "فون / واٹس ایپ" "Phone / WhatsApp")) "</div>"
                                  "<div class='meta'>"
                                  "<div><a class='text-link' href='tel:" (escape-html phone) "'>" (escape-html phone-display) "</a></div>"
                                  (when (and phone-local (not (str/blank? phone-local)))
                                    (str "<div><a class='text-link' href='tel:" (escape-html phone-local) "'>" (escape-html phone-local) "</a></div>"))
                                  "</div></div>"
	                                  "</div></section>")))}

	       "/privacy"
	       {:status 200
	        :headers {"Content-Type" "text/html; charset=utf-8"}
	        :body (let [page-title (case lang :ar "الخصوصية" :ur "پرائیویسی" "Privacy")
	                    strapline (case lang
	                               :ar "نستخدم تفاصيلك فقط للرد وإعداد قائمة متطلباتك."
	                               :ur "ہم آپ کی تفصیلات صرف جواب دینے اور تقاضوں کی چیک لسٹ تیار کرنے کے لیے استعمال کرتے ہیں۔"
	                               "We use your details only to respond and prepare your requirements checklist.")
	                    collect-title (case lang :ar "ما نجمعه" :ur "ہم کیا جمع کرتے ہیں" "What we collect")
	                    collect (case lang
	                              :ar "تفاصيل الاستشارة التي ترسلها (الأنشطة، الملكية، معلومات التواصل، والتفضيلات)."
	                              :ur "آپ کی بھیجی گئی مشاورتی تفصیلات (سرگرمیاں، ملکیت، رابطہ معلومات، اور ترجیحات)۔"
	                              "Consultation details you submit (activities, ownership, contact details, and preferences).")
	                    use-title (case lang :ar "كيف نستخدمه" :ur "ہم اسے کیسے استعمال کرتے ہیں" "How we use it")
	                    use (case lang
	                          :ar "للرد على طلبك، توضيح المتطلبات، وتقديم قائمة الخطوات التالية."
	                          :ur "آپ کی درخواست پر جواب دینے، تقاضوں کی وضاحت کرنے، اور اگلے مراحل کی چیک لسٹ دینے کے لیے۔"
	                          "To respond to your request, clarify requirements, and deliver the checklist for next steps.")
	                    retention-title (case lang :ar "الاحتفاظ" :ur "ریکارڈ/مدت" "Retention")
	                    retention (case lang
	                                :ar "نحتفظ بالطلبات لفترة كافية للمتابعة وإكمال الخدمة، ثم نحذفها عندما لا نحتاجها."
	                                :ur "ہم فالو اپ اور کام مکمل کرنے کے لیے مناسب مدت تک ریکارڈ رکھتے ہیں، پھر ضرورت نہ ہونے پر حذف کر دیتے ہیں۔"
	                                "We keep submissions long enough to complete follow-up and delivery, then remove them when no longer needed.")
	                    contact-title (case lang :ar "التواصل" :ur "رابطہ" "Contact")
	                    contact' (merge public-defaults (or contact {}))
	                    email (:email contact')
	                    next-cta (case lang :ar "احجز استشارة" :ur "مشاورت طے کریں" "Schedule a consultation")]
	                (public-page {:title (case lang
	                                       :ar "الخصوصية | دار الوصل"
	                                       :ur "پرائیویسی | Dar El Wasl"
	                                       "Privacy | Dar El Wasl")
	                              :description strapline
	                              :public-base-url public-base-url
	                              :base-path base-path
	                              :lang lang
	                              :path path
	                              :image-path "/logo.jpg"
	                              :contact contact'}
	                           (str (hero-simple page-title strapline nil nil)
	                                "<section><div class='stack'>"
	                                "<div class='card'><h3>" (escape-html collect-title) "</h3><p class='muted'>" (escape-html collect) "</p></div>"
	                                "<div class='card'><h3>" (escape-html use-title) "</h3><p class='muted'>" (escape-html use) "</p></div>"
	                                "<div class='card'><h3>" (escape-html retention-title) "</h3><p class='muted'>" (escape-html retention) "</p></div>"
	                                "<div class='card'><h3>" (escape-html contact-title) "</h3><p class='muted'>"
	                                (escape-html (case lang
	                                               :ar "لطلبات الخصوصية، راسل"
	                                               :ur "پرائیویسی درخواست کے لیے ای میل کریں"
	                                               "For privacy requests, email"))
	                                " <a class='text-link' href='mailto:" (escape-html email) "'>" (escape-html email) "</a>.</p></div>"
	                                "</div></section>"
	                                "<section><div class='section-title'><h2>" (escape-html (case lang :ar "الخطوة التالية" :ur "اگلا قدم" "Next step")) "</h2></div>"
	                                "<p class='muted'>" (escape-html (case lang
	                                                              :ar "أرسل تفاصيلك وسنرد عليك بقائمة متطلبات وخطوتك التالية."
	                                                              :ur "اپنی تفصیلات بھیجیں اور ہم تقاضوں کی چیک لسٹ اور اگلا قدم بھیجیں گے۔"
	                                                              "Send your details and we’ll reply with your requirements checklist and next step."))
	                                "</p>"
	                                "<a class='cta primary' href='" (escape-html (href "/contact#consultation")) "'>" (escape-html next-cta) "</a>"
	                                "</section>")))}

	       "/terms"
	       {:status 200
	        :headers {"Content-Type" "text/html; charset=utf-8"}
	        :body (let [page-title (case lang :ar "الشروط" :ur "شرائط" "Terms")
	                    strapline (case lang
	                               :ar "معلومات عامة وإرشادات استخدام الموقع."
	                               :ur "عمومی معلومات اور ویب سائٹ کے استعمال کی رہنمائی۔"
	                               "General information and website usage guidelines.")
	                    info-title (case lang :ar "معلومات" :ur "معلومات" "Information")
	                    info (case lang
	                           :ar "هذه الصفحة تقدم إرشادات عامة. نؤكد المتطلبات والنطاق خلال الاستشارة."
	                           :ur "یہ صفحہ عمومی رہنمائی ہے۔ ہم مشاورت کے دوران تقاضے اور اسکوپ کنفرم کرتے ہیں۔"
	                           "This page provides general guidance. We confirm requirements and scope during consultation.")
	                    guarantees-title (case lang
	                                      :ar "النتائج والمواعيد"
	                                      :ur "نتائج اور ٹائم لائن"
	                                      "Outcomes and timelines")
	                    guarantees (case lang
	                                 :ar "نحدد نطاق العمل والمخرجات المتوقعة قبل التنفيذ. وقد تتأثر المواعيد بالجهات والوثائق حسب الحالة."
	                                 :ur "ہم عمل شروع کرنے سے پہلے اسکوپ اور متوقع مخرجات واضح کرتے ہیں۔ ٹائم لائن کیس کے مطابق دستاویزات اور متعلقہ اداروں پر منحصر ہو سکتی ہے۔"
	                                 "We define scope and expected deliverables before execution. Timelines may vary depending on documentation and authorities.")
	                    contact-title (case lang :ar "التواصل" :ur "رابطہ" "Contact")
	                    contact' (merge public-defaults (or contact {}))
	                    email (:email contact')
	                    next-cta (case lang :ar "احجز استشارة" :ur "مشاورت طے کریں" "Schedule a consultation")]
	                (public-page {:title (case lang
	                                       :ar "الشروط | دار الوصل"
	                                       :ur "شرائط | Dar El Wasl"
	                                       "Terms | Dar El Wasl")
	                              :description strapline
	                              :public-base-url public-base-url
	                              :base-path base-path
	                              :lang lang
	                              :path path
	                              :image-path "/logo.jpg"
	                              :contact contact'}
	                           (str (hero-simple page-title strapline nil nil)
	                                "<section><div class='stack'>"
	                                "<div class='card'><h3>" (escape-html info-title) "</h3><p class='muted'>" (escape-html info) "</p></div>"
	                                "<div class='card'><h3>" (escape-html guarantees-title) "</h3><p class='muted'>" (escape-html guarantees) "</p></div>"
	                                "<div class='card'><h3>" (escape-html contact-title) "</h3><p class='muted'>"
	                                (escape-html (case lang :ar "للأسئلة، راسل" :ur "سوالات کے لیے ای میل کریں" "For questions, email"))
	                                " <a class='text-link' href='mailto:" (escape-html email) "'>" (escape-html email) "</a>.</p></div>"
	                                "</div></section>"
	                                "<section><div class='section-title'><h2>" (escape-html (case lang :ar "الخطوة التالية" :ur "اگلا قدم" "Next step")) "</h2></div>"
	                                "<p class='muted'>" (escape-html (case lang
	                                                              :ar "أرسل تفاصيلك وسنرد عليك بقائمة متطلبات وخطوتك التالية."
	                                                              :ur "اپنی تفصیلات بھیجیں اور ہم تقاضوں کی چیک لسٹ اور اگلا قدم بھیجیں گے۔"
	                                                              "Send your details and we’ll reply with your requirements checklist and next step."))
	                                "</p>"
	                                "<a class='cta primary' href='" (escape-html (href "/contact#consultation")) "'>" (escape-html next-cta) "</a>"
	                                "</section>")))}

	       "/cookies"
	       {:status 200
	        :headers {"Content-Type" "text/html; charset=utf-8"}
	        :body (let [page-title (case lang :ar "الكوكيز" :ur "کوکیز" "Cookies")
	                    strapline (case lang
	                               :ar "نستخدم الحد الأدنى من الكوكيز."
	                               :ur "ہم کم سے کم کوکیز استعمال کرتے ہیں۔"
	                               "We use minimal cookies.")
	                    essential-title (case lang :ar "الكوكيز الأساسية" :ur "ضروری کوکیز" "Essential cookies")
	                    essential (case lang
	                               :ar "قد تُستخدم لوظائف الموقع الأساسية وتفضيلات اللغة."
	                               :ur "سائٹ کی بنیادی فنکشنالٹی اور زبان کی ترجیحات کے لیے استعمال ہو سکتی ہیں۔"
	                               "May be used for basic site functionality and preferences.")
	                    marketing-title (case lang :ar "كوكيز تسويقية" :ur "مارکیٹنگ کوکیز" "Marketing cookies")
	                    marketing (case lang
	                               :ar "غير مستخدمة بشكل افتراضي على هذا الموقع."
	                               :ur "اس سائٹ پر ڈیفالٹ طور پر استعمال نہیں ہوتی۔"
	                               "Not used by default on this site.")
	                    questions-title (case lang :ar "الأسئلة" :ur "سوالات" "Questions")
	                    contact' (merge public-defaults (or contact {}))
	                    email (:email contact')
	                    next-cta (case lang :ar "احجز استشارة" :ur "مشاورت طے کریں" "Schedule a consultation")]
	                (public-page {:title (case lang
	                                       :ar "الكوكيز | دار الوصل"
	                                       :ur "کوکیز | Dar El Wasl"
	                                       "Cookies | Dar El Wasl")
	                              :description strapline
	                              :public-base-url public-base-url
	                              :base-path base-path
	                              :lang lang
	                              :path path
	                              :image-path "/logo.jpg"
	                              :contact contact'}
	                           (str (hero-simple page-title strapline nil nil)
	                                "<section><div class='stack'>"
	                                "<div class='card'><h3>" (escape-html essential-title) "</h3><p class='muted'>" (escape-html essential) "</p></div>"
	                                "<div class='card'><h3>" (escape-html marketing-title) "</h3><p class='muted'>" (escape-html marketing) "</p></div>"
	                                "<div class='card'><h3>" (escape-html questions-title) "</h3><p class='muted'>"
	                                (escape-html (case lang :ar "للأسئلة، راسل" :ur "سوالات کے لیے ای میل کریں" "For questions, email"))
	                                " <a class='text-link' href='mailto:" (escape-html email) "'>" (escape-html email) "</a>.</p></div>"
	                                "</div></section>"
	                                "<section><div class='section-title'><h2>" (escape-html (case lang :ar "الخطوة التالية" :ur "اگلا قدم" "Next step")) "</h2></div>"
	                                "<p class='muted'>" (escape-html (case lang
	                                                              :ar "أرسل تفاصيلك وسنرد عليك بقائمة متطلبات وخطوتك التالية."
	                                                              :ur "اپنی تفصیلات بھیجیں اور ہم تقاضوں کی چیک لسٹ اور اگلا قدم بھیجیں گے۔"
	                                                              "Send your details and we’ll reply with your requirements checklist and next step."))
	                                "</p>"
	                                "<a class='cta primary' href='" (escape-html (href "/contact#consultation")) "'>" (escape-html next-cta) "</a>"
	                                "</section>")))}

	       (public-not-found {:public-base-url public-base-url
	                          :base-path base-path
	                          :lang lang
	                          :path path}))))

(defn nav-links
  ([links current-path]
   (nav-links links current-path ""))
  ([links current-path base-path]
   (->> links
        (map (fn [{:keys [path label cta?]}]
               (let [active (= path current-path)
                     href (with-base base-path path)]
                 (format "<a href=\"%s\" class=\"nav-link %s %s\" aria-current=\"%s\">%s</a>"
                         (escape-html href)
                         (if active "active" "")
                         (when cta? "primary-cta")
                         (if active "page" "false")
                         (escape-html label)))))
        (apply str))))

(defn layout
  ([title nav body footer-cta]
   (layout title nav body footer-cta ""))
  ([title nav body footer-cta base-path]
  (let [preview-mode? (str/starts-with? (normalize-base-path base-path) "/_preview/")]
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>" (escape-html title) "</title>"
       "<link rel=\"stylesheet\" href=\"" (escape-html (with-base base-path "/css/theme.css")) "\">"
       "<link rel=\"stylesheet\" href=\"" (escape-html (with-base base-path "/css/main.css")) "\">"
       "<link rel=\"stylesheet\" href=\"" (escape-html (with-base base-path "/css/site.css")) "\">"
       (when preview-mode?
         (str "<link rel=\"stylesheet\" href=\"" (escape-html (with-base base-path "/css/preview-annotate.css")) "\">"))
       "<script>function toggleMenu(){document.body.classList.toggle('mobile-open');var btn=document.getElementById('mobile-toggle');if(btn){var open=document.body.classList.contains('mobile-open');btn.setAttribute('aria-expanded',open);if(!open){btn.focus();}}}</script>"
       (when preview-mode?
         (str "<script src=\"" (escape-html (with-base base-path "/preview-annotate.js")) "\"></script>"))
       "</head>"
       "<body data-theme=\"site-premium\">"
       "<header class=\"site-header\"><div class=\"shell\"><div class=\"nav-bar\">"
       "<div class=\"brand\" aria-label=\"Dar Alwasl\"><img src=\"" (escape-html (with-base base-path "/logo.jpg")) "\" alt=\"Dar Alwasl logo\" loading=\"lazy\"></div>"
       "<nav class=\"nav-links\" aria-label=\"Primary\">" nav "</nav>"
       "<button id=\"mobile-toggle\" class=\"mobile-toggle\" type=\"button\" aria-expanded=\"false\" aria-controls=\"mobile-menu\" onclick=\"toggleMenu()\">Menu</button>"
       "</div>"
       "<nav id=\"mobile-menu\" class=\"mobile-menu\" aria-label=\"Mobile\">" nav "</nav>"
       "</div></header>"
       "<main>" body "</main>"
       (or footer-cta "")
       "<footer class=\"site-footer\"><div class=\"footer-content\"><div>(c) Dar Alwasl - Public site</div><div>"
       "<a class=\"nav-link\" href=\"" (escape-html (with-base base-path "/contact")) "\">Contact</a>"
       "<a class=\"nav-link\" href=\"" (escape-html (with-base base-path "/about")) "\">About</a>"
       "</div></div></footer>"
       "</body></html>"))))

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
    (format "<section class=\"hero\"><p class=\"pill\">%s</p><h1 class=\"headline\">%s</h1><p class=\"strapline\">%s</p>%s<div class=\"meta\">Gate review: leave with your next step and required inputs.</div>%s</section>"
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
  ([licenses selected-type]
   (render-license-tabs licenses selected-type ""))
  ([licenses selected-type base-path]
   (let [ordered (sort-by #(or (:license/order %) Long/MAX_VALUE) licenses)
         selected (or (some #(when (= (:license/type %) selected-type) %) ordered)
                      (first ordered))
         tab-links (apply str
                          (map (fn [lic]
                                 (let [active (= (:license/id lic) (:license/id selected))
                                       t (some-> lic :license/type name (str/replace "license.type/" ""))
                                       href (with-base base-path (str "/services?type=" t))]
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
               (or details ""))))))

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
  ([business contact]
   (render-contact business contact ""))
  ([business contact base-path]
  (let [summary (or (:business/summary business)
                    "Share your structure, activities, and timing to get a plotted roadmap.")
        email (:contact/email contact)
        phone (:contact/phone contact)
        primary-label (:contact/primary-cta-label contact)
        primary-url (some-> (:contact/primary-cta-url contact) (with-base base-path))
        secondary-label (:contact/secondary-cta-label contact)
        secondary-url (some-> (:contact/secondary-cta-url contact) (with-base base-path))
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
              "")))))

(defn render-footer-cta
  ([business contact]
   (render-footer-cta business contact ""))
  ([business contact base-path]
  (let [headline (or (:business/hero-headline business) "Ready to start your Saudi setup?")
        strapline (or (:business/tagline business) "Schedule a meeting to map your path.")
        primary-label (or (:contact/primary-cta-label contact) "Schedule a meeting")
        primary-url (with-base base-path (or (:contact/primary-cta-url contact) "/contact"))
        secondary-label (:contact/secondary-cta-label contact)
        secondary-url (when-let [u (:contact/secondary-cta-url contact)]
                        (with-base base-path u))]
    (format "<div class=\"footer-cta\"><div class=\"inner\"><div><h3>%s</h3><p>%s</p></div><div class=\"actions\"><a class=\"cta primary\" href=\"%s\">%s</a>%s</div></div></div>"
            (escape-html headline)
            (escape-html strapline)
            (escape-html primary-url)
            (escape-html primary-label)
            (if secondary-label
              (format "<a class=\"cta secondary\" href=\"%s\">%s</a>"
                      (escape-html secondary-url)
                      (escape-html secondary-label))
              "")))))

(defn render-not-found
  ([path nav]
   (render-not-found path nav ""))
  ([path nav base-path]
  {:status 404
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (layout "Not found"
                 nav
                 (format "<h1>Page not found</h1><p>No content found at <strong>%s</strong>.</p>"
                         (escape-html path))
                 ""
                 base-path)}))

(defn html-response
  ([title nav body footer-cta]
   (html-response title nav body footer-cta ""))
  ([title nav body footer-cta base-path]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (layout title nav body footer-cta base-path)}))
