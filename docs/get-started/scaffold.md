# Scaffold a new project

`qkt create template` writes a complete, working project tree so you go from
"qkt is installed" to "the daemon is running on docker-compose" in two
commands.

## TL;DR

```bash
qkt create template ./my-strategies            # default --kind mt5
cd my-strategies
cp .env.example .env                           # edit .env with your broker creds
make up                                        # start qkt + mt5-gateway
make deploy STRAT=ema_cross                    # deploy the sample strategy
make logs                                      # follow daemon output
```

That's the entire happy path.

## Kinds

```bash
qkt create template <path> [--kind mt5|minimal]
```

| Kind | What you get |
|---|---|
| `mt5` *(default)* | Full stack: qkt daemon + `mt5-gateway` container for MT5 (Exness, IC Markets, FTMO, Pepperstone). One-time VNC login through the gateway, then qkt talks to it over the internal Docker network. |
| `minimal` | qkt daemon only, no broker. Useful for backtest exploration or paper-trading against `BACKTEST:<symbol>` streams. Add a broker later by extending `qkt.config.yaml` + `docker-compose.yml`. |

## Generated tree

```
my-strategies/
├── .env.example          # secrets template (QKT_IMAGE_TAG, broker creds, ...)
├── Makefile              # up / down / deploy / logs / audit-ticks shortcuts
├── docker-compose.yml    # qkt + (mt5-gateway when --kind mt5)
├── qkt.config.yaml       # broker profiles + state + notifications
└── strategies/
    ├── README.md
    └── ema_cross.qkt     # sample strategy
```

`state/` and `logs/` get created on first `make up` (mounted bind volumes).

## Image-tag pinning

The generated `.env.example` pins `QKT_IMAGE_TAG=v<current-version>` —
matching the qkt binary that ran `qkt create template`. Bump it manually when
you want to move to a newer release; the compose file requires it to be set, so
a missing value fails the deploy loud (no silent downgrades — see
[Production deploy](../operations/deploy.md) for the rationale).

## Makefile targets

The bundled `Makefile` is a thin wrapper around the docker-compose + qkt CLI
commands you'd run anyway:

| Target | What it does |
|---|---|
| `make up` | `docker compose up -d` |
| `make down` | `docker compose down` (state survives) |
| `make logs` | `docker compose logs -f --tail 200 qkt` |
| `make status` / `make list` | `qkt list` inside the container |
| `make deploy STRAT=<name>` | Deploy `strategies/<name>.qkt` |
| `make stop STRAT=<name>` | Stop a deployed strategy |
| `make audit-ticks SYMBOL=<sym> [DUR=5m]` | *(mt5 only)* Capture live ticks + write JSON audit |
| `make shell` | Shell into the qkt container |

## Adding your own strategies

Drop `.qkt` files in `strategies/` and `make deploy STRAT=<name>`. The
directory is bind-mounted into the container, so edits are visible immediately
— no rebuild required. Re-running deploy hot-swaps the strategy.

## Refusing to overwrite

`qkt create template` refuses to write into a non-empty directory. To start
fresh, point it at a new path or remove the old tree first.

## Where to next

- [Deploy MT5](deploy-mt5.md) — the full broker setup that this scaffold
  builds on.
- [Production deploy (qkt-prod)](../operations/deploy.md) — the runbook for
  the managed Dokploy host that runs qkt for elitekaycy in production.
- [DSL reference](../reference/dsl/index.md) — when you're ready to write your
  own strategies.
