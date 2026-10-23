/**
 * Vegas desktop: stand-in for Android's Timber so the shared innertube sources compile on the JVM.
 * Silent unless the VEGAS_DEBUG environment variable is set.
 * Licensed under GPL-3.0
 */

package timber.log

object Timber {
    private val enabled = System.getenv("VEGAS_DEBUG") != null

    fun tag(tag: String): Timber = this

    fun d(message: String, vararg args: Any?) = log("D", null, message, args)

    fun i(message: String, vararg args: Any?) = log("I", null, message, args)

    fun w(message: String, vararg args: Any?) = log("W", null, message, args)

    fun w(t: Throwable?, message: String, vararg args: Any?) = log("W", t, message, args)

    fun e(message: String, vararg args: Any?) = log("E", null, message, args)

    fun e(t: Throwable?, message: String, vararg args: Any?) = log("E", t, message, args)

    private fun log(level: String, t: Throwable?, message: String, args: Array<out Any?>) {
        if (!enabled) return
        System.err.println("$level/Vegas: " + if (args.isEmpty()) message else message.format(*args))
        t?.printStackTrace()
    }
}
