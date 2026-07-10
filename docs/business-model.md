# Open Business Blueprint: cloud-itonami-isic-5590

This repository publishes an OSS business model for operating an
alternative/short-stay accommodation booking service (hostel/guesthouse/
camping-cabin/dormitory management platform, distinct from hotel PMS and
whole-home rental platforms) on itonami.cloud.

## Classification

- Repository name: `cloud-itonami-isic-5590`
- Primary classification: ISIC Rev.4 5590 (Other accommodation)
- Activity: registering properties, confirming bookings and serving
  governed occupancy reports for hostels, guesthouses, camping cabins and
  worker/student dormitories

## Customer

Primary customers (contracted, licensed access only):

- independent hostel/guesthouse operators needing a governed booking
  system without building capacity/license-compliance logic themselves
- OTA (online travel agency) partners needing a licensed reporting feed
- dormitory operators (worker housing, student housing) needing tenure/
  capacity tracking without a full property-management-system purchase

## Problem

Off-the-shelf hostel/guesthouse booking software either lacks structural
overbooking prevention (relying on manual staff diligence) or lacks any
license/safety-certification awareness at all, letting a property keep
taking bookings after its fire-safety certificate lapses. Neither offers
an inspectable governance trail for why a booking was accepted, held, or
escalated.

## Offer

Operators provide an OSS actor for alternative-accommodation booking:

- property registration with license-basis citation
  (jurisdiction-appropriate statutory regime, or an operator-registered
  license for other jurisdictions)
- booking confirmation with structural overbooking prevention
  (interval-overlap capacity arithmetic against every other confirmed
  booking at the property)
- structural license/safety-certification-lapse awareness — no booking
  confirms at a property whose license isn't active or whose safety cert
  has expired before check-in
- governed, tier-scoped partner reporting (never a public/anonymous query
  surface)
- an operator caution-flag mechanism for guests, always human-reviewed
  before confirming
- a booking/billing dispute channel, always human-reviewed
- immutable audit ledger of every registration/booking/disclosure event

The core promise: StayBooking-LLM can draft property/booking
normalization, but it cannot register, confirm, or resolve a dispute
unless the independent StayGovernor allows it.

## Revenue

Operators can sell:

- per-property or per-booking licensed access (contract tenant × tier)
- tiered subscriptions: `:tier/basic` (booking status only) →
  `:tier/detailed` (+ guest identity/count)
- managed hosting: monthly subscription per property
- compliance package: audit export, dispute-handling SLA, security review

| Package | Customer | Price shape |
|---|---|---|
| Basic booking feed | small hostel/guesthouse | per-booking or low monthly tier |
| Detailed tier | OTA partner / property-owner portal | monthly platform fee |
| Managed hosting | multi-property operator | monthly fee + usage |

## Unit Economics

Track these numbers for every operator:

- property-onboarding hours per new license jurisdiction
- monthly infrastructure cost
- LLM cost per operation (register / place / report)
- dispute handling hours per tenant
- gross margin after infrastructure and support
- churn and expansion revenue per contract tier

## Open Participation

Anyone may fork, run the demo, deploy self-hosted, submit patches, publish
compatible license-regime catalog extensions (real, citable regimes only),
or create a local operator business.

## Marketplace Metadata

```edn
{:itonami.blueprint/id "cloud-itonami-isic-5590"
 :itonami.blueprint/name "Alternative Accommodation Booking Actor"
 :itonami.blueprint/isic-rev4 "5590"
 :itonami.blueprint/domain :hospitality/alternative-accommodation
 :itonami.blueprint/license "AGPL-3.0-or-later"
 :itonami.blueprint/operator-model :certified-open-business
 :itonami.blueprint/repo "https://github.com/cloud-itonami/cloud-itonami-isic-5590"
 :itonami.blueprint/status :public-oss
 :itonami.blueprint/required-technologies [:identity :forms :audit-ledger]
 :itonami.blueprint/optional-technologies [:dmn :bpmn]}
```

## Non-Negotiables

- Do not commit real property records or real named guests.
- Do not add a schema field for payment capture, refund execution, or
  physical door/key-code credentials.
- Do not bypass the StayGovernor for production registration, booking or
  disclosure.
- Do not serve a disclosure to a tenant without an active, registered
  contract.
- Do not fabricate a license-basis or booking-channel catalog entry.
- Do not market an uncertified deployment as an itonami.cloud certified
  operator.
