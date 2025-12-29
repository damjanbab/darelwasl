;; Capture public site screenshots via Playwright and store in the library.
(ns darelwasl.site.screenshots
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [darelwasl.bundles :as bundles]
            [darelwasl.files :as files])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)
           (java.util Date)))

(defn- now-inst
  []
  (Date/from (Instant/now)))

(defn- safe-host
  [host]
  (if (or (nil? host)
          (= host "0.0.0.0")
          (= host "0:0:0:0:0:0:0:0"))
    "127.0.0.1"
    host))

(defn- site-base-url
  [config]
  (let [{:keys [host port]} (:site config)]
    (format "http://%s:%s" (safe-host host) port)))

(defn- default-pages
  []
  [{:path "/" :label "Home"}
   {:path "/services" :label "Services"}
   {:path "/comparison" :label "Comparison"}
   {:path "/process" :label "Process"}
   {:path "/about" :label "About"}
   {:path "/contact" :label "Contact"}])

(defn- default-viewports
  []
  [{:name "desktop" :width 1440 :height 900}
   {:name "mobile" :width 390 :height 844}])

(defn- default-title
  []
  (format "Site screenshots %s" (subs (str (now-inst)) 0 19)))

(defn- write-json!
  [path data]
  (with-open [w (io/writer path)]
    (.write w (json/write-str data))))

(defn- read-json
  [path]
  (with-open [r (io/reader path)]
    (json/read r :key-fn keyword)))

(defn- run-playwright!
  [config out-dir]
  (let [config-path (.getPath (io/file out-dir "config.json"))
        manifest-path (.getPath (io/file out-dir "manifest.json"))
        root (System/getProperty "user.dir")
        _ (write-json! config-path (assoc config :manifest "manifest.json"))
        result (sh/sh "node" "scripts/site-screenshot.js"
                      "--config" config-path
                      "--out" out-dir
                      :dir root)]
    (if (zero? (:exit result))
      (let [manifest (read-json manifest-path)]
        {:manifest manifest})
      {:error {:status 500
               :message "Screenshot capture failed"
               :details (or (:err result) (:out result))}})))

(defn- create-library-file!
  [state {:keys [path filename mime slug]} actor]
  (let [file (io/file path)
        upload {:filename filename
                :content-type mime
                :tempfile file
                :size (.length file)}]
    (files/create-file! (get-in state [:db :conn])
                        {:file upload
                         :slug slug}
                        actor
                        (get-in state [:config :files :storage-dir]))))

(defn capture-site-bundle!
  [state {:keys [input actor]}]
  (let [base-url (site-base-url (:config state))
        pages (or (:pages input) (default-pages))
        viewports (or (:viewports input) (default-viewports))
        title (or (:bundle/title input) (:title input) (default-title))
        bundle-type (or (:bundle/type input) :bundle.type/site-screenshot)
        tmp-dir (-> (Files/createTempDirectory "site-screens" (make-array FileAttribute 0))
                    (.toFile)
                    (.getPath))]
    (try
      (let [{:keys [error manifest]} (run-playwright! {:baseUrl base-url
                                                       :pages pages
                                                       :viewports viewports
                                                       :fullPage true}
                                                      tmp-dir)]
        (if error
          {:error error}
          (let [items (:items manifest)
                created (reduce (fn [acc item]
                                  (if (:error acc)
                                    (reduced acc)
                                    (let [res (create-library-file! state
                                                                    {:path (str (io/file tmp-dir (:file item)))
                                                                     :filename (:filename item)
                                                                     :mime (:mime item)
                                                                     :slug (:slug item)}
                                                                    actor)]
                                      (if-let [err (:error res)]
                                        {:error err}
                                        (update acc :files conj (:file res))))))
                                {:files []}
                                items)]
            (if-let [err (:error created)]
              {:error err}
              (let [bundle-res (bundles/create-bundle! (get-in state [:db :conn])
                                                       {:title title
                                                        :type bundle-type
                                                        :files (map :file/id (:files created))}
                                                       actor)]
                (if-let [err (:error bundle-res)]
                  {:error err}
                  {:bundle (:bundle bundle-res)
                   :files (:files created)}))))))
      (finally
        (try
          (doseq [entry (reverse (file-seq (io/file tmp-dir)))]
            (when (.isFile entry)
              (.delete entry)))
          (.delete (io/file tmp-dir))
          (catch Exception _))))))
