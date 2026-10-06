# Deribit option ticker with Greeks — provenance

One public answer each from Deribit testnet, recorded unchanged on 2026-10-05 (no account):

```bash
curl -s 'https://test.deribit.com/api/v2/public/ticker?instrument_name=BTC_USDC-30OCT26-110000-C' > ticker.json
curl -s 'https://test.deribit.com/api/v2/public/get_instrument?instrument_name=BTC_USDC-30OCT26-110000-C' > instrument.json
```

The gateway's Deribit adapter keeps the same ticker as `fixtures/ticker-option-greeks.json`.
`DeribitGreeksAgreementTest` prices the option from the ticker's `mark_iv` and `underlying_price` and checks
Deribit's published `greeks` agree.

| File | SHA-256 |
|---|---|
| `ticker.json` | `7af0e689d05d97a374f1a9203e05f0a4c4d2922b93e0bd09eb213cc564b286a2` |
| `instrument.json` | `0484399b596ce48ab245ffa491928cbef6864cea9842c1b57600f7f04d78d8e9` |
