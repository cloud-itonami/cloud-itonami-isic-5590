# cloud-itonami-isic-5590

Open Business Blueprint for **ISIC Rev.4 5590**: other accommodation —
hostels, guesthouses, camping cabins and worker/student dormitories,
distinct from hotels (5510) and whole-home short-term rental — published
as an OSS business that any qualified operator can fork, deploy, run,
improve and sell.

Registers properties, confirms bookings, and serves governed occupancy
reports to booking partners, built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph) StateGraph runtime
(portable `.cljc`, supervised superstep loop, interrupts, Datomic/in-mem
checkpoints) — the same actor pattern as
[`cloud-itonami-isic-6311`](https://github.com/cloud-itonami/cloud-itonami-isic-6311)
and [`cloud-itonami-isic-7820`](https://github.com/cloud-itonami/cloud-itonami-isic-7820).

> **Why an actor layer at all?** A StayBooking-LLM is great at normalizing
> property-onboarding submissions and incoming booking requests — but it
> has **no notion of physical capacity, license/fire-safety-certification
> lapse, or a partner's disclosure entitlement**. Letting it confirm
> bookings directly invites overselling a property's capacity on
> overlapping dates, confirming a stay at a property whose safety
> certificate has lapsed, or over-disclosing guest data beyond a partner's
> contract tier. This project seals the StayBooking-LLM into a single node
> and wraps it with an independent **StayGovernor**, a human **review
> workflow**, and an immutable **audit ledger**.

## Scope (deliberately narrow — read this before anything else)

This actor **registers properties, confirms bookings and serves governed
reports**. It never captures a payment, executes a refund, or issues a
physical door/key-code credential — there is no field anywhere in this
schema for those (see `docs/adr/0001-architecture.md`). Every registration
must cite a real license basis (`src/stay/facts.cljc`: Japan 旅館業法, UK
Fire Safety Order 2005) or an operator-registered `:operator-attested-
license`; every booking must cite a real channel (direct desk or
OTA-partner) — never a bare "the LLM inferred it".

## The core contract

```
request + injected role/tenant/phase context
        │
        ▼
   ┌───────────────┐    proposal      ┌───────────────────────┐
   │ StayBooking-LLM│ ───────────────▶│ StayGovernor           │  (independent system)
   │ (sealed)       │  draft + source │  capacity · license ·  │
   └───────────────┘   citation       │  provenance · human    │
                                       └───────────────────────┘
                                              │
                                   commit / confirm only if allowed
                                              ▼
                                    append-only audit ledger
```

**Single invariant**: StayBooking-LLM never registers, confirms, or
resolves a dispute the StayGovernor would reject.

## Run

```bash
clojure -M:dev:test   # governor contract · store parity · phases · facts
clojure -M:dev:run    # 7-operation demo through one OperationActor
clojure -M:lint
```

## Non-Negotiables

- Do not commit real property records or real named guests.
- Do not add a schema field for payment capture, refund execution, or
  physical door/key-code credentials.
- Do not bypass the StayGovernor for production registration, booking or
  disclosure.
- Do not serve a disclosure without an active, registered contract.
- Do not fabricate a license-basis or booking-channel catalog entry.

License: AGPL-3.0-or-later.
