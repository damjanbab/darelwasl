(ns darelwasl.webterm.config
  (:require [clojure.string :as str]))

(defn env
  ^String
  [^String k ^String default]
  (or (System/getenv k) default))

(defn- parse-int
  [s default]
  (try
    (let [n (Long/parseLong (str s))]
      (int n))
    (catch Exception _ default)))

(defn env-int
  [^String k default]
  (parse-int (System/getenv k) default))

(defn public-base-path
  "UI-only path prefix used when this service is mounted under a prefix (e.g. /canary via Caddy).
  The reverse proxy strips the prefix before forwarding, so the backend still receives unprefixed paths."
  []
  (let [raw (some-> (System/getenv "DW_PUBLIC_BASE_PATH") str/trim)]
    (if (str/blank? raw)
      ""
      (let [p (if (str/starts-with? raw "/") raw (str "/" raw))]
        (-> p (str/replace #"/+$" ""))))))

(defn config
  []
  (let [stable (env-int "DW_LAB_SESSION_STABLE" (env-int "DW_LAB_SESSION" 7))]
    {:tmux-bin (env "DW_TMUX_BIN" "tmux")
     :tmux-prefix (env "DW_TMUX_PREFIX" "codex")
     :terminal-count (env-int "DW_TERMINAL_COUNT" 32)
     :workdir (env "DW_WORKDIR" "/opt/darelwasl")
     :listen-host (env "DW_LISTEN_HOST" "127.0.0.1")
     :listen-port (env-int "DW_LISTEN_PORT" 7682)
     :public-base-path (public-base-path)
     :lab-stable-session stable
     :lab-canary-session (env-int "DW_LAB_SESSION_CANARY" (inc stable))
     :lab-dir (env "DW_LAB_DIR" "/opt/darelwasl/tmp/lab")
     :lab-max-upload-bytes (env-int "DW_LAB_MAX_UPLOAD_BYTES" (* 50 1024 1024))
     :lab-default-history-lines (env-int "DW_LAB_HISTORY_LINES" 20000)
     :tmux-history-limit (env-int "DW_TMUX_HISTORY_LIMIT" 50000)}))
