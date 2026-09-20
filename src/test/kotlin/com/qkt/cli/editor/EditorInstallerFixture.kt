package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path

abstract class EditorInstallerFixture {
    /** Mirrors the bundled share/editor/ layout under [root]. */
    protected fun fakeEditorRoot(root: Path): Path {
        val nvim = root.resolve("nvim")
        Files.createDirectories(nvim.resolve("ftdetect"))
        Files.createDirectories(nvim.resolve("ftplugin"))
        Files.createDirectories(nvim.resolve("syntax"))
        Files.writeString(nvim.resolve("ftdetect/qkt.vim"), "au BufRead,BufNewFile *.qkt setfiletype qkt\n")
        Files.writeString(nvim.resolve("ftplugin/qkt.vim"), "setlocal commentstring=--\\ %s\n")
        Files.writeString(nvim.resolve("syntax/qkt.vim"), "syn keyword qktSection STRATEGY\n")

        val textmate = root.resolve("textmate")
        Files.createDirectories(textmate)
        Files.writeString(textmate.resolve("qkt.tmLanguage.json"), """{"scopeName":"source.qkt"}""")

        val vscode = root.resolve("vscode")
        Files.createDirectories(vscode)
        Files.writeString(vscode.resolve("package.json"), """{"name":"qkt","version":"0.1.0"}""")

        return root
    }

    protected fun detector(
        home: Path,
        codeBin: Path? = null,
    ): EditorDetector =
        EditorDetector(
            env = emptyMap(),
            home = home,
            osName = "linux",
            pathLookup = { name -> if (name == "code" && codeBin != null) codeBin else null },
        )
}
