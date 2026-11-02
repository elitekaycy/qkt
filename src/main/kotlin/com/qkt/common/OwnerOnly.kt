package com.qkt.common

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * Restricts a file or directory to its owner, best effort.
 *
 * On POSIX filesystems that is `rw-------` / `rwx------`. On Windows (NTFS) the ACL is replaced by a
 * entry granting the owner (and the running user) full control, so a state directory outside the user profile (say
 * `QKT_STATE_DIR=C:\qkt`, where `Users` inherit read access) still keeps tokens and journals
 * private. Any failure leaves the path as it was: these are hardening steps, never a reason to stop.
 */
object OwnerOnly {
    fun file(path: Path) {
        restrict(path, posix = "rw-------", inherit = false)
    }

    fun directory(path: Path) {
        restrict(path, posix = "rwx------", inherit = true)
    }

    private fun restrict(
        path: Path,
        posix: String,
        inherit: Boolean,
    ) {
        runCatching {
            val posixView = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
            if (posixView != null) {
                posixView.setPermissions(PosixFilePermissions.fromString(posix))
                return
            }
            val aclView = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return
            // An elevated process creates files owned by the Administrators group, so the running
            // user is granted access by name as well as the owner.
            val user =
                runCatching {
                    path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
                }.getOrNull()
            val principals = listOfNotNull(aclView.owner, user).distinct()
            aclView.acl =
                principals.map { principal ->
                    AclEntry
                        .newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(principal)
                        .setPermissions(AclEntryPermission.entries.toSet())
                        .apply {
                            if (inherit) setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                        }.build()
                }
        }
    }
}
