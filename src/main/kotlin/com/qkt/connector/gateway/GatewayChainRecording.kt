package com.qkt.connector.gateway

import com.qkt.derivatives.options.chain.ChainRecorder
import com.qkt.derivatives.options.chain.ChainSnapshotStore
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.QuoteSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
                ChainRecorder(root, cadenceMs, { options.listings(root).mapValues { it.value.expiryMs } }) { snapshot ->
                    writer.execute {
                        runCatching { store.append(root, snapshot) }
                            .onFailure { log.error("chain snapshot of {} not written: {}", root, it.message) }
                    }
                }
            }
        return { quote -> gatewayChainQuote(quote)?.let(recorder::record) }
    }

    /** Stops recording; appends already handed to the writer finish. */
    override fun close() {
        if (recorders.isNotEmpty()) writer.shutdown()
    }
}
