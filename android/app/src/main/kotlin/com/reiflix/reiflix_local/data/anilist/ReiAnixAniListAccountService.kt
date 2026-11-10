package com.reiflix.reiflix_local.data.anilist

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.reiflix.reiflix_local.BuildConfig
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Account credentials and outbound receipts only; SQLite remains the local progress authority. */
class ReiAnixAniListAccountService(context: Context, private val library: ReiAnixSqliteLibraryRepository) {
    data class State(val connected: Boolean = false, val name: String = "", val autoSync: Boolean = false,
        val message: String = "", val configured: Boolean = BuildConfig.ANILIST_CLIENT_ID.isNotBlank())
    private val prefs = context.applicationContext.getSharedPreferences("anilist_account_v1", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            mutex.withLock { refresh() }
            wake.trySend(Unit)
            for (signal in wake) {
                for (attempt in 0..2) {
                    var retryDelay = 60_000L * (attempt + 1)
                    val retry = mutex.withLock {
                        try { flush(); false }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: AccountFailure) {
                            retryDelay = maxOf(retryDelay, failure.retryAfterMs)
                            if (failure.code == 401 || failure.code == 403) {
                                prefs.edit().remove("token").putBoolean("enabled", false).commit()
                                refresh("Conecte sua conta novamente."); false
                            } else { refresh("Sincronização pendente. Tentaremos novamente."); true }
                        } catch (_: Exception) { refresh("Sincronização pendente. Tentaremos novamente."); true }
                    }
                    if (!retry) break
                    if (attempt < 2) delay(retryDelay)
                }
            }
        }
    }

    fun retryPending() { wake.trySend(Unit) }

    suspend fun authorizationUri(): Uri = withContext(Dispatchers.IO) { mutex.withLock {
        check(BuildConfig.ANILIST_CLIENT_ID.isNotBlank()) { "Conexão indisponível nesta versão." }
        val nonce = UUID.randomUUID().toString()
        prefs.edit().putString("oauth_state", nonce).putLong("oauth_started", System.currentTimeMillis()).commit()
        Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
            .appendQueryParameter("client_id", BuildConfig.ANILIST_CLIENT_ID)
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("state", nonce).build()
    } }

    suspend fun acceptCallback(uri: Uri): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        if (uri.scheme != "reianix-anilist" || uri.host != "oauth" || !uri.path.isNullOrEmpty()) return@withLock false
        val values = Uri.parse("https://callback.invalid/?" + uri.encodedFragment.orEmpty())
        val receivedState = values.getQueryParameter("state") ?: uri.getQueryParameter("state") ?: ""
        val expected = prefs.getString("oauth_state", "").orEmpty()
        val age = System.currentTimeMillis() - prefs.getLong("oauth_started", 0L)
        if (expected.isEmpty() || age !in 0..900_000L ||
            !MessageDigest.isEqual(expected.toByteArray(), receivedState.toByteArray())) return@withLock false
        prefs.edit().remove("oauth_state").remove("oauth_started").commit()
        val token = values.getQueryParameter("access_token")?.takeIf { it.length in 16..8192 }
            ?: run { refresh("Conexão cancelada ou não autorizada."); return@withLock false }
        val viewer = request("query { Viewer { id name } }", JSONObject(), token).getJSONObject("Viewer")
        val id = viewer.getLong("id")
        require(id > 0L)
        val encrypted = encrypt(token)
        if (prefs.getLong("user_id", 0L) != id) prefs.edit().clear().commit()
        // New connections always require a separate explicit opt-in for automatic writes.
        prefs.edit().putString("token", encrypted).putLong("user_id", id)
            .putString("name", viewer.getString("name")).putBoolean("enabled", false).commit()
        refresh("Conta conectada.")
        true
    } }

    suspend fun setAutoSync(enabled: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        check(!enabled || prefs.contains("token"))
        prefs.edit().putBoolean("enabled", enabled).apply()
        if (!enabled) clearPending()
        refresh()
        if (enabled) wake.trySend(Unit)
    } }

    suspend fun disconnect() = withContext(Dispatchers.IO) { mutex.withLock {
        prefs.edit().clear().commit()
        refresh("Conta desconectada neste aparelho.")
    } }

    /** Runs after a successful local checkpoint; never holds up playback or the SQLite writer. */
    fun offerProgress(episodeId: Long, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L || positionMs.toDouble() / durationMs < .8) return
        scope.launch {
            try {
                mutex.withLock {
                    if (!prefs.getBoolean("enabled", false) || !prefs.contains("token")) return@withLock
                    val episode = library.getEpisode(episodeId) ?: return@withLock
                    val anime = library.getAnime(episode.animeId) ?: return@withLock
                    // Artwork's permissive matching rules must never silently write to the wrong profile entry.
                    if (anime.anilistMatchManual != true) { refresh("Confirme a associação ao AniList nos detalhes do anime para sincronizar."); return@withLock }
                    val mediaId = anime.anilistId?.takeIf { it > 0L } ?: return@withLock
                    if (episode.episodeType?.lowercase() in setOf("special", "ova", "oad", "ona", "extra")) return@withLock
                    val number = if (anime.mediaKind.equals("movie", ignoreCase = true)) 1.0 else if (episode.season <= 1) episode.number else episode.absoluteNumber
                    if (number == null || !number.isFinite() || number < 1 || number % 1.0 != 0.0 || number > Int.MAX_VALUE) return@withLock
                    val progress = number.toInt()
                    if (progress <= prefs.getInt("ack_$mediaId", 0)) return@withLock
                    val previous = prefs.getString("pending_$mediaId", null)?.let(::JSONObject)?.optInt("progress") ?: 0
                    if (progress >= previous) prefs.edit().putString("pending_$mediaId", JSONObject()
                        .put("episode", episodeId).put("progress", progress).toString()).commit()
                    wake.trySend(Unit)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutex.withLock { refresh("Não foi possível preparar a sincronização. O progresso local foi preservado.") } }
        }
    }

    private suspend fun flush() {
        if (!prefs.getBoolean("enabled", false) || !prefs.contains("token")) return
        val token = try { decrypt(prefs.getString("token", "").orEmpty()) }
            catch (_: Exception) { throw AccountFailure(401) }
        for ((key, value) in prefs.all.filterKeys { it.startsWith("pending_") }) {
            val mediaId = key.removePrefix("pending_").toLongOrNull() ?: continue
            val pending = JSONObject(value as String)
            val episode = library.getEpisode(pending.getLong("episode"))
            val anime = episode?.let { library.getAnime(it.animeId) }
            if (anime == null || anime.anilistId != mediaId || anime.anilistMatchManual != true) { prefs.edit().remove(key).commit(); continue }
            val data = request("query(\$id: Int) { Media(id: \$id, type: ANIME) { episodes mediaListEntry { id progress status } } }",
                JSONObject().put("id", mediaId), token).getJSONObject("Media")
            val entry = data.optJSONObject("mediaListEntry")
            val remoteProgress = entry?.optInt("progress") ?: 0
            val progress = pending.getInt("progress")
            val total = data.optInt("episodes", 0)
            if (total > 0 && progress > total) { prefs.edit().remove(key).commit(); refresh("Numeração incompatível com o AniList; confira a associação do anime."); continue }
            val remoteStatus = entry?.optString("status").orEmpty()
            val shouldComplete = total > 0 && progress == total &&
                remoteStatus != "COMPLETED" && remoteStatus != "REPEATING"
            if (progress > remoteProgress || shouldComplete) {
                val latestAnime = episode?.let { library.getAnime(it.animeId) }
                if (latestAnime == null || latestAnime.anilistId != mediaId || latestAnime.anilistMatchManual != true) {
                    prefs.edit().remove(key).commit()
                    continue
                }
                val status = when {
                    remoteStatus == "COMPLETED" || remoteStatus == "REPEATING" -> remoteStatus
                    total > 0 && progress >= total -> "COMPLETED"
                    else -> "CURRENT"
                }
                request("mutation(\$entry: Int, \$media: Int, \$progress: Int, \$status: MediaListStatus) { SaveMediaListEntry(id: \$entry, mediaId: \$media, progress: \$progress, status: \$status) { id progress } }",
                    JSONObject().put("entry", entry?.optInt("id")?.takeIf { it > 0 } ?: JSONObject.NULL)
                        .put("media", mediaId).put("progress", maxOf(progress, remoteProgress)).put("status", status), token)
            }
            prefs.edit().putInt("ack_$mediaId", maxOf(progress, remoteProgress)).remove(key).commit()
            refresh("Progresso sincronizado com o AniList.")
            delay(1_500L)
        }
    }

    private fun clearPending() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("pending_") }.forEach { edit.remove(it) }
        edit.commit()
    }
    private fun refresh(message: String = "") {
        mutableState.value = State(prefs.contains("token"), prefs.getString("name", "").orEmpty(),
            prefs.getBoolean("enabled", false), message)
    }
    private class AccountFailure(val code: Int, val retryAfterMs: Long = 0L) : Exception()
    private fun request(query: String, variables: JSONObject, token: String): JSONObject {
        val connection = URL("https://graphql.anilist.co").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.doOutput = true
            connection.outputStream.use { it.write(JSONObject().put("query", query).put("variables", variables).toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val retry = connection.getHeaderField("Retry-After")?.toLongOrNull()
                    ?.coerceIn(0, Long.MAX_VALUE / 1_000)?.times(1_000) ?: 0L
                throw AccountFailure(code, retry)
            }
            val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) throw AccountFailure(errors.optJSONObject(0)?.optInt("status", 500) ?: 500)
            return json.getJSONObject("data")
        } finally { connection.disconnect() }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("reianix_anilist_token_v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("reianix_anilist_token_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(token: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    companion object { const val REDIRECT_URI = "reianix-anilist://oauth" }
}
