# Run qkt in Docker

This example shows how to run the qkt daemon inside a container, mount a directory of `.qkt`
strategy files, and manage strategies via `docker exec`.

## 1. Build the base image

Either pull from ghcr.io:

```sh
docker pull ghcr.io/elitekaycy/qkt:0.13.0
```

Or build it locally from the qkt repo root:

```sh
./gradlew dockerBuild      # tags qkt:local
docker tag qkt:local ghcr.io/elitekaycy/qkt:0.13.0
```

## 2. Build your image

The `Dockerfile` in this directory extends the base image with your strategies:

```sh
docker build -t my-prop:0.1 .
```

## 3. Run the daemon

The base image's `ENTRYPOINT` is `qkt daemon --load-dir /strategies`. Each `.qkt` file in
`/strategies/` is auto-deployed at startup. The control plane binds an ephemeral port on
`127.0.0.1`; per-strategy observability ports are in the `40000-50000` range and exposed.

```sh
docker run -d --name qkt-prop \
    -p 47000-47100:47000-47100 \
    my-prop:0.1
```

## 4. Inspect what's running

```sh
docker exec qkt-prop qkt list
# NAME          UPTIME   PORT     TRADES   STATE
# sample        00:00:42 47291    0        running

docker exec qkt-prop qkt status sample
docker exec qkt-prop qkt logs sample -f
```

## 5. Deploy a new strategy at runtime

Copy the file in, then call `qkt deploy`:

```sh
docker cp ./new-strategy.qkt qkt-prop:/strategies/new-strategy.qkt
docker exec qkt-prop qkt deploy /strategies/new-strategy.qkt --as new-strat
```

## 6. Stop the daemon

```sh
docker exec qkt-prop qkt daemon stop
docker stop qkt-prop
```

## Volume mounts

For persistent log retention across container restarts, mount the state directory:

```sh
docker run -d --name qkt-prop \
    -v $(pwd)/qkt-state:/var/lib/qkt \
    -p 47000-47100:47000-47100 \
    my-prop:0.1
```

Logs land at `qkt-state/logs/<name>.log` on the host.
