# Config schema

`qkt.config.yaml` (project-local) configures brokers, data sources, and runtime defaults. The file is optional — qkt has sensible defaults for every field. See the [`qkt.config.yaml.example`](https://github.com/elitekaycy/qkt/blob/main/qkt.config.yaml.example) for an annotated template.

`${VAR}` substitution from environment variables works in any value.

## Top-level fields

```yaml
source: tv                            # default tick source for live mode
data_root: ./data                     # historical-data store path
starting_balance: 10000               # default starting balance for backtest reports
log_level: info                       # info | debug | warn | error
```

## Bybit credentials (optional)

```yaml
bybit:
  api_key: ${BYBIT_API_KEY}
  api_secret: ${BYBIT_API_SECRET}
```

Only used when a strategy declares a `BYBIT:` stream.

## Brokers

The `brokers:` section is keyed by profile name. Each entry has a `type:` discriminator that picks the broker class.

### `type: mt5`

```yaml
brokers:
  exness:
    type: mt5
    gateway_url: http://localhost:5001
    symbol_suffix: m                    # appended to qkt symbol → broker symbol
    aliases:                            # qkt symbol → broker base name
      NAS100: USTEC
      US500: US500
      UKOIL: XBRUSD
    server_tz_offset_hours: 2           # MT5 server time → UTC subtraction
    magic: 10001                        # identifies orders placed by this profile
    poll_interval_ms: 1000              # position-poll cadence
    http_timeout_ms: 5000
    retry_attempts: 3
    deviation_points: 20                # market order slippage tolerance (in points)
```

#### Built-in defaults

Listing a profile by a built-in name (`exness`, `icmarkets`, `ftmo`, `pepperstone`) inherits its defaults. You only need to specify the fields you want to override:

```yaml
brokers:
  exness:
    type: mt5
    gateway_url: http://my-host:5005    # only this changes
```

#### Extending a base profile

Use `extends:` to base a new profile on a built-in or another user profile:

```yaml
brokers:
  exness-personal:
    type: mt5
    extends: exness
    gateway_url: http://localhost:5005
    magic: 10005
```

## Resolution order (last wins)

1. Built-in defaults (from `MT5DefaultProfiles`)
2. User config: `~/.config/qkt/qkt.config.yaml`
3. Project config: `./qkt.config.yaml` (or `--config <path>`)
4. Env vars: `QKT_BROKER_<NAME>_<FIELD>=value`
5. CLI flags on relevant commands

## Required fields for a fresh profile

If a profile name doesn't match a built-in and doesn't `extends:` one, you must provide:

- `gateway_url`
- `magic`
- `server_tz_offset_hours`

Everything else has a default.

## Risk

Daemon-wide and per-strategy risk caps.

```yaml
risk:
  max_daily_loss: "1000"          # daemon-global daily realized-P&L floor (0 to disable)

  # Prop-firm drawdown limits (#348). *_pct values are PERCENTS (8 => 8%).
  max_drawdown_pct: "8"           # halt the account at 8% total drawdown
  max_daily_drawdown_pct: "4"     # halt at 4% drawdown within a single UTC day
  total_dd_basis: static          # static (vs initial balance, default) | trailing (vs high-water peak)
  daily_dd_basis: balance         # balance (day-start closed balance, default) | equity (incl. open float)

  # Phase 25D: per-strategy overrides keyed by strategy name. Each field is optional.
  per_strategy:
    ema_cross:
      max_daily_loss: "500"        # halts only ema_cross when its realized loss exceeds $500
      max_position_size: "1.0"     # rejects ema_cross orders that push |position| above 1.0
      max_open_positions: 3        # rejects ema_cross new-symbol entries past 3 concurrent positions
      max_drawdown_pct: "5"        # per-strategy total-drawdown halt (percent)
      max_daily_drawdown_pct: "3"  # per-strategy daily-drawdown halt (percent)
    rsi_mean_reversion:
      max_daily_loss: "300"
```

Per-strategy caps **layer on top of** the global rules — both apply. A strategy without an entry under `per_strategy` only sees the global rules. A strategy with an entry gets its own per-strategy halts (`MaxStrategyDailyLoss`, `MaxStrategyDrawdown`, `MaxStrategyDailyDrawdown`) and per-strategy order rules (`MaxStrategyPositionSize`, `MaxStrategyOpenPositions`) added in addition. The global `max_daily_loss` / `max_drawdown_pct` / `max_daily_drawdown_pct` always halt *every* strategy on breach.

**Drawdown basis.** `total_dd_basis` and `daily_dd_basis` are account-level (not per-strategy). *Static* total drawdown measures loss from the account's starting balance (the prop-firm "max loss" rule); *trailing* measures from the running high-water peak. The daily reference is captured at UTC midnight: *balance* uses the day-start closed balance, *equity* includes open-position float. `total_dd_basis` defaults to **static**, `daily_dd_basis` to **balance**.

**Backtest daily metrics.** `qkt backtest --json` emits `maxDailyDrawdown` (worst single-day intraday equity decline, a fraction) and `dailyPnL` (`{ "YYYY-MM-DD": realized }`), so you can size a strategy against these limits before deploying. (`maxDailyDrawdown` is distinct from `maxDrawdown`, the peak-to-trough over the whole run.)

`max_open_positions` counts symbols with a non-zero position; opening on a new symbol when at the cap is rejected. Adding to an existing-symbol position is always permitted by this rule (the position-size rule still applies).

## Inspect resolved profiles

```bash
qkt brokers list
```

Shows the resolved set with values pulled from defaults, file, and env.
