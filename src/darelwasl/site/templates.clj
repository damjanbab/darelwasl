(ns darelwasl.site.templates
  (:require [clojure.string :as str]))

(def ^:private public-defaults
  {:company-name "Dar El Wasl"
   :site-name "Dar El Wasl"
   :email "contact@darelwasl.com"
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
                   [(link "/resources/saudi-business-setup-guide" (:saudi-guide labels))
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

(defn- render-public-footer
  [base-path]
  (str "<footer class='site-footer'><div class='footer-content'><div>(c) Dar El Wasl - Public site</div><div>"
       "<a class='nav-link' href='" (escape-html (with-base base-path "/contact#consultation")) "'>Contact</a>"
       "<a class='nav-link' href='" (escape-html (with-base base-path "/about")) "'>About</a>"
       "</div></div></footer>"))

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
         (render-public-footer base-path)
         "</body></html>")))

(defn- hero-simple
  [headline strapline image alt]
  (str "<section class='hero hero--simple'>"
       "<div class='hero-copy'><h1 class='headline'>" (escape-html headline) "</h1>"
       "<p class='strapline'>" (escape-html strapline) "</p></div>"
       "<div class='hero-media hero-media--compact'>"
       "<img src='" (escape-html image) "' alt='" (escape-html alt) "' loading='lazy'>"
       "</div></section>"))

(defn- hero-split
  [{:keys [headline-html strapline primary secondary image alt]}]
  (str "<section class='hero hero--split'>"
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
       "<div class='hero-media'>"
       "<img src='" (escape-html image) "' alt='" (escape-html alt) "' loading='lazy'>"
       "</div></section>"))

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
                       :image-path "/images/saudi-business.png"}
                      (str "<section class='section-pad'><h1>Page not found</h1>"
                           "<p>No content found at <strong>" (escape-html path) "</strong>.</p></section>"))})

