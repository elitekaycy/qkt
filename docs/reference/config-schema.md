# Config schema and reference

`qkt.config.yaml` is the operator-facing config file for qkt commands, daemon runtime, broker profiles, risk controls, accounting, observability, and promotion gates. The file is optional for local research, but production mode is intentionally fail-closed when required controls are absent.

## Resolution and substitution

qkt resolves config in this order:

1. `--config <path>`
2. `QKT_CONFIG`
3. `./qkt.config.yaml`
4. `/etc/qkt/qkt.config.yaml` on non-Windows systems
5. `$XDG_CONFIG_HOME/qkt/qkt.config.yaml` or the platform config equivalent
6. `~/.qkt/qkt.config.yaml`

Environment and system-property substitution works in any scalar value:

```yaml
brokers:
  mt5:
    type: mt5
    gateway_url: ${QKT_BROKER_GATEWAY_URL:-http://localhost:5001}
```

Broker env overrides of the form `QKT_BROKER_<NAME>_<FIELD>` win over file
values for MT5 profile scalar fields and for every connector's credential fields. They are profile-scoped operational
overrides; reusable scaffolds use neutral substitution variables such as
`QKT_BROKER_GATEWAY_URL`.

## Minimal research config

Local research can run with no config. This file is enough when you want explicit data and accounting defaults:

```yaml
source: local
data_root: ./data
starting_balance: 10000
log_level: info

runtime:
  mode: dev

account:
  currency: USD

fx_conversion:
  missing_policy: warn
```

## Production-style config skeleton

```yaml
source: local
data_root: /var/lib/qkt/data
starting_balance: 100000
log_level: info

runtime:
  mode: production
  waivers:
    alerts:
      reason: "temporary supervised launch without alert channel"

state:
  enabled: true
  async: false

account:
  currency: USD

fx_conversion:
  source: market
  missing_policy: fail
  symbols:
    USDJPY: BACKTEST:USDJPY
    EURUSD: BACKTEST:EURUSD

execution:
  preset: mt5-realistic
  seed: 42
  # Venue position model the backtest simulates (#1071). CLI runs default to `hedging`
  # (the retail-MT5 model both production accounts use): every entry books its own
  # coexisting leg and each bracket exit closes only its leg. `netting` collapses
  # opposite fills into one signed position for venues that truly net.
  position_mode: hedging

risk:
  max_daily_loss: "1000"
  max_order_qty: "100"
  max_order_notional: "250000"
  price_collar_pct: "5"
  margin_floor_pct: "200"
  measured_usage_hours: "24"
  measured_usage_max_qty: "0.01"
  max_drawdown_pct: "8"
  max_daily_drawdown_pct: "4"
  total_dd_basis: static
  daily_dd_basis: balance
  per_strategy:
    xau-ema-cross:
      max_daily_loss: "300"
      max_position_size: "1.0"
      max_open_positions: "2"
      max_drawdown_pct: "5"
      max_daily_drawdown_pct: "3"

promotion:
  enforce: true
  required_state: production
  dataset_snapshot: true
  realistic_execution: true
  walk_forward: true
  approval: true
  paper_days: 20
  paper_min_trades: 30
  max_paper_slippage_bps: 3.0

brokers:
  mt5:
    type: mt5
    gateway_url: ${QKT_BROKER_GATEWAY_URL:-http://localhost:5001}
    api_key: ${QKT_BROKER_API_KEY}
    server_time_zone: ${QKT_BROKER_SERVER_TIME_ZONE}
    symbol_suffix: ${QKT_BROKER_SYMBOL_SUFFIX:-}
    magic: ${QKT_BROKER_MAGIC:-10001}
    calendars:
      "BTC*": crypto
      "XAU*": fx pause 17:00-18:00 America/New_York
      "*": fx
    aliases:
      NAS100: USTEC
    capability_restrictions: []
    instrument_overrides:
      XAUUSD:
        min_volume: "0.01"
        max_volume: "50"
        volume_step: "0.01"
        point_size: "0.001"
        digits: "3"
        trade_stops_level_points: "50"

notify:
  telegram:
    enabled: true
    bot_token: ${TELEGRAM_BOT_TOKEN}
    chat_id: ${TELEGRAM_CHAT_ID}
    events: [order_rejected, halted, resumed, strategy_error, daemon_started]
    daily_summary_utc: "21:00"
    commands: true

insights:
  enabled: false

book_risk:
  capital: "100000"
  limits:
    max_gross_exposure: "3.0"      # x capital: 300,000 on this book
    max_net_exposure: "1.5"        # x capital
    max_symbol_concentration: "0.35"
  de_risk:
    ladder:
      - drawdown: "0.04"
        factor: "0.50"
        cooldown_bars: 24
      - drawdown: "0.08"
        factor: "0.00"
        cooldown_bars: 72
  allocation:
    method: INVERSE_VOL
    target_vol: "0.12"
    rebalance_every_bars: 96
    max_leverage: "3"
```

