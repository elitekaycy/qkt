# Install qkt on Windows

qkt ships a self-contained Windows build — `qkt.exe` plus a bundled Java runtime,
so there are no prerequisites to install.

## winget (recommended)

```powershell
winget install elitekaycy.qkt
```

## One-line installer

```powershell
irm https://raw.githubusercontent.com/elitekaycy/qkt/main/scripts/install.ps1 | iex
```

Pin a version or change the location:

```powershell
$env:QKT_VERSION='v0.29.14'; irm https://raw.githubusercontent.com/elitekaycy/qkt/main/scripts/install.ps1 | iex
```

Open a **new** terminal afterward (so the updated PATH takes effect), then:

```powershell
qkt --version
qkt --help
```

## Where qkt stores things on Windows

- State (daemon runtime, logs, persisted engine state): `%LOCALAPPDATA%\qkt`
- Config (`qkt.config.yaml`, editor manifest): `%APPDATA%\qkt`

## Live trading

The live daemon connects to an MT5 gateway over HTTP, exactly as on Linux — set
`gatewayUrl` in your `qkt.config.yaml`. The gateway is a separate service (it can
run natively against a MetaTrader 5 terminal on the same Windows machine). See the
live-trading guide for gateway setup.
