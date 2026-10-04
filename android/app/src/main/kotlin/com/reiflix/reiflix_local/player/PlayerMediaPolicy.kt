package com.reiflix.reiflix_local.player
import java.util.Locale

/** Pure playback policy helpers; no I/O, Android state, network, or player ownership. */
internal object PlayerMediaPolicy {
    enum class ErrorCategory {
        SOURCE_UNAVAILABLE,
        DECODER_UNSUPPORTED,
        TRANSIENT,
        NON_RECOVERABLE,
        UNKNOWN,
    }

    /**
     * Fine-grained playback failure classes used by diagnostics and retry policy.
     * These deliberately remain a single flat classification instead of introducing
     * a parallel error hierarchy.
     */
    enum class PlaybackFailureKind {
        MEDIA_NOT_FOUND,
        PERMISSION,
        SOURCE,
        PARSER,
        DECODER,
        CODEC,
        RENDERER,
        TIMEOUT,
        LIFECYCLE,
        STALE,
        UNKNOWN,
    }

    data class ErrorClassification(
        val kind: PlaybackFailureKind,
        val legacyCategory: ErrorCategory,
        val retryable: Boolean,
    )

    fun resolveVideoMimeType(providerMime: String?, displayName: String?): String? {
        val provider = providerMime?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (provider.startsWith("video/")) {
            return provider
        }
        if (provider.isNotBlank() &&
            provider !in setOf("application/octet-stream", "binary/octet-stream", "application/binary")
        ) {
            return provider.takeIf { it.startsWith("video/") }
        }

        val extension = displayName
            ?.substringAfterLast('.', "")
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return when (extension) {
            "mp4", "m4v" -> "video/mp4"
            "mkv", "mk3d" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mpeg", "mpg", "mpe" -> "video/mpeg"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "3gp", "3gpp" -> "video/3gpp"
            "flv" -> "video/x-flv"
            else -> null
        }
    }

    fun safeResumePosition(requestedMs: Long, durationMs: Long): Long {
        if (requestedMs <= 0L || durationMs <= 0L) return 0L
        val lastPlayable = (durationMs - 1L).coerceAtLeast(0L)
        return requestedMs.coerceIn(0L, lastPlayable)
    }

    /**
     * Compatibility classifier kept for the earlier Prompt 1-27 tests/contracts.
     * Prompt 28 uses classifyPlaybackFailure for the more precise reason.
     */
    fun classifyError(errorCodeName: String?, causeNames: List<String> = emptyList()): ErrorCategory {
        val code = errorCodeName.orEmpty().uppercase(Locale.ROOT)
        val causes = causeNames.joinToString(" ").uppercase(Locale.ROOT)
        val combined = "$code $causes"
        return when {
            combined.contains("DECODER") ||
                combined.contains("MEDIACODEC") -> ErrorCategory.DECODER_UNSUPPORTED
            combined.contains("SOURCE") &&
                (combined.contains("NOT_FOUND") || combined.contains("UNAVAILABLE")) -> ErrorCategory.SOURCE_UNAVAILABLE
            combined.contains("SECURITY") ||
                combined.contains("PERMISSION") ||
                combined.contains("FILE_NOT_FOUND") ||
                combined.contains("NO_PERMISSION") -> ErrorCategory.SOURCE_UNAVAILABLE
            code.startsWith("ERROR_CODE_IO_") ||
                combined.contains("IOEXCEPTION") ||
                combined.contains("TIMEOUT") -> ErrorCategory.TRANSIENT
            combined.contains("MALFORMED") ||
                combined.contains("UNSUPPORTED_FORMAT") ||
                combined.contains("PARSER") -> ErrorCategory.NON_RECOVERABLE
            else -> ErrorCategory.UNKNOWN
        }
    }

