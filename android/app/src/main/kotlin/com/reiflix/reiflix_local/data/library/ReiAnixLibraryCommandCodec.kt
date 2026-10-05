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
    ): JSONObject {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        val payload = JSONObject().put("action", action.value)
        animeId?.let { payload.put("animeId", it) }
        episodeId?.let { payload.put("episodeId", it) }
        watched?.let { payload.put("watched", it) }
        source?.takeIf { it.isNotBlank() }?.let { payload.put("source", it) }

        return JSONObject()
            .put("type", "compose_library_command")
            .put("requestId", requestId)
            .put("payload", payload)
    }

    enum class Action(val value: String) {
        TOGGLE_FAVORITE("toggle_favorite"),
        SET_WATCHED("set_watched"),
        REFRESH("refresh"),
        OPEN_MEDIA("open_media"),
        SELECT_SAF("select_saf"),
        REMOVE_SAF("remove_saf"),
    }
}
