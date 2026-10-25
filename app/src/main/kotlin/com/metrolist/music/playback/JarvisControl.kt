/**
 * Vegas: the media service's own commands for JARVIS (explicit shuffle, repeat and volume, and queue
 * editing), on top of the standard ones every app gets. Only Vegas itself and JARVIS, recognised by
 * package and signing certificate, may use them. Contract in docs/vegas/control-jarvis.md.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.playback

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import java.security.MessageDigest

object VegasCommands {
    private const val PREFIX = "dev.alextorres.vegas.control."
    const val SET_SHUFFLE = PREFIX + "ALEATORIO"
    const val SET_REPEAT = PREFIX + "REPETIR"
    const val SET_VOLUME = PREFIX + "VOLUMEN"
    const val QUEUE_ADD = PREFIX + "COLA_ANADIR"
    const val QUEUE_REMOVE = PREFIX + "COLA_QUITAR"
    const val QUEUE_CLEAR = PREFIX + "COLA_VACIAR"

    const val EXTRA_ACTIVE = "activo"
    const val EXTRA_MODE = "modo"
    const val EXTRA_VALUE = "valor"
    const val EXTRA_ID = "id"
    const val EXTRA_QUERY = "consulta"
    const val EXTRA_WHERE = "donde"
    const val EXTRA_INDEX = "indice"

    val actions = listOf(SET_SHUFFLE, SET_REPEAT, SET_VOLUME, QUEUE_ADD, QUEUE_REMOVE, QUEUE_CLEAR)

    val all: List<SessionCommand> by lazy { actions.map { SessionCommand(it, Bundle.EMPTY) } }

    fun isVegasCommand(action: String) = action.startsWith(PREFIX)

    /** "no" | "lista" | "una", as in the PC API. */
    fun repeatMode(value: String?): Int? =
        when (value?.trim()?.lowercase()) {
            "no" -> Player.REPEAT_MODE_OFF
            "lista" -> Player.REPEAT_MODE_ALL
            "una" -> Player.REPEAT_MODE_ONE
            else -> null
        }

    /** Volume as a number 0..1, sent as a float, double or int. */
    @Suppress("DEPRECATION")
    fun volume(args: Bundle): Float? = (args.get(EXTRA_VALUE) as? Number)?.toFloat()?.takeIf { it in 0f..1f }

    /** True to play next, false for the end of the queue (the default). */
    fun playNext(value: String?): Boolean? =
        when (value?.trim()?.lowercase()) {
            null, "", "final" -> false
            "siguiente" -> true
            else -> null
        }
}

/** Who may use [VegasCommands]: Vegas itself and JARVIS's release build. */
object TrustedCallers {
    const val JARVIS_PACKAGE = "com.jarvis.mobile"

    /** SHA-256 of JARVIS's stable CI signing certificate (CN=JARVIS). Public, not a secret. */
    const val JARVIS_CERT_SHA256 = "1afdae2a0388820963c73c795d8ba738cb6e079b6ef60dbe2cddc075e1fa127e"

    /**
     * [packagesForUid] lists the packages the system says run as the caller's uid (never trusting the
     * name the caller reports); [hasCertificate] checks a package's signing certificate.
     */
    fun isAllowed(
        callerUid: Int,
        ownUid: Int,
        packagesForUid: (Int) -> List<String>,
        hasCertificate: (packageName: String, sha256: ByteArray) -> Boolean,
    ): Boolean {
        if (callerUid < 0) return false
        if (callerUid == ownUid) return true
        return JARVIS_PACKAGE in packagesForUid(callerUid) && hasCertificate(JARVIS_PACKAGE, hexToBytes(JARVIS_CERT_SHA256))
    }

    fun isAllowed(context: Context, controller: MediaSession.ControllerInfo): Boolean {
        val pm = context.packageManager
        return isAllowed(
            callerUid = controller.uid,
            ownUid = android.os.Process.myUid(),
            packagesForUid = { uid -> pm.getPackagesForUid(uid)?.toList().orEmpty() },
            hasCertificate = { pkg, sha -> hasCertificate(pm, pkg, sha) },
        )
    }

    private fun hasCertificate(pm: PackageManager, packageName: String, sha256: ByteArray): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.hasSigningCertificate(packageName, sha256, PackageManager.CERT_INPUT_SHA256)
            } else {
                @Suppress("DEPRECATION")
                val signatures = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
                // Before Android 9 every signer must match, so a second signer cannot slip in.
                signatures.isNotEmpty() && signatures.all { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).contentEquals(sha256) }
            }
        }.getOrDefault(false)

    internal fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