    fun classifyPlaybackFailure(
        errorCodeName: String?,
        causeNames: List<String> = emptyList(),
        messages: List<String> = emptyList(),
        rendererIndex: Int? = null,
    ): ErrorClassification {
        val code = errorCodeName.orEmpty().uppercase(Locale.ROOT)
        val causes = causeNames.joinToString(" ").uppercase(Locale.ROOT)
        val details = messages.joinToString(" ").uppercase(Locale.ROOT)
        val combined = "$code $causes $details"

        val kind = when {
            combined.contains("STALE") ||
                combined.contains("LIFECYCLE") ||
                combined.contains("SESSION_INVALID") ->
                PlaybackFailureKind.LIFECYCLE

            combined.contains("FILE_NOT_FOUND") ||
                combined.contains("NO_SUCH_FILE") ||
                combined.contains("ENOENT") ||
                combined.contains("ITEM_UNAVAILABLE") ||
                combined.contains("MEDIASTORE_ITEM_UNAVAILABLE") ->
                PlaybackFailureKind.MEDIA_NOT_FOUND

            combined.contains("PERMISSION") ||
                combined.contains("SECURITY") ||
                combined.contains("ACCESS_DENIED") ||
                combined.contains("EACCES") ||
                combined.contains("SAF_PERMISSION") ||
                combined.contains("STORAGE_PERMISSION") ->
                PlaybackFailureKind.PERMISSION

            combined.contains("TIMEOUT") ->
                PlaybackFailureKind.TIMEOUT

            combined.contains("DECODER_INIT") ||
                combined.contains("DECODER_QUERY") ||
                combined.contains("DECODING_FAILED") ||
                combined.contains("MEDIACODEC") ||
                combined.contains("DECODER") ->
                PlaybackFailureKind.DECODER

            combined.contains("UNSUPPORTED_CODEC") ||
                combined.contains("CODEC") ->
                PlaybackFailureKind.CODEC

            combined.contains("VIDEO_FRAME_PROCESSING") ||
                combined.contains("RENDERER") ||
                (rendererIndex != null && rendererIndex >= 0) ->
                PlaybackFailureKind.RENDERER

            combined.contains("PARSING_") ||
                combined.contains("PARSER") ||
                combined.contains("MALFORMED") ||
                combined.contains("UNRECOGNIZED_INPUT") ||
                combined.contains("UNSUPPORTED_FORMAT") ->
                PlaybackFailureKind.PARSER

            combined.contains("MEDIA_URI_INVALID") ||
                combined.contains("PROVIDER_UNAVAILABLE") ||
                code.startsWith("ERROR_CODE_IO_") ||
                combined.contains("IOEXCEPTION") ||
                combined.contains("DATASOURCE") ||
                combined.contains("DATA_SOURCE") ||
                combined.contains("SOURCE") ->
                PlaybackFailureKind.SOURCE

            else ->
                PlaybackFailureKind.UNKNOWN
        }

        val legacyCategory = when (kind) {
            PlaybackFailureKind.MEDIA_NOT_FOUND,
            PlaybackFailureKind.PERMISSION,
            PlaybackFailureKind.SOURCE -> ErrorCategory.SOURCE_UNAVAILABLE
            PlaybackFailureKind.TIMEOUT -> ErrorCategory.TRANSIENT
            PlaybackFailureKind.DECODER,
            PlaybackFailureKind.CODEC -> ErrorCategory.DECODER_UNSUPPORTED
            PlaybackFailureKind.PARSER,
            PlaybackFailureKind.RENDERER -> ErrorCategory.NON_RECOVERABLE
            PlaybackFailureKind.LIFECYCLE,
            PlaybackFailureKind.STALE,
            PlaybackFailureKind.UNKNOWN -> ErrorCategory.UNKNOWN
        }

        // Retrying is intentionally narrow. Missing files, revoked permissions,
        // parser failures and decoder/renderer failures are deterministic for the
        // current source/device and must not be retried as a masking mechanism.
        val retryable = when (kind) {
            PlaybackFailureKind.TIMEOUT -> true
            PlaybackFailureKind.SOURCE ->
                code.contains("IO_UNSPECIFIED") ||
                    code.contains("CONNECTION_CLOSED") ||
                    details.contains("TEMPORARY")
            else -> false
        }

        return ErrorClassification(
            kind = kind,
            legacyCategory = legacyCategory,
            retryable = retryable,
        )
    }

    fun isRetryableFailure(kind: PlaybackFailureKind): Boolean =
        when (kind) {
            PlaybackFailureKind.TIMEOUT,
            PlaybackFailureKind.SOURCE -> true
            else -> false
        }

    fun isRetryable(category: ErrorCategory): Boolean = when (category) {
        ErrorCategory.SOURCE_UNAVAILABLE,
        ErrorCategory.TRANSIENT,
        ErrorCategory.UNKNOWN -> true
        ErrorCategory.DECODER_UNSUPPORTED,
        ErrorCategory.NON_RECOVERABLE -> false
    }
}
