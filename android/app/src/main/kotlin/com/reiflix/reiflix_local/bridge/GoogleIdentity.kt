package com.reiflix.reiflix_local.bridge
import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.SecureRandom
import org.json.JSONObject

/** Uses Android Credential Manager. The ID token is never written to disk or sent through the mailbox. */
object GoogleIdentity {
    private const val TAG = "[REIFLIX][AUTH]"
    private const val NONCE_BYTES = 32

    suspend fun signIn(context: Context, serverClientId: String, requestId: String? = null) {
        if (serverClientId.isBlank() || !isWebClientId(serverClientId)) {
            Log.w(TAG, "Google sign-in requested without a valid Web client ID")
            writeError(context, requestId, "configuration_required", "O login Google ainda não foi configurado corretamente neste APK.")
            return
        }

        try {
            Log.i(TAG, "Google Credential Manager sign-in requested")
            NativeMailbox.write(context, JSONObject().put("type", "google_sign_in_started").put("requestId", requestId ?: ""))

            // A nonce binds the returned ID token to this sign-in request and is
            // defense-in-depth for any future server-backed authentication flow.
            val nonce = generateNonce()
            val option = GetGoogleIdOption.Builder()
                .setServerClientId(serverClientId)
                .setNonce(nonce)
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(false)
                .build()

            val response = CredentialManager.create(context).getCredential(
                context,
                GetCredentialRequest.Builder().addCredentialOption(option).build(),
            )
            val credential = GoogleIdTokenCredential.createFrom(response.credential.data)
            val claims = validatedClaims(credential.idToken, serverClientId, nonce)

            if (claims == null || credential.uniqueId.isBlank() || credential.email.isNullOrBlank()) {
                writeError(context, requestId, "invalid_credential", "A credencial Google recebida não pôde ser validada.")
                return
            }

            // uniqueId is the stable Google account identifier exposed by the
            // Google Identity SDK. Email is retained only as profile data.
            if (claims.subject != credential.uniqueId) {
                writeError(context, requestId, "invalid_credential", "A identidade Google recebida não corresponde ao identificador da conta.")
                return
            }

            NativeMailbox.write(
                context,
                JSONObject()
                    .put("type", "google_account")
                    .put("requestId", requestId ?: "")
                    .put(
                        "payload",
                        JSONObject()
                            .put("id", credential.uniqueId)
                            .put("name", credential.displayName ?: "")
                            .put("email", credential.email ?: "")
                            .put("picture", credential.profilePictureUri?.toString() ?: ""),
                    ),
            )
            Log.i(TAG, "Google account selected")
        } catch (cancelled: GetCredentialCancellationException) {
            Log.i(TAG, "Google sign-in cancelled")
            NativeMailbox.write(context, JSONObject().put("type", "google_cancelled").put("requestId", requestId ?: ""))
        } catch (exception: GetCredentialException) {
            Log.e(TAG, "Google credential manager failed", exception)
            val error = classifyCredentialError(exception)
            writeError(context, requestId, error.first, error.second)
        } catch (exception: Exception) {
            Log.e(TAG, "Google identity failed", exception)
            writeError(context, requestId, "internal_error", "Não foi possível concluir o login Google. Tente novamente.")
        }
    }

    /** Clears Credential Manager state so future account selection is not pinned to the previous user. */
    suspend fun signOut(context: Context, requestId: String? = null): Boolean {
        return try {
            Log.i(TAG, "Google Credential Manager sign-out requested")
            val clearRequest = ClearCredentialStateRequest(
                ClearCredentialStateRequest.TYPE_CLEAR_CREDENTIAL_STATE,
            )
            CredentialManager.create(context).clearCredentialState(clearRequest)
            NativeMailbox.write(
                context,
                JSONObject()
                    .put("type", "google_signed_out")
                    .put("requestId", requestId ?: ""),
            )
            Log.i(TAG, "Google Credential Manager credential state cleared")
            true
        } catch (exception: kotlinx.coroutines.CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Log.e(TAG, "Google Credential Manager sign-out failed", exception)
            writeError(
                context,
                requestId,
                "credential_state_clear_failed",
                "Não foi possível encerrar a sessão Google com segurança.",
            )
            false
        }
    }

    private fun writeError(context: Context, requestId: String?, code: String, message: String) {
        NativeMailbox.write(
            context,
            JSONObject()
                .put("type", "google_error")
                .put("requestId", requestId ?: "")
                .put("code", code)
                .put("message", message),
        )
    }

    private fun classifyCredentialError(exception: GetCredentialException): Pair<String, String> {
        return when (exception::class.java.simpleName) {
            "NoCredentialException" ->
                "no_credential" to "Nenhuma credencial Google disponível neste dispositivo."
            "GetCredentialUnsupportedException" ->
                "unsupported" to "O Gerenciador de Credenciais não está disponível neste dispositivo."
            "GetCredentialProviderConfigurationException" ->
                "provider_configuration" to "O provedor de credenciais Google não está configurado corretamente."
            else ->
                "credential_error" to "O Gerenciador de Credenciais não conseguiu concluir o login Google."
        }
    }

    private fun isWebClientId(clientId: String): Boolean =
        clientId.trim().endsWith(".apps.googleusercontent.com") &&
            clientId.substringBefore(".apps.googleusercontent.com").isNotBlank()

    private fun generateNonce(): String {
        val bytes = ByteArray(NONCE_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    private data class TokenClaims(
        val subject: String,
    )

    /**
     * Defense-in-depth claim validation. Credential Manager/Google Identity
     * supplies the credential; this check additionally enforces the expected
     * issuer, audience, expiry and per-request nonce before the profile is
     * reduced to token-free fields.
     *
     * Full cryptographic JWT signature verification belongs at a trusted
     * backend when the ID token is used to authorize server resources.
     */
    private fun validatedClaims(idToken: String, clientId: String, expectedNonce: String): TokenClaims? {
        return try {
            val parts = idToken.split('.')
            if (parts.size != 3) return null
            val payload = JSONObject(
                String(
                    Base64.decode(
                        parts[1],
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                    ),
                ),
            )
            val issuer = payload.optString("iss")
            val audience = payload.opt("aud")
            val audienceMatches =
                audience == clientId ||
                    (audience is org.json.JSONArray &&
                        (0 until audience.length()).any { audience.optString(it) == clientId })
            val expiresAt = payload.optLong("exp", 0L)
            val nonce = payload.optString("nonce")
            val subject = payload.optString("sub").trim()
            if (
                issuer !in setOf("https://accounts.google.com", "accounts.google.com") ||
                !audienceMatches ||
                expiresAt <= System.currentTimeMillis() / 1000 ||
                nonce != expectedNonce ||
                subject.isBlank()
            ) {
                null
            } else {
                TokenClaims(subject)
            }
        } catch (_: Exception) {
            null
        }
    }
}
