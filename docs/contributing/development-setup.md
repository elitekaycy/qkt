# Development setup

## Prerequisites

- JDK 21 (Temurin recommended)
- Git
- For docs work: Python 3.12+ (for MkDocs)

## Clone + build

```bash
git clone https://github.com/elitekaycy/qkt.git
cd qkt
./gradlew build
```

`./gradlew build` runs all unit tests by default. End-to-end and live-broker tests are gated behind tags and excluded from normal builds.

## Common Gradle tasks

```bash
./gradlew build            # compile + unit tests + assemble
./gradlew test             # unit tests only
./gradlew ktlintCheck      # lint
./gradlew ktlintFormat     # auto-fix lint
./gradlew dokkaHtml        # build API reference (build/dokka/html/)
./gradlew run              # run the qkt CLI (use --args="...")

# Tagged tests
./gradlew test -PincludeTags=e2e
./gradlew test -PincludeTags=e2e-live    # requires real broker creds
./gradlew test -PincludeTags=dockerSmoke
```

## Pre-push checklist

The repo ships [`scripts/precheck.sh`](https://github.com/elitekaycy/qkt/blob/main/scripts/precheck.sh) — runs `./gradlew build`, ktlint, and a clean-tree check. Required green before every push.

```bash
./scripts/precheck.sh
```

## Docs preview

```bash
python3 -m venv .venv-docs
source .venv-docs/bin/activate
pip install -r requirements-docs.txt
mkdocs serve              # localhost:8000
```

For full local parity with the GitHub Actions deploy, also run `./gradlew dokkaHtml` and copy `build/dokka/html` to `site/api/` after `mkdocs build --strict`.

## IDE

- **IntelliJ IDEA** (Community is fine): import as a Gradle project, enable JDK 21, install ktlint plugin.
- **VS Code**: install the Kotlin extension and Gradle for Java extension. Less polished than IntelliJ for Kotlin but workable.

## Where things live

```
src/main/kotlin/com/qkt/        # production code (one package per domain)
src/test/kotlin/com/qkt/        # tests (mirrors prod tree)
docs/                           # MkDocs site
docs/superpowers/specs/         # design specs (one per feature)
docs/superpowers/plans/         # implementation plans
docs/phases/                    # phase changelogs (post-merge)
.claude/skills/qkt/             # project conventions skill
scripts/                        # tooling (precheck, etc)
```
