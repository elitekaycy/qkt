package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditorInstallerHelixTest {
    private val repoEditorRoot: Path = Path.of("editor").toAbsolutePath()

    private fun installer(
        tmp: Path,
        compiler: Path? = null,
        commands: MutableList<List<String>> = mutableListOf(),
    ) = EditorInstaller(
        detector = EditorDetector(env = mapOf("XDG_CONFIG_HOME" to tmp.resolve("config").toString()), home = tmp),
        editorRoot = repoEditorRoot,
        manifestPath = tmp.resolve("manifest.json"),
        out = StringBuilder(),
        err = StringBuilder(),
        processRunner = { cmd, _ ->
            commands.add(cmd)
            Files.writeString(Path.of(cmd.last()), "compiled")
            0
        },
        pathLookup = { if (it == "cc") compiler else null },
    )

    @Test
    fun `helix install binds qkt files, the language server and the bundled grammar`(
        @TempDir tmp: Path,
    ) {
        val helix = tmp.resolve("config/helix")
        Files.createDirectories(helix)
        Files.writeString(helix.resolve("languages.toml"), "[[language]]\nname = \"rust\"\n")

        val result = installer(tmp).install(EditorTarget.HELIX)

        assertThat(result).isNotNull
        val languages = Files.readString(helix.resolve("languages.toml"))
        assertThat(languages).startsWith("[[language]]\nname = \"rust\"\n")
        assertThat(languages).contains("file-types = [\"qkt\"]", "args = [\"lsp\"]")
        assertThat(languages).doesNotContain("JAVA_TOOL_OPTIONS")
        assertThat(
            languages,
        ).contains("source = { path = \"${repoEditorRoot.resolve("tree-sitter-qkt").toString().replace("\\", "/")}\" }")
        assertThat(helix.resolve("runtime/queries/qkt/highlights.scm")).hasSameTextualContentAs(
            repoEditorRoot.resolve("tree-sitter-qkt/queries/highlights.scm"),
        )
    }

    @Test
    fun `reinstalling replaces the managed block instead of adding a second one`(
        @TempDir tmp: Path,
    ) {
        installer(tmp).install(EditorTarget.HELIX)
        installer(tmp).install(EditorTarget.HELIX)

        val languages = Files.readString(tmp.resolve("config/helix/languages.toml"))
        assertThat(languages.split("[language-server.qkt]")).hasSize(2)
    }

    @Test
    fun `with a C compiler the grammar is compiled into the helix runtime`(
        @TempDir tmp: Path,
    ) {
        val commands = mutableListOf<List<String>>()
        val result = installer(tmp, compiler = Path.of("/usr/bin/cc"), commands = commands).install(EditorTarget.HELIX)

        val so = tmp.resolve("config/helix/runtime/grammars/qkt.so")
        assertThat(result?.placedFiles).contains(so)
        assertThat(
            commands.single(),
        ).contains("-shared", repoEditorRoot.resolve("tree-sitter-qkt/src/parser.c").toString())
    }

    @Test
    fun `uninstall removes the placed files and only the managed block`(
        @TempDir tmp: Path,
    ) {
        val helix = tmp.resolve("config/helix")
        Files.createDirectories(helix)
        Files.writeString(helix.resolve("languages.toml"), "theme = \"onedark\"\n")
        installer(tmp).install(EditorTarget.HELIX)

        assertThat(installer(tmp).uninstall(EditorTarget.HELIX)).isTrue()

        assertThat(Files.readString(helix.resolve("languages.toml"))).isEqualTo("theme = \"onedark\"\n")
        assertThat(helix.resolve("runtime/queries/qkt/highlights.scm")).doesNotExist()
    }
}
