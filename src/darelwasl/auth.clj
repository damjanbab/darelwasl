(ns darelwasl.auth
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io PushbackReader)))

(def default-users-fixture "fixtures/users.edn")

(defn read-users
  "Read user fixtures from disk. Throws if the file is missing or invalid."
  ([] (read-users default-users-fixture))
  ([path]
   (let [file (io/file path)]
     (when-not (.exists file)
       (throw (ex-info (str "User fixtures not found at " (.getPath file))
                       {:path (.getPath file)})))
     (with-open [r (PushbackReader. (io/reader file))]
       (edn/read r)))))

(defn- valid-user?
  [{:user/keys [id username password]}]
  (and id (uuid? id) (string? username) (not (str/blank? username))
       (string? password) (not (str/blank? password))))

(defn load-users!
  "Load and validate user fixtures. Returns a vector of user maps, failing fast
  when required fields are missing or duplicate usernames exist."
  ([] (load-users! default-users-fixture))
  ([path]
   (let [users (read-users path)
         duplicates (->> users
                         (map :user/username)
                         (frequencies)
                         (keep (fn [[uname freq]] (when (> freq 1) uname)))
                         seq)
         invalid (remove valid-user? users)]
     (when duplicates
       (throw (ex-info "Duplicate usernames in user fixtures"
                       {:duplicates duplicates})))
     (when (seq invalid)
       (throw (ex-info "Invalid user fixtures" {:invalid invalid})))
     users)))

(defn user-index-by-username
  "Build a lookup map of username -> user map."
  [users]
  (into {} (map (juxt :user/username identity) users)))

(defn sanitize-user
  "Return user map without sensitive fields."
  [user]
  (select-keys user [:user/id :user/username :user/name]))

(defn authenticate
  "Validate username/password against a prepared user index. Returns
  {:user ...} on success or {:error reason :message msg} on failure."
  [user-index username password]
  (cond
    (or (nil? username) (str/blank? (str username)))
    {:error :invalid-input
     :message "Username is required"}

    (or (nil? password) (str/blank? (str password)))
    {:error :invalid-input
     :message "Password is required"}

    :else
    (if-let [user (get user-index username)]
      (if (= password (:user/password user))
        {:user (sanitize-user user)}
        {:error :invalid-credentials
         :message "Invalid username or password"})
      {:error :invalid-credentials
       :message "Invalid username or password"})))
