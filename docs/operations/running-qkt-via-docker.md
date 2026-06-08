# Running qkt via Docker

You can use qkt entirely from Docker — author, backtest, and run strategies — without
installing Java or qkt on your machine. Two images are published:

| Image | For | Default behavior |
|---|---|---|
| `ghcr.io/elitekaycy/qkt:latest` (and `:vX.Y.Z`) | production / the CLI | starts the **daemon** |
| `ghcr.io/elitekaycy/qkt:dev` | authoring + testing | drops you into a **shell** with `qkt` + `vim`/`nano` |

The runtime image is deliberately minimal (no editor, non-root, daemon entrypoint) so the
trading container stays small and locked down. The `:dev` image adds an editor and a shell for
interactive work. **Do not deploy `:dev` to production.**

---

## Author + test in a dev container (recommended)

Start one long-lived container, then `exec` into it to write and run strategies.

```bash
# A host folder for your strategies — files persist here and are editable from the host too.
mkdir -p ~/qkt-lab

# Start the dev container (detached, interactive, workspace mounted writable).
docker run -dit --name qkt-dev -v ~/qkt-lab:/work ghcr.io/elitekaycy/qkt:dev

# Drop into it.
docker exec -it qkt-dev bash
```

Inside the container — `qkt`, `vim`, and `nano` are all on `PATH`, and `/work` is writable:

```bash
qkt --help
qkt create template mystrat.qkt --kind minimal   # scaffold a starter into /work
vim mystrat.qkt                                   # .qkt syntax highlighting is pre-installed
qkt parse mystrat.qkt
qkt backtest mystrat.qkt --from 2024-03-01 --to 2024-03-05
```

Lifecycle:

```bash
docker stop qkt-dev      # pause — in-container state is kept
docker start qkt-dev     # resume
docker rm -f qkt-dev     # discard the container (your ~/qkt-lab files are untouched)
```

Your strategies live in `~/qkt-lab` on the host, so they survive `rm` and you can also edit
them with your host editor (e.g. VSCode + the qkt extension) — same files, either way.

---

## One-off CLI commands (runtime image)

For a single command without a long-lived container, run the runtime image and override its
daemon entrypoint. A shell alias makes it feel native:

```bash
alias qkt='docker run --rm -it --entrypoint qkt \
  -u "$(id -u):$(id -g)" -v "$PWD:/work" -w /work \
  ghcr.io/elitekaycy/qkt:latest'

qkt parse mystrat.qkt
qkt backtest mystrat.qkt --from 2024-03-01 --to 2024-03-05
```

Each invocation creates a fresh container, runs the one command, and `--rm` deletes it — only
the mounted `/work` persists. `-u "$(id -u):$(id -g)"` runs as you, so files written to the
mount are owned by you. This is stateless and clean; use the dev container above when you want
to stay inside an environment.

---

## Data persistence

Backtests read market data from a local store. Keep it under `/work` so it survives across
runs and container rebuilds:

- `qkt backtest` defaults to `--data-root ./data`, which is `/work/data` when your workspace is
  mounted at `/work` — so the cache persists in `~/qkt-lab/data`.
- `qkt fetch` defaults to `~/.qkt/data` inside the container, which is **not** persisted. Pass
  `--data-root /work/data` so it lands in the same place the backtest reads from:
  ```bash
  qkt fetch BYBIT_SPOT:BTCUSDT --tf 5m --last 30d --data-root /work/data
  ```

Sources today: crypto (`BYBIT_SPOT`/`BYBIT_LINEAR`) fetches from Bybit's public API with no
extra setup; FX/metals (e.g. `EXNESS:XAUUSD`) need the MT5 gateway until seamless dukascopy
auto-fetch lands, after which `qkt backtest` acquires its own data with no infra.

---

## Updating

Both `:latest` and `:dev` are mutable tags. Docker caches them, so refresh explicitly:

```bash
docker pull ghcr.io/elitekaycy/qkt:dev
docker pull ghcr.io/elitekaycy/qkt:latest
```

To pin an exact release for reproducibility, use a version tag: `ghcr.io/elitekaycy/qkt:v0.34.0`.

---

## Daemon vs CLI

The runtime image's entrypoint is `qkt daemon --load-dir /strategies` — running it with no
arguments starts the long-lived daemon (how production deploys it). The CLI subcommands
(`parse`, `backtest`, `run`, `create`, `editor`, …) are reached either through the `:dev`
shell, or by overriding the entrypoint as in the one-off alias above.
