package com.reiflix.reiflix_local

import android.content.Context
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.data.anilist.ReiAnixAniListClient
import com.reiflix.reiflix_local.data.anilist.ReiAnixAniListIntegrationService
import com.reiflix.reiflix_local.data.anilist.ReiAnixAniListDescriptionLocalizer
import com.reiflix.reiflix_local.data.artwork.ReiAnixArtworkService
import com.reiflix.reiflix_local.data.collector.ReiAnixCollectorJourneyService
import com.reiflix.reiflix_local.data.library.ReiAnixLibraryDiscoveryService
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.data.organize.ReiAnixGenreRegistry
import java.io.File
import java.util.UUID
import org.json.JSONObject
import com.reiflix.reiflix_local.data.sqlite.ReiAnixConsumptionService
import com.reiflix.reiflix_local.data.sqlite.ReiAnixLibraryQueryService
import com.reiflix.reiflix_local.data.sqlite.ReiAnixLibraryService
import com.reiflix.reiflix_local.data.sqlite.ReiAnixPersonalListService
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteDatabase
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteAccountRepository
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteSettingsRepository

/**
 * Process-scoped composition root for native services that safely share state.
 *
 * Construction is cheap and does not open SQLite, start a scan, issue network
 * requests, or initialize artwork workers. Each service is instantiated on first
 * use and receives the same SQLite/database objects where the existing contracts
 * permit sharing. Legacy projection repositories remain owned by their ViewModels.
 */
class ReiAnixAppDependencies(context: Context) {
    private val appContext = context.applicationContext

    /** Single process-scoped coordinator for native startup ordering and health state. */
    val bootstrap: ReiAnixAppBootstrap by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReiAnixAppBootstrap(appContext, this)
    }


    val sqliteDatabase: ReiAnixSqliteDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReiAnixSqliteDatabase(appContext)
    }

    val sqliteLibraryRepository: ReiAnixSqliteLibraryRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        ReiAnixSqliteLibraryRepository(sqliteDatabase)
    }

    val sqliteSettingsRepository: ReiAnixSqliteSettingsRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        ReiAnixSqliteSettingsRepository(sqliteDatabase)
    }

    val sqliteAccountRepository: ReiAnixSqliteAccountRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        ReiAnixSqliteAccountRepository(sqliteDatabase)
    }

    val libraryQueryService: ReiAnixLibraryQueryService by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        ReiAnixLibraryQueryService(appContext, sqliteLibraryRepository)
    }

    /** Single Kotlin domain entry point for scan-owned canonical library mutations. */
    val libraryService: ReiAnixLibraryService by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        ReiAnixLibraryService(sqliteLibraryRepository)
    }

    /** Local discovery algorithms over the shared canonical SQLite repository. */
    val libraryDiscoveryService: ReiAnixLibraryDiscoveryService by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        ReiAnixLibraryDiscoveryService(sqliteLibraryRepository)
    }

    val personalListService: ReiAnixPersonalListService by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        ReiAnixPersonalListService(sqliteDatabase, sqliteLibraryRepository)
    }

    val consumptionService: ReiAnixConsumptionService by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        ReiAnixConsumptionService(sqliteLibraryRepository)
    }

    /** Native Collector Journey derives statistics from canonical SQLite consumption rows. */
    val collectorJourneyService: ReiAnixCollectorJourneyService by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        ReiAnixCollectorJourneyService(
            library = sqliteLibraryRepository,
            settings = sqliteSettingsRepository,
        )
    }

    /** Local genre identity/classification uses the same canonical SQLite database. */
    val genreRegistry: ReiAnixGenreRegistry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReiAnixGenreRegistry(sqliteDatabase)
    }

    val artworkService: ReiAnixArtworkService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReiAnixArtworkService(appContext, sqliteDatabase)
    }

    val aniListClient: ReiAnixAniListClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReiAnixAniListClient()
    }

    val aniListIntegrationService: ReiAnixAniListIntegrationService by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        ReiAnixAniListIntegrationService(
            client = aniListClient,
            library = sqliteLibraryRepository,
            settings = sqliteSettingsRepository,
            genreRegistry = genreRegistry,
            descriptionLocalizer = ReiAnixAniListDescriptionLocalizer(
                cacheFile = File(appContext.filesDir, "data/covers/anilist_description_ptbr.json"),
            ),
            metadataCommitPublisher = { animeId, requestArtwork ->
                val requestId = UUID.randomUUID().toString()
                ReiAnixDiagnosticTimeline.record(
                    name = "ANILIST_METADATA_COMMITTED",
                    source = "ReiAnixAniListIntegrationService",
                    result = "COMPLETED",
                    requestId = requestId,
                    context = mapOf("artwork_requested" to requestArtwork),
                )
                if (requestArtwork) artworkService.requestAnimeArtwork(animeId, force = true)
                NativeMailbox.write(
                    appContext,
                    JSONObject()
                        .put("type", "anilist_metadata_committed")
                        .put("requestId", requestId)
                        .put("payload", JSONObject().put("animeId", animeId).put("requestArtwork", false)),
                )
            },
        )
    }

    companion object {
        /**
         * Resolves only the graph installed by the actual Android Application.
         * A missing manifest registration is an integration error, never a silent
         * fallback to a second process graph.
         */
        fun from(context: Context): ReiAnixAppDependencies =
            existingOrNull(context)
                ?: throw IllegalStateException(
                    "ReiAnixApplication is not registered in the rendered AndroidManifest.",
                )

        internal fun existingOrNull(context: Context): ReiAnixAppDependencies? =
            (context.applicationContext as? ReiAnixApplication)?.dependencies
    }
}
