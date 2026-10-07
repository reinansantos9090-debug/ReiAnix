package com.reiflix.reiflix_local.data.library

import org.json.JSONObject

internal object ReiAnixLibraryCommandCodec {

    fun create(
        requestId: String,
        action: Action,
        animeId: Long? = null,
        episodeId: Long? = null,
        watched: Boolean? = null,
        source: String? = null,
        page: Int? = null,
        pageSize: Int? = null,
        query: String? = null,
        genre: String? = null,
        sort: String? = null,
        favoritesOnly: Boolean? = null,
        watchingOnly: Boolean? = null,
        completedOnly: Boolean? = null,
        generation: Long? = null,
    ): JSONObject {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        val payload = JSONObject().put("action", action.value)
        animeId?.let { payload.put("animeId", it) }
        episodeId?.let { payload.put("episodeId", it) }
        watched?.let { payload.put("watched", it) }
        source?.takeIf { it.isNotBlank() }?.let { payload.put("source", it) }
        page?.let { payload.put("page", it) }
        pageSize?.let { payload.put("pageSize", it) }
        query?.let { payload.put("query", it) }
        genre?.let { payload.put("genre", it) }
        sort?.let { payload.put("sort", it) }
        favoritesOnly?.let { payload.put("favoritesOnly", it) }
        watchingOnly?.let { payload.put("watchingOnly", it) }
        completedOnly?.let { payload.put("completedOnly", it) }
        generation?.let { payload.put("generation", it) }

        return JSONObject()
            .put("type", "compose_library_command")
            .put("requestId", requestId)
            .put("payload", payload)
    }

    enum class Action(val value: String) {
        TOGGLE_FAVORITE("toggle_favorite"),
        SET_WATCHED("set_watched"),
        REFRESH("refresh"),
        LOAD_LIBRARY_PAGE("load_library_page"),
        OPEN_MEDIA("open_media"),
        SELECT_SAF("select_saf"),
        REMOVE_SAF("remove_saf"),
    }
}
