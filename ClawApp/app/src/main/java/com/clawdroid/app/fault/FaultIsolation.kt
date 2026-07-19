package com.clawdroid.app.fault

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * App-process fault isolation helpers: coroutine guards, error codes, and crash breadcrumbs.
 */
object FaultCodes {
    const val TOOL_UNCAUGHT = "tool_uncaught"
    const val MCP_INTERNAL = "mcp_internal"
    const val ORCHESTRATOR_FAULT = "orchestrator_fault"
    const val OVERVIEW_FAULT = "overview_fault"
}

object FaultIsolation {
    private const val TAG = "ClawFault"
    private const val LAST_FAULT_FILE = "fault-last.json"
    private const val CRASH_LOG_FILE = "fault-crash.log"
    private const val MAX_STACK_CHARS = 4_000
    private const val MAX_CRASH_LOG_BYTES = 256 * 1024

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun exceptionHandler(tag: String): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            recordFault(tag, throwable)
        }
    }

    fun recordFault(tag: String, throwable: Throwable) {
        val message = throwable.message ?: throwable::class.java.simpleName
        Log.e(TAG, "[$tag] $message", throwable)
        val context = appContext ?: return
        runCatching {
            val payload = JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("tag", tag)
                .put("message", message)
                .put("type", throwable::class.java.name)
                .put("stack", throwable.stackTraceToString().take(MAX_STACK_CHARS))
            File(context.filesDir, LAST_FAULT_FILE).writeText(payload.toString())
        }
    }

    fun recordCrash(throwable: Throwable) {
        val context = appContext ?: return
        runCatching {
            val body = buildString {
                append("ts=").append(System.currentTimeMillis()).append('\n')
                append("type=").append(throwable::class.java.name).append('\n')
                append("message=").append(throwable.message ?: "").append('\n')
                append(throwable.stackTraceToString().take(MAX_STACK_CHARS))
                append('\n')
            }
            val file = File(context.filesDir, CRASH_LOG_FILE)
            if (file.exists() && file.length() > MAX_CRASH_LOG_BYTES) {
                val rotated = File(context.filesDir, "$CRASH_LOG_FILE.1")
                runCatching { rotated.delete() }
                runCatching { file.renameTo(rotated) }
            }
            file.appendText(body)
        }
    }

    fun installUncaughtExceptionHandler(context: Context) {
        install(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recordCrash(throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun formatIsolatedError(tag: String, throwable: Throwable): String {
        val detail = throwable.message?.takeIf { it.isNotBlank() }
            ?: throwable::class.java.simpleName
        return "内部错误已隔离 [$tag]: $detail"
    }
}

/**
 * Launch with a handler + local try/catch so failures update UI via [onError]
 * instead of killing the process.
 */
fun CoroutineScope.safeLaunch(
    tag: String,
    onError: (Throwable) -> Unit = {},
    block: suspend CoroutineScope.() -> Unit
): Job {
    val handler = FaultIsolation.exceptionHandler(tag)
    return launch(handler) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            FaultIsolation.recordFault(tag, error)
            runCatching { onError(error) }
        }
    }
}

/** Launch on [ViewModel.viewModelScope] with fault isolation. */
fun ViewModel.safeLaunch(
    tag: String,
    onError: (Throwable) -> Unit = {},
    block: suspend CoroutineScope.() -> Unit
): Job = viewModelScope.safeLaunch(tag, onError, block)
