package com.qkt.research

import java.time.Instant

/**
 * Formats a [StepResult] into the text the REPL prints: one line per tape event,
 * any notice or reload diagnostics, then a one-line footer. Pure — returns a string,
 * does no IO.
 */
object TapeRenderer {
    /** Render the full block for one dispatched command (may be empty-tape + footer only). */
    fun render(result: StepResult): String {
        val lines = mutableListOf<String>()
        for (e in result.tape) lines.add(renderEvent(e))
        result.notice?.let { lines.add(it) }
        for (err in result.reloadErrors) lines.add("  ${err.line}:${err.col} — ${err.message}")
        lines.add(renderFooter(result.footer))
        return lines.joinToString("\n")
    }

    private fun renderEvent(e: TapeEvent): String =
        when (e) {
            is TapeEvent.SignalEmitted -> "  ${ts(e.timestamp)}  SIGNAL ${e.signal::class.simpleName}"
            is TapeEvent.Filled ->
                "  ${ts(e.timestamp)}  FILL ${e.trade.side} ${e.trade.quantity} @${e.trade.price}  pnl ${e.realized}"
            is TapeEvent.Rejected -> "  ${ts(e.timestamp)}  REJECT ${e.symbol} (${e.reason})"
        }

    private fun renderFooter(f: Footer): String {
        val pos =
            if (f.openPositions.isEmpty()) {
                "flat"
            } else {
                f.openPositions.entries.joinToString(" ") { (sym, p) -> "$sym ${p.quantity}" }
            }
        val end = if (f.exhausted) " [end]" else ""
        return "  ${ts(f.timestamp)}  bars ${f.barsClosed}  trades ${f.tradeCount}  " +
            "pos $pos  equity ${f.equity}$end"
    }

    private fun ts(millis: Long): String = Instant.ofEpochMilli(millis).toString()
}
