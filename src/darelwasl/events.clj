(ns darelwasl.events
  (:require [clojure.string :as str])
  (:import (java.util Date UUID)))

(defn now
  "Return a java.util.Date representing now."
  []
  (Date.))

(defn- present-string?
  [v]
  (and (string? v) (not (str/blank? v))))

(defn- error
  [message & [details]]
  {:error {:status 400
           :message message
           :details details}})

(defn new-event
  "Create a normalized event envelope.

  Required keys:
  - :event/type (keyword)

  Optional keys:
  - :event/subject (map)
  - :event/payload (map)
  - :actor (map)
  - :event/id (uuid) (generated when missing)
  - :event/occurred-at (java.util.Date) (now when missing)"
  [{:event/keys [type subject payload id occurred-at]
    :keys [actor]
    :as m}]
  (cond
    (nil? type) (error "Missing event type")
    (not (keyword? type)) (error "Invalid event type; expected keyword" {:event/type type})
    (and (some? payload) (not (map? payload))) (error "Invalid event payload; expected map" {:event/payload payload})
    (and (some? subject) (not (map? subject))) (error "Invalid event subject; expected map" {:event/subject subject})
    (and (some? actor) (not (map? actor))) (error "Invalid event actor; expected map" {:actor actor})
    :else
    (cond-> {:event/id (or id (UUID/randomUUID))
             :event/type type
             :event/occurred-at (or occurred-at (now))}
      subject (assoc :event/subject subject)
      payload (assoc :event/payload payload)
      actor (assoc :actor actor)
      (and (present-string? (:event/source m))) (assoc :event/source (:event/source m)))))

