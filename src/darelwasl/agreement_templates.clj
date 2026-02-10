(ns darelwasl.agreement-templates
  (:require [clojure.string :as str]))

(def ^:private system-v1-title
  "Dar El Wasl Service Agreement — System v1")

(def ^:private system-v1-terms
  (str
   "DAR EL WASL — SERVICE AGREEMENT (SYSTEM v1)\n"
   "\n"
   "This Agreement sets the ground rules for how Dar El Wasl delivers services through a tracked, document-based workflow.\n"
   "It is designed to keep execution fast, clear, and auditable for both parties.\n"
   "\n"
   "1) Parties\n"
   "• Service Provider: Dar El Wasl (\"DEW\")\n"
   "• Client: The client referenced in this Agreement and the associated Proposal.\n"
   "\n"
   "2) Documents and scope\n"
   "• The official Proposal (PDF) defines the agreed scope, deliverables, and payment schedule.\n"
   "• If there is any conflict, the Proposal scope controls the deliverables, and this Agreement controls the process/terms.\n"
   "• Any scope change must be confirmed in writing (portal update, email, or WhatsApp) before execution.\n"
   "\n"
   "3) Delivery model (tracked execution)\n"
   "• DEW delivers work through a structured workflow with tasks, documents, and status updates.\n"
   "• The Client will receive progress updates and document requests through the portal and/or the delivery channels selected.\n"
   "• The Client understands that some steps may require iterations (review cycles) and cannot be guaranteed to be completed in a single pass.\n"
   "\n"
   "4) Client responsibilities\n"
   "• Provide accurate information and required documents in a timely manner.\n"
   "• Respond to questions/requests that block progress.\n"
   "• Confirm decisions that affect scope, milestones, or timeline.\n"
   "\n"
   "5) Fees, payment plan, and invoicing\n"
   "• Fees and milestones are defined in the Proposal payment schedule.\n"
   "• Invoices may be issued based on the payment plan milestones and are payable upon receipt unless otherwise stated.\n"
   "• If a payment becomes overdue, DEW may pause work until the outstanding amount is settled.\n"
   "• All payments are recorded through official receipts.\n"
   "\n"
   "6) External fees and exclusions\n"
   "• Third-party / external fees (e.g., government or service-provider fees) are excluded unless explicitly stated in the Proposal.\n"
   "• Timelines depend on Client responsiveness and external review cycles.\n"
   "\n"
   "7) Communication and consent\n"
   "• The Client agrees to receive documents and updates via the delivery channels recorded in this Agreement.\n"
   "• Electronic acceptance (including via email/WhatsApp/Telegram confirmation) is valid where enabled.\n"
   "\n"
   "8) Confidentiality\n"
   "• Both parties will keep non-public information confidential and use it only for delivery of the services.\n"
   "\n"
   "9) Termination\n"
   "• Either party may terminate the engagement with written notice.\n"
   "• Amounts due for completed work and agreed milestones remain payable.\n"
   "\n"
   "10) Final\n"
   "• This Agreement is intended to keep delivery clear and professional; it does not disclose internal authority/portal names.\n"
   "\n"
   "— End of terms —\n"))

(defn system-v1
  "Return the default system agreement template (English)."
  []
  {:template/id :agreement.template/system-v1
   :template/title system-v1-title
   :template/terms system-v1-terms})

(defn title
  "Convenience: template title by keyword."
  [template-id]
  (case template-id
    :agreement.template/system-v1 (:template/title (system-v1))
    nil))

(defn terms
  "Convenience: template terms by keyword."
  [template-id]
  (case template-id
    :agreement.template/system-v1 (:template/terms (system-v1))
    nil))

