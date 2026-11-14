package com.reiflix.reiflix_local.data.sqlite

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native API for the personal list represented by LibraryStore's persisted
 * favorite flag. It does not use episode playback state or create another DB.
 *
 * The canonical schema has no favorite-association timestamp or named custom
 * lists. This API does not invent either feature or imply AniList synchronization.
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
        /** Null: the canonical schema does not record when an anime was favorited. */
        val favoritedAtEpochSeconds: Double? = null,
    )

    sealed interface Result<out T> {
        data class Success<T>(val value: T) : Result<T>
        data object NotFound : Result<Nothing>
        data object Conflict : Result<Nothing>
        data class Failure(val cause: Throwable) : Result<Nothing>
    }

    suspend fun list(): Result<List<Item>> = withContext(Dispatchers.IO) {
        try {
            Result.Success(
                database.read { db ->
                    db.rawQuery(
                        """
                        SELECT id, anilist_id, title
                        FROM anime
                        WHERE COALESCE(favorite, 0) = 1
                        ORDER BY title COLLATE NOCASE ASC, id ASC
                        """.trimIndent(),
                        null,
                    ).use { cursor ->
                        buildList {
                            val idColumn = cursor.getColumnIndexOrThrow("id")
                            val anilistColumn = cursor.getColumnIndexOrThrow("anilist_id")
                            val titleColumn = cursor.getColumnIndexOrThrow("title")
                            while (cursor.moveToNext()) {
                                add(
                                    Item(
                                        animeId = cursor.getLong(idColumn),
                                        anilistId = if (cursor.isNull(anilistColumn)) null
                                            else cursor.getLong(anilistColumn),
                                        title = cursor.getString(titleColumn).orEmpty(),
                                    ),
                                )
                            }
                        }
                    }
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.Failure(error)
        }
    }

    suspend fun contains(animeId: Long): Result<Boolean> {
        if (animeId <= 0L) {
            return Result.Failure(
                IllegalArgumentException("animeId must be a positive persisted ID."),
            )
        }
        return try {
            val anime = library.getAnime(animeId) ?: return Result.NotFound
            Result.Success(anime.favorite == true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.Failure(error)
        }
    }

    suspend fun add(animeId: Long): Result<Boolean> = setMembership(animeId, included = true)

    suspend fun remove(animeId: Long): Result<Boolean> = setMembership(animeId, included = false)

    suspend fun toggle(animeId: Long): Result<Boolean> {
        if (animeId <= 0L) {
            return Result.Failure(
                IllegalArgumentException("animeId must be a positive persisted ID."),
            )
        }
        return withContext(Dispatchers.IO) {
            try {
                database.transaction { db ->
                    val currentlyIncluded = db.rawQuery(
                        "SELECT favorite FROM anime WHERE id = ? LIMIT 1",
                        arrayOf(animeId.toString()),
                    ).use { cursor ->
                        if (!cursor.moveToFirst()) return@transaction null
                        cursor.getInt(0) != 0
                    } ?: return@transaction null

                    val target = !currentlyIncluded
                    db.execSQL(
                        "UPDATE anime SET favorite = ? WHERE id = ?",
                        arrayOf(if (target) 1 else 0, animeId),
                    )
                    target
                }?.let { Result.Success(it) } ?: Result.NotFound
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.Failure(error)
            }
        }
    }

    private suspend fun setMembership(
        animeId: Long,
        included: Boolean,
    ): Result<Boolean> {
        if (animeId <= 0L) {
            return Result.Failure(
                IllegalArgumentException("animeId must be a positive persisted ID."),
            )
        }
        return withContext(Dispatchers.IO) {
            try {
                database.transaction { db ->
                    val currentlyIncluded = db.rawQuery(
                        "SELECT favorite FROM anime WHERE id = ? LIMIT 1",
                        arrayOf(animeId.toString()),
                    ).use { cursor ->
                        if (!cursor.moveToFirst()) return@transaction null
                        cursor.getInt(0) != 0
                    } ?: return@transaction null

                    if (currentlyIncluded == included) return@transaction included

                    db.execSQL(
                        "UPDATE anime SET favorite = ? WHERE id = ?",
                        arrayOf(if (included) 1 else 0, animeId),
                    )
                    included
                }?.let { Result.Success(it) } ?: Result.NotFound
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.Failure(error)
            }
        }
    }
}
