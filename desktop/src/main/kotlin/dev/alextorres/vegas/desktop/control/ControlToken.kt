/**
 * Vegas desktop: the control API's secret (control.token, 256 random bits, readable only by the current
 * user) and its small log of rejected callers (control.log).
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.control

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object ControlToken {
    private val HEX = Regex("^[0-9a-f]{64}$")

    /** Reads the token, creating it on first run (or replacing a damaged one). */
    fun loadOrCreate(file: Path): String {
        runCatching { Files.readString(file).trim() }.getOrNull()?.takeIf { HEX.matches(it) }?.let {
            restrictToOwner(file)
            return it
        }
        Files.createDirectories(file.toAbsolutePath().parent)
        val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.deleteIfExists(tmp)
        Files.createFile(tmp)
        // Locked down before the secret is written, so it is never readable by others.
        restrictToOwner(tmp)
        Files.writeString(tmp, token, StandardOpenOption.TRUNCATE_EXISTING)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        restrictToOwner(file)
        return token
    }

    /** Windows: one ACL entry, full control for the owner, nothing inherited. Elsewhere: 0600. */
    fun restrictToOwner(file: Path) {
        Files.getFileAttributeView(file, AclFileAttributeView::class.java)?.let { view ->
            val entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(view.owner)
                .setPermissions(AclEntryPermission.entries.toSet())
                .build()
            view.acl = listOf(entry)
            return
        }
        Files.getFileAttributeView(file, PosixFileAttributeView::class.java)
            ?.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
    }
}

/** Appends one line per rejected caller; never writes the token or what was sent as one. */
class ControlLog(private val file: Path?, private val now: () -> LocalDateTime = LocalDateTime::now) {
    private var lastAuthLog = LocalDateTime.MIN
    private var suppressed = 0

    @Synchronized
    fun authFailure(method: String, path: String) {
        val t = now()
        // A flood of bad tokens leaves one line per second, with how many were left out.
        if (t.isBefore(lastAuthLog.plusSeconds(1))) {
            suppressed++
            return
        }
        lastAuthLog = t
        val extra = if (suppressed > 0) " (+$suppressed omitidos)" else ""
        suppressed = 0
        write("Token no válido: $method ${path.take(120)}$extra")
    }

    @Synchronized
    fun write(line: String) {
        val target = file ?: return
        runCatching {
            Files.createDirectories(target.toAbsolutePath().parent)
            if (Files.exists(target) && Files.size(target) > MAX_BYTES) {
                Files.move(target, target.resolveSibling(target.fileName.toString() + ".1"), StandardCopyOption.REPLACE_EXISTING)
            }
            val stamp = now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            Files.writeString(target, "$stamp $line\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    companion object {
        val NONE = ControlLog(null)
        private const val MAX_BYTES = 256 * 1024L
    }
}
