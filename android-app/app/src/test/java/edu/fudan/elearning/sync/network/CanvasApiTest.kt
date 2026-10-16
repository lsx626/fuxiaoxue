package edu.fudan.elearning.sync.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CanvasApi 的分页/限流/错误语义单测。
 *
 * 全部用假传输 + 假时钟，不访问网络也不真正等待；这是「Android 分页与限流」
 * 这条 P0 缺口的可回归证据。
 */
class CanvasApiTest {

    @Test fun invalidRecordCannotTurnListingIntoDeletionEvidence() = runBlocking {
        for (json in listOf("[{\"id\":1}, null]", "[{\"id\":1},{\"id\":{}}]", "[{\"id\":0}]")) {
            val transport = FakeTransport(listOf({ reply(200, json) }))
            try { api(transport, FakeTime()).getCourseFiles(1); throw AssertionError("expected parse error") }
            catch (_: ApiException.Parse) { }
        }
    }

    /** 一次性假传输：按调用顺序返回预设响应，用完后重复最后一个。 */
    private class FakeTransport(
        private val replies: List<() -> HttpResponse>
    ) : HttpTransport {
        val urls = mutableListOf<String>()
        var calls = 0
        override suspend fun get(url: String): HttpResponse {
            urls += url
            val reply = replies[minOf(calls, replies.size - 1)]
            calls += 1
            return reply()
        }
    }

    private class FakeTime {
        var now = 0L
        val sleeps = mutableListOf<Long>()
        val clock: () -> Long = { now }
        val sleeper: suspend (Long) -> Unit = { ms ->
            sleeps += ms
            now += ms
        }
    }

    private fun reply(code: Int, body: String, headers: Map<String, String> = emptyMap()) =
        HttpResponse(code, headers, body)

    private fun api(transport: HttpTransport, time: FakeTime, policy: RequestPolicy = RequestPolicy()): CanvasApi =
        CanvasApi(
            transport = transport,
            baseUrl = "https://host",
            policy = policy,
            sleeper = time.sleeper,
            clock = time.clock
        )

    @Test
    fun courses_followsNextLinkAndAccumulatesAllPages() = runBlocking {
        val nextUrl = "https://host/api/v1/courses?page=2&per_page=100&include[]=term"
        val transport = FakeTransport(
            listOf(
                { reply(200, """[{"id":1,"name":"甲"},{"id":2,"name":"乙"}]""",
                    mapOf("Link" to "<$nextUrl>; rel=\"next\", <https://host/x>; rel=\"last\"")) },
                { reply(200, """[{"id":3,"name":"丙"}]""") }
            )
        )
        val time = FakeTime()

        val courses = api(transport, time).getCourses()

        assertEquals(listOf(1L, 2L, 3L), courses.map { it.id })
        assertEquals(2, transport.calls)
        // 第二页必须用 Link 头里的原样 URL（不透明值）
        assertEquals(nextUrl, transport.urls[1])
    }

    @Test
    fun courses_doesNotFollowNonNextRels() = runBlocking {
        val transport = FakeTransport(
            listOf(
                { reply(200, """[{"id":1,"name":"甲"}]""",
                    mapOf("Link" to "<https://host/api/v1/courses?page=9>; rel=\"last\"")) }
            )
        )
        val courses = api(transport, FakeTime()).getCourses()
        assertEquals(1, courses.size)
        assertEquals(1, transport.calls)
    }

    @Test
    fun serverError_retriesWithExponentialBackoffThenSucceeds() = runBlocking {
        val transport = FakeTransport(
            listOf(
                { reply(500, "boom") },
                { reply(502, "boom") },
                { reply(200, """[{"id":1,"name":"甲"}]""") }
            )
        )
        val time = FakeTime()

        val courses = api(transport, time).getCourses()

        assertEquals(1, courses.size)
        assertEquals(3, transport.calls)
        // 第一次请求前的节流 150ms + 两次退避 500/1000
        assertEquals(listOf(150L, 500L, 1_000L), time.sleeps)
    }

    @Test
    fun rateLimit_honoursRetryAfterSeconds() = runBlocking {
        val transport = FakeTransport(
            listOf(
                { reply(429, "slow down", mapOf("Retry-After" to "2")) },
                { reply(200, """[{"id":1,"name":"甲"}]""") }
            )
        )
        val time = FakeTime()

        val courses = api(transport, time).getCourses()

        assertEquals(1, courses.size)
        assertTrue("限流后必须按 Retry-After 等待 2 秒", time.sleeps.contains(2_000L))
    }

