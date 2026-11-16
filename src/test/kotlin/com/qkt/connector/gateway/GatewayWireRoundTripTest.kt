package com.qkt.connector.gateway

import org.junit.jupiter.api.Test

class GatewayWireRoundTripTest {
    private val cost = """{"kind":"fee","amount":"1","currency":"USD"}"""

    @Test
    fun `kill switch and health round trip every field`() {
        WireRoundTrip(WireKillSwitch.serializer(), """{"all":true,"symbols":["A"]}""", setOf("symbols")).verify()
        WireRoundTrip(
            WireHealth.serializer(),
            """{"protocol":"vgp1","adapter":"paper","adapter_version":"1","account_login":"7","trade_mode":"demo",
            "venue_connected":true,"kill_switch":{"all":true,"symbols":["A"]},"server_time":5,"stream":"s","seq":3,
            "capabilities":["funding"]}""",
            setOf("capabilities"),
        ).verify()
    }

    @Test
    fun `account instrument position and order objects round trip every field`() {
        WireRoundTrip(
            WireAccount.serializer(),
            """{"currency":"USD","balance":"1","equity":"2","margin_used":"3","margin_available":"4",
            "initial_margin":"5","maintenance_margin":"6"}""",
            setOf("initial_margin", "maintenance_margin"),
        ).verify()
        WireRoundTrip(
            WireInstrument.serializer(),
            """{"code":"C","kind":"option","currency":"USD","contract_size":"1","tick_size":"1","volume_step":"1",
            "volume_min":"1","expiry":9,"strike":"2","right":"call","underlying":"U","category":"x","trading":"long_only"}""",
            setOf("expiry", "strike", "right", "underlying", "category", "trading"),
        ).verify()
        WireRoundTrip(
            WirePosition.serializer(),
            """{"symbol":"S","quantity":"1","avg_price":"2","ticket":"t","opened_at":4}""",
            setOf("ticket", "opened_at"),
        ).verify()
        WireRoundTrip(
            WirePositions.serializer(),
            """{"accounting":"netting","positions":[{"symbol":"S","quantity":"1","avg_price":"2"}]}""",
        ).verify()
    }

    @Test
    fun `order and submit objects round trip every field`() {
        WireRoundTrip(
            WireOrder.serializer(),
            """{"client_order_id":"c","venue_order_id":"v","symbol":"S","side":"buy","type":"limit","quantity":"1",
            "limit_price":"2","stop_price":"3","time_in_force":"gtc","reduce_only":true,"status":"new",
            "filled_quantity":"1","avg_fill_price":"2","reject_reason":"r","created_at":1,"updated_at":2}""",
            setOf(
                "venue_order_id",
                "limit_price",
                "stop_price",
                "reduce_only",
                "filled_quantity",
                "avg_fill_price",
                "reject_reason",
            ),
        ).verify()
        WireRoundTrip(
            WireOrders.serializer(),
            """{"orders":[{"client_order_id":"c","symbol":"S","side":"buy","type":"market","quantity":"1",
            "time_in_force":"ioc","status":"new","created_at":1,"updated_at":2}]}""",
        ).verify()
        WireRoundTrip(
            WireSubmit.serializer(),
            """{"client_order_id":"c","symbol":"S","side":"buy","type":"limit","quantity":"1","limit_price":"2",
            "stop_price":"3","time_in_force":"gtc","reduce_only":true}""",
            setOf("limit_price", "stop_price", "reduce_only"),
        ).verify()
    }

    @Test
    fun `fill settlement deal event and error objects round trip every field`() {
        val fill =
            """{"client_order_id":"c","venue_order_id":"v","fill_id":"f","symbol":"S","side":"buy","quantity":"1",
            "price":"2","time":3,"costs":[$cost]}"""
        WireRoundTrip(WireCost.serializer(), cost).verify()
        WireRoundTrip(WireFill.serializer(), fill, setOf("venue_order_id", "costs")).verify()
        WireRoundTrip(WireDeals.serializer(), """{"deals":[$fill]}""").verify()
        WireRoundTrip(
            WireSettlement.serializer(),
            """{"symbol":"S","price":"1","time":2,"costs":[$cost]}""",
            setOf("costs"),
        ).verify()
        WireRoundTrip(
            WireSettlements.serializer(),
            """{"settlements":[{"symbol":"S","price":"1","time":2}]}""",
        ).verify()
        WireRoundTrip(
            WireEvent.serializer(),
            """{"stream":"s","seq":1,"type":"fill","time":2,"data":{"a":"b"}}""",
            setOf("time", "data"),
        ).verify()
        WireRoundTrip(WireError.serializer(), """{"code":"c","message":"m"}""").verify()
        WireRoundTrip(WireErrorBody.serializer(), """{"error":{"code":"c","message":"m"}}""").verify()
    }
}
