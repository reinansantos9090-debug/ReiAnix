package com.reiflix.reiflix_local.data.anilist

import android.system.Os
import android.text.Html
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.job
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Kotlin implementation of the existing AniList PT-BR synopsis localization contract.
 *
 * The cache path is the legacy cache file so cached translations remain reusable across
 * app launches and the hybrid transition. It is a cache only: SQLite remains the source
 * of truth for the displayed/persisted description. Raw synopsis text and API payloads
 * are never logged.
 */
class ReiAnixAniListDescriptionLocalizer(
    private val cacheFile: File,
    private val transport: AniListTranslationTransport = HttpUrlConnectionAniListTranslationTransport(),
) {
    private val cacheMutex = Mutex()
    private val translationPermits = Semaphore(MAX_CONCURRENT_TRANSLATIONS)
    private val keyLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun localizeToPtBr(description: String, sourceLanguage: String? = null): String? =
        withContext(Dispatchers.IO) { localizeToPtBrOnIo(description, sourceLanguage) }

    private suspend fun localizeToPtBrOnIo(description: String, sourceLanguage: String?): String? {
        val original = normalizeDescription(description)
        if (original.isEmpty()) return null

        var source = normalizeSourceLanguage(sourceLanguage)
        if (source == "unknown") source = detectDescriptionLanguage(original).first
        if (source == "unknown") return null
        if (source == "pt") {
            cacheEntry(
                cacheKey(original, source),
                JSONObject()
                    .put("status", "not_needed")
                    .put("translated", original)
                    .put("source_language", "pt")
                    .put("target_language", TARGET_LANGUAGE)
                    .put("updated_at", epochSeconds()),
            )
            return original
        }

        val key = cacheKey(original, source)
        val lock = keyLocks.getOrPut(key) { Mutex() }
        return try {
            lock.withLock {
                cachedTranslation(original, source)?.let { return@withLock it }
                val now = epochSeconds()
                val existing = readCacheEntry(key)
                val retryAfter = existing?.optDouble("retry_after", 0.0) ?: 0.0
                if (existing?.optString("status") == "failed" && now < retryAfter) {
                    return@withLock null
                }

                val translated = translationPermits.withPermit { translateDescription(original, source) }
                if (translated != null &&
                    isValidTranslation(original, translated) &&
                    isPtBrDescription(translated)
                ) {
                    cacheEntry(
                        key,
                        JSONObject()
                            .put("status", "ok")
                            .put("translated", translated)
                            .put("source_language", source)
                            .put("target_language", TARGET_LANGUAGE)
                            .put("updated_at", epochSeconds()),
                    )
                    translated
                } else {
                    if (translated != null) {
                        Log.w(
                            TAG,
                            "Translation response rejected by final PT-BR validation sourceLanguage=$source",
                        )
                    }
                    // Bound repeated failures without treating the failure as a translation.
                    cacheEntry(
                        key,
                        JSONObject()
                            .put("status", "failed")
                            .put("source_language", source)
                            .put("target_language", TARGET_LANGUAGE)
                            .put("updated_at", epochSeconds())
                            .put("retry_after", epochSeconds() + FAILED_RETRY_WINDOW_SECONDS),
                    )
                    null
                }
            }
        } finally {
            evictUnusedKeyLocksIfNeeded()
        }
    }

    suspend fun getCachedDescriptionPtBr(description: String, sourceLanguage: String? = null): String? =
        withContext(Dispatchers.IO) { getCachedDescriptionPtBrOnIo(description, sourceLanguage) }

    private suspend fun getCachedDescriptionPtBrOnIo(description: String, sourceLanguage: String?): String? {
        val original = normalizeDescription(description)
        if (original.isEmpty()) return null
        var source = normalizeSourceLanguage(sourceLanguage)
        if (source == "unknown") source = detectDescriptionLanguage(original).first
        if (source == "pt") return original
        if (source == "unknown") return null
        return cachedTranslation(original, source)
    }

    fun normalizeDescription(description: String?): String {
        val raw = description.orEmpty()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("(?i)<br\\s*/?>"), "\n\n")
            .replace(Regex("(?i)</p>\\s*<p\\b[^>]*>"), "\n\n")
        val htmlText = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        return htmlText.replace("\u00a0", " ")
            .replace(Regex("[\t ]+"), " ")
            .replace(Regex(" *\n *"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .split(Regex("\n\\s*\n"))
            .map { paragraph -> paragraph.split(Regex("\\s+")).filter(String::isNotBlank).joinToString(" ") }
            .filter(String::isNotBlank)
            .joinToString("\n\n")
    }

    fun detectDescriptionLanguage(description: String): Pair<String, Double> {
        val normalized = normalizeDescription(description)
        if (normalized.isEmpty()) return "unknown" to 0.0
        for (char in normalized) {
            val code = char.code
            when (code) {
                in 0x3040..0x30FF -> return "ja" to 0.99
                in 0xAC00..0xD7AF -> return "ko" to 0.99
                in 0x0400..0x04FF -> return "ru" to 0.99
                in 0x0600..0x06FF -> return "ar" to 0.99
                in 0x0370..0x03FF -> return "el" to 0.99
            }
        }
        if (normalized.any { it.code in 0x4E00..0x9FFF }) return "zh-CN" to 0.99

        val tokens = Regex("[\\p{L}]+").findAll(normalized.lowercase(Locale.ROOT))
            .map { it.value }.toList()
        if (tokens.isEmpty()) return "unknown" to 0.0
        val unique = tokens.toSet()
        val ranked = LANGUAGE_PROFILES.map { (language, words) ->
            val hits = unique.intersect(words).size
            val weighted = tokens.count { it in words } +
                if (language == "pt") tokens.count { token -> token.any { it in "ãõçáéíóúâêô" } } else 0
            Triple(language, hits, weighted)
        }.sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third })
        val (language, hits, weighted) = ranked.first()
        val second = ranked.getOrNull(1) ?: return "unknown" to 0.0
        val strongEnough = hits >= 3 || (hits >= 2 && tokens.size <= 6 && weighted >= 3)
        val margin = hits - second.second
        if (!strongEnough || (margin < 1 && weighted - second.third < 2)) return "unknown" to 0.0
        val confidence = minOf(0.99, 0.45 + (hits.toDouble() / maxOf(4, tokens.size)) * 0.5 + minOf(0.15, maxOf(0, margin) * 0.05))
        return language to String.format(Locale.ROOT, "%.3f", confidence).toDouble()
    }

    fun isValidTranslation(sourceText: String, translatedText: String): Boolean {
        val source = normalizeDescription(sourceText)
        val translated = normalizeDescription(translatedText)
        if (source.isEmpty() || translated.isEmpty()) return false
        if (translated.equals(source, ignoreCase = true)) return false
        if (Regex("(?i)<(?:html|body|script|json)\\b").containsMatchIn(translatedText)) return false
        val trimmed = translatedText.trim()
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
        ) {
            try {
                if (trimmed.startsWith("{")) JSONObject(trimmed) else JSONArray(trimmed)
                return false
            } catch (_: Exception) {
                // Not a JSON response envelope.
            }
        }
        val upper = translated.uppercase(Locale.ROOT)
        val errorMarkers = listOf(
            "MYMEMORY WARNING", "MYMEMORY ERROR", "RATE LIMIT", "TOO MANY REQUESTS",
            "SERVICE UNAVAILABLE", "INTERNAL SERVER ERROR", "BAD REQUEST", "TRANSLATION ERROR", "ERROR:",
        )
        if (errorMarkers.any(upper::contains)) return false
        val sourceCompact = source.filterNot(Char::isWhitespace).length
        val translatedCompact = translated.filterNot(Char::isWhitespace).length
        if (sourceCompact >= 80 && translatedCompact < maxOf(8, (sourceCompact * 0.08).toInt())) return false
        if (translatedCompact > maxOf(120, sourceCompact * 4)) return false
        return true
    }

    /**
     * Validates the final synopsis contract. A PT-BR source may be retained without
     * translation; non-Portuguese source text must produce a distinct, valid PT-BR result.
     * Callers use this before treating persisted text as complete.
     */
    fun isValidPtBrResult(sourceText: String, translatedText: String): Boolean {
        val source = normalizeDescription(sourceText)
        val translated = normalizeDescription(translatedText)
        if (source.isEmpty() || translated.isEmpty() || !isPtBrDescription(translated)) return false
        if (source.equals(translated, ignoreCase = true)) {
            return detectDescriptionLanguage(source).first == "pt"
        }
        return isValidTranslation(source, translated)
    }

    fun isPtBrDescription(text: String): Boolean {
        val normalized = normalizeDescription(text)
        if (normalized.isEmpty()) return false
        if (detectDescriptionLanguage(normalized).first == "pt") return true
        val tokens = Regex("[\\p{L}]+").findAll(normalized.lowercase(Locale.ROOT)).map { it.value }.toSet()
        // Accent marks alone are not evidence of Portuguese: French, Spanish and
        // other languages routinely contain accented characters. Require lexical
        // evidence when the conservative detector cannot establish the language.
        return tokens.intersect(PORTUGUESE_WORDS).size >= 2
    }

    private fun evictUnusedKeyLocksIfNeeded() {
        if (keyLocks.size <= MAX_CACHE_ENTRIES) return
        keyLocks.entries.forEach { entry ->
            if (keyLocks.size > MAX_CACHE_ENTRIES &&
                !entry.value.isLocked
            ) {
                keyLocks.remove(entry.key, entry.value)
            }
        }
    }

    private suspend fun translateDescription(original: String, source: String): String? {
        val paragraphs = original.split("\n\n").filter(String::isNotBlank)
        if (paragraphs.isEmpty()) return null
        val translatedParagraphs = mutableListOf<String>()
        for (paragraph in paragraphs) {
            val chunks = splitUtf8Chunks(paragraph, MAX_TRANSLATION_BYTES) ?: return null
            val translatedChunks = mutableListOf<String>()
            for (chunk in chunks) {
                val translated = translateChunk(chunk, source) ?: return null
                translatedChunks += translated
            }
            translatedParagraphs += translatedChunks.joinToString(" ")
        }
        return normalizeDescription(translatedParagraphs.joinToString("\n\n"))
    }

    private suspend fun translateChunk(text: String, source: String): String? {
        val encodedText = URLEncoder.encode(text, Charsets.UTF_8.name())
        val langPair = URLEncoder.encode("$source|$TARGET_LANGUAGE", Charsets.UTF_8.name())
        val url = "$TRANSLATION_ENDPOINT?q=$encodedText&langpair=$langPair&mt=1"
        var attempt = 0
        while (attempt < MAX_TRANSLATION_ATTEMPTS) {
            attempt++
            val response = try {
                transport.get(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                return null
            } catch (_: Exception) {
                return null
            }
            if (response.statusCode !in 200..299) {
                // Retry only when the service explicitly asks the client to wait.
                val retryAfter = response.retryAfterSeconds
                if (attempt < MAX_TRANSLATION_ATTEMPTS &&
                    (response.statusCode == 429 || response.statusCode >= 500) &&
                    retryAfter != null &&
                    retryAfter in 0L..MAX_RETRY_AFTER_SECONDS
                ) {
                    delay(retryAfter * 1000L)
                    continue
                }
                return null
            }
            val translated = try {
                val payload = JSONObject(response.body)
                val status = payload.opt("responseStatus")?.toString()?.toIntOrNull() ?: 200
                if (status != 200) {
                    // The translation endpoint returned an application-level failure
                    // without an HTTP Retry-After header. Do not add speculative delays.
                    return null
                }
                payload.optJSONObject("responseData")?.optString("translatedText")
                    ?.takeIf(String::isNotBlank)
            } catch (_: Exception) {
                null
            }
            if (translated != null && isValidTranslation(text, translated)) {
                return normalizeDescription(translated)
            }
            if (translated != null) {
                Log.w(
                    TAG,
                    "Translation response rejected by content validation sourceLanguage=$source",
                )
            }
            return null
        }
        return null
    }

    private fun splitUtf8Chunks(paragraph: String, maxBytes: Int): List<String>? {
        val words = paragraph.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        var bytes = 0
        for (word in words) {
            val wordBytes = word.toByteArray(Charsets.UTF_8).size
            if (wordBytes > maxBytes) return null
            val extra = wordBytes + if (current.isNotEmpty()) 1 else 0
            if (current.isNotEmpty() && bytes + extra > maxBytes) {
                chunks += current.toString()
                current = StringBuilder()
                bytes = 0
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
            bytes += wordBytes + if (current.length > word.length) 1 else 0
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    private suspend fun cachedTranslation(original: String, source: String): String? {
        val entry = readCacheEntry(cacheKey(original, source)) ?: return null
        if (entry.optString("status") != "ok") return null
        val translated = normalizeDescription(entry.optString("translated"))
        return translated.takeIf { isValidTranslation(original, it) && isPtBrDescription(it) }
    }

    private suspend fun readCacheEntry(key: String): JSONObject? = cacheMutex.withLock {
        withContext(Dispatchers.IO) {
            val file = cacheFile
            if (!file.isFile) return@withContext null
            try {
                JSONObject(file.readText(Charsets.UTF_8)).optJSONObject(key)
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun cacheEntry(key: String, entry: JSONObject) {
        cacheMutex.withLock {
            withContext(Dispatchers.IO) {
                val file = cacheFile
                val parent = file.parentFile ?: return@withContext
                try {
                    if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) return@withContext
                    val root = if (file.isFile) {
                        try { JSONObject(file.readText(Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }
                    } else JSONObject()
                    root.put(key, entry)
                    trimCache(root)
                    val temporary = File.createTempFile(".anilist-ptbr-", ".tmp", parent)
                    try {
                        temporary.writeText(root.toString(), Charsets.UTF_8)
                        Os.rename(temporary.absolutePath, file.absolutePath)
                    } finally {
                        if (temporary.exists()) temporary.delete()
                    }
                } catch (_: Exception) {
                    // A cache write failure must never turn a valid metadata result into failure.
                }
            }
        }
    }

    private fun trimCache(cache: JSONObject) {
        if (cache.length() <= MAX_CACHE_ENTRIES) return
        val ranked = cache.keys().asSequence().mapNotNull { key ->
            cache.optJSONObject(key)?.let { entry -> Triple(key, entry, entry.optDouble("updated_at", 0.0)) }
        }.sortedByDescending { it.third }.take(MAX_CACHE_ENTRIES).toList()
        val keys = cache.keys().asSequence().toList()
        keys.forEach { key -> cache.remove(key) }
        ranked.forEach { retainedKey -> cache.put(retainedKey.first, retainedKey.second) }
    }

    private fun normalizeSourceLanguage(value: String?): String {
        val source = value.orEmpty().trim().lowercase(Locale.ROOT).replace('_', '-')
        return when (source) {
            "pt", "pt-br", "pt-pt" -> "pt"
            "en", "en-us", "en-gb" -> "en"
            "es", "fr", "it", "de", "nl", "pl", "tr" -> source
            "ja" -> "ja"
            "zh", "zh-cn" -> "zh-CN"
            "ko", "ru", "ar", "el" -> source
            else -> "unknown"
        }
    }

    private fun cacheKey(description: String, source: String): String {
        val material = "$source|$TARGET_LANGUAGE|$description"
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun epochSeconds(): Double = System.currentTimeMillis() / 1000.0

    companion object {
        private const val TAG = "ReiAnixAniListTranslation"
        const val TARGET_LANGUAGE = "pt-BR"
        const val TRANSLATION_ENDPOINT = "https://api.mymemory.translated.net/get"
        const val MAX_TRANSLATION_BYTES = 480
        const val MAX_CACHE_ENTRIES = 4096
        const val MAX_TRANSLATION_ATTEMPTS = 3
        const val MAX_CONCURRENT_TRANSLATIONS = 2
        const val MAX_RETRY_AFTER_SECONDS = 8L
        const val FAILED_RETRY_WINDOW_SECONDS = 60 * 60.0

        private val PORTUGUESE_WORDS = setOf(
            "que", "uma", "um", "não", "com", "quando", "através", "está", "são", "dos", "das",
            "para", "por", "como", "história", "segue", "jovem", "garota", "garoto", "pessoas",
            "seus", "suas", "eles", "elas", "tem", "têm", "isso", "esta", "este", "depois",
            "antes", "entre", "sobre", "sem", "mais", "também", "porque", "onde",
        )

        private val LANGUAGE_PROFILES = mapOf(
            "en" to setOf("the", "this", "that", "with", "from", "when", "where", "after", "before", "into", "about", "story", "follows", "young", "girl", "boy", "people", "their", "they", "is", "are", "has", "have", "for", "and", "but", "not", "who", "his", "her"),
            "pt" to PORTUGUESE_WORDS,
            "es" to setOf("que", "una", "uno", "con", "cuando", "desde", "después", "antes", "sobre", "para", "por", "como", "historia", "sigue", "joven", "chica", "chico", "personas", "sus", "ellos", "ellas", "tiene", "está", "son", "esta", "este", "entre", "pero"),
            "fr" to setOf("que", "une", "un", "avec", "quand", "dans", "après", "avant", "pour", "par", "comme", "histoire", "suit", "jeune", "fille", "garçon", "personnes", "leurs", "ils", "elles", "est", "sont", "avoir", "cette", "ce", "mais", "entre", "sans"),
            "it" to setOf("che", "una", "uno", "con", "quando", "dopo", "prima", "per", "come", "storia", "segue", "giovane", "ragazza", "ragazzo", "persone", "loro", "sono", "questa", "questo", "avere", "ma", "tra", "senza", "dove", "anche"),
            "de" to setOf("der", "die", "das", "ein", "eine", "mit", "wenn", "nach", "vor", "für", "von", "wie", "geschichte", "folgt", "junge", "mädchen", "menschen", "ihre", "sie", "ist", "sind", "haben", "dies", "diese", "aber", "ohne"),
            "nl" to setOf("de", "het", "een", "met", "wanneer", "na", "voor", "van", "zoals", "verhaal", "volgt", "jonge", "meisje", "jongen", "mensen", "hun", "zij", "is", "zijn", "heeft", "maar", "zonder"),
            "pl" to setOf("że", "jest", "są", "z", "do", "dla", "kiedy", "po", "przed", "jak", "historia", "śledzi", "młody", "dziewczyna", "chłopak", "ludzie", "ich", "ma", "ten", "ta", "ale", "bez"),
            "tr" to setOf("bir", "ve", "ile", "ne", "zaman", "sonra", "önce", "için", "gibi", "hikaye", "takip", "genç", "kız", "erkek", "insanlar", "onların", "onlar", "olan", "ama", "bu"),
        )
    }
}

data class AniListTranslationHttpResponse(
    val statusCode: Int,
    val body: String,
    val retryAfterSeconds: Long? = null,
)

fun interface AniListTranslationTransport {
    suspend fun get(url: String): AniListTranslationHttpResponse
}

/** HTTP adapter for MyMemory; network I/O is off the main thread and cancellable. */
class HttpUrlConnectionAniListTranslationTransport(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 12_000,
) : AniListTranslationTransport {
    init {
        require(connectTimeoutMs > 0 && readTimeoutMs > 0)
    }

    override suspend fun get(url: String): AniListTranslationHttpResponse = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val active = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "ReiAnix/1.0 (personal-use)")
            }
            connection = active
            coroutineContext.job.invokeOnCompletion { cause ->
                if (cause is CancellationException) active.disconnect()
            }
            val code = active.responseCode
            val stream = if (code in 200..299) active.inputStream else active.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val retryAfter = active.getHeaderField("Retry-After")?.toLongOrNull()
            AniListTranslationHttpResponse(code, body, retryAfter)
        } finally {
            connection?.disconnect()
        }
    }
}