    @Test
    fun rateLimitExhausted_reportsRateLimitedInsteadOfEmptyList() = runBlocking {
        val transport = FakeTransport(
            listOf({ reply(429, "slow down", mapOf("Retry-After" to "1")) })
        )
        val time = FakeTime()
        val policy = RequestPolicy(maxAttempts = 3)

        var thrown: ApiException? = null
        try {
            api(transport, time, policy).getCourses()
        } catch (error: ApiException) {
            thrown = error
        }

        assertTrue("应抛 RateLimited，实际 $thrown", thrown is ApiException.RateLimited)
        assertEquals(1_000L, (thrown as ApiException.RateLimited).retryAfterMs)
        assertEquals(3, transport.calls)
    }

    @Test
    fun authFailure_throwsAuthWithoutRetrying() = runBlocking {
        val transport = FakeTransport(listOf({ reply(401, "unauthorized") }))
        val time = FakeTime()

        var thrown: ApiException? = null
        try {
            api(transport, time).getCourses()
        } catch (error: ApiException) {
            thrown = error
        }

        assertTrue("401 必须抛 Auth，实际 $thrown", thrown is ApiException.Auth)
        assertEquals("鉴权失败只允许一次会话验证，不重试原请求", 2, transport.calls)
    }

    @Test
    fun forbiddenWithoutRateLimit_isAuthNotData() = runBlocking {
        val transport = FakeTransport(listOf({ reply(403, "forbidden") }))
        var thrown: ApiException? = null
        try {
            api(transport, FakeTime()).getCourseFiles(42L)
        } catch (error: ApiException) {
            thrown = error
        }
        assertTrue(thrown is ApiException.Auth)
    }

    @Test
    fun courseAccessDenied_withValidSession_isNotGlobalAuthFailure() = runBlocking {
        val transport = FakeTransport(listOf(
            { reply(401, "course files disabled") },
            { reply(200, "{\"id\":1}") }
        ))
        var thrown: ApiException? = null
        try {
            api(transport, FakeTime()).getCourseFiles(42L)
        } catch (error: ApiException) {
            thrown = error
        }
        assertTrue(thrown is ApiException.Server)
        assertEquals(401, (thrown as ApiException.Server).code)
        assertEquals("https://host/api/v1/users/self", transport.urls.last())
    }

    @Test
    fun tooManyPages_stopsWithParseError() = runBlocking {
        val transport = FakeTransport(
            listOf({
                reply(200, "[]", mapOf("Link" to "<https://host/api/v1/courses?page=99>; rel=\"next\""))
            })
        )
        val policy = RequestPolicy(maxPages = 3)
        var thrown: ApiException? = null
        try {
            api(transport, FakeTime(), policy).getCourses()
        } catch (error: ApiException) {
            thrown = error
        }
        assertTrue(thrown is ApiException.Parse)
        assertEquals(3, transport.calls)
    }

    @Test
    fun lowRemainingHeader_slowsDownFollowingRequest() = runBlocking {
        val transport = FakeTransport(
            listOf(
                { reply(200, """[{"id":1,"name":"甲"}]""",
                    mapOf("X-Rate-Limit-Remaining" to "3")) },
                { reply(200, """[{"id":2,"name":"乙"}]""") }
            )
        )
        val time = FakeTime()
        val api = api(transport, time)

        api.getCourses()
        api.getCourseFiles(1L)

        // 第一次请求前 150ms 节流；额度偏低后再追加 500ms 减速
        assertEquals(listOf(150L, 500L), time.sleeps)
    }

    @Test
    fun nonJsonArray_reportsParseError() = runBlocking {
        val transport = FakeTransport(listOf({ reply(200, "{\"error\":\"nope\"}") }))
        var thrown: ApiException? = null
        try {
            api(transport, FakeTime()).getCourses()
        } catch (error: ApiException) {
            thrown = error
        }
        assertTrue(thrown is ApiException.Parse)
    }

    @Test
    fun currentUser_returnsNameAndToleratesWeirdBody() = runBlocking {
        val transport = FakeTransport(
            listOf(
                { reply(200, """{"id":7,"name":"张三"}""") },
                { reply(200, "not json") }
            )
        )
        val api = api(transport, FakeTime())
        assertEquals("张三", api.getCurrentUser())
        assertEquals(null, api.getCurrentUser())
    }

    @Test
    fun networkFailure_reportsNetworkErrorNotEmptyList() = runBlocking {
        val transport = object : HttpTransport {
            override suspend fun get(url: String): HttpResponse = throw java.io.IOException("timeout")
        }
        val time = FakeTime()
        var thrown: ApiException? = null
        try {
            api(transport, time, RequestPolicy(maxAttempts = 2)).getCourses()
        } catch (error: ApiException) {
            thrown = error
        }
        assertTrue("应抛 Network，实际 $thrown", thrown is ApiException.Network)
        // 一次失败后退避 500ms 再试一次
        assertTrue(time.sleeps.contains(500L))
    }
}
