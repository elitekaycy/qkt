# Scale-Out Restart And Ownership Safety

## Problem

`OrderManager` persisted a ScaleOut basis as an ordinary leaf. A restart therefore lost the
wrapper that activates fractional exits. After activation, generated exits used the requested
basis quantity, omitted strategy attribution, and could submit an opposite market order on an MT5
hedging account instead of reducing the position opened by the basis fill.

## Contract

- Persist the pre-fill wrapper under the basis id and restore it before broker recovery.
- Size every exit from the cumulative quantity actually filled by the basis.
- Keep the active wrapper alongside all remaining exits so cancellation and restart preserve the
  original lifecycle.
- Carry the basis fill's venue position ticket through the engine-held trigger into its market
  close, with partial-close intent where the exit is smaller than the filled basis.
- Refuse to arm live multi-position exits when the basis fill has no owned venue ticket.
- Add no work to the steady tick path beyond reading metadata already attached to the symbol's
  live trigger; durability and broker submission occur only when lifecycle state changes.

## Evidence

- `OrderManagerScaleOutTest` covers partial-fill sizing, strategy/ticket ownership, fail-closed
  missing-ticket behavior, wrapper recovery, remaining-exit recovery, and cancellation after
  restart.
- `FileStatePersistorPendingOrdersTest` and the ScaleOut restart test cover durable request
  round-trips.
- `MT5BrokerIntegrationTest` covers the `closesTicket` partial-close gateway route.

## Limitations

- A process failure after a target fires but before its ticket-close outcome is observed remains
  governed by the broker's existing ambiguous close reconciliation contract.
- A venue that never produces a real partial entry cannot provide live evidence for cumulative
  partial-fill sizing; deterministic broker-event coverage proves that engine branch.
