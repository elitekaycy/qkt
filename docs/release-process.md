# Release process

## Versioning

SemVer 0.x.y while pre-1.0:

- **minor (`0.X.0`)** — phase number. Phase 10 → `0.10.x`. Phase 11 → `0.11.x`.
- **patch (`0.X.Y`)** — sub-phase letters (a/b/c/d-a/d-b/...) in chronological order, or hotfixes between phases. Phase 10 = `0.10.0`, Phase 10b = `0.10.1`, a hypothetical Phase 10c hotfix = `0.10.2`.

Breaking changes are acceptable in minor bumps until 1.0. This matches the "no backwards compatibility cruft" stance documented in the qkt skill.

Once a stable public DSL ships and the API is documented as stable, we bump to `1.0.0` and standard SemVer rules apply (no breaking changes in minor).

## Tagging

After a `merge: phase X ...` commit lands on main:

```bash
git checkout main && git pull
git tag -a v0.X.Y -m "phase X — <short description>"
git push origin v0.X.Y
```

Tags are annotated and immutable. **Never** force-update or delete a published tag. If you push a wrong tag, immediately push a corrected next-patch tag and note the mistake in the release notes.

## GitHub Release

```bash
gh release create v0.X.Y \
  --title "v0.X.Y — phase X <description>" \
  --notes-file docs/phases/phase-<N>-<topic>.md
```

If no phase changelog exists (phases 1-6 predate the convention), use `--notes "<merge commit subject>"` instead.

Mark as "Latest release" if it is.

## Binary distribution (since Phase 12a)

Phase 12a ships the `qkt` CLI binary via Gradle's `application` plugin. Each release should attach a tarball so users can install without building from source.

```bash
./gradlew distTar
gh release upload v0.X.Y build/distributions/qkt-0.X.Y.tar
```

The release body should document install:

```bash
curl -L -o qkt.tar https://github.com/<owner>/<repo>/releases/download/v0.X.Y/qkt-0.X.Y.tar
tar -xf qkt.tar
export PATH="$PWD/qkt-0.X.Y/bin:$PATH"
qkt --version
```

## Hotfix policy

Pre-1.0 hotfixes:
1. Fix on a `fix-<short-description>` branch off main.
2. Merge via `--no-ff` with `merge: fix <description>`.
3. Tag as the next patch on the current minor: e.g. if main is at `v0.10.1`, the hotfix is `v0.10.2`.
4. No back-port branches. Users on older minors update to latest.

## Mapping phase merges to versions

| Phase merge | Version |
|---|---|
| phase 1 trading engine | v0.1.0 |
| phase 2a event bus | v0.2.0 |
| phase 2b candle aggregator | v0.2.1 |
| phase 3 risk and positions | v0.3.0 |
| phase 3b pnl and bigdecimal | v0.3.1 |
| phase 4 backtest harness | v0.4.0 |
| phase 5 indicators | v0.5.0 |
| phase 6 data store | v0.6.0 |
| phase 6 cleanup | v0.6.1 |
| phase 7a live runtime refactor | v0.7.0 |
| phase 7b live runtime + warmup | v0.7.1 |
| phase 7c TradingView vendor | v0.7.2 |
| phase 7d-a broker abstraction | v0.7.3 |
| phase 7d-b OrderManager | v0.7.4 |
| phase 7e Bybit Spot + composite | v0.7.5 |
| phase 7f broker resilience | v0.7.6 |
| phase 7g reconciliation + balances | v0.7.7 |
| phase 7h derivatives + rate limit | v0.7.8 |
| phase 8 strategy context + pnl attribution | v0.8.0 |
| phase 9 risk engine | v0.9.0 |
| phase 10 backtest reporting | v0.10.0 |
| phase 10b parameter sweep | v0.10.1 |

Intermediate maintenance merges (TradingView fixes, onTick/onCandle refactor) are not tagged — they are not user-facing milestones.
