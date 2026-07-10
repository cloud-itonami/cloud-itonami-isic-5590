# Operator Guide

This guide is for people who want to start an open business from
`cloud-itonami-isic-5590`.

## 1. Fork and Run

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-isic-5590
cd cloud-itonami-isic-5590
clojure -M:dev:test
clojure -M:dev:run
```

The default demo uses entirely fictitious properties and guests.
Production properties/bookings must stay outside the repository, and every
registration must carry a real, verifiable license-basis citation.

## 2. Choose an Operating Mode

| Mode | Use when |
|---|---|
| Demo | validating the actor and governor contract |
| Self-host | one operator owns infrastructure and data |
| Managed tenant | an operator hosts for another property owner |
| Certified operator | itonami.cloud has reviewed security and process controls |

## 3. Production Checklist

- replace demo properties/bookings with real, license-cited data (extend
  `stay.facts/catalog` honestly for jurisdictions with a real statutory
  regime — never fabricate one — and register real operator-license
  records for jurisdictions outside R0)
- configure Datomic Local, kotoba-server or an equivalent durable SSoT
- configure the LLM adapter through environment variables or secret manager
- define partner contract tenants/tiers and RBAC rules
- run `clojure -M:dev:test`
- run `clojure -M:lint`
- verify audit-ledger export
- document backup and restore
- document incident response
- document the booking/billing dispute-handling SLA
- get written legal review for the jurisdictions you serve (lodging
  licensing and fire-safety certification requirements vary by
  jurisdiction and property type)

## 4. Sales Motion

Start with a narrow offer:

1. onboard one real, license-cited property
2. prove governed, tier-scoped disclosure end to end
3. run one booking-confirmation workflow in assisted mode (human-approved)
4. export the audit ledger for review
5. convert to a metered or subscription contract

## 5. Certification Requirements

itonami.cloud certification should require:

- passing tests and lint on the published version
- written data-flow diagram (registration → governor → booking →
  disclosure)
- backup/restore evidence
- incident contact and response window
- proof that production registration/bookings/disclosures go through
  StayGovernor
- proof that real property/guest data is not stored in Git
- proof that a booking/billing dispute channel exists and is
  human-reviewed
- customer-facing support and licensing terms

## 6. Operator Responsibilities

Operators are responsible for:

- lawful basis for each property's license/safety-certification status
- local lodging/hospitality-licensing and fire-safety-certification law
  review
- secure infrastructure and tenant isolation
- honest license-regime-catalog maintenance
- human review workflow for flagged-guest and dispute-request operations
- data-retention policy
- security updates

The OSS project provides software and an operating blueprint. It does not
make an operator compliant by itself, and it does not license or endorse
operation of any specific property.
