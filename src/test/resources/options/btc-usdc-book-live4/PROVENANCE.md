# BTC_USDC live book, four snapshots — provenance

Four real Deribit book snapshots of every live BTC_USDC option (614 contracts each), taken anonymously
on 2026-10-01 between 06:15:16Z and 06:18:20Z, about a minute apart, with qkt itself:

```bash
qkt fetch DERIBIT:BTC_USDC --catalog
for i in 1 2 3 4; do qkt fetch DERIBIT:BTC_USDC --chains --live; sleep 60; done
```

The chain file holds the four snapshots unchanged (2456 rows). The catalog keeps the listings of those
614 contracts. `instruments.yaml` declares the root to trade the book series, with no fees.
`StructureBacktestTest` reads each fill's quote straight from this file.

| File | SHA-256 |
|---|---|
| `chains/DERIBIT/BTC_USDC/book/2026-10-01.csv.gz` | `fb5786f959d24d4c29d036d7bba9ed45d73c0f09d2664f81893226ab86adb527` |
| `contracts/DERIBIT/BTC_USDC.options.json` | `aa71cec2f7f21e0a17de216efa784017a2a7e38840e738c46412ba34a7a852b2` |
| `instruments.yaml` | `2dc2f7f9384db712d6483d9be345dd456ecc6df21f101c9f192101829f308e53` |
