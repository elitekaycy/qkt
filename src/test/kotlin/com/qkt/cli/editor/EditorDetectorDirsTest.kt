package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Where each editor keeps its files on Linux, macOS (`~/Library`) and Windows (`%AppData%`). */
class EditorDetectorDirsTest {
    @TempDir
    lateinit var home: Path

    private fun detector(
        vararg env: Pair<String, String>,
        onPath: Set<String> = emptySet(),
    ) = EditorDetector(env = mapOf(*env), home = home, pathLookup = { if (it in onPath) home.resolve(it) else null })

    private fun dir(relative: String): Path = Files.createDirectories(home.resolve(relative))

    @Test
    fun `linux JetBrains IDEs keep plugins under the data home, by default dot-local-share`() {
        dir(".config/JetBrains/IntelliJIdea2024.2")
        dir(".config/JetBrains/PyCharmCE2023.3")
        dir(".config/JetBrains/Toolbox")
        dir(".config/JetBrains/Fleet2025.1")

        assertThat(detector().jetBrainsIdes()).containsExactly(
            JetBrainsIde(
                "IntelliJIdea2024.2",
                "IU",
                2024,
                2,
                home.resolve(".local/share/JetBrains/IntelliJIdea2024.2"),
            ),
            JetBrainsIde("PyCharmCE2023.3", "PC", 2023, 3, home.resolve(".local/share/JetBrains/PyCharmCE2023.3")),
        )
        val data = home.resolve("data")
        assertThat(detector("XDG_DATA_HOME" to data.toString(), "APPDATA" to " ").jetBrainsIdes().map { it.pluginsDir })
            .containsExactly(data.resolve("JetBrains/IntelliJIdea2024.2"), data.resolve("JetBrains/PyCharmCE2023.3"))
        assertThat(detector("XDG_DATA_HOME" to "").jetBrainsIdes().first().pluginsDir)
            .isEqualTo(home.resolve(".local/share/JetBrains/IntelliJIdea2024.2"))
    }

    @Test
    fun `macOS and Windows JetBrains IDEs keep plugins inside the IDE's own directory`() {
        val mac = dir("Library/Application Support/JetBrains/GoLand2025.1")
        assertThat(detector().jetBrainsIdes())
            .containsExactly(JetBrainsIde("GoLand2025.1", "GO", 2025, 1, mac.resolve("plugins")))
        assertThat(detector().detect(EditorTarget.INTELLIJ)).isTrue()

        val appData = dir("AppData/Roaming")
        val windows = Files.createDirectories(appData.resolve("JetBrains/Rider2024.1"))
        assertThat(detector("APPDATA" to appData.toString()).jetBrainsIdes())
            .containsExactly(JetBrainsIde("Rider2024.1", "RD", 2024, 1, windows.resolve("plugins")))
    }

    @Test
    fun `no JetBrains directory means no IDEs, and a CLI on the path is still detection`() {
        assertThat(detector().jetBrainsIdes()).isEmpty()
        assertThat(detector().detect(EditorTarget.INTELLIJ)).isFalse()
        assertThat(detector(onPath = setOf("pycharm")).detect(EditorTarget.INTELLIJ)).isTrue()
    }

    @Test
    fun `sublime packages are under AppData, then Library, then the config home`() {
        assertThat(detector("XDG_CONFIG_HOME" to home.resolve("xdg").toString()).sublimePackagesDir())
            .isEqualTo(home.resolve("xdg/sublime-text/Packages"))
        assertThat(detector().sublimePackagesDir()).isEqualTo(home.resolve(".config/sublime-text/Packages"))
        assertThat(detector().detect(EditorTarget.SUBLIME)).isFalse()
        assertThat(detector(onPath = setOf("subl")).detect(EditorTarget.SUBLIME)).isTrue()

        dir("Library")
        val mac = home.resolve("Library/Application Support/Sublime Text/Packages")
        assertThat(detector().sublimePackagesDir()).isEqualTo(mac)
        Files.createDirectories(mac)
        assertThat(detector().detect(EditorTarget.SUBLIME)).isTrue()

        assertThat(detector("APPDATA" to home.resolve("roaming").toString()).sublimePackagesDir())
            .isEqualTo(home.resolve("roaming/Sublime Text/Packages"))
    }

    @Test
    fun `emacs uses dot-emacs-d unless only the config home has an emacs directory`() {
        assertThat(detector().emacsDir()).isEqualTo(home.resolve(".emacs.d"))
        assertThat(detector().detect(EditorTarget.EMACS)).isFalse()
        assertThat(detector(onPath = setOf("emacs")).detect(EditorTarget.EMACS)).isTrue()

        val xdg = dir(".config/emacs")
        assertThat(detector().emacsDir()).isEqualTo(xdg)
        assertThat(detector().emacsInitFile()).isEqualTo(xdg.resolve("init.el"))
        assertThat(detector().detect(EditorTarget.EMACS)).isTrue()

        dir(".emacs.d")
        assertThat(detector().emacsDir()).isEqualTo(home.resolve(".emacs.d"))
        val dotEmacs = Files.writeString(home.resolve(".emacs"), "; init\n")
        assertThat(detector().emacsInitFile()).isEqualTo(dotEmacs)
    }

    @Test
    fun `helix and zed are found by either binary name or by their config directory`() {
        assertThat(detector().detect(EditorTarget.HELIX)).isFalse()
        assertThat(detector().detect(EditorTarget.ZED)).isFalse()
        assertThat(detector(onPath = setOf("hx")).detect(EditorTarget.HELIX)).isTrue()
        assertThat(detector(onPath = setOf("helix")).detect(EditorTarget.HELIX)).isTrue()
        assertThat(detector(onPath = setOf("zed")).detect(EditorTarget.ZED)).isTrue()
        assertThat(detector(onPath = setOf("zeditor")).detect(EditorTarget.ZED)).isTrue()

        val appData = dir("roaming")
        Files.createDirectories(appData.resolve("helix"))
        Files.createDirectories(appData.resolve("zed"))
        val windows = detector("APPDATA" to appData.toString())
        assertThat(windows.helixConfigDir()).isEqualTo(appData.resolve("helix"))
        assertThat(windows.detect(EditorTarget.HELIX)).isTrue()
        assertThat(windows.detect(EditorTarget.ZED)).isTrue()
        assertThat(
            detector("APPDATA" to appData.toString(), "XDG_CONFIG_HOME" to home.resolve("x").toString()).zedConfigDir(),
        ).isEqualTo(home.resolve("x/zed"))
    }
}
