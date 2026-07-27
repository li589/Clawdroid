package com.clawdroid.app.termux

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred

/**
 * Receives Termux RUN_COMMAND results via PendingIntent callback.
 */
class TermuxResultService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        val bundle = intent.getBundleExtra(TermuxBridge.resultBundleKey())
        if (executionId < 0) {
            Log.w(TAG, "Missing execution id")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (bundle == null) {
            Log.w(TAG, "Missing result bundle for executionId=$executionId")
            TermuxResultDispatcher.complete(
                executionId,
                TermuxBridge.TermuxExecResult(
                    stdout = "",
                    stderr = "",
                    exitCode = -1,
                    errCode = Activity.RESULT_CANCELED,
                    errMsg = "Termux 回调缺少 result bundle（请确认 Termux ≥0.109，并已设置 allow-external-apps=true）"
                )
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        TermuxResultDispatcher.complete(executionId, TermuxBridge.parseResultBundle(bundle))
        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_EXECUTION_ID = "execution_id"
        private const val TAG = "TermuxResultService"
    }
}

/**
 * Correlates Termux PendingIntent callbacks with in-flight bridge executions.
 */
object TermuxResultDispatcher {
    private val pendingResults = mutableMapOf<Int, CompletableDeferred<TermuxBridge.TermuxExecResult>>()

    fun registerPending(
        executionId: Int,
        deferred: CompletableDeferred<TermuxBridge.TermuxExecResult>
    ) {
        synchronized(pendingResults) {
            pendingResults[executionId] = deferred
        }
    }

    fun complete(executionId: Int, result: TermuxBridge.TermuxExecResult) {
        val deferred = synchronized(pendingResults) {
            pendingResults.remove(executionId)
        } ?: return
        deferred.complete(result)
    }

    fun cancel(executionId: Int) {
        val deferred = synchronized(pendingResults) {
            pendingResults.remove(executionId)
        } ?: return
        deferred.cancel()
    }
}
