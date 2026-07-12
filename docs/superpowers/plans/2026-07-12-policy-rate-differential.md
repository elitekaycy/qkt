# Policy-rate differential implementation plan

1. Extend macro storage with explicit availability timestamps and deterministic source
   provenance while preserving legacy FRED rows.
2. Parse the official RBA F1 and RBNZ B2 workbooks and provision both source series.
3. Derive the signed RBA-minus-RBNZ series point-in-time in one writer.
4. Close macro observations as immediate event candles and permit signed read-only data.
5. Route cataloged policy series through historical and live market-source construction.
6. Prove positive-entry and sign-flip flatten behavior with focused and full builds.
