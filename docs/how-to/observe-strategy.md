# Verify a live strategy with `qkt observe`

`qkt observe` reads the running daemon's control plane and prints a go/no-go report over a period.
It is read-only — it never halts, stops, or redeploys anything. Three gates:

- **placement** — every scheduled `:55` window in the period fired (a `submit` line appeared).
- **errors** — no engine-side `[ERROR]` lines (recurring benign WARNs are allowlisted).
- **pnl** — daily realized per UTC day, cumulative realized/unrealized, and a `Σ fills ≈ status.realized`
  reconciliation. This gate is **review**, not a hard pass/fail on the value: a legitimately red day is
  not a failure — you judge the number against your own expectation. Only a reconciliation *mismatch*
  (a P&L-accounting bug) fails it.

The overall verdict is `GO` unless a gate `FAIL`s (placement missed a window, or an engine error, or a
PnL mismatch), in which case it is `NO-GO` and the exit code is non-zero — so it composes in cron/scripts.

## Local daemon

```bash
qkt observe --strategy hedge-straddle --since 7d
```

`--since` accepts `7d` / `24h` / `30m` (default `24h`). `--windows` overrides the expected placement
schedule (default is hedge-straddle's `--windows 6,7,12,13,14,15@55`).

## Production daemon

The daemon's control plane binds to `127.0.0.1` only, so reach it over an SSH tunnel and point
`--control-port` at the local end of the tunnel:

```bash
PORT=$(ssh root@<prod> 'cat "$QKT_STATE_DIR/control.port"')
ssh -N -L 9999:127.0.0.1:"$PORT" root@<prod> &
qkt observe --strategy hedge-straddle --since 7d --control-port 9999
```

## Note on daily PnL

The daily-realized table is reconstructed from `trade … realized=` log lines the daemon emits per fill.
Those lines were added alongside this command, so the daily breakdown is populated for runs on that
daemon version onward; cumulative realized (from `/status`) is always available.
