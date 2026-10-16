package edu.fudan.elearning.sync.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancelling a sync closes its socket, including a blocked response-body read. */
internal suspend fun <T> Call.readCancellable(read: (Response, () -> Unit) -> T): T {
    val released = CompletableDeferred<Unit>()
    try {
        return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        try { enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                try { if (continuation.isActive) continuation.resumeWithException(e) }
                finally { released.complete(Unit) }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        read(it) {
                            if (!continuation.isActive) throw CancellationException("请求已取消")
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally { released.complete(Unit) }
            }
        }) } catch (error: Throwable) {
            released.complete(Unit)
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        }
    } finally {
        // Do not release SyncGate while a cancelled callback can still write a
        // last chunk to the same .part file that the next sync would resume.
        withContext(NonCancellable) { released.await() }
    }
}
