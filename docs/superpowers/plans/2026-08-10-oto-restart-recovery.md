# OTO Restart Recovery

## Problem

An OTO parent was persisted as an atomic pending order while its unarmed children existed
only in `OrderManager.pendingChildren`. If the parent filled while qkt was down, broker
recovery replayed the fill without rebuilding that map, so no child was submitted.

## Design

While an OTO parent remains live, `pending-orders.json` stores the complete OTO wrapper
under the atomic parent's client order id. Startup restores the wrapper, parent, children,
and parent-to-child activation map before handing the parent to broker recovery. A replayed
fill therefore activates the same children as an uninterrupted fill. Once the parent is
terminal, ordinary snapshots retain only children that have actually been activated.

The venue-bound intent write remains synchronous and fail-closed. It includes the OTO
wrapper before the parent submission, so acceptance cannot precede durable activation state.

## Hot-Path Cost

There is no per-tick work. Persistence snapshots run on order-state mutations and add one
scan over currently unfilled OTO parents, `O(active OTO parents)`. JSON and disk work keep
using the existing persistence worker, except for the existing synchronous pre-submission
intent barrier.
