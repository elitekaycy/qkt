package com.qkt.cli

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TemplateScaffolderEdgesTest {
    @TempDir
    lateinit var tmp: Path

    private fun loaderWith(files: Map<String, String>): ClassLoader {
        val root = tmp.resolve("classpath")
        files.forEach { (path, body) ->
            val file = root.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, body)
        }
        return URLClassLoader(arrayOf(root.toUri().toURL()), null)
    }

    @Test
    fun `an unknown kind has no manifest`() {
        val result = TemplateScaffolder(loaderWith(emptyMap())).scaffold("nope", tmp.resolve("out"))

        assertThat(
            result,
        ).isEqualTo(
            TemplateScaffolder.Result.Failed("unknown template kind 'nope' (no MANIFEST at templates/nope/MANIFEST)"),
        )
    }

    @Test
    fun `a manifest naming a missing entry fails naming the resource`() {
        val loader = loaderWith(mapOf("templates/broken/MANIFEST" to "# files\n\nREADME.md\n"))

        val result = TemplateScaffolder(loader).scaffold("broken", tmp.resolve("out"))

        assertThat(
            result,
        ).isEqualTo(
            TemplateScaffolder.Result.Failed("template entry missing from classpath: templates/broken/README.md"),
        )
    }

    @Test
    fun `a template without tokens copies files and leaves placeholders in templated ones`() {
        val loader =
            loaderWith(
                mapOf(
                    "templates/mini/MANIFEST" to "a.txt\nb.txt.tmpl\n",
                    "templates/mini/a.txt" to "plain",
                    "templates/mini/b.txt.tmpl" to "hello {{NAME}}",
                ),
            )
        val out = tmp.resolve("out")

        val result = TemplateScaffolder(loader).scaffold("mini", out)

        assertThat(result).isInstanceOf(TemplateScaffolder.Result.Created::class.java)
        assertThat(Files.readString(out.resolve("a.txt"))).isEqualTo("plain")
        assertThat(Files.readString(out.resolve("b.txt"))).isEqualTo("hello {{NAME}}")
    }
}
