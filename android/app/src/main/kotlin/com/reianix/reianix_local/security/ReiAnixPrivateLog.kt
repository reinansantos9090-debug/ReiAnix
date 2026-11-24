package com.reianix.reianix_local.security

/** Release diagnostics expose an event code and exception type, never free-form payloads,
 * file names, URLs, account identifiers, throwable messages or stack traces.
 * Explicit developer debug builds retain the existing diagnostic detail.
 */
internal object ReiAnixPrivateLog {
    const val DEBUG = android.util.Log.DEBUG
    private val event = Regex("^[A-Z][A-Z0-9_]{3,95}(?=[ :]|$)")
    fun isLoggable(tag: String, level: Int): Boolean =
        android.util.Log.isLoggable(tag, level)

    private fun write(level: Int, tag: String, message: String?, error: Throwable?): Int {
        if (com.reianix.reianix_local.BuildConfig.DEBUG) {
            return android.util.Log.println(level, tag, message.orEmpty() +
                (error?.let { "\n" + android.util.Log.getStackTraceString(it) } ?: ""))
        }
        val code = event.find(message.orEmpty())?.value ?: "INTERNAL_EVENT"
        val category = error?.javaClass?.simpleName?.let { " category=" + it }.orEmpty()
        return android.util.Log.println(level, tag, code + category)
    }
    fun d(tag: String, msg: String?, error: Throwable? = null) = write(android.util.Log.DEBUG, tag, msg, error)
    fun i(tag: String, msg: String?, error: Throwable? = null) = write(android.util.Log.INFO, tag, msg, error)
    fun w(tag: String, msg: String?, error: Throwable? = null) = write(android.util.Log.WARN, tag, msg, error)
    fun e(tag: String, msg: String?, error: Throwable? = null) = write(android.util.Log.ERROR, tag, msg, error)
    fun v(tag: String, msg: String?, error: Throwable? = null) = write(android.util.Log.VERBOSE, tag, msg, error)
    fun wtf(tag: String, msg: String?, error: Throwable? = null) = write(android.util.Log.ASSERT, tag, msg, error)
}
