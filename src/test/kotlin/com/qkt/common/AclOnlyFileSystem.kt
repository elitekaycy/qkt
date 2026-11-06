package com.qkt.common

import java.lang.reflect.Proxy
import java.net.URI
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.WatchService
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.UserPrincipal
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider

// A file system that offers only an ACL view, standing in for Windows NTFS in OwnerOnly tests.

internal data class AclUser(
    private val id: String,
) : UserPrincipal {
    override fun getName() = id
}

internal class AclView(
    private val ownerUser: UserPrincipal,
) : AclFileAttributeView {
    var entries: List<AclEntry> = emptyList()

    override fun name() = "acl"

    override fun getAcl(): List<AclEntry> = entries

    override fun setAcl(acl: List<AclEntry>) {
        entries = acl
    }

    override fun getOwner() = ownerUser

    override fun setOwner(owner: UserPrincipal) = Unit
}

internal class AclFileSystem(
    val view: AclView?,
    val lookup: (String) -> UserPrincipal,
) : FileSystem() {
    val provider = AclProvider(this)

    override fun provider(): FileSystemProvider = provider

    override fun close() = Unit

    override fun isOpen() = true

    override fun isReadOnly() = false

    override fun getSeparator() = "/"

    override fun getRootDirectories(): Iterable<Path> = emptyList()

    override fun getFileStores(): Iterable<FileStore> = emptyList()

    override fun supportedFileAttributeViews() = setOf("acl")

    override fun getPath(
        first: String,
        vararg more: String,
    ): Path = throw UnsupportedOperationException()

    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher = throw UnsupportedOperationException()

    override fun getUserPrincipalLookupService() =
        object : UserPrincipalLookupService() {
            override fun lookupPrincipalByName(name: String) = lookup(name)

            override fun lookupPrincipalByGroupName(group: String): GroupPrincipal =
                throw UnsupportedOperationException()
        }

    override fun newWatchService(): WatchService = throw UnsupportedOperationException()

    fun path(): Path =
        Proxy.newProxyInstance(Path::class.java.classLoader, arrayOf(Path::class.java)) { proxy, method, args ->
            when (method.name) {
                "getFileSystem" -> this
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "acl:/state"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Path
}

internal class AclProvider(
    val fs: AclFileSystem,
) : FileSystemProvider() {
    override fun getScheme() = "acltest"

    override fun newFileSystem(
        uri: URI,
        env: Map<String, *>,
    ): FileSystem = fs

    override fun getFileSystem(uri: URI): FileSystem = fs

    override fun getPath(uri: URI): Path = fs.path()

    override fun newByteChannel(
        path: Path,
        options: Set<OpenOption>,
        vararg attrs: FileAttribute<*>,
    ): SeekableByteChannel = throw UnsupportedOperationException()

    override fun newDirectoryStream(
        dir: Path,
        filter: DirectoryStream.Filter<in Path>,
    ): DirectoryStream<Path> = throw UnsupportedOperationException()

    override fun createDirectory(
        dir: Path,
        vararg attrs: FileAttribute<*>,
    ) = Unit

    override fun delete(path: Path) = Unit

    override fun copy(
        source: Path,
        target: Path,
        vararg options: CopyOption,
    ) = Unit

    override fun move(
        source: Path,
        target: Path,
        vararg options: CopyOption,
    ) = Unit

    override fun isSameFile(
        path: Path,
        path2: Path,
    ) = path === path2

    override fun isHidden(path: Path) = false

    override fun getFileStore(path: Path): FileStore = throw UnsupportedOperationException()

    override fun checkAccess(
        path: Path,
        vararg modes: AccessMode,
    ) = Unit

    @Suppress("UNCHECKED_CAST")
    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption,
    ): V? = if (type == AclFileAttributeView::class.java) fs.view as V? else null

    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption,
    ): A = throw UnsupportedOperationException()

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption,
    ): Map<String, Any> = emptyMap()

    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any,
        vararg options: LinkOption,
    ) = Unit
}
