package com.reianix.reianix_local.data.releases

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.reianix.reianix_local.ReiAnixAppDependencies
import com.reianix.reianix_local.R
import com.reianix.reianix_local.data.anilist.AniListResult
import com.reianix.reianix_local.data.anilist.ReiAnixAniListClient
import com.reianix.reianix_local.data.anilist.ReiAnixAiringRecord
import com.reianix.reianix_local.data.anilist.ReiAnixOfficialLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.TimeUnit

/** Background release alerts, scheduled only with user/Android authorization. */
object ReiAnixReleaseMonitor {
    private const val PREFERENCES = "reianix_release_monitor"
    private const val ENABLED = "enabled"
    private const val AWAITING_PERMISSION = "enable_when_permission_granted"
    private const val LAST_PERMISSION_ALLOWED = "last_permission_allowed"
    const val CHANNEL_ID = "reianix_episode_releases"
    private const val WORK_NAME = "reianix_release_checks_v1"

    /** Existing installations retain their saved choice; first-run setup owns the default. */
    fun hasSavedPreference(context: Context): Boolean = preferences(context).contains(ENABLED)

    fun enabled(context: Context): Boolean = preferences(context).getBoolean(ENABLED, false)

    /** The setting reflects both the user's preference and the Android permission. */
    fun active(context: Context): Boolean = enabled(context) && notificationsAllowed(context)

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = preferences(context)
        val editor = prefs.edit()
            .putBoolean(ENABLED, enabled)
            .putBoolean(AWAITING_PERMISSION, false)
            .putBoolean(LAST_PERMISSION_ALLOWED, notificationsAllowed(context))
        if (enabled && !prefs.contains("last_checked")) {
            editor.putLong("last_checked", System.currentTimeMillis() / 1000L)
        }
        editor.apply()
        schedule(context)
    }

    /**
     * First-run or settings permission result. A denial is visible as OFF, but if
     * Android permission is granted later the default request can be completed.
     */
    fun recordPermissionDecision(context: Context, granted: Boolean) {
        if (granted && notificationsAllowed(context)) {
            setEnabled(context, true)
        } else {
            preferences(context).edit()
                .putBoolean(ENABLED, false)
                .putBoolean(AWAITING_PERMISSION, true)
                .putBoolean(LAST_PERMISSION_ALLOWED, false)
                .apply()
            schedule(context)
        }
    }

    /** Run only on Activity resume, not in the library/cover-loading pipeline. */
    fun reconcilePermission(context: Context) {
        val prefs = preferences(context)
        if (!prefs.contains(ENABLED)) return
        val allowed = notificationsAllowed(context)
        if (allowed && prefs.getBoolean(AWAITING_PERMISSION, false)) {
            setEnabled(context, true)
            return
        }
        if (prefs.getBoolean(LAST_PERMISSION_ALLOWED, allowed) != allowed) {
            prefs.edit().putBoolean(LAST_PERMISSION_ALLOWED, allowed).apply()
            if (enabled(context)) schedule(context)
        }
    }

    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!active(context)) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val work = PeriodicWorkRequestBuilder<ReiAnixReleaseWorker>(4, TimeUnit.HOURS)
            .setConstraints(constraints).build()
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, work)
    }

    fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Platform availability is never inferred from AniList broadcast timestamps. */
    fun officialWatchLink(links: List<ReiAnixOfficialLink>): ReiAnixOfficialLink? =
        links.firstOrNull { item ->
            runCatching {
                val uri = URI(item.url)
                val host = uri.host?.lowercase() ?: return@runCatching false
                val accepted = listOf(
                    "crunchyroll.com", "netflix.com", "hidive.com",
                    "primevideo.com", "disneyplus.com", "hulu.com",
                )
                uri.scheme.equals("https", ignoreCase = true) &&
                    uri.userInfo == null &&
                    accepted.any { host == it || host.endsWith(".$it") }
            }.getOrDefault(false)
        }

    internal fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

class ReiAnixReleaseWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!ReiAnixReleaseMonitor.enabled(context) ||
            !ReiAnixReleaseMonitor.notificationsAllowed(context)
        ) return Result.success()
        val now = (System.currentTimeMillis() / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val prefs = ReiAnixReleaseMonitor.preferences(context)
        val checked = prefs.getLong("last_checked", now.toLong()).coerceAtLeast(0L)
        val from = maxOf(now.toLong() - 48L * 3600L, checked - 1800L)
            .coerceIn(0L, now.toLong()).toInt()
        val candidates: Map<Int, String> = try {
            val db = ReiAnixAppDependencies.from(context).sqliteDatabase
            withContext(Dispatchers.IO) {
                db.read { connection ->
                    val matches = linkedMapOf<Int, String>()
                    connection.rawQuery(
                        """SELECT a.anilist_id, MIN(a.title) AS title
                           FROM anime a
                           WHERE a.anilist_id IS NOT NULL AND a.anilist_id > 0
                             AND EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id = a.id)
                           GROUP BY a.anilist_id
                           ORDER BY CASE WHEN MAX(a.status) = 'RELEASING' THEN 0 ELSE 1 END,
                                    a.anilist_id
                           LIMIT 100""",
                        null,
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val rawId = cursor.getLong(0)
                            if (rawId <= 0L || rawId > Int.MAX_VALUE.toLong()) continue
                            matches[rawId.toInt()] = cursor.getString(1) ?: "Anime"
                        }
                    }
                    matches
                }
            }
        } catch (_: Exception) { return Result.retry() }
        if (candidates.isEmpty()) return Result.success()
        val client = ReiAnixAniListClient(minimumRequestIntervalMs = 1_500)
        val airings = mutableListOf<ReiAnixAiringRecord>()
        for (page in 1..4) {
            if (isStopped) return Result.retry()
            when (val remote = client.getRecentAirings(candidates.keys.toList(), from, now, page)) {
                is AniListResult.Success -> {
                    airings += remote.value.items
                    if (!remote.value.hasNextPage) break
                }
                is AniListResult.Failure -> return Result.retry()
            }
        }
        if (isStopped) return Result.retry()
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    ReiAnixReleaseMonitor.CHANNEL_ID,
                    "Novos episódios de animes",
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        for (airing in airings.distinctBy { it.mediaId to it.episode }) {
            if (!ReiAnixReleaseMonitor.enabled(context)) break
            val key = "sent_" + airing.mediaId + "_" + airing.episode
            if (prefs.getBoolean(key, false)) continue
            val title = candidates[airing.mediaId] ?: continue
            val link = ReiAnixReleaseMonitor.officialWatchLink(airing.officialLinks)
            val intent = Intent(context, ReiAnixReleaseDestinationActivity::class.java).apply {
                putExtra("title", title)
                putExtra("episode", airing.episode)
                putExtra("anilist_id", airing.mediaId)
                putExtra("official_site", link?.site.orEmpty())
                putExtra("official_link", link?.url.orEmpty())
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val notificationId = (airing.mediaId * 31 + airing.episode) and Int.MAX_VALUE
            val pending = PendingIntent.getActivity(
                context, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, ReiAnixReleaseMonitor.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_reianix_notification)
                .setContentTitle("Novo episódio: " + title)
                .setContentText("Episódio " + airing.episode + " estreou. Veja as opções oficiais.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "O episódio " + airing.episode + " de " + title +
                        " estreou. A disponibilidade no Brasil pode variar."
                ))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(notificationId, notification)
                prefs.edit().putBoolean(key, true).apply()
            } catch (_: SecurityException) { return Result.success() }
        }
        prefs.edit().putLong("last_checked", now.toLong()).apply()
        return Result.success()
    }
}
