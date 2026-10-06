# Attestation cases

Everything the live attestation proves is a directory under `cases/<lane>/<case-id>/`.
A runner never names a case: it reads this tree. To prove something new, add a directory.

```
attestation/
  lanes.yaml              what each lane is for, and what may run at the same time
  gaps.yaml               capabilities knowingly not proven yet, each with a reason
  cases/<lane>/<id>/
    case.yaml             what the case proves, what it needs, how long it may take
    strategy.qkt          the strategy (order and shadow lanes) - optional for daemon/engine cases
    instruments.yaml      derivatives lane: the `futures:`/`options:` roots the strategy trades
  lib/validate.py         schema check + coverage gate; run by CI
```

## Adding a case

1. Pick the lane in `lanes.yaml` whose purpose matches. If none does, add a lane.
2. `mkdir cases/<lane>/<id>` - the id is lowercase, `a-z0-9-`, unique across lanes.
3. Write `case.yaml` (fields below). The `why` field is the point: name the failure this
   case would catch. "Covers EMA" is not a reason; "a 1h stream fed by warmup plus a live
   boundary closes its first bar one period late" is.
4. Write `strategy.qkt` if the lane runs one. Entries must not depend on market direction;
   a case that needs a loss, a halt or a position restores that state.
5. `python3 attestation/lib/validate.py` - it must pass, and the capability you added must
   leave `gaps.yaml` if it was listed there.

## case.yaml

