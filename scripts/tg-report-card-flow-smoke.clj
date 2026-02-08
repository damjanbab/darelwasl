(ns tg-report-card-flow-smoke
  (:require
   [clojure.string :as str]
   [datomic.client.api :as d]
   [darelwasl.actions :as actions]
   [darelwasl.tasks :as tasks]
   [darelwasl.telegram :as tg]))

(defn- assert!
  ([pred msg]
   (when-not pred
     (throw (ex-info (str "ASSERT FAILED: " msg) {}))))
  ([pred msg data]
   (when-not pred
     (throw (ex-info (str "ASSERT FAILED: " msg) (or data {}))))))

(defn- last-call
  [calls]
  (last @calls))

(defn- find-callback-data
  [reply-markup]
  (let [rows (get reply-markup :inline_keyboard [])]
    (->> rows
         (mapcat identity)
         (keep :callback_data)
         vec)))

(defn- has-callback?
  [reply-markup cb]
  (some #(= cb %) (find-callback-data reply-markup)))

(defn- any-callback-prefix?
  [reply-markup prefix]
  (some #(str/starts-with? % prefix) (find-callback-data reply-markup)))

(defn run!
  []
  (let [calls (atom [])
        user {:user/id #uuid "00000000-0000-0000-0000-000000000001"
              :user/username "huda"
              :actor/workspace nil}
        client-id #uuid "10000000-0000-0000-0000-000000000001"
        task-id #uuid "20000000-0000-0000-0000-000000000001"
        next-task-id #uuid "20000000-0000-0000-0000-000000000002"
        task {:task/id task-id
              :task/title "Onboard lead"
              :task/status :todo
              :task/archived? false
              :task/report-card-type :report.card.type/onboarding
              :task/client {:client/id client-id :client/name "Acme"}
              :task/assignee {:user/id (:user/id user) :user/username (:user/username user)}}
        next-task {:task/id next-task-id
                   :task/title "Deliver proposal (stub)"
                   :task/status :todo
                   :task/archived? false
                   :task/report-card-type :report.card.type/proposal-response
                   :task/client {:client/id client-id :client/name "Acme"}
                   :task/assignee {:user/id (:user/id user) :user/username (:user/username user)}}
        state {:config {:telegram {:webhook-enabled? true
                                   :commands-enabled? true
                                   :bot-token "test-token"}}
               :db {:conn :fake-conn}}
        fake-request (fn [_cfg path payload]
                       (swap! calls conj {:path path :payload payload})
                       {:ok true :result {:message_id 999}})]
    (with-redefs [tg/log-telegram-message! (fn [& _] nil)
                  tg/ensure-conn (fn [_] :fake-conn)
                  d/db (fn [_] :fake-db)
                  d/q (fn [& _] [[task]])
                  tasks/list-tasks (fn [_conn _params] {:tasks [task]})
                  darelwasl.telegram/user-by-chat-id (fn [_db _chat-id] user)
                  actions/execute! (fn [_state invocation]
                                     (case (:action/id invocation)
                                       :cap/action/service-list
                                       {:result {:services [{:id :service/entrepreneur-license
                                                             :title "Entrepreneur License"}]}}

                                       :cap/action/report-card-submit
                                       {:result {:report-card {:report.card/id #uuid "30000000-0000-0000-0000-000000000001"}}}

                                       :cap/action/task-set-status
                                       {:result {:task (assoc task :task/status (get-in invocation [:input :task/status]))}}

                                       :cap/action/task-create
                                       {:result {:task next-task}}

                                       {:error {:status 500 :message (str "Unexpected action in smoke: " (:action/id invocation))}}))
                  tg/request-json fake-request]
      ;; /tasks -> list keyboard includes "task:view:<task-id>"
      (reset! calls [])
      (tg/handle-update state {:update_id 1
                              :message {:message_id 10
                                        :chat {:id 42}
                                        :from {:id 7 :username "huda"}
                                        :text "/tasks"}})
      (let [{:keys [path payload]} (last-call calls)]
        (assert! (= path "sendMessage") "tasks uses sendMessage")
        (assert! (has-callback? (:reply_markup payload) (str "task:view:" task-id))
                 "tasks list has task:view button"))

      ;; Open task -> task card includes rc:start:<task-id>
      (reset! calls [])
      (tg/handle-update state {:update_id 2
                              :callback_query {:id "cb1"
                                               :from {:id 7 :username "huda"}
                                               :data (str "task:view:" task-id)
                                               :message {:message_id 999 :chat {:id 42}}}})
      (let [{:keys [path payload]} (last-call calls)]
        (assert! (= path "editMessageText") "task:view edits list message into task card")
        (assert! (has-callback? (:reply_markup payload) (str "rc:start:" task-id))
                 "task card shows report card button"))

      ;; Start report card -> contacted step
      (reset! calls [])
      (tg/handle-update state {:update_id 3
                              :callback_query {:id "cb2"
                                               :from {:id 7 :username "huda"}
                                               :data (str "rc:start:" task-id)
                                               :message {:message_id 999 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (has-callback? (:reply_markup payload) "rc:contacted:yes")
                 "contacted keyboard present"))

      ;; Contacted -> schedule step
      (reset! calls [])
      (tg/handle-update state {:update_id 4
                              :callback_query {:id "cb3"
                                               :from {:id 7 :username "huda"}
                                               :data "rc:contacted:yes"
                                               :message {:message_id 999 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (has-callback? (:reply_markup payload) "rc:schedule:pick-date")
                 "schedule keyboard present"))

      ;; Pick date -> inline calendar shows dp:* callbacks
      (reset! calls [])
      (tg/handle-update state {:update_id 5
                              :callback_query {:id "cb4"
                                               :from {:id 7 :username "huda"}
                                               :data "rc:schedule:pick-date"
                                               :message {:message_id 999 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)
            rm (:reply_markup payload)]
        (assert! (any-callback-prefix? rm "dp:day:") "date picker shows day buttons")
        (assert! (has-callback? rm "dp:quick:tomorrow") "date picker quick button present"))

      ;; Quick date -> should land in time picker (tp:hour:* + tp:skip)
      (reset! calls [])
      (tg/handle-update state {:update_id 6
                              :callback_query {:id "cb5"
                                               :from {:id 7 :username "huda"}
                                               :data "dp:quick:tomorrow"
                                               :message {:message_id 999 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)
            rm (:reply_markup payload)]
        (assert! (any-callback-prefix? rm "tp:hour:") "time picker shows hour buttons")
        (assert! (has-callback? rm "tp:skip") "time picker allows skip"))

      ;; Pick hour -> minute picker
      (reset! calls [])
      (tg/handle-update state {:update_id 7
                              :callback_query {:id "cb6"
                                               :from {:id 7 :username "huda"}
                                               :data "tp:hour:14"
                                               :message {:message_id 999 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)
            rm (:reply_markup payload)]
        (assert! (has-callback? rm "tp:set:1430") "minute picker includes 14:30 option")
        (assert! (has-callback? rm "tp:back") "minute picker includes Back button"))

      ;; Pick minute -> service stage
      (reset! calls [])
      (tg/handle-update state {:update_id 8
                              :callback_query {:id "cb7"
                                               :from {:id 7 :username "huda"}
                                               :data "tp:set:1430"
                                               :message {:message_id 999 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)
            rm (:reply_markup payload)]
        (assert! (any-callback-prefix? rm "rc:service:") "service picker shows rc:service callbacks"))

      ;; Pick service -> budget stage
      (reset! calls [])
      (tg/handle-update state {:update_id 9
                              :callback_query {:id "cb8"
                                               :from {:id 7 :username "huda"}
                                               :data "rc:service:service/entrepreneur-license"
                                               :message {:message_id 999 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (has-callback? (:reply_markup payload) "rc:budget:skip") "budget keyboard present"))

      ;; Skip budget -> notes step (await typing / skip)
      (reset! calls [])
      (tg/handle-update state {:update_id 10
                              :callback_query {:id "cb9"
                                               :from {:id 7 :username "huda"}
                                               :data "rc:budget:skip"
                                               :message {:message_id 999 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (has-callback? (:reply_markup payload) "rc:notes:skip") "notes keyboard present"))

      ;; Skip notes -> review stage
      (reset! calls [])
      (tg/handle-update state {:update_id 11
                              :callback_query {:id "cb10"
                                               :from {:id 7 :username "huda"}
                                               :data "rc:notes:skip"
                                               :message {:message_id 999 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (has-callback? (:reply_markup payload) "rc:submit") "review keyboard present"))

      ;; Submit -> replaces message with next task card (proposal response)
      (reset! calls [])
      (tg/handle-update state {:update_id 12
                              :callback_query {:id "cb11"
                                               :from {:id 7 :username "huda"}
                                               :data "rc:submit"
                                               :message {:message_id 999 :chat {:id 42}}}})
      (let [{:keys [path payload]} (last-call calls)]
        (assert! (= path "editMessageText") "submit edits message into next task card")
        (assert! (has-callback? (:reply_markup payload) (str "rc:start:" next-task-id))
                 "next task card has proposal-response report card button")))
    :ok))

