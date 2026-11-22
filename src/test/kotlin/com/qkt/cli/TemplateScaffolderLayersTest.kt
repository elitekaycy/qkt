package com.qkt.cli

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TemplateScaffolderLayersTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `a later layer replaces a file of an earlier one and layers need no tokens`() {
        val root = tmp.resolve("classpath")
        mapOf(
            "templates/base/MANIFEST" to "a.txt\nb.txt\n",
            "templates/base/a.txt" to "base a",
            "templates/base/b.txt" to "base b",
            "templates/extra/MANIFEST" to "b.txt\n",
            "templates/extra/b.txt" to "extra b",
        ).forEach { (path, body) ->
            Files.createDirectories(root.resolve(path).parent)
            Files.writeString(root.resolve(path), body)
        }
        val out = tmp.resolve("out")

        val result =
            TemplateScaffolder(
                URLClassLoader(arrayOf(root.toUri().toURL()), null),
            ).scaffoldLayers(listOf("base", "extra"), out)

        assertThat(result).isInstanceOf(TemplateScaffolder.Result.Created::class.java)
        assertThat(Files.readString(out.resolve("a.txt"))).isEqualTo("base a")
        assertThat(Files.readString(out.resolve("b.txt"))).isEqualTo("extra b")
    }
}
