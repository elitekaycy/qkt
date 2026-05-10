package com.qkt.cli.daemon

import com.qkt.app.LiveSession
import com.qkt.app.LiveSessionHandle
import com.qkt.candles.TimeWindow
import com.qkt.cli.observe.EventRing
import com.qkt.cli.observe.ObservabilityServer
import com.qkt.cli.observe.PendingStackLayer
import com.qkt.cli.observe.PositionDto
import com.qkt.cli.observe.StatusSnapshot
import com.qkt.cli.observe.TradeDto
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.execution.Trade
import com.qkt.marketdata.source.MarketSource
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class StrategyHandle(
    val name: String,
    val ast: StrategyAst,
    val live: LiveSessionHandle,
    val observability: ObservabilityServer,
    val ring: EventRing,
    val logFile: Path,
    val startedAt: Instant,
    val childMeta: ChildMeta? = null,
) : AutoCloseable {

    data class ChildMeta(
        val parent: String,
        val alias: String,
        val hold: Boolean,
        val gateActive: java.util.concurrent.atomic.AtomicBoolean,
        val operatorStop: java.util.concurrent.atomic.AtomicBoolean,
    )
    val port: Int get() = observability.boundPort
    val tradeCount: Int get() = ring.size()

    fun isRunning(): Boolean = live.running

    override fun close() {
        live.stop()
        observability.close()
    }

    fun interface Factory {
        fun create(
            name: String,
            file: Path,
        ): StrategyHandle
    }

    class RealFactory(
        private val stateDir: StateDir,
        private val marketSourceProvider: (List<String>) -> MarketSource,
        private val candleHub: CandleHub? = null,
        private val ringSize: Int = 1000,
        private val bind: String = "127.0.0.1",
    ) : Factory {
        override fun create(
            name: String,
            file: Path,
        ): StrategyHandle {
            val ast =
                when (val parsed = Dsl.parseFile(file)) {
                    is ParseResult.Success -> parsed.value
                    is ParseResult.Failure -> {
                        val msg = parsed.errors.joinToString("\n") { e -> "${e.line}:${e.col} — ${e.message}" }
                        error("parse failure for $file:\n$msg")
                    }
                }
            val strategy = AstCompiler().compile(ast)
            val symbols = ast.streams.map { it.symbol }.distinct()
            val tvSymbols = ast.streams.map { "${it.broker}:${it.symbol}" }.distinct()
            val candleWindow: TimeWindow? =
                ast.streams
                    .firstOrNull()
                    ?.timeframe
                    ?.let { TimeWindow.parse(it) }

            val source = marketSourceProvider(tvSymbols)
            val ring = EventRing(capacity = ringSize)
            val startMs = System.currentTimeMillis()
            val startedAt = Instant.ofEpochMilli(startMs)

            val session =
                LiveSession(
                    strategies = listOf(ast.name to strategy),
                    source = source,
                    symbols = symbols,
                    candleWindow = candleWindow,
                    mdcStrategy = name,
                    candleHub = candleHub,
                    onTrade = { trade, realized, _ ->
                        org.slf4j.MDC.put("strategy", name)
                        try {
                            ring.append("trade", tradeToJson(trade, realized))
                        } finally {
                            org.slf4j.MDC.remove("strategy")
                        }
                    },
                    onSignal = { sig ->
                        org.slf4j.MDC.put("strategy", name)
                        try {
                            ring.append("signal", signalToJson(sig))
                        } finally {
                            org.slf4j.MDC.remove("strategy")
                        }
                    },
                ).start()

            val server =
                ObservabilityServer(
                    ring = ring,
                    statusProvider = {
                        val layers =
                            session.pendingStackLayerInfos().map {
                                PendingStackLayer(
                                    stackId = it.stackId,
                                    layer = it.layer,
                                    triggerPrice = it.triggerPrice,
                                    side = it.side,
                                    quantity = it.quantity,
                                )
                            }
                        buildSnapshot(
                            ast.name,
                            ast.version,
                            startMs,
                            startedAt.toString(),
                            session.recentTrades(),
                            layers,
                        )
                    },
                    running = { session.running },
                    onStop = { _ -> session.stop() },
                    bind = bind,
                    port = 0,
                ).also { it.start() }

            // Ensure log file exists (logback's SiftingAppender lazily creates it on first write,
            // but the daemon's API contract is that the file path is valid immediately).
            val logFile = stateDir.logFile(name)
            java.nio.file.Files
                .createDirectories(logFile.parent)
            if (!java.nio.file.Files
                    .exists(logFile)
            ) {
                java.nio.file.Files
                    .createFile(logFile)
            }

            return StrategyHandle(
                name = name,
                ast = ast,
                live = session,
                observability = server,
                ring = ring,
                logFile = logFile,
                startedAt = startedAt,
            )
        }

        private fun tradeToJson(
            trade: Trade,
            realized: BigDecimal,
        ) = buildJsonObject {
            put("timestamp", JsonPrimitive(Instant.ofEpochMilli(trade.timestamp).toString()))
            put("side", JsonPrimitive(trade.side.name))
            put("symbol", JsonPrimitive(trade.symbol))
            put("qty", JsonPrimitive(trade.quantity.toPlainString()))
            put("price", JsonPrimitive(trade.price.toPlainString()))
            put("realized", JsonPrimitive(realized.toPlainString()))
        }

        private fun signalToJson(sig: com.qkt.strategy.Signal) =
            buildJsonObject {
                when (sig) {
                    is com.qkt.strategy.Signal.Buy -> {
                        put("kind", JsonPrimitive("buy"))
                        put("symbol", JsonPrimitive(sig.symbol))
                        put("size", JsonPrimitive(sig.size.toPlainString()))
                    }
                    is com.qkt.strategy.Signal.Sell -> {
                        put("kind", JsonPrimitive("sell"))
                        put("symbol", JsonPrimitive(sig.symbol))
                        put("size", JsonPrimitive(sig.size.toPlainString()))
                    }
                    is com.qkt.strategy.Signal.Submit -> {
                        put("kind", JsonPrimitive("submit"))
                        put("symbol", JsonPrimitive(sig.request.symbol))
                        put("size", JsonPrimitive(sig.request.quantity.toPlainString()))
                    }
                    is com.qkt.strategy.Signal.CancelPendingForSymbol -> {
                        put("kind", JsonPrimitive("cancel_stacks"))
                        put("symbol", JsonPrimitive(sig.symbol))
                    }
                }
            }

        private fun buildSnapshot(
            strategyName: String,
            strategyVersion: Int,
            startMs: Long,
            startedAt: String,
            trades: List<Trade>,
            pendingStackLayers: List<PendingStackLayer> = emptyList(),
        ): StatusSnapshot {
            val now = System.currentTimeMillis()
            val last = trades.lastOrNull()
            return StatusSnapshot(
                strategy = strategyName,
                version = strategyVersion,
                uptimeMs = now - startMs,
                startedAt = startedAt,
                equity = BigDecimal.ZERO,
                balance = BigDecimal.ZERO,
                realized = BigDecimal.ZERO,
                unrealized = BigDecimal.ZERO,
                positions = emptyList<PositionDto>(),
                lastTrade =
                    last?.let {
                        TradeDto(
                            timestamp = Instant.ofEpochMilli(it.timestamp).toString(),
                            side = it.side.name,
                            symbol = it.symbol,
                            qty = it.quantity,
                            price = it.price,
                            realized = BigDecimal.ZERO,
                        )
                    },
                pendingStackLayers = pendingStackLayers,
            )
        }
    }
}