(defn public-route
  [{:keys [public-base-url base-path lang path contact]}]
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
                       :ar [{:title "الاستشارة (15–30 دقيقة)"
                             :desc "نؤكد الأنشطة والملكية، ونحدد الخيار الأنسب. تحصل على قائمة متطلبات واضحة."}
                            {:title "تنفيذ التأسيس والترخيص"
                             :desc "نرتب الوثائق والترجمات والتقديمات والموافقات خطوة بخطوة."}
                            {:title "التفعيل + استمرارية PRO"
                             :desc "نساعدك للوصول إلى جاهزية التشغيل ونبقي مهامك الحكومية تتحرك."}]
                       :ur [{:title "مشاورت (15–30 منٹ)"
                             :desc "ہم آپ کی سرگرمیوں اور ملکیت کی توثیق کرتے ہیں اور درست اختیار منتخب کرتے ہیں۔ آپ کو تقاضوں کی فہرست ملتی ہے۔"}
                            {:title "انکارپوریشن + لائسنسنگ عمل"
                             :desc "ہم دستاویزات، ترجمہ، جمع کرانے اور منظوریوں کو مرحلہ وار منظم کرتے ہیں۔"}
                            {:title "ایکٹیویشن + PRO تسلسل"
                             :desc "ہم آپ کو آپریشنل ریڈی تک پہنچاتے ہیں اور حکومتی کام جاری رکھتے ہیں۔"}]
                       [{:title "Consultation (15–30 minutes)"
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
                   :ar [{:q "هل تقدمون دراسات جدوى؟" :a "لا. نركز على الترخيص، التأسيس، التفعيل، وخدمات PRO/GRO التنفيذية."}
                        {:q "هل يمكنكم المساعدة إذا كنت خارج السعودية؟" :a "نعم. يمكن تجهيز معظم الخطوات عن بُعد؛ وقد تتطلب بعض الحالات حضورًا حسب الوضع."}
                        {:q "هل تنشرون الأسعار والجداول الزمنية؟" :a "لا ننشر أسعارًا ثابتة لأن المتطلبات تختلف. بعد الاستشارة تحصل على نطاق واضح وتوقعات."}
                        {:q "هل تدعمون العربية والإنجليزية والأردو؟" :a "نعم. دعم متعدد اللغات للتواصل والوثائق حسب الحاجة."}
                        {:q "هل يمكنكم مساعدتي للبدء في المملكة المتحدة أيضًا؟" :a "نعم. يمكننا المساعدة في تأسيس شركة UK Ltd وشرح خطوات الامتثال الأساسية."}
                        {:q "أين تعملون؟" :a "نعمل من جدة ونخدم العملاء عن بُعد عبر المملكة."}]
                   :ur [{:q "کیا آپ feasibility studies فراہم کرتے ہیں؟" :a "نہیں۔ ہم لائسنسنگ، سیٹ اپ، ایکٹیویشن، اور PRO/GRO عملدرآمد پر فوکس کرتے ہیں۔"}
                        {:q "کیا آپ سعودی عرب سے باہر ہونے کی صورت میں مدد کر سکتے ہیں؟" :a "جی ہاں۔ زیادہ تر تیاری ریموٹ ہو سکتی ہے؛ کچھ مراحل کیس کے مطابق موجودگی چاہ سکتے ہیں۔"}
                        {:q "کیا آپ قیمتیں اور ٹائم لائنز شائع کرتے ہیں؟" :a "ہم فکسڈ قیمتیں شائع نہیں کرتے کیونکہ تقاضے مختلف ہوتے ہیں۔ مشاورت کے بعد آپ کو واضح اسکوپ ملتا ہے۔"}
                        {:q "کیا آپ English, Arabic اور Urdu سپورٹ کرتے ہیں؟" :a "جی ہاں۔ ضرورت کے مطابق کثیر لسانی سپورٹ۔"}
                        {:q "کیا آپ UK میں بھی آغاز میں مدد کر سکتے ہیں؟" :a "جی ہاں۔ ہم UK Ltd سیٹ اپ اور بنیادی کمپلائنس اقدامات سمجھا سکتے ہیں۔"}
                        {:q "آپ کہاں کام کرتے ہیں؟" :a "ہم جدہ سے آپریٹ کرتے ہیں اور پورے KSA میں ریموٹ سروس دیتے ہیں۔"}]
                   [{:q "Do you offer feasibility studies?" :a "No. We focus on licensing, incorporation, activation, and PRO/GRO execution."}
                    {:q "Can you help if I’m outside Saudi Arabia?" :a "Yes. Most of the process can be prepared remotely; some steps may require presence depending on your case."}
                    {:q "Do you publish prices and timelines?" :a "We don’t publish fixed prices because requirements differ. After the consultation, you receive a tailored scope and expectations."}
                    {:q "Do you support English, Arabic, and Urdu?" :a "Yes. Multilingual support for communication and documents when needed."}
                    {:q "Can you help me start in the UK too?" :a "Yes. We can set up a UK Ltd and guide you through basic compliance steps."}
                    {:q "Where do you operate?" :a "We operate from Jeddah and serve clients remotely across the Kingdom."}])
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
                                       "Saudi Business Setup (Jeddah) | Dar El Wasl")
                              :description (case lang
                                             :ar "تأسيس منظم وخدمات PRO/GRO للمؤسسين والشركات الدولية. ابدأ باستشارة للحصول على قائمة متطلبات واضحة."
                                             :ur "منظم سیٹ اپ اور PRO/GRO سروسز۔ تقاضوں کی فہرست کے لیے مشاورت طے کریں۔"
                                             "Structured setup options and PRO/GRO services. Start with a consultation to get a clear checklist.")
                              :public-base-url public-base-url
                              :base-path base-path
                              :lang lang
                              :path path
                              :image-path "/images/saudi-business.png"
                              :contact contact}
                         (str (hero-split {:headline-html headline-html
                                           :strapline strapline
                                           :primary cta-primary
                                           :secondary cta-secondary
                                           :image (href "/images/saudi-business.png")
                                           :alt "Saudi city skyline"})
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
                     :image-path "/images/saudi-business.png"
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
                          (href "/images/saudi-business.png")
                          "Saudi city skyline")
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
                         "</section>"))}

       "/saudi/foreign-investors"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (public-page {:title "Foreign Investor Setup | Saudi Arabia"
                     :description "Foreign investor route overview and required inputs."
                     :public-base-url public-base-url
                     :base-path base-path
                     :lang lang
                     :path path
                     :image-path "/images/saudi-hero.png"
                     :contact contact}
                    (str (hero-simple
                          "Foreign investor setup"
                          "For international founders and foreign companies expanding into KSA."
                          (href "/images/saudi-hero.png")
                          "Saudi skyline")
                         "<section class='section-pad'><p>We help you pick the correct license path, prepare the required documents, and complete activation so you can operate.</p></section>"))}

       "/saudi/entrepreneur"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (public-page {:title "Entrepreneur Setup | Saudi Arabia"
                     :description "Entrepreneur setup route overview and required inputs."
                     :public-base-url public-base-url
                     :base-path base-path
                     :lang lang
                     :path path
                     :image-path "/images/saudi-hero.png"
                     :contact contact}
                    (str (hero-simple
                          "Entrepreneur setup"
                          "For founder-led startups with the right support documentation."
                          (href "/images/saudi-hero.png")
                          "Saudi skyline")
                         "<section class='section-pad'><p>We guide you through eligibility, documentation, licensing, and activation.</p></section>"))}

       "/saudi/gcc"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (public-page {:title "GCC Nationals Setup | Saudi Arabia"
                     :description "GCC nationals setup route overview and required inputs."
                     :public-base-url public-base-url
                     :base-path base-path
                     :lang lang
                     :path path
                     :image-path "/images/saudi-hero.png"
                     :contact contact}
                    (str (hero-simple
                          "GCC nationals setup"
                          "For GCC nationals who want a fast, compliant setup."
                          (href "/images/saudi-hero.png")
                          "Saudi skyline")
                         "<section class='section-pad'><p>We streamline licensing and activation with a clear checklist.</p></section>"))}

       "/saudi/pro-services"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (public-page {:title "PRO / GRO Services | Saudi Arabia"
                     :description "Operational PRO/GRO support after setup."
                     :public-base-url public-base-url
                     :base-path base-path
                     :lang lang
                     :path path
                     :image-path "/images/process.png"
                     :contact contact}
                    (str (hero-simple
                          "PRO / GRO services"
                          "Ongoing operational support for compliance and portals."
                          (href "/images/process.png")
                          "Process")
                         "<section class='section-pad'><p>We keep your company operational with renewals, registrations, and compliance steps handled calmly and on time.</p></section>"))}

       "/saudi/activation"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (public-page {:title "Activation & Compliance | Saudi Arabia"
                     :description "Activation steps that make your company operational."
                     :public-base-url public-base-url
                     :base-path base-path
                     :lang lang
                     :path path
                     :image-path "/images/checklist.png"
                     :contact contact}
                    (str (hero-simple
                          "Activation & compliance"
                          "Don’t stop at license issuance. Get operational-ready."
                          (href "/images/checklist.png")
                          "Checklist")
                         "<section class='section-pad'><p>Activation includes portal accounts, registrations, address/lease steps, and ongoing compliance. We guide the sequence.</p></section>"))}

       "/uk"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (public-page {:title "Start in the UK | Company Formation"
                     :description "UK company formation overview."
                     :public-base-url public-base-url
                     :base-path base-path
                     :lang lang
                     :path path
                     :image-path "/images/uk-hero.png"
                     :contact contact}
                    (str (hero-simple
                          "Start in the UK"
                          "A clean setup path for UK company formation and readiness."
                          (href "/images/uk-hero.png")
                          "UK")
                         "<section class='section-pad'><p>We support company formation and a clean handoff for banking, accounting, and operational readiness.</p></section>"))}

       "/uk/company-formation"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (public-page {:title "UK Ltd Company Formation"
                     :description "UK Ltd company formation guide."
                     :public-base-url public-base-url
                     :base-path base-path
                     :lang lang
                     :path path
                     :image-path "/images/uk-hero.svg"
                     :contact contact}
                    (str (hero-simple
                          "UK Ltd Company Formation"
                          "Clear steps, required inputs, and practical outputs."
                          (href "/images/uk-hero.svg")
                          "UK")
                         "<section class='section-pad'><p>Tell us your shareholders, directors, and structure. We handle the formation steps and provide the checklist for the next phase.</p></section>"))}

       "/resources/saudi-business-setup-guide"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (public-page {:title "Saudi Business Setup Guide"
                     :description "A structured guide to setup options, inputs, and activation."
                     :public-base-url public-base-url
                     :base-path base-path
                     :lang lang
                     :path path
                     :image-path "/images/documents.png"
                     :contact contact}
                    (str (hero-simple
                          "Saudi Setup Guide"
                          "A structured guide to setup options, inputs, and activation."
                          (href "/images/documents.png")
                          "Documents")
                         "<section class='section-pad'><p>This guide summarizes setup options and what you need to prepare before licensing and activation.</p></section>"))}

       "/resources/uk-company-formation-guide"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (public-page {:title "UK Company Formation Guide"
                     :description "A practical guide for UK company formation."
                     :public-base-url public-base-url
                     :base-path base-path
                     :lang lang
                     :path path
                     :image-path "/images/documents.png"
                     :contact contact}
                    (str (hero-simple
                          "UK Formation Guide"
                          "A practical guide for UK company formation."
                          (href "/images/documents.png")
                          "Documents")
                         "<section class='section-pad'><p>This guide summarizes formation inputs and the expected outputs for a clean start.</p></section>"))}

       "/resources/blog"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (public-page {:title "Blog"
                     :description "Updates and short notes."
                     :public-base-url public-base-url
                     :base-path base-path
                     :lang lang
                     :path path
                     :image-path "/images/process.svg"
                     :contact contact}
                    (str (hero-simple
                          "Blog"
                          "Short notes and updates."
                          (href "/images/process.svg")
                          "Blog")
                         "<section class='section-pad'><p>Coming soon.</p></section>"))}

       "/resources/faqs"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (public-page {:title "FAQs"
                     :description "Common questions."
                     :public-base-url public-base-url
                     :base-path base-path
                     :lang lang
                     :path path
                     :image-path "/images/checklist.svg"
                     :contact contact}
                    (str (hero-simple
                          "FAQs"
                          "Common questions and quick answers."
                          (href "/images/checklist.svg")
                          "FAQs")
                         "<section class='section-pad'><p>Coming soon.</p></section>"))}

       "/about"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (public-page {:title "About"
                     :description "About Dar El Wasl."
                     :public-base-url public-base-url
                     :base-path base-path
                     :lang lang
                     :path path
                     :image-path "/images/process.png"
                     :contact contact}
                    (str (hero-simple
                          "About Dar El Wasl"
                          "Principles and operating model for calm, evidence-led execution."
                          (href "/images/process.png")
                          "About")
                         "<section class='section-pad'><p>We operate with checklists, clear sequencing, and a calm, evidence-led approach.</p></section>"))}

       "/contact"
       {:status 200
        :headers {"Content-Type" "text/html; charset=utf-8"}
        :body (let [contact (merge public-defaults (or contact {}))
                    email (:email contact)
                    phone (:phone contact)
                    phone-display (or (:phone-display contact) phone)
                    phone-local (:phone-local contact)
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
                             :image-path "/images/documents.png"
                              :contact contact}
                             (str (hero-simple
                                   page-title
                                   strapline
                                   (href "/images/documents.png")
                                   "Documents")
                                  "<section id='consultation'><div class='section-title'><h2>" (escape-html form-title) "</h2></div>"
                                  "<p class='muted'>" (escape-html form-note) "</p>"
                                  "<form class='gate-form' onsubmit='return false;'>"
                                  "<div class='form-grid'>"
                                  "<label>" (escape-html (case lang :ar "الأنشطة" :ur "سرگرمیاں" "Activities"))
                                  "<textarea rows='3' name='activities' placeholder='" (escape-html (case lang :ar "صف أنشطتك (1–3 أسطر)" :ur "اپنی سرگرمیاں بیان کریں (1–3 لائنیں)" "Describe activities (1–3 lines)")) "'></textarea></label>"
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
                                                 :ar ["الأنشطة (1–3 أسطر)"
                                                      "الملكية (فرد / شركة أم / خليجي)"
                                                      "شهر البدء المستهدف (الشهر كافٍ)"
                                                      "حالة الوثائق (جاهز / قيد التجهيز)"
                                                      "اللغة المفضلة (عربي/إنجليزي/أردو)"]
                                                 :ur ["سرگرمیاں (1–3 لائنیں)"
                                                      "ملکیت (انفرادی / پیرنٹ کمپنی / GCC)"
                                                      "ہدف آغاز کی تاریخ (مہینہ کافی ہے)"
                                                      "دستاویزات کی حالت (تیار / جاری)"
                                                      "ترجیحی زبان (EN/AR/UR)"]
                                                 ["Activities (1–3 lines)"
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
