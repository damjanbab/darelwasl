(ns darelwasl.terminal.plan
  (:require [clojure.set :as set]))

(def ^:private proof-requirements
  {:change/ui #{:proof/screencast :proof/screenshot :proof/api-response}
   :change/api #{:proof/test-log :proof/api-response}
   :change/registry #{:proof/test-log}
   :change/terminal #{:proof/test-log :proof/cli-output}
   :change/integration #{:proof/test-log :proof/api-response}
   :change/data #{:proof/cli-output :proof/api-response}
   :change/scrape #{:proof/sitemap-manifest :proof/crawl-manifest :proof/cli-output}
   :change/knowledge #{:proof/datomic-tx-log :proof/query-result}
   :change/app #{:proof/app-smoke :proof/api-response}
   :change/telegram #{:proof/telegram-devbot-session :proof/telegram-command-response}
   :change/docs #{:proof/cli-output}
   :change/devops #{:proof/cli-output}})

(defn- now-ms
  []
  (System/currentTimeMillis))

(defn- step-id
  []
  (str "step-" (java.util.UUID/randomUUID)))

(defn required-proofs
  [change-types]
  (let [types (or change-types #{:change/unknown})]
    (->> types
         (map proof-requirements)
         (remove nil?)
         (reduce set/union #{}))))

(defn generate-plan
  [spec]
  (let [plan-id (str "plan-" (java.util.UUID/randomUUID))
        decision? (nil? (:spec/delivery spec))
        decision-step (when decision?
                        {:step/id (step-id)
                         :step/type :step.type/decision
                         :step/title "Derive delivery mode"
                         :step/depends-on #{}
                         :step/required? true
                         :step/scope :scope/decision})
        work-step {:step/id (step-id)
                   :step/type :step.type/action
                   :step/title "Execute work"
                   :step/depends-on (cond-> #{}
                                      decision? (conj (:step/id decision-step)))
                   :step/required? true
                   :step/scope :scope/write}
        proofs (required-proofs (:spec/change-types spec))
        verify-steps (mapv (fn [proof-type]
                             {:step/id (step-id)
                              :step/type :step.type/verification
                              :step/title (str "Verify " (name proof-type))
                              :step/depends-on #{(:step/id work-step)}
                              :step/required? true
                              :step/proof-types #{proof-type}
                              :step/scope :scope/verify})
                           proofs)
        gate-step {:step/id (step-id)
                   :step/type :step.type/verification
                   :step/title "Verification gate"
                   :step/depends-on (set (map :step/id verify-steps))
                   :step/required? true
                   :step/proof-types proofs
                   :step/scope :scope/verify}
        steps (cond-> []
                decision? (conj decision-step)
                true (conj work-step)
                (seq verify-steps) (into verify-steps)
                true (conj gate-step))]
    {:id plan-id
     :spec-id (:spec/id spec)
     :created-at (now-ms)
     :steps steps}))

(defn update-step-status
  [plan step-id status]
  (update plan :steps
          (fn [steps]
            (mapv (fn [step]
                    (if (= (:step/id step) step-id)
                      (assoc step :step/status status)
                      step))
                  steps))))
