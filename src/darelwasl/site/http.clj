(ns darelwasl.site.http
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.content :as content]))

(defn- escape-html [s]
  (let [text (str s)]
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

(defn- render-block
  [block]
  (let [title (or (:content.block/title block)
                  (str/capitalize (name (:content.block/type block))))
        body (or (:content.block/body block) "")
        type (:content.block/type block)]
    (format "<section class=\"block block-%s\"><h2>%s</h2><p>%s</p></section>"
            (name type)
            (escape-html title)
            (escape-html body))))

(defn- nav-links
  [pages current-path]
  (->> pages
       (sort-by #(or (:content.page/navigation-order %) Long/MAX_VALUE))
       (map (fn [p]
              (let [path (:content.page/path p)
                    label (or (:content.page/title p) path)
                    active (= path current-path)]
                (format "<a href=\"%s\" class=\"nav-link %s\">%s</a>"
                        (escape-html path)
                        (if active "active" "")
                        (escape-html label)))))
       (apply str)))

(defn- layout
  [title nav body]
  (format (str "<!doctype html><html><head><meta charset=\"utf-8\">"
               "<title>%s</title><style>%s</style></head>"
               "<body><header><div class=\"brand\">Darel Wasl Public Site</div>"
               "<nav>%s</nav></header><main>%s</main>"
               "<footer><p>Powered by Darel Wasl content</p></footer></body></html>")
          (escape-html title)
          "body{font-family:Inter,system-ui,-apple-system,sans-serif;margin:0;padding:0;background:#f8fafc;color:#0f172a;}header{padding:16px 24px;background:white;border-bottom:1px solid #e2e8f0;display:flex;align-items:center;justify-content:space-between;position:sticky;top:0;}nav{display:flex;gap:12px;flex-wrap:wrap;}main{max-width:960px;margin:32px auto;padding:0 24px 48px;}footer{padding:16px 24px;border-top:1px solid #e2e8f0;font-size:14px;color:#475569;}h1{font-size:32px;margin-bottom:12px;}h2{font-size:20px;margin:16px 0 8px;}p{line-height:1.6;}a.nav-link{padding:8px 12px;border-radius:8px;text-decoration:none;color:#0f172a;background:#e2e8f0;transition:background .15s;}a.nav-link.active{background:#0f172a;color:white;}a.nav-link:hover{background:#cbd5e1;}section.block{background:white;border:1px solid #e2e8f0;border-radius:12px;padding:16px;margin-bottom:16px;box-shadow:0 1px 2px rgba(15,23,42,0.08);} .hero h1{font-size:36px;margin:0 0 8px;} .hero p{font-size:18px;color:#334155;}"
          nav
          body))

(defn- render-page
  [page blocks pages]
  (let [ordered-blocks (->> blocks
                            (sort-by #(or (:content.block/order %) Long/MAX_VALUE))
                            (map render-block)
                            (apply str))
        title (or (:content.page/title page) "Page")]
    (layout title
            (nav-links pages (:content.page/path page))
            (format "<article class=\"page\"><h1>%s</h1><p class=\"summary\">%s</p>%s</article>"
                    (escape-html title)
                    (escape-html (or (:content.page/summary page) ""))
                    ordered-blocks))))

(defn- render-index
  [pages]
  (layout "Site index"
          (nav-links pages nil)
          (format "<section><h1>Site pages</h1><ul>%s</ul></section>"
                  (apply str
                         (for [p (sort-by #(or (:content.page/navigation-order %) Long/MAX_VALUE) pages)]
                           (format "<li><a href=\"%s\">%s</a> — %s</li>"
                                   (escape-html (:content.page/path p))
                                   (escape-html (or (:content.page/title p) (:content.page/path p)))
                                   (escape-html (or (:content.page/summary p) ""))))))))

(defn- render-not-found
  [path pages]
  {:status 404
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (layout "Not found"
                 (nav-links pages nil)
                 (format "<h1>Page not found</h1><p>No content found at <strong>%s</strong>.</p>%s"
                         (escape-html path)
                         (render-index pages)))})

(defn- html-response
  [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})

(defn handle-request
  [{:keys [db]} request]
  (let [start (System/nanoTime)
        conn (:conn db)
        {:keys [pages]} (content/list-pages conn {:with-blocks? true})
        {:keys [blocks]} (content/list-blocks conn {})
        visible-pages (->> pages (filter #(not= false (:content.page/visible? %))) vec)
        visible-blocks (->> blocks (filter #(not= false (:content.block/visible? %))) vec)
        block-index (into {} (map (fn [b] [(:content.block/id b) b]) visible-blocks))
        raw-path (:uri request)
        clean-path (if (and (not= raw-path "/") (str/ends-with? raw-path "/"))
                     (subs raw-path 0 (dec (count raw-path)))
                     raw-path)
        target-page (some #(when (= (:content.page/path %) clean-path) %) visible-pages)
        response (cond
                   target-page
                   (let [block-ids (map #(ref-id % :content.block/id) (:content.page/blocks target-page))
                         blocks-for-page (->> block-ids
                                              (map block-index)
                                              (remove nil?))]
                     (html-response (render-page target-page blocks-for-page visible-pages)))

                   (= clean-path "/")
                   (html-response (render-index visible-pages))

                   :else
                   (render-not-found clean-path visible-pages))
        dur-ms (/ (double (- (System/nanoTime) start)) 1e6)]
    (log/infof "site request path=%s status=%s dur=%.1fms pages=%s blocks=%s"
               clean-path
               (:status response)
               dur-ms
               (count visible-pages)
               (count visible-blocks))
    response))

(defn app
  "Ring handler for the public site process."
  [state]
  (fn [request]
    (handle-request state request)))
