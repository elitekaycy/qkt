# Phase 12c — Daemon, Multi-Strategy Hosting, Docker

## Summary

Phase 12c collapses the "one terminal per strategy" UX into a single daemon process — `qkt` is now `docker` for trading strategies. `qkt daemon` runs in foreground, listens on a kernel-ephemeral TCP control port (written to `~/.local/state/qkt/control.port` for CLI discovery), and hosts many strategies as in-memory entries in a `StrategyRegistry`. Each registered strategy gets its own coroutine, its own 12b `ObservabilityServer` on a dedicated port, its own per-strategy log file (routed via logback `SiftingAppender` keyed off MDC), and shares one daemon-wide `CandleHub` so two strategies on `(BYBIT, BTCUSDT, 1m)` dedupe to a single aggregator. The `qkt deploy / list / logs / status / stop` subcommands address strategies by name through the control plane. The whole stack ships as a Docker image at `ghcr.io/elitekaycy/qkt:<tag>` published on git tag, with a multi-stage Dockerfile, a user-extension example, and a GitHub Actions workflow.

## What's new

### Daemon machinery (`com.qkt.cli.daemon`)

- `StateDir.resolve(override?)` — XDG-compliant state directory (`$XDG_STATE_HOME/qkt`, fallback `~/.local/state/qkt`). Atomic `writeControlPort(port)`/`readControlPort()` via temp+rename. Per-strategy log paths under `logs/<name>.log`.
- `StrategyHandle` — one running strategy: `LiveSession`, `ObservabilityServer`, `EventRing`, log file, MDC-tagged callbacks. Implements `AutoCloseable` for graceful drain.
- `StrategyHandle.RealFactory(stateDir, sourceFactory, candleHub)` — production builder that parses (`Dsl.parseFile`), compiles (`AstCompiler`), constructs the LiveSession + observability server + ring, registers the per-strategy MDC, returns a ready handle.
- `StrategyRegistry` — in-memory `name → StrategyHandle` map. `deploy(name, file)` rejects duplicate names and invalid identifiers (regex `[A-Za-z0-9_-]+`). `stopAll()` drains everything on daemon shutdown.
- `ControlPlane(registry, bind, port)` — `com.sun.net.httpserver`-backed HTTP server bound `127.0.0.1:0`. Routes:
  - `GET /health` — daemon liveness + strategy count + uptime.
  - `POST /deploy {file, name}` — register a strategy.
  - `GET /list` — JSON array of running strategies.
  - `POST /stop/<name>?flatten=&timeout=` — graceful shutdown of one strategy.
  - `GET /status` — array of all strategy snapshots (composes 12b `/status` from each).
  - `GET /status/<name>` — proxies to that strategy's observability port.
  - `GET /logs/<name>?lines=&since=&follow=` — per-strategy log file tail (or follow stream).
  - `POST /shutdown` — gracefully stop daemon.
- `ControlClient(stateDir)` — CLI-side: reads `control.port`, exposes typed `deploy/list/stop/status/logs/shutdownDaemon` methods over OkHttp. Errors with a clear "no daemon running" message when the port file is absent.
- `ControlRoutes` — per-route handlers (top-level functions). JSON via `kotlinx.serialization`.

### CLI subcommands (extending 12a's `Main`)

| Command | Description |
|---|---|
| `qkt daemon [--state-dir <path>] [--load-dir <path>] [--control-port <num>]` | Start daemon in foreground. |
| `qkt daemon stop` | POST `/shutdown` to running daemon. |
| `qkt daemon status` | GET `/health`. Prints PID, uptime, control port, strategy count. |
| `qkt deploy <file> [--as <name>] [--json]` | Register + start a strategy. |
| `qkt list [--json]` | Tabular listing of running strategies. |
| `qkt stop <name> [--flatten] [--timeout <ms>]` | Stop one strategy. |
| `qkt logs <name> [-f|--follow] [--since <ts>] [--lines <n>]` | Tail per-strategy log file. |
| `qkt status [<name>] [--json]` | Per-strategy or roll-up status. |

### Per-strategy logging

- **`slf4j-simple` replaced by `logback-classic`** as the runtime SLF4J implementation. ~700KB jar. Existing log output continues to render to stdout in a similar pattern.
- **`src/main/resources/logback.xml`** — `SiftingAppender` keyed off MDC `strategy` discriminator. Each registered strategy's logs route to `<state-dir>/logs/<name>.log`.
- **MDC plumbing in `StrategyHandle.RealFactory`** — `MDC.put("strategy", name)` at every callback boundary (`onTrade`, `onSignal`, `LiveSession.start`). No new dep (`kotlinx-coroutines-slf4j` deferred).
- **`LiveSession.mdcStrategy: String?`** — optional parameter so the engine thread inherits the MDC tag when the strategy logs from inside DSL evaluation.

### Shared `CandleHub` at daemon scope

