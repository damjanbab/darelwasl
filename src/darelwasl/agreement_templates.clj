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

(def ^:private system-v2-title
  "Dar El Wasl Service Agreement (Saudi Arabia Courts) — System v2")

(def ^:private system-v2-terms
  (str
   "DAR EL WASL — SERVICE AGREEMENT (SYSTEM v2)\n"
   "Governing law and jurisdiction: Kingdom of Saudi Arabia (Saudi courts).\n"
   "\n"
   "This Service Agreement (\"Agreement\") sets the contractual terms for delivery of Dar El Wasl services.\n"
   "It is intended to be used together with an official Proposal PDF issued for the Client.\n"
   "\n"
   "1) Parties\n"
   "1.1 Service Provider: Dar El Wasl (\"DEW\"), represented by the assigned representative (e.g., Huda Sabir).\n"
   "1.2 Client: The client referenced in this Agreement and the associated Proposal.\n"
   "\n"
   "2) Documents and order of precedence\n"
   "2.1 The official Proposal (PDF) defines the agreed scope, deliverables, service title, and payment schedule.\n"
   "2.2 This Agreement defines the process/terms of delivery (payments, responsibilities, acceptance, confidentiality, etc.).\n"
   "2.3 If there is any conflict: Proposal controls deliverables/payment plan; this Agreement controls delivery terms.\n"
   "2.4 Any scope change must be confirmed in writing (portal update, email, or WhatsApp) before execution.\n"
   "\n"
   "3) Scope of services\n"
   "3.1 DEW will provide the services listed in the Proposal and any attached scope pages.\n"
   "3.2 Services may include (depending on the Proposal): business setup and licensing (e.g., MISA licensing, Ministry of Commerce CR issuance),\n"
   "    registrations (e.g., ZATCA, GOSI, Qiwa, Muqeem, Mudad, SPL), PRO/GRO support, document preparation, submissions, follow-ups,\n"
   "    and handling authority queries and review cycles.\n"
   "3.3 Client acknowledges that authority/governing-body requirements, forms, and review cycles may change and may require revisions.\n"
   "\n"
   "4) Delivery model (tracked execution)\n"
   "4.1 DEW delivers work through a structured workflow with tasks, documents, and status updates (\"Tracked Execution\").\n"
   "4.2 The Client will receive progress updates and document requests through the portal and/or recorded delivery channels.\n"
   "4.3 Some steps may require iterations (review cycles) and cannot be guaranteed to complete in a single pass.\n"
   "\n"
   "5) Fees, payment plan, invoices, and receipts\n"
   "5.1 Fees, deposit (if any), and milestones are defined in the Proposal payment schedule.\n"
   "5.2 DEW may issue invoices in line with the payment schedule; invoices are payable upon receipt unless otherwise stated.\n"
   "5.3 If a payment becomes overdue, DEW may pause work, withhold deliverables, or suspend progress until amounts due are settled.\n"
   "5.4 All payments are recorded through official receipts.\n"
   "\n"
   "6) Government/external fees and VAT\n"
   "6.1 Third-party/external fees (e.g., government and portal fees) are excluded unless explicitly stated in the Proposal.\n"
   "6.2 Government fees are subject to change without prior notice. Client is responsible for new or additional charges introduced during delivery.\n"
   "6.3 VAT is not included unless stated. If VAT applies under Saudi law (especially when paid into a Saudi-based bank account),\n"
   "    Client shall pay VAT in addition to service fees; DEW will issue a VAT invoice if required.\n"
   "\n"
   "7) Obligations of the parties\n"
   "7.1 DEW obligations:\n"
   "• Deliver services professionally and in good faith.\n"
   "• Provide updates when Client documents are missing or when authority requirements change.\n"
   "• Maintain confidentiality of Client personal and business information.\n"
   "• Respond within 1–2 working days where reasonably possible (excluding closures/public holidays).\n"
   "\n"
   "7.2 Client obligations:\n"
   "• Provide accurate information and required documents in a timely manner.\n"
   "• Pay fees and external/government charges as required.\n"
   "• Respond to requests within 2 working days where reasonably possible to avoid delays.\n"
   "• Review and approve drafts/documents in a timely manner.\n"
   "\n"
   "8) Timeline, delays, and non-refundability in authority delays\n"
   "8.1 Timelines depend on Client responsiveness and authority processing.\n"
   "8.2 If delays occur due to authority backlogs, policy changes, or review timelines, payments made are non-refundable.\n"
   "\n"
   "9) Provider-fault refund\n"
   "If the primary service outcome (as stated in the Proposal) is not obtained solely due to an error or failure on DEW's part,\n"
   "DEW will issue a full refund of fees paid by the Client for that service.\n"
   "\n"
   "10) Confidentiality and IP\n"
   "10.1 Both parties will keep non-public information confidential except as required by law.\n"
   "10.2 Templates, materials, and know-how provided by DEW remain DEW property unless expressly transferred in writing.\n"
   "\n"
   "11) Limitation of liability\n"
   "To the maximum extent permitted by law, DEW's total liability under this Agreement is capped at the total service fees paid by the Client\n"
   "under the Proposal. DEW is not liable for authority decisions, authority system outages, or third-party delays.\n"
   "\n"
   "12) Force majeure\n"
   "DEW shall not be liable for delays/failures due to events beyond reasonable control, including government processing delays,\n"
   "technical failures, internet outages, legal changes, or acts of God.\n"
   "\n"
   "13) Modification\n"
   "No amendment is binding unless confirmed in writing by both parties.\n"
   "\n"
   "14) Acceptance and delivery channels\n"
   "Client agrees that electronic acceptance (email/WhatsApp/Telegram confirmation and portal confirmation) may be valid where enabled.\n"
   "Delivery channels are the ones recorded in this Agreement.\n"
   "\n"
   "15) Signatures\n"
   "Service Provider (Dar El Wasl): ____________________   Name/Title: ____________________   Date: __________\n"
   "Client: ____________________   Name/Title: ____________________   Date: __________\n"
   "\n"
   "— End of Agreement —\n"))

(defn system-v1
  "Return the default system agreement template (English)."
  []
  {:template/id :agreement.template/system-v1
   :template/title system-v1-title
   :template/terms system-v1-terms})

(defn system-v2
  "Return the system agreement template aligned with KSA courts and authority delivery."
  []
  {:template/id :agreement.template/system-v2
   :template/title system-v2-title
   :template/terms system-v2-terms})

(defn title
  "Convenience: template title by keyword."
  [template-id]
  (case template-id
    :agreement.template/system-v1 (:template/title (system-v1))
    :agreement.template/system-v2 (:template/title (system-v2))
    nil))

(defn terms
  "Convenience: template terms by keyword."
  [template-id]
  (case template-id
    :agreement.template/system-v1 (:template/terms (system-v1))
    :agreement.template/system-v2 (:template/terms (system-v2))
    nil))
