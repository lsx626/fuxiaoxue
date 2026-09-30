package edu.fudan.elearning.sync.network

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Canvas 课程（含学期）。 */
data class CanvasCourse(
    val id: Long,
    val name: String,
    @SerializedName("course_code") val courseCode: String = "",
    @SerializedName("enrollment_term_id") val enrollmentTermId: Long = 0,
    val term: CanvasTerm? = null
)

/** Canvas 学期。 */
data class CanvasTerm(
    val id: Long,
    val name: String = ""
)

/** Canvas 文件。 */
data class CanvasFile(
    val id: Long,
    @SerializedName("display_name") val displayName: String = "",
    val filename: String = "",
    val size: Long = 0,
    val url: String = "",
    @SerializedName("folder_id") val folderId: Long = 0,
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("mime_class") val mimeClass: String = "",
    @SerializedName("locked_for_user") val lockedForUser: Boolean = false,
    /** 旧字段：部分 Canvas 版本/来源（模块附件）只给 `locked`。 */
    val locked: Boolean = false
) {
    /** 两个键任一为真即视为锁定。 */
    val isLocked: Boolean get() = lockedForUser || locked
}

/**
 * Canvas REST API 客户端。
 *
 * 硬性行为（与桌面端对齐）：
 * - **分页**：原样跟随 `Link: rel="next"`，不自行重建页码/参数；页数超过
 *   [RequestPolicy.maxPages] 时明确报错，而不是无限请求；
 * - **限流**：元数据请求串行并保持最小间隔；429 / 含 `Rate Limit Exceeded` 的
 *   403 尊重 `Retry-After`；`X-Rate-Limit-Remaining` 偏低时主动减速；
 * - **错误语义**：失败抛 [ApiException] 的对应子类，绝不返回空列表冒充成功
 *   （空列表会让界面显示「同步完成，0 个文件」，并可能被误判为远端删除）。
 *
 * 构造参数可注入传输/时钟/休眠，便于在 JVM 单测里完全离线验证上述行为。
 */