## Paper daemon example

Use this when you want daemon behavior and alerts without production fail-closed gates:

```yaml
source: tv
starting_balance: 25000

runtime:
  mode: paper

state:
  enabled: true

risk:
  max_daily_loss: "500"
  max_order_qty: "1.0"
  max_order_notional: "50000"

notify:
  telegram:
    enabled: true
    bot_token: ${TELEGRAM_BOT_TOKEN}
    chat_id: ${TELEGRAM_CHAT_ID}
    events: [order_rejected, halted, resumed, strategy_error]
```

## qkt-forge-compatible research example

Use this for high-volume research where qkt-forge owns orchestration and qkt stays the deterministic kernel:

```yaml
source: local
data_root: ../qkt-forge/run/data
starting_balance: 10000

runtime:
  mode: dev

execution:
  preset: paper-fast

account:
  currency: USD

fx_conversion:
  missing_policy: warn

risk:
  max_daily_loss: "0"
```

qkt-forge should pass `--data-root`, `--dataset`, `--execution`, and `--parallelism` explicitly in its command layer when a gate needs stricter behavior than this fast default.

## Portfolio/book-risk example

Use this when evaluating a multi-strategy book or a portfolio DSL file:

```yaml
starting_balance: 100000

account:
  currency: USD

fx_conversion:
  source: market
  missing_policy: fail
  symbols:
    USDJPY: BACKTEST:USDJPY

risk:
  max_daily_loss: "1500"
  max_drawdown_pct: "8"
  max_daily_drawdown_pct: "4"
  total_dd_basis: trailing
  daily_dd_basis: equity

book_risk:
  capital: "100000"
  limits:
    max_gross_exposure: "3.0"      # x capital: 300,000 on this book
    max_net_exposure: "1.5"        # x capital
    max_symbol_concentration: "0.35"
  allocation:
    method: ERC
    target_vol: "0.10"
    rebalance_every_bars: 96
    max_leverage: "2.5"
```

## Top-level fields

| Key | Type | Default | Used by | Notes |
|---|---|---|---|---|
| `source` | string | `tv` when file exists, `local` from built-in defaults on missing file | `daemon`, `run` market-source fallback | `tv` opens TradingView fallback. `replay` reads `QKT_REPLAY_TICKS`. Any other value uses a null fallback. MT5 and Bybit routed symbols still use their own routes. |
| `data_root` | path string | `./data` in config object, but backtest CLI defaults to `DataRoot.resolve()` unless `--data-root` is passed | historical data commands and examples | Prefer explicit `--data-root` for research runs that need reproducibility. |
| `starting_balance` | decimal | `0` (unset) | daemon risk, live PnL, reports, backtest basis | Must be greater than zero when a live drawdown limit is configured. A single-strategy backtest uses it too when `--starting-balance` is not given, so its drawdown halts and percent sizing sit on the daemon's balance; with neither set a backtest starts at `10000`. The chosen source is printed to stderr. Set explicitly for production and portfolio work. |
| `log_level` | string | `info` | process logging setup where honored | Expected values are conventional log levels such as `debug`, `info`, `warn`, `error`. |

## `runtime`

Controls safety mode and explicit runtime waivers.

| Key | Type | Default | Used by | Notes |
|---|---|---|---|---|
| `runtime.mode` | `dev`, `paper`, or `production` | `dev` | config, daemon, preflight, promotion | Production mode enables fail-closed preflight and promotion enforcement defaults. |
| `runtime.candle_close_grace_ms` | non-negative integer | `2000` | daemon, run, backtest, replay | How long after a bar's window ends the 1 Hz heartbeat closes a quiet symbol's bar. Live and replay read the same value, so a `SYNCHRONIZE` group with a sparse member decides on the same heartbeat step in both; replay records it in `result.json` as `execution.candleCloseModel`. A tick of the same stream from the next window closes the bar immediately in both modes. |
| `runtime.waivers.<control>.reason` | string | none | preflight | Currently `alerts` is used by `notify.alerts` production preflight. Keep reasons operator-readable. |

## `state`

Controls engine state persistence.

| Key | Type | Default | Used by | Notes |
|---|---|---|---|---|
| `state.enabled` | boolean | `true` | daemon and live sessions | `false` disables restart recovery and fails production preflight. |
| `state.async` | boolean | `false` | state persistor | `true` moves persistence writes to a background thread. |
| `state.journal_retention_days` | integer | `14` | daemon | Day-files of the engine audit journal and MT5 transport journal (plain or gzipped) older than this many UTC days are deleted at daemon start and once a day. `0` keeps everything. |
| `state.journal_compress_after_days` | integer | `1` | daemon | Closed `.jsonl` journal day-files older than this many UTC days are gzipped in place (~10x smaller); today's and yesterday's files stay plain with the default. Golden capture and retention read both forms. `0` disables compression. |
| `state.disk_free_alert_gb` | integer | `10` | daemon and preflight | Below this much free space on the state volume the daemon logs an error and raises a `disk_space_low` alert (once per crossing; re-arms at 110% of the floor). Preflight fails production below the floor. `0` disables. |

