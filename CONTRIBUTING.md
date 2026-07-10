# Contributing

`cloud-itonami-isic-5590` accepts contributions to the OSS actor, governor
tests, documentation, examples and open business blueprint.

## Development

```bash
clojure -M:dev:test
clojure -M:lint
```

Keep changes small and include tests for governor, audit, store or
disclosure behavior.

## Rules

- Do not commit real property records, real named guests, credentials or
  partner contract documents.
- Keep production registrations, bookings and disclosures behind
  StayGovernor.
- Treat every new property type or booking channel as high-risk: add tests
  for capacity-overbooking-gate, license-lapsed-gate, source-provenance-gate,
  licensed-disclosure, confidence floor and audit logging.
- Never add a schema field for payment capture, refund execution, or
  physical door/key-code credentials. If a proposed feature needs one, it
  does not belong in this repository — raise it as an ADR instead of
  adding the field.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which governor invariant is affected
- how it was tested
- whether operator or certification docs need updates
