package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `qkt dsl vocabulary` prints the DSL's names for tooling, as JSON or as a grouped listing. */
class DslCommandTest {
    private fun capture(vararg argv: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        val code =
            try {
                System.setOut(PrintStream(out))
                System.setErr(PrintStream(err))
                runMain(arrayOf(*argv))
            } finally {
                System.setOut(origOut)
                System.setErr(origErr)
            }
        return Triple(code, out.toString(), err.toString())
    }

    @Test
    fun `--json prints the qkt-vocabulary-v1 document`() {
        val (code, out, err) = capture("dsl", "vocabulary", "--json")

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(err).isEmpty()
        assertThat(out.trim()).startsWith("{\"schema\":\"qkt-vocabulary-v1\",\"keywords\":[")
        val json = Json.parseToJsonElement(out).jsonObject
        assertThat(json.keys).containsExactly(
            "schema",
            "keywords",
            "keywordCategories",
            "indicators",
            "functions",
            "constants",
            "streamFields",
            "metaFields",
            "seriesSelectors",
            "members",
            "shorthands",
        )
        val ema =
            json.getValue("indicators").jsonArray.map { it.jsonObject }.first {
                it.getValue("name").jsonPrimitive.content ==
                    "ema"
            }
        assertThat(ema.getValue("arity").jsonPrimitive.content).isEqualTo("2")
        assertThat(ema.getValue("signature").jsonPrimitive.content).isEqualTo("ema(value, period)")
        assertThat(ema.getValue("doc").jsonPrimitive.content).contains("Exponential moving average")
        val min =
            json.getValue("functions").jsonArray.map { it.jsonObject }.first {
                it.getValue("name").jsonPrimitive.content ==
                    "min"
            }
        assertThat(min.getValue("variadic").jsonPrimitive.content).isEqualTo("true")
        assertThat(
            json
                .getValue("members")
                .jsonObject
                .getValue("STREAK")
                .jsonArray
                .map { it.jsonPrimitive.content },
        ).containsExactly("banked", "losses", "wins")
    }

    @Test
    fun `the json is byte-identical across runs`() {
        assertThat(
            capture("dsl", "vocabulary", "--json").second,
        ).isEqualTo(capture("dsl", "vocabulary", "--json").second)
    }

    @Test
    fun `without --json it prints a grouped listing`() {
        val (code, out, _) = capture("dsl", "vocabulary")

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out)
            .contains("keywords (")
            .contains("POSITION members (")
            .contains(" ema ")
            .contains("WHEN")
    }

    @Test
    fun `an unknown dsl subcommand is an argument error`() {
        val (code, _, err) = capture("dsl", "grammar")

        assertThat(code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(err).contains("unknown dsl subcommand 'grammar'")
    }
}