State root is not set in config. Use `--state-dir` or `QKT_STATE_DIR` for commands that support state directories.

## `risk`

Daemon-wide and per-strategy risk controls. Values are parsed as decimals unless noted.

| Key | Default | Used by | Notes |
|---|---|---|---|
| `max_daily_loss` | `1000` | backtest halt rules and daemon | Set `0` to disable the global daily-loss rule. Production preflight requires an explicit risk block. |
| `max_order_qty` | `PreTradeControls.DEFAULT_MAX_ORDER_QTY` | daemon pre-trade controls | Hard per-order quantity cap. |
| `max_order_notional` | `PreTradeControls.DEFAULT_MAX_ORDER_NOTIONAL` | daemon pre-trade controls | Account-currency notional cap. |
| `price_collar_pct` | `PreTradeControls.DEFAULT_PRICE_COLLAR_FRAC` as percent | daemon pre-trade controls | Percent distance from last market price for explicit-price orders. |
| `margin_floor_pct` | `200` | daemon pre-trade controls | Entry orders reject when reported margin level is below this floor. `0` disables. |
| `measured_usage_hours` | `24` | daemon pre-trade controls | New deployments can only trade up to `measured_usage_max_qty` during this window. `0` disables. |
| `measured_usage_max_qty` | `0.01` | daemon pre-trade controls | Max entry quantity during measured usage. |
| `max_round_trips_10m` | `10` | daemon, foreground run, and backtest disclosure | Per-strategy closing-fill threshold in 10 minutes. `0` disables. Live halts persistently on a breach; replay records the breach unless strict mode is enabled. |
| `max_broker_rejections_1m` | `5` | daemon, foreground run, and backtest disclosure | Per-strategy broker-rejection threshold in one minute. `0` disables. Live halts persistently on a breach; replay records the breach unless strict mode is enabled. |
| `max_drawdown_pct` | unset | backtest and daemon halt rules | Percent in `(0, 100]`. Global total-drawdown halt. |
| `max_daily_drawdown_pct` | unset | backtest and daemon halt rules | Percent in `(0, 100]`. Global daily-drawdown halt. |
| `total_dd_basis` | `static` | halt rules | `static` uses initial balance. `trailing` uses high-water equity. |
| `daily_dd_basis` | `balance` | halt rules | `balance` uses day-start closed balance. `equity` includes open float. |
| `live_equity_basis` | `venue` | standalone live sizing and drawdown | `venue` consumes broker account equity. `modeled` pins live to `starting_balance + qkt realized + qkt unrealized`, matching backtest accounting. Portfolio children always use their allocated modeled capital. |

`balance` is retained as the compatibility default, but it ignores intraday open
loss. Accounts governed by equity-based daily-loss mandates (including many funded
account programs) should set `daily_dd_basis: equity` explicitly.
| `per_strategy.<name>.max_daily_loss` | unset | daemon and backtest risk layering | Per-strategy daily realized-loss halt. |
| `per_strategy.<name>.max_position_size` | unset | daemon pre-trade controls | Caps absolute position size for one strategy. |
| `per_strategy.<name>.max_open_positions` | unset | daemon pre-trade controls | Caps non-zero symbols for one strategy. |
| `per_strategy.<name>.max_drawdown_pct` | unset | halt rules | Per-strategy total drawdown percent. |
| `per_strategy.<name>.max_daily_drawdown_pct` | unset | halt rules | Per-strategy daily drawdown percent. |
| `per_strategy.<name>.max_trades_per_day` | unset | PACER pre-trade controls | Rejects risk-increasing entries after N entry fills in the current UTC day. |
| `per_strategy.<name>.cooldown_after_loss` | unset | PACER pre-trade controls and DSL | Duration such as `30m`, `1h`, or integer milliseconds. Rejects new risk after losses while active. |
| `per_strategy.<name>.cooldown_after_loss_after_consecutive` | `1` | PACER pre-trade controls and DSL | Number of consecutive losing closes required before cooldown starts. |
| `per_strategy.<name>.loss_streak_halt` | unset | halt rules | Halts the strategy once consecutive losing closes reach N. |
| `per_strategy.<name>.loss_streak_halt_scope` | `persistent` | halt rules | `daily` auto-resumes at next UTC day; `persistent` requires operator resume. |

Per-strategy rules layer on top of global rules. A global breach halts the whole daemon. A per-strategy breach halts only that strategy.

## `account` and `fx_conversion`

