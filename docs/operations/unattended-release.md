# Unattended release on a VPS

`scripts/release-unattended.sh` takes a green commit of `testing` to a tagged release with nobody at
the keyboard: live attestation → paper-soak → promotion PR → tag. This page is what the host needs.

## A release names a candidate

Every merge into `dev` promotes `testing`, so the tip moves for as long as `dev` is open. A release therefore does not
follow the tip: it names a **candidate**, a commit already on `testing`, and everything is done for that commit.

```sh
scripts/release-unattended.sh --profile P --key-file K [--candidate SHA] [--no-carry]
```

`--candidate` defaults to the testing tip when the release starts (the old behaviour). It must be an ancestor of `testing`
(otherwise the release stops at once) and be green on `check`, `integration` and `docker`. From there:

1. The attestation runs on a checkout of the candidate.
2. `scripts/pin-release-branch.sh` creates or moves `release/vX.Y.Z` to the candidate (`X.Y.Z` is `VERSION` at the candidate).
   paper-soak and Windows validation are dispatched on that branch, so their `GITHUB_SHA` is the candidate whatever `testing` is by then.
3. `promote-to-main.yml -f candidate=SHA` verifies every gate for the candidate (CI, the paper-soak bundle with
   `--expected-git-sha <candidate>`, Windows validation, no existing tag) and opens the promotion PR from `release/vX.Y.Z`.
   An open PR for that branch is reused; a retry for a newer candidate moves the branch and the PR follows.
4. The PR merges only when `CLEAN` and with its head pinned to the candidate (`--match-head-commit`); the tag goes on the merge commit.

**`dev` stays open during an attestation.** Commits that reach `testing` afterwards are not part of the release and are
not promoted by it; they go in the next one. `release/v*` branches gate what paper-soak runs, so protect them like `testing`
(no force pushes except by the release token, no deletion).

The first release after this flow lands needs a candidate that contains the updated `paper-soak.yml` (a workflow runs as
it is at the dispatched ref).

## Starting over on a newer candidate

An attestation of candidate A that is cut short can be restarted on a newer candidate B without repeating every case,
under rules that cannot be argued with: see "Evidence carried between commits" in
[the attestation runbook](../contributing/live-parity-attestation.md). Needs `gateway_images=...` in the profile; without it
a new candidate starts from nothing, exactly as before.

## Auditing the carry

Carry is checked by real runs: schedule a full re-attestation of the testing head with `--no-carry` (for example weekly, a
second timer running `release-unattended.sh --no-carry`, or `run-attestation.sh --no-carry` without `--dispatch`). Every
case runs on the commit, and the bundle's catalog evidence says `carry.disabled: true`. A case that passes carried and fails
fresh means a path is missing from the deny-list in `lib/runtime_fingerprint.py`: fix the list.

## Why a timer and not a workflow job

The paper-soak workflow runs on the one self-hosted runner. A release job on that same runner
would hold it while waiting for paper-soak to finish — which can never start. The driver therefore
runs beside the runner, from a systemd timer, and talks to GitHub through `gh`.

## The host

- Docker, JDK 21, `gh`, `jq`, `git`, `python3` with `pyyaml`, `flock`.
- The MT5 gateway for a **demo** account on loopback (`http://127.0.0.1:5001`). The attestation
  refuses any other address and any account whose trade mode is not demo.
- The `qkt-paper-soak` self-hosted runner, online.
- `gh auth login` as a user who may approve workflow runs and review and merge the promotion PR.
- A dedicated clone for the driver (it checks out `testing`, detached): `/srv/qkt-release/qkt`.
- `/srv/qkt-release/attestation.profile` (copy `scripts/live-validation/attestation-profile.example`)
  and `/srv/qkt-release/gateway.key`, mode `0600`.

## The units

```ini
# /etc/systemd/system/qkt-release.service
[Unit]
Description=qkt unattended release (testing -> main -> tag)
[Service]
Type=oneshot
User=qkt
WorkingDirectory=/srv/qkt-release/qkt
ExecStart=/usr/bin/flock -n /srv/qkt-release/release.lock \
  scripts/release-unattended.sh --profile /srv/qkt-release/attestation.profile --key-file /srv/qkt-release/gateway.key
```

```ini
# /etc/systemd/system/qkt-release.timer
[Timer]
OnCalendar=Mon..Fri *-*-* 07,13:30:00 UTC
[Install]
WantedBy=timers.target
```

The driver stops at once, without touching anything, when the version in the candidate's `VERSION` is
already tagged — so the timer is safe to fire on days with nothing to release. A release happens
when a version bump has reached `testing`.

Market hours are not a start condition. Started outside the stocks or Alpaca window, the attestation runs every other
lane at once and waits, inside the same process, for the next window those lanes fit in (or holds the whole run until
shortly before it, when a weekend would age the earlier evidence past 24 hours). One run can therefore last up to the
profile's `session_wait_max_h` (default 72 hours): `Type=oneshot` has no start timeout, and the `flock -n` makes a
timer firing during the wait a no-op. The planned times are in the journal (`session plan:`, `holding the run until`).
Nothing outside the repository should retry or reschedule a session refusal: only a window beyond `session_wait_max_h`
still stops the run, and retrying that sooner cannot help.

## Reading what happened

- `journalctl -u qkt-release` — one line per stage, and `STOPPED: <reason>` or `RELEASED vX.Y.Z`.
- `<wave_root>/attestation-run.json` — the attestation's current stage, machine-readable.
- `<wave_root>/attest-<sha>-catalog/result.json` — every case's verdict, which were retried and why.
- `<bundle_root>/build-<sha>/attestation.json` — the evidence paper-soak verified.

## What stops a release, by design

A failed case (after its one quiet retry); an order or position on the demo account that is not the
attestation's own; paper-soak red; the promotion PR not `CLEAN` or not on the attested candidate; a candidate that is not an ancestor of `testing`.
Something merged to `dev` mid-release no longer stops a release. None of these is retried: the next timer run
starts from the top.
