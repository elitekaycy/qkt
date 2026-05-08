package com.qkt.dsl.parse

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DslEntryTest {
    @Test
    fun `parse from string`() {
        val r = Dsl.parse("STRATEGY s VERSION 1") as ParseResult.Success
        assertThat(r.value.name).isEqualTo("s")
        assertThat(r.value.version).isEqualTo(1)
    }

    @Test
    fun `parseFile reads from path`() {
        val tmp = Files.createTempFile("qkt-dsl-", ".qkt")
        try {
            Files.writeString(tmp, "STRATEGY t VERSION 2")
            val r = Dsl.parseFile(tmp) as ParseResult.Success
            assertThat(r.value.name).isEqualTo("t")
            assertThat(r.value.version).isEqualTo(2)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `parse and parseFile produce same result`() {
        val src = "STRATEGY t VERSION 3\nSYMBOLS x = BACKTEST:Y EVERY 1m"
        val tmp = Files.createTempFile("qkt-dsl-", ".qkt")
        try {
            Files.writeString(tmp, src)
            val a = Dsl.parse(src) as ParseResult.Success
            val b = Dsl.parseFile(tmp) as ParseResult.Success
            assertThat(a.value).isEqualTo(b.value)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `parse returns Failure on syntax error`() {
        val r = Dsl.parse("STRATEGY x")
        assertThat(r).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `parse non-existent file throws IO`() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy { Dsl.parseFile(Path.of("/nonexistent/path/should-not-exist.qkt")) }
            .isInstanceOf(java.io.IOException::class.java)
    }
}
