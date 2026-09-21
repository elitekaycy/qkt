# Unattended release on a VPS

`scripts/release-unattended.sh` takes a green `testing` branch to a tagged release with nobody at
the keyboard: live attestation → paper-soak → promotion PR → tag. This page is what the host needs.

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

The driver stops at once, without touching anything, when the version in `testing`'s `VERSION` is
already tagged — so the timer is safe to fire on days with nothing to release. A release happens
when a version bump has reached `testing`.

## Reading what happened

- `journalctl -u qkt-release` — one line per stage, and `STOPPED: <reason>` or `RELEASED vX.Y.Z`.
- `<wave_root>/attestation-run.json` — the attestation's current stage, machine-readable.
- `<wave_root>/attest-<sha>-catalog/result.json` — every case's verdict, which were retried and why.
- `<bundle_root>/build-<sha>/attestation.json` — the evidence paper-soak verified.

## What stops a release, by design

A failed case (after its one quiet retry); an order or position on the demo account that is not the
attestation's own; paper-soak red; the promotion PR not `CLEAN`, or moved off the attested commit
because something was merged to `dev` mid-release. None of these is retried: the next timer run
starts from the top.
