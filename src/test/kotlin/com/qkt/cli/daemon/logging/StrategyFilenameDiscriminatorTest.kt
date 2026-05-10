package com.qkt.cli.daemon.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyFilenameDiscriminatorTest {
    private val discriminator =
        StrategyFilenameDiscriminator().also { it.start() }

    private fun event(strategy: String?): ILoggingEvent {
        val mdc = if (strategy == null) emptyMap() else mapOf("strategy" to strategy)
        return object : ILoggingEvent {
            override fun getMDCPropertyMap(): Map<String, String> = mdc

            override fun getMdc(): Map<String, String> = mdc

            override fun getThreadName(): String = "test"

            override fun getLevel(): ch.qos.logback.classic.Level = ch.qos.logback.classic.Level.INFO

            override fun getMessage(): String = ""

            override fun getArgumentArray(): Array<Any?>? = null

            override fun getFormattedMessage(): String = ""

            override fun getLoggerName(): String = "test"

            override fun getLoggerContextVO(): ch.qos.logback.classic.spi.LoggerContextVO? = null

            override fun getThrowableProxy(): ch.qos.logback.classic.spi.IThrowableProxy? = null

            override fun getCallerData(): Array<StackTraceElement> = emptyArray()

            override fun hasCallerData(): Boolean = false

            override fun getMarker(): org.slf4j.Marker? = null

            override fun getMarkerList(): MutableList<org.slf4j.Marker>? = null

            override fun getKeyValuePairs(): MutableList<org.slf4j.event.KeyValuePair>? = null

            override fun getTimeStamp(): Long = 0L

            override fun getNanoseconds(): Int = 0

            override fun getSequenceNumber(): Long = 0

            override fun prepareForDeferredProcessing() {}
        }
    }

    @Test
    fun `slash strategy is substituted with double underscore`() {
        assertThat(discriminator.getDiscriminatingValue(event("mybook/trend")))
            .isEqualTo("mybook__trend")
    }

    @Test
    fun `top-level strategy is unchanged`() {
        assertThat(discriminator.getDiscriminatingValue(event("rsi-only")))
            .isEqualTo("rsi-only")
    }

    @Test
    fun `missing strategy falls back to default`() {
        assertThat(discriminator.getDiscriminatingValue(event(null)))
            .isEqualTo("main")
    }

    @Test
    fun `key is strategy_filename`() {
        assertThat(discriminator.key).isEqualTo("strategy_filename")
    }
}
