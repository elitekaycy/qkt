# KDoc style

qkt's public API is documented with KDoc — Kotlin's docstring format. Dokka renders KDoc into the [API reference site](/qkt/api/). This page is the style guide.

## Default

**Internal code: no KDoc by default.** A well-named method on an internal class doesn't need a docstring. Code that reads itself is better than code that repeats itself.

**Public surface: KDoc is required.** Anything that a strategy author, integrator, or new contributor can call needs at minimum a class-level paragraph and one-liner KDoc per public member.

The dividing line: would someone outside this package reasonably call this? If yes — KDoc it.

## Class-level KDoc

```kotlin
/**
 * Tracks the most recent price seen for each symbol on a [MarketSource].
 *
 * Brokers consult the tracker to price market orders; strategies read it to compute
 * indicator values that depend on the latest tick. The tracker is updated by exactly
 * one producer (the engine on tick ingest) and read by many consumers — expose it to
 * consumers as [MarketPriceProvider] so they can't accidentally write.
 */
class MarketPriceTracker { ... }
```

Structure:

1. **One-line summary** ending with a period. What is this type.
2. **Blank line.**
3. **Paragraph or two** of context: when to use it, who produces it, who consumes it, any invariants that aren't visible from the type signature.

Keep it short. The goal is to give a stranger enough to *judge* whether this is the type they want.

## Member KDoc

```kotlin
/**
 * Returns the latest price for [symbol], or `null` if no tick has been seen for it.
 *
 * Does not block, does not fetch — only reads the tracker's current state.
 */
fun priceOf(symbol: String): Double? = ...
```

Rules:

- **One-line summary** is mandatory.
- **`@param`** only when the parameter's role is non-obvious from its name. Don't write `@param symbol the symbol` — it adds nothing.
- **`@return`** only when the return value's meaning isn't already clear from the method name + return type. Document edge cases (null, empty, sentinel values).
- **`@throws`** for *documented* exceptions only — the ones a caller is expected to catch. Don't list every runtime exception that could theoretically propagate.
- **`@see`** when there's a directly related type the reader would benefit from jumping to.

## Don't do this

```kotlin
// BAD: temporal reference
/** Replaces the legacy ticker. Used to be called PriceCache. */

// BAD: implementation detail in the doc
/** Uses a ConcurrentHashMap internally for thread safety. */

// BAD: docstring just restating the signature
/**
 * @param symbol the symbol
 * @param price the price
 */
fun update(symbol: String, price: Double)

// BAD: redundant repetition of the name
/** The MarketPriceTracker class tracks market prices. */
class MarketPriceTracker

// BAD: future-tense or aspirational
/** Will eventually support multi-broker price aggregation. */
```

KDoc describes **what the code does right now**, in declarative present tense. It doesn't describe history, implementation choices, or roadmap. Those belong in PR descriptions, phase changelogs, or `docs/phases/`.

## `@sample` blocks

For frequently-instantiated types, link a `@sample`:

```kotlin
/**
 * ...
 *
 * @sample com.qkt.samples.brokerUsage
 */
```

Sample functions live under `src/samples/kotlin/com/qkt/samples/` and are picked up by Dokka. Each sample is a real top-level `fun` (must compile). Keep samples to the canonical usage pattern — not exhaustive variants.

## Where to put it

- Class/object/interface KDoc: directly above the declaration, no blank line between the doc and the class.
- Function/property KDoc: directly above the declaration.
- Constructor: KDoc the primary constructor inline with the class declaration if the constructor parameters are part of the public surface.
- Companion object: KDoc the companion if it's named or has public members; otherwise leave it bare.

```kotlin
/**
 * A broker that books fills against an in-memory ledger.
 *
 * The reference paper-trading implementation. Used in backtests and in `qkt run`/
 * `qkt daemon` deployments where no real venue is connected.
 *
 * @property bus where to publish [BrokerEvent]s after a fill
 * @property tracker the price source consulted for market-order fills
 */
class PaperBroker(
    private val bus: EventBus,
    private val tracker: MarketPriceProvider,
) : Broker { ... }
```

## Enforcement

- `./gradlew dokkaHtml` runs as part of CI. The `reportUndocumented = true` flag emits a warning per undocumented Tier 1 type — not a build failure, but visible.
- Reviewers check that any new public surface lands with KDoc.
- A KDoc-free PR that adds public surface is grounds for "please add KDoc before merge."

## Quick reference

| Need to document | Use |
|---|---|
| What a class is | Class-level KDoc paragraph |
| What a method returns | One-line summary; `@return` only if non-obvious |
| Why a parameter matters | `@param` only if non-obvious |
| Exceptions callers should catch | `@throws` |
| Related type | `@see` |
| Canonical usage | `@sample` to a function in `src/samples/kotlin` |
| Why something is a workaround | Inline comment in the method body, not KDoc |
| Roadmap | PR description or `docs/backlog.md` |
| What changed | Phase changelog at `docs/phases/` |