| Key | Type | Default | Used by | Notes |
|---|---|---|---|---|
| `account.currency` | ISO currency string | `USD` | accounting engine, reports, risk notional | Normalized to uppercase. |
| `fx_conversion.source` | string | `market` | accounting evidence | Descriptive source label for FX conversion. |
| `fx_conversion.missing_policy` | `warn` or `fail` | `fail` | accounting engine | `fail` rejects unconvertible non-account-currency PnL/costs. `warn` is an explicit unsafe compatibility override. |
| `fx_conversion.symbols.<PAIR>` | qkt symbol | empty | backtest/accounting | Maps an FX pair such as `USDJPY` to a qkt symbol used to source conversion marks. |

CLI overrides are available for backtests: `--account-currency`, `--fx-source`, `--fx-missing-policy`, and repeated `--fx-symbol PAIR=QKT_SYMBOL`.

## `execution`

Backtest, sweep, walk-forward, and experiment commands read execution settings through `BacktestContext`.

| Key | Type | Default | CLI override | Notes |
|---|---|---|---|---|
| `execution.preset` | `paper-fast`, `mt5-basic`, `mt5-realistic`, `stress` | based on `--broker`, usually `paper-fast` | `--execution` | Chooses default broker simulator behavior. |
| `execution.seed` | long | unset except stress default `42` | `--seed` | Deterministic random slippage seed. |
| `execution.latency` | duration | preset default | `--execution-latency` | Accepts integer milliseconds, `250ms`, `1s`, or `fixed:250ms`. Delays order placement: a delayed market order fills at the sided quote prevailing at its release (the last quote at or before `submit + latency`), not the next quote after it, matching the venue (45 of 46 Exness demo market deals on 2026-09-23 filled at the prevailing quote, none at the next one). A resting order's trigger-to-fill stays instantaneous. |
| `execution.order_spacing` | duration | `0` | `--order-spacing` | Minimum gap between consecutive order releases on the simulated venue's single send lane (mt5-sim). An order submitted at `t` is released at the later of `t + latency` and the previous release plus the spacing, and fills at the sided quote prevailing at its release. Models the serialized MT5 gateway, which places a burst's legs one after another (measured on the Exness demo: about 140-160 ms between legs; parity row A20). `0` releases every order independently. Same format as `execution.latency`; not valid with `--tick-fills`. |
| `execution.stop_latency` | duration | `0` | `--stop-latency` | Delay between a protective stop's trigger and its execution (mt5-sim). A crossed stop fills at the first quote at or after `trigger + delay`, sided, plus slippage — the venue behaviour measured on the Exness demo (median ~260 ms, #1135). `0` fills on the crossing print. Same format as `execution.latency`. |
| `execution.tp_fill` | `print`, `level` | `print` | `--tp-fill` | How a gap-crossed protective take-profit is priced (mt5-sim). `print` credits the crossing print when it beats the level; `level` fills exactly at the level, as retail MT5 does. |
| `execution.slippage` | string | preset default | `--slippage` | `zero`, `instrument`, `fixed-points:N`, or `uniform:N`. |
| `execution.reject_every` | int | unset | `--reject-every` | Reject every Nth simulated order. |
| `execution.partial_fill` | decimal `(0,1)` | unset | `--partial-fill` | Fractional partial-fill model. |

Preset defaults:

| Preset | Broker kind | Defaults |
|---|---|---|
| `paper-fast` | `paper` | zero latency, zero slippage, no venue rules |
| `mt5-basic` | `mt5-sim` | instrument slippage and MT5 sizing rules |
| `mt5-realistic` | `mt5-sim` | 250 ms latency, instrument slippage, stop-distance enforcement |
| `stress` | `mt5-sim` | 500 ms latency, uniform slippage up to 20 points, reject every 10th order, 50 percent partial fills |

No preset sets `execution.order_spacing`. Add `--order-spacing 150ms` when a strategy sends several orders at once (`STACK_AT` bursts, `TIMES`, `OCO_ENTRY`), where a single release price overstates the fills.

## `promotion`

Promotion gates protect production deploys. In `runtime.mode: production`, `promotion.enforce` defaults to `true`.

| Key | Type | Default | Used by | Notes |
|---|---|---|---|---|
| `promotion.enforce` | boolean | `runtime.mode == production` | daemon deploy, `qkt promotion status`, `qkt status --deep` | When true, missing gates block deploy unless waived. |
| `promotion.required_state` | promotion state | `production` | gate evaluator | Valid states: `draft`, `research`, `candidate`, `paper`, `shadow-live`, `small-capital`, `production`, `retired`. |
| `promotion.dataset_snapshot` | boolean | `false` | gate evaluator | Requires `--evidence dataset_snapshot=...` on the promotion record. |
| `promotion.realistic_execution` | boolean | `false` | gate evaluator | Requires `--evidence realistic_execution=...`. |
| `promotion.walk_forward` | boolean | `false` | gate evaluator | Requires `--evidence walk_forward=...`. |
| `promotion.approval` | boolean | `true` | gate evaluator | Requires `qkt promotion approve` for the required state. |
| `promotion.paper_days` | int | `0` | gate evaluator | Minimum recorded paper/live validation days. |
| `promotion.paper_min_trades` | int | `0` | gate evaluator | Minimum recorded paper/live validation trades. |
| `promotion.max_paper_slippage_bps` | double | unset | gate evaluator | Fails if recorded p95 paper slippage is missing or above the cap. |
| `promotion.registry_dir` | path | state dir `promotion` subdir | promotion store | Override JSONL promotion registry location. |

