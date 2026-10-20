package com.reiflix.reiflix_local.data.sqlite

/** Query-only specification for filters accepted by LibraryStore.catalog_page. */
data class ReiAnixCatalogFilters(
    val query: String = "",
    val state: String = "Todos",
    val genre: String = "Todos",
    val sort: String = "Mais recentes",
    val tag: String = "Todos",
    val mediaType: String = "Todos",
    val season: String? = null,
    val episodeType: String = "Todos",
    val sourceKind: String = "Todos",
    val availability: String = "Todos",
    val metadata: String = "Todos",
    val artwork: String = "Todos",
    val favoritesOnly: Boolean = false,
    val watchingOnly: Boolean = false,
    val completedOnly: Boolean = false,
)
