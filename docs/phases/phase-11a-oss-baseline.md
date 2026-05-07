# Phase 11a — Open-Source Baseline

## Summary

Phase 11a closes the open-source posture gap on the qkt repo. After 22 phases of engine work, the repo had no LICENSE, no CI, a stale README from Phase 1, no Code of Conduct, no security policy, no release/versioning policy, and no tags. This phase ships all of those plus 22 backfill tags + GitHub Releases for every shipped phase.

This is meta-work, not engine work. No production source code changes. The deliverable is documentation, repo plumbing, CI, and a public release history.

## What's new

- `LICENSE` — Apache 2.0, copyright 2026 Dickson Anyaele.
- `README.md` — full rewrite. Reflects Phase 10b state (was stuck at Phase 1). Adds CI badge, feature list linking to phase changelogs, current source tree, links to all docs.
- `CODE_OF_CONDUCT.md` — Contributor Covenant 2.1 with maintainer email.
- `SECURITY.md` — vulnerability disclosure email + 7-day acknowledgment SLA + scope statement.
- `.github/workflows/check.yml` — GitHub Actions CI: `./gradlew check` on push to main and on PR.
- `.github/ISSUE_TEMPLATE/bug_report.md` — bug report template.
- `.github/ISSUE_TEMPLATE/feature_request.md` — feature request template.
- `.github/PULL_REQUEST_TEMPLATE.md` — PR template mirroring the qkt skill PR description format.
- `docs/release-process.md` — SemVer 0.x.y policy, tagging procedure, GitHub Release authoring, phase → version mapping table.
- `CONTRIBUTING.md` — adds a Releases section pointing to `docs/release-process.md`.
- 22 backfill tags pushed (`v0.1.0` through `v0.10.1`) covering every named phase merge.
- 22 GitHub Releases authored from phase changelogs (phases 7+) or merge commit subjects (phases 1-6).

## Migration from previous phase

None. Phase 11a is purely additive documentation + repo plumbing. No source code changes.

## Usage cookbook

### Cutting a release for a future phase

After a `merge: phase X ...` commit lands on main:

```bash
git checkout main && git pull
git tag -a v0.X.Y -m "phase X — <short description>"
git push origin v0.X.Y
gh release create v0.X.Y \
  --title "v0.X.Y — phase X <description>" \
  --notes-file docs/phases/phase-<N>-<topic>.md
```

Mark as "Latest release" on the GitHub UI if it is.

### Filing a bug report

Open <https://github.com/elitekaycy/qkt/issues/new?template=bug_report.md>. The template prompts for description, repro, expected vs actual, environment, and logs.

### Reporting a security issue

Email dicksonanyaele1234@gmail.com with "qkt security" in the subject. Do not open a public issue. Acknowledgment within 7 days; mitigation timeline within 14.

### Pinning to a specific phase

Consumers of qkt can pin to a tagged release in their own clone:

```bash
git clone https://github.com/elitekaycy/qkt.git
cd qkt
git checkout v0.10.0   # phase 10 backtest reporting only, no parameter sweep
./gradlew assemble
```

## Testing patterns

Phase 11a has no production tests. The CI workflow itself is the verification — `./gradlew check` running green on every push and PR is the durable signal that the engine is healthy. The pre-push precheck (`./scripts/precheck.sh`) runs the same gate locally.

## Known limitations

- **No publishing to Maven Central / JitPack / GitHub Packages.** Consumers clone + build. Publishing is a separate decision tree (group ID, signing, Sonatype account) and not justified until someone asks.
- **No release automation.** Tags pushed manually, releases authored manually via `gh release create`. A `release.yml` workflow is a future polish.
- **No docs site / GitHub Pages.** `docs/` in the repo is enough.
- **No top-level CHANGELOG.md aggregator.** `docs/phases/` is the authoritative timeline.
- **No retroactive changelog writing for phases 1-6.** Their tags use the merge commit subject as the release body.
- **No CLA, dual-licensing, or sponsorship setup.** Apache 2.0 is enough for v1.
- **No multi-OS / multi-JDK CI matrix.** Single Ubuntu + JDK 21 runner.

## References

- Spec: `docs/superpowers/specs/2026-05-07-trading-engine-phase11a-design.md`
- Plan: `docs/superpowers/plans/2026-05-07-trading-engine-phase11a.md`
- Merge commit: `3094fae`
