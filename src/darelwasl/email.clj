(ns darelwasl.email
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import (jakarta.mail Authenticator Message$RecipientType PasswordAuthentication Session Transport)
           (jakarta.mail.internet InternetAddress MimeMessage)
           (java.util Properties)))

(defn- present
  [v]
  (let [s (some-> v str str/trim)]
    (when-not (str/blank? s) s)))

(defn- ensure
  [ok? message]
  (when-not ok?
    {:error message}))

(defn send-smtp!
  "Send a plain-text email.

  cfg is `(:site cfg)` and expects:
  - :smtp {:host :port :starttls? :user :pass}
  - :mail-from

  payload expects:
  - :to (string or seq of strings)
  - :subject (string)
  - :text (string)"
  [site-cfg {:keys [to subject text]}]
  (let [smtp (:smtp site-cfg)
        host (present (:host smtp))
        port (or (:port smtp) 587)
        starttls? (true? (:starttls? smtp))
        user (present (:user smtp))
        pass (present (:pass smtp))
        from (present (:mail-from site-cfg))
        to' (cond
              (string? to) (->> (str/split to #",") (map str/trim) (remove str/blank?) vec)
              (sequential? to) (->> to (map present) (remove nil?) vec)
              :else [])]
    (or (ensure host "Missing SMTP host")
        (ensure from "Missing mail-from")
        (ensure (seq to') "Missing recipients")
        (ensure (present subject) "Missing subject")
        (ensure (present text) "Missing text")
        (ensure (and user pass) "Missing SMTP credentials")
        (try
          (let [props (doto (Properties.)
                        (.put "mail.smtp.host" host)
                        (.put "mail.smtp.port" (str port))
                        (.put "mail.smtp.auth" "true")
                        (.put "mail.smtp.starttls.enable" (if starttls? "true" "false"))
                        (.put "mail.smtp.starttls.required" (if starttls? "true" "false"))
                        (.put "mail.smtp.ssl.trust" host))
                session (Session/getInstance
                         props
                         (proxy [Authenticator] []
                           (getPasswordAuthentication []
                             (PasswordAuthentication. user pass))))
                msg (doto (MimeMessage. session)
                      (.setFrom (InternetAddress. from))
                      (.setSubject (str subject) "UTF-8")
                      (.setText (str text) "UTF-8"))]
            (doseq [addr to']
              (.addRecipient msg Message$RecipientType/TO (InternetAddress. addr)))
            (Transport/send msg)
            {:ok true})
          (catch Exception e
            (log/warn e "SMTP send failed")
            {:error (or (some-> e .getMessage present) "SMTP send failed")})))))

