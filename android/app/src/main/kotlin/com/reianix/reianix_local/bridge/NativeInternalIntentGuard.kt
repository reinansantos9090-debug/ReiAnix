package com.reianix.reianix_local.bridge

import android.content.Context
import android.content.Intent
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * MainActivity is exported for the launcher. A matching URI or an explicit component
 * is NOT proof that a native command came from ReiAnix: another Android app can
 * construct either. Internal command senders attach an unpredictable, app-private
 * installation token; untrusted external intents may still open the launcher but
 * cannot request scans, player commands, account changes or backup operations.
 *
 * Never add this token to an URI, log statement, mailbox event or exported result.
 */
internal object NativeInternalIntentGuard {
    private const val PREFS = "reianix_native_intent_auth_v1"
    private const val KEY = "private_command_token"
    private const val EXTRA = "com.reianix.reianix_local.private_command_token"

    fun stamp(context: Context, intent: Intent): Intent =
        intent.putExtra(EXTRA, privateToken(context))

    fun isTrusted(context: Context, intent: Intent): Boolean {
        val supplied = intent.getStringExtra(EXTRA) ?: return false
        val expected = privateToken(context)
        return MessageDigest.isEqual(
            supplied.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun privateToken(context: Context): String = synchronized(this) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.takeIf(String::isNotEmpty)?.let { return@synchronized it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.NO_WRAP)
        check(prefs.edit().putString(KEY, token).commit()) {
            "Could not initialize app-private native command authentication"
        }
        token
    }
}
