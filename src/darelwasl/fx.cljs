(ns darelwasl.fx
  (:require [re-frame.core :as rf]))

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

(rf/reg-fx
 ::http
 (fn [{:keys [url method body headers credentials on-success on-error]}]
   (let [has-body? (some? body)
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
   (let [opts (clj->js (cond-> {:method (or method "POST")
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
 ::dispatch-later
 (fn [{:keys [ms dispatch]}]
   (when (and (number? ms) dispatch)
     (js/setTimeout #(rf/dispatch dispatch) ms))))
