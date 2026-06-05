package com.qkt.cli

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UserDirsTest {
    private val winEnv = mapOf("LOCALAPPDATA" to "/fake/Local", "APPDATA" to "/fake/Roaming")

    @Test
    fun `windows state home uses LOCALAPPDATA`() {
        val ud = UserDirs(osName = "windows 11", env = winEnv, home = Path.of("/fake/home"))
        assertThat(ud.isWindows).isTrue
        assertThat(ud.stateHome()).isEqualTo(Path.of("/fake/Local/qkt"))
    }

    @Test
    fun `windows config home uses APPDATA`() {
        val ud = UserDirs(osName = "windows 11", env = winEnv, home = Path.of("/fake/home"))
        assertThat(ud.configHome()).isEqualTo(Path.of("/fake/Roaming/qkt"))
    }

    @Test
    fun `windows falls back to home AppData when env vars absent`() {
        val ud = UserDirs(osName = "windows 10", env = emptyMap(), home = Path.of("/fake/home"))
        assertThat(ud.stateHome()).isEqualTo(Path.of("/fake/home/AppData/Local/qkt"))
        assertThat(ud.configHome()).isEqualTo(Path.of("/fake/home/AppData/Roaming/qkt"))
    }

    @Test
    fun `linux state home honors XDG_STATE_HOME`() {
        val ud = UserDirs(osName = "linux", env = mapOf("XDG_STATE_HOME" to "/x/state"), home = Path.of("/home/me"))
        assertThat(ud.isWindows).isFalse
        assertThat(ud.stateHome()).isEqualTo(Path.of("/x/state/qkt"))
    }

    @Test
    fun `linux state home falls back to dot-local-state`() {
        val ud = UserDirs(osName = "linux", env = emptyMap(), home = Path.of("/home/me"))
        assertThat(ud.stateHome()).isEqualTo(Path.of("/home/me/.local/state/qkt"))
    }

    @Test
    fun `linux config home honors XDG_CONFIG_HOME then falls back to dot-config`() {
        val withXdg = UserDirs(osName = "linux", env = mapOf("XDG_CONFIG_HOME" to "/x/cfg"), home = Path.of("/home/me"))
        assertThat(withXdg.configHome()).isEqualTo(Path.of("/x/cfg/qkt"))
        val noXdg = UserDirs(osName = "linux", env = emptyMap(), home = Path.of("/home/me"))
        assertThat(noXdg.configHome()).isEqualTo(Path.of("/home/me/.config/qkt"))
    }

    @Test
    fun `blank env values are ignored`() {
        val ud = UserDirs(osName = "windows 11", env = mapOf("LOCALAPPDATA" to "  "), home = Path.of("/fake/home"))
        assertThat(ud.stateHome()).isEqualTo(Path.of("/fake/home/AppData/Local/qkt"))
    }
}
