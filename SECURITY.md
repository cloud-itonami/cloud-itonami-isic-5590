# Security Policy

This project handles property license/safety-certification status, booking
records and guest data. Treat vulnerabilities as potentially high impact
even when the demo data is synthetic — an overbooked or unlicensed-facility
booking has direct safety and liability consequences.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential or operator-license-record exposure
- StayGovernor bypass (capacity-overbooking-gate, license-lapsed-gate,
  source-provenance-gate, licensed-disclosure)
- audit-ledger tampering
- over-disclosure beyond a partner contract's tier
- tenant isolation failures
- confirmation of a booking for a property whose license/safety cert has
  lapsed
- confirmation of a booking that overbooks a property's capacity

## Reporting

Use GitHub private vulnerability reporting when available for the
repository. If that is unavailable, contact the repository maintainers
through the cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on booking data, governor enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets and operator-license credentials outside Git.
- Run governor tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for onboarding/coordinator operators and service
  accounts.
- Alert on any capacity-overbooking-gate or license-lapsed-gate HOLD spike
  — it may indicate a compromised or malfunctioning upstream channel.
