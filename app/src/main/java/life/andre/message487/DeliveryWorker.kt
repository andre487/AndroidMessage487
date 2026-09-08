package life.andre.message487

import life.andre.message487.diagnostics.DiagnosticEvent
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class DeliveryScheduler(context: Context) {
    private val manager = WorkManager.getInstance(context)

    fun schedule(id: String, replace: Boolean = false) {
        val work = OneTimeWorkRequestBuilder<DeliveryWorker>()
            .setInputData(workDataOf("event_id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniqueWork("event-$id", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, work)
    }

    fun startRecovery() {
        manager.enqueueUniquePeriodicWork("outbox-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RecoveryWorker>(15, TimeUnit.MINUTES).build())
    }
}

class DeliveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val graph = MessageGraph.get(applicationContext)
        val id = inputData.getString("event_id") ?: return@withContext Result.failure()
        try {
            if (graph.settings.state.value.paused) return@withContext Result.retry()
            val attempt = try {
                graph.outbox.beginAttempt(id)
            } catch (databaseError: android.database.SQLException) {
                throw databaseError
            } catch (error: Exception) {
                graph.diagnostics.record(DiagnosticEvent.PAYLOAD_UNREADABLE, error = error)
                graph.outbox.blockUnreadable(id)
                return@withContext Result.success()
            } ?: return@withContext Result.success()
            graph.diagnostics.record(DiagnosticEvent.DELIVERY_STARTED)
            val request = attempt.request
            val result = if (validWebhookUrl(request.url, BuildConfig.DEBUG)) {
                WebhookClient().sendJson(request.url, id, request.json, request.requireAck)
            } else DeliveryResult(id, DeliveryStatus.HTTP_ERROR)
            graph.diagnostics.record(DiagnosticEvent.DELIVERY_FINISHED, outcome = result.status.name, http = result.httpCode)
            val state = graph.outbox.finish(id, attempt.token, result)
            if (state == QueueState.RETRY) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            graph.diagnostics.record(DiagnosticEvent.DELIVERY_FAILED, error = error)
            graph.captureFailed()
            Result.retry()
        }
    }
}

class RecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            MessageGraph.get(applicationContext).recover()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            MessageGraph.get(applicationContext).diagnostics.record(DiagnosticEvent.RECOVERY_FAILED, error = error)
            Result.retry()
        }
    }
}
