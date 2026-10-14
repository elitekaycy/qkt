# Install qkt

Pick one. All of them give you the same `qkt` command; check with `qkt --version`.

The GitHub release is the canonical stable distribution. A versioned image such as
`ghcr.io/elitekaycy/qkt:v0.56.1` is built from the same tag; `:latest`, `:dev` and `:edge` are moving
main, authoring and testing channels, not release pins.

## Linux (x64): self-contained binary

Each release ships a bundled runtime, so no system JDK is needed. Download `qkt-<version>-linux-x64.tar.gz`
from the [latest release](https://github.com/elitekaycy/qkt/releases/latest), then:

```bash
tar xzf qkt-*-linux-x64.tar.gz
export PATH="$PWD/qkt/bin:$PATH"
qkt --version
```

## Windows

```powershell
winget install elitekaycy.qkt
```

or the one-line installer, see [Install on Windows](../how-to/install-windows.md). Open a new terminal, then
`qkt --version`.

## Docker (no local Java)

A workbench with an editor built in. Your work is saved in `~/qkt-lab`:

```bash
mkdir -p ~/qkt-lab
docker run -dit --name qkt-dev -v ~/qkt-lab:/work ghcr.io/elitekaycy/qkt:dev
docker exec -it qkt-dev bash          # qkt + vim ready; then: qkt create template mystrat.qkt
```

The lean image that hosts your strategies live:

```bash
docker run -d --name qkt \
  -v "$(pwd)/strategies:/strategies" \
  ghcr.io/elitekaycy/qkt:latest
docker exec qkt qkt list
```

Full walkthrough: [Using qkt with Docker](../operations/running-qkt-via-docker.md).

## From source (any platform with JDK 21)

```bash
git clone https://github.com/elitekaycy/qkt.git && cd qkt
./gradlew installDist
./build/install/qkt/bin/qkt --version
```
