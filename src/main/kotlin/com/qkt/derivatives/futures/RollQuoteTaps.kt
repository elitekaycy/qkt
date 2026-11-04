package com.qkt.derivatives.futures

import com.qkt.marketdata.Tick
import java.util.concurrent.ConcurrentHashMap

/**
 * The old contracts live continuous streams still need quotes of after moving off them, and the way those quotes reach
 * the roll. A stream's lane [hold]s the contract its roll closes out of while that roll is due or a leg on it is still
 * out (also after a restart past the roll instant, when the stream serves only the new contract), and [release]s it
 * once the roll is done. The stream's live feed reads the [held] contract beside the ones it follows and hands each of
 * its ticks to [hear], never to the strategy; [hear] passes it to the lane's [listen]er through [post] (live, the
 * engine thread). Thread-safe: the lane and the feed run on different threads.
 *
 * ```kotlin
 * val taps = RollQuoteTaps { action -> mailbox.postOnEngine(action) }
 * taps.listen("DERIBIT:BTC_USDC@front") { tick -> /* the lane hears the old contract's quote */ }
 * taps.hold("DERIBIT:BTC_USDC@front", "DERIBIT:BTC_USDC-5NOV26") // the feed now reads 5NOV26 too
 * ```
 */
class RollQuoteTaps(
    private val post: (() -> Unit) -> Unit = { it() },
) {
    private val held = ConcurrentHashMap<String, String>()
    private val listeners = ConcurrentHashMap<String, (Tick) -> Unit>()

    /** Stream [stream]'s roll needs quotes of [contract] (`VENUE:CODE`) from now on. */
    fun hold(
        stream: String,
        contract: String,
    ) {
        held[stream] = contract
    }

    /** Stream [stream]'s roll needs no old contract's quotes any more. */
    fun release(stream: String) {
        held.remove(stream)
    }

    /** The contract stream [stream]'s roll needs quotes of, or null. */
    fun held(stream: String): String? = held[stream]

    /** Hands every tick heard for [stream]'s held contract to [listener], through [post]. */
    fun listen(
        stream: String,
        listener: (Tick) -> Unit,
    ) {
        listeners[stream] = listener
    }

    /** A [tick] of the contract [stream] holds, for its lane; dropped when no lane listens. */
    fun hear(
        stream: String,
        tick: Tick,
    ) {
        val listener = listeners[stream] ?: return
        post { listener(tick) }
    }
}
