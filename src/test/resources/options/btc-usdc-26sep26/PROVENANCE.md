# BTC_USDC 26SEP26 option fixture — provenance

Real Deribit data, fetched anonymously on 2026-10-01 with qkt itself:

```bash
qkt fetch DERIBIT:BTC_USDC --catalog
qkt fetch DERIBIT:BTC_USDC --chains --from 2026-09-25 --to 2026-09-26
```

Trimmed to two contracts that expired at 2026-09-26T08:00:00Z: `BTC_USDC-26SEP26-84000-C` (in the
money at the 84042.83 delivery price) and `BTC_USDC-26SEP26-85000-C` (out of the money). The chain
files keep every hourly row of those two contracts unchanged (header included); the catalog keeps
their listings and the 2026-09-26 `btc_usdc` delivery price from `public/get_delivery_prices`.
`instruments.yaml` declares the root with Deribit's linear option fees (research note §8) and a 5%
mark spread for the trade-built chain.

| File | Records | SHA-256 |
|---|---|---|
| `chains/DERIBIT/BTC_USDC/trade/2026-09-25.csv.gz` | 41 | `98c8a64ed43e4df806c06334fea7e0579f0b3b8cfd0d4211d742a2f486210899` |
| `chains/DERIBIT/BTC_USDC/trade/2026-09-26.csv.gz` | 16 | `cc6be813e5b8c97d665fd4260521b0c77a4e96bad08d6ca163e69c1c445aab27` |
| `contracts/DERIBIT/BTC_USDC.options.json` | 2 | `63bc490a7b9716a50c12bfdcf7ada5204a48136456e53755a04cae78cdca801a` |
