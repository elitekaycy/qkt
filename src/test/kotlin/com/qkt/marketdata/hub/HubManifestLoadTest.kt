package com.qkt.marketdata.hub

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** How `manifest.json` is read when it is partial, mistyped or not an object. */
class HubManifestLoadTest {
    private fun load(
        dir: Path,
        text: String?,
    ): HubManifest? {
        if (text != null) Files.writeString(dir.resolve("manifest.json"), text)
        return HubManifest.load(dir)
    }

    @Test
    fun `no manifest, unparseable text or a non-object is no manifest`(
        @TempDir dir: Path,
    ) {
        assertThat(load(dir, null)).isNull()
        assertThat(load(dir, "{not json")).isNull()
        assertThat(load(dir, "[1,2]")).isNull()
        assertThat(load(dir, "5")).isNull()
    }

    @Test
    fun `a manifest without an object of datasets holds none`(
        @TempDir dir: Path,
    ) {
        assertThat(load(dir, "{}")!!.datasets).isEmpty()
        assertThat(load(dir, """{"datasets":[]}""")!!.datasets).isEmpty()
        assertThat(load(dir, """{"datasets":"x"}""")!!.datasets).isEmpty()
    }

    @Test
    fun `a dataset entry that is not an object is skipped`(
        @TempDir dir: Path,
    ) {
        val manifest = load(dir, """{"datasets":{"a":5,"b":{"schema_hash":"h","scope_kind":"k"}}}""")!!

        assertThat(manifest.datasets.keys).containsExactly("b")
        val b = manifest.datasets.getValue("b")
        assertThat(b.schemaHash).isEqualTo("h")
        assertThat(b.scopeKind).isEqualTo("k")
        assertThat(b.fields).isEmpty()
    }

    @Test
    fun `a dataset missing its hash and scope reads them as empty`(
        @TempDir dir: Path,
    ) {
        val b = load(dir, """{"datasets":{"b":{}}}""")!!.datasets.getValue("b")

        assertThat(b.schemaHash).isEmpty()
        assertThat(b.scopeKind).isEmpty()
        val mistyped = load(dir, """{"datasets":{"b":{"schema_hash":{"x":1},"scope_kind":[1],"fields":"nope"}}}""")!!
        assertThat(mistyped.datasets.getValue("b").schemaHash).isEmpty()
        assertThat(mistyped.datasets.getValue("b").scopeKind).isEmpty()
        assertThat(mistyped.datasets.getValue("b").fields).isEmpty()
    }

    @Test
    fun `fields default their type and strategy flag and drop nameless entries`(
        @TempDir dir: Path,
    ) {
        val fields =
            load(
                dir,
                """{"datasets":{"b":{"fields":[
                |{"name":"a"},
                |{"name":"t","type":"text","strategy":false},
                |{"type":"int"},
                |{"name":{"x":1}},
                |{"name":"m","type":{"x":1},"strategy":"maybe"},
                |{"name":"s","type":"float","strategy":true}]}}}
                """.trimMargin(),
            )!!.datasets.getValue("b").fields

        assertThat(fields).containsExactly(
            HubField("a", "", true),
            HubField("t", "text", false),
            HubField("m", "", true),
            HubField("s", "float", true),
        )
    }
}
