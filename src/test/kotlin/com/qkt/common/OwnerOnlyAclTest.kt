package com.qkt.common

import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.UserPrincipalNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Covers the Windows (ACL, no POSIX) branch through a file system that only offers an ACL view. */
class OwnerOnlyAclTest {
    private val owner = AclUser("Administrators")

    @Test
    fun `a file's acl grants full control to the owner and the running user only`() {
        val fs = AclFileSystem(AclView(owner)) { AclUser(it) }
        OwnerOnly.file(fs.path())

        val entries = fs.view!!.entries
        assertThat(entries.map { it.principal() }).containsExactly(owner, AclUser(System.getProperty("user.name")))
        assertThat(entries).allSatisfy {
            assertThat(it.type()).isEqualTo(AclEntryType.ALLOW)
            assertThat(it.permissions()).isEqualTo(AclEntryPermission.entries.toSet())
            assertThat(it.flags()).isEmpty()
        }
    }

    @Test
    fun `a directory's acl entries are inherited by new children`() {
        val fs = AclFileSystem(AclView(owner)) { owner }
        OwnerOnly.directory(fs.path())

        val entry = fs.view!!.entries.single()
        assertThat(entry.principal()).isEqualTo(owner)
        assertThat(entry.flags()).containsExactlyInAnyOrder(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
    }

    @Test
    fun `an unknown running user leaves the owner as the only grantee`() {
        val fs = AclFileSystem(AclView(owner)) { throw UserPrincipalNotFoundException(it) }
        OwnerOnly.file(fs.path())

        assertThat(fs.view!!.entries.map { it.principal() }).containsExactly(owner)
    }

    @Test
    fun `a file system with neither posix nor acl views is left untouched`() {
        var looked = false
        val fs =
            AclFileSystem(null) {
                looked = true
                owner
            }
        OwnerOnly.directory(fs.path())

        assertThat(looked).isFalse
    }
}