Promotion records are appended JSONL. Waivers require a reason and are also journaled when created through CLI or deploy waiver paths.

## `brokers`

`brokers` is keyed by account name. Each entry is one trading account, opened by the connector
named in its `type`; the entry name, upper-cased, is the prefix strategies use (`prop_s01` serves
`PROP_S01:XAUUSD`). A missing or unknown `type` refuses startup and lists the installed
connectors. See [Broker integration](../concepts/broker-integration.md) for the model.

An entry may carry only the settings its connector lists below, plus `type` and the nested blocks
`calendars`, `aliases`, `capability_restrictions` and `instrument_overrides`. Any other key refuses
startup and names the closest known setting — a misspelled `expected_account_login` would
otherwise switch the account identity check off without a word.

Credential fields (`api_key`, `api_secret`) accept a literal, `${VAR}` (substituted when the
file loads), `env:VAR`, or `file:/path` (trailing newline trimmed, for Docker secrets). An
environment override `QKT_BROKER_<NAME>_<FIELD>` wins over the file value.

### `type: mt5`

MT5 entries can inherit built-in defaults.

Built-in MT5 profile names: `exness`, `icmarkets`, `ftmo`, `pepperstone`.

| Key | Type | Required | Default/inheritance | Notes |
|---|---|---|---|---|
| `brokers.<name>.type` | string | yes | none | `mt5` (this table) or `bybit` (below). |
| `extends` | profile name | no | same-name built-in if present | Inherit from a built-in or earlier user profile. |
| `gateway_url` | URL | yes for fresh profile | inherited or built-in | MT5 gateway HTTP base URL. |
| `api_key` | string | no | inherited or empty | Bearer token matching the gateway `API_KEY`; use a neutral variable such as `${QKT_BROKER_API_KEY}` in reusable scaffolds. |
| `symbol_suffix` | string | no | inherited or empty | Appended to broker symbol names. |
| `magic` | int | yes for fresh profile | inherited or built-in | Must be unique across MT5 profiles. |
| `server_time_zone` | zone id | yes for fresh profile | inherited or built-in | MT5 server clock. Use `new_york_close` for UTC+2/+3 following US DST, or an IANA id such as `Europe/Helsinki`. |
| `server_tz_offset_hours` | int | no | none | Legacy fixed-offset alternative. Cannot be combined with `server_time_zone`; does not handle DST. |
| `poll_interval_ms` | long | no | `1000` | Position and pending-order polling cadence. |
| `tick_poll_interval_ms` | long | no | `1000` | Live quote polling cadence. When omitted beside an explicitly configured `poll_interval_ms`, inherits that value for backward compatibility. Set both keys to tune quote and reconciliation load independently. |
| `http_timeout_ms` | long | no | `20000` | Per-request gateway HTTP timeout. The gateway places orders serially at roughly four a second, so a burst of N entries (`TIMES N`, a deep `STACK`, N actions in one rule) queues N/4 seconds deep; size this above that or the tail of the burst fails on the qkt side with "Socket closed" while the venue still fills it. |
| `retry_attempts` | int | no | `3` | Gateway retry attempts. |
| `deviation_points` | int | no | `20` | Market-order price deviation tolerance. |
| `expected_account_login` | long | production MT5 | none | Refuses startup when `/account.login` differs. |
| `expected_account_server` | string | production MT5 | none | Refuses startup when `/account.server` differs. |
| `expected_trade_mode` | `demo`, `contest`, or `real` | production MT5 | none | Prevents demo/real environment inversion. |
| `expected_account_currency` | currency code | no | none | Optional profile-level currency assertion; global `account.currency` is also checked by preflight. |
| `expected_leverage` | int | production MT5 | none | Refuses startup when venue leverage differs. |
| `expected_margin_mode` | `netting` or `hedging` | production MT5 | none | Refuses startup when the venue's margin mode differs — the engine's position model must match the account's (#1071). |
| `calendars` | map pattern to `fx`, `crypto`, `nyse`, or `<base> pause HH:MM-HH:MM [Zone]` | no | inherited or FX default | First matching pattern wins. A `pause` clause adds a venue-scheduled daily break on top of the base calendar's sessions; the map form `{ base: fx, pause: 17:00-18:00, zone: America/New_York }` is equivalent. During the pause the market-data gate reports a quote gap as `PAUSED` (info) instead of `STALE` (error) and still suppresses new entries; feed polling and session gating are unchanged. Outside the symbol's session (weekends for `fx`, never for `crypto`) the gate reports a quote gap as venue closed (info), not `STALE`. Built-in venue windows, measured from live quote gaps and widened by one stale threshold on each side: `exness` and `icmarkets` pause metals and energy (`XAU*`, `XAG*`, `XPT*`, `XPD*`, `USOIL*`, `UKOIL*`, `XTI*`, `XBR*`, `XNG*`) 16:55–18:10 New York and FX 16:55–17:15 New York; `exness` also pauses copper (`XCU*`) 18:50–01:10 London and keeps crypto (`BTC*`, `ETH*`, `LTC*`, `XRP*`, `BCH*`, `SOL*`, `ADA*`, `DOGE*`, `DOT*`, `LINK*`) on the 24/7 `crypto` calendar so its feed polls through weekends; `the5ers` pauses metals and energy 16:45–18:10 and everything else 16:50–17:10 New York, with BTC on the FX calendar (it stops at weekends there). |
| `aliases` | map qkt symbol to broker symbol | no | inherited plus overrides | Example `NAS100: USTEC`. |
| `capability_restrictions` | list of `OrderTypeCapability` names | no | inherited plus overrides | Disables venue capabilities by enum name. |
| `instrument_overrides.<symbol>` | map | no | inherited plus overrides | Requires `min_volume`, `volume_step`, `point_size`, `digits`, `trade_stops_level_points`; optional `max_volume` is enforced when present. |

