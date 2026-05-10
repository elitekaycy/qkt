# Phase 20 — Quickstart + Docker compose + documentation plan

**Released:** 2026-05-10
**Version:** 0.22.0

## Summary

Phase 20 ships the operational handoff layer. Three deliverables: a top-level `QUICKSTART.md` that gets a new user from clone to a paper-traded strategy in five minutes, a full-stack `docker-compose.yml` that runs `qkt` + `mt5-gateway` side-by-side with example config files, and a written documentation plan covering three audiences (end-user trader, contributor, operator) with a concrete tooling stack (MkDocs Material + Dokka + Mermaid + GitHub Pages) and phased rollout. No code changes — this phase is the bridge from "the engine is built" to "someone else can use it."

## What's new

### Quickstart

- `QUICKSTART.md` at repo root. Five steps: install, write strategy, backtest, run as daemon (paper), run live via MT5. Includes patterns for multi-strategy load-dir, multi-account MT5, and portfolios.

### Docker compose stack

- `docker-compose.yml` at repo root. Spins up `mt5-gateway` (Wine + MT5 + Flask API on port 5001 + VNC on port 3000) and `qkt` daemon together. Healthcheck-gated dependency: qkt waits for gateway to be ready.
- `.env.example` — template for MT5 credentials and VNC password.
- `qkt.config.yaml.example` — annotated config showing how to override built-in `exness` defaults, extend with multiple accounts, or define a fresh broker. Uses `${QKT_EXNESS_URL:-...}` fallback syntax for the gateway URL so the same file works against both `localhost:5001` and `mt5-gateway:5001` (Docker network DNS).
- `strategies/` directory with a README — operators drop `.qkt` files in, compose mounts them at `/strategies` for auto-deploy.

### Documentation plan

- `docs/superpowers/specs/2026-05-10-documentation-plan-design.md` — full design covering:
  - Three audiences (end-user, contributor, operator) and what each reads
  - Information architecture using the [Diátaxis](https://diataxis.fr/) framework (tutorials / how-to / reference / explanation)
  - Detailed sitemap with every planned page
  - Tooling: MkDocs Material for prose, Dokka for API reference, Mermaid for diagrams, GitHub Pages for hosting, GitHub Actions for build/deploy
  - Authoring workflow that keeps docs current (PR discipline, auto-gen scripts, KDoc lint)
  - Examples + demos (`examples/strategies/`, asciinema GIFs, sample HTML reports)
  - Phased rollout: v1 ships infra + minimum content, v2 fills tutorials, v3 adds versioning and live playground

The plan is not yet executed — it's the spec for an upcoming Phase 21 candidate. Locked-in decisions documented in §9.

## Migration from Phase 19

**No code changes.** All additions are documentation + infra files at repo root.

**No DSL changes.**

## Usage cookbook

### Run the full stack

```sh
cp .env.example .env
# edit .env: MT5_LOGIN, MT5_PASSWORD, MT5_SERVER, VNC_PASSWORD
cp qkt.config.yaml.example qkt.config.yaml
docker compose up -d
# open VNC at localhost:3000, log in to MT5 GUI
docker compose exec qkt qkt brokers list
```

### Deploy a strategy

```sh
cat > strategies/eur.qkt <<'EOF'
STRATEGY eur VERSION 1
SYMBOLS
    eur = EXNESS:EURUSD EVERY 1m
RULES
    WHEN ema(eur, 9) crosses ABOVE ema(eur, 21)
    THEN BUY eur SIZING 0.01 BRACKET STOP_LOSS BY 30 PCT TAKE_PROFIT BY 60 PCT
EOF

# Either restart compose for auto-deploy, or hot-deploy:
docker compose exec qkt qkt deploy /strategies/eur.qkt --as eur
docker compose exec qkt qkt status eur
docker compose exec qkt qkt logs eur --follow
```

### Pre-launch tick audit

```sh
docker compose exec qkt qkt audit-ticks --symbol EURUSD --duration 300 --mt5-profile exness
```

### Tear down

```sh
docker compose down            # keeps state volume
docker compose down -v         # also wipes the volume
```

## Testing patterns

The `docker-compose.yml` itself isn't unit-tested. Validation is operational:

- `docker compose config` parses the file and prints the resolved spec — run as a CI smoke check.
- `docker compose up -d --dry-run` (recent compose versions) dry-runs the stack.

For full validation, the operator runs the stack manually against a demo MT5 account and confirms `qkt status` shows the strategy running.

## Known limitations

- **`mt5-gateway` image not auto-built.** The compose file references `mt5-gateway:latest` — operators must build the image separately from the upstream `mt5-gateway` repo. Future enhancement: a single `make` target that builds both images.
- **No CI smoke-test for compose.** `docker compose config` validation could run in CI but isn't wired yet.
- **VNC password in `.env` is plaintext.** Documented as a development convenience; production deployments should use a secrets manager (Docker secrets, Vault, AWS Secrets Manager).
- **Documentation site not yet built.** The plan is committed; execution is Phase 21+. Today, docs live as scattered markdown across `docs/`.
- **Strategy auto-deploy doesn't refresh on file change.** Compose mount is read-only; daemon loads at startup. To add a new strategy, either restart the qkt service or `docker compose exec qkt qkt deploy ...`.

## References

- Spec/plan: skipped per user direction — handoff scope was clear.
- Documentation plan spec: [`docs/superpowers/specs/2026-05-10-documentation-plan-design.md`](../superpowers/specs/2026-05-10-documentation-plan-design.md)
- Phase 17 (MT5 broker): [`docs/phases/phase-17.md`](phase-17.md)
- Phase 18 (LiveSession typed dispatch): [`docs/phases/phase-18.md`](phase-18.md)
- Phase 19 (pre-live confidence pack): [`docs/phases/phase-19.md`](phase-19.md)
- Existing Docker example: [`examples/docker/`](../../examples/docker/)
