package edu.fudan.elearning.sync.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import okhttp3.*
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test

class CancellableHttpTest {
    private fun client(body: ResponseBody, before: () -> Unit = {}) =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                before()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("synthetic")
                    .body(body)
                    .build()
            }
            .build()

    private class Body : ResponseBody() {
        val closed = AtomicBoolean(false)
        private val buffer = Buffer().writeUtf8("synthetic body")

        override fun contentType(): MediaType? = null

        override fun contentLength() = buffer.size

        override fun source(): BufferedSource = buffer

        override fun close() {
            closed.set(true)
            super.close()
        }
    }

    @Test
    fun responseClosesAfterSuccessfulRead() = runBlocking {
        val body = Body()
        val call = client(body).newCall(Request.Builder().url("https://synthetic.invalid").build())
        assertEquals(
            "synthetic body",
            call.readCancellable { response, check ->
                check()
                response.body!!.string()
            },
        )
        assertTrue(body.closed.get())
    }

    @Test
    fun responseClosesAfterReaderThrows() = runBlocking {
        val body = Body()
        val call = client(body).newCall(Request.Builder().url("https://synthetic.invalid").build())
        try {
            call.readCancellable<Unit> { _, _ -> throw IllegalStateException("synthetic") }
            fail()
        } catch (_: IllegalStateException) {}
        assertTrue(body.closed.get())
    }

    @Test
    fun cancellationCancelsUnderlyingCallAndClosesLateResponse() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val body =
            object : ResponseBody() {
                private val buffer = Buffer()

                override fun contentType(): MediaType? = null

                override fun contentLength() = 0L

                override fun source(): BufferedSource = buffer

                override fun close() {
                    closed.countDown()
                    super.close()
                }
            }
        val call =
            client(body) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                .newCall(Request.Builder().url("https://synthetic.invalid").build())
        val job = launch { call.readCancellable { _, check -> check() } }
        yield()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancel()
        yield()
        assertTrue(call.isCanceled())
        assertFalse(
            "Cancellation must retain file ownership until the callback releases it",
            job.isCompleted,
        )
        release.countDown()
        job.join()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
    }
}