### `type: bybit`

One entry per Bybit product category. The Bybit brokers serve fixed prefixes, so the entry must be
named after its category: `bybit_spot` (`BYBIT_SPOT:`) or `bybit_linear` (`BYBIT_LINEAR:`).
Entries with the same credentials and endpoint share one Bybit connection.

```yaml
brokers:
  bybit_linear:
    type: bybit
    category: linear
    api_key: env:BYBIT_API_KEY
    api_secret: env:BYBIT_API_SECRET
    testnet: "true"
```

| Key | Type | Required | Default | Notes |
|---|---|---|---|---|
| `category` | `spot` or `linear` | yes | none | Must match the entry name. |
| `api_key` | credential | yes | none | Refuses startup when missing or empty. |
| `api_secret` | credential | yes | none | Refuses startup when missing or empty. |
| `testnet` | bool | no | `true` | Only an explicit `false` trades mainnet. |
| `recv_window_ms` | long | no | `5000` | Bybit signed-request receive window. |
| `account_type` | string | no | `UNIFIED` | Bybit account type for balance reads. |

The daemon connects each Bybit account at startup and refuses to start if the connection is
rejected. Bybit is never enabled by environment variables alone.

Policy-rate artifacts are also configured through the environment. `QKT_RBA_POLICY_RATE_SOURCE`
and `QKT_RBNZ_POLICY_RATE_SOURCE` accept an absolute path, `file:` URI, or HTTPS URL for the
authorities' official XLSX tables. The RBA and RBNZ URLs are the defaults. Use a read-only mounted
local artifact when an authority rejects server-side HTTP traffic; qkt records its SHA-256 and
fails live ingestion when the artifact is malformed or more than seven calendar days stale.

## `market_data`

Thresholds for the live market-data quality gate (`MarketDataGate`) every daemon live session builds. Defaults are the gate's historical hard-coded values, so an absent block changes nothing.

| Key | Type | Default | Used by | Notes |
|---|---|---|---|---|
| `market_data.stale_age_multiple` | double | `5.0` | live sessions | Staleness threshold as a multiple of the symbol's smoothed inter-tick gap. |
| `market_data.min_stale_age_ms` | long | `10000` | live sessions | Floor for the staleness threshold. |
| `market_data.outlier_sigma` | double | `6.0` | live sessions | Standard deviations from the short-window mean beyond which a tick is rejected. |
| `market_data.max_clock_skew_ms` | long | `60000` | live sessions | Tolerance between broker tick timestamps and the local clock before new orders are suppressed. |

The gate suppresses new orders for a symbol in every case below; only the faults alert. A fault
logs at ERROR, sends a `MarketDataUnhealthy` strategy error (when `strategy_error` notifications
are on) and ships `marketdata.stale` to insights once per episode.

| Condition | Log | Alerts | `marketdata.stale` `kind` |
|---|---|---|---|
| No tick past the staleness threshold while the symbol is in session | ERROR `STALE` | yes | `stale` |
| Broker tick clock further than `max_clock_skew_ms` from the local clock | ERROR `CLOCK-SKEWED` | yes | `clock_skew` |
| A run of rejected outlier ticks | ERROR `UNHEALTHY` | yes | `outlier` |
| Quote gap inside a calendar `pause` | INFO `PAUSED` | no | none |
| Quote gap while the symbol's calendar is out of session, or a last print older than any server-zone offset | INFO `venue closed` | no | none |

