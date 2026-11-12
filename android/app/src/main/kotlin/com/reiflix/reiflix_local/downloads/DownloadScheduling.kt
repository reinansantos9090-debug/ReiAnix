package com.reiflix.reiflix_local.downloads

import android.app.*
import android.app.job.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

object DownloadScheduling {
    const val NOTICE=8701
    private const val JOB=8702
    // Completed notifications must outlive the temporary WorkManager/UIDT
    // foreground group, which Android removes when the transfer job ends.
    private const val COMPLETED_CHANNEL="downloads_complete"
    fun hasUserJob(context: Context): Boolean = Build.VERSION.SDK_INT>=34 &&
        context.getSystemService(JobScheduler::class.java).getPendingJob(JOB)!=null
    suspend fun reconfigure(context: Context) = withContext(Dispatchers.IO) {
        if(Build.VERSION.SDK_INT>=34) context.getSystemService(JobScheduler::class.java).cancel(JOB)
        WorkManager.getInstance(context).cancelUniqueWork("reianix.native-downloads").result.get()
        schedule(context)
    }
    suspend fun schedule(context: Context, userInitiated: Boolean = true) {
        val wifi=com.reiflix.reiflix_local.ReiAnixAppDependencies.from(context).sqliteSettingsRepository.getPreference("downloads.wifi_only","false")=="true"
        var uidt=hasUserJob(context)
        if(Build.VERSION.SDK_INT>=34 && userInitiated && !uidt) {
            try {
                val job=JobInfo.Builder(JOB,ComponentName(context,DownloadTransferJob::class.java))
                    .setUserInitiated(true).setRequiredNetworkType(if(wifi)JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY).build()
                uidt=context.getSystemService(JobScheduler::class.java).schedule(job)==JobScheduler.RESULT_SUCCESS
            } catch (_: Exception) { /* Durable framework fallback below. */ }
        }
        val request=OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if(wifi)NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setInitialDelay(0,TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build()
        // A successor closes the enqueue-versus-runner-finish race. A KEEP-only queue can
        // strand a new download when a previous worker is just returning success.
        WorkManager.getInstance(context).enqueueUniqueWork("reianix.native-downloads",
            if(userInitiated) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,request)
    }
    private val covers=java.util.concurrent.ConcurrentHashMap<String,android.graphics.Bitmap>()
    private fun itemNotification(context: Context,item: DownloadItem?,text: String): Notification {
        val manager=context.getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT>=26) {
            manager.createNotificationChannel(NotificationChannel("downloads","Downloads",NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(COMPLETED_CHANNEL,"Downloads concluídos",NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open=PendingIntent.getActivity(context,0,Intent(context,DownloadsActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val completed=item?.state=="COMPLETE"
        val builder=NotificationCompat.Builder(context,if(completed) COMPLETED_CHANNEL else "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open).setOnlyAlertOnce(true)
            .setCategory(if(completed) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_PROGRESS)
        // Active transfers belong to the foreground-job group. Completion must
        // not: the OS may dismiss grouped children when it stops that job.
        if(!completed) builder.setGroup("reianix.downloads")
        if(item==null) return builder.setContentTitle("Downloads do ReiAnix").setContentText(text).setOngoing(true).build()
        val active=item.state in DownloadState.pending
        val progress=downloadPercent(item)
        val speed=item.json.optLong("speed")
        val size=android.text.format.Formatter.formatFileSize(context,item.bytes)
        // Reuse the size already discovered in the download editor. The
        // transfer's authoritative 'total' takes precedence as soon as the
        // server supplies it; HLS estimates are always marked approximate.
        val displayTotal=downloadDisplaySize(item)
        val total=displayTotal?.let {
            (if(it.exact) "" else "≈ ")+android.text.format.Formatter.formatFileSize(context,it.bytes)
        } ?: "tamanho desconhecido"
        val eta=if(speed>0 && displayTotal?.exact==true && displayTotal.bytes>item.bytes) {
            val seconds=(displayTotal.bytes-item.bytes)/speed; " • ${seconds/60}m ${seconds%60}s"
        } else ""
        val detail="${DownloadState.labels[item.state] ?: item.state} • "+(progress?.let {"$it% • "} ?: "")+"$size / $total"+
            (if(speed>0) " • ${android.text.format.Formatter.formatFileSize(context,speed)}/s" else "")+eta
        builder.setContentTitle(item.json.optString("displayAnime").ifBlank {item.json.optString("animeTitle").ifBlank {item.name}})
            .setContentText(item.name).setStyle(NotificationCompat.BigTextStyle().bigText(item.name+"\n"+detail))
            .setSubText(if(active && progress!=null) "$progress%" else DownloadState.labels[item.state])
            .setOngoing(active)
        if(active) {
            builder.setProgress(100,progress ?: 0,progress==null)
        } else {
            // A completed file is not an active foreground transfer. Do not
            // leave a 100% "downloading" progress indicator on Android.
            builder.setProgress(0,0,false).setAutoCancel(item.state!="COMPLETE")
            if(item.state=="COMPLETE") {
                // Completed notifications stay visible until the user dismisses
                // them. An automatic timeout hid the only completion feedback.
                // Explicit completion symbol for the status bar and for Android
                // notification cards that support preferring the small icon.
                // Some Samsung One UI versions enforce the launcher icon on cards.
                builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .addExtras(android.os.Bundle().apply {
                        putBoolean("android.app.preferSmallIcon", true)
                    })
                    .setContentText("Download concluído • ${item.name}")
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setOngoing(false)
            }
        }
        val path=item.json.optString("coverCache")
        covers[path]?.let {builder.setLargeIcon(it)}
        if(item.state!="SAVING" && item.state!="COMPLETE" && item.state!="CANCELLED" && item.state!="CORRUPT") {
            val command=if(active)"pause" else "resume"
            fun action(action: String)=PendingIntent.getBroadcast(context,(item.id+action).hashCode(),Intent(context,DownloadPauseReceiver::class.java)
                .setData(android.net.Uri.parse("reianix-download://"+item.id+"/"+action)).putExtra("id",item.id).putExtra("command",action),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(if(active)android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,if(active)"Pausar" else "Retomar",action(command))
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel,"Cancelar",action("cancel"))
        }
        return builder.build()
    }
    fun notification(context: Context,text: String): Notification {
        itemNotification(context,null,text) // Ensure the channel exists before foreground startup.
        return NotificationCompat.Builder(context,"downloads").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloads do ReiAnix").setContentText(text).setOnlyAlertOnce(true).setOngoing(true)
            .setGroup("reianix.downloads").setGroupSummary(true).build()
    }
    /** Called on IO; decode cached local covers only, never introduce more artwork network requests. */
    private val posted=java.util.concurrent.ConcurrentHashMap<String,String>()
    fun publish(context: Context) {
        val rows=DownloadSnapshots.items.value
        rows.forEach { item ->
            val marker=item.state+":"+item.bytes+":"+item.total+":"+item.json.optLong("detectedSize")+
                ":"+item.json.optBoolean("detectedSizeExact")+":"+item.json.optLong("hlsEstimatedTotal")+
                ":"+item.json.optLong("speed")+":"+item.json.optString("coverCache")
            if(posted[item.id]==marker)return@forEach
            val path=item.json.optString("coverCache")
            if(path.isNotBlank() && !covers.containsKey(path) && covers.size<30) runCatching {
                val file=if(path.startsWith("file:"))android.net.Uri.parse(path).path else path
                val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                android.graphics.BitmapFactory.decodeFile(file,bounds)
                val options=android.graphics.BitmapFactory.Options().apply {inSampleSize=(maxOf(bounds.outWidth,bounds.outHeight)/160).coerceAtLeast(1)}
                android.graphics.BitmapFactory.decodeFile(file,options)?.let {covers[path]=it}
            }
            val manager=context.getSystemService(NotificationManager::class.java)
            if(item.state in setOf("CANCELLED","CORRUPT")) {
                manager.cancel(noticeId(item.id))
                manager.cancel(completedNoticeId(item.id))
                posted[item.id]=marker
                return@forEach
            }
            if(item.state=="COMPLETE") {
                // An independent, non-foreground notification survives jobFinished()
                // and ForegroundInfo teardown. It stays until user dismissal.
                runCatching {
                    manager.cancel(noticeId(item.id))
                    manager.notify(completedNoticeId(item.id),itemNotification(context,item,""))
                }.onSuccess {posted[item.id]=marker}
            } else {
                runCatching {
                    manager.cancel(completedNoticeId(item.id))
                    manager.notify(noticeId(item.id),itemNotification(context,item,""))
                }.onSuccess {posted[item.id]=marker}
            }
        }
    }
    fun clear(context: Context,id: String) {
        posted.remove(id)
        context.getSystemService(NotificationManager::class.java).let {
            it.cancel(noticeId(id))
            it.cancel(completedNoticeId(id))
        }
    }
    private fun noticeId(id: String)=10000+(id.hashCode() and 0x3fffffff)
    // Negative IDs cannot collide with transfer notifications or NOTICE=8701.
    private fun completedNoticeId(id: String)=Int.MIN_VALUE or (id.hashCode() and 0x3fffffff)

}

/** API 34+: user-initiated transfers avoid Android 16 long-worker quota for an explicit download. */
class DownloadTransferJob: JobService() {
    private var running: Job?=null
    @Volatile private var stopped=false
    override fun onStartJob(params: JobParameters): Boolean {
        if(Build.VERSION.SDK_INT<34) return false
        stopped=false
        setNotification(params,DownloadScheduling.NOTICE,DownloadScheduling.notification(this,"Preparando downloads"),JOB_END_NOTIFICATION_POLICY_REMOVE)
        running=CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            var retry=false
            try {
                retry=DownloadEngine(applicationContext).run({ text ->
                    setNotification(params,DownloadScheduling.NOTICE,DownloadScheduling.notification(this@DownloadTransferJob,text),JOB_END_NOTIFICATION_POLICY_REMOVE)
                    DownloadSnapshots.refresh(this@DownloadTransferJob)
                    DownloadScheduling.publish(this@DownloadTransferJob)
                })
            } catch(cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { retry=true }
            finally { withContext(NonCancellable+Dispatchers.Main) { if(!stopped) jobFinished(params,retry) } }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        stopped=true
        running?.cancel()
        running=null
        // Android may defer this user-initiated job under competing network/system
        // work. Start the durable fallback immediately, without waiting for an
        // unrelated browser download to finish.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { DownloadScheduling.schedule(applicationContext,userInitiated=false) }
        }
        return true
    }
    override fun onDestroy() { stopped=true; running?.cancel(); super.onDestroy() }
}

@androidx.annotation.Keep
class DownloadWorker(context: Context,parameters: WorkerParameters): CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result {
        // A merely pending JobScheduler entry does not mean an actual transfer
        // is running. Do not block the foreground fallback behind it.
        if(DownloadScheduling.hasUserJob(applicationContext) && DownloadEngine.isRunning()) return Result.retry()
        return try {
            // On modern Android the durable fallback uses bounded, resumable work. Explicit
            // user transfers use UIDT above; older Android uses WorkManager's foreground API.
            if(Build.VERSION.SDK_INT<34) setForeground(ForegroundInfo(DownloadScheduling.NOTICE,
                DownloadScheduling.notification(applicationContext,"Preparando downloads")))
            val again=DownloadEngine(applicationContext).run({ text ->
                runCatching { applicationContext.getSystemService(NotificationManager::class.java)
                    .notify(DownloadScheduling.NOTICE,DownloadScheduling.notification(applicationContext,text)) }
                DownloadSnapshots.refresh(applicationContext)
                DownloadScheduling.publish(applicationContext)
            }, if(Build.VERSION.SDK_INT>=34) 8*60_000L else Long.MAX_VALUE)
            if(again) Result.retry() else Result.success()
        } catch(cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
        finally { applicationContext.getSystemService(NotificationManager::class.java).cancel(DownloadScheduling.NOTICE); withContext(NonCancellable+Dispatchers.IO) {DownloadSnapshots.refresh(applicationContext);DownloadScheduling.publish(applicationContext)} }
    }
}

class DownloadPauseReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store=DownloadStore(context); store.ready()
                val id=intent.getStringExtra("id")
                if(id!=null) {
                    val command=intent.getStringExtra("command") ?: "pause"
                    if(command in setOf("pause","resume","cancel"))store.control(id,command)
                    if(command=="resume")DownloadScheduling.schedule(context)
                } else store.all().filter { it.state in DownloadState.pending && it.state!="SAVING" }.forEach { store.control(it.id,"pause") }
                DownloadSnapshots.refresh(context)
                DownloadScheduling.publish(context)
            } catch (_: Exception) { /* No background callback may crash the application. */ }
            finally { pending.finish() }
        }
    }
}
