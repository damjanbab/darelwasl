(in-ns 'darelwasl.telegram)

(comment "Callback parsing (callback_data -> {:type ...}).")

(defn- parse-callback
  [data]
  (when (present-string? data)
    (let [parts (str/split data #":")]
      (case (first parts)
        "dp"
        (case (second parts)
          "noop" {:type :date-picker/noop}
          "nav" {:type :date-picker/nav
                 :value (nth parts 2 nil)}
          "day" {:type :date-picker/day
                 :value (nth parts 2 nil)}
          "quick" {:type :date-picker/quick
                   :value (nth parts 2 nil)}
          "skip" {:type :date-picker/skip}
          nil)
        "tp"
        (case (second parts)
          "now" {:type :time-picker/now}
          "hour" {:type :time-picker/hour
                  :value (nth parts 2 nil)}
          "back" {:type :time-picker/back}
          "set" {:type :time-picker/set
                 :value (str (nth parts 2 "") (nth parts 3 ""))}
          "skip" {:type :time-picker/skip}
          nil)
        "docs"
        (case (second parts)
          "cancel" {:type :docs/cancel}
          "menu" {:type :docs/menu}
          "skip" {:type :docs/skip}
          "client" (case (nth parts 2 nil)
                     "set" {:type :docs/client-set
                            :client-id (nth parts 3 nil)}
                     "pick" {:type :docs/client-pick}
                     nil)
          "agreements" (case (nth parts 2 nil)
                         "menu" {:type :docs/agreements-menu}
                         "pick" {:type :docs/agreements-pick}
                         "create" {:type :docs/agreements-create}
                         "set" {:type :docs/agreements-set
                                :agreement-id (nth parts 3 nil)}
                         "overview" {:type :docs/agreements-overview
                                     :agreement-id (nth parts 3 nil)}
                         "invoices" {:type :docs/agreements-invoices
                                     :agreement-id (nth parts 3 nil)}
                         "payments" {:type :docs/agreements-payments
                                     :agreement-id (nth parts 3 nil)}
                         "propose" {:type :docs/agreements-propose
                                    :agreement-id (nth parts 3 nil)}
                         "accept" {:type :docs/agreements-accept
                                   :agreement-id (nth parts 3 nil)}
                         "due" {:type :docs/agreements-due
                                :agreement-id (nth parts 3 nil)}
                         "plan" (case (nth parts 3 nil)
                                  "list" {:type :docs/agreements-plan-list
                                          :agreement-id (nth parts 4 nil)}
                                  "add" {:type :docs/agreements-plan-add
                                         :agreement-id (nth parts 4 nil)}
                                  nil)
                         nil)
          "agreement" (case (nth parts 2 nil)
                        "party" (case (nth parts 3 nil)
                                  "skip" {:type :docs/agreement-party-skip
                                          :field (nth parts 4 nil)}
                                  nil)
                        "accept" (case (nth parts 3 nil)
                                   "by" (case (nth parts 4 nil)
                                          "skip" {:type :docs/agreement-accept-by-skip
                                                  :agreement-id (nth parts 5 nil)}
                                          nil)
                                   "channels" (case (nth parts 4 nil)
                                                "toggle" {:type :docs/agreement-accept-channels-toggle
                                                          :value (nth parts 5 nil)
                                                          :agreement-id (nth parts 6 nil)}
                                                "done" {:type :docs/agreement-accept-channels-done
                                                        :agreement-id (nth parts 5 nil)}
                                                "skip" {:type :docs/agreement-accept-channels-skip
                                                        :agreement-id (nth parts 5 nil)}
                                                nil)
                                   "consent" (case (nth parts 4 nil)
                                               "yes" {:type :docs/agreement-accept-consent
                                                      :value true
                                                      :agreement-id (nth parts 5 nil)}
                                               "no" {:type :docs/agreement-accept-consent
                                                     :value false
                                                     :agreement-id (nth parts 5 nil)}
                                               "skip" {:type :docs/agreement-accept-consent
                                                       :value :skip
                                                       :agreement-id (nth parts 5 nil)}
                                               nil)
                                   "confirm" {:type :docs/agreement-accept-confirm
                                              :agreement-id (nth parts 4 nil)}
                                   nil)
                        nil)
          "plan-item" (case (nth parts 2 nil)
                        "open" {:type :docs/plan-item-open
                                :agreement-id (nth parts 3 nil)
                                :plan-item-id (nth parts 4 nil)}
                        "kind" {:type :docs/plan-item-kind
                                :value (nth parts 3 nil)}
                        "invoice-on" {:type :docs/plan-item-invoice-on
                                      :value (nth parts 3 nil)}
                        "invoice" (case (nth parts 3 nil)
                                    "issue" {:type :docs/plan-item-invoice-issue
                                             :agreement-id (nth parts 4 nil)
                                             :plan-item-id (nth parts 5 nil)}
                                    "paid" {:type :docs/plan-item-invoice-paid
                                            :agreement-id (nth parts 4 nil)
                                            :plan-item-id (nth parts 5 nil)}
                                    nil)
                        "payment" {:type :docs/plan-item-payment
                                   :agreement-id (nth parts 3 nil)
                                   :plan-item-id (nth parts 4 nil)}
                        "edit" (case (nth parts 3 nil)
                                 "label" {:type :docs/plan-item-edit-label
                                          :agreement-id (nth parts 4 nil)
                                          :plan-item-id (nth parts 5 nil)}
                                 "amount" {:type :docs/plan-item-edit-amount
                                           :agreement-id (nth parts 4 nil)
                                           :plan-item-id (nth parts 5 nil)}
                                 "due" {:type :docs/plan-item-edit-due
                                        :agreement-id (nth parts 4 nil)
                                        :plan-item-id (nth parts 5 nil)}
                                 "invoice-on" {:type :docs/plan-item-edit-invoice-on
                                               :agreement-id (nth parts 4 nil)
                                               :plan-item-id (nth parts 5 nil)}
                                 "kind" {:type :docs/plan-item-edit-kind
                                         :agreement-id (nth parts 4 nil)
                                         :plan-item-id (nth parts 5 nil)}
                                 nil)
                        "toggle" {:type :docs/plan-item-toggle
                                  :agreement-id (nth parts 3 nil)
                                  :plan-item-id (nth parts 4 nil)}
                        "move" (case (nth parts 3 nil)
                                 "up" {:type :docs/plan-item-move
                                       :dir :up
                                       :agreement-id (nth parts 4 nil)
                                       :plan-item-id (nth parts 5 nil)}
                                 "down" {:type :docs/plan-item-move
                                         :dir :down
                                         :agreement-id (nth parts 4 nil)
                                         :plan-item-id (nth parts 5 nil)}
                                 nil)
                        "set" (case (nth parts 3 nil)
                                "invoice-on" {:type :docs/plan-item-set-invoice-on
                                              :value (nth parts 4 nil)
                                              :agreement-id (nth parts 5 nil)
                                              :plan-item-id (nth parts 6 nil)}
                                "kind" {:type :docs/plan-item-set-kind
                                        :value (nth parts 4 nil)
                                        :agreement-id (nth parts 5 nil)
                                        :plan-item-id (nth parts 6 nil)}
                                nil)
                        nil)
          "field" {:type :docs/field
                   :value (nth parts 2 nil)}
          "currency" {:type :docs/currency
                      :value (nth parts 2 nil)}
          "invoice" (case (nth parts 2 nil)
                      "add" {:type :docs/invoice-add}
                      "status" {:type :docs/invoice-status
                                :value (nth parts 3 nil)}
                      "open" {:type :docs/invoice-open
                              :agreement-id (nth parts 3 nil)
                              :invoice-id (nth parts 4 nil)}
                      "pdf" {:type :docs/invoice-pdf
                             :agreement-id (nth parts 3 nil)
                             :invoice-id (nth parts 4 nil)}
                      "payment" {:type :docs/invoice-payment
                                 :agreement-id (nth parts 3 nil)
                                 :invoice-id (nth parts 4 nil)}
                      "receipts" {:type :docs/invoice-receipts
                                  :agreement-id (nth parts 3 nil)
                                  :invoice-id (nth parts 4 nil)}
                      nil)
          "payment" (case (nth parts 2 nil)
                      "add" {:type :docs/payment-add}
                      "method" {:type :docs/payment-method
                                :value (nth parts 3 nil)}
                      "note" (case (nth parts 3 nil)
                               "skip" {:type :docs/payment-note-skip}
                               nil)
                      "invoice" (case (nth parts 3 nil)
                                  "pick" {:type :docs/payment-invoice-pick}
                                  "set" {:type :docs/payment-invoice-set
                                         :invoice-id (nth parts 4 nil)}
                                  "skip" {:type :docs/payment-invoice-skip}
                                  nil)
                      "ref" (case (nth parts 3 nil)
                              "skip" {:type :docs/payment-ref-skip}
                              nil)
                      nil)
          "generate" (case (nth parts 2 nil)
                       "proposal" {:type :docs/generate
                                   :value :proposal}
                       "status-report" {:type :docs/generate
                                        :value :status-report}
                       "invoice" (case (nth parts 3 nil)
                                   "pick" {:type :docs/generate-invoice-pick}
                                   "set" {:type :docs/generate-invoice-set
                                          :invoice-id (nth parts 4 nil)}
                                   nil)
                       "receipt" (case (nth parts 3 nil)
                                   "pick" {:type :docs/generate-receipt-pick}
                                   "set" {:type :docs/generate-receipt-set
                                          :payment-id (nth parts 4 nil)}
                                   nil)
                       nil)
          "analytics" (case (nth parts 2 nil)
                        "menu" {:type :docs/analytics-menu}
                        "revenue" {:type :docs/analytics-revenue}
                        "outstanding" {:type :docs/analytics-outstanding}
                        nil)
          nil)
        "filter"
        (case (second parts)
          "status" {:type :tasks/filter
                    :filter :status
                    :value (when-let [v (nth parts 2 nil)]
                             (when-not (= v "all") (keyword v)))}
          "archived" {:type :tasks/filter
                      :filter :archived
                      :value (keyword (or (nth parts 2 nil) "active"))}
          "refresh" {:type :tasks/filter
                     :filter :refresh
                     :value nil}
          nil)
        "capture"
        (case (second parts)
          "task" {:type :capture/task}
          "client" {:type :capture/client}
          "cancel" {:type :capture/cancel}
          nil)
        "pending"
        (case (second parts)
          "reason" {:type :pending/reason
                    :task-id (nth parts 2 nil)
                    :value (nth parts 3 nil)}
          "followup" {:type :pending/followup
                      :task-id (nth parts 2 nil)
                      :value (nth parts 3 nil)}
          "cancel" {:type :pending/cancel
                    :task-id (nth parts 2 nil)}
          nil)
        "task"
        (case (second parts)
          "status" {:type :task/status
                    :task-id (nth parts 2 nil)
                    :value (nth parts 3 nil)}
          "client" (case (nth parts 2 nil)
                     "pick" {:type :task/client-pick
                             :task-id (nth parts 3 nil)}
                     "create" {:type :task/client-create
                               :task-id (nth parts 3 nil)}
                     "skip" {:type :task/client-skip
                             :task-id (nth parts 3 nil)}
                     "set" {:type :task/client-set
                            :task-id (nth parts 3 nil)
                            :client-id (nth parts 4 nil)}
                     "cancel" {:type :task/client-cancel
                               :task-id (nth parts 3 nil)}
                     nil)
          "archive" {:type :task/archive
                     :task-id (nth parts 2 nil)
                     :value (nth parts 3 nil)}
          "delete" {:type :task/delete
                    :task-id (nth parts 2 nil)}
          "view" {:type :task/view
                  :task-id (nth parts 2 nil)}
          "edit" (case (nth parts 2 nil)
                   "title" {:type :task/edit-title
                            :task-id (nth parts 3 nil)}
                   "desc" {:type :task/edit-desc
                           :task-id (nth parts 3 nil)}
                   "cancel" {:type :task/edit-cancel
                             :task-id (nth parts 3 nil)}
                   nil)
          "note" (case (nth parts 2 nil)
                   "add" {:type :task/note-add
                          :task-id (nth parts 3 nil)}
                   "edit" {:type :task/note-edit
                           :task-id (nth parts 3 nil)}
                   "delete" {:type :task/note-delete
                             :task-id (nth parts 3 nil)}
                   nil)
          nil)
        "client"
        (case (second parts)
          "action" {:type :client/action
                    :client-id (nth parts 2 nil)
                    :value (nth parts 3 nil)}
          nil)
        nil))))

