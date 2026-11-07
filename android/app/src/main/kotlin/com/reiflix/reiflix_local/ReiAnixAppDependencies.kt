package com.reiflix.reiflix_local

import android.content.Context
import android.util.Log
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.data.anilist.ReiAnixAniListClient
import com.reiflix.reiflix_local.data.anilist.ReiAnixAniListIntegrationService
import com.reiflix.reiflix_local.data.anilist.ReiAnixAniListDescriptionLocalizer
import com.reiflix.reiflix_local.data.artwork.ReiAnixArtworkService
import com.reiflix.reiflix_local.data.catalog.ReiAnixAniMapCatalogIdResolver
import com.reiflix.reiflix_local.data.catalog.ReiAnixAniPubCatalogProvider
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogProviderRegistry
import com.reiflix.reiflix_local.data.catalog.ReiAnixExternalCatalogIntegrationService
import com.reiflix.reiflix_local.data.catalog.ReiAnixExternalRequestCoordinator
import com.reiflix.reiflix_local.data.catalog.ReiAnixKitsuCatalogProvider
import com.reiflix.reiflix_local.data.collector.ReiAnixCollectorJourneyService
import com.reiflix.reiflix_local.data.library.ReiAnixLibraryDiscoveryService
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.data.organize.ReiAnixGenreRegistry
import com.reiflix.reiflix_local.data.tracemoe.ReiAnixTraceMoeClient
import com.reiflix.reiflix_local.data.tracemoe.ReiAnixHybridTraceMoeClient
import com.reiflix.reiflix_local.data.tracemoe.ReiAnixLocalVisualIndex
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
        )
    }

    val traceMoeClient: ReiAnixTraceMoeClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReiAnixTraceMoeClient()
    }

    /** Optional, separately operated visual index queried in parallel with public Trace.moe. */
    val selfHostedTraceMoeClient: ReiAnixTraceMoeClient? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BuildConfig.REIANIX_SELF_HOSTED_TRACE_MOE_BASE_URL
            .trim()
            .takeIf(String::isNotBlank)
            ?.let { baseUrl ->
                runCatching { ReiAnixTraceMoeClient.selfHosted(baseUrl) }
                    .onFailure { error ->
                        Log.w("ReiAnixAppDependencies", "Independent visual index URL is invalid; fallback disabled.", error)
                    }
                    .getOrNull()
            }
    }

    /** Persistent device-local references learned only from successfully confirmed anime matches. */
    val localVisualIndex: ReiAnixLocalVisualIndex by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReiAnixLocalVisualIndex(
            indexFile = File(appContext.filesDir, "data/visual-index/references.json"),
        )
    }

    val hybridVisualSearchClient: ReiAnixHybridTraceMoeClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReiAnixHybridTraceMoeClient(
            publicProvider = traceMoeClient,
            independentProvider = selfHostedTraceMoeClient,
            localReferenceIndex = localVisualIndex,
            cacheDirectory = File(appContext.cacheDir, "visual-search-cache"),
            cacheNamespace = BuildConfig.REIANIX_SELF_HOSTED_TRACE_MOE_BASE_URL.ifBlank { "public-only" },
        )
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
            hybridVisualSearchClient = hybridVisualSearchClient,
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
