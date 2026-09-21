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
| `status` | `ready` (runs in the attestation) or `planned` (designed, not yet runnable) |
| `symbols` | Venue-neutral symbols, e.g. `[EURUSD, XAUUSD]` |
| `timeframes` | Every timeframe a stream uses, e.g. `[1m, 15m, 1h]` |
| `warmup_bars` | Per-timeframe warmup depth, e.g. `{1m: 6, 15m: 20}` |
| `proves` | Capability names from `oracle-evidence.json`, plus free-form `behaviour:` tags |
| `budget_seconds` | Hard deadline; omitted means the lane default. Never above 600 |
| `steps` | Daemon/engine lanes: ordered operator commands with the exit code and state expected after each |
| `assertions` | Named checks the runner applies, e.g. `trace-parity`, `journal-byte-exact`, `flat-by-magic` |

## Rules

- A case resolves inside its budget or fails. Nothing is waited on.
- Value parity is exact text (`compare-trace-vectors.py`); fill prices are compared within the
  reviewed drift for the symbol's own point.
- Every capability in the catalog is proven by a `ready` case or listed in `gaps.yaml`. CI
  fails on a capability that is neither, and on a gap that a ready case already covers.