- `LiveSession` gains optional `candleHub: CandleHub? = null` constructor parameter. When provided, registers the strategy's keys with the shared hub (additive). When null, constructs a fresh hub (preserves 12a/12b `qkt run` behaviour).
- `DaemonCommand` constructs **one** `CandleHub` and threads it through `StrategyHandle.RealFactory` to every deployed strategy. Two strategies on the same `(broker, symbol, timeframe)` share one aggregator + history.
- `CandleHub.feedStarted` lockout removed — the daemon must allow late-registering strategies after feed starts. Mid-stream registration sees the latest candle data; older history starts fresh per key.
- `CandleHub` slot map switched to `ConcurrentHashMap` for thread-safe daemon-scoped use.

### Docker

- **`Dockerfile`** at repo root — multi-stage. Stage 1: `eclipse-temurin:21-jdk` runs `./gradlew installDist`. Stage 2: `eclipse-temurin:21-jre`, copies the install dist, runs as a non-root `qkt` user, `WORKDIR /strategies`, `EXPOSE 40000-50000`. Entrypoint: `qkt daemon --load-dir /strategies`.
- **`./gradlew dockerBuild`** Gradle task — runs `docker build -t qkt:local .`.
- **`DockerImageTest`** with `@Tag("dockerSmoke")` — runs `docker run --rm qkt:local --version` via `ProcessBuilder`. Excluded from default `./gradlew test` runs.
- **`examples/docker/Dockerfile`** + **`examples/docker/README.md`** + **`examples/docker/strategies/sample.qkt`** — user template extending the base image.
- **`.github/workflows/docker.yml`** — on `v*` tag push, builds and pushes `ghcr.io/elitekaycy/qkt:<tag>` and `:latest`. Uses `docker/build-push-action@v5` with GHA cache.

### Version

- `BuildInfo.VERSION` bumped to `0.13.0` (substantial new public CLI surface).

### README quick-start

- New "Quick start" section at the top of `README.md` covering three deployment shapes side-by-side: foreground (`qkt run`), daemon (`qkt daemon` + `deploy/list/...`), and Docker.

## Migration from previous phase

**Logging backend swap.** `slf4j-simple` → `logback-classic`. The console output format changes slightly (`logback` defaults: `HH:mm:ss.SSS [thread] LEVEL logger - msg`). If anything in your CI was grepping log lines verbatim, the format is now configurable via `src/main/resources/logback.xml`. SLF4J API calls are unchanged.

**`qkt run` is unaffected.** No flags removed, no behaviour change. The daemon is opt-in via the new `qkt daemon` subcommand.

**`LiveSession` constructor** gains two optional parameters (`candleHub: CandleHub? = null`, `mdcStrategy: String? = null`). Existing callers compile unchanged.

**`CandleHub.feedStarted` post-feed lockout removed.** The previous `IllegalStateException` on late `register(...)` is gone. The single existing test for this behaviour was updated (`register after feed throws` → `register after feed adds a new key`). If any consumer was relying on the lockout, they need to enforce it externally.

## Usage cookbook

### Daemon mode — host many strategies on one box

Terminal 1 (or systemd / launchd / Docker PID 1):

```
$ qkt daemon
[INFO] qkt 0.13.0 daemon starting
[INFO] state directory: /home/dickson/.local/state/qkt
[INFO] control plane: http://127.0.0.1:39201
[INFO] daemon ready
```

Terminal 2 (management):

```
$ qkt deploy strategies/ema-crossover.qkt --as ema-fast
NAME       PORT     STATE     STARTED
ema-fast   47291    running   2026-05-08T15:02:14Z

$ qkt deploy strategies/momentum-basket.qkt --as momentum
NAME       PORT     STATE     STARTED
momentum   47298    running   2026-05-08T15:02:21Z

$ qkt list
NAME       UPTIME     PORT     TRADES   STATE
ema-fast   00:00:42   47291    3        running
momentum   00:00:35   47298    7        running

$ qkt status ema-fast | jq '.strategy, .uptimeMs'
"ema-crossover"
42091

$ qkt logs ema-fast -f
2026-05-08T15:02:14Z [INFO] strategy ema-crossover starting
2026-05-08T15:02:18Z [INFO] indicator warmup complete
2026-05-08T15:02:23Z [INFO] BUY BTCUSDT qty=0.001 px=68234.50

$ qkt stop ema-fast
$ qkt daemon stop
```

### Auto-deploy strategies on startup

```
$ qkt daemon --load-dir /opt/strategies
[INFO] auto-deploying ema-fast (from /opt/strategies/ema-fast.qkt)
[INFO] auto-deploying momentum-basket (from /opt/strategies/momentum-basket.qkt)
[INFO] daemon ready
```

### Docker

Pull the published image and run as the container's PID 1:

```
$ docker pull ghcr.io/elitekaycy/qkt:0.13.0
$ docker run -d --name qkt-prop \
    -v $(pwd)/strategies:/strategies \
    -p 47000-47100:47000-47100 \
    ghcr.io/elitekaycy/qkt:0.13.0

$ docker exec qkt-prop qkt list
NAME       UPTIME     PORT     TRADES   STATE
ema-fast   00:00:42   47291    3        running

$ docker exec qkt-prop qkt logs ema-fast -f
$ curl http://localhost:47291/status              # observability port mapped through Docker
```

