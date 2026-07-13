# MT5 live/history feed audit implementation plan

1. Preserve millisecond MT5 timestamps and expose bounded raw tick-history reads.
2. Add a pure live/history comparator with exact timestamp and bid/ask reconciliation.
3. Add `--reference mt5-history` with a post-capture settlement delay and versioned JSON.
4. Update operator documentation and tests for pass, mismatch, and missing-history cases.
5. Run a read-only bot1 audit, retain the artifact, and record whether it is sufficient
   liquid-hours evidence to close #54.
