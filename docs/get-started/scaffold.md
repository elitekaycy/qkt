# Scaffold a new project

`qkt create template` generates a complete project with pinned images, sample
configuration, strategies, `.env.example`, and operational commands.

## Quick start

```bash
qkt create template ./my-strategies
cd my-strategies
cp .env.example .env
# Replace the credential placeholders in .env.
make up
make deploy STRAT=full_strategy
make resync-dry-run STRAT=full_strategy
make resync STRAT=full_strategy
```

The default is the full MT5 stack. Generated `.env` files, state, market data,
reports, and logs are ignored by Git.

## Template kinds

```bash
qkt create template <path> \
  [--kind mt5|mt5-ci|backtest|portfolio|minimal|bybit]
```

| Kind | Contents |
|---|---|
| `mt5` *(default)* | QKT, authenticated MT5 gateway, Docker Compose, an EMA example, and a production-shaped bracketed strategy. |
| `mt5-ci` | Everything in `mt5`, plus a GitHub Actions deployment workflow and server setup instructions. |
| `backtest` | Local-data research configuration and a runnable single-strategy backtest. |
| `portfolio` | Local-data research configuration, book risk limits, a weighted portfolio, and two child strategies. |
| `minimal` | Broker-free QKT daemon and a small sample strategy. |
| `bybit` | QKT configured for Bybit REST, using testnet by default. |

Specialized templates are layered on tested base projects, so `mt5-ci` retains
the complete MT5 deployment and the research templates retain the standard
container and ignore-file setup.

## Configuration model

Only deployment-specific values need changing:

- Copy `.env.example` to `.env` for local Docker deployment.
- Replace broker/API credentials and any notification credentials.
- Keep the image defaults pinned, or deliberately update them during an upgrade.
- `qkt.config.yaml` references environment variables and provides defaults for
  non-secret values such as the internal gateway URL, magic number, data root,
  and starting balance.

The generated MT5 gateway requires `MT5_API_KEY`; QKT receives the same value as
`QKT_BROKER_EXNESS_API_KEY`. Credentials are never stored in `qkt.config.yaml`.

## GitHub deployment

The `mt5-ci` template deploys pushes to `main` through a GitHub environment named
`production`. Configure these environment secrets:

- `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS`
- `MT5_LOGIN`, `MT5_PASSWORD`, `MT5_API_KEY`, `MT5_VNC_PASSWORD`
- optionally `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`

Optional environment variables include `DEPLOY_PORT`, `DEPLOY_PATH`,
`MT5_SERVER`, `MT5_VNC_USER`, `QKT_EXNESS_MAGIC`, and `TELEGRAM_ENABLED`.
The workflow validates required secrets, renders a mode-600 `.env`, validates
Compose, transfers the project without deleting persistent state/data, then
pulls and starts the pinned images.

See the generated `DEPLOYMENT.md` for server prerequisites and initial setup.

## Research templates

For `backtest` or `portfolio`, place data under `data/` as described by the
generated README, then run:

```bash
cp .env.example .env
make backtest
```

The `portfolio` template also includes daemon targets for whole-book lifecycle
checks: `make up`, `make deploy BOOK=<name>`, `make resync-dry-run BOOK=<name>`,
`make resync BOOK=<name>`, and `make reconcile BOOK=<name>`.

## Safety and lifecycle

The scaffolder refuses to overwrite a non-empty target. `make down` retains
state. Strategy files are bind-mounted, so `make deploy STRAT=<name>` starts a
new daemon entry and `make resync-dry-run STRAT=<name>` followed by
`make resync STRAT=<name>` validates and replaces an edited running strategy
without rebuilding an image or stopping unrelated strategies.

For detailed operations, see [Deploy MT5](deploy-mt5.md) and
[Production deploy](../operations/deploy.md).
