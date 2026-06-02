# Iterate in the research REPL (`qkt research`)

Interactive playback of a strategy over a historical window. Load once, then step / run /
seek through a tick replay and watch trades, positions, and equity update — without
re-running a full backtest on every edit.

## Start a session

    qkt research strategy.qkt --from 2024-01-01 --to 2024-02-01 --data-root ./data

Optional: `--starting-balance 10000`. The data window is read from the local store under
`--data-root` (the same store `qkt fetch` / `qkt backtest` use).

## Commands

| Command | Effect |
|---|---|
| `run` | Advance to the end of the window. |
| `step N` | Advance N bars on the strategy's primary timeframe. |
| `step 1d` / `step 30m` / `step 2h` | Advance a wall-clock duration. |
| `run-to 2024-01-15` | Advance to a timestamp (a past time resets and runs forward). |
| `run-to next-trade` | Advance to the next fill. |
| `reset` | Restart from the first tick, same strategy. |
| `reload` | Re-read + recompile the file (after you edit it), then reset. |
| `show` | Print the current footer without advancing. |
| `quit` | Exit. |

## The tweak loop

Edit the `.qkt` in your editor, then type `reload`. A parse error keeps the previous
strategy loaded and prints the diagnostics, so a bad edit never drops your session.

## Determinism

A full `run` is bit-identical to `qkt backtest` over the same window — the REPL and the
batch backtest share one replay engine; stepping only changes when the replay pauses.

## Not yet supported

Per-bar rule introspection, in-session parameter overrides (`set`), portfolio files, and
backward seek are tracked as follow-ups under the #81 epic.
