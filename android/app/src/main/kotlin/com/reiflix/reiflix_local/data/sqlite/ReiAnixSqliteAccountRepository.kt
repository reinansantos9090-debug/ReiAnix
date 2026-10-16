package com.reiflix.reiflix_local.data.sqlite

import android.content.ContentValues
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Canonical, token-free Google profile stored in the existing schema-v29
 * account table. This repository is intentionally separate from authentication:
 * credentials are owned by Credential Manager and are never persisted here.
 */
data class ReiAnixAccountProfile(
    val id: String,
    val name: String,
    val email: String,
    val picture: String,
)

class ReiAnixSqliteAccountRepository(
    private val database: ReiAnixSqliteDatabase,
) {
    constructor(context: Context) : this(ReiAnixSqliteDatabase(context))

    suspend fun loadProfile(): ReiAnixAccountProfile? = withContext(Dispatchers.IO) {
        database.read { db ->
            val values = linkedMapOf<String, String>()
            db.rawQuery("SELECT key,value FROM account", null).use { cursor ->
                while (cursor.moveToNext()) {
                    values[cursor.getString(0)] = cursor.getString(1)
                }
            }
            val id = values["id"]?.trim().orEmpty()
            val email = values["email"]?.trim().orEmpty()
            if (id.isBlank() || id.length > MAX_ID_LENGTH || !email.contains('@') || email.length > MAX_EMAIL_LENGTH) {
                null
            } else {
                ReiAnixAccountProfile(
                    id = id,
                    name = values["name"].orEmpty().take(MAX_NAME_LENGTH),
                    email = email,
                    picture = normalizePicture(values["picture"].orEmpty()),
                )
            }
        }
    }

    suspend fun saveProfile(profile: ReiAnixAccountProfile): ReiAnixAccountProfile = withContext(Dispatchers.IO) {
        val id = profile.id.trim()
        val name = profile.name.trim().take(MAX_NAME_LENGTH)
        val email = profile.email.trim()
        val picture = normalizePicture(profile.picture)
        require(id.isNotEmpty() && id.length <= MAX_ID_LENGTH) { "Identificador de conta inválido." }
        require(email.length <= MAX_EMAIL_LENGTH && email.contains('@')) { "E-mail da conta inválido." }

        database.transaction { db ->
            val entries = listOf(
                "id" to id,
                "name" to name,
                "email" to email,
                "picture" to picture,
            )
            entries.forEach { (key, value) ->
                val values = ContentValues().apply { put("value", value) }
                val updated = db.update("account", values, "key=?", arrayOf(key))
                if (updated == 0) {
                    values.put("key", key)
                    val inserted = db.insertOrThrow("account", null, values)
                    check(inserted >= 0L) { "Não foi possível persistir o perfil da conta." }
                }
            }
        }
        val persisted = loadProfile()
        check(persisted == ReiAnixAccountProfile(id, name, email, picture)) {
            "O perfil persistido não corresponde ao perfil confirmado."
        }
        persisted
    }

    suspend fun clearProfile() = withContext(Dispatchers.IO) {
        database.transaction { db ->
            db.delete("account", null, null)
        }
        check(loadProfile() == null) {
            "O perfil da conta não foi removido do SQLite canônico."
        }
    }

    private fun normalizePicture(raw: String): String {
        val value = raw.trim().take(MAX_PICTURE_LENGTH)
        if (value.isEmpty()) return ""
        val valid = runCatching {
            val uri = java.net.URI(value)
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null
        }.getOrDefault(false)
        return if (valid) value else ""
    }

    companion object {
        private const val MAX_ID_LENGTH = 256
        private const val MAX_NAME_LENGTH = 160
        private const val MAX_EMAIL_LENGTH = 320
        private const val MAX_PICTURE_LENGTH = 2048
    }
}
