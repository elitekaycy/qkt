# qkt production stack

This bundle wires qkt, qkt-insights, and the MT5 REST gateway as one Docker Compose stack. It is designed for a demo or paper validation account first, then the same wiring can be promoted to production by changing env values and image tags.

Use this as the production-ready template: copy the env/config files, fill real secrets, pin image tags for promotion, and run `docker compose up -d`.

## What Runs

- `qkt-insights`: dashboard, API, SQLite storage, and `POST /ingest` collector on port `8420`.
- `mt5-gateway`: MT5 terminal plus REST API on port `5001`, with VNC on port `3000` for login/debug.
- `qkt`: daemon image from GHCR, loading strategies from `./strategies` and pushing insights events to `qkt-insights`.

qkt to qkt-insights is a push path: qkt sends batches to `http://qkt-insights:8420/ingest`. That is the correct model for ordered trade/order/log events. Metrics-only monitoring can be pull/scrape based, but qkt-insights is an event ingestion system. This stack enables qkt's local insights journal so unacked event batches are replayed after collector downtime instead of being discarded.

## First Run

```bash
cd examples/production-stack
cp .env.example .env
cp qkt.config.yaml.template qkt.config.yaml
# edit .env with MT5 demo credentials, strong tokens, and pinned images for production

docker compose up -d
```

Then verify the stack:

```bash
docker compose ps
curl -fsS http://localhost:8420/healthz
curl -fsS -H "Authorization: Bearer $MT5_API_KEY" http://localhost:5001/health/ready
docker compose config >/tmp/qkt-stack.rendered.yml
```

Open qkt-insights in a browser:

```text
http://localhost:8420
```

Sign in with `ADMIN_USERNAME` and `ADMIN_PASSWORD` from `.env`.

## Paper/Demo Validation

The included `strategies/eur-paper.qkt` is intentionally small and uses the `MT5:EURUSD` broker-neutral profile. With demo MT5 credentials this exercises the same qkt runtime path as live trading:

1. MT5 gateway provides live ticks and broker responses.
2. qkt daemon runs the strategy and broker adapter.
3. qkt pushes `signal`, `order.*`, `trade`, `trade.closed`, `risk.*`, `state.*`, `broker.deal`, `strategy.started`, `strategy.stopped`, and `log` envelopes to qkt-insights. `strategy.started` includes source path, source SHA-256, DSL version, runtime mode, streams, params, defaults, and risk caps.
4. qkt-insights validates, stores, and streams those events to the UI.

This is the same insights transport used in live mode. Paper/demo and live both use `LiveSession`, the same MT5 broker adapter route, the same qkt `InsightsSink`, and the same qkt-insights `POST /ingest` collector. The account safety difference is the MT5 account, qkt `runtime.mode`, promotion gates, and risk sizing, not a separate observability mechanism.

Check qkt activity:

```bash
docker compose logs -f qkt
docker compose exec qkt qkt list
docker compose exec qkt qkt logs eur-paper -f
```

Check qkt-insights Health and Orderflow pages while the strategy runs. You should see the instance named by `QKT_INSTANCE_ID`.

## Production Promotion

Before using real capital:

- Keep `QKT_RUNTIME_MODE=paper` until the strategy has enough paper days/trades for your gate.
- Use an MT5 demo account first, then a live account only after feed, fills, and qkt-insights data are verified.
- Set a unique `QKT_BROKER_MAGIC` per qkt instance/account.
- Replace mutable `:latest` image tags in `QKT_IMAGE` and `QKT_INSIGHTS_IMAGE` with immutable `:v*` or `:sha-*` GHCR tags once published.
- Back up the `qkt-state` and `qkt-insights-data` Docker volumes. The qkt volume contains the local insights replay journal when `QKT_INSIGHTS_JOURNAL_ENABLED=true`.
- Keep MT5 HTTP and VNC ports bound to `127.0.0.1` unless there is a controlled private network in front of them.

## CI and GHCR Expectations

- qkt CI runs fast JVM build/test/ktlint on `dev` PRs, integration smoke on `testing` and `main`, then publishes GHCR images from `main`, `testing`, and `v*` tags.
- qkt runtime images are smoke-tested before publish with `qkt --version`, `qkt parse`, and a containerized backtest. Published tags include `latest` on `main`, `edge` on `testing`, immutable `sha-*`, and `v*` release tags, with provenance and SBOM enabled.
- qkt-insights CI runs TypeScript builds, tests, a production Docker smoke that boots `/healthz` and verifies `/ingest`, then publishes GHCR images from `main` and `v*` tags. Published tags include `latest`, immutable `sha-*`, and `v*`, with provenance and SBOM enabled.
- This compose template defaults to `latest` for convenience only. Production deploys should set `QKT_IMAGE` and `QKT_INSIGHTS_IMAGE` to immutable tags that have passed CI.

## Local qkt-insights Only

You can run only the dashboard locally to inspect UI changes:

```bash
cd ../qkt-insights
mise exec node@22.22.1 -- pnpm install --frozen-lockfile
mise exec node@22.22.1 -- pnpm build:all
INSIGHTS_DB=/tmp/qkt-insights-local.db \
INGEST_TOKEN=local-token \
ADMIN_USERNAME=admin \
ADMIN_PASSWORD=admin-pass \
SESSION_SECRET=local-session-secret-at-least-32-chars \
mise exec node@22.22.1 -- node dist/src/server.js run
```

Then browse `http://localhost:8420`. To feed it from qkt, set the qkt config `insights.url` to `http://host.docker.internal:8420/ingest` from a Docker container, or `http://localhost:8420/ingest` from a local qkt process.

## Files

- `.env.example`: all required environment variables.
- `docker-compose.yml`: production-shape stack wiring.
- `qkt.config.yaml.template`: qkt daemon config with insights enabled.
- `strategies/eur-paper.qkt`: minimal demo strategy for validation.

## Stop

```bash
docker compose down
# destructive: also removes qkt and qkt-insights volumes
docker compose down -v
```
