package com.qkt.connector.gateway

import com.qkt.derivatives.options.chain.ChainRecorder
import com.qkt.derivatives.options.chain.ChainSnapshotStore
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.QuoteSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * The live chain history of a gateway account's option roots: one [ChainRecorder] per root fed live
 * that `instruments.yaml` declares with `chains: book`, appending its snapshots to the root's book
 * series every [cadenceMs], where `ChainView` (structures) and chain analytics read them. Appends run
 * on one writer thread, off the quotes socket; one that fails is logged and the next boundary tries
 * again. A root declared otherwise, or no options at all, records nothing.
 */
internal class GatewayChainRecording(
    private val instruments: InstrumentRegistry?,
    private val cadenceMs: Long,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(GatewayChainRecording::class.java)
    private val recorders = ConcurrentHashMap<String, ChainRecorder>()

    @Volatile private var closed = false
    private val writer: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "gateway-chain-writer").apply { isDaemon = true } }
    }

    /** Where root [root]'s (`DERIBIT:BTC_USDC`) quotes go to be recorded, or null when it keeps no book series. */
    fun sinkFor(root: String): ((WireQuote) -> Unit)? {
        val options = instruments?.options() ?: return null
        if (options.root(root)?.chains != QuoteSource.BOOK) return null
        val dataRoot = options.dataRoot ?: return null
        val store = ChainSnapshotStore(dataRoot, QuoteSource.BOOK)
        val recorder =
            recorders.computeIfAbsent(root) {
                ChainRecorder(root, cadenceMs, { expiries(root) }) { snapshot ->
                    try {
                        writer.execute {
                            runCatching { store.append(root, snapshot) }
                                .onFailure { log.error("chain snapshot of {} not written: {}", root, it.message) }
                        }
                    } catch (e: RejectedExecutionException) {
                        log.warn("chain snapshot of {} at {} dropped: recording closed", root, snapshot.atMs)
                    }
                }
            }
        return { quote -> if (!closed) gatewayChainQuote(quote)?.let(recorder::record) }
    }

    /** Root [root]'s catalogued contracts' expiries, asked per snapshot so a reloaded catalog counts. */
    private fun expiries(root: String): Map<String, Long> {
        val listings = instruments?.options()?.listings(root) ?: return emptyMap()
        return listings.mapValues { it.value.expiryMs }
    }

    /** Stops recording: later quotes are ignored, and appends already handed to the writer finish (up to 10 s). */
    override fun close() {
        closed = true
        if (recorders.isEmpty()) return
        writer.shutdown()
        if (!writer.awaitTermination(CLOSE_WAIT_S, TimeUnit.SECONDS)) log.warn("chain snapshots still writing at close")
    }

    private companion object {
        const val CLOSE_WAIT_S = 10L
    }
}
