# Backing up engine state

The qkt daemon keeps persistent state — open positions, leg books, pending-order
records, the control port, and logs — under a single directory. In a Docker
deployment this is the `qkt-state` volume mounted at `/var/lib/qkt` (see
[Deploy with Docker](deploy-docker.md)); a bare-metal daemon uses its
`--state-dir`.

State survives container restarts. It does **not** survive the loss of the host
disk. If the host's storage fails, an un-backed-up state directory is gone.

## Why it matters

On restart the engine reconciles its persisted state against the broker's open
positions. If the state directory is intact, reconciliation is exact. If it is
lost, the engine can only rebuild what the **broker still reports** — any
qkt-side context the broker does not know (leg roles, OCO sibling links,
per-strategy attribution) is gone. A backup is the difference between a clean
recovery and a manual rebuild.

## What to back up

The whole state directory — for the Docker deployment, the `qkt-state` volume.
Nothing inside needs special handling; it is plain files. Excluding `logs/` is
fine (logs are not needed for recovery), but keeping them costs little and helps
post-incident analysis. When in doubt, back up the whole directory.

## Recommended cadence

State changes whenever an order or position changes. Match the cadence to how
often the deployed strategies trade:

- **Default — hourly.** A cron snapshot every hour covers any strategy without
  thought. State directories are small; an hourly copy is cheap.
- **Event-aligned.** If a strategy places orders in a known window (e.g.
  hedge-straddle's daily 19:55 UTC placement), add a snapshot a few minutes
  after that window so the most important state is captured promptly.

A backup at most one hour stale is enough: a stale backup plus broker
reconciliation recovers correctly as long as the broker still holds the
positions.

## Mechanism

Keep it simple — a host cron job that snapshots the volume and copies it
off-host. Read a named volume without stopping the container:

```bash
# Snapshot the qkt-state volume to a timestamped tarball.
docker run --rm -v qkt-state:/data:ro -v "$PWD":/out alpine \
    tar czf "/out/qkt-state-$(date -u +%Y%m%dT%H%M%SZ).tar.gz" -C /data .
```

Then ship the tarball off-host — object storage, or `rsync`/`scp` to another
machine. A bind-mount deployment can skip the `docker run` and copy the
directory directly.

Two rules:

- **Off-host.** A backup on the same disk as the original does not survive a
  disk failure. The copy must leave the host.
- **Retain a few.** Keep the last several snapshots (e.g. 48 hourly — two days)
  so a corrupted state directory does not immediately overwrite the last good
  copy.

The snapshot read is consistent enough in practice: state files are small and
written atomically; a copy taken mid-write at worst loses the last write, which
reconciliation then recovers from the broker.

## Restoring

1. Stop the daemon / container.
2. Replace the state directory (or volume contents) with the unpacked backup.
3. Start the daemon. It reconciles the restored state against the broker on
   boot — check the startup logs for reconcile warnings.

For the Docker volume:

```bash
docker compose stop qkt
docker run --rm -v qkt-state:/data -v "$PWD":/in alpine \
    sh -c 'rm -rf /data/* && tar xzf /in/qkt-state-<timestamp>.tar.gz -C /data'
docker compose start qkt
```

## Decision

Back up the **whole state directory, hourly, off-host**, keeping roughly two
days of snapshots. Wiring the actual cron job on a given host is a per-deployment
operational step — this page is the policy it should follow.
