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
   "# Master Terms\n"
   "\n"
   "**Governing law and jurisdiction:** Kingdom of Saudi Arabia (Saudi courts).\n"
   "\n"
   "This Service Agreement (\"Agreement\") sets the contractual terms for delivery of Dar El Wasl services.\n"
   "It is intended to be used together with an official Proposal (PDF) issued for the Client.\n"
   "\n"
   "## 1. Parties\n"
   "- **Service Provider:** Dar El Wasl (\"DEW\"), represented by the assigned representative.\n"
   "- **Client:** The client referenced in this Agreement and the associated Proposal.\n"
   "\n"
   "## 2. Definitions\n"
   "- **Proposal:** The official Proposal PDF issued by DEW for the Client, including scope, deliverables, and payment schedule.\n"
   "- **Portal:** DEW’s client portal where documents and status updates may be shared.\n"
   "- **Authorities:** Government bodies and related platforms relevant to the Client’s services (e.g., MISA, Ministry of Commerce,\n"
   "  ZATCA, GOSI, Qiwa, Muqeem, Mudad, SPL, Chamber of Commerce, and others as applicable).\n"
   "- **External Fees:** Any third‑party fees, government fees, subscriptions, or charges payable to Authorities or third parties.\n"
   "- **Business Days:** Sunday to Thursday in Saudi Arabia, excluding public holidays.\n"
   "\n"
   "## 3. Documents and order of precedence\n"
   "1) The Proposal defines the agreed deliverables and payment schedule.\n"
   "2) This Agreement defines the delivery terms and responsibilities.\n"
   "3) If there is any conflict: Proposal controls deliverables/payment plan; this Agreement controls delivery terms.\n"
   "4) Any scope change must be confirmed in writing (portal update, email, or WhatsApp) before execution.\n"
   "\n"
   "## 4. Scope of services\n"
   "DEW will provide the services listed in the Proposal and any attached scope pages. Depending on the Proposal, services may include:\n"
   "- Business setup and licensing (e.g., MISA licensing, Ministry of Commerce CR issuance)\n"
   "- Registrations and compliance (e.g., ZATCA, GOSI, Qiwa, Muqeem, Mudad, SPL)\n"
   "- Document preparation, submissions, follow‑ups, and handling authority queries/review cycles\n"
   "\n"
   "**Important:** Authority requirements and review cycles may change and may require revisions.\n"
   "\n"
   "## 5. Delivery model (tracked execution)\n"
   "- DEW delivers work through a structured workflow with tasks, documents, and status updates (\"Tracked Execution\").\n"
   "- Some steps require iterations (review cycles) and cannot be guaranteed to complete in a single pass.\n"
   "- The Portal and recorded delivery channels may be used as the operational record for requests, approvals, and updates.\n"
   "\n"
   "## 6. Fees, payment plan, invoices, and receipts\n"
   "- Fees, deposit (if any), and milestones are defined in the Proposal payment schedule.\n"
   "- DEW may issue invoices in line with the payment schedule; invoices are payable upon receipt unless otherwise stated.\n"
   "- If a payment becomes overdue, DEW may pause work, withhold deliverables, or suspend progress until amounts due are settled.\n"
   "- All payments are recorded through official receipts.\n"
   "\n"
   "## 7. External fees and VAT\n"
   "- External Fees are excluded unless explicitly stated in the Proposal.\n"
   "- External Fees are subject to change without prior notice. Client is responsible for new or additional charges introduced during delivery.\n"
   "- VAT is not included unless stated. If VAT applies under Saudi law (including where payments are made into a Saudi-based bank account),\n"
   "  Client shall pay VAT in addition to service fees; DEW will issue a VAT invoice if required.\n"
   "\n"
   "## 8. Client responsibilities\n"
   "- Provide accurate information and required documents in a timely manner.\n"
   "- Pay fees and External Fees as required.\n"
   "- Respond to requests within 2 Business Days where reasonably possible to avoid delays.\n"
   "- Review and approve drafts/documents in a timely manner.\n"
   "\n"
   "## 9. DEW responsibilities\n"
   "- Deliver services professionally and in good faith.\n"
   "- Provide updates when Client documents are missing or when Authority requirements change.\n"
   "- Maintain confidentiality of Client personal and business information.\n"
   "- Respond within 2 Business Days where reasonably possible (excluding closures/public holidays).\n"
   "\n"
   "## 10. No guarantee; timelines\n"
   "- Timelines are indicative and depend on Client responsiveness and Authority processing.\n"
   "- DEW does not guarantee approvals, issuance, or decisions made by Authorities.\n"
   "\n"
   "## 11. Delays and non-refundability in authority delays\n"
   "If delays occur due to Authority backlogs, policy changes, or review timelines, payments made are non-refundable.\n"
   "\n"
   "## 12. Provider-fault refund\n"
   "If the primary service outcome (as stated in the Proposal) is not obtained solely due to an error or failure on DEW's part,\n"
   "DEW will issue a full refund of service fees paid by the Client for that service. External Fees are non-refundable.\n"
   "\n"
   "## 13. Confidentiality and IP\n"
   "- Both parties will keep non-public information confidential except as required by law.\n"
   "- Templates, materials, and know-how provided by DEW remain DEW property unless expressly transferred in writing.\n"
   "\n"
   "## 14. Limitation of liability\n"
   "To the maximum extent permitted by law:\n"
   "- DEW's total liability under this Agreement is capped at the total service fees paid by the Client under the Proposal.\n"
   "- DEW is not liable for Authority decisions, Authority system outages, or third-party delays.\n"
   "- DEW is not liable for indirect or consequential damages.\n"
   "\n"
   "## 15. Termination\n"
   "- Either party may terminate with written notice.\n"
   "- Amounts due for completed work and agreed milestones remain payable.\n"
   "- If termination occurs due to Client non-responsiveness or non-payment, DEW may pause delivery and/or close the case after notice.\n"
   "- On termination, DEW will provide the Client with the latest available drafts/documents prepared up to the termination date,\n"
   "  subject to settlement of amounts due.\n"
   "\n"
   "## 16. Force majeure\n"
   "DEW shall not be liable for delays/failures due to events beyond reasonable control, including government processing delays,\n"
   "technical failures, internet outages, legal changes, or acts of God.\n"
   "\n"
   "## 17. Notices\n"
   "Notices may be delivered to the recorded delivery channels (email/WhatsApp/Telegram) and are deemed received when sent.\n"
   "\n"
   "## 18. Authority compliance; no improper payments\n"
   "- Client and DEW agree to comply with applicable laws and Authority requirements.\n"
   "- DEW does not support improper payments, facilitation payments, or any unlawful conduct.\n"
   "- If any Authority requests or requirements conflict with applicable law, DEW may suspend the relevant part of delivery pending resolution.\n"
   "\n"
   "## 19. Relationship of the parties\n"
   "DEW is an independent service provider. Nothing in this Agreement creates a partnership, agency, or employment relationship.\n"
   "\n"
   "## 20. Entire agreement; severability; assignment\n"
   "- This Agreement together with the Proposal constitutes the entire agreement between the parties regarding the services.\n"
   "- If any provision is held invalid, the remaining provisions remain in effect.\n"
   "- Client may not assign this Agreement without DEW’s written consent.\n"
   "\n"
   "## 21. Modification\n"
   "No amendment is binding unless confirmed in writing by both parties.\n"
   "\n"
   "## 22. Acceptance and delivery channels\n"
   "- Client agrees that electronic acceptance (email/WhatsApp/Telegram confirmation and portal confirmation) may be valid where enabled.\n"
   "- Delivery channels are the ones recorded in this Agreement.\n"
   "\n"
   "— End of Master Terms —\n"))

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
