# Config schema and reference

`qkt.config.yaml` is the operator-facing config file for qkt commands, daemon runtime, broker profiles, risk controls, accounting, observability, and promotion gates. The file is optional for local research, but production mode is intentionally fail-closed when required controls are absent.

## Resolution and substitution

qkt looks for config in this order when a command does not receive `--config <path>`:

1. `./qkt.config.yaml`
2. `/etc/qkt/qkt.config.yaml` on non-Windows systems
3. `$XDG_CONFIG_HOME/qkt/qkt.config.yaml` or the platform config equivalent
4. `~/.qkt/qkt.config.yaml`

Environment and system-property substitution works in any scalar value:

```yaml
brokers:
  exness:
    type: mt5
    gateway_url: ${EXNESS_GATEWAY_URL:-http://localhost:5001}
```

`--config` wins where a command supports it. Broker env overrides of the form `QKT_BROKER_<NAME>_<FIELD>` win over file values for MT5 broker profile scalar fields.

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
  exness:
    type: mt5
    extends: exness
    gateway_url: ${EXNESS_GATEWAY_URL:-http://localhost:5001}
    magic: 10001
    calendars:
      "BTC*": crypto
      "*": fx
    aliases:
      NAS100: USTEC
    capability_restrictions: []
    instrument_overrides:
      XAUUSD:
        min_volume: "0.01"
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
    max_gross_exposure: "300000"
    max_net_exposure: "150000"
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
    max_gross_exposure: "300000"
    max_net_exposure: "150000"
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
| `starting_balance` | decimal | `0` in config, `10000` for backtest CLI default | daemon risk, live PnL, reports | Set explicitly for production and portfolio work. |
| `log_level` | string | `info` | process logging setup where honored | Expected values are conventional log levels such as `debug`, `info`, `warn`, `error`. |

## `runtime`

Controls safety mode and explicit runtime waivers.

| Key | Type | Default | Used by | Notes |
|---|---|---|---|---|
| `runtime.mode` | `dev`, `paper`, or `production` | `dev` | config, daemon, preflight, promotion | Production mode enables fail-closed preflight and promotion enforcement defaults. |
| `runtime.waivers.<control>.reason` | string | none | preflight | Currently `alerts` is used by `notify.alerts` production preflight. Keep reasons operator-readable. |

## `state`

Controls engine state persistence.

| Key | Type | Default | Used by | Notes |
|---|---|---|---|---|
| `state.enabled` | boolean | `true` | daemon and live sessions | `false` disables restart recovery and fails production preflight. |
| `state.async` | boolean | `false` | state persistor | `true` moves persistence writes to a background thread. |

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
| `max_drawdown_pct` | unset | backtest and daemon halt rules | Percent in `(0, 100]`. Global total-drawdown halt. |
| `max_daily_drawdown_pct` | unset | backtest and daemon halt rules | Percent in `(0, 100]`. Global daily-drawdown halt. |
| `total_dd_basis` | `static` | halt rules | `static` uses initial balance. `trailing` uses high-water equity. |
| `daily_dd_basis` | `balance` | halt rules | `balance` uses day-start closed balance. `equity` includes open float. |
| `per_strategy.<name>.max_daily_loss` | unset | daemon and backtest risk layering | Per-strategy daily realized-loss halt. |
| `per_strategy.<name>.max_position_size` | unset | daemon pre-trade controls | Caps absolute position size for one strategy. |
| `per_strategy.<name>.max_open_positions` | unset | daemon pre-trade controls | Caps non-zero symbols for one strategy. |
| `per_strategy.<name>.max_drawdown_pct` | unset | halt rules | Per-strategy total drawdown percent. |
| `per_strategy.<name>.max_daily_drawdown_pct` | unset | halt rules | Per-strategy daily drawdown percent. |

Per-strategy rules layer on top of global rules. A global breach halts the whole daemon. A per-strategy breach halts only that strategy.

## `account` and `fx_conversion`

| Key | Type | Default | Used by | Notes |
|---|---|---|---|---|
| `account.currency` | ISO currency string | `USD` | accounting engine, reports, risk notional | Normalized to uppercase. |
| `fx_conversion.source` | string | `market` | accounting evidence | Descriptive source label for FX conversion. |
| `fx_conversion.missing_policy` | `warn` or `fail` | `warn` outside production, `fail` in production | accounting engine | `fail` rejects unconvertible non-account-currency PnL/costs. |
| `fx_conversion.symbols.<PAIR>` | qkt symbol | empty | backtest/accounting | Maps an FX pair such as `USDJPY` to a qkt symbol used to source conversion marks. |

CLI overrides are available for backtests: `--account-currency`, `--fx-source`, `--fx-missing-policy`, and repeated `--fx-symbol PAIR=QKT_SYMBOL`.

## `execution`

Backtest, sweep, walk-forward, and experiment commands read execution settings through `BacktestContext`.

| Key | Type | Default | CLI override | Notes |
|---|---|---|---|---|
| `execution.preset` | `paper-fast`, `mt5-basic`, `mt5-realistic`, `stress` | based on `--broker`, usually `paper-fast` | `--execution` | Chooses default broker simulator behavior. |
| `execution.seed` | long | unset except stress default `42` | `--seed` | Deterministic random slippage seed. |
| `execution.latency` | duration | preset default | `--execution-latency` | Accepts integer milliseconds, `250ms`, `1s`, or `fixed:250ms`. |
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

`brokers` is keyed by profile name. `type: mt5` entries are loaded by `MT5BrokerProfileLoader` and can inherit built-in defaults.

Built-in MT5 profile names: `exness`, `icmarkets`, `ftmo`, `pepperstone`.

| Key | Type | Required | Default/inheritance | Notes |
|---|---|---|---|---|
| `brokers.<name>.type` | string | yes | none | Currently `mt5` for config-driven MT5 profiles. |
| `extends` | profile name | no | same-name built-in if present | Inherit from a built-in or earlier user profile. |
| `gateway_url` | URL | yes for fresh profile | inherited or built-in | MT5 gateway HTTP base URL. |
| `symbol_suffix` | string | no | inherited or empty | Appended to broker symbol names. |
| `magic` | int | yes for fresh profile | inherited or built-in | Must be unique across MT5 profiles. |
| `server_tz_offset_hours` | int | yes for fresh profile | inherited or built-in | MT5 server offset from UTC. |
| `poll_interval_ms` | long | no | `1000` | Position and pending-order polling cadence. |
| `http_timeout_ms` | long | no | `5000` | Gateway HTTP timeout. |
| `retry_attempts` | int | no | `3` | Gateway retry attempts. |
| `deviation_points` | int | no | `20` | Market-order price deviation tolerance. |
| `calendars` | map pattern to `fx`, `crypto`, or `nyse` | no | inherited or FX default | First matching pattern wins. |
| `aliases` | map qkt symbol to broker symbol | no | inherited plus overrides | Example `NAS100: USTEC`. |
| `capability_restrictions` | list of `OrderTypeCapability` names | no | inherited plus overrides | Disables venue capabilities by enum name. |
| `instrument_overrides.<symbol>` | map | no | inherited plus overrides | Requires `min_volume`, `volume_step`, `point_size`, `digits`, `trade_stops_level_points`. |

Bybit credentials are not configured under `qkt.config.yaml`. Bybit live routes are enabled when `BYBIT_API_KEY` is non-empty. The client reads `BYBIT_API_KEY`, `BYBIT_API_SECRET`, `BYBIT_TESTNET`, `BYBIT_RECV_WINDOW_MS`, and `BYBIT_ACCOUNT_TYPE` from the environment.

## `notify`

Notification channels are keyed by channel type. Telegram is built in.

| Key | Type | Default | Used by | Notes |
|---|---|---|---|---|
| `notify.<channel>.enabled` | boolean | `false` | daemon notifier and preflight | Production preflight fails if no alert channel is enabled unless `runtime.waivers.alerts.reason` is set. |
| `notify.<channel>.commands` | boolean | `false` | daemon command channels | Telegram command channel is enabled only when this is true. |
| `notify.<channel>.events` | list | empty | notifier filter | Valid event names: `order_rejected`, `halted`, `resumed`, `position_reconciled`, `strategy_started`, `strategy_stopped`, `strategy_error`, `daemon_started`. |
| `notify.<channel>.daily_summary_utc` | string | empty | daily summary scheduler | UTC time string used by the channel. |
| `notify.telegram.bot_token` | string | none | Telegram provider | Required for enabled Telegram. |
| `notify.telegram.chat_id` | string | none | Telegram provider | Required for enabled Telegram. |
| `notify.telegram.queue_capacity` | int | `100` | Telegram provider | Bounded queue size. |

Unknown notify channel keys are passed through as provider settings.

## `insights`

Optional egress to a qkt-insights collector. Disabled config wires no queue and no worker thread.

| Key | Type | Default | Notes |
|---|---|---|---|
| `insights.enabled` | boolean | `false` | Must be true and `url` non-blank to create a sink. |
| `insights.url` | URL | empty | Collector ingest URL. |
| `insights.instance_id` | string | `qkt` fallback at daemon wire time | Instance label sent with events. |
| `insights.token` | string | empty | Bearer or collector token as expected by the sink. |
| `insights.events` | list | all families when enabled and omitted | Valid families: `trade`, `order`, `signal`, `risk`, `position`, `snapshot`, `log`, `state`, `deal`. `snapshot` is retained for old configs and wires nothing. |
| `insights.flush_interval_ms` | long | `250` | Batch flush cadence. |
| `insights.batch_size` | int | `200` | Max events per HTTP batch. |
| `insights.queue_capacity` | int | `10000` | In-memory queue bound. |
| `insights.state_poll_ms` | long | `10000` | Broker state polling cadence. |
| `insights.deal_backfill_days` | long | `30` | Broker deal backfill window on startup. |

## `book_risk`

Book-risk controls apply to portfolio/book evaluation and portfolio daemon flows.

| Key | Type | Default | Notes |
|---|---|---|---|
| `book_risk.capital` | decimal | unset | Required for drawdown-style book risk to form a basis. |
| `book_risk.limits.max_gross_exposure` | decimal | unset | Gross exposure cap in account currency. |
| `book_risk.limits.max_net_exposure` | decimal | unset | Net exposure cap in account currency. |
| `book_risk.limits.max_symbol_concentration` | decimal | unset | Fractional single-symbol concentration cap. |
| `book_risk.de_risk.ladder[].drawdown` | decimal | required per rung | Drawdown threshold as fraction. |
| `book_risk.de_risk.ladder[].factor` | decimal | required per rung | Exposure scale factor at that drawdown rung. |
| `book_risk.de_risk.ladder[].cooldown_bars` | int | unset | Bars to hold a rung after recovery. |
| `book_risk.allocation.method` | `FIXED`, `INVERSE_VOL`, `ERC` | `FIXED` | Portfolio allocation method. |
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
3. Run pinned backtests with `--dataset` and realistic execution.
4. Record promotion evidence and approval.
5. Run `qkt promotion status <name> --strategy <strategy.qkt> --config qkt.config.yaml`.
6. Run `qkt status --deep` after daemon startup.
