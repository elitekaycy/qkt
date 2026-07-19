# Exit Hooks Implementation Plan

Issue: #674

1. Add hook AST nodes, tokens, parser clauses, `EXIT.*`, hook-only
   `WITH`/`AGAINST` pending prices, and focused parser failures for nesting.
2. Add exit context evaluation and compile-time scope validation.
3. Compile hook definitions, attach definition references to emitted signals,
   and expose definition lookup/execution through `DslCompiledStrategy`.
4. Add optional exit reason metadata to broker fills. Use deterministic
   bracket/stack child ids in the shared engine and translate MT5 deal reasons
   for venue-attached SL/TP closes.
5. Add the pipeline-owned `ExitHookManager`, register risk-approved entries,
   track fills by id/ticket, and dispatch terminal exits through normal emit.
6. Persist and restore active bindings with definition fingerprints.
7. Add unit, restart, backtest, and PaperBroker parity coverage.
8. Document the DSL surface, run targeted tests, then run the full required
   pre-push checks.
