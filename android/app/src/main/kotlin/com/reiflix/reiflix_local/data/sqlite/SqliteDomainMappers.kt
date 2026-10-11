package com.reiflix.reiflix_local.data.sqlite

import com.reiflix.reiflix_local.domain.media.AniListCatalogId
import com.reiflix.reiflix_local.domain.media.Anime
import com.reiflix.reiflix_local.domain.media.AnimeRecordId
import com.reiflix.reiflix_local.domain.media.ArtworkReference
import com.reiflix.reiflix_local.domain.media.CanonicalMediaIdentity
import com.reiflix.reiflix_local.domain.media.Episode
import com.reiflix.reiflix_local.domain.media.EpisodeRecordId
import com.reiflix.reiflix_local.domain.media.LocalMediaReference
import com.reiflix.reiflix_local.domain.media.PlaybackProgress

/** Explicit read mapping; these projections never create or rewrite SQLite rows. */
fun ReiAnixAnimeRecord.toDomain(): Anime = Anime(
    id = AnimeRecordId(id),
    lookupKey = lookupTitle,
    aniListId = anilistId?.takeIf { it > 0L }?.let(::AniListCatalogId),
    title = title,
    romajiTitle = romaji,
    englishTitle = english,
    nativeTitle = nativeTitle,
    aliasesJson = aliasesJson,
    description = description,
    originalDescription = descriptionOriginal,
    coverUrl = coverUrl,
    coverCacheReference = coverCache,
    bannerUrl = bannerUrl,
    genresJson = genresJson,
    year = year,
    season = season,
    status = status,
    episodeCount = episodesCount,
    durationMinutes = duration,
    score = score,
    format = format,
    studio = studio,
    metadataUpdatedAt = metadataUpdatedAt,
    metadataFetchedAt = metadataFetchedAt,
    metadataSource = metadataSource,
    metadataConfidence = metadataConfidence,
    metadataStatus = metadataStatus,
    metadataManualFieldsJson = metadataManualFieldsJson,
    favorite = favorite,
    userTagsJson = userTagsJson,
    mediaKind = mediaKind,
    pinned = isPinned,
    personalNote = personalNote,
    addedAtEpochSeconds = addedAt,
    aniListMatchStatus = anilistMatchStatus,
    aniListMatchScore = anilistMatchScore,
    aniListMatchMargin = anilistMatchMargin,
    aniListMatchManual = anilistMatchManual,
)

fun ReiAnixEpisodeRecord.toDomain(): Episode {
    val recordId = EpisodeRecordId(id)
    return Episode(
        id = recordId,
        animeId = AnimeRecordId(animeId),
        seasonNumber = season,
        number = number,
        durationSeconds = duration,
        progressSeconds = progress,
        watched = watched,
        media = LocalMediaReference(
            episodeRecordId = recordId,
            storedLocation = path,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = fileSize,
            modifiedAtEpochSeconds = modifiedAt,
            sourceFolder = sourceFolder,
            relativePath = relativePath,
            volumeId = volumeId,
            volumeUuid = volumeUuid,
            identity = mediaIdentity?.takeIf { it.isNotBlank() }?.let(::CanonicalMediaIdentity),
            availabilityState = availabilityState,
        ),
        absoluteNumber = absoluteNumber,
        episodeType = episodeType,
        title = episodeTitle,
        identificationSource = identificationSource,
        identificationConfidence = identificationConfidence,
        manualOverride = manualOverride,
        missing = missing,
        lastPlayedAtEpochSeconds = lastPlayedAt,
        availabilityState = availabilityState,
    )
}

fun ReiAnixArtworkRecord.toDomain(): ArtworkReference = ArtworkReference(
    id = id,
    entityType = entityType,
    entityId = entityId,
    artworkType = artworkType,
    source = source,
    sourceReference = sourceRef,
    localPath = localPath,
    externalUrl = externalUrl,
    manual = manual,
    priority = priority,
    status = status,
    variant = variant,
    checksum = checksum,
    contentType = contentType,
)

fun ReiAnixEpisodeRecord.toPlaybackProgress(): PlaybackProgress = PlaybackProgress(
    episodeId = EpisodeRecordId(id),
    positionSeconds = progress,
    durationSeconds = duration,
    watched = watched,
    lastPlayedAtEpochSeconds = lastPlayedAt,
)
