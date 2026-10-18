# Release proofs

What a testing commit must pass, offline, before it can be promoted to main. `run-attestation.sh` runs them in
its `proofs` stage, before it touches the demo account; run them by hand with:

```bash
bash scripts/release-proof/run.sh --cli build/install/qkt/bin/qkt --corpus <clone of qkt-data-corpus> --out /tmp/proofs \
    --previous <previous release>/bin/qkt --docs-data ~/.qkt/data
```

| Proof | What it proves |
|---|---|
| corpus | every corpus file's sha256 (and the vendor's own checksum where published) |
| data-e2e | every real-data case in `tests/data-e2e` |
| references | qkt reproduces backtesting.py and backtrader (`tests/references`) |
| audit | 48 scenarios: 991 checks recomputed without qkt's code, 39 relations between runs, fingerprints |
| compat | a store the previous release wrote reads, verifies, pins and backtests the same under this build |
| docs | the 12 docs examples, written out from the pages, run to the fingerprints in `docs/expected.json` |

A changed number fails until its fingerprint is rewritten with `--update` (audit, docs) after reading why.
`<out>/proofs.json` records the corpus commit, the CLI and each proof's result.
