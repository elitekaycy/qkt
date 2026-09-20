package com.qkt.cli.daemon.routes

import com.qkt.cli.daemon.StrategyRegistry
import com.sun.net.httpserver.HttpExchange

/**
 * `GET /reconcile/{name}` — the strategy's engine-vs-broker position and protection
 * deltas, or `supported:false` when its venue cannot reconcile.
 */
internal fun handleReconcile(
    ex: HttpExchange,
    registry: StrategyRegistry,
    name: String,
) {
    val handle = registry.get(name) ?: return respond(ex, 404, """{"error":"unknown name: $name"}""")
    val report =
        handle.live.reconcile()
            ?: return respond(ex, 200, """{"strategy":"$name","supported":false}""")
    val deltas =
        report.deltas.joinToString(",", "[", "]") {
            """{"symbol":"${it.symbol}","engineQty":"${it.engineQty.toPlainString()}",""" +
                """"brokerQty":"${it.brokerQty.toPlainString()}",""" +
                """"side":${it.side?.let { side -> "\"${side.name}\"" } ?: "null"}}"""
        }
    val protectionDeltas =
        report.protectionDeltas.joinToString(",", "[", "]") {
            """{"ticket":"${it.ticket}","symbol":"${it.symbol}",""" +
                """"requestedStopLoss":${jsonDecimal(it.requestedStopLoss)},""" +
                """"brokerStopLoss":${jsonDecimal(it.brokerStopLoss)},""" +
                """"requestedTakeProfit":${jsonDecimal(it.requestedTakeProfit)},""" +
                """"brokerTakeProfit":${jsonDecimal(it.brokerTakeProfit)}}"""
        }
    respond(
        ex,
        200,
        """{"strategy":"$name","clean":${report.clean},"deltas":$deltas,""" +
            """"protectionDeltas":$protectionDeltas,""" +
            """"brokerReadFailed":${report.brokerReadFailed},""" +
            """"brokerReadError":${jsonStringOrNull(report.brokerReadError)},""" +
            """"engineEquity":"${report.engineEquity.toPlainString()}",""" +
            """"brokerEquity":${report.brokerEquity?.let { "\"${it.toPlainString()}\"" } ?: "null"}}""",
    )
}
