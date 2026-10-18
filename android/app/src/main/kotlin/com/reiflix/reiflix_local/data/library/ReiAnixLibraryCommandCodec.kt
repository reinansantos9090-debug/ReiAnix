package com.reiflix.reiflix_local.data.library

import org.json.JSONObject

internal object ReiAnixLibraryCommandCodec {

    fun create(
        requestId: String,
        action: Action,
    ): JSONObject {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        val payload = JSONObject().put("action", action.value)

        return JSONObject()
            .put("type", "compose_library_command")
            .put("requestId", requestId)
            .put("payload", payload)
    }

    enum class Action(val value: String) {
        REFRESH("refresh"),
        PUBLISH_CATALOG("publish_catalog"),
    }
}
