package com.qkt.broker.mt5

import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent
import org.slf4j.LoggerFactory

class MT5StateRecovery(
    private val client: MT5Client,
    private val profile: MT5BrokerProfile,
    private val symbol: MT5Symbol,
    private val bus: EventBus,
) {
    private val log = LoggerFactory.getLogger(MT5StateRecovery::class.java)

    fun recover() {
        val positions = client.getPositions(magic = profile.magic)
        log.info("MT5 ${profile.name} state recovery: ${positions.size} open positions")
        for (p in positions) {
            val qktSymbol = symbol.toQkt(p.symbol)
            val signedQty = if (p.type == 0) p.volume else p.volume.negate()
            bus.publish(
                BrokerEvent.PositionReconciled(
                    symbol = qktSymbol,
                    oldQty = null,
                    newQty = signedQty,
                    oldAvgPx = null,
                    newAvgPx = p.priceOpen,
                    source = "mt5:${profile.name}",
                    reason = "startup-recovery",
                ),
            )
        }
    }
}
