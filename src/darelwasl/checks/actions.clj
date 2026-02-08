(ns darelwasl.checks.actions
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [darelwasl.actions :as actions]
            [darelwasl.auth :as auth]
            [darelwasl.clients :as clients]
            [darelwasl.config :as config]
            [darelwasl.content :as content]
            [darelwasl.documents :as documents]
            [darelwasl.fixtures :as fixtures]
            [darelwasl.tasks :as tasks]
            [darelwasl.users :as users])
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

(defn- tag-index
  [conn]
  (let [tags (:tags (tasks/list-tags conn))]
    (into {}
          (map (fn [{:tag/keys [id name]}]
                 [(-> name str/lower-case keyword) id])
               tags))))

(defn- sorted-by?
  "Return true if coll is non-decreasing according to comparator."
  [cmp-fn coll]
  (every? (fn [[a b]] (not (pos? (cmp-fn a b)))) (partition 2 1 coll)))

(defn- pending-note-count
  [db task-id]
  (or (ffirst (d/q '[:find (count ?n)
                     :in $ ?tid
                     :where [?t :task/id ?tid]
                            [?n :note/subject ?t]
                            [?n :note/type :note.type/pending-reason]]
                   db task-id))
      0))

(defn- check-listing
  [conn failures tag-index]
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
    (let [home-id (:home tag-index)]
      (let [home-default (tasks/list-tasks conn {:tag home-id})
            home-default-list (ensure-success failures "Tag filter with archived default" home-default)]
        (when home-default-list
          (when-not (zero? (count (:tasks home-default-list)))
            (fail! failures "Tag filter should respect archived=false by default"))))
      (let [home-archived (tasks/list-tasks conn {:tag home-id :archived true})
            home-archived-list (ensure-success failures "Tag filter with archived=true" home-archived)]
        (when home-archived-list
          (when-not (= 1 (count (:tasks home-archived-list)))
            (fail! failures "Tag filter with archived=true should return archived home task")))))
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
  [conn failures {:keys [user-index actor tag-index]}]
  (when (and conn actor)
    (let [user-body {:user/username "contract-user"
                     :user/password "Contract1!"
                     :user/roles [:role/content-editor]}
          created-user (some-> (ensure-success failures "Create user" (users/create-user! conn user-body actor))
                               :user)
          updated-user (when created-user
                         (some-> (ensure-success failures "Update user"
                                                 (users/update-user! conn (:user/id created-user)
                                                                     {:user/name "Contract User"
                                                                      :user/roles [:role/admin]}
                                                                     actor))
                                 :user))
          default-client-id clients/default-client-id
          created-client (some-> (ensure-success failures "Create client"
                                                 (clients/create-client! conn {:client/name "Contract Client"
                                                                               :client/status :active}
                                                                         actor))
                                 :client)
          updated-client (when created-client
                           (some-> (ensure-success failures "Update client"
                                                   (clients/update-client! conn (:client/id created-client)
                                                                           {:client/status :waiting
                                                                            :client/notes "Waiting on docs"}
                                                                           actor))
                                   :client))
          assignee-huda (:user/id (get user-index "huda"))
           assignee-damjan (:user/id (get user-index "damjan"))
           tag-ops (:ops tag-index)
           tag-urgent (:urgent tag-index)
           create-body {:task/title "Write action harness"
                        :task/description "Add contract tests for auth and tasks"
                        :task/status :todo
                        :task/client default-client-id
                        :task/assignee assignee-huda
                        :task/priority :high
                        :task/tags [tag-ops tag-urgent]
                        :task/due-date "2025-12-20T10:00:00Z"}
           created (tasks/create-task! conn create-body actor)
           created-task (some-> (ensure-success failures "Create task" created) :task)]
       (when created-user
         (when-not (= "contract-user" (:user/username created-user))
           (fail! failures "Create user should echo username" created-user)))
       (when updated-user
         (when-not (= "Contract User" (:user/name updated-user))
           (fail! failures "Update user should change display name" updated-user))
         (when-not (= #{:role/admin} (set (:user/roles updated-user)))
           (fail! failures "Update user should replace roles" updated-user)))
       (when updated-client
         (when-not (= :waiting (:client/status updated-client))
           (fail! failures "Update client should change status" updated-client))
         (when-not (= "Waiting on docs" (:client/notes updated-client))
           (fail! failures "Update client should change notes" updated-client)))
       (when created-task
         (when-not (= :todo (:task/status created-task))
           (fail! failures "Create should set provided status" created-task))
         (when-not (= assignee-huda (:user/id (:task/assignee created-task)))
           (fail! failures "Create should assign to provided user" created-task))
         (when-not (= default-client-id (:client/id (:task/client created-task)))
           (fail! failures "Create should link provided client" created-task))
         (when-not (= #{tag-ops tag-urgent} (set (map :tag/id (:task/tags created-task))))
           (fail! failures "Create should persist tags" created-task)))
       (let [bad-create (tasks/create-task! conn {} actor)]
         (when-not (and (:error bad-create) (= 400 (get-in bad-create [:error :status])))
           (fail! failures "Missing title/fields should return 400 on create" bad-create)))
       (when created-task
         (let [task-id (:task/id created-task)
               update-body {:task/title "Action harness ready"
                            :task/description "Contracts run via scripts/checks.sh actions"
                            :task/priority :medium
                            :task/tags [(:finance tag-index)]
                            :task/extended? true}
               updated (tasks/update-task! conn task-id update-body actor)
               updated-task (some-> (ensure-success failures "Update task" updated) :task)]
           (when updated-task
             (when-not (= :medium (:task/priority updated-task))
               (fail! failures "Update should change priority" updated-task))
             (when-not (= #{(:finance tag-index)} (set (map :tag/id (:task/tags updated-task))))
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
           (let [missing-reason (tasks/set-status! conn task-id {:task/status :pending} actor)]
             (when-not (and (:error missing-reason) (= 400 (get-in missing-reason [:error :status])))
               (fail! failures "Pending status should require a note body" missing-reason)))
           (let [pending (tasks/set-status! conn task-id {:task/status :pending
                                                          :note/body "Awaiting client response"} actor)
                 pending-task (some-> (ensure-success failures "Set status pending" pending) :task)
                 note-count (pending-note-count (d/db conn) task-id)]
             (when pending-task
               (when-not (= :pending (:task/status pending-task))
                 (fail! failures "Status should update to :pending" pending-task))
               (when-not (pos? note-count)
                 (fail! failures "Pending reason note should be created" note-count))))
           (let [assign (tasks/assign-task! conn task-id {:task/assignee assignee-damjan} actor)
                 assigned-task (some-> (ensure-success failures "Assign task" assign) :task)]
             (when assigned-task
               (when-not (= assignee-damjan (:user/id (:task/assignee assigned-task)))
                 (fail! failures "Assign should change assignee" assigned-task))))
           (when created-client
             (let [link (tasks/set-client! conn task-id {:task/client (:client/id created-client)} actor)
                   linked-task (some-> (ensure-success failures "Set client" link) :task)]
               (when linked-task
                 (when-not (= (:client/id created-client) (get-in linked-task [:task/client :client/id]))
                   (fail! failures "Set client should link task to client" linked-task)))))
           (let [due (tasks/set-due-date! conn task-id {:task/due-date "2025-12-24T12:00:00Z"} actor)
                 due-task (some-> (ensure-success failures "Set due date" due) :task)]
             (when due-task
               (when-not (= "2025-12-24T12:00:00Z" (:task/due-date due-task))
                 (fail! failures "Due date should be set" due-task)))
             (let [cleared (tasks/set-due-date! conn task-id {:task/due-date nil} actor)
                   cleared-task (some-> (ensure-success failures "Clear due date" cleared) :task)]
               (when (and cleared-task (some? (:task/due-date cleared-task)))
                 (fail! failures "Due date should clear when nil provided" cleared-task))))
           (let [tags (tasks/set-tags! conn task-id {:task/tags [(:finance tag-index) tag-ops]} actor)
                 tags-task (some-> (ensure-success failures "Set tags" tags) :task)]
             (when tags-task
               (when-not (= #{(:finance tag-index) tag-ops} (set (map :tag/id (:task/tags tags-task))))
                 (fail! failures "Tags should update fully" tags-task))))
           (let [invalid-tags (tasks/set-tags! conn task-id {:task/tags [(UUID/randomUUID)]} actor)]
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
                 (fail! failures "Archived task should not appear in default list after archiving" (:tasks list-after)))))
           (let [deleted (tasks/delete-task! conn task-id actor)
                 deleted-task (some-> (ensure-success failures "Delete task" deleted) :task)]
           (when deleted-task
             (when-not (= task-id (:task/id deleted-task))
               (fail! failures "Delete response should echo task id" deleted-task)))
           (let [list-after-delete (tasks/list-tasks conn {:archived :all})
                 after (ensure-success failures "List after delete" list-after-delete)]
             (when after
               (when (some #(= task-id (:task/id %)) (:tasks after))
                 (fail! failures "Deleted task should not appear in listings" (:tasks after)))))))))))

(defn- content-checks
  [conn failures actor]
  (let [tags (ensure-success failures "Content tags list" (content/list-tags conn))
        pages (ensure-success failures "Content pages list" (content/list-pages conn {:with-blocks? true}))
        blocks (ensure-success failures "Content blocks list" (content/list-blocks conn {}))]
    (when tags
      (when-not (= 3 (count (:tags tags)))
        (fail! failures "Expected 3 content tags from fixtures")))
    (when pages
      (when-not (= 2 (count (:pages pages)))
        (fail! failures "Expected 2 content pages from fixtures")))
    (when blocks
      (when-not (= 5 (count (:blocks blocks)))
        (fail! failures "Expected 5 content blocks from fixtures"))))
  (let [tag-id (-> (content/list-tags conn) :tags first :content.tag/id)
        created-tag (some-> (ensure-success failures "Create content tag" (content/create-tag! conn {:content.tag/name "News"} actor)) :tag)
        updated-tag (when created-tag
                      (some-> (ensure-success failures "Update content tag" (content/update-tag! conn (:content.tag/id created-tag) {:content.tag/slug "news-updated"} actor)) :tag))]
    (when updated-tag
      (when-not (= "news-updated" (:content.tag/slug updated-tag))
        (fail! failures "Content tag slug should update" updated-tag)))
    (let [page-res (ensure-success failures "Create content page"
                                   (content/create-page! conn {:content.page/title "News"
                                                               :content.page/path "/news"
                                                               :content.page/summary "News landing"
                                                               :content.page/navigation-order 3
                                                               :content.page/tag [tag-id]}
                                                        actor))
          page (:page page-res)
          block-res (when page
                      (ensure-success failures "Create content block"
                                      (content/create-block! conn {:content.block/page (:content.page/id page)
                                                                   :content.block/type :hero
                                                                   :content.block/title "News hero"}
                                                            actor)))
          block (:block block-res)]
      (when block
        (let [updated (some-> (ensure-success failures "Update content block"
                                              (content/update-block! conn (:content.block/id block) {:content.block/order 5
                                                                                                    :content.block/type (:content.block/type block)} actor))
                              :block)]
          (when (not= 5 (:content.block/order updated))
            (fail! failures "Content block order should update" updated)))
        (let [page-blocks (:blocks (content/list-blocks conn {:page-id (:content.page/id page)}))]
          (when-not (some #(= (:content.block/id block) (:content.block/id %)) page-blocks)
            (fail! failures "Created block should be returned when filtering by page")))
        (ensure-success failures "Delete content block"
                        (content/delete-block! conn (:content.block/id block) actor))))
    (when created-tag
      (ensure-success failures "Delete content tag" (content/delete-tag! conn (:content.tag/id created-tag) actor)))))

(defn- documents-checks
  [conn failures actor]
  (let [state {:config (config/load-config)
               :db {:conn conn}}
        client-id clients/default-client-id]
    (let [issued (documents/issue-document! state {:type :proposal
                                                   :input {:client/id client-id}
                                                   :actor actor})
          issued2 (documents/issue-document! state {:type :proposal
                                                    :input {:client/id client-id}
                                                    :actor actor})]
      (when-not (success? issued)
        (fail! failures "Issue proposal failed" issued))
      (when-not (success? issued2)
        (fail! failures "Issue proposal (second call) failed" issued2))
      (when (and (success? issued) (success? issued2))
        (let [d1 (:document issued)
              d2 (:document issued2)]
          (when-not (= (:document/id d1) (:document/id d2))
            (fail! failures "Issuing same proposal twice should reuse the same document/id"
                   {:first (:document/id d1)
                    :second (:document/id d2)}))
          (let [ver (documents/verify-document! state {:input {:document/ref (:entity/ref d1)
                                                               :document/verification-code (:document/verification-code d1)}
                                                       :actor actor})]
            (when-not (true? (:document/valid? ver))
              (fail! failures "Issued document should verify" ver)))
          (let [forced (documents/issue-document! state {:type :proposal
                                                         :input {:client/id client-id
                                                                 :document/force? true}
                                                         :actor actor})]
            (when-not (success? forced)
              (fail! failures "Force issue proposal failed" forced))
            (let [d3 (:document forced)]
              (when (and d3 (= (:document/id d1) (:document/id d3)))
                (fail! failures "Force issue should create a new document/id"
                       {:first (:document/id d1)
                        :forced (:document/id d3)}))
              (let [latest (documents/latest-document! state {:type :proposal
                                                              :input {:client/id client-id}
                                                              :actor actor})]
                (when-not (success? latest)
                  (fail! failures "Latest document fetch failed" latest))
                (when (and (success? latest) d3)
                  (when-not (= (:document/id (:document latest)) (:document/id d3))
                    (fail! failures "Latest should return forced-issued document"
                           {:latest (:document/id (:document latest))
                            :expected (:document/id d3)})))))))))
    ;; Payment create should auto-issue a receipt document.
    (let [res (actions/execute! state {:action/id :cap/action/payment-create
                                       :actor actor
                                       :input {:client/id client-id
                                               :payment/amount 123.45
                                               :payment/method :cash
                                               :payment/paid-at (java.util.Date.)}})
          receipt (get-in res [:result :receipt])]
      (when-let [err (:error res)]
        (fail! failures "Payment create action failed" err))
      (when-not (and (map? receipt) (get receipt :document) (get receipt :file))
        (fail! failures "Payment create should include :receipt {:document .. :file ..}"
               {:result (:result res)})))))

(defn run-check!
  []
  (let [failures (atom [])]
    (try
      (fixtures/with-temp-fixtures
        (fn [{:keys [conn] :as state}]
          (if-let [err (:error state)]
            (fail! failures "Fixture setup failed" err)
            (let [auth-state (check-auth failures)
                  state' (assoc auth-state :tag-index (tag-index conn))]
              (check-listing conn failures (:tag-index state'))
              (check-mutations conn failures state')
              (content-checks conn failures (:actor auth-state))
              (documents-checks conn failures (:actor auth-state))))))
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
