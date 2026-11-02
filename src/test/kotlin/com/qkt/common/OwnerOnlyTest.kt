package com.qkt.common

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OwnerOnlyTest {
    @Test
    fun `a file is left readable by its owner alone`(
        @TempDir tmp: Path,
    ) {
        val file = Files.writeString(tmp.resolve("control.token"), "secret")

        OwnerOnly.file(file)

        assertOwnerOnly(file, "rw-------")
        assertThat(Files.readString(file)).isEqualTo("secret")
    }

    @Test
    fun `a directory is left to its owner and new files in it stay writable`(
        @TempDir tmp: Path,
    ) {
        val dir = Files.createDirectories(tmp.resolve("journal"))

        OwnerOnly.directory(dir)

        assertOwnerOnly(dir, "rwx------")
        Files.writeString(dir.resolve("audit.jsonl"), "{}")
        assertThat(Files.readString(dir.resolve("audit.jsonl"))).isEqualTo("{}")
    }

    @Test
    fun `a missing path is ignored`(
        @TempDir tmp: Path,
    ) {
        OwnerOnly.file(tmp.resolve("missing"))
    }

    private fun assertOwnerOnly(
        path: Path,
        posix: String,
    ) {
        val posixView = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posixView != null) {
            assertThat(PosixFilePermissions.toString(posixView.readAttributes().permissions())).isEqualTo(posix)
            return
        }
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)!!
        val owner = acl.owner
        val user = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
        assertThat(acl.acl).allSatisfy { entry ->
            assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW)
            assertThat(entry.principal()).isIn(owner, user)
        }
    }
}
