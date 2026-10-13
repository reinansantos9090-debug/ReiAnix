package com.reiflix.reiflix_local.data.sqlite

import android.content.Context
import android.database.sqlite.SQLiteException

/**
 * Native API for the personal list currently represented by LibraryStore's
 * persisted favorite flag. This service deliberately does not use episode
 * playback state as list membership and does not create a second database.
 *
 * Custom named lists are not modeled by the current canonical SQLite schema;
 * callers must not infer remote AniList synchronization from this API.
 */
class ReiAnixPersonalListService(
    private val database: ReiAnixSqliteDatabase,
    private val library: ReiAnixSqliteLibraryRepository,
) {
    constructor(context: Context) : this(
        ReiAnixSqliteDatabase(context.applicationContext),
        ReiAnixSqliteLibraryRepository(context.applicationContext),
    )

    data class Item(
        val animeId: Long,
        val anilistId: Long?,
        val title: String,
        val addedAtEpochSeconds: Double,
    )

    sealed interface Result<out T> {
        data class Success<T>(val value: T) : Result<T>
        data object NotFound : Result<Nothing>
        data object Conflict : Result<Nothing>
        data class Failure(val cause: Throwable) : Result<Nothing>
    }

    suspend fun list(): Result<List<Item>> = try {
        Result.Success(database.read { db ->
            db.rawQuery(
                """
                SELECT id, anilist_id, title, added_at
                FROM anime
                WHERE COALESCE(favorite, 0) = 1
                ORDER BY added_at DESC, id ASC
                """.trimIndent(),
                null,
            ).use { cursor ->
                buildList {
                    val idColumn = cursor.getColumnIndexOrThrow("id")
                    val anilistColumn = cursor.getColumnIndexOrThrow("anilist_id")
                    val titleColumn = cursor.getColumnIndexOrThrow("title")
                    val addedAtColumn = cursor.getColumnIndexOrThrow("added_at")
                    while (cursor.moveToNext()) {
                        add(
                            Item(
                                animeId = cursor.getLong(idColumn),
                                anilistId = if (cursor.isNull(anilistColumn)) null
                                    else cursor.getLong(anilistColumn),
                                title = cursor.getString(titleColumn).orEmpty(),
                                addedAtEpochSeconds = if (cursor.isNull(addedAtColumn)) 0.0
                                    else cursor.getDouble(addedAtColumn),
                            ),
                        )
                    }
                }
            }
        })
    } catch (error: SQLiteException) {
        Result.Failure(error)
    } catch (error: IllegalStateException) {
        Result.Failure(error)
    }

    suspend fun contains(animeId: Long): Result<Boolean> {
        if (animeId <= 0L) return Result.Failure(
            IllegalArgumentException("animeId must be a positive persisted ID."),
        )
        return try {
            val anime = library.getAnime(animeId) ?: return Result.NotFound
            Result.Success(anime.favorite == true)
        } catch (error: SQLiteException) {
            Result.Failure(error)
        } catch (error: IllegalStateException) {
            Result.Failure(error)
        }
    }

    suspend fun add(animeId: Long): Result<Boolean> = setMembership(animeId, true)

    suspend fun remove(animeId: Long): Result<Boolean> = setMembership(animeId, false)

    suspend fun toggle(animeId: Long): Result<Boolean> {
        if (animeId <= 0L) return Result.Failure(
            IllegalArgumentException("animeId must be a positive persisted ID."),
        )
        return try {
            database.transaction { db ->
                val exists = db.rawQuery(
                    "SELECT favorite FROM anime WHERE id = ? LIMIT 1",
                    arrayOf(animeId.toString()),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) return@transaction null
                    cursor.getInt(0) != 0
                } ?: return@transaction null

                val target = !exists
                db.execSQL(
                    "UPDATE anime SET favorite = ? WHERE id = ?",
                    arrayOf(if (target) 1 else 0, animeId),
                )
                // execSQL has no affected-row count; the row was verified in the
                // same transaction and the requested value is now canonical.
                target
            }?.let { Result.Success(it) } ?: Result.NotFound
        } catch (error: SQLiteException) {
            Result.Failure(error)
        } catch (error: IllegalStateException) {
            Result.Failure(error)
        }
    }

    private suspend fun setMembership(animeId: Long, included: Boolean): Result<Boolean> {
        if (animeId <= 0L) return Result.Failure(
            IllegalArgumentException("animeId must be a positive persisted ID."),
        )
        return try {
            database.transaction { db ->
                val current = db.rawQuery(
                    "SELECT favorite FROM anime WHERE id = ? LIMIT 1",
                    arrayOf(animeId.toString()),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) return@transaction null
                    cursor.getInt(0) != 0
                } ?: return@transaction null

                if (current == included) return@transaction included
                db.execSQL(
                    "UPDATE anime SET favorite = ? WHERE id = ?",
                    arrayOf(if (included) 1 else 0, animeId),
                )
                included
            }?.let { Result.Success(it) } ?: Result.NotFound
        } catch (error: SQLiteException) {
            Result.Failure(error)
        } catch (error: IllegalStateException) {
            Result.Failure(error)
        }
    }
}