| Field | Meaning |
|---|---|
| `id` | Same as the directory name |
| `title` | One line |
| `why` | The specific failure this case exists to catch |
| `status` | `ready` (runs in the attestation), `planned` (designed, not yet runnable) or `scheduled` (a venue-gateway lane's case run at a set time outside the catalog, e.g. across an expiry; its budget may reach four hours) |
| `symbols` | Venue-neutral symbols, e.g. `[EURUSD, XAUUSD]` |
| `timeframes` | Every timeframe a stream uses, e.g. `[1m, 15m, 1h]` |
| `warmup_bars` | Per-timeframe warmup depth, e.g. `{1m: 6, 15m: 20}` |
| `proves` | Capability names from `oracle-evidence.json`, plus free-form `behaviour:` tags |
| `budget_seconds` | Hard deadline; omitted means the lane default. Never above 600 (14400 for a `scheduled` case) |
| `steps` | Daemon/engine lanes: ordered operator commands with the exit code and state expected after each |
| `assertions` | Named checks the runner applies, e.g. `trace-parity`, `journal-byte-exact`, `flat-by-magic` |
| `fills` | Derivatives lane: the fills the strategy makes before it is judged |
| `replay` | Derivatives lane: `bars` (replay the gateway's own bars), `chain` (replay the chain the account recorded) or `none` |
| `dated_from_root` | Derivatives lane: trade the root's dated contract listed 7 to 45 days from expiry in place of the case's symbol |
| `expiring_option` | Derivatives lane: trade the option about to expire in place of the case's symbol (below) |
| `settles` | Derivatives lane: the expiry settlements qkt must book before the case is judged |
| `drills` | Derivatives lane: failures fired while the case holds, each `{kind, at_s}` timed from the first fill (below) |

## The derivatives lane

Futures, perpetuals and options run on a VGP gateway account (the Deribit testnet), not the MT5 demo, so
the lane is opt-in: `run-attestation-catalog.sh --lanes ...,derivatives --deriv-gateway-url URL
--deriv-expected-login LOGIN` with the trader token in `QKT_DERIV_GATEWAY_KEY`, or `deriv_gateway_url` and
`deriv_expected_login` in the attestation profile. One netting account cannot tell two cases' positions
apart, so its cases run one after another on a flat account (`run-derivatives-lane-case.py`). Each proves
that the account ends flat, that qkt's realized PnL equals the venue's deals net with fees to the last
digit, and that replaying the venue's bars (or the recorded chain) makes the same fills (or opens the same
legs).

### The bybit lane

`cases/bybit/` holds the derivatives lane's perpetual cases on a second venue: Bybit linear perpetuals
(`BYBIT_LINEAR:BTCUSDT`) on a Bybit testnet account behind a gateway running the qkt-venue-gateway's Bybit
adapter, reached as a `type: gateway` broker named `bybit_linear`; qkt has no Bybit code of its own. The
cases, assertions and drills are the derivatives lane's, run by the same `run-derivatives-lane-case.py`; the
lane is opt-in with `run-attestation-catalog.sh --lanes ...,bybit --bybit-gateway-url URL
--bybit-expected-login LOGIN` (the gateway's `account_login`) and the trader token in `QKT_BYBIT_GATEWAY_KEY`
(the guardian token in `QKT_BYBIT_GUARDIAN_KEY` for the kill-switch drill), or `bybit_gateway_url` and
`bybit_expected_login` in the attestation profile. It runs beside the derivatives lane: the accounts are
separate. Funding, which Bybit charges at 00:00, 08:00 and 16:00 UTC, is proven by the soak in
`scripts/live-validation/funding-soak-bybit`, run before one of those times.

### Holding through an expiry

A settlement is not a deal. When a contract the account holds expires, the venue closes it at the settlement
price (an option's intrinsic value from the delivery price, a future's delivery price) and the gateway reports
it on `/v1/settlements` and the event stream, never on `/v1/deals`; qkt books it as a fill that is no order's
(id `settle:<symbol>:<strategy>`, exit reason `EXPIRY`, `ContractSettlement`), which the strategy's order
journal records. So `deals-net-equals-realized` adds, for every settlement in the run of a contract the
strategy's deals left it holding, the holding x the price x the contract size, less the settlement's costs
(Deribit's delivery fee), to the deals net: a long 0.01 call bought at 3060 (fee 0.25) and settled at 2120.5
(fee 0.12768) is venue net `-30.60 - 0.25 + 21.205 - 0.12768 = -9.77268`, which qkt's realized must equal. A
contract's size is read before it expires, because the venue delists it then. A case that declares `settles: N` is
done only once qkt has booked N settlements and the account is flat, and it also asserts:

- `settlement-booked-once`: each held contract the venue settled was booked exactly once, closing the whole
  holding; qkt booked no settlement the venue never made;
- `settlement-at-venue-price`: at exactly the venue's settlement price;
- `account-balance-equals-realized` (any case may assert it): the account's balance moved by exactly qkt's
  realized over the case, so the venue's own cash agrees, whatever the gateway reports as deals and fees.

`expiring_option` picks the contract when the case starts: of `underlying`'s `right` options expiring soonest
within `min_minutes` to `max_minutes`, the one nearest the money that is at least `in_the_money` (a fraction)
in the money against the index of `index_symbol` (its newest `/v1/marks` index), with a two-sided book
(`/v1/depth`) whose spread is within `max_spread` of the ask. In the money, so the settlement pays cash. The
choice is in `result.json` (`selected`), with the venue's settlements, qkt's bookings and the balance moved.

`option-held-through-expiry` is `scheduled`: Deribit expires options daily at 08:00 UTC, so it runs from about
06:30 with `--budget-seconds 7200`, holding the lock on the one account for the whole hold. It replays
nothing (`replay: none`): a backtest settles at the catalog's delivery price, not the venue's live settlement.
The offline tests (`python3 -m unittest discover -s scripts/live-validation/tests`) run the whole case against
a fake gateway and qkt: booked once passes; booked twice, at another price, or never fails.

### Failure drills

A derivatives case may declare `drills`, fired by the runner `at_s` seconds after the case's first fill (its
`after_fills`-th fill, when set) while it holds, or with `flat: true` while it holds nothing
(`scripts/live-validation/lib/derivatives_drills.py`):

| `kind` | What happens | Extra keys |
|---|---|---|
| `qkt_restart` | `qkt daemon stop`, wait, then `qkt daemon start` on the same state directory | `down_s` (15) |
| `gateway_outage` | The daemon reaches the gateway through a loopback TCP proxy the runner owns; the proxy cuts every connection and refuses new ones, then forwards again on the same port. The shared gateway keeps running | `seconds` (90) |
| `kill_switch` | With the gateway's guardian token (`QKT_DERIV_GUARDIAN_KEY`), the switch is engaged for the whole account, a risk-adding probe order (a buy at half the price, smallest size) must be refused `423 kill_switch`, then it is released | `hold_s` (30) |

On top of the case's own assertions, a drilled case passes only if every drill fired while the case held a
position (held none, for a `flat` drill), the venue's fills of the strategy are exactly the fills qkt booked (none lost, none booked twice:
`venue-fills-equal-qkt-fills`), and each engine order reached the venue under one id (`no-duplicate-order`).
An outage must show in the daemon's audit journal as the link going down and coming back up; a kill-switch
drill must see the probe refused and the daemon log the switch engaging and releasing from its event
stream (qkt-venue-gateway sends it from the release that fixed its #55). The switch is released whatever fails after it engaged (and on SIGTERM), and a case refuses to start while the
gateway's switch is on. Each drill's timeline is written to `result.json` under `drills`.

## Rules

- A case resolves inside its budget or fails. Nothing is waited on.
- A shadow case that logged no vector while its feed ticked less than twice a minute per symbol is
  `market-quiet`, not `failed`: there was nothing to compare. It still does not pass - no evidence is
  no evidence - but an unattended run can simply come back later. A busy feed with no vector is a failure.
- Value parity is exact text (`compare-trace-vectors.py`); fill prices are compared within the
  reviewed drift for the symbol's own point.
- Every capability in the catalog is proven by a `ready` case or listed in `gaps.yaml`. CI
  fails on a capability that is neither, and on a gap that a ready case already covers.
- Every case enters on the same bar close, so the one gateway takes a burst production never sees.
  Cases that fail beside the others get ONE more attempt, together, in a second and much quieter
  wave (`run-attestation-catalog.sh`): a case passes only if that attempt passes, its first failure
  is kept in `result.json` (`retried`, `firstAttempt`), and a case that fails twice fails the run.
  The shadow lane - the parity evidence - is never retried.
- A case that claims to catch a defect is run once against a build that still has the defect. It
  must fail there (`engine/mid-bar-start-higher-timeframe` fails on 0.49.1, passes from 0.51.0).
- Running all of this with nobody at the keyboard: `docs/operations/unattended-release.md`.
