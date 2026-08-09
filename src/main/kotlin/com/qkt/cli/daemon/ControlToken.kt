package com.qkt.cli.daemon

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

/** Bearer credential and its operator-visible source for the daemon control plane. */
data class ControlToken(
    val value: String,
    val source: String,
) {
    init {
        require(value.isNotBlank()) { "control token must not be blank" }
    }

    companion object {
        private const val ENV_NAME = "QKT_CONTROL_TOKEN"
        private val secureRandom = SecureRandom()

        /** Resolve the daemon credential, generating an owner-only state file when no env token is set. */
        fun forDaemon(
            stateDir: StateDir,
            env: Map<String, String> = System.getenv(),
        ): ControlToken {
            env[ENV_NAME]?.takeIf { it.isNotBlank() }?.let { return ControlToken(it, ENV_NAME) }
            stateDir.readControlToken()?.let {
                enforceOwnerOnly(stateDir.controlTokenFile)
                return ControlToken(it, stateDir.controlTokenFile.toString())
            }
            val bytes = ByteArray(32).also(secureRandom::nextBytes)
            val generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            writeOwnerOnly(stateDir.controlTokenFile, generated)
            val stored = stateDir.readControlToken() ?: error("failed to read generated control token")
            return ControlToken(stored, stateDir.controlTokenFile.toString())
        }

        /** Resolve the credential used by CLI clients, preferring the same environment override. */
        fun forClient(
            stateDir: StateDir,
            env: Map<String, String> = System.getenv(),
        ): ControlToken? {
            env[ENV_NAME]?.takeIf { it.isNotBlank() }?.let { return ControlToken(it, ENV_NAME) }
            return stateDir.readControlToken()?.let { ControlToken(it, stateDir.controlTokenFile.toString()) }
        }

        private fun writeOwnerOnly(
            path: java.nio.file.Path,
            token: String,
        ) {
            Files.createDirectories(path.parent)
            val attributes =
                runCatching {
                    arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                }.getOrDefault(emptyArray())
            try {
                FileChannel
                    .open(path, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), *attributes)
                    .use { channel ->
                        val bytes = ByteBuffer.wrap(token.toByteArray(StandardCharsets.UTF_8))
                        while (bytes.hasRemaining()) channel.write(bytes)
                        channel.force(true)
                    }
            } catch (_: FileAlreadyExistsException) {
                return
            } catch (_: UnsupportedOperationException) {
                Files.writeString(path, token, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            }
            enforceOwnerOnly(path)
        }

        private fun enforceOwnerOnly(path: java.nio.file.Path) {
            runCatching {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            }
        }
    }
}
