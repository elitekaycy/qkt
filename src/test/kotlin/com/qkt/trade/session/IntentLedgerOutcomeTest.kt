package com.qkt.trade.session

import com.qkt.common.Side
import com.qkt.execution.ExitReason
import com.qkt.strategy.OrderOutcome
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IntentLedgerOutcomeTest {
    private val changes = mutableListOf<IntentFate>()
    private val ledger = IntentLedger().also { it.onChange = { f -> changes.add(f) } }

    private fun filled(
        orderId: String,
        parent: String? = null,
        partial: Boolean = false,
        exit: ExitReason? = null,
    ) = OrderOutcome.Filled(
        orderId,
        "EXNESS:XAUUSD",
        Side.BUY,
        BigDecimal("2400"),
        BigDecimal("0.1"),
        partial = partial,
        exitReason = exit,
        timestamp = 9L,
        parentOrderId = parent,
    )

    private fun fired(id: String = "int-1") = ledger.fate(IntentFate(id, "brain", "fired", listOf("o1"), atMs = 1L))

    @Test
    fun `a fate never moves back but keeps adding the order ids it learns`() {
        fired()
        ledger.link("int-1", "o1")
        ledger.outcome("brain", filled("o1"))

        ledger.fate(IntentFate("int-1", "brain", "fired", listOf("o2"), atMs = 2L))

        val fate = ledger.fates().single()
        assertThat(fate.state).isEqualTo("filled")
        assertThat(fate.orderIds).containsExactly("o1", "o2")
    }

    @Test
    fun `compiled is sticky and marking it twice tells the journal once`() {
        ledger.fate(IntentFate("int-1", "brain", "queued"))
        ledger.markCompiled("int-1")
        ledger.markCompiled("int-1")
        ledger.markCompiled("unknown")
        val before = changes.size

        ledger.fate(IntentFate("int-1", "brain", "fired"))

        assertThat(ledger.fates().single().compiled).isTrue()
        assertThat(changes.count { it.compiled && it.state == "queued" }).isEqualTo(1)
        assertThat(changes.size).isEqualTo(before + 1)
    }

    @Test
    fun `linking an order of an unknown intent records no fate`() {
        ledger.link("int-9", "o9")

        assertThat(ledger.fates()).isEmpty()
        ledger.outcome("brain", OrderOutcome.Cancelled("o9", "user", 3L))
        assertThat(ledger.drain().single().intentId).isEqualTo("int-9")
        assertThat(ledger.fates()).isEmpty()
    }

    @Test
    fun `an exit filled under an intent's order is an event that does not settle the intent`() {
        fired()
        ledger.link("int-1", "o1")

        ledger.outcome("brain", filled("o1-sl", parent = "o1", exit = ExitReason.STOP))

        val event = ledger.drain().single()
        assertThat(event.intentId).isEqualTo("int-1")
        assertThat(event.exitReason).isEqualTo("STOP")
        assertThat(ledger.fates().single().state).isEqualTo("fired")
        assertThat(ledger.working().map { it.orderId }).containsExactly("o1")
    }

    @Test
    fun `an oco leg's parent resolves to the intent without its suffix`() {
        fired()
        ledger.link("int-1", "o1")

        ledger.outcome("brain", filled("leg-a", parent = "o1-oco"))

        assertThat(ledger.drain().single().intentId).isEqualTo("int-1")
    }

    @Test
    fun `a child order id resolves to the intent of its prefix`() {
        fired()
        ledger.link("int-1", "o1")

        ledger.outcome("brain", OrderOutcome.Rejected("o1-tp", "stops level", 4L))

        val event = ledger.drain().single()
        assertThat(event.intentId).isEqualTo("int-1")
        assertThat(event.kind).isEqualTo("rejected")
        assertThat(event.reason).isEqualTo("stops level")
        assertThat(event.price).isNull()
    }

    @Test
    fun `an order no intent placed is an event with no intent`() {
        ledger.outcome("brain", filled("engine"))

        val event = ledger.drain().single()
        assertThat(event.intentId).isNull()
        assertThat(event.side).isEqualTo("BUY")
        assertThat(event.quantity).isEqualByComparingTo("0.1")
    }

    @Test
    fun `a partial fill keeps the order working and settles the intent filled`() {
        fired()
        ledger.link("int-1", "o1")

        ledger.outcome("brain", filled("o1", partial = true))

        assertThat(ledger.working().map { it.orderId }).containsExactly("o1")
        assertThat(ledger.fates().single().state).isEqualTo("filled")
        assertThat(ledger.fates().single().atMs).isEqualTo(9L)
    }

    @Test
    fun `a cancelled order settles the intent cancelled with the reason`() {
        fired()
        ledger.link("int-1", "o1")

        ledger.outcome("brain", OrderOutcome.Cancelled("o1", "GTD expired", 5L))

        val fate = ledger.fates().single()
        assertThat(fate.state).isEqualTo("cancelled")
        assertThat(fate.reason).isEqualTo("GTD expired")
        assertThat(ledger.working()).isEmpty()
        assertThat(changes.last()).isEqualTo(fate)
    }

    @Test
    fun `a rejection ends every order of the intent`() {
        fired()
        ledger.link("int-1", "group")
        ledger.link("int-1", "entry")

        ledger.outcome("brain", OrderOutcome.Rejected("group", "notional", 6L))

        assertThat(ledger.working()).isEmpty()
        assertThat(ledger.fates().single().state).isEqualTo("rejected")
    }
}
