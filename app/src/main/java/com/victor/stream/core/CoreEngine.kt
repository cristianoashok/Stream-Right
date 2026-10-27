package com.victor.stream.core

import android.util.Log
import com.victor.stream.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

enum class FailReason {
    TIMEOUT,
    HTTP,
    NO_MANIFEST,
    PLAYER,
    BLOCKED,
    NETWORK,
    UNKNOWN
}

sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Failure(val reason: FailReason, val message: String = "", val cause: Throwable? = null) : AppResult<Nothing>()

    inline fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }
}

interface AppDispatchers {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}

object DefaultAppDispatchers : AppDispatchers {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
}

suspend fun <T> withBudget(
    timeoutMs: Long,
    onTimeoutReason: FailReason = FailReason.TIMEOUT,
    block: suspend CoroutineScope.() -> T
): AppResult<T> {
    return try {
        val result = withTimeout(timeoutMs) {
            block()
        }
        AppResult.Success(result)
    } catch (e: TimeoutCancellationException) {
        AppResult.Failure(onTimeoutReason, "Operation timed out after ${timeoutMs}ms", e)
    } catch (e: Throwable) {
        AppResult.Failure(FailReason.UNKNOWN, e.message ?: "Unexpected error", e)
    }
}

object Logger {
    private const val GLOBAL_TAG = "VictorStream"
    private val lastLogTimeByKey = ConcurrentHashMap<String, Long>()

    var verboseReqLoggingEnabled: Boolean = false

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d("$GLOBAL_TAG:$tag", message)
        }
    }

    fun i(tag: String, message: String) {
        Log.i("$GLOBAL_TAG:$tag", message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w("$GLOBAL_TAG:$tag", message, throwable)
        } else {
            Log.w("$GLOBAL_TAG:$tag", message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e("$GLOBAL_TAG:$tag", message, throwable)
        } else {
            Log.e("$GLOBAL_TAG:$tag", message)
        }
    }

    fun throttledD(tag: String, throttleKey: String, minIntervalMs: Long = 1500L, message: () -> String) {
        if (!BuildConfig.DEBUG) return
        val now = System.currentTimeMillis()
        val prev = lastLogTimeByKey[throttleKey] ?: 0L
        if (now - prev >= minIntervalMs) {
            lastLogTimeByKey[throttleKey] = now
            Log.d("$GLOBAL_TAG:$tag", message())
        }
    }

    fun logSubresourceReq(url: String) {
        if (BuildConfig.DEBUG && verboseReqLoggingEnabled) {
            throttledD("NetReq", url.take(60), 500L) { "REQ $url" }
        }
    }
}