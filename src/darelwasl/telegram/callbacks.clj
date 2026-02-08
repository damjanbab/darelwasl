(in-ns 'darelwasl.telegram)

(comment "Callback handlers are split by domain.")

(load "telegram/callbacks_core")
(load "telegram/callbacks_tasks")
(load "telegram/callbacks_docs")
(load "telegram/callbacks_pickers")
(load "telegram/callbacks_services")
