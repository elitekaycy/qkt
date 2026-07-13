package com.qkt.parity.mt5golden

import com.qkt.broker.mt5.MT5DefaultProfiles
import com.qkt.broker.mt5.MT5OrderTranslator
import com.qkt.broker.mt5.MT5Symbol
import com.qkt.broker.mt5.MT5Translation
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MT5GoldenVerifierTest {
    private val syntheticFixture =
        Path.of("src/test/resources/parity/mt5-golden-synthetic-schema-smoke.json")
    private val authenticFixture =
        Path.of("src/test/resources/parity/mt5-golden-exness-demo-2026-06-19.json")
    private val authenticSource =
        Path.of("src/test/resources/parity/mt5-golden-exness-demo-2026-06-19-source.json")

    @Test
    fun `authentic demo capture replays exactly and retains hashed source evidence`() {
        val fixture = MT5GoldenFixture.load(authenticFixture)

        val result = MT5GoldenVerifier.verify(fixture)

        assertThat(result.submissionsCompared).isEqualTo(1)
        assertThat(result.dealGroupsCompared).isEqualTo(1)
        assertThat(result.capturedVenueCost).isEqualByComparingTo("0")
        assertThat(fixture.provenance.sourceArtifactSha256).isEqualTo(sha256(authenticSource))
        val sourceText = Files.readString(authenticSource)
        assertThat(sourceText)
            .doesNotContain("\"login\"")
            .doesNotContain("\"balance\"")
        val source = Json.parseToJsonElement(sourceText).jsonObject
        val sourceOrder = source.getValue("historyOrder").jsonObject
        val sourceDeal = source.getValue("historyDeal").jsonObject
        assertThat(fixture.provenance.captureId).isEqualTo(source.getValue("captureId").jsonPrimitive.content)
        assertThat(fixture.venueOrders.single().brokerOrderId)
            .isEqualTo(sourceOrder.getValue("ticket").jsonPrimitive.content)
        assertThat(fixture.venueDeals.single().dealId)
            .isEqualTo(sourceDeal.getValue("ticket").jsonPrimitive.content)
        assertThat(fixture.venueDeals.single().price)
            .isEqualTo(sourceDeal.getValue("price").jsonPrimitive.content)
        assertThat(
            source
                .getValue("account")
                .jsonObject
                .getValue("tradeMode")
                .jsonPrimitive
                .content,
        ).isEqualTo("0")
        assertThat(
            source
                .getValue("tickQuery")
                .jsonObject
                .getValue("retainedTicksAroundFill")
                .jsonArray
                .map {
                    it.jsonObject
                        .getValue("ask")
                        .jsonPrimitive
                        .content
                },
        ).contains(fixture.venueDeals.single().price)
        val clientOrder = fixture.submissions.single().clientOrder
        val capturedRequest = fixture.submissions.single().venueRequest
        val profile = MT5DefaultProfiles.exness.copy(magic = capturedRequest.magic.toInt())
        val translated =
            MT5OrderTranslator(profile, MT5Symbol(profile.symbolPolicy))
                .translate(
                    OrderRequest.Market(
                        id = clientOrder.id,
                        symbol = clientOrder.symbol,
                        side = Side.valueOf(clientOrder.side),
                        quantity = decimal(clientOrder.quantity),
                        timeInForce = TimeInForce.valueOf(clientOrder.timeInForce),
                        timestamp = clientOrder.timestampMs,
                        strategyId = clientOrder.strategyId,
                    ),
                ) as MT5Translation.Single
        assertThat(translated.request.symbol).isEqualTo(capturedRequest.symbol)
        assertThat(translated.request.type).isEqualTo(capturedRequest.orderType)
        assertThat(translated.request.volume).isEqualByComparingTo(capturedRequest.volume)
        assertThat(translated.request.magic).isEqualTo(capturedRequest.magic)
        assertThat(translated.request.clientOrderId).isEqualTo(capturedRequest.clientOrderId)
    }

    @Test
    fun `synthetic fixture exercises the schema and replay verifier`() {
        val fixture = MT5GoldenFixture.load(syntheticFixture, requireAuthentic = false)

        val result = MT5GoldenVerifier.verify(fixture, requireAuthentic = false)

        assertThat(result.submissionsCompared).isEqualTo(2)
        assertThat(result.dealGroupsCompared).isEqualTo(2)
        assertThat(result.capturedVenueCost).isEqualByComparingTo("-0.70")
    }

    @Test
    fun `synthetic fixture cannot satisfy the authentic MT5 evidence gate`() {
        assertThatThrownBy { MT5GoldenFixture.load(syntheticFixture) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("authentic demo-session capture")
    }

    @Test
    fun `verifier rejects venue prices outside the declared tolerance`() {
        val fixture = MT5GoldenFixture.load(syntheticFixture, requireAuthentic = false)
        val drifted =
            fixture.copy(
                venueDeals =
                    fixture.venueDeals.map { deal ->
                        if (deal.clientOrderId == "market-buy-1") deal.copy(price = "1.10030") else deal
                    },
            )

        assertThatThrownBy { MT5GoldenVerifier.verify(drifted, requireAuthentic = false) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("market-buy-1: simulated price")
    }

    private fun sha256(path: Path): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
}