class CanvasApi(
    private val transport: HttpTransport = OkHttpTransport(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val policy: RequestPolicy = RequestPolicy(),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = { System.currentTimeMillis() }
) : CanvasDataSource {
    private val gson = Gson()

    /** 串行化所有元数据请求，避免并发打满接口配额。 */
    private val gate = Mutex()
    private var lastRequestAt = 0L
    private var slowDownUntil = 0L

    /** 当前用户可见课程（含学期）。 */
    suspend fun getCourses(): List<CanvasCourse> =
        getPaged(COURSES_PATH).mapNotNull { element ->
            runCatching { gson.fromJson(element, CanvasCourse::class.java) }.getOrNull()
        }

    /** 指定课程的全部文件（分页拉全）。 */
    override suspend fun getCourseFiles(courseId: Long): List<CanvasFile> =
        getPaged(filesPath(courseId)).mapNotNull { element ->
            runCatching { gson.fromJson(element, CanvasFile::class.java) }.getOrNull()
        }

    // ------------------------------------------------------------------
    // 课程内容抓取（目录 / 模块 / 页面 / 作业 / 公告 / 大纲）
    // ------------------------------------------------------------------

    /** 课程目录树（用于重建本地子目录）。 */
    override suspend fun getFolders(courseId: Long): List<CanvasFolder> =
        getPaged("/api/v1/courses/$courseId/folders?per_page=100").mapNotNull {
            runCatching { gson.fromJson(it, CanvasFolder::class.java) }.getOrNull()
        }

    /** 模块列表（带 items）。 */
    override suspend fun getModules(courseId: Long): List<CanvasModule> =
        getPaged("/api/v1/courses/$courseId/modules?per_page=100&include[]=items").mapNotNull {
            runCatching { gson.fromJson(it, CanvasModule::class.java) }.getOrNull()
        }

    /** 模块条目（`items_count>0` 但未内联时按需拉取）。 */
    override suspend fun getModuleItems(courseId: Long, moduleId: Long): List<CanvasModuleItem> =
        getPaged("/api/v1/courses/$courseId/modules/$moduleId/items?per_page=100").mapNotNull {
            runCatching { gson.fromJson(it, CanvasModuleItem::class.java) }.getOrNull()
        }

    /** 课程页面列表（不含正文）。 */
    override suspend fun getPages(courseId: Long): List<CanvasPage> =
        getPaged("/api/v1/courses/$courseId/pages?per_page=100").mapNotNull {
            runCatching { gson.fromJson(it, CanvasPage::class.java) }.getOrNull()
        }

    /** 单个页面正文。 */
    override suspend fun getPageBody(courseId: Long, pageUrl: String): String {
        val body = request(url("/api/v1/courses/$courseId/pages/$pageUrl")).body
        return runCatching {
            gson.fromJson(body, CanvasPage::class.java).body.orEmpty()
        }.getOrDefault("")
    }

    /** 作业列表（含附件）。 */
    override suspend fun getAssignments(courseId: Long): List<CanvasAssignment> =
        getPaged("/api/v1/courses/$courseId/assignments?per_page=100").mapNotNull {
            runCatching { gson.fromJson(it, CanvasAssignment::class.java) }.getOrNull()
        }

    /**
     * 公告列表（含附件）。
     *
     * 部署差异：部分 Canvas 只提供 `discussion_topics?only_announcements=true`，
     * 因此 `/announcements` 404 时自动回退到讨论主题接口，而不是把公告当空。
     */
    override suspend fun getAnnouncements(courseId: Long): List<CanvasAnnouncement> {
        val path = "/api/v1/courses/$courseId/announcements?per_page=100&include[]=attachments"
        val elements = try {
            getPaged(path)
        } catch (notFound: ApiException.Server) {
            if (notFound.code != 404) throw notFound
            getPaged(
                "/api/v1/courses/$courseId/discussion_topics?only_announcements=true" +
                    "&per_page=100&include[]=attachments"
            )
        }
        return elements.mapNotNull {
            runCatching { gson.fromJson(it, CanvasAnnouncement::class.java) }.getOrNull()
        }
    }

    /** 课程大纲正文（`include[]=syllabus_body`）。 */
    override suspend fun getSyllabus(courseId: Long): String {
        val body = request(url("/api/v1/courses/$courseId?include[]=syllabus_body")).body
        return runCatching {
            gson.fromJson(body, CanvasCourseDetail::class.java).syllabusBody.orEmpty()
        }.getOrDefault("")
    }

    /** 单个文件的完整元数据（引用型文件需要补签名下载地址）。 */
    override suspend fun getFile(courseId: Long, fileId: Long): CanvasFile? {
        val body = request(url("/api/v1/courses/$courseId/files/$fileId")).body
        return runCatching { gson.fromJson(body, CanvasFile::class.java) }.getOrNull()
    }

    /** 当前用户显示名；解析不出来返回 null（不算失败）。 */
    suspend fun getCurrentUser(): String? {
        val body = request(url(SELF_PATH)).body
        return runCatching {
            JsonParser.parseString(body).asJsonObject.get("name")
                ?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // 分页与请求执行
    // ------------------------------------------------------------------

    private suspend fun getPaged(path: String): List<JsonElement> {
        val items = ArrayList<JsonElement>()
        var next: String? = url(path)
        var pages = 0
        while (next != null) {
            pages += 1
            if (pages > policy.maxPages) {
                throw ApiException.Parse(
                    "分页数量超过上限（${policy.maxPages}），已停止请求以免无休止拉取"
                )
            }
            val reply = request(next)
            val array = runCatching { JsonParser.parseString(reply.body).asJsonArray }
                .getOrElse { throw ApiException.Parse("响应不是合法的 JSON 数组", it) }
            items.addAll(array)
            // next 是不透明 URL，必须原样使用
            next = LinkHeader.next(reply.header("Link"))
        }
        return items
    }

    /**
     * 执行一次带节流与重试的请求。
     *
     * 重试期间一直持有 [gate]，即元数据请求严格串行；[sleeper] 可注入，
     * 使单测能在毫秒内验证退避数值。
     */
    private suspend fun request(urlString: String): HttpResponse = gate.withLock {
        var attempt = 0
        while (true) {
            attempt += 1
            throttle()
            val reply = try {
                transport.get(urlString)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (attempt >= policy.maxAttempts) throw ApiException.Network(error)
                sleeper(policy.backoffMs(attempt))
                continue
            }

            applyRateLimitHints(reply)
            val rateLimited = policy.isRateLimited(reply.code, reply.body)
            if (reply.isSuccessful) return@withLock reply

            // 鉴权失败必须显式报错，不能降级成「空数据」
            if (reply.code == 401 || (reply.code == 403 && !rateLimited)) {
                throw ApiException.Auth()
            }
            if (!policy.isRetryable(reply.code, rateLimited)) {
                throw ApiException.Server(reply.code, urlString, "请求失败（HTTP ${reply.code}）")
            }
            if (attempt >= policy.maxAttempts) {
                throw if (rateLimited) {
                    ApiException.RateLimited(
                        policy.retryAfterMs(reply.header("Retry-After")),
                        "请求过于频繁，请稍后再试"
                    )
                } else {
                    ApiException.Server(
                        reply.code, urlString,
                        "服务端错误（HTTP ${reply.code}），已重试 $attempt 次"
                    )
                }
            }
            val waitMs = if (rateLimited) {
                policy.retryAfterMs(reply.header("Retry-After"))
            } else {
                policy.backoffMs(attempt)
            }
            sleeper(waitMs)
        }
        @Suppress("UNREACHABLE_CODE")
        throw ApiException.Server(0, urlString, "请求未完成")
    }

    /** 最小间隔 + 额度偏低时的主动减速，保证元数据请求严格低频。 */
    private suspend fun throttle() {
        val target = maxOf(lastRequestAt + policy.minIntervalMs, slowDownUntil)
        val waitMs = target - clock()
        if (waitMs > 0) sleeper(waitMs)
        lastRequestAt = clock()
    }

    /** 记录服务端返回的剩余额度，低于阈值时让后续请求减速。 */
    private fun applyRateLimitHints(reply: HttpResponse) {
        val remaining = policy.remaining(reply.header("X-Rate-Limit-Remaining"))
        val extra = policy.slowDownMs(remaining)
        if (extra > 0) slowDownUntil = clock() + extra
    }

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    companion object {
        const val DEFAULT_BASE_URL = "https://elearning.fudan.edu.cn"

        const val COURSES_PATH =
            "/api/v1/courses?per_page=100&include[]=term&state[]=available&enrollment_type=student"
        const val SELF_PATH = "/api/v1/users/self"

        /** 每页 100 条：比旧实现的 200 更保守，减少单次响应体与限流风险。 */
        fun filesPath(courseId: Long): String =
            "/api/v1/courses/$courseId/files?per_page=100&sort=position"
    }
}
