(ns darelwasl.checks.actions
  (:require [darelwasl.auth :as auth]
            [darelwasl.fixtures :as fixtures]
            [darelwasl.tasks :as tasks])
  (:import (java.time Instant)
           (java.util UUID)))

(defn- fail!
  [failures message & [details]]
  (swap! failures conj (str message (when details (str " :: " (pr-str details))))))

(defn- success?
  [result]
  (and (map? result) (nil? (:error result))))

(defn- ensure-success
  [failures label result]
  (if-let [err (:error result)]
    (do
      (fail! failures (str label " failed (" (:message err) ")") (:details err))
      nil)
    result))

(defn- sorted-by?
  "Return true if coll is non-decreasing according to comparator."
  [cmp-fn coll]
  (every? (fn [[a b]] (not (pos? (cmp-fn a b)))) (partition 2 1 coll)))

(defn- check-listing
  [conn failures]
  (let [default-list (tasks/list-tasks conn {})
        listing (ensure-success failures "Default task list" default-list)]
    (when listing
      (let [tasks (:tasks listing)]
        (when-not (= 3 (count tasks))
          (fail! failures "Default list should exclude archived tasks (expected 3)"))
        (when-not (every? false? (map :task/archived? tasks))
          (fail! failures "Default list should only include non-archived tasks"))))
    (let [archived (tasks/list-tasks conn {:archived true})
          archived-list (ensure-success failures "Archived filter" archived)]
      (when archived-list
        (when-not (= 1 (count (:tasks archived-list)))
          (fail! failures "Archived filter should return only archived tasks"))))
    (let [all (tasks/list-tasks conn {:archived :all})
          all-list (ensure-success failures "Archived=all filter" all)]
      (when all-list
        (when-not (= 4 (count (:tasks all-list)))
          (fail! failures "Archived=all should include all fixtures"))))
    (let [done-only (tasks/list-tasks conn {:status :done})
          done-list (ensure-success failures "Status filter" done-only)]
      (when done-list
        (when-not (= 1 (count (:tasks done-list)))
          (fail! failures "Status filter :done should return one task"))
        (when-not (every? #(= :done (:task/status %)) (:tasks done-list))
          (fail! failures "Status filter should only include :done tasks"))))
    (let [assignee (tasks/list-tasks conn {:assignee #uuid "00000000-0000-0000-0000-000000000001"})
          assignee-list (ensure-success failures "Assignee filter" assignee)]
      (when assignee-list
        (when-not (= 2 (count (:tasks assignee-list)))
          (fail! failures "Assignee filter should return two tasks for huda"))))
    (let [home-default (tasks/list-tasks conn {:tag :home})
          home-default-list (ensure-success failures "Tag filter with archived default" home-default)]
      (when home-default-list
        (when-not (zero? (count (:tasks home-default-list)))
          (fail! failures "Tag filter should respect archived=false by default"))))
    (let [home-archived (tasks/list-tasks conn {:tag :home :archived true})
          home-archived-list (ensure-success failures "Tag filter with archived=true" home-archived)]
      (when home-archived-list
        (when-not (= 1 (count (:tasks home-archived-list)))
          (fail! failures "Tag filter with archived=true should return archived home task"))))
    (let [due-sort (tasks/list-tasks conn {:archived :all
                                           :sort :due
                                           :order :asc})
          due-list (ensure-success failures "Sort by due date" due-sort)]
      (when due-list
        (let [dates (map :task/due-date (:tasks due-list))
              insts (keep #(when % (Instant/parse %)) dates)]
          (when-not (sorted-by? compare insts)
            (fail! failures "Due dates should be sorted ascending with nils last" dates))
          (when-not (nil? (last dates))
            (fail! failures "Tasks without due date should sort last")))))
    (let [bad-sort (tasks/list-tasks conn {:sort "bogus"})
          err (:error bad-sort)]
      (when-not (and err (= 400 (:status err)))
        (fail! failures "Invalid sort should return 400 error" err)))))

(defn- check-auth
  [failures]
  (let [users (auth/load-users!)
        user-index (auth/user-index-by-username users)
        good (auth/authenticate user-index "huda" "Damjan1!")
        bad (auth/authenticate user-index "huda" "wrong-password")]
    (when-not (:user good)
      (fail! failures "Valid credentials should authenticate" good))
    (when-not (and (:error bad) (= :invalid-credentials (:error bad)))
      (fail! failures "Invalid credentials should fail with :invalid-credentials" bad))
    {:user-index user-index
     :actor (:user good)}))

(defn- check-mutations
  [conn failures {:keys [user-index actor]}]
  (when (and conn actor)
    (let [assignee-huda (:user/id (get user-index "huda"))
           assignee-damjan (:user/id (get user-index "damjan"))
           create-body {:task/title "Write action harness"
                        :task/description "Add contract tests for auth and tasks"
                        :task/status :todo
                        :task/assignee assignee-huda
                        :task/priority :high
                        :task/tags [:ops :urgent]
                        :task/due-date "2025-12-20T10:00:00Z"}
           created (tasks/create-task! conn create-body actor)
           created-task (some-> (ensure-success failures "Create task" created) :task)]
       (when created-task
         (when-not (= :todo (:task/status created-task))
           (fail! failures "Create should set provided status" created-task))
         (when-not (= assignee-huda (:user/id (:task/assignee created-task)))
           (fail! failures "Create should assign to provided user" created-task))
         (when-not (= #{:ops :urgent} (set (:task/tags created-task)))
           (fail! failures "Create should persist tags" created-task)))
       (let [bad-create (tasks/create-task! conn {} actor)]
         (when-not (and (:error bad-create) (= 400 (get-in bad-create [:error :status])))
           (fail! failures "Missing title/fields should return 400 on create" bad-create)))
       (when created-task
         (let [task-id (:task/id created-task)
               update-body {:task/title "Action harness ready"
                            :task/description "Contracts run via scripts/checks.sh actions"
                            :task/priority :medium
                            :task/tags [:finance]
                            :task/extended? true}
               updated (tasks/update-task! conn task-id update-body actor)
               updated-task (some-> (ensure-success failures "Update task" updated) :task)]
           (when updated-task
             (when-not (= :medium (:task/priority updated-task))
               (fail! failures "Update should change priority" updated-task))
             (when-not (= #{:finance} (set (:task/tags updated-task)))
               (fail! failures "Update should replace tags" updated-task))
             (when-not (= "Action harness ready" (:task/title updated-task))
               (fail! failures "Update should change title" updated-task))
             (when-not (true? (:task/extended? updated-task))
               (fail! failures "Update should set extended flag" updated-task)))
           (let [status1 (tasks/set-status! conn task-id {:task/status :in-progress} actor)
                 s1 (some-> (ensure-success failures "Set status" status1) :task)
                 status2 (tasks/set-status! conn task-id {:task/status :in-progress} actor)
                 _ (ensure-success failures "Set status idempotent" status2)]
             (when s1
               (when-not (= :in-progress (:task/status s1))
                 (fail! failures "Status should update to :in-progress" s1))))
           (let [assign (tasks/assign-task! conn task-id {:task/assignee assignee-damjan} actor)
                 assigned-task (some-> (ensure-success failures "Assign task" assign) :task)]
             (when assigned-task
               (when-not (= assignee-damjan (:user/id (:task/assignee assigned-task)))
                 (fail! failures "Assign should change assignee" assigned-task))))
           (let [due (tasks/set-due-date! conn task-id {:task/due-date "2025-12-24T12:00:00Z"} actor)
                 due-task (some-> (ensure-success failures "Set due date" due) :task)]
             (when due-task
               (when-not (= "2025-12-24T12:00:00Z" (:task/due-date due-task))
                 (fail! failures "Due date should be set" due-task)))
             (let [cleared (tasks/set-due-date! conn task-id {:task/due-date nil} actor)
                   cleared-task (some-> (ensure-success failures "Clear due date" cleared) :task)]
               (when (and cleared-task (some? (:task/due-date cleared-task)))
                 (fail! failures "Due date should clear when nil provided" cleared-task))))
           (let [tags (tasks/set-tags! conn task-id {:task/tags [:finance :ops]} actor)
                 tags-task (some-> (ensure-success failures "Set tags" tags) :task)]
             (when tags-task
               (when-not (= #{:finance :ops} (set (:task/tags tags-task)))
                 (fail! failures "Tags should update fully" tags-task))))
           (let [invalid-tags (tasks/set-tags! conn task-id {:task/tags [:invalid/tag]} actor)]
             (when-not (and (:error invalid-tags) (= 400 (get-in invalid-tags [:error :status])))
               (fail! failures "Invalid tags should return 400" invalid-tags)))
           (let [archive (tasks/archive-task! conn task-id {:task/archived? true} actor)
                 archived-task (some-> (ensure-success failures "Archive task" archive) :task)]
             (when archived-task
               (when-not (true? (:task/archived? archived-task))
                 (fail! failures "Archive should set archived? true" archived-task))))
           (let [invalid-assignee (tasks/assign-task! conn task-id {:task/assignee (UUID/randomUUID)} actor)]
             (when-not (and (:error invalid-assignee) (= 400 (get-in invalid-assignee [:error :status])))
               (fail! failures "Unknown assignee should return 400" invalid-assignee)))
           (let [invalid-status (tasks/set-status! conn task-id {:task/status :bogus} actor)]
             (when-not (and (:error invalid-status) (= 400 (get-in invalid-status [:error :status])))
               (fail! failures "Invalid status should return 400" invalid-status)))
           (let [post-archive (tasks/list-tasks conn {})
                 list-after (ensure-success failures "List after archive" post-archive)]
             (when list-after
               (when-not (= 3 (count (:tasks list-after)))
                 (fail! failures "Archived task should not appear in default list after archiving" (:tasks list-after))))))))))

(defn run-check!
  []
  (let [failures (atom [])]
    (try
      (let [result (fixtures/with-temp-fixtures
                     (fn [{:keys [conn] :as state}]
                       (if-let [err (:error state)]
                         (fail! failures "Fixture setup failed" err)
                         (let [auth-state (check-auth failures)]
                           (check-listing conn failures)
                           (check-mutations conn failures auth-state)))))]
        (when (and (map? result) (:error result))
          (fail! failures "Action contract checks failed during setup" (:error result))))
      (catch Exception e
        (fail! failures "Action contract checks crashed" (.getMessage e))))
    (if (seq @failures)
      (do
        (println "Action contract checks failed:")
        (doseq [f @failures]
          (println "-" f))
        (System/exit 1))
      (do
        (println "Action contract checks passed.")
        (System/exit 0)))))

(defn -main [& _]
  (run-check!))
