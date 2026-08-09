# Production Readiness Audit Remediation Design

## Scope

This change audits the production-readiness findings reported against `dev` at
`ad9f47dc` and fixes the findings that are reproducible as repository behavior.
It does not manufacture external venue or soak evidence that has not been run.

| Finding | Verification | Disposition |
| --- | --- | --- |
| E1: live-only runaway breaker | Reproduced from construction paths: `LiveSession` wired the breaker and `ReplayEngine` did not | Fixed in replay, reports, configuration, and strict parity coverage |
| E2: unauthenticated control mutations | Reproduced: all daemon `POST` routes accepted requests on loopback without a credential | Fixed with a bearer token; read-only routes remain public on loopback |
| E3: no release-image paper soak attestation | Confirmed in `promote-to-main.yml` | Remains an operational evidence gap; a repository-only test is not a substitute for an external demo gateway soak |
| E4: narrow authentic MT5 golden evidence | Confirmed: one retained authentic fill, alongside existing deterministic chaos and simulator coverage | Remains an evidence-collection gap; broader real session captures are still required |
| E5: warning FX default | Reproduced: omitted policy selected `WARN` outside production | Fixed by making `FAIL` the universal default; `warn` is now an explicit unsafe override |
| Test-suite forensics | Reproduced from source and focused tests | Hardened the DSYNC watchdog, pinned UTF-8, removed the warning in `LocalBarStore`, and verified the wrapper mode bit |

## Runaway Breaker Parity

Replay constructs the same `RunawayBreaker` used by live execution. The default
replay mode is observe-only: it records structured trips and continues so the
research equity curve remains available. Text, JSON, persisted `result.json`,
and HTML outputs disclose the active thresholds and the first divergence point.

`--enforce-live-breakers` switches replay to live enforcement. A parity test
feeds the same rapid round-trip strategy and ticks through strict replay and
`LiveSession`, then requires identical trades and a persistent halt.

The thresholds are global risk configuration because one `LiveSession` owns one
strategy pipeline and a portfolio creates one session per child:

```yaml
risk:
  max_round_trips_10m: 10
  max_broker_rejections_1m: 5
```

Zero disables the corresponding rule. Negative or malformed values fail config
validation.

## Control Plane Authentication

Daemon startup resolves one bearer credential in this order:

1. Non-blank `QKT_CONTROL_TOKEN`.
2. Existing `<state-dir>/control.token`.
3. A newly generated 256-bit URL-safe token persisted to that path.

On POSIX filesystems, daemon startup sets the state token to `0600`, including
pre-existing token files. CLI mutation commands resolve the credential using the
same precedence and add an `Authorization: Bearer` header. Every `POST` route is
authenticated before route dispatch; loopback `GET` endpoints stay available to
monitoring. Token comparison uses `MessageDigest.isEqual`.

Unix-domain-socket transport is not included. It requires replacing or adapting
the JDK HTTP server and the CLI HTTP client and is defense in depth after the
verified unauthenticated mutation defect is closed.

## FX Conversion Default

Missing conversion paths now fail closed in every runtime mode. Operators can
still explicitly select `fx_conversion.missing_policy: warn` for compatibility,
and reports retain the warning evidence. Replay already includes configured FX
symbols in its data requirements, so the audit claim that this wiring was absent
was stale for the audited revision.

## Test Environment Hardening

The Gradle and Kotlin daemon JVMs pin UTF-8. The two OCO test names implicated by
the report use ASCII punctuation as an additional defense. The wrapper is already
tracked as executable (`100755`), so no mode change is needed.

The state-writer concurrency test keeps the zero-torn-read assertion unchanged.
Only its deadlock watchdog expands from 10 to 60 seconds because 200 synchronous
DSYNC writes can legitimately exceed 10 seconds on throttled overlay storage.
Persistence remains off the engine tick path.

## Hot-Path Cost

Live execution adds no new per-tick work. Existing breaker recording remains on
closing fills and broker rejections only. Replay adds the same keyed deque work
at those lifecycle events and appends one evidence object per distinct threshold
breach. Control authentication runs on HTTP worker threads. Token persistence and
permission repair occur once at daemon startup.

## Evidence Boundary

This change does not claim that a release image completed a paper soak or that
additional MT5 venue behaviors were captured. Those claims require an external
demo terminal, exact image-digest attestation, and retained session artifacts.
Existing chaos tests and the single authentic golden fill remain useful but do
not close those broader empirical boundaries.
