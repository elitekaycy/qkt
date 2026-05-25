# `qkt create template` — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `qkt create template <path> [--kind mt5|minimal]` scaffolds a working qkt project so a new operator goes from "I have qkt installed" to "I have a daemon running on docker-compose" in two commands.

**Architecture:** Templates live as classpath resources under `templates/<kind>/`. Each template's `MANIFEST` file lists every file to copy. `CreateCommand` reads the manifest, copies each entry to the target directory, and substitutes `{{TOKENS}}` (currently just `{{QKT_VERSION}}`).

**Tech stack:** Kotlin, classpath resources (no extra deps), JUnit 5 + AssertJ.

**Spec:** GitHub issue [#137](https://github.com/elitekaycy/qkt/issues/137).

**Working branch:** `issue137-create-template`. One PR into `dev` at the end.

---

## Scope decisions

| Decision | Choice |
|---|---|
| Kinds in this PR | `mt5` (default), `minimal` |
| Kinds deferred | `bybit` (needs real testnet to validate) — separate follow-up issue |
| Image tag pin | Compose uses `${QKT_IMAGE_TAG}`; generated `.env.example` defaults to current `BuildInfo.VERSION` (aligns with #141) |
| Default kind | `mt5` (the prod-ready full stack) |
| Existing-dir handling | Refuse if target exists and is non-empty; succeed if missing or empty |
| Token substitution | One token: `{{QKT_VERSION}}`. Substitution is text-replace, no Mustache/Handlebars dep |

---

## File structure

| File | Responsibility | Action |
|---|---|---|
| `src/main/kotlin/com/qkt/cli/CreateCommand.kt` | Subcommand entry, arg parsing, scaffolding. | Create |
| `src/main/kotlin/com/qkt/cli/TemplateScaffolder.kt` | Reusable copy logic: read MANIFEST, copy each entry with token sub. | Create |
| `src/main/kotlin/com/qkt/cli/Main.kt` | Add `"create"` to subcommand router. | Modify |
| `src/main/resources/templates/mt5/MANIFEST` | List of files in mt5 template. | Create |
| `src/main/resources/templates/mt5/qkt.config.yaml.tmpl` | Broker profiles (exness via mt5-gateway). | Create |
| `src/main/resources/templates/mt5/docker-compose.yml.tmpl` | qkt + mt5-gateway services. | Create |
| `src/main/resources/templates/mt5/.env.example.tmpl` | `QKT_IMAGE_TAG=v{{QKT_VERSION}}`, MT5 creds, VNC pwd. | Create |
| `src/main/resources/templates/mt5/Makefile.tmpl` | up, down, deploy STRAT=, audit-ticks SYMBOL=, logs. | Create |
| `src/main/resources/templates/mt5/strategies/ema_cross.qkt` | Sample strategy (verbatim from existing examples). | Create |
| `src/main/resources/templates/mt5/strategies/README.md` | "Drop .qkt files here; `make deploy STRAT=<name>`". | Create |
| `src/main/resources/templates/minimal/MANIFEST` | List of files in minimal template. | Create |
| `src/main/resources/templates/minimal/qkt.config.yaml.tmpl` | No brokers; paper-only. | Create |
| `src/main/resources/templates/minimal/docker-compose.yml.tmpl` | qkt only, no gateway. | Create |
| `src/main/resources/templates/minimal/.env.example.tmpl` | `QKT_IMAGE_TAG=v{{QKT_VERSION}}`. | Create |
| `src/main/resources/templates/minimal/Makefile.tmpl` | up, down, deploy, logs. | Create |
| `src/main/resources/templates/minimal/strategies/ema_cross.qkt` | Sample. | Create |
| `src/main/resources/templates/minimal/strategies/README.md` | As above. | Create |
| `src/test/kotlin/com/qkt/cli/CreateCommandTest.kt` | End-to-end test: invoke runMain, assert files. | Create |
| `docs/reference/cli-commands.md` | Add `create template` entry. | Modify |
| `docs/get-started/scaffold.md` | New tutorial-style page. | Create |
| `docs/get-started/index.md` | Link the new page. | Modify |
| `mkdocs.yml` | Add nav entry. | Modify |

The `.tmpl` extension on resource files exists to keep them inert in build tooling (so e.g. `processResources` doesn't try to interpolate them — Gradle's filter would replace `{{QKT_VERSION}}` itself). Scaffolder strips `.tmpl` when writing to target.

---

## Task 1: `TemplateScaffolder` (pure copy logic)

**File:** `src/main/kotlin/com/qkt/cli/TemplateScaffolder.kt`.

- [ ] Class `TemplateScaffolder(private val classLoader: ClassLoader = ...::class.java.classLoader)`.
- [ ] `fun scaffold(kind: String, target: Path, tokens: Map<String, String>): ScaffoldResult` where `ScaffoldResult` is a `sealed interface` of `Created(filesWritten: List<Path>) | Failed(reason: String)`.
- [ ] Read MANIFEST from `templates/$kind/MANIFEST` (one path per line, blank lines + `#` comments ignored).
- [ ] For each manifest entry: open the resource at `templates/$kind/<entry>`, read as bytes, perform `{{TOKEN}}` substitution on text files (heuristic: substitute when entry ends in `.tmpl`, `.yaml`, `.yml`, `.md`, `Makefile.tmpl`, `.qkt`, `.example.tmpl`), write to `target/<entry-without-.tmpl-suffix>`.
- [ ] Validate target: refuse if exists and non-empty; create if missing.

---

## Task 2: `CreateCommand`

**File:** `src/main/kotlin/com/qkt/cli/CreateCommand.kt`.

- [ ] Parses arg shape: `create template <path> [--kind <mt5|minimal>]`. Default kind = `mt5`.
- [ ] Constructs tokens map: `{"QKT_VERSION" -> BuildInfo.VERSION}`.
- [ ] Calls `TemplateScaffolder.scaffold(...)`. On success: print summary (`Created N files at <path>. Next: cd <path> && cp .env.example .env && make up`). Return `ExitCodes.SUCCESS`.
- [ ] On failure: print reason to stderr, return `ExitCodes.USER_ERROR`.

---

## Task 3: Wire into Main.kt

**File:** `src/main/kotlin/com/qkt/cli/Main.kt`.

- [ ] Add `"create" -> CreateCommand(args).run()` to the subcommand `when`.
- [ ] Add a line in `printHelp()` under STRATEGY AUTHORING (or a new SCAFFOLDING section): `create template <path>      scaffold a new qkt project`.

---

## Task 4: Author `mt5` template

Files under `src/main/resources/templates/mt5/`. Base the compose on the existing `../qkt-prod/compose.yml` pattern (QKT_IMAGE_TAG required, no fallback). The Makefile mirrors the operator commands the issue lists.

`Makefile.tmpl` (cheat sheet of targets):
```
.PHONY: up down deploy logs status audit-ticks shell
up:        ; docker compose up -d
down:      ; docker compose down
logs:      ; docker compose logs -f --tail 200 qkt
status:    ; docker compose exec qkt qkt status
deploy:    ; @test -n "$(STRAT)" && docker compose exec qkt qkt deploy /strategies/$(STRAT).qkt --as $(STRAT) || (echo "usage: make deploy STRAT=<name>"; exit 1)
audit-ticks: ; @test -n "$(SYMBOL)" && docker compose exec qkt qkt audit-ticks EXNESS:$(SYMBOL) --duration 5m --out /var/lib/qkt/audit-$(SYMBOL).json || (echo "usage: make audit-ticks SYMBOL=XAUUSD"; exit 1)
shell:     ; docker compose exec qkt sh
```

- [ ] Write all 7 files.
- [ ] MANIFEST lists each by relative path, in alphabetical order so test asserts deterministically.

---

## Task 5: Author `minimal` template

Same shape as `mt5` but no `mt5-gateway` service in compose; `qkt.config.yaml` has no broker profiles configured; `.env.example` only has `QKT_IMAGE_TAG`.

- [ ] Write all 6 files (no MT5-related ones).
- [ ] MANIFEST.

---

## Task 6: Tests

**File:** `src/test/kotlin/com/qkt/cli/CreateCommandTest.kt`.

- [ ] `mt5 scaffold writes the manifest files at target` — `@TempDir` Path, invoke `runMain(arrayOf("create", "template", tmp.toString()))` (default kind), assert every MT5 manifest file exists; assert `.env.example` contains `QKT_IMAGE_TAG=v` followed by `BuildInfo.VERSION`.
- [ ] `--kind minimal scaffolds the minimal template` — same but with `--kind minimal`. Assert MT5-specific files (gateway compose entries, MT5 env vars) are NOT present.
- [ ] `create on non-empty target dir errors out without overwriting` — write a file in tmpDir first, invoke create, assert exit code = USER_ERROR and existing file untouched.
- [ ] `unknown kind errors out` — `--kind notathing`, assert USER_ERROR with helpful message listing valid kinds.

---

## Task 7: Documentation

- [ ] `docs/reference/cli-commands.md` — add `create template` row with synopsis + flags.
- [ ] `docs/get-started/scaffold.md` — new walkthrough page: `qkt create template ./my-project --kind mt5` → `cp .env.example .env` → edit `.env` → `make up` → `make deploy STRAT=ema_cross` → `make logs`.
- [ ] `docs/get-started/index.md` — link the new page above the Deploy MT5 entry.
- [ ] `mkdocs.yml` — add `Scaffold a project: get-started/scaffold.md` to the Get started section.

---

## Acceptance

- `qkt create template /tmp/test-project` writes a complete `mt5` tree.
- `qkt create template /tmp/test-min --kind minimal` writes a `minimal` tree.
- Both write a `.env.example` that pins `QKT_IMAGE_TAG=v<current-VERSION>`.
- Test suite green; ktlint clean.
- Issue #137 acceptance:
    - ✓ `qkt create template ./my-project` writes a complete project tree.
    - ~ `cd my-project && cp .env.example .env && make up` — verifiable by operator, not by unit test.
    - ✓ Makefile target for `make audit-ticks SYMBOL=XAUUSD` wires `--out` flag from #54.

---

## Out of scope

- `bybit` template — needs real testnet API to validate; file follow-up.
- Interactive prompting (`qkt create template --interactive`) — not in v1.
- Template upgrade path (`qkt template upgrade`) — only scaffolds; upgrades are the operator's responsibility.
- Custom tokens beyond `{{QKT_VERSION}}` — add as needed in follow-ups.
