# Conventions

The full version lives in [`.claude/skills/qkt/SKILL.md`](https://github.com/elitekaycy/qkt/blob/main/.claude/skills/qkt/SKILL.md). Highlights below.

## Commits

**Format:** `<type>(<scope>): <subject>`

Subject only — no body, no footer, no AI attribution, no emoji.

| Type | Use for |
|---|---|
| `feat` | New user-visible behavior |
| `fix` | Bug in existing behavior |
| `refactor` | Internal restructuring, no behavior change |
| `docs` | Specs, plans, READMEs, comments |
| `test` | Test-only changes |
| `style` | Formatting (e.g. ktlint auto-format) |
| `build` | Gradle, dependencies |
| `chore` | Tooling, scripts, CI |
| `merge` | Merge commits |

**Scope:** the package — `engine`, `broker`, `dsl`, `risk`, `marketdata`, `execution`, `strategy`, `backtesting`, `app`, `common`. Plus non-source: `build`, `ci`, `docs`, `scripts`, `skill`. Drop the scope if a change spans more than two.

**Subject rules:** imperative, lowercase first word, no trailing period, ≤70 chars, describes what (not why — why goes in the PR).

## Branches

| Pattern | Use for |
|---|---|
| `phase<N>-<feature>` | Phase work (e.g. `phase21-docs-site`) |
| `fix-<short>` | Bug fixes |
| `refactor-<short>` | Refactors |

One concern per branch. Never commit directly to `main`.

## File size

Aim for **< 150 lines per source file**. Split when it grows. Tests may run ~10% over if splitting would break a tight test class.

## Naming

Names describe **what**, not **how** or **when**.

- `Tool` not `ZodValidator`
- `Handler` not `LegacyHandler`, `NewAPI`, `EnhancedHandler`
- `Registry` not `ToolRegistryManager`

If you're reaching for `new`, `old`, `legacy`, `wrapper`, `unified`, `enhanced` — stop and pick a better name.

## Comments

Default to none. Write one only when **why** is non-obvious — a hidden constraint, a workaround, an invariant. Never describe what the next line of code does. Never reference temporal context ("recently refactored", "moved from").

## Testing

- JUnit 5 + AssertJ. No mocking frameworks.
- Anonymous interface impls for fakes (`object : Strategy { ... }`).
- Test names are sentences in backticks: `` `fills MARKET order at tracker last price`() ``.
- Tests are deterministic. Inject `Clock`. Seed `Random`.
- Never delete a failing test without raising it first.

## PRs

Title: `[phase <N>] <type>(<scope>): <subject>`. Body uses the template in the SKILL — Phase, Summary, Changes, Tests, Backwards compat, Out of scope, Risk.

Merge style: `--no-ff` merge commits with subject `merge: phase <N> <feature>`. Never squash.