### Extend the base image with a strategy bundle

`examples/docker/Dockerfile`:

```dockerfile
FROM ghcr.io/elitekaycy/qkt:0.13.0
COPY strategies/ /strategies/
COPY qkt.config.yaml /etc/qkt/qkt.config.yaml
# CMD inherited from base: qkt daemon --load-dir /strategies
```

```
$ docker build -t my-prop:0.1 .
$ docker run -d -p 47000-47100:47000-47100 my-prop:0.1
```

### Shared CandleHub — automatic dedup

```
$ qkt daemon &
$ qkt deploy ema-on-btc.qkt --as ema      # registers (BYBIT, BTCUSDT, 1m)
$ qkt deploy rsi-on-btc.qkt --as rsi      # also registers (BYBIT, BTCUSDT, 1m)
# → daemon has ONE aggregator for that key. One websocket subscription. One copy of every candle.
```

### Per-strategy log routing

Each strategy's output lands in its own file:

```
$ qkt deploy ema-fast.qkt --as ema
$ ls ~/.local/state/qkt/logs/
ema.log
$ tail -f ~/.local/state/qkt/logs/ema.log
2026-05-08T15:02:14.234 [INFO] strategy ema-crossover starting
2026-05-08T15:02:18.001 [INFO] BUY BTCUSDT qty=0.001 px=68234.50
```

Backed by logback's `SiftingAppender` discriminating on MDC key `strategy`.

## Testing patterns

### `DaemonEndToEndTest` — full lifecycle in-process

```kotlin
@Test
fun `full daemon lifecycle in-process`() {
    val tmp = Files.createTempDirectory("qkt-daemon-e2e")
    val daemonThread = Thread {
        DaemonCommand(
            Args(arrayOf("daemon", "--state-dir", tmp.toString())),
            sourceFactory = { fakeSource },
        ).run()
    }
    daemonThread.start()
    waitForFile(StateDir.resolve(tmp.toString()).controlPortFile)
    val client = ControlClient(StateDir.resolve(tmp.toString()))
    client.deploy("ema", Path.of("src/test/resources/cli/valid_strategy.qkt"))
    assertThat(client.list()).hasSize(1)
    client.stop("ema")
    client.shutdownDaemon()
    daemonThread.join(Duration.ofSeconds(5).toMillis())
    assertThat(daemonThread.isAlive).isFalse
}
```

### `DockerImageTest` — gated behind `dockerSmoke`

Run via `./gradlew test -PincludeTags=dockerSmoke` after `./gradlew dockerBuild`. Default `./gradlew test` skips it.

### Per-strategy log isolation

`PerStrategyAppenderTest` deploys two strategies, asserts each strategy's log file at `<state>/logs/<name>.log` contains only its own entries — no cross-contamination via shared appenders or thread leaks.

## Known limitations

- **No persistent strategy registry.** Daemon restart drops every registered strategy. User must redeploy. Persistent state-on-disk is a separate phase.
- **No hot reload.** `qkt deploy <file> --as <existing-name>` is rejected. User must `qkt stop <name>` first.
- **No auth on the control plane.** TCP-localhost binding is the security boundary — anyone with local user access can hit it. Multi-user hardening (Unix sockets with file permissions, role-based access) is post-12c.
- **No log rotation.** Per-strategy log files append forever. Use `logrotate(8)` or a Docker log driver.
- **No clustering.** One daemon per machine. Multi-machine orchestration is Kubernetes' job.
- **CandleHub keys leak.** Stopping a strategy doesn't unregister its hub keys. Long-running daemons accumulate keys over their lifetime. Per-key ref-counting is post-12c polish.
- **No multi-arch Docker manifest.** amd64 only in 12c. Multi-arch (`linux/arm64`) is post-12c polish.
- **No daemon self-update.** Replace the image, restart the container.
- **No `qkt restart <name>`.** Compose `qkt stop` + `qkt deploy` from a wrapper script.
- **No Prometheus exposition format.** `/status` is plain JSON.

## References

- Spec: `docs/superpowers/specs/2026-05-08-trading-engine-phase12c-design.md`
- Plan: `docs/superpowers/plans/2026-05-08-trading-engine-phase12c.md`
- Phase 12b (single-strategy observability HTTP port): `docs/superpowers/specs/2026-05-08-trading-engine-phase12b-design.md`
- Phase 12a (CLI binary): `docs/superpowers/specs/2026-05-08-trading-engine-phase12a-design.md`
- Phase 11e (CandleHub at pipeline scope): `docs/superpowers/specs/2026-05-08-trading-engine-phase11e-design.md`
- XDG Base Directory: <https://specifications.freedesktop.org/basedir-spec/basedir-spec-latest.html>
- Logback `SiftingAppender`: <https://logback.qos.ch/manual/appenders.html#SiftingAppender>
- Merge commit: 4d1be94
