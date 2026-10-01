# Phase 43.0: options pricing core — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A deterministic, dependency-free pricing library for European options: the normal
distribution, Black–Scholes (spot with a rate and carry) and Black-76 (options on futures/forwards),
their Greeks, and an implied-volatility solver that never returns a fabricated number.

**Architecture:** `derivatives/options/pricing` holds pure functions over `Double` (closed forms;
money stays `BigDecimal` at the boundaries that call them). `NormalDistribution` implements the CDF by
Abramowitz & Stegun 26.2.17 (|error| < 7.5e-8) and the PDF exactly. `BlackScholes` and `Black76`
return an `OptionValue(price, delta, gamma, vega, theta, rho)`; `ImpliedVolatility` solves by Newton
on vega with a bisection fallback inside `[1e-4, 5.0]` and returns null outside no-arbitrage bounds or
without convergence. Time is years = `(expiryMs − nowMs) / (365 × 24 × 3600 × 1000)`.

**Spec:** `docs/superpowers/specs/2026-09-30-futures-options-design.md` (§6.2, §11 E20)

## Global constraints

- No change outside `derivatives/options/pricing` and its tests; CFD pins unaffected by construction.
- Files ≤ 200 lines; KDoc on public API with the formula and its source; commits via the helper.
- Every published reference value is asserted to its published precision.

## Review focus

- Expiry and zero volatility (T → 0, σ → 0): intrinsic value, no NaN, no division by zero.
- Deep in/out of the money: CDF tails stay in [0, 1]; put–call parity holds to 1e-9.
- IV for a price below intrinsic or above the upper bound → null; for a model price → the input σ to 1e-6.
- Negative or zero inputs (price, strike, time) refused with a named reason.

---

### Task 1: NormalDistribution
- `cdf(x)` (A&S 26.2.17, symmetric for x < 0), `pdf(x)`.
- Tests: N(0)=0.5, N(1.96)=0.9750021, N(−1)=0.1586553 to 1e-7; symmetry; tails at ±10 in [0, 1].

### Task 2: Black–Scholes and Black-76 with Greeks
- `BlackScholes.value(right, spot, strike, years, rate, volatility, carry = rate)`; `Black76.value(right, forward, strike, years, rate, volatility)`; `OptionValue`; `OptionRight { CALL, PUT }`.
- Greeks per unit (vega per 1.00 of σ, theta per year, rho per 1.00 of rate), documented.
- Expiry or zero volatility → discounted intrinsic, delta ±1/0, other Greeks 0.
- Tests: Hull 15.6 (S 42, K 40, r 10%, σ 20%, T 0.5: c 4.76, p 0.81); Hull Greeks example (S 49, K 50, r 5%, σ 20%, T 0.3846: c 2.40, Δ 0.522, Γ 0.066, vega 12.1, θ −4.31/yr, ρ 8.91); Black-76 put on futures (F 20, K 20, r 9%, σ 25%, T 4/12: p 1.12); parity for both models.

### Task 3: ImpliedVolatility
- `solve(right, target, price-function inputs)` for both models: bounds check (intrinsic ≤ price < upper bound), Newton from 0.5 with vega guard, bisection fallback, tolerance 1e-8 on price, 100 iterations; null otherwise.
- Tests: round-trip σ ∈ {0.05, 0.2, 0.8, 2.0} across moneyness; below-intrinsic and above-bound → null; near-expiry deep OTM (vega ≈ 0) converges by bisection or returns null, never 0.
