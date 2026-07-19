# Stop Ratchets Implementation Plan

**Issue:** #677

1. Extend `ChildBy`, tokens, and parser grammar while keeping the
   `ChildPriceAst` sealed membership unchanged.
2. Add validated `StopLossSpec` and `OrderRequest` variants and compiler mappings.
3. Wire bracket risk, fill anchoring, attached/fallback routing, and MT5 initial
   stop translation.
4. Add keyed runtime state for step cursors, elapsed intervals, stop levels, and
   favorable extremes; modify venue SL only on a tightening transition.
5. Extend the trailing-stop journal with backward-compatible state fields and
   restore all new runtime state.
6. Add focused parser, compiler, runtime, restart, end-to-end, modify-path, and
   parity tests.
7. Document the DSL and run targeted tests followed by the repository checks.

