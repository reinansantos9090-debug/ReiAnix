package com.reianix.reianix_local.data.sqlite

import com.reianix.reianix_local.data.media.LocalCatalogLookup
import com.reianix.reianix_local.data.media.LocalCatalogLookupPolicy

internal fun ReiAnixAnimeRecord.hasTrustedCatalogAssociation(): Boolean =
    anilistId != null || anilistMatchManual == true ||
        metadataSource.equals("manual", true) || metadataStatus.equals("manual", true)

internal fun ReiAnixAnimeRecord.localCatalogLookup(fileNames: List<String>): LocalCatalogLookup =
    if (hasTrustedCatalogAssociation()) LocalCatalogLookup.NORMAL
    else LocalCatalogLookupPolicy.classify(lookupTitle, mediaKind, fileNames)

/** Only suspicious/standalone titles need a bounded filename read. Ordinary series
 * never perform an additional SQLite query. */
internal suspend fun ReiAnixSqliteLibraryRepository.localCatalogLookup(
    anime: ReiAnixAnimeRecord,
): LocalCatalogLookup {
    if (anime.hasTrustedCatalogAssociation() ||
        !LocalCatalogLookupPolicy.needsEvidence(anime.lookupTitle, anime.mediaKind)
    ) return LocalCatalogLookup.NORMAL
    val names = database.read { db ->
        db.rawQuery(
            "SELECT file_name FROM episodes WHERE anime_id=? ORDER BY id LIMIT 8",
            arrayOf(anime.id.toString()),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) cursor.getString(0)?.let(::add) }
        }
    }
    return anime.localCatalogLookup(names)
}
