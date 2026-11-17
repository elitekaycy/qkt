package com.qkt.connector.gateway

import com.qkt.common.WireAsserts.decodes
import com.qkt.common.WireAsserts.refusesWithout
import com.qkt.common.WireAsserts.refusesWrongType
import com.qkt.common.WireAsserts.roundTrips
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class GatewayWireSerializationTest {
    private val kill = WireKillSwitch(all = true, symbols = listOf("A", "B"))
    private val cost = WireCost("fee", "0.1", "USD")

    @Test
    fun `kill switch round trips with and without symbols`() {
        roundTrips(kill)
        roundTrips(WireKillSwitch(all = false))
        decodes("""{"all":false}""", WireKillSwitch(all = false, symbols = emptyList()))
        refusesWithout<WireKillSwitch>("""{"all":true}""", "all")
        refusesWrongType<WireKillSwitch>("""{"all":true}""", "all")
    }

    @Test
    fun `health round trips and names a missing field`() {
        roundTrips(WireHealth("vgp1", "fake", "1", "7", "demo", true, kill, 5L, "s", 9L, listOf("quotes")))
        roundTrips(WireHealth("vgp1", "fake", "1", "7", "demo", false, WireKillSwitch(false), 5L, "s", 9L))
        val body =
            """{"protocol":"p","adapter":"a","adapter_version":"1","account_login":"7","trade_mode":"demo",
            |"venue_connected":true,"kill_switch":{"all":false},"server_time":1,"stream":"s","seq":2}
            """.trimMargin()
        decodes<WireHealth>(
            body,
            WireHealth("p", "a", "1", "7", "demo", true, WireKillSwitch(false), 1L, "s", 2L, emptyList()),
        )
        refusesWithout<WireHealth>(
            body,
            "protocol",
            "adapter",
            "adapter_version",
            "account_login",
            "trade_mode",
            "venue_connected",
            "kill_switch",
            "server_time",
            "stream",
            "seq",
        )
        refusesWrongType<WireHealth>(body, "protocol", "venue_connected", "server_time", "kill_switch", "capabilities")
    }

    @Test
    fun `account round trips with and without margins`() {
        roundTrips(WireAccount("USD", "1", "2", "3", "4", "5", "6"))
        roundTrips(WireAccount("USD", "1", "2", "3", "4"))
        val body = """{"currency":"USD","balance":"1","equity":"2","margin_used":"3","margin_available":"4"}"""
        decodes(body, WireAccount("USD", "1", "2", "3", "4", null, null))
        refusesWithout<WireAccount>(body, "currency", "balance", "equity", "margin_used", "margin_available")
        refusesWrongType<WireAccount>(body, "balance", "initial_margin", "maintenance_margin")
    }

    @Test
    fun `position and positions round trip with and without optional fields`() {
        val full = WirePosition("BTC", "1", "100", "t1", 5L)
        val bare = WirePosition("BTC", "1", "100")
        roundTrips(full)
        roundTrips(bare)
        roundTrips(WirePositions("netting", listOf(full, bare)))
        roundTrips(WirePositions("hedging", emptyList()))
        val body = """{"symbol":"BTC","quantity":"1","avg_price":"100"}"""
        decodes(body, bare)
        refusesWithout<WirePosition>(body, "symbol", "quantity", "avg_price")
        refusesWrongType<WirePosition>(body, "ticket", "opened_at")
        refusesWithout<WirePositions>("""{"accounting":"n","positions":[]}""", "accounting", "positions")
    }

    @Test
    fun `order round trips with every optional set and none`() {
        val full =
            WireOrder("c", "v", "BTC", "buy", "limit", "1", "9", "8", "gtc", true, "filled", "1", "9", "r", 1L, 2L)
        val bare =
            WireOrder("c", null, "BTC", "buy", "market", "1", null, null, "gtc", false, "new", "0", null, null, 1L, 2L)
        roundTrips(full)
        roundTrips(bare)
        roundTrips(WireOrders(listOf(full, bare)))
        val body =
            """{"client_order_id":"c","symbol":"BTC","side":"buy","type":"market","quantity":"1",
            |"time_in_force":"gtc","status":"new","created_at":1,"updated_at":2}
            """.trimMargin()
        decodes(body, bare)
        refusesWithout<WireOrder>(
            body,
            "client_order_id",
            "symbol",
            "side",
            "type",
            "quantity",
            "time_in_force",
            "status",
            "created_at",
            "updated_at",
        )
        refusesWrongType<WireOrder>(body, "venue_order_id", "reduce_only", "filled_quantity", "created_at")
        refusesWithout<WireOrders>("""{"orders":[]}""", "orders")
    }

    @Test
    fun `submit round trips with every optional set and none`() {
        roundTrips(WireSubmit("c", "BTC", "buy", "limit", "1", "9", "8", "gtc", true))
        roundTrips(WireSubmit("c", "BTC", "buy", "market", "1", timeInForce = "ioc"))
        val body =
            """{"client_order_id":"c","symbol":"BTC","side":"buy","type":"market","quantity":"1",""" +
                """"time_in_force":"ioc"}"""
        decodes(body, WireSubmit("c", "BTC", "buy", "market", "1", null, null, "ioc", false))
        refusesWithout<WireSubmit>(body, "client_order_id", "symbol", "side", "type", "quantity", "time_in_force")
        refusesWrongType<WireSubmit>(body, "limit_price", "stop_price", "reduce_only")
    }

    @Test
    fun `cost fill and deals round trip and name missing fields`() {
        roundTrips(cost)
        refusesWithout<WireCost>("""{"kind":"f","amount":"1","currency":"USD"}""", "kind", "amount", "currency")
        val full = WireFill("c", "v", "f1", "BTC", "buy", "1", "9", 5L, listOf(cost))
        val bare = WireFill("c", null, "f1", "BTC", "buy", "1", "9", 5L)
        roundTrips(full)
        roundTrips(bare)
        roundTrips(WireDeals(listOf(full, bare)))
        val body =
            """{"client_order_id":"c","fill_id":"f1","symbol":"BTC","side":"buy","quantity":"1",""" +
                """"price":"9","time":5}"""
        decodes(body, bare)
        refusesWithout<WireFill>(body, "client_order_id", "fill_id", "symbol", "side", "quantity", "price", "time")
        refusesWrongType<WireFill>(body, "venue_order_id", "costs", "time")
        refusesWithout<WireDeals>("""{"deals":[]}""", "deals")
    }

    @Test
    fun `settlement event and error shapes round trip`() {
        roundTrips(WireSettlement("O", "5", 1L, listOf(cost)))
        roundTrips(WireSettlement("O", "5", 1L))
        roundTrips(WireSettlements(listOf(WireSettlement("O", "5", 1L))))
        refusesWithout<WireSettlement>("""{"symbol":"O","price":"5","time":1}""", "symbol", "price", "time")
        refusesWithout<WireSettlements>("""{"settlements":[]}""", "settlements")
        val data = buildJsonObject { put("k", JsonPrimitive("v")) }
        roundTrips(WireEvent("s", 1L, "fill", 2L, data))
        roundTrips(WireEvent("s", 1L, "reset"))
        decodes("""{"stream":"s","seq":1,"type":"reset"}""", WireEvent("s", 1L, "reset", null, null))
        refusesWithout<WireEvent>("""{"stream":"s","seq":1,"type":"reset"}""", "stream", "seq", "type")
        refusesWrongType<WireEvent>("""{"stream":"s","seq":1,"type":"reset"}""", "time", "seq")
        roundTrips(WireErrorBody(WireError("bad", "no")))
        refusesWithout<WireError>("""{"code":"c","message":"m"}""", "code", "message")
        refusesWithout<WireErrorBody>("""{"error":{"code":"c","message":"m"}}""", "error")
    }
}
