/**
 * Vegas: CrashHandler ends the process itself, so Android never records the crash. The last few
 * crash reports are kept in the app's files to review them later (adb run-as … files/crashes).
 * Licensed under GPL-3.0
 */

package com.metrolist.music.utils

import java.io.File

class CrashLogStore(private val dir: File, private val keep: Int = 5) {
    fun save(report: String, timestamp: Long = System.currentTimeMillis()) {
        dir.mkdirs()
        File(dir, "crash-%013d.txt".format(timestamp)).writeText(report)
        dir.listFiles { file -> file.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            ?.drop(keep)
            ?.forEach { it.delete() }
    }
}
