(ns tg-docs-flow-smoke
  (:require
   [clojure.string :as str]
   [datomic.client.api :as d]
   [darelwasl.actions :as actions]
   [darelwasl.clients :as clients]
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

(defn- run!
  []
	    (let [calls (atom [])
	          user {:user/id #uuid "00000000-0000-0000-0000-000000000001"
	                :user/username "smoke"
	                :actor/workspace nil}
	          client-id #uuid "10000000-0000-0000-0000-000000000001"
	          agreement-id #uuid "40000000-0000-0000-0000-000000000001"
	          plan-item-id #uuid "50000000-0000-0000-0000-000000000001"
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
	                  tasks/list-tasks (fn [_conn _params] {:tasks []})
	                  darelwasl.telegram/user-by-chat-id (fn [_db _chat-id] user)
	                  clients/list-clients (fn [_conn _params _workspace]
	                                         {:clients [{:client/id client-id :client/name "Acme"}]})
                  clients/client-by-id (fn [_conn _client-id _workspace]
                                         {:client/id client-id :client/name "Acme"})
	                  actions/execute! (fn [_state {:keys [input] :as invocation}]
	                                     (case (:action/id invocation)
	                                       :cap/action/doc-pack-upsert {:result {:doc-pack {:client/id (:client/id input)}}}
	                                       :cap/action/doc-pack-read {:result {:doc-pack {:client/id (:client/id input)
	                                                                                     :doc.pack/currency "SAR"}}}
	                                       :cap/action/invoice-create {:result {:invoice {:invoice/id #uuid "20000000-0000-0000-0000-000000000001"
	                                                                                     :invoice/number (get input :invoice/number)
	                                                                                     :invoice/status (get input :invoice/status)}}}
	                                       :cap/action/payment-create {:result {:payment {:payment/id #uuid "30000000-0000-0000-0000-000000000001"}}}
	                                       :cap/action/invoice-list {:result {:invoices []}}
	                                       :cap/action/payment-list {:result {:payments []}}
	                                       :cap/action/agreement-list {:result {:agreements []}}
	                                       :cap/action/agreement-create {:result {:agreement {:agreement/id agreement-id
	                                                                                         :agreement/number "AG-1"
	                                                                                         :agreement/status :draft}}}
	                                       :cap/action/plan-item-list {:result {:plan-items []}}
	                                       :cap/action/plan-item-create {:result {:plan-item {:plan.item/id plan-item-id}}}
	                                       {:error {:status 500 :message (str "Unexpected action in smoke: " (:action/id invocation))}}))
	                  tg/request-json fake-request]
      ;; /docs -> client pick keyboard
      (reset! calls [])
      (tg/handle-update state {:update_id 1
                              :message {:message_id 10
                                        :chat {:id 42}
                                        :from {:id 7 :username "smoke"}
                                        :text "/docs"}})
      (let [{:keys [path payload]} (last-call calls)]
        (assert! (= path "sendMessage") "docs uses sendMessage")
        (assert! (any-callback-prefix? (:reply_markup payload) "docs:client:set:")
                 "docs client pick has client buttons"))

      ;; pick client
      (reset! calls [])
      (tg/handle-update state {:update_id 2
                              :callback_query {:id "cb1"
                                               :from {:id 7 :username "smoke"}
                                               :data (str "docs:client:set:" client-id)
                                               :message {:message_id 11
                                                         :chat {:id 42}}}})
      (let [{:keys [path payload]} (last-call calls)]
        (assert! (= path "editMessageText") "client pick edits message")
        (assert! (has-callback? (:reply_markup payload) "docs:invoice:add")
                 "docs menu has Add invoice"))

      ;; start invoice wizard
      (reset! calls [])
      (tg/handle-update state {:update_id 3
                              :callback_query {:id "cb2"
                                               :from {:id 7 :username "smoke"}
                                               :data "docs:invoice:add"
                                               :message {:message_id 12
                                                         :chat {:id 42}}}})
      (let [{:keys [path payload]} (last-call calls)]
        (assert! (= path "editMessageText") "invoice add edits message")
        (assert! (str/includes? (str (:text payload)) "invoice number") "prompts invoice number"))

      ;; invoice number -> invoice total
      (reset! calls [])
      (tg/handle-update state {:update_id 4
                              :message {:message_id 13 :chat {:id 42} :from {:id 7 :username "smoke"} :text "INV-1"}})
      (let [{:keys [path payload]} (last-call calls)]
        (assert! (= path "sendMessage") "inv number step sends message")
        (assert! (str/includes? (str (:text payload)) "total") "prompts invoice total"))

      ;; invoice total -> invoice status buttons
      (reset! calls [])
      (tg/handle-update state {:update_id 5
                              :message {:message_id 14 :chat {:id 42} :from {:id 7 :username "smoke"} :text "1000"}})
      (let [{:keys [path payload]} (last-call calls)]
        (assert! (= path "sendMessage") "inv total step sends message")
        (assert! (has-callback? (:reply_markup payload) "docs:invoice:status:sent")
                 "invoice status keyboard present"))

      ;; pick invoice status -> due date inline calendar (+ No due date)
      (reset! calls [])
      (tg/handle-update state {:update_id 6
                              :callback_query {:id "cb3"
                                               :from {:id 7 :username "smoke"}
                                               :data "docs:invoice:status:sent"
                                               :message {:message_id 15 :chat {:id 42}}}})
      (let [{:keys [path payload]} (last-call calls)
            rm (:reply_markup payload)]
        (assert! (= path "editMessageText") "status pick edits message")
        (assert! (any-callback-prefix? rm "dp:day:") "due-date shows day picker buttons")
        (assert! (has-callback? rm "dp:skip") "due-date allows No due date (skip)"))

      ;; payment wizard: start + amount
      (reset! calls [])
      (tg/handle-update state {:update_id 7
                              :callback_query {:id "cb4"
                                               :from {:id 7 :username "smoke"}
                                               :data "docs:payment:add"
                                               :message {:message_id 16 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (str/includes? (str (:text payload)) "amount") "prompts payment amount"))

      ;; enter amount -> method buttons
      (reset! calls [])
      (tg/handle-update state {:update_id 8
                              :message {:message_id 17 :chat {:id 42} :from {:id 7 :username "smoke"} :text "500"}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (has-callback? (:reply_markup payload) "docs:payment:method:transfer")
                 "payment method keyboard present"))

      ;; pick method -> invoice attach buttons
      (reset! calls [])
      (tg/handle-update state {:update_id 9
                              :callback_query {:id "cb5"
                                               :from {:id 7 :username "smoke"}
                                               :data "docs:payment:method:transfer"
                                               :message {:message_id 18 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (has-callback? (:reply_markup payload) "docs:payment:invoice:skip")
                 "invoice attach keyboard present"))

      ;; skip invoice -> payment date inline calendar (NO skip)
      (reset! calls [])
      (tg/handle-update state {:update_id 10
                              :callback_query {:id "cb6"
                                               :from {:id 7 :username "smoke"}
                                               :data "docs:payment:invoice:skip"
                                               :message {:message_id 19 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)
            rm (:reply_markup payload)]
        (assert! (any-callback-prefix? rm "dp:day:") "payment date shows day picker buttons")
        (assert! (not (has-callback? rm "dp:skip")) "payment date does NOT allow skip"))

      ;; typing a date should be rejected (no fallback typing)
      (reset! calls [])
      (tg/handle-update state {:update_id 11
                              :message {:message_id 20 :chat {:id 42} :from {:id 7 :username "smoke"} :text "2026-02-07"}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (str/includes? (str (:text payload)) "calendar") "typed date is rejected with instruction")
        (assert! (any-callback-prefix? (:reply_markup payload) "dp:day:") "re-sends calendar picker"))

      ;; pick payment date -> reference step has Skip button
      (reset! calls [])
      (tg/handle-update state {:update_id 12
                              :callback_query {:id "cb7"
                                               :from {:id 7 :username "smoke"}
                                               :data "dp:day:2026-02-07"
                                               :message {:message_id 21 :chat {:id 42}}}})
      (let [{:keys [path payload]} (last-call calls)
            rm (:reply_markup payload)]
        (assert! (= path "editMessageText") "payment date pick edits message to time picker")
        (assert! (any-callback-prefix? rm "tp:hour:") "payment time picker has hour buttons")
        (assert! (has-callback? rm "tp:skip") "payment time picker has Skip time button"))

      ;; typing a time should be rejected (no fallback typing)
      (reset! calls [])
      (tg/handle-update state {:update_id 13
                              :message {:message_id 22 :chat {:id 42} :from {:id 7 :username "smoke"} :text "14:30"}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (str/includes? (str (:text payload)) "buttons") "typed time is rejected with instruction")
        (assert! (any-callback-prefix? (:reply_markup payload) "tp:hour:") "re-sends time picker"))

      ;; pick hour -> minute picker shown
      (reset! calls [])
      (tg/handle-update state {:update_id 14
                              :callback_query {:id "cb8"
                                               :from {:id 7 :username "smoke"}
                                               :data "tp:hour:14"
                                               :message {:message_id 21 :chat {:id 42}}}})
      (let [{:keys [path payload]} (last-call calls)
            rm (:reply_markup payload)]
        (assert! (= path "editMessageText") "hour pick edits message to minute picker")
        (assert! (has-callback? rm "tp:set:1430") "minute picker includes 14:30 option")
        (assert! (has-callback? rm "tp:back") "minute picker includes Back button"))

      ;; pick minute -> reference step has Skip button
      (reset! calls [])
      (tg/handle-update state {:update_id 15
                              :callback_query {:id "cb9"
                                               :from {:id 7 :username "smoke"}
                                               :data "tp:set:1430"
                                               :message {:message_id 21 :chat {:id 42}}}})
      (let [{:keys [path payload]} (last-call calls)]
        (assert! (= path "sendMessage") "time pick prompts for reference")
        (assert! (has-callback? (:reply_markup payload) "docs:payment:ref:skip")
                 "payment reference step has Skip button"))

      ;; skip reference -> note step has Skip button
      (reset! calls [])
      (tg/handle-update state {:update_id 16
                              :callback_query {:id "cb10"
                                               :from {:id 7 :username "smoke"}
                                               :data "docs:payment:ref:skip"
                                               :message {:message_id 23 :chat {:id 42}}}})
      (let [{:keys [path payload]} (last-call calls)]
        (assert! (= path "sendMessage") "reference skip prompts for note")
        (assert! (has-callback? (:reply_markup payload) "docs:payment:note:skip")
                 "payment note step has Skip button"))

      ;; skip note -> payment is created
      (reset! calls [])
      (tg/handle-update state {:update_id 17
                              :callback_query {:id "cb11"
                                               :from {:id 7 :username "smoke"}
                                               :data "docs:payment:note:skip"
                                               :message {:message_id 24 :chat {:id 42}}}})
      (let [{:keys [path payload]} (last-call calls)]
        (assert! (= path "sendMessage") "note skip sends confirmation")
        (assert! (str/includes? (str (:text payload)) "Payment") "payment added confirmation"))

      ;; open agreements menu
      (reset! calls [])
      (tg/handle-update state {:update_id 18
                              :callback_query {:id "cb12"
                                               :from {:id 7 :username "smoke"}
                                               :data "docs:agreements:menu"
                                               :message {:message_id 25 :chat {:id 42}}}})
      (let [{:keys [path payload]} (last-call calls)]
        (assert! (= path "editMessageText") "agreements menu edits message")
        (assert! (has-callback? (:reply_markup payload) "docs:agreements:create")
                 "agreements menu has Create agreement"))

      ;; start agreement wizard
      (reset! calls [])
      (tg/handle-update state {:update_id 19
                              :callback_query {:id "cb13"
                                               :from {:id 7 :username "smoke"}
                                               :data "docs:agreements:create"
                                               :message {:message_id 25 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (str/includes? (str (:text payload)) "agreement title") "prompts agreement title"))

      ;; agreement title -> agreement terms
      (reset! calls [])
      (tg/handle-update state {:update_id 20
                              :message {:message_id 26
                                        :chat {:id 42}
                                        :from {:id 7 :username "smoke"}
                                        :text "Agreement for Acme"}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (str/includes? (str (:text payload)) "terms") "prompts agreement terms"))

      ;; agreement terms -> party fields (skip allowed via buttons)
      (reset! calls [])
      (tg/handle-update state {:update_id 21
                              :message {:message_id 27
                                        :chat {:id 42}
                                        :from {:id 7 :username "smoke"}
                                        :text "These are the terms."}})
      (let [{:keys [payload]} (last-call calls)
            rm (:reply_markup payload)]
        (assert! (has-callback? rm "docs:agreement:party:skip:client-company")
                 "agreement party client company shows Skip button"))

      ;; skip client company -> client representative
      (reset! calls [])
      (tg/handle-update state {:update_id 22
                              :callback_query {:id "cb14"
                                               :from {:id 7 :username "smoke"}
                                               :data "docs:agreement:party:skip:client-company"
                                               :message {:message_id 28 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (str/includes? (str (:text payload)) "Client representative") "prompts client representative"))

      ;; skip client representative -> our representative
      (reset! calls [])
      (tg/handle-update state {:update_id 23
                              :callback_query {:id "cb15"
                                               :from {:id 7 :username "smoke"}
                                               :data "docs:agreement:party:skip:client-representative"
                                               :message {:message_id 28 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (str/includes? (str (:text payload)) "Our representative") "prompts our representative"))

      ;; skip our representative -> recipient
      (reset! calls [])
      (tg/handle-update state {:update_id 24
                              :callback_query {:id "cb16"
                                               :from {:id 7 :username "smoke"}
                                               :data "docs:agreement:party:skip:our-representative"
                                               :message {:message_id 28 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (str/includes? (str (:text payload)) "receives funds") "prompts recipient"))

      ;; skip recipient -> effective date picker (skip allowed)
      (reset! calls [])
      (tg/handle-update state {:update_id 25
                              :callback_query {:id "cb17"
                                               :from {:id 7 :username "smoke"}
                                               :data "docs:agreement:party:skip:our-recipient"
                                               :message {:message_id 28 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)
            rm (:reply_markup payload)]
        (assert! (any-callback-prefix? rm "dp:day:") "agreement effective date shows calendar")
        (assert! (has-callback? rm "dp:skip") "agreement effective date allows skip"))

      ;; skip effective date -> agreement created with actions keyboard
      (reset! calls [])
      (tg/handle-update state {:update_id 26
                              :callback_query {:id "cb18"
                                               :from {:id 7 :username "smoke"}
                                               :data "dp:skip"
                                               :message {:message_id 28 :chat {:id 42}}}})
      (let [{:keys [path payload]} (last-call calls)
            rm (:reply_markup payload)]
        (assert! (= path "sendMessage") "agreement create sends confirmation")
        (assert! (any-callback-prefix? rm (str "docs:agreements:plan:add:" agreement-id))
                 "agreement actions include Add plan item"))

      ;; add plan item -> kind picker
      (reset! calls [])
      (tg/handle-update state {:update_id 27
                              :callback_query {:id "cb19"
                                               :from {:id 7 :username "smoke"}
                                               :data (str "docs:agreements:plan:add:" agreement-id)
                                               :message {:message_id 29 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (has-callback? (:reply_markup payload) "docs:plan-item:kind:installment")
                 "plan item kind picker present"))

      ;; pick kind -> invoice-on picker
      (reset! calls [])
      (tg/handle-update state {:update_id 28
                              :callback_query {:id "cb20"
                                               :from {:id 7 :username "smoke"}
                                               :data "docs:plan-item:kind:installment"
                                               :message {:message_id 30 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (has-callback? (:reply_markup payload) "docs:plan-item:invoice-on:due")
                 "plan item invoice-on picker present"))

      ;; pick invoice-on -> prompt label
      (reset! calls [])
      (tg/handle-update state {:update_id 29
                              :callback_query {:id "cb21"
                                               :from {:id 7 :username "smoke"}
                                               :data "docs:plan-item:invoice-on:due"
                                               :message {:message_id 31 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (str/includes? (str (:text payload)) "label") "prompts plan item label"))

      ;; label -> amount
      (reset! calls [])
      (tg/handle-update state {:update_id 26
                              :message {:message_id 32
                                        :chat {:id 42}
                                        :from {:id 7 :username "smoke"}
                                        :text "Installment 1"}})
      (let [{:keys [payload]} (last-call calls)]
        (assert! (str/includes? (str (:text payload)) "amount") "prompts plan item amount"))

      ;; amount -> due date calendar (no skip)
      (reset! calls [])
      (tg/handle-update state {:update_id 27
                              :message {:message_id 33
                                        :chat {:id 42}
                                        :from {:id 7 :username "smoke"}
                                        :text "5000"}})
      (let [{:keys [payload]} (last-call calls)
            rm (:reply_markup payload)]
        (assert! (any-callback-prefix? rm "dp:day:") "plan item due date shows calendar")
        (assert! (not (has-callback? rm "dp:skip")) "plan item due date does NOT allow skip"))

      ;; pick due date -> due time picker (skip allowed)
      (reset! calls [])
      (tg/handle-update state {:update_id 28
                              :callback_query {:id "cb18"
                                               :from {:id 7 :username "smoke"}
                                               :data "dp:day:2026-03-01"
                                               :message {:message_id 34 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)
            rm (:reply_markup payload)]
        (assert! (any-callback-prefix? rm "tp:hour:") "plan item due time shows hour picker")
        (assert! (has-callback? rm "tp:skip") "plan item due time allows skip"))

      ;; skip due time -> plan item created
      (reset! calls [])
      (tg/handle-update state {:update_id 29
                              :callback_query {:id "cb19"
                                               :from {:id 7 :username "smoke"}
                                               :data "tp:skip"
                                               :message {:message_id 34 :chat {:id 42}}}})
      (let [{:keys [payload]} (last-call calls)
            rm (:reply_markup payload)]
        (assert! (str/includes? (str (:text payload)) "Plan item") "plan item added confirmation")
        (assert! (any-callback-prefix? rm (str "docs:agreements:plan:list:" agreement-id))
                 "agreement actions include Plan items"))

      (println "OK: docs invoice due-date uses inline calendar + No-due-date button")
      (println "OK: docs payment paid-at date requires inline calendar (no skip, no typing fallback)")
      (println "OK: time picker + note step are click-first (no typing fallback)")
      (println "OK: agreements + plan items flow is click-first"))))

(run!)
