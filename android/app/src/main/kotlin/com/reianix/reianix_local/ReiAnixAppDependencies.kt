package com.reianix.reianix_local

import android.content.Context
import com.reianix.reianix_local.bridge.NativeMailbox
import com.reianix.reianix_local.data.anilist.ReiAnixAniListClient
import com.reianix.reianix_local.data.anilist.ReiAnixAniListIntegrationService
import com.reianix.reianix_local.data.anilist.ReiAnixAniListDescriptionLocalizer
import com.reianix.reianix_local.data.artwork.ReiAnixArtworkService
import com.reianix.reianix_local.data.catalog.ReiAnixAniMapCatalogIdResolver
import com.reianix.reianix_local.data.catalog.ReiAnixAniPubCatalogProvider
import com.reianix.reianix_local.data.catalog.ReiAnixCatalogProviderRegistry
import com.reianix.reianix_local.data.catalog.ReiAnixExternalCatalogIntegrationService
import com.reianix.reianix_local.data.catalog.ReiAnixExternalRequestCoordinator
import com.reianix.reianix_local.data.catalog.ReiAnixKitsuCatalogProvider
import com.reianix.reianix_local.data.collector.ReiAnixCollectorJourneyService
import com.reianix.reianix_local.data.library.ReiAnixLibraryDiscoveryService
import com.reianix.reianix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reianix.reianix_local.data.organize.ReiAnixGenreRegistry
import com.reianix.reianix_local.data.tracemoe.ReiAnixTraceMoeClient
import java.io.File
import java.util.UUID
import org.json.JSONObject
import com.reianix.reianix_local.data.sqlite.ReiAnixConsumptionService
import com.reianix.reianix_local.data.sqlite.ReiAnixLibraryQueryService
import com.reianix.reianix_local.data.sqlite.ReiAnixLibraryService
import com.reianix.reianix_local.data.sqlite.ReiAnixPersonalListService
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteDatabase
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteAccountRepository
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteSettingsRepository

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

    val aniListAccountService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.reianix.reianix_local.data.anilist.ReiAnixAniListAccountService(appContext, sqliteLibraryRepository)
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

    /** Shared executor/cache for supplemental public catalog lookups. */
    val externalCatalogRequestCoordinator: ReiAnixExternalRequestCoordinator by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        ReiAnixExternalRequestCoordinator(maxConcurrentRequests = 2)
    }

    /**
     * AniPub's read-only adapter is available, but automatic requests stay disabled:
     * the original integration required prior written permission for automated access.
     */
    val aniPubCatalogProvider: ReiAnixAniPubCatalogProvider by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        ReiAnixAniPubCatalogProvider()
    }

    val externalCatalogProviderRegistry: ReiAnixCatalogProviderRegistry by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        ReiAnixCatalogProviderRegistry(
            providers = listOf(ReiAnixKitsuCatalogProvider()),
            requestCoordinator = externalCatalogRequestCoordinator,
        )
    }

    /** AniMap is a read-only crosswalk for IDs already linked to a local anime. */
    val externalCatalogIntegrationService: ReiAnixExternalCatalogIntegrationService by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        ReiAnixExternalCatalogIntegrationService(
            library = sqliteLibraryRepository,
            providers = externalCatalogProviderRegistry,
            artwork = artworkService,
            identifierResolver = externalCatalogProviderRegistry.coordinate(
                ReiAnixAniMapCatalogIdResolver(),
            ),
            metadataCommitPublisher = { animeId ->
                NativeMailbox.write(
                    appContext,
                    JSONObject()
                        .put("type", "catalog_metadata_committed")
                        .put("requestId", UUID.randomUUID().toString())
                        .put("payload", JSONObject().put("animeId", animeId).put("requestArtwork", false)),
                )
            },
        )
    }

    val traceMoeClient: ReiAnixTraceMoeClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReiAnixTraceMoeClient()
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
            traceMoeClient = traceMoeClient,
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
