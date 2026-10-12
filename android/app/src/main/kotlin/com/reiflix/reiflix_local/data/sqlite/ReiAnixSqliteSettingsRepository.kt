package com.reiflix.reiflix_local.data.sqlite

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.reiflix.reiflix_local.data.settings.ReiAnixSettingsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native access to LibraryStore's existing preferences table.
 *
 * Settings that still use ReiAnixSettingsRepository/NativeMailbox must not also
 * call this writer until their flow is explicitly switched to this repository.
 */
class ReiAnixSqliteSettingsRepository(
    private val database: ReiAnixSqliteDatabase,
) {
    constructor(context: Context) : this(ReiAnixSqliteDatabase(context))

    suspend fun getPreference(key: String): ReiAnixPreferenceRecord? =
        withContext(Dispatchers.IO) {
            val normalizedKey = requireKey(key)
            database.read { connection ->
                queryPreference(connection, normalizedKey)
            }
        }

    suspend fun getPreference(key: String, defaultValue: String?): String? =
        getPreference(key)?.value ?: defaultValue

    suspend fun allPreferences(): Map<String, String> = withContext(Dispatchers.IO) {
        database.read { connection ->
            connection.rawQuery(
                "SELECT key, value FROM preferences ORDER BY key COLLATE NOCASE ASC",
                null,
            ).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        put(cursor.requiredString("key"), cursor.requiredString("value"))
                    }
                }
            }
        }
    }

    /**
     * Explicit native write to the same preferences row used by LibraryStore.
     * Uses UPDATE-then-INSERT inside one transaction for Android SQLite compatibility.
     */
    suspend fun setPreference(
        key: String,
        value: String,
        updatedAtEpochSeconds: Double = System.currentTimeMillis() / 1000.0,
    ): ReiAnixPreferenceRecord = withContext(Dispatchers.IO) {
        val normalizedKey = requireKey(key)
        val normalizedValue = ReiAnixSettingsContract.normalize(normalizedKey, value)
        require(updatedAtEpochSeconds.isFinite() && updatedAtEpochSeconds >= 0.0) {
            "updatedAtEpochSeconds must be finite and non-negative."
        }
        database.transaction { connection ->
            val existing = queryPreference(connection, normalizedKey)
            if (existing?.value == normalizedValue) return@transaction existing
            val values = ContentValues().apply {
                put("value", normalizedValue)
                put("updated_at", updatedAtEpochSeconds)
            }
            val updatedRows = connection.update(
                "preferences",
                values,
                "key = ?",
                arrayOf(normalizedKey),
            )
            if (updatedRows == 0) {
                val insertValues = ContentValues().apply {
                    put("key", normalizedKey)
                    put("value", normalizedValue)
                    put("updated_at", updatedAtEpochSeconds)
                }
                connection.insertOrThrow("preferences", null, insertValues)
            }
            queryPreference(connection, normalizedKey)
                ?: throw IllegalStateException("Preference write finished without a persisted row.")
        }
    }

    /**
     * Validates and writes a group of preferences in one SQLite transaction.
     * Unchanged values are not rewritten, so their updated_at metadata remains stable.
     * Unknown legacy keys are preserved by ReiAnixSettingsContract for compatibility.
     */
    suspend fun setPreferencesAtomically(
        values: Map<String, String>,
        updatedAtEpochSeconds: Double = System.currentTimeMillis() / 1000.0,
    ): Map<String, ReiAnixPreferenceRecord> = withContext(Dispatchers.IO) {
        require(updatedAtEpochSeconds.isFinite() && updatedAtEpochSeconds >= 0.0) {
            "updatedAtEpochSeconds must be finite and non-negative."
        }
        val normalized = values.entries.associate { (key, value) ->
            val normalizedKey = requireKey(key)
            normalizedKey to ReiAnixSettingsContract.normalize(normalizedKey, value)
        }
        database.transaction { connection ->
            buildMap {
                normalized.forEach { (key, value) ->
                    val existing = queryPreference(connection, key)
                    if (existing?.value != value) {
                        val updated = ContentValues().apply {
                            put("value", value)
                            put("updated_at", updatedAtEpochSeconds)
                        }
                        val rows = connection.update("preferences", updated, "key = ?", arrayOf(key))
                        if (rows == 0) {
                            val inserted = ContentValues().apply {
                                put("key", key)
                                put("value", value)
                                put("updated_at", updatedAtEpochSeconds)
                            }
                            connection.insertOrThrow("preferences", null, inserted)
                        }
                    }
                    put(key, queryPreference(connection, key)
                        ?: throw IllegalStateException("Preference batch did not persist key: $key"))
                }
            }
        }
    }
    /** Deletion is explicit and idempotent; no other preference rows are touched. */
    suspend fun removePreference(key: String): Boolean = withContext(Dispatchers.IO) {
        val normalizedKey = requireKey(key)
        database.transaction { connection ->
            connection.delete(
                "preferences",
                "key = ?",
                arrayOf(normalizedKey),
            ) > 0
        }
    }

    private fun requireKey(key: String): String {
        val normalizedKey = key.trim()
        require(normalizedKey.isNotEmpty()) { "Preference key must not be blank." }
        return normalizedKey
    }

    private fun queryPreference(
        connection: SQLiteDatabase,
        key: String,
    ): ReiAnixPreferenceRecord? =
        connection.rawQuery(
            "SELECT key, value, updated_at FROM preferences WHERE key = ? LIMIT 1",
            arrayOf(key),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toPreferenceRecord() else null
        }
}

private fun Cursor.toPreferenceRecord() = ReiAnixPreferenceRecord(
    key = requiredString("key"),
    value = requiredString("value"),
    updatedAt = requiredDouble("updated_at"),
)
