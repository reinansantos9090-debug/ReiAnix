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
    fun schedule(context: Context, userInitiated: Boolean = true) {
        if(Build.VERSION.SDK_INT>=34 && !userInitiated && context.getSystemService(JobScheduler::class.java).getPendingJob(JOB)!=null) return
        if(Build.VERSION.SDK_INT>=34 && userInitiated) {
            val scheduler=context.getSystemService(JobScheduler::class.java)
            if(scheduler.getPendingJob(JOB)!=null) return
            try {
                val job=JobInfo.Builder(JOB,ComponentName(context,DownloadTransferJob::class.java))
                    .setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build()
                if(scheduler.schedule(job)==JobScheduler.RESULT_SUCCESS) return
            } catch (_: Exception) { /* Framework scheduler below remains durable fallback. */ }
        }
        val request=OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("reianix.native-downloads",ExistingWorkPolicy.KEEP,request)
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
    override fun onDestroy() { running?.cancel(); super.onDestroy() }
}

class DownloadWorker(context: Context,parameters: WorkerParameters): CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result {
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
