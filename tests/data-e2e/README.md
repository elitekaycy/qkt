# Real-data end-to-end suite

Real vendor files run through the installed `qkt` as a user would, every stored value checked against an
independent reader (`lib/oracle.py`, plain Python and `Decimal`) or the vendor's own data at another
resolution. Design: `docs/superpowers/specs/2026-10-18-data-e2e-suite-design.md`.

```bash
./gradlew installDist
git clone git@github.com:elitekaycy/qkt-data-corpus.git ../qkt-data-corpus   # private; pinned in corpus.lock
git -C ../qkt-data-corpus checkout "$(cut -d' ' -f1 tests/data-e2e/corpus.lock)"
python3 -I tests/data-e2e/run.py --tier pr      # what every pull request runs (about a minute)
python3 -I tests/data-e2e/run.py                # every case (the testing branch)
python3 -I tests/data-e2e/run.py --case <id> --keep   # one case, keeping its work folder to look at
```

- `cases.toml`: one case per source shape and per edge. `tier = "pr"` cases run on every pull request.
- `golden/<case>.json`: the sha256 of every file a case stores. A change that alters a stored byte fails until
  `--case <id> --update` rewrites it, so the change shows in the pull request's diff. Oracle checks run either
  way, so a rewritten fingerprint cannot hide a wrong value.
- Each case runs in its own data root with a private home and config folder: nothing on the machine
  (`~/.qkt`, `~/.config/qkt`, `/etc/qkt`) reaches it.

## Adding a source

1. Download the file into the corpus (keep it byte-for-byte), add its source rule to
   `qkt-data-corpus/tools/provenance.py`, run it, commit and push the corpus.
2. Put the new corpus commit in `corpus.lock`.
3. Add a case: the import, what the report must say, and a check against an independent read of the file and,
   where the vendor publishes them, its own bars at another timeframe.
4. `python3 -I tests/data-e2e/run.py --case <id> --update`, read the fingerprint, commit.

A vendor that draws data its own way gets that convention written into the oracle (Bybit's klines open at the
previous close), never a tolerance. A tolerance is allowed only for a named ambiguity in the vendor's own
data, counted and capped (Bybit spot trades sharing a millisecond; Binance aggTrades stamped at their first
fill).
