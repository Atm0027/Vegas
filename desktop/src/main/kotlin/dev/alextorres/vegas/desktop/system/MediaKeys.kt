/**
 * Vegas desktop: the keyboard's media keys work even when the window is minimised. Uses Windows'
 * RegisterHotKey for just these four keys, so no global keyboard hook ever sees other key presses.
 * If another app already owns a key, registration fails quietly and that key keeps going there.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.system

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import javax.swing.SwingUtilities

enum class MediaKey(val virtualKey: Int) {
    PLAY_PAUSE(0xB3),
    NEXT(0xB0),
    PREVIOUS(0xB1),
    STOP(0xB2),
    ;

    val hotkeyId: Int get() = HOTKEY_BASE + ordinal

    companion object {
        private const val HOTKEY_BASE = 0x7E00

        fun fromVirtualKey(vk: Int): MediaKey? = entries.firstOrNull { it.virtualKey == vk }

        fun fromHotkeyId(id: Int): MediaKey? = entries.getOrNull(id - HOTKEY_BASE)
    }
}

class MediaKeys(private val onKey: (MediaKey) -> Unit) : AutoCloseable {
    @Volatile private var threadId = 0

    private val thread = Thread({ loop() }, "vegas-media-keys").apply {
        isDaemon = true
        start()
    }

    private fun loop() {
        val user32 = User32.INSTANCE
        threadId = Kernel32.INSTANCE.GetCurrentThreadId()
        val registered = MediaKey.entries.filter { user32.RegisterHotKey(null, it.hotkeyId, MOD_NOREPEAT, it.virtualKey) }
        try {
            val msg = WinUser.MSG()
            while (user32.GetMessage(msg, null, 0, 0) > 0) {
                if (msg.message == WinUser.WM_HOTKEY) {
                    MediaKey.fromHotkeyId(msg.wParam.toInt())?.let { key -> SwingUtilities.invokeLater { onKey(key) } }
                }
            }
        } finally {
            registered.forEach { user32.UnregisterHotKey(null, it.hotkeyId) }
        }
    }

    override fun close() {
        if (threadId != 0) User32.INSTANCE.PostThreadMessage(threadId, WinUser.WM_QUIT, null, null)
        thread.join(1_000)
    }

    private companion object {
        const val MOD_NOREPEAT = 0x4000
    }
}
