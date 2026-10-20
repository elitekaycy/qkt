package com.qkt.trade.session

import com.qkt.execution.Trade
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * A live session's own record, beside the order and audit journals the deploy pipeline writes:
 * `fills.jsonl` (every execution the engine booked, by identity) and, on finish, `summary.json`
 * (per identity: fills, realized P&L, and the legs still open).
 */
class LiveSessionRecord(
    private val dir: Path,
) {
    private val realized = LinkedHashMap<String, BigDecimal>()
    private val fills = LinkedHashMap<String, Int>()

    init {
        Files.createDirectories(dir)
    }

    @Synchronized
    fun fill(
        trade: Trade,
        realizedPnl: BigDecimal,
        identity: String,
    ) {
        realized[identity] = (realized[identity] ?: BigDecimal.ZERO) + realizedPnl
        fills[identity] = (fills[identity] ?: 0) + 1
        val line =
            """{"identity":"$identity","orderId":"${trade.orderId}","symbol":"${trade.symbol}",""" +
                """"side":"${trade.side}",""" +
                """"qty":${trade.quantity.toPlainString()},"price":${trade.price.toPlainString()},""" +
                """"realized":${realizedPnl.toPlainString()},"timeMs":${trade.timestamp}}"""
        val path = dir.resolve("fills.jsonl")
        Files.writeString(path, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    /** Writes `summary.json` for [identities] with their open [legs]. */
    @Synchronized
    fun summary(
        identities: Collection<String>,
        legs: List<SessionLeg>,
    ): Path {
        val rows =
            identities.joinToString(",", "[", "]") { id ->
                val pnl = (realized[id] ?: BigDecimal.ZERO).toPlainString()
                """{"identity":"$id","fills":${fills[id] ?: 0},"realized":$pnl,""" +
                    """"openLegs":${legs.filter { it.identity == id }.joinToString(",", "[", "]") { legJson(it) }}}"""
            }
        val path = dir.resolve("summary.json")
        Files.writeString(path, """{"identities":$rows}""" + "\n")
        return path
    }
}

/** Copies the session's journals from [sessionDir] into [outDir], the run's report directory. */
fun copySessionJournals(
    sessionDir: Path,
    outDir: Path,
) {
    Files.createDirectories(outDir)
    listOf(
        "intents.jsonl",
        "reads.jsonl",
        "fills.jsonl",
        "summary.json",
        "session.meta.json",
        LiveTickLog.FILE,
        VenueDealLog.FILE,
        SessionInstruments.FILE,
    ).forEach { name ->
        val from = sessionDir.resolve(name)
        if (Files.exists(from)) Files.copy(from, outDir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
    }
}