Sessions are judged per symbol from the broker profile's `calendars`, so on an account trading
`XAUUSD` and `BTCUSD` (with `BTC*: crypto`) a weekend gap is closed for gold and still `STALE` for
bitcoin. A gap that went `STALE` in session stays `STALE` after the session ends. Every condition
clears on the first fresh tick. When a symbol that alerted is fully healthy again (no fault left,
clock in tolerance), the gate logs `recovered after <ms>ms unhealthy` and ships one
`marketdata.recovered` event whose `unhealthyForMs` runs from the episode's first alert; a symbol
that raised `stale` and `clock_skew` recovers once, when both have cleared. Recovery sends no
notification.

## `notify`

Notification channels are keyed by channel type. Telegram is built in.

| Key | Type | Default | Used by | Notes |
|---|---|---|---|---|
| `notify.<channel>.enabled` | boolean | `false` | daemon notifier and preflight | Production preflight fails if no alert channel is enabled unless `runtime.waivers.alerts.reason` is set. |
| `notify.<channel>.commands` | boolean | `false` | daemon command channels | Telegram command channel is enabled only when this is true. |
| `notify.<channel>.events` | list | empty | notifier filter | Valid event names: `order_rejected`, `halted`, `resumed`, `position_reconciled`, `strategy_started`, `strategy_stopped`, `strategy_error`, `daemon_started`, `disk_space_low`. |
| `notify.<channel>.daily_summary_utc` | string | empty | daily summary scheduler | UTC time string used by the channel. |
| `notify.telegram.bot_token` | string | none | Telegram provider | Required for enabled Telegram. |
| `notify.telegram.chat_id` | string | none | Telegram provider | Required for enabled Telegram. |
| `notify.telegram.queue_capacity` | int | `100` | Telegram provider | Bounded queue size. |

Unknown notify channel keys are passed through as provider settings.

## `insights`

Optional egress to a qkt-insights collector. Disabled config wires no queue and no worker thread. Enable `journal_enabled` for production so collector outages leave unacked batches on disk for replay instead of dropping them after retry exhaustion. Daemon live sessions also write a full engine audit JSONL under `state/audit-journal/<strategy>/audit-YYYY-MM-DD.jsonl`; the audit writer uses a bounded queue and its own daemon thread so durable file I/O does not run on the event bus thread.

For `trade.closed` envelopes, `realized` is retained only as a legacy alias of
`netAccountRealized`. Dashboards should treat `netAccountRealized` as the
canonical account-currency PnL after modeled commissions and venue-reported
costs. When conversion evidence is available, the same payload includes
`grossAccountRealized`, `nativeRealized`, currencies, FX rate/source fields, and
`costsAccount` so live trade tables and graphs can reconcile net-vs-gross values.

