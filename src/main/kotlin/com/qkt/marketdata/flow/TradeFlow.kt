package com.qkt.marketdata.flow

import com.qkt.common.Side
import java.math.BigDecimal

/**
 * The two series a contract's public trade tape carries, each the capability a VGP gateway declares for it:
 * [TRADES], every print with its aggressor side, and [LIQUIDATIONS], the prints that liquidated a position with
 * the liquidation order's side. [dir] is where `qkt fetch --<[flag]>` stores the series under the data root.
 */
enum class FlowKind(
    val capability: String,
    val dir: String,
    val flag: String,
) {
    TRADES("trades", "tape", "tape"),
    LIQUIDATIONS("liquidations", "liquidations", "liquidations"),
}

/**
 * One print: [size] (in the contract's quantity) at [price] at [timeMs]. [side] is the aggressor's on the tape and
 * the liquidation order's among liquidations ([Side.SELL] closed a liquidated long). [id] is the venue's trade id.
 */
data class Print(
    val id: String,
    val timeMs: Long,
    val price: BigDecimal,
    val size: BigDecimal,
    val side: Side,
)

/** The volume one window of a series printed on each side: [buy] and [sell], zero when nothing printed. */
data class SideVolumes(
    val buy: BigDecimal,
    val sell: BigDecimal,
) {
    companion object {
        /** The sums of [prints], by side. */
        fun of(prints: Iterable<Print>): SideVolumes {
            var buy = BigDecimal.ZERO
            var sell = BigDecimal.ZERO
            for (p in prints) if (p.side == Side.BUY) buy += p.size else sell += p.size
            return SideVolumes(buy, sell)
        }
    }
}

/**
 * Where a strategy reads a contract's trade flow (`<alias>.buy_volume[1]`, ...): the sums of one closed window
 * of a series. Live, what the venue's gateway served for it; in a backtest, the stored tape.
 */
interface TradeFlow {
    /**
     * [symbol]'s [kind] summed over the window `[startMs, startMs + windowMs)`, or null while that window's prints
     * are not known (live: not yet read from the gateway).
     */
    fun window(
        symbol: String,
        kind: FlowKind,
        windowMs: Long,
        startMs: Long,
    ): SideVolumes?

    /** Why [symbol]'s [kind] cannot be read, naming what would serve it; null when it can. */
    fun problem(
        symbol: String,
        kind: FlowKind,
    ): String? = null
}

/** Where `qkt fetch --tape` and `--liquidations` read a contract's prints (a gateway's `/v1/trades`, `/v1/liquidations`). */
fun interface PrintHistorySource {
    /** [qktSymbol]'s [kind] prints with time in `[fromMs, toMs)`, oldest first. */
    fun prints(
        qktSymbol: String,
        kind: FlowKind,
        fromMs: Long,
        toMs: Long,
    ): List<Print>
}
