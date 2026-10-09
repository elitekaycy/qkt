# Gold 1m/5m scalp TP consensus vs our $1.00 fixed target

Date: 2026-09-11. Question: does our $1.00 TP (100 pips, 0.01 lots) match
what actually works in retail XAUUSD scalping, or is bare-minimum-to-beat-
costs smaller? And what structural pieces are we missing?

## Findings (concrete thresholds)

- Retail gold scalp TP consensus: **5–15 pips ($0.50–$1.50)**, 20–40
  trades/session, 1m/5m/15m multi-TF. Our $1.00 sits mid-consensus.
- Typical R:R 1:1–1:2; one costed example: 5pt SL / 7.5pt TP (1:1.5) with
  explicit spread math: effective SL = SL + spread + slippage (8–9pt),
  effective TP = TP − spread (2–3pt net). Our books match: $1 TP nets
  $0.86, $2 SL costs $2.20–2.30.
- Session: London–New York overlap (our 13–15 UTC sits inside it).
- Multi-TF discipline: 15m trend context + 5m/1m entries. Counter-trend
  scalps framed as quick, small, with-trend-aware (sell pullback highs
  inside daily uptrend, tight SL above swing high, re-enter while valid).
- "What separates profitable mean-reversion from unprofitable is the
  regime filter, not the entry indicator" (fxroboteasy). Our v11 regime
  gate follows exactly this.
- ATR-based dynamic SL/TP is the standard answer to fixed levels dying
  across regimes (FMZ XAUUSD 1m: 14-ATR for SL/TP; EMA14/28 cross entries).
- Live reference (Myfxbook XAUUSD M5 EA, 279 trades, PF 1.55): 48% WR with
  avg win $62 / avg loss $38 (~1.6:1 payoff) — the opposite geometry to
  ours (their bigger targets + lower WR vs our $1 + 86% WR). Both viable;
  theirs needs trend legs, ours needs oscillation.
- Academic: optimal mean-reversion TP *falls* as stop-loss rises and both
  depend on transaction cost (Leung & Li 2014). Small TP + tight regime
  control is theoretically coherent, not just folk wisdom.

## Gaps in s02 vs literature

1. No higher-TF trend awareness. Every published counter-trend scalp
   frames entries inside daily bias. Our fades are trend-blind (v11 fired
   7/7 sells — shorts into a rallying tape worked, but unmonitored).
2. Absolute-$ levels (TP/SL/gates) vs ATR-relative. Literature standard is
   ATR multiples; ours died silently in 2026 ($3000+ gold). v13 must
   convert: TP/SL and all gates to ATR fractions.
3. No structured re-entry (literature re-enters while structure valid;
   ours only via slot freeing).

## Decision

- $1.00 TP validated (mid-consensus + our MFE median $1.02 + TP 0.70
  falsified live). Bare-minimum math: at 86% WR, TP $0.60 clears costs —
  but live test showed smaller TP shrinks EV/trade at same WR. Keep $1.00.
- Next build: v13 = ATR-relative conversion of all $-levels.

## Sources

- https://www.mql5.com/en/blogs/post/766579 (live counter-trend gold scalp diary)
- https://www.mql5.com/en/blogs/post/764883 (XAUUSD M1 channel scalp, TP 2–3 widths)
- https://www.mql5.com/en/blogs/post/767288 (Minting EA: EMA-structure + session + profit-first trailing)
- https://www.mql5.com/en/blogs/post/763678 (EMA+SMA200 5m gold pullback system)
- https://fazencapital.com/learn/en/scalping-xauusd-gold-trading-strategies (ORB, VWAP, 1:1–1:2, spread handling)
- https://www.myfxbook.com/strategies/xauusd-ea-m5/370485 (M5 EA 279 trades PF 1.55)
- https://ideas.repec.org/p/arx/papers/1411.5062.html (Leung & Li optimal MR with costs)
- https://www.fxroboteasy.com/guide/strategy/mean-reversion (regime filter > entry)
- Search excerpts: xs.com 2026 gold scalping guide (5–15 pip targets, 20–40 trades/session)
