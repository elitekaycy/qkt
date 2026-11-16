# Runtime set membership, slot universes and cross-member functions

Status: plan, 2026-11-16. Issue #1714. Design: `docs/superpowers/specs/2026-11-06-symbol-sets-and-universes-design.md`
(§3.2-§3.5, §6 steps 3-4) and, for families and slots, `docs/superpowers/specs/2026-11-06-event-contracts-design.md`
(§2.3, §3.6, §3.7, §3.9). Builds on #1708 (steps 1-2: picked lists, unions, filtered sets resolved once at start,
set caps) and #1694/#1695 (event families, slots, live slots).

Code references are to `origin/dev` at 8f611e209. `Q/` is `src/main/kotlin/com/qkt/`.

## 1. Where we start

- A set declaration is a `StreamDecl` under `SET_BROKER` whose canonical spec lives in `StreamDecl.symbol`
  (`Q/dsl/sets/SymbolSetSpec.kt`). `FOR EACH m IN <set>` parses to `ForEachSet` (`Q/dsl/ast/RuleAst.kt:40`).
- `SymbolSetExpansion.expand` (`Q/dsl/sets/SymbolSetExpansion.kt`) turns every resolvable set into member streams
  aliased `<set>.<symbol>` and every `ForEachSet` into one textual copy per member. Picked lists expand in the
  parser; filtered sets expand at backtest start (`Q/cli/SymbolSetResolution.kt`) and are refused live (#1712).
- Membership is therefore **fixed per run** everywhere. Nothing joins or leaves.
- Slots already follow a moving contract with a pure stepper shared by backtest and live
  (`Q/marketdata/source/SlotFollower.kt`): at each window start it picks from quotes strictly before the window and
  records the pick. That stepper is the model for runtime membership.
- `RANK_OF(a, b, c, ...)` exists over explicit peers (`Q/dsl/stdlib/FuncRegistry.kt:94`), strict on Undefined: one
  undefined argument makes the call Undefined.

Parallel work this plan must not collide with:

- **#1712** (filtered sets live, branch `feat/symbol-sets-live`): builds the pure resolver shared by backtest and
  live (catalog as of t, filter, rank, top n). Steps 4+ consume it and wait for it to land.
- **#1734** (live set caps): enforces `MaxSetOpenPositions`/`MaxSetPositionSize` in live sessions. Step 9 extends
  it to moving membership and waits for it.

## 2. Steps

Each step is one mergeable PR with its own tests, 100% diff coverage, `DslCorpusPinTest` and `GoldenBacktestTest`
unchanged. Steps 1-3 depend on nothing in flight; step 4 onward needs #1712.

| # | PR | Depends on | Ships |
|---|---|---|---|
| 1 | cross-member aggregates over a fixed set | - | `RANK_OF(expr, set)`, `SUM_OF`, `AVG_OF`, `MAX_OF`, `MIN_OF`, `COUNT_OF` inside `FOR EACH` over picked and start-resolved sets |
| 2 | membership stepper and journal record | - | pure `SetMembership` diff (joins, leaves, sticky while held), `SetMembershipChanged` event and its audit-journal line |
| 3 | runtime `FOR EACH` instances | 2 | a set rule compiled once as a template, instantiated on join and freed on release, per-member indicator state, warmup on join; driven in tests by a scripted membership |
| 4 | point-in-time membership in a backtest | 2, 3, #1712 | `DYNAMIC` filtered sets re-evaluated at each close of the set's `EVERY` window from the catalog as of that close |
| 5 | watched tier and as-of market fields | 4 | `close`, `volume`, `spread`, `open_interest` (window figures, never lifetime) in `WHERE` and `TOP n BY` |
| 6 | slot universes | 4, 5 | `VENUE:*@front.<sel>`, `VENUE:*@each.<sel>`, `<family>@front.*`, `@each.*`; members keyed by (family, event) |
| 7 | cross-member functions over moving sets, strike neighbours | 1, 4, 6 | aggregates over the active members of a dynamic set; `NEXT_STRIKE(expr)`, `PREV_STRIKE(expr)` |
| 8 | live runtime membership | 4-6, #1712 | gateway catalog polling, re-subscription on join/leave, restart, Insights events, `qkt bot universe --at` |
| 9 | set caps over moving membership | 4, #1734 | caps count every member that holds, sticky ones included, at order time |

### Step 1: cross-member aggregates over a fixed set

The sets of steps 1-2 never change during a run, so a cross-member function over one is a compile-time
cross-section: lowered while `FOR EACH` expands, into one `FuncCall` per instance whose arguments are the
expression written once per member. Nothing at runtime knows about sets.

- Parser: `SUM_OF`, `AVG_OF`, `MAX_OF`, `MIN_OF`, `COUNT_OF` parse to `FuncCall` (they are not in `FuncRegistry`,
  which would otherwise make them indicator calls); `RANK_OF` already does.
- Expansion (`SymbolSetExpansion`, through a new `ExprTransform` hook on `FuncCall`): in a `ForEachSet` template, a
  call `F(expr, s)` whose last argument names a set becomes `FuncCall("F.SET", [expr with m := member, ...])`, one
  argument per member of `s` in declaration order; `RANK_OF.SET` also takes this instance's own value first. The
  `.SET` names cannot be typed (`.` is not an identifier character, the rule member aliases already rely on), so
  user-written `RANK_OF(a, b, c)` keeps its strict meaning and fingerprint.
- Compiler: `.SET` calls compile with set semantics (§4.1). A set function left unlowered (outside `FOR EACH`, or
  over a name that is not a set) is a compile error naming the fix.
- A rule whose aggregate names a set that this pass cannot resolve yet (a picked loop ranking over a filtered set)
  stays a template until the pass that resolves both; the later pass finds the already-expanded loop set by its
  member aliases.

Tests: lowering unit tests (members, self value, union sets, a set other than the loop's, nesting refused,
expression without the loop variable refused, unresolved set deferred), compiler semantics per function including
Undefined members, parse of each name, and a golden: a picked-set rotation using `RANK_OF(..., fx)` trades
byte-identically to the same rules written with today's variadic `RANK_OF` over every member once all members are
warm (equivalence where strict and skip semantics agree), plus one case where a cold member is skipped.

### Step 2: membership stepper and journal record

`Q/instrument/universe/SetMembership.kt`: a pure class with one call per evaluation instant,

```kotlin
fun step(atMs: Long, ranked: List<String>, held: Set<String>): MembershipStep
```

where `ranked` is the resolver's top-n at `atMs` (step 4 supplies it) and `held` the members with a position or a
working order. It returns the new active set and the changes (§3), and keeps no other state than the previous
active set, so it can be rebuilt from scratch at any instant (restart). Plus the bus event
`SetMembershipChanged` (§3.1), recorded by `EngineAuditJournal` as a decision event (never shed) and encoded by
`encodeAuditLine`.

Tests: joins and leaves, sticky while held, release when flat, a held member that re-enters the ranking (a
`stay`, not a second join), rank ties, deterministic order of changes, the journal line round-trip.

### Step 3: runtime `FOR EACH` instances

A `ForEachSet` over a dynamic set is not expanded. The compiler builds a `SetRuleTemplate` once and a
`SetInstances` registry that, on a join:

1. registers the member stream on `CandleHub` (mid-run registration is already allowed,
   `Q/dsl/compile/HubSlots.kt`);
2. creates the member's indicator bindings, keyed by `(alias, member)` instead of alias alone;
3. loads warmup (§4.3) through the warmup path declared streams use;
4. instantiates the rule with the loop variable bound to the member alias (`<set>.<symbol>`, the alias steps 1-2
   already give members, so trades and reports read the same as a fixed set's).

On release it unregisters the stream and frees the instance and its state. In this step membership is driven by a
scripted `SetMembership` input in tests, so the engine half is proven before any catalog is involved.

Tests: a scripted join mid-run trades only after warmup, a release drops the instance and its indicators, a rejoin
starts from a fresh warmup (no stale state), a fixed set still expands exactly as before (no instance machinery on
the picked path, proved by `PickedSetEquivalenceTest` unchanged).

### Step 4: point-in-time membership in a backtest

The set feed evaluates `members(T)` at each close `T` of the set's `EVERY` window: catalog rows alive at `T`
(#1712's resolver over the point-in-time catalog, §4.4), filtered and ranked on data as of `T`, top n; then
`SetMembership.step(T, ranked, held(T))`. Joins and leaves drive step 3's instances.

Which sets move: slot universes (step 6) and sets reading as-of fields (step 5) always do, since a fixed reading
would be meaningless. A catalog-only filtered set keeps step 2's resolve-once meaning unless it says `DYNAMIC`
after `TOP n BY f`, a contextual word inside the set spec (no new `TokenKind`, canonical text in `StreamDecl.symbol`,
no fingerprint change). This keeps every filtered strategy written since #1708 backtesting exactly as it does
today (§7, open question 1).

Golden: a filtered event universe over a stored event catalog with a join, a leave, and a sticky member that exits
after it left the ranking.

### Step 5: watched tier and as-of market fields

Every catalog row that passes the catalog part of the filter is **watched**: a light record per row (last close,
window volume, last bid/ask, open interest) fed from the same stored bars (backtest) or the family/roots quote
subscription (live). Only the top n after the data part are active. Fields read the watched record of the last
window that closed at or before `T`: `close`, `volume` (that window's volume), `spread`, `open_interest`.
Lifetime figures are not exposed. A row with no record yet reads null, so it fails comparisons and ranks last.

### Step 6: slot universes

`VENUE:*@<event>.<strike> WHERE ...`: the filter runs over **families**; each member is the family's slot
(`KALSHI:KXINXU@front.p70`), fed by the existing `SlotFollower`. With `@each`, every open event of each matching
family is a member keyed by (family, event); a member leaves when its event closes. `<family>@front.*` and
`@each.*` make every contract of the selected event(s) a member (ladders, buckets). Event settlement (§5.2)
releases members.

### Step 7: cross-member functions over moving sets, strike neighbours

Aggregates over a dynamic set read the set's **current members** (top n at the last evaluation), not sticky
leftovers, through a per-window cross-section: each member's value is computed once per window close and shared,
so the cost is O(n) per window instead of step 1's O(n²) lowering. `NEXT_STRIKE(expr)` / `PREV_STRIKE(expr)`
evaluate `expr` with the loop variable bound to the neighbouring contract of the same event (by strike), and are
Undefined at the end of the ladder or when that contract is not an active member (§4.5).

### Step 8: live runtime membership

The same set feed live: catalog from the gateway (`/v1/instruments` with change polling) through #1712's cached
client, watched quotes from the family/roots subscription, re-subscription on join/leave (`ContinuousLiveFeed`
precedent), journal lines as in backtest, `strategy.started` listing set declarations and Insights receiving
`SetMembershipChanged`. Restart: §5.3. `qkt bot universe "<spec>" --at <instant>` prints members as of an instant
from the same code.

### Step 9: set caps over moving membership

`MaxSetOpenPositions` and `MaxSetPositionSize` (#1708, live in #1734) read a set's members from aliases fixed at
compile time (`SetMemberAlias.membersOf`). For dynamic sets they ask the set feed's membership instead, and count
every instance that holds (sticky members included), so a cap is never escaped by a member leaving the ranking.

## 3. Membership rules

`members(T)` = the resolver's top n at `T`. `active(T)` = `members(T) ∪ held(T)`, where `held(T)` is the previous
active members with a non-zero position or a working order at `T`.

| Change | When | Effect |
|---|---|---|
| `join` | in `members(T)`, not active before | instance created, warmup loaded, rules evaluate from the next close |
| `leave` | active before, not in `members(T)`, flat, no working order | instance released |
| `stick` | active before, not in `members(T)`, but held | instance stays; opening orders refused (reduce/close only) |
| `release` | was sticky, now flat with no working order | instance released at that evaluation |
| `stay` | sticky member back in `members(T)` | normal member again; no new warmup |

- **Sticky while held** keeps exit rules running on a member that left the ranking. A sticky instance runs all its
  rules, but an order that would open or add on it is refused at the instance (reason `not-a-member`); reducing
  and closing orders pass. A sticky member does not count toward `TOP n` and is not part of cross-member
  aggregates (step 7).
- Evaluation instants are the closes of the set's `EVERY` window, epoch aligned, exactly as slots pick.
- Order of changes within one instant: leaves and releases first (by symbol), then joins (by rank), so caps
  (step 9) see the freed slots before the new members.
- Ties in the ranking break by symbol (`UniverseResolver`), so equal inputs always give equal members.

### 3.1 The membership journal

One bus event per change, `SetMembershipChanged`, recorded by the engine audit journal (decision event: never
shed) and forwarded to Insights. Audit line:

```json
{"type":"SetMembershipChanged","seq":4182,"ts":1763301600000,"strategy":"kalshi_hourly_carry",
 "set":"hourly","member":"KALSHI:KXINXU@front.p80","key":"KXINXU","change":"join","rank":3,
 "by":"open_interest","value":"18250","windowEndMs":1763301600000,"reason":"rank"}
```

| Field | Meaning |
|---|---|
| `set` | the set alias |
| `member` | the qkt symbol of the member (a slot symbol for slot universes, the contract for `@front.*`) |
| `key` | membership key: the symbol, or `family/event` for `@each` members |
| `change` | `join`, `leave`, `stick`, `release`, `stay`, `restore` (restart, §5.3) |
| `rank`, `by`, `value` | rank and ranking value at `windowEndMs`; null for `leave`/`release` |
| `reason` | `rank` (fell out of top n or entered it), `filter` (stopped passing), `delisted`, `expired`, `settled`, `flat` |

The journal is evidence, never input: membership is never read back from it. A live run's journal and a backtest
over the same window and catalog are compared line for line (`qkt golden`-style), which is how parity is checked.

## 4. Semantics

### 4.1 Cross-member functions (DSL surface)

```qkt
FOR EACH m IN fx DO
  WHEN RANK_OF(m.close / m.close[20] - 1, fx) <= 2 AND POSITION.m = 0
  THEN BUY m SIZING 1 PCT RISK
```

| Function | Value at this member |
|---|---|
| `RANK_OF(expr, set)` | 1 + the number of members whose `expr` is strictly greater than this member's (1 = highest, ties share) |
| `SUM_OF(expr, set)` | sum of `expr` over members |
| `AVG_OF(expr, set)` | mean of `expr` over members |
| `MAX_OF(expr, set)`, `MIN_OF(expr, set)` | largest / smallest `expr` over members |
| `COUNT_OF(test, set)` | number of members where `test` is true |

- Grammar fit: ordinary function calls; the set is the last argument, by name. No new token, no new AST node or
  property (lowered to `FuncCall`).
- Inside the call, the loop variable ranges over the named set's members, like a lambda parameter: `expr` must
  mention it (`SUM_OF(gold.close, fx)` is refused, it would be n × one value). The named set may differ from the
  loop's set; `RANK_OF` then ranks this member's value among that set's values.
- **Undefined members are left out** (warmup, a cold join, a member with no bar yet), as SQL aggregates skip null:
  `SUM_OF`/`AVG_OF`/`MAX_OF`/`MIN_OF` are Undefined only when no member is defined; `RANK_OF` is Undefined when
  this member's own value is; `COUNT_OF` counts members whose test is true (Undefined counts as not true) and is 0
  when none. Strict propagation would let one cold Kalshi listing blank the whole set for its lookback.
- Not nested: an aggregate inside another aggregate's `expr` is refused (the inner loop variable would be bound
  twice). Outside `FOR EACH` a set function is refused ("use it inside FOR EACH m IN <set>").
- `RANK_OF` with peers listed by hand keeps its existing strict meaning; only the set form is new.
- Fixed sets (step 1) see the same cross-section as hand-written variadic calls: each member's value at the
  moment its rule evaluates. Dynamic sets (step 7) compute the cross-section once per window close, so every member
  sees the identical one.

### 4.2 Rule instances and aliases

A member's alias is `<set>.<symbol>` in every step, fixed or dynamic, so `POSITION.m`, reports and trade tapes look
the same whichever path produced the member.

### 4.3 Warmup on join

A member that joins at the close `T` loads its warmup bars `[T - warmup × tf, T)` (the set declaration's
`WARMUP`, else the indicators' own requirement) from the store in a backtest and from gateway bars live, through
the path declared streams use. Its rules first evaluate at the close `T + tf`, never at `T` itself: the join was
decided on data as of `T`, and live needs time to fetch warmup, so evaluating at `T` would differ between modes.
Short history (a fresh event contract) is not padded: long lookbacks stay Undefined until enough bars exist,
exactly as a declared stream with short history. A rejoin after release warms up from scratch.

### 4.4 Point-in-time catalogs in a backtest

| Catalog | Alive at T | Note |
|---|---|---|
| event catalog (`contracts/<VENUE>/<FAMILY>.events.json`) | `openAt ≤ T < expiry` (and `close_at` for trading) | exact |
| futures / options contract catalogs, chain days | listing and expiry dates | exact |
| `instruments.yaml` (FX, CFDs, crypto spot) | always | static; the report notes the set used a static catalog |
| equities | none yet | refused unless `--survivorship-accepted`, recorded in the report |

The resolver is handed only rows alive at `T` and data as of `T`; a row's settlement value and lifetime figures are
never visible to it.

### 4.5 Strike neighbours

`NEXT_STRIKE(expr)` is `expr` with the loop variable bound to the contract of the same event whose strike is the
next higher (by `floor_strike`, then `cap_strike`), and `PREV_STRIKE` the next lower. This deviates from the
design's `next_strike(m)` spelling, which would need a function call to yield a stream (`next_strike(m).close`, new
grammar); the expression form reuses the lambda binding of §4.1 and reads `NEXT_STRIKE(m.close) - m.close`. It is
Undefined when the neighbour is not an active member, at the end of the ladder, or outside an event set.

## 5. Edge cases

1. **Member delisted while held.** It leaves `members(T)` (reason `delisted`) but stays sticky; only reducing
   orders pass (and the venue's `close_only` trade mode already refuses opens). It is released when flat. If the
   venue removes the instrument entirely, the position is handled by the existing reconcile path and the instance
   is released once the position is gone.
2. **Settlement of an event member.** `ContractSettled` books the payout and flattens; the member leaves with
   reason `settled` at the next evaluation. No order is sent to a contract after its `close_at` (the event
   kind-scoped trade-mode rule). An `@each` member keyed by (family, event) leaves when the event closes even if
   its slot could still pick.
3. **Restart mid-run.** Membership is recomputed, not restored: `members(now)` from the catalog and as-of data,
   plus every member `StartupReconcile` finds a position or working order on (sticky). Each gets a `restore`
   journal line and a fresh warmup; rules evaluate from the next close. Because membership is a pure function of
   catalog, data and held state, a restart lands on the same members an uninterrupted run would (held state
   included, since positions are reconciled from the venue).
4. **Caps and joins.** Forty members joining on one close and firing together open at most the set cap
   (`MaxSetOpenPositions`, in-flight reservation, the burst-race fix). Leaves are applied before joins (§3) so a
   member leaving frees its cap slot first. `TOP n` bounds members; instances are bounded by `n` plus held members,
   and held members are bounded by the set cap when one is configured. `sets.max_members` (500) still bounds `n`.
5. **A member with no data at T.** Fields read null; it fails comparisons, ranks last, and is not a member unless
   `TOP n` reaches past every member with data (ties by symbol).
6. **A member that joins and leaves on consecutive closes.** It never evaluated (its first evaluation is the close
   after joining), so it opened nothing and is released without a trade; both lines are journalled.
7. **Same member in two sets of one strategy (a union, or overlapping filters).** One stream and one alias per
   set; each set's instance is independent, as two declared streams on one symbol are today.
8. **Window misalignment.** A set's evaluation instant is its own `EVERY` close; members of a `1m` set and rules on
   a `15m` stream evaluate on their own clocks. Unions require one `EVERY` (#1708).

## 6. Determinism and parity

Membership is a pure function, the same code in both modes:

```
members(T) = top_n(rank_by(filter(catalog alive at T, data as of T)))
active(T)  = members(T) ∪ held(T)
```

- **Same inputs.** A backtest reads the point-in-time catalog and stored bars; live reads the gateway catalog and
  the same bars as they arrive. Data "as of T" means windows closed at or before T in both, as slots already do.
- **Same code.** `SetMembership.step` and the resolver are mode-free (the `SlotFollower` pattern); only their
  inputs differ.
- **No hidden state.** The stepper keeps only the previous active set; held state comes from positions and
  orders, which both modes already reconcile. Nothing is recorded to reproduce a run, so a backtest is the
  replay.
- **Known divergence** (to be listed in `docs/parity/backtest-vs-live.md` when step 8 lands): live sees a catalog
  change at its next poll, so a listing that appears between polls joins one evaluation later than in the
  backtest; the journal's `windowEndMs` makes that visible.
- **Proof.** Each step adds a golden; step 8 adds a parity test that drives the live set feed and a backtest over
  the same recorded catalog and quotes and compares journal lines and trades.

## 7. Open questions

1. `DYNAMIC` as the opt-in for catalog-only filtered sets (step 4) versus making every filtered set dynamic. The
   opt-in keeps #1708's resolve-once behaviour for strategies already written; making all of them dynamic would
   change the meaning of `dte`/`minutes_left` filters in existing ones.
2. Whether a sticky member should also refuse adds that `RESIZE` makes towards a larger target (proposed: yes, same
   as any opening order).
3. `NEXT_STRIKE(expr)` instead of the design's `next_strike(m)` (§4.5).
