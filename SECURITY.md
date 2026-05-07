# Security Policy

## Reporting a vulnerability

Email dicksonanyaele1234@gmail.com with "qkt security" in the subject.

We aim to acknowledge within 7 days and provide a fix or mitigation timeline within 14 days. Please do not file public GitHub issues for security reports.

## Scope

In scope:
- Code execution, deserialization, or path traversal in the engine, broker, or data store layers
- Credential leakage via logs, exception messages, or persisted state
- Order-routing logic that can produce unintended live-broker actions

Out of scope:
- Strategy bugs (incorrect P&L is a strategy concern, not an engine vulnerability)
- DoS via crafted market data — we accept that adversarial feeds can OOM us
- Issues in transitive dependencies that do not affect qkt's actual behavior

## Supported versions

Pre-1.0 releases: only the latest minor receives security fixes. Users on older minors should upgrade to the latest. Once 1.0 ships, this policy will be revisited.
