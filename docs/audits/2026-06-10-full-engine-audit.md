# qkt Full Engine Audit — 2026-06-10

> Seven-pillar audit at v0.38.0 (`11c9349e`, origin/dev): architecture, money math,
> backtest=live parity, test quality, production readiness, DSL semantics, and industry
> research (FIA / SEC 15c3-5 / MiFID II RTS 6 / Knight Capital). Seven parallel auditor
> agents, findings adversarially re-verified by the orchestrator against source.
>
> Markers: **[V]** = orchestrator read the cited code and confirmed the finding.
> **[A]** = accepted on auditor evidence (file:line cited, spot-checked or dual-agent agreement).
>
> Context for severity: the operator intends to risk personal savings on DSL strategies.
> Losses from a strategy's edge are tolerable; losses from an engine fault are not.
> "CRITICAL" below means: an engine fault that loses or misstates real money silently.

## Verdict

**Not ready for real money.** Ready to *become* ready: the deterministic core (BigDecimal
money, event-driven replay, idempotent order IDs, single-consumer engine queue) is sound,
and nothing found is architecturally fatal — but 13 verified critical defects currently
stand between this engine and a savings account, several of them silent-wrong-number bugs
of exactly the class the audit was commissioned to find.

The five pillar questions:

1. **Sound, scalable design without silent leaks?** Single-strategy: yes, post-#281.
   Multi-strategy: no — the daemon-shared CandleHub (#360) breaks the single-consumer
   invariant, and one strategy exception silently kills a session's engine thread (#361).
2. **Money math correct throughout?** No. Engine-fired exits book to no strategy (#356),
   venue-side closes are priced from fiction (#357), three sizing modes are off by
   contractSize — 100x to 100,000x (#358), and live PnL is cost-blind (#392).
3. **Backtest = live?** Not yet. Backtest has no halt rules (#362), triggers on mid (#382),
   warmup differs (#383), GTD differs (#368), and the divergence catalog under-reports (#389).
4. **Tested well?** Strong where tests exist (real types, hand-computed constants, no
   mocks). The risk layer's most important behavior — halted strategy's orders rejected —
   is untested behind a comment claiming otherwise (#386); broker-failure paths untested (#359).
5. **Production ready?** No — gateway outage reads as "all closed" (#359), halts don't
   survive restart (#380), feed death is undetectable (#381), and the industry checklist
   shows the standard pre-trade control layer (caps, collar, kill switch, runaway breaker)
   is missing (#393-#396).

**Go/no-go:** fix the 13 P0s, then run the staged ramp (#401). The latch-stack strategy
currently live on qkt-prod is affected by #365 (its 4-hour time exit fires in 14.4 seconds)
and should be considered not-as-designed until that lands.

## Critical findings (P0)

| ID | Finding | Where | Issue |
|---|---|---|---|
| MATH-1 [V] | Engine-fired exits (trailing conversions, scale-outs, time exits) omit `strategyId`; fills short-circuit `StrategyPositionTracker.applyFill` — per-strategy positions/PnL/risk silently diverge | `OrderManager.fireFallbackTrigger` ~1695-1760 | [#356](https://github.com/elitekaycy/qkt/issues/356) |
| MATH-2 [V] | Venue-side closes booked at `lastPrice(qktSymbol) ?: p.priceOpen` — engine's last tick, or a fabricated break-even; deal commission/swap never captured | `MT5PositionPoller` ~142 | [#357](https://github.com/elitekaycy/qkt/issues/357) |
| MATH-3 / DSL-5 [V] | `SizeNotional`/`SizePctEquity`/`SizePctBalance` omit contractSize → 100x (XAUUSD) to 100,000x (FX) oversize; risk-based paths in the same file are correct | `SizingCompiler` | [#358](https://github.com/elitekaycy/qkt/issues/358) |
| PROD-2 / TEST-1/2 [V] | Gateway outage → `getPositions`/`getPendingOrders` return `emptyList` → pollers synthesize close fills for every position and cancel every pending; untested | `MT5Client:125,136`; `MT5PositionPoller:121-122`; `MT5PendingOrderPoller:80` | [#359](https://github.com/elitekaycy/qkt/issues/359) |
| ARCH-1 [V] | One `sharedHub` for all sessions: engine threads execute each other's rule evaluation on plain HashMaps — double aggregation, duplicate fires, CME | `DaemonCommand:59-61,180` | [#360](https://github.com/elitekaycy/qkt/issues/360) |
| ARCH-2/5, PROD-6, DSL-10 [V] | Engine loop arms FeedTick/BusEvent unwrapped — any strategy exception kills the thread silently; Heartbeat/Flatten failures swallowed in bare `runCatching` | `LiveSession` ~633-642 | [#361](https://github.com/elitekaycy/qkt/issues/361) |
| PAR-1 [V] | Backtest constructs `RiskEngine(rules, emptyList(), ...)` — zero halt rules; live wires MaxDailyLoss ($1000 default) + DD halts | `ReplayEngine:145` | [#362](https://github.com/elitekaycy/qkt/issues/362) |
| PAR-2 / MATH-5 [V] | `setStartingBalance` only called via PortfolioDeployer's map — standalone deploys have `ACCOUNT.equity` = 0; %-of-equity sizing broken (risk rules do get the balance via StrategyHandle) | `LiveSession:449`; `PortfolioDeployer:232`; `StrategyHandle:91,139,181` | [#363](https://github.com/elitekaycy/qkt/issues/363) |
| DSL-1 [V] | Update-then-fire + RollingHigh includes current update → `close > highest(close,N)` structurally never true; docs claim exclusion | `AstCompiler:342-343`; `indicators.md:131` | [#364](https://github.com/elitekaycy/qkt/issues/364) |
| DSL-2 [V] | `holding_duration` is milliseconds; docs + both shipped examples treat it as seconds — time exits fire 1000x early. **latch-stack is LIVE on qkt-prod with `> 14400`** | `ExprCompiler:287-288`; both example .qkt files | [#365](https://github.com/elitekaycy/qkt/issues/365) |
| DSL-3 [V] | Rules re-fire every bar while condition holds — no edge memory; `conditions.md:31` promises edge-driven actions | `CompiledRule.fire()` | [#366](https://github.com/elitekaycy/qkt/issues/366) |
| DSL-4 [V] | `parseStrategy` never checks EOF — everything after the first unrecognized top-level token silently discarded; half a strategy deploys "ok" | `Parser:268-330` | [#367](https://github.com/elitekaycy/qkt/issues/367) |
| PAR-3 [V] | GTD end-to-end still inert: gateway `order.py` hardcodes `ORDER_TIME_GTC`, `supportsNativeGtd = true` disables the engine sweep. #308 fixed the wire only | `MT5Broker:65`; mt5-gateway | [#368](https://github.com/elitekaycy/qkt/issues/368) |

Plus three P0-labeled enhancements that gate go-live: pre-trade caps + collar
([#393](https://github.com/elitekaycy/qkt/issues/393)), true kill switch
([#394](https://github.com/elitekaycy/qkt/issues/394)), and the ramp policy
([#401](https://github.com/elitekaycy/qkt/issues/401)).

## High findings (P1)

| ID | Finding | Issue |
|---|---|---|
| MATH-4 [V] | `EquityTracker` peak starts at ZERO on a 0-based PnL series — trailing DD is fraction-of-peak-profit, not equity (affects #348 rules) | [#369](https://github.com/elitekaycy/qkt/issues/369) |
| MATH-6 [V] | `LegBook.netView()` zeroes net-flat hedged pairs — locked-in straddle loss invisible to unrealized/equity/halts | [#370](https://github.com/elitekaycy/qkt/issues/370) |
| MATH-7/11 [V] | Backtest equity curve is 0-based → maxDD/Sharpe/daily-DD distorted; Calmar divides dollars by a fraction | [#371](https://github.com/elitekaycy/qkt/issues/371) |
| MATH-8 / DSL-9 [V] | `PCT RISK` divides by 100; `% OF EQUITY` consumes a raw fraction — same surface number, 100x apart | [#372](https://github.com/elitekaycy/qkt/issues/372) |
| MATH-10 [A] | No quote→account currency conversion; registry ships JPY/CHF/CAD pairs — JPY PnL booked as USD (~150x) | [#373](https://github.com/elitekaycy/qkt/issues/373) |
| ARCH-3 [A] | OrderFilled chain: venue mutated (sibling cancelled, children sent) before books written; mid-chain exception → permanent venue/book divergence | [#374](https://github.com/elitekaycy/qkt/issues/374) |
| ARCH-4 [V] | Unbounded `LinkedBlockingQueue<Inbound>` — stalled consumer OOMs the whole daemon | [#375](https://github.com/elitekaycy/qkt/issues/375) |
| ARCH-6 [V] | Boot reconcile: `getOpenPositions` failure → `emptyMap()` → session trades believing it is flat; CompositeBroker leaves swallow per-leaf | [#376](https://github.com/elitekaycy/qkt/issues/376) |
| ARCH-9 [V] | StackOrchestrator subscribes before the fill-applying handler — stack children risk-approved against pre-fill book | [#377](https://github.com/elitekaycy/qkt/issues/377) |
| PROD-3/13 [V] | IO error → synthetic retcode -1 → "rejected" (maybe filled); `isOrderSuccessful = retcode == 10009` only — 10008/10010 treated as rejects | [#378](https://github.com/elitekaycy/qkt/issues/378) |
| PROD-4 / TEST-6 [V] | `Tick` has zero validation; zero/negative/crossed quotes reach indicators, PnL marks, triggers; untested | [#379](https://github.com/elitekaycy/qkt/issues/379) |
| PROD-5 [V] | Halt state + daily PnL not persisted — restart un-halts and resets the daily budget | [#380](https://github.com/elitekaycy/qkt/issues/380) |
| PROD-7 [V] | `onDisconnect` only on thread exit; poll loop never exits — reconnect budget unreachable, stale feed undetectable | [#381](https://github.com/elitekaycy/qkt/issues/381) |
| PAR-4 [V] | Triggers evaluate mid in PaperBroker, MT5_SIM, and live engine-held path; venue uses bid/ask — systematic half-spread optimism | [#382](https://github.com/elitekaycy/qkt/issues/382) |
| PAR-6 / DSL-7 [V] | Bind (gate credit from cold hub, `LiveSession:529`) before seed (`:590`) — dead warmup window after every deploy; backtest silently consumes the window | [#383](https://github.com/elitekaycy/qkt/issues/383) |
| DSL-6 [V] | All `buildRequest` sites use `ec.candle.symbol` — cross-stream/SCHEDULE actions order the wrong instrument | [#384](https://github.com/elitekaycy/qkt/issues/384) |
| DSL-8 [V] | `orderType ?: Market` at parse time — `DEFAULTS ORDER_TYPE` dead (family of #247) | [#385](https://github.com/elitekaycy/qkt/issues/385) |
| MATH-9/12/13, PAR-5 [A] | Live PnL/halts cost-blind (no commission/swap/execFee); MaxDailyLoss realized-only (open bleed never trips it); contractSize `?: ONE` silent fallback | [#392](https://github.com/elitekaycy/qkt/issues/392) |
| TEST-3/4/5/7/8 [V] | Halt rejection untested behind a false comment; exact-threshold, cost-blind-halt, determinism-replay, DST all undefined-by-test | [#386](https://github.com/elitekaycy/qkt/issues/386) |

## Medium/low tails (grouped, P2)

- **OrderManager/candle lifecycle** — terminal states not sinks (ARCH-7), satellite-state
  growth vs #255's GC invariant (ARCH-8), `persistAll` O(N)+swallowed failures (ARCH-10),
  next-tick-only candle close (ARCH-11): [#387](https://github.com/elitekaycy/qkt/issues/387)
- **Ops hardening** — blind-broker init window (PROD-11), pre-bind event race (PROD-12),
  no fsync / async loss window (PROD-16), trust boundary + NTP undocumented (PROD-18/19):
  [#388](https://github.com/elitekaycy/qkt/issues/388)
- **Parity tail + catalog gaps** — bar-synthesis short-side optimism vs KDoc (PAR-7), tick
  sampling (PAR-8), SCHEDULE timing (PAR-10), calendar defaults crypto-vs-fx (PAR-12),
  bid/ask Undefined on bar data (PAR-15), catalog missing rows (PAR-14):
  [#389](https://github.com/elitekaycy/qkt/issues/389)
- **DSL semantics tail** — bracket anchored to signal-bar mid-close (DSL-12), SCHEDULE DST
  (DSL-13), warmup ignores action-side indicators (DSL-14), STACK_AT dead after restart
  (DSL-15/PROD-17), `TRUE OR Undefined` swallow (DSL-16), CROSSES-in-CASE staleness
  (DSL-17), legacy onCandle cross-feed (DSL-19): [#390](https://github.com/elitekaycy/qkt/issues/390)
- **Doc drift** — every doc claim found false, incl. the two semantics promises above:
  [#391](https://github.com/elitekaycy/qkt/issues/391)

## Industry research (FIA, SEC 15c3-5, RTS 6, Knight, CME, RTS 25)

54-point checklist built from primary sources (FIA Guide 2015 full text, SEC market-access
rule, MiFID II RTS 6/25, Knight Capital SEC order, CME Globex controls, Bybit DCP, MT5
stop-out mechanics, Bailey & López de Prado overfitting statistics). Scores against qkt:
roughly **14 have / 19 partial / 12 missing / 9 unknown**. qkt is strong on the parts most
hobby engines skip (walk-forward, parity catalog, state recovery, deterministic replay,
deploy verification) and missing the standard institutional pre-trade control layer.

Top gaps → issues: per-order qty/notional caps + price collar
([#393](https://github.com/elitekaycy/qkt/issues/393)), stale-quote/outlier/crossed-book
gate ([#395](https://github.com/elitekaycy/qkt/issues/395)), true kill switch
([#394](https://github.com/elitekaycy/qkt/issues/394)), runaway circuit breaker
([#396](https://github.com/elitekaycy/qkt/issues/396)), unknown-order-state protocol
(folded into [#378](https://github.com/elitekaycy/qkt/issues/378)), external deadman
watchdog ([#397](https://github.com/elitekaycy/qkt/issues/397)), margin headroom +
weekend policy ([#398](https://github.com/elitekaycy/qkt/issues/398)), measured-usage
deploys ([#399](https://github.com/elitekaycy/qkt/issues/399)), immutable trade journal +
daily reconcile ([#400](https://github.com/elitekaycy/qkt/issues/400)).

### Burn-in recommendation

Staged ramp, codified in [#401](https://github.com/elitekaycy/qkt/issues/401):
30+ day demo burn-in with explicit exit criteria (zero unexplained order states, zero
reconciliation deltas, kill switch exercised, demo equity inside the cost-calibrated
backtest band) → 2-4 weeks micro-real at 0.01 lots → 25/50/100% ramp, ≥1 month per step,
with a reset rule on any engine-state incident. P0 fixes land before first real money;
#398/#399/#400 before scaling.

## Verified solid (no issue needed)

- Money representation: BigDecimal DECIMAL64, scale 8, HALF_EVEN throughout; no float on
  any money path.
- Position/leg arithmetic test suites (PositionTracker/LegBook/StrategyPositionTracker):
  hand-computed constants, no tautologies. Signed-quantity handling correct.
- Candle construction parity: same classes, same epoch anchoring, same tick-driven close
  in both modes — identical given identical ticks.
- Order idempotency plumbing (clientOrderId/orderLinkId on Bybit; magic+comment on MT5).
- Intrabar SL+TP resolution under tick replay (single price can't trip both).
- CompositeBroker delegation (PROD-1/#283) and pending-order reload (PROD-8) — fixed in
  the 125 commits between audit start and v0.38.0; re-verified fixed, dropped.

## Dedupe notes

- DSL-11 (VWAP volume-less feed) ≈ existing [#301](https://github.com/elitekaycy/qkt/issues/301) — not refiled.
- Commission/swap modeling: backtest side shipped (v0.35.0, #335 closed, #338 documented
  conventions); the **live** cost-blindness is new and tracked in #392.
- PROD-14 (Bybit recovery `limit=50`) not filed: `nextPageCursor` is consumed at
  `BybitLinearStateRecovery:114`; pagination appears handled. Re-check only if recovery
  misses orders in practice.
- #352 (broker-equity sizing) remains the deeper fix behind #363; #351 (portfolio DD)
  unaffected by these findings.

## Method

Seven auditor agents (one per pillar + research) ran against origin/dev with file:line
evidence required for every claim. The orchestrator then re-read every cited site for the
CRITICAL/HIGH set, attempted to refute each finding, corrected two stale ones (PROD-1,
PROD-8 — fixed upstream mid-audit), resolved one agent conflict (warmup seeding order —
the DSL agent was right), and downgraded/folded where evidence was weaker than the claim.
Issues filed: [#356](https://github.com/elitekaycy/qkt/issues/356)–[#401](https://github.com/elitekaycy/qkt/issues/401), 46 total.
