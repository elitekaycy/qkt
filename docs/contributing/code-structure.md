# Code structure

qkt is read far more often than it is written, usually by someone chasing a live-trading
question under time pressure. The structure rules below exist so that person can find the
code for "what happens to a bracket's stop when the broker partially fills the entry" by
following package and file names, without scrolling a 4,000-line file.

## Size limits

| What | Limit | Aim |
|---|---|---|
| Production source file (`src/main`) | 200 lines | under 150 |
| Test source file (`src/test`) | 220 lines | under 165 |
| Build script (`*.gradle.kts`, `build-logic/`) | 200 lines | under 150 |
| Function | not enforced | under 40 lines; over 60 is a smell |

`./gradlew checkFileSize` enforces the file limits and runs as part of `check`, so CI
fails on a violation. Files that were already over the limit when the rule landed are
listed in `config/file-size-baseline.txt` with their line count at that time. Those
entries form a ratchet:

- A baselined file may not grow. A fix that needs to add code to one first extracts the
  area it touches, then adds the fix to the extracted file.
- When a baselined file shrinks, the check asks you to lower its entry. Run
  `./gradlew updateFileSizeBaseline`, which lowers or drops entries and never raises one.
- Nobody edits the baseline upward. A PR that raises an entry is rejected in review.

## Model the domain

Packages and types are named after the real trading objects they represent, so the tree
reads like the domain.

- **Package by domain object, not by layer.** Everything about brackets belongs in one
  bracket package; there is no `helpers/` or `impl/` package that collects unrelated
  pieces.
- **A file is named for the one thing it holds.** A file called `BracketExitPlacer.kt`
  places bracket exits and nothing else. If you cannot name the file without "And",
  "Manager", "Helper", "Utils" or "Misc", it holds two things. Split it.
- **Split by the state a piece owns.** The seams of a large class are the groups of fields
  that change together: the live-order index, the bracket book, the trailing-stop state.
  Each group becomes a collaborator that owns those fields and the functions that mutate
  them. The original class keeps its public API and delegates.
- **Never split by position.** `OrderManagerPart2.kt`, or extension-function files that
  exist only to spread one class's private logic across several files, move lines
  without separating responsibilities. They are rejected in review.
- **Pure logic goes top-level in the owning package.** A function that computes a
  quantity from its arguments and touches no state does not need a class.
- **A sealed hierarchy may share a file** with its variants while it stays under the limit.

## How to refactor a large file

1. **List the responsibilities** by grouping fields and the functions that write them.
   Write the list down in the PR description.
2. **Extract one responsibility per commit.** Use a `refactor(<scope>):` subject. The
   commit moves code and adds delegation only: no behavior change, no renames the move
   does not need.
3. **Verify every step.** Run `./gradlew build`, which covers compile, the full test suite,
   ktlint and `checkFileSize`, then lower the baseline.
4. **Prove parity for engine-path code.** For extractions under `app/`, `execution/`,
   `broker/`, `positions/`, `risk/`, `backtest/` or `dsl/compile/`, run a fixed set of
   backtests with the pre-refactor jar and the new one, and diff the trade tape and metrics
   byte for byte. A difference means the refactor changed behavior. Revert it and find out
   why, never re-baseline the golden output.
5. **Keep hot-path cost unchanged.** Delegation through a `final` collaborator is free; a
   new per-event allocation, map lookup or iterator is not (see the qkt skill §9).

## Build scripts

The root `build.gradle.kts` declares plugins, dependencies and plugin settings only. Task
wiring lives in convention plugins under `build-logic/src/main/kotlin/`, one file per
concern (`qkt.distribution`, `qkt.testing`, `qkt.file-size`, ...). A new group of tasks
gets a new convention plugin, not a new block in the root script.
