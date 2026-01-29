(ns darelwasl.fx
  (:require [re-frame.core :as rf]))

(defn- preview-app-base-path
  []
  (try
    (let [path (.-pathname js/location)
          m (re-matches #"^(/_preview/[^/]+/app)(?:/.*)?$" path)]
      (when (and m (string? (second m)))
        (second m)))
    (catch :default _ nil)))

(defn- maybe-prefix-preview
  [url]
  (let [u (or url "")]
    (cond
      (or (not (string? u))
          (= "" u)
          (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*://" u)) u
      (not (re-find #"^/api(?:/|$)" u)) u
      :else (if-let [base (preview-app-base-path)]
              (str base u)
              u))))

(defn- ensure-headers [base has-body? extra]
  (let [default {"Accept" "application/json"}
        content {"Content-Type" "application/json"
                 "Accept" "application/json"}]
    (cond-> (merge default base extra)
      has-body? (merge content))))

(defn- json-body [body]
  (cond
    (nil? body) nil
    (string? body) body
    :else (js/JSON.stringify (clj->js body))))

(defn- handle-response [resp on-success on-error]
  (-> (.json resp)
      (.then
       (fn [data]
         (let [payload (js->clj data :keywordize-keys true)
               status (.-status resp)]
           (if (<= 200 status 299)
             (rf/dispatch (conj on-success payload))
             (rf/dispatch (conj on-error {:status status
                                          :body payload}))))))
      (.catch
       (fn [_]
         (rf/dispatch (conj on-error {:status (.-status resp)
                                      :body {:error "Invalid response from server"}}))))))

(defn- handle-response-text [resp on-success on-error]
  (-> (.text resp)
      (.then
       (fn [text]
         (let [status (.-status resp)]
           (if (<= 200 status 299)
             (rf/dispatch (conj on-success {:text text :status status}))
             (rf/dispatch (conj on-error {:status status
                                          :body {:error text}}))))))
      (.catch
       (fn [_]
         (rf/dispatch (conj on-error {:status (.-status resp)
                                      :body {:error "Invalid response from server"}}))))))

(rf/reg-fx
 ::http
 (fn [{:keys [url method body headers credentials on-success on-error]}]
   (let [url (maybe-prefix-preview url)
         has-body? (some? body)
         opts (clj->js (cond-> {:method (or method "GET")
                                :headers (ensure-headers headers has-body? nil)
                                :credentials (or credentials "same-origin")}
                         has-body? (assoc :body (json-body body))))]
     (-> (js/fetch url opts)
         (.then #(handle-response % on-success on-error))
         (.catch
          (fn [_]
            (rf/dispatch (conj on-error {:status nil
                                         :body {:error "Network error. Please try again."}}))))))))

(rf/reg-fx
 ::http-form
 (fn [{:keys [url method form-data headers credentials on-success on-error]}]
   (let [url (maybe-prefix-preview url)
         opts (clj->js (cond-> {:method (or method "POST")
                                :credentials (or credentials "same-origin")
                                :headers (ensure-headers headers false nil)}
                         form-data (assoc :body form-data)))]
     (-> (js/fetch url opts)
         (.then #(handle-response % on-success on-error))
         (.catch
          (fn [_]
            (rf/dispatch (conj on-error {:status nil
                                         :body {:error "Network error. Please try again."}}))))))))

(rf/reg-fx
 ::http-text
 (fn [{:keys [url method body headers credentials on-success on-error]}]
   (let [url (maybe-prefix-preview url)
         has-body? (some? body)
         opts (clj->js (cond-> {:method (or method "GET")
                                :headers (ensure-headers headers has-body? nil)
                                :credentials (or credentials "same-origin")}
                         has-body? (assoc :body (json-body body))))]
     (-> (js/fetch url opts)
         (.then #(handle-response-text % on-success on-error))
         (.catch
          (fn [_]
            (rf/dispatch (conj on-error {:status nil
                                         :body {:error "Network error. Please try again."}}))))))))

(rf/reg-fx
 ::dispatch-later
 (fn [{:keys [ms dispatch]}]
   (when (and (number? ms) dispatch)
     (js/setTimeout #(rf/dispatch dispatch) ms))))
