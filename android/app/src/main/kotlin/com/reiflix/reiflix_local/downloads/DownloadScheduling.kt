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
    fun hasUserJob(context: Context): Boolean = Build.VERSION.SDK_INT>=34 &&
        context.getSystemService(JobScheduler::class.java).getPendingJob(JOB)!=null
    fun schedule(context: Context, userInitiated: Boolean = true) {
        var uidt=hasUserJob(context)
        if(Build.VERSION.SDK_INT>=34 && userInitiated && !uidt) {
            try {
                val job=JobInfo.Builder(JOB,ComponentName(context,DownloadTransferJob::class.java))
                    .setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build()
                uidt=context.getSystemService(JobScheduler::class.java).schedule(job)==JobScheduler.RESULT_SUCCESS
            } catch (_: Exception) { /* Durable framework fallback below. */ }
        }
        val request=OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(if(uidt) 15 else 0,TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build()
        // A successor closes the enqueue-versus-runner-finish race. A KEEP-only queue can
        // strand a new download when a previous worker is just returning success.
        WorkManager.getInstance(context).enqueueUniqueWork("reianix.native-downloads",
            if(userInitiated) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,request)
    }
    fun notification(context: Context,text: String): Notification {
        val manager=context.getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT>=26) manager.createNotificationChannel(NotificationChannel("downloads","Downloads",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(context,0,Intent(context,DownloadsActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause=PendingIntent.getBroadcast(context,0,Intent(context,DownloadPauseReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context,"downloads").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloads do ReiAnix").setContentText(text).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).addAction(android.R.drawable.ic_media_pause,"Pausar todos",pause).build()
    }
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
                })
            } catch(cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { retry=true }
            finally { withContext(NonCancellable+Dispatchers.Main) { if(!stopped) jobFinished(params,retry) } }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped=true; running?.cancel(); running=null; return true }
    override fun onDestroy() { stopped=true; running?.cancel(); super.onDestroy() }
}

class DownloadWorker(context: Context,parameters: WorkerParameters): CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result {
        if(DownloadScheduling.hasUserJob(applicationContext)) return Result.retry()
        return try {
            // On modern Android the durable fallback uses bounded, resumable work. Explicit
            // user transfers use UIDT above; older Android uses WorkManager's foreground API.
            if(Build.VERSION.SDK_INT<34) setForeground(ForegroundInfo(DownloadScheduling.NOTICE,
                DownloadScheduling.notification(applicationContext,"Preparando downloads")))
            val again=DownloadEngine(applicationContext).run({ text ->
                runCatching { applicationContext.getSystemService(NotificationManager::class.java)
                    .notify(DownloadScheduling.NOTICE,DownloadScheduling.notification(applicationContext,text)) }
                DownloadSnapshots.refresh(applicationContext)
            }, if(Build.VERSION.SDK_INT>=34) 8*60_000L else Long.MAX_VALUE)
            if(again) Result.retry() else Result.success()
        } catch(cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
        finally { applicationContext.getSystemService(NotificationManager::class.java).cancel(DownloadScheduling.NOTICE) }
    }
}

class DownloadPauseReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store=DownloadStore(context); store.ready()
                store.all().filter { it.state in DownloadState.pending && it.state!="SAVING" }.forEach { store.control(it.id,"pause") }
                DownloadSnapshots.refresh(context)
            } catch (_: Exception) { /* No background callback may crash the application. */ }
            finally { pending.finish() }
        }
    }
}
