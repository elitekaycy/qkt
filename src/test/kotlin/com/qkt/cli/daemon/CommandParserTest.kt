package com.qkt.cli.daemon

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CommandParserTest {
    private val parser = CommandParser

    @Test
    fun `status parses to Status`() {
        assertThat(parser.parse("/status")).isEqualTo(ControlCommand.Status)
    }

    @Test
    fun `halt with no arg parses to Halt All`() {
        assertThat(parser.parse("/halt")).isEqualTo(ControlCommand.Halt(Target.All))
    }

    @Test
    fun `halt with name parses to Halt Strategy`() {
        assertThat(parser.parse("/halt gold")).isEqualTo(ControlCommand.Halt(Target.Strategy("gold")))
    }

    @Test
    fun `resume with no arg parses to Resume All`() {
        assertThat(parser.parse("/resume")).isEqualTo(ControlCommand.Resume(Target.All))
    }

    @Test
    fun `resume with name parses to Resume Strategy`() {
        assertThat(parser.parse("/resume gold")).isEqualTo(ControlCommand.Resume(Target.Strategy("gold")))
    }

    @Test
    fun `help parses to Help`() {
        assertThat(parser.parse("/help")).isEqualTo(ControlCommand.Help)
    }

    @Test
    fun `command token is case-insensitive`() {
        assertThat(parser.parse("/HALT")).isEqualTo(ControlCommand.Halt(Target.All))
    }

    @Test
    fun `arg case is preserved`() {
        assertThat(parser.parse("/halt Gold")).isEqualTo(ControlCommand.Halt(Target.Strategy("Gold")))
    }

    @Test
    fun `extra whitespace between command and arg is ignored`() {
        assertThat(parser.parse("/halt   gold")).isEqualTo(ControlCommand.Halt(Target.Strategy("gold")))
    }

    @Test
    fun `empty string parses to Unknown with original raw`() {
        assertThat(parser.parse("")).isEqualTo(ControlCommand.Unknown(""))
    }

    @Test
    fun `blank string parses to Unknown with original raw`() {
        val raw = "   "
        assertThat(parser.parse(raw)).isEqualTo(ControlCommand.Unknown(raw))
    }

    @Test
    fun `unrecognised command parses to Unknown with original raw`() {
        assertThat(parser.parse("hello")).isEqualTo(ControlCommand.Unknown("hello"))
    }

    @Test
    fun `extra tokens past first arg are ignored`() {
        assertThat(parser.parse("/halt gold extra ignored")).isEqualTo(ControlCommand.Halt(Target.Strategy("gold")))
    }
}
