package com.reianix.reianix_local.bridge

import android.content.Context
import org.json.JSONObject

/** Compatibility responses for older callers after removal of Google login. */
object GoogleIdentity {
    data class AccountProfile(
        val id: String,
        val name: String,
        val email: String,
        val picture: String,
    )

    data class SignInResult(
        val profile: AccountProfile? = null,
        val cancelled: Boolean = false,
        val errorCode: String? = null,
        val errorMessage: String? = null,
    )

    suspend fun signIn(
        context: Context,
        serverClientId: String,
        requestId: String? = null,
        publishLegacyMailboxEvents: Boolean = true,
    ): SignInResult {
        val message = "O login Google foi removido do aplicativo."
        if (publishLegacyMailboxEvents) {
            NativeMailbox.write(context, JSONObject()
                .put("type", "google_error")
                .put("requestId", requestId.orEmpty())
                .put("code", "feature_removed")
                .put("message", message))
        }
        return SignInResult(errorCode = "feature_removed", errorMessage = message)
    }

    suspend fun signOut(
        context: Context,
        requestId: String? = null,
        publishLegacyMailboxEvents: Boolean = true,
    ): Boolean {
        if (publishLegacyMailboxEvents) {
            NativeMailbox.write(context, JSONObject()
                .put("type", "google_signed_out")
                .put("requestId", requestId.orEmpty()))
        }
        return true
    }
}
