# Governance

`cloud-itonami-isic-5590` is an OSS open-business blueprint. Governance
covers both code and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- StayBooking-LLM cannot directly register, confirm or resolve a dispute.
- StayGovernor remains independent of the advisor.
- hard governor violations (capacity-overbooking-gate, license-lapsed-gate,
  source-provenance-gate, licensed-disclosure) cannot be overridden by
  human approval.
- a booking/billing dispute never auto-resolves, at any rollout phase.
- every commit, hold and disclosure event is auditable.
- no schema field exists for payment capture, refund execution or physical
  door/key-code credentials — scope is structural, not a runtime filter
  someone could forget to call.
- real property, guest and partner contract data stays outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, disclosure scope, public business model, operator
certification or license should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit, support and
data-flow review.

Certified operators can lose certification for:

- bypassing governor checks
- disclosing data to an uncontracted party
- confirming a booking that overbooks a property's capacity
- confirming a booking for a property whose license/safety cert has lapsed
- misrepresenting certification status
- failing to respond to security incidents or booking disputes
- hiding material changes to customer-facing operation
