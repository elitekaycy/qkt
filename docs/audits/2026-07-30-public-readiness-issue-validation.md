# Public-readiness issue validation

Validated against `origin/dev` at `aeefac87` on 2026-07-30. An issue is
**confirmed** when its described path remains present in code or its documented
command fails when executed. **Partial** means the ticket remains actionable but
one or more statements or requested tests are already satisfied.

## PR grouping

### Group A: bounded hardening

Low-blast-radius parser, CLI, test, onboarding, release-gate, configuration, and
documentation changes:

- #901 onboarding path
- #911 division by zero
- #912 unterminated block comments
- #915 partial `DEFAULTS` brackets
- #917 warmup guard coverage
- #918 unknown and short CLI flags
- #926 public docs and operations batch
- #902 tag/version guard only; publishing the release waits for both groups

### Group B: runtime safety and parity

Order lifecycle, broker ambiguity, recovery, shutdown, money safety, DSL runtime
semantics, and backtest/live parity:

- #854 and #894-#900
- #903-#910
- #913-#914 and #916
- #919-#925
- #902 release publication and #927 umbrella closure after verification

## Validation matrix

| Issue | Result | Current evidence |
| --- | --- | --- |
| #854 | Confirmed | `PaperBroker` has no instrument volume quantization while MT5 simulation does. It is an explicit #927 dependency. |
| #894 | Confirmed | `OrderManager` makes broker rejection terminal; a continuously true CLOSE edge has no asynchronous rejection re-arm. |
| #895 | Confirmed | Live constructs and samples book risk differently from replay, and no live end-to-end test drives losing fills through the controller. |
| #896 | Confirmed | Bybit position reads still collapse failures to empty state and ambiguous placement failures are published as rejection. |
| #897 | Confirmed | `LiveSession.doFlatten` still submits net opposite markets without `closesTicket`; hedged net-zero books are skipped. |
| #898 | Confirmed | Close resolution can outlive the poller's recent-close TTL and the accounting path has no fill-level deduplication across the two order IDs. |
| #899 | Confirmed | Halt cancellation dispatch is one-shot; failed venue cancellation has no confirmation deadline, retry, or critical event. |
| #900 | Confirmed | Persisted single pending orders are not loaded into engine restore/reconciliation. |
| #901 | Confirmed | Execution found invalid brackets, unsupported flags, missing dates, an empty `data/sample`, invalid tutorial `LOG` actions, and Docker-only port claims. |
| #902 | Confirmed | `VERSION` is `0.47.1` while the latest published release is `v0.47.0`; tag workflows did not verify tag/version equality. |
| #903 | Confirmed | Halt sweep scope is symbol-wide and does not distinguish entry intent from protective/exit orders. |
| #904 | Confirmed, narrow | Default halts cancel first, but `cancelWorkingOrders=false` halts still allow engine-held exposure-increasing fires. |
| #905 | Confirmed | Unresolved closes publish no durable unresolved event and have no recovery-driven retry path. |
| #906 | Confirmed, narrow | Rule edge state remains memory-only; restored position guards mitigate only position-gated entries. |
| #907 | Confirmed | Replay constructs `RiskState` without the configured daily drawdown basis. |
| #908 | Confirmed | Backtest wiring omits five per-strategy risk rules used by live sessions. |
| #909 | Confirmed | Unknown-outcome resolve ladders still execute from OkHttp callbacks under the shared per-host dispatcher cap. |
| #910 | Confirmed | `stop()` interrupts before bounded control-queue and broker-callback draining. |
| #911 | Confirmed | DSL division called `BigDecimal.divide` without a zero guard, contrary to the expression contract. |
| #912 | Confirmed | The lexer consumed an unterminated block comment to EOF without an error. |
| #913 | Confirmed | Sequence completion pulses reset even when all consuming submissions are suppressed. |
| #914 | Confirmed | Latch firing bypasses the shared portfolio gate and its suppression accounting. |
| #915 | Confirmed | `mergeBracket` created an implicit bracket only when both default legs existed. |
| #916 | Confirmed | RESIZE plus bracket is accepted while protective quantities remain at entry size. |
| #917 | Partial | The two `Mt5BarFetcher` fail-closed branches lacked tests. `LiveSessionTest` already covered history seeding opening the warmup gate. |
| #918 | Confirmed | `Args` accepted only queried `--` tokens and silently ignored unknown and documented single-dash flags. |
| #919 | Confirmed batch | Restore/read failure, adoption protection, and persistence-health gaps remain. Each subitem requires its own Group B assertion. |
| #920 | Confirmed batch | Reconcile, suppressed RESIZE, repeated evaluator failure, stale-feed protection, and error-detail gaps remain. |
| #921 | Confirmed batch | The parity catalog omits live-only controls and overstates calendar/test evidence; replay also ignores `cancelWorkingOrders`. |
| #922 | Confirmed, partial | Live money-path and suppression/OTO tests remain absent. A report-bundle verifier exists only as unrelated uncommitted local work and is not in `dev`. |
| #923 | Confirmed batch | Compile-time DSL validation, undefined-value semantics, schedule/indicator documentation, and venue-reject re-arm design remain open. |
| #924 | Confirmed batch | Stale venue equity, null margin, and modify-reject protection gaps remain; OF BOOK basis is a declared design choice requiring explicit risk documentation. |
| #925 | Confirmed batch | Partial-close marker, persistence ordering, redeploy overlap, price tracker concurrency, and repeated ambiguity resolution remain open. |
| #926 | Partial | Public host/env/port/version/scaffold drift is real. `instruments.yaml` was already documented and replay now fails closed on missing metadata, but no starter file shipped. |
| #927 | Confirmed umbrella | It remains open until both groups, #854, release publication, CI, and a repeat audit are complete. |

## Closure rule

Do not close a batch issue because one bullet landed. Each issue must have its
acceptance bullets checked against tests, docs, or an explicit design decision.
Close #927 only after the release artifacts exist and the Critical/High paths
are re-run against the merged commit.