Market-data health ships in the `lifecycle` family, per symbol (see [`market_data`](#market_data)
for when each fires):

- `marketdata.stale`: `{"source", "symbols": [symbol], "state": "stale", "reason", "ts", "kind"}`,
  `kind` one of `stale`, `clock_skew`, `outlier`; `reason` is the operator text, e.g.
  `quote age 60553ms exceeds 60000ms threshold`.
- `marketdata.recovered`: `{"source", "symbols": [symbol], "state": "recovered", "reason", "ts",
  "unhealthyForMs"}`, e.g. `"reason": "fresh tick after stale", "unhealthyForMs": 184000`. Pair it
  with the open `marketdata.stale` for the same source and symbol to measure the episode.

Both use envelope ids `marketdata-<state>-<source>-<ts>`, like `marketdata.connected`,
`marketdata.disconnected` and `marketdata.reconnected`.

| Key | Type | Default | Notes |
|---|---|---|---|
| `insights.enabled` | boolean | `false` | Must be true and `url` non-blank to create a sink. |
| `insights.url` | URL | empty | Collector ingest URL. |
| `insights.instance_id` | string | `qkt` fallback at daemon wire time | Instance label sent with events. |
| `insights.token` | string | empty | Bearer or collector token as expected by the sink. |
| `insights.events` | list | all families when enabled and omitted | Valid families: `trade`, `order`, `signal`, `risk`, `position`, `snapshot`, `log`, `state`, `deal`, `lifecycle`. `snapshot` is retained for old configs and wires nothing. |
| `insights.flush_interval_ms` | long | `250` | Batch flush cadence. |
| `insights.batch_size` | int | `200` | Max events per HTTP batch. |
| `insights.queue_capacity` | int | `10000` | In-memory queue bound before the sink worker drains events. |
| `insights.journal_enabled` | boolean | `false` | When true, the sink worker spools serialized envelopes locally and replays unacked rows after collector downtime. |
| `insights.journal_dir` | path | daemon state `state/insights-journal` fallback when journal is enabled | Optional journal directory. Blank uses the daemon state directory fallback. |
| `insights.state_poll_ms` | long | `10000` | Broker state polling cadence. |
| `insights.deal_backfill_days` | long | `30` | Broker deal backfill window on startup. |

## `book_risk`

Book-risk controls apply to portfolio/book evaluation and portfolio daemon flows.

| Key | Type | Default | Notes |
|---|---|---|---|
| `book_risk.capital` | decimal | unset | Required for drawdown-style book risk to form a basis. |

> **`book_risk` applies to portfolio deployments only.** A strategy deployed on its own is
> not bounded by these limits — `BookRiskController` is built by the portfolio deployer alone. The
> daemon warns at start when the block is present, and total notional is otherwise unbounded for a
> standalone strategy. See parity catalog row A23.
| `book_risk.limits.max_gross_exposure` | decimal | unset | Gross exposure cap as a **multiple of `capital`** (`3.0` = 3x). Not an amount of money: values above 100 are refused at load. |
| `book_risk.limits.max_net_exposure` | decimal | unset | Net exposure cap as a multiple of `capital`. |
| `book_risk.limits.max_symbol_concentration` | decimal | unset | Single-symbol net exposure cap as a multiple of `capital` (`0.35` = 35%). |
| `book_risk.de_risk.ladder[].drawdown` | decimal | required per rung | Drawdown threshold as fraction. |
| `book_risk.de_risk.ladder[].factor` | decimal | required per rung | Exposure scale factor at that drawdown rung. |
| `book_risk.de_risk.ladder[].cooldown_bars` | int | unset | Bars to hold a rung after recovery. |
| `book_risk.allocation.method` | `FIXED`, `INVERSE_VOL`, `ERC`, `REGIME_WEIGHTED` | `FIXED` | Portfolio allocation method. Regime portfolio DSL selects `REGIME_WEIGHTED` automatically. |
| `book_risk.allocation.target_vol` | decimal | unset | Target volatility for allocation. |
| `book_risk.allocation.rebalance_every_bars` | int | `0` | Rebalance cadence. |
| `book_risk.allocation.max_leverage` | decimal | `4` | Allocation leverage cap. |

## Legacy and reserved sections

| Section | Status | Notes |
|---|---|---|
| `tv` | parsed flat map | Reserved for TradingView-related settings. Current live fallback is selected by top-level `source: tv`. |
| `fetchers` | parsed nested map | Reserved for named fetcher settings. Current backtest custom fetcher path uses CLI flags `--fetcher dukascopy --fetcher-script <path>`. |

## qkt-forge config alignment

qkt-forge has its own YAML files under `../qkt-forge/config`. Keep these aligned with qkt:

| qkt-forge file | qkt side to align |
|---|---|
| `sources.yaml` | qkt strategy symbols, data root, dataset snapshot windows |
| `gates.yaml` | qkt execution preset, walk-forward windows, cost stress, promotion evidence keys |
| `qkt.yaml` | qkt binary/image, data root, concurrency, session budget |
| `budget.yaml` | research spend and run cadence, not qkt runtime config |
| `agents.yaml` | model/agent config, not qkt runtime config |

qkt-forge should call ordinary qkt commands for backtest, sweep, walk-forward, experiment, and promotion. Production deploy governance remains in qkt.

## Common command overrides

| Command | Config read | Useful overrides |
|---|---|---|
| `qkt backtest` | `risk`, `account`, `fx_conversion`, `execution`, `book_risk` | `--config`, `--dataset`, `--data-root`, `--execution`, `--broker`, `--account-currency`, `--fx-symbol`, `--starting-balance` |
| `qkt sweep` | same as backtest | `--parallelism`, `--param`, `--scenarios` |
| `qkt walkforward` | same as backtest | `--train`, `--test`, `--step`, `--parallelism` |
| `qkt experiment run` | passes through backtest config | `--plan`, `--parallelism`, `--dataset`, `--registry-dir`, `--out-dir` |
| `qkt daemon start` | all runtime sections | `--config`, `--state-dir`, `--load-dir` |
| `qkt preflight` | runtime, state, risk, broker, notify | `--production`, `--config`, `--state-dir` |
| `qkt promotion` | promotion | `--config`, `--state-dir`, `--registry-dir` |

## Validation checklist

Before production:

1. Run `qkt preflight <strategy.qkt> --production --config qkt.config.yaml`.
2. Run `qkt brokers list --config qkt.config.yaml` when using MT5 profiles.
3. Run `qkt instruments verify --config qkt.config.yaml` to detect YAML/venue metadata drift.
4. Run pinned backtests with `--dataset` and realistic execution.
5. Record promotion evidence and approval.
6. Run `qkt promotion status <name> --strategy <strategy.qkt> --config qkt.config.yaml`.
7. Run `qkt status --deep` after daemon startup.
