# Production Readiness Audit Remediation Plan

1. Reproduce each audit claim against the current `dev` construction paths,
   configuration defaults, workflows, fixtures, and focused tests.
2. Mirror the live runaway breaker in replay, preserve observe-only research
   behavior by default, add strict enforcement, and surface structured evidence
   in every backtest report format.
3. Make runaway thresholds configurable and propagate them through foreground,
   daemon, portfolio, and replay session construction.
4. Authenticate daemon mutations with an environment or owner-only state token,
   update CLI clients transparently, and test public reads plus rejected and
   accepted mutations.
5. Default missing FX conversion to fail closed and update examples and reference
   documentation.
6. Pin compiler encoding, remove the `LocalBarStore` warning, and give the DSYNC
   concurrency watchdog enough headroom without weakening its atomicity assertion.
7. Add asynchronous raw MT5 transport evidence and structured tick/fill capture,
   then export checksummed session bundles with `qkt golden capture`.
8. Add a seeded `--chaos` backtest mode and strict replay/live parity coverage
   across 500 generated cases.
9. Derive exact-image soak attestations from health, reconcile, and golden
   evidence; require a valid attestation for `testing -> main` promotion.
10. Document the external evidence boundary without claiming an unrun soak.
11. Run focused tests, formatting checks, the full build and test suite, then
   inspect branch status and commits relative to `dev`.
