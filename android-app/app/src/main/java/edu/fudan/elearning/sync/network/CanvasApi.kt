package edu.fudan.elearning.sync.network

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody

/** Canvas 课程（含学期）。 */
data class CanvasCourse(
    val id: Long,
    val name: String,
    @SerializedName("course_code") val courseCode: String = "",
    @SerializedName("enrollment_term_id") val enrollmentTermId: Long = 0,
    val term: CanvasTerm? = null,
)

/** Canvas 学期。 */
data class CanvasTerm(val id: Long, val name: String = "")

data class CanvasUser(
    val name: String = "",
    @SerializedName("avatar_url") val avatarUrl: String = "",
    @SerializedName("sis_user_id") val sisUserId: String = "",
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
    val locked: Boolean = false,
) {
    /** 两个键任一为真即视为锁定。 */
    val isLocked: Boolean
        get() = lockedForUser || locked
}

/**
 * Canvas REST API 客户端。
 *
 * 硬性行为（与桌面端对齐）：
 * - **分页**：原样跟随 `Link: rel="next"`，不自行重建页码/参数；页数超过 [RequestPolicy.maxPages] 时明确报错，而不是无限请求；
 * - **限流**：元数据请求串行并保持最小间隔；429 / 含 `Rate Limit Exceeded` 的 403 尊重
 *   `Retry-After`；`X-Rate-Limit-Remaining` 偏低时主动减速；
 * - **错误语义**：失败抛 [ApiException] 的对应子类，绝不返回空列表冒充成功 （空列表会让界面显示「同步完成，0 个文件」，并可能被误判为远端删除）。
 *
 * 构造参数可注入传输/时钟/休眠，便于在 JVM 单测里完全离线验证上述行为。
 */
class CanvasApi(
    private val transport: HttpTransport = OkHttpTransport(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val policy: RequestPolicy = RequestPolicy(),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : CanvasDataSource {
    private val gson = Gson()

    /** 串行化所有元数据请求，避免并发打满接口配额。 */
    private val gate = Mutex()
    private var lastRequestAt = 0L
    private var slowDownUntil = 0L

    /** 当前用户可见课程（含学期）。 */
    suspend fun getCourses(): List<CanvasCourse> =
        getPaged(COURSES_PATH).map { element ->
            val course =
                try {
                    gson.fromJson(element, CanvasCourse::class.java)
                } catch (error: Exception) {
                    throw ApiException.Parse("课程数据格式异常", error)
                }
            if (course == null || course.id <= 0 || course.name.isNullOrBlank())
                throw ApiException.Parse("课程数据不完整，已保留本地资料")
            course.copy(courseCode = course.courseCode.orEmpty())
        }

    /** 指定课程的全部文件（分页拉全）。 */
    override suspend fun getCourseFiles(courseId: Long): List<CanvasFile> =
        getPaged(filesPath(courseId)).map { element ->
            // A silently dropped record would make the deletion gate trust an
            // incomplete list. Reject the whole listing instead.
            val file =
                try {
                    gson.fromJson(element, CanvasFile::class.java)
                } catch (error: Exception) {
                    throw ApiException.Parse("文件数据格式异常", error)
                }
            if (file == null || file.id <= 0 || file.size < 0)
                throw ApiException.Parse("文件数据不完整，本轮不判定远端删除")
            file.copy(
                displayName = file.displayName.orEmpty(),
                filename = file.filename.orEmpty(),
                url = file.url.orEmpty(),
                updatedAt = file.updatedAt.orEmpty(),
                mimeClass = file.mimeClass.orEmpty(),
            )
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
        return runCatching { gson.fromJson(body, CanvasPage::class.java).body.orEmpty() }
            .getOrDefault("")
    }

    /** 作业列表（含附件）。 */
    override suspend fun getAssignments(courseId: Long): List<CanvasAssignment> =
        getPaged("/api/v1/courses/$courseId/assignments?per_page=100").map {
            val assignment =
                try {
                    gson.fromJson(it, CanvasAssignment::class.java)
                } catch (error: Exception) {
                    throw ApiException.Parse("作业数据格式异常", error)
                }
            if (assignment == null || assignment.id <= 0 || assignment.name.isNullOrBlank())
                throw ApiException.Parse("作业数据不完整，已保留之前的截止日期")
            assignment.copy(
                attachments = assignment.attachments.orEmpty(),
                submissionTypes = assignment.submissionTypes.orEmpty(),
            )
        }

    /** 单个作业的完整正文、分值、提交方式与附件（应用内任务详情）。 */
    suspend fun getAssignment(courseId: Long, assignmentId: Long): CanvasAssignment {
        val body =
            request(
                    url(
                        "/api/v1/courses/$courseId/assignments/$assignmentId" +
                            "?include[]=submission&include[]=submission_comments" +
                            "&include[]=submission_history&include[]=rubric"
                    )
                )
                .body
        val assignment =
            try {
                gson.fromJson(body, CanvasAssignment::class.java)
            } catch (error: Exception) {
                throw ApiException.Parse("任务数据格式异常", error)
            }
        if (assignment == null || assignment.id != assignmentId || assignment.name.isNullOrBlank())
            throw ApiException.Parse("任务数据不完整，请刷新文件列表后重试")
        return assignment.copy(
            attachments = assignment.attachments.orEmpty(),
            submissionTypes = assignment.submissionTypes.orEmpty(),
            rubric =
                assignment.rubric.orEmpty().map { criterion ->
                    criterion.copy(ratings = criterion.ratings.orEmpty())
                },
            submission =
                assignment.submission?.copy(
                    attachments = assignment.submission.attachments.orEmpty(),
                    comments = assignment.submission.comments.orEmpty(),
                ),
            submissionHistory =
                assignment.submissionHistory.orEmpty().map { history ->
                    history.copy(
                        attachments = history.attachments.orEmpty(),
                        comments = history.comments.orEmpty(),
                    )
                },
        )
    }

    /** 提交文字作业。 */
    suspend fun submitText(courseId: Long, assignmentId: Long, text: String): CanvasSubmission {
        val body =
            request(
                    url(submissionsPath(courseId, assignmentId)),
                    method = "POST",
                    body =
                        FormBody.Builder()
                            .add("submission[submission_type]", "online_text_entry")
                            .add("submission[body]", text)
                            .build(),
                )
                .body
        return parseSubmission(body)
    }

    /** 提交网址型作业。 */
    suspend fun submitUrl(courseId: Long, assignmentId: Long, url: String): CanvasSubmission {
        val body =
            request(
                    url(submissionsPath(courseId, assignmentId)),
                    method = "POST",
                    body =
                        FormBody.Builder()
                            .add("submission[submission_type]", "online_url")
                            .add("submission[url]", url)
                            .build(),
                )
                .body
        return parseSubmission(body)
    }

    /**
     * 上传文件并作为作业提交。
     *
     * 使用 Canvas 官方的三步上传：申请上传参数 -> 向 `upload_url` 发送 multipart -> 用返回的 file_id 提交作业。
     */
    suspend fun submitFiles(
        courseId: Long,
        assignmentId: Long,
        files: List<File>,
    ): CanvasSubmission {
        require(files.isNotEmpty()) { "请先选择要提交的文件" }
        val uploadedIds = files.map { file -> uploadSubmissionFile(courseId, assignmentId, file) }
        val form =
            FormBody.Builder()
                .add("submission[submission_type]", "online_upload")
                .apply { uploadedIds.forEach { add("submission[file_ids][]", it.toString()) } }
                .build()
        val body =
            request(url(submissionsPath(courseId, assignmentId)), method = "POST", body = form).body
        return parseSubmission(body)
    }

    private suspend fun uploadSubmissionFile(courseId: Long, assignmentId: Long, file: File): Long {
        val mime = guessMime(file.name)
        val requestBody =
            request(
                    url(
                        "/api/v1/courses/$courseId/assignments/$assignmentId/submissions/self/files"
                    ),
                    method = "POST",
                    body =
                        FormBody.Builder()
                            .add("name", file.name)
                            .add("size", file.length().toString())
                            .add("content_type", mime)
                            .add("on_duplicate", "overwrite")
                            .build(),
                )
                .body
        val slot =
            runCatching { JsonParser.parseString(requestBody).asJsonObject }.getOrNull()
                ?: throw ApiException.Parse("文件上传参数格式异常")
        slot
            .get("id")
            ?.takeIf { !it.isJsonNull }
            ?.asLong
            ?.let {
                return it
            }
        val uploadUrl =
            slot.get("upload_url")?.takeIf { !it.isJsonNull }?.asString
                ?: throw ApiException.Parse("文件上传地址缺失")
        val uploadParams = slot.getAsJsonObject("upload_params")
        val upload =
            runCatching {
                    MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .apply {
                            uploadParams?.entrySet()?.forEach { (key, value) ->
                                if (!value.isJsonNull) addFormDataPart(key, value.asString)
                            }
                            addFormDataPart(
                                "file",
                                file.name,
                                file.asRequestBody(mime.toMediaTypeOrNull()),
                            )
                        }
                        .build()
                }
                .getOrElse { throw ApiException.Parse("无法构造文件上传", it) }
        val uploadedBody = request(uploadUrl, method = "POST", body = upload, throttle = false).body
        val uploaded =
            runCatching { JsonParser.parseString(uploadedBody).asJsonObject.get("id")?.asLong }
                .getOrNull()
        return uploaded ?: throw ApiException.Parse("文件已上传，但服务端未返回文件编号")
    }

    private fun parseSubmission(body: String): CanvasSubmission {
        val submission =
            runCatching {
                    gson.fromJson(JsonParser.parseString(body), CanvasSubmission::class.java)
                }
                .getOrNull() ?: throw ApiException.Parse("提交结果格式异常")
        return submission.copy(
            attachments = submission.attachments.orEmpty(),
            comments = submission.comments.orEmpty(),
        )
    }

    private fun submissionsPath(courseId: Long, assignmentId: Long) =
        "/api/v1/courses/$courseId/assignments/$assignmentId/submissions"

    private fun guessMime(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            "jpg",
            "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }

    /**
     * 公告列表（含附件）。
     *
     * 部署差异：部分 Canvas 只提供 `discussion_topics?only_announcements=true`， 因此 `/announcements` 404
     * 时自动回退到讨论主题接口，而不是把公告当空。
     */
    override suspend fun getAnnouncements(courseId: Long): List<CanvasAnnouncement> {
        val path = "/api/v1/courses/$courseId/announcements?per_page=100&include[]=attachments"
        val elements =
            try {
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
            }
            .getOrDefault("")
    }

    /** 单个文件的完整元数据（引用型文件需要补签名下载地址）。 */
    override suspend fun getFile(courseId: Long, fileId: Long): CanvasFile? {
        val body = request(url("/api/v1/courses/$courseId/files/$fileId")).body
        val file =
            try {
                gson.fromJson(body, CanvasFile::class.java)
            } catch (error: Exception) {
                throw ApiException.Parse("文件数据格式异常", error)
            }
        if (file == null || file.id != fileId || file.size < 0)
            throw ApiException.Parse("文件数据不完整，请重新同步")
        return file.copy(
            displayName = file.displayName.orEmpty(),
            filename = file.filename.orEmpty(),
            url = file.url.orEmpty(),
            updatedAt = file.updatedAt.orEmpty(),
            mimeClass = file.mimeClass.orEmpty(),
        )
    }

    /** 当前用户资料；解析不出来返回 null（不算失败）。 */
    suspend fun getCurrentUser(): CanvasUser? {
        val body = request(url(SELF_PATH)).body
        return runCatching {
                gson.fromJson(JsonParser.parseString(body), CanvasUser::class.java)?.let { user ->
                    user.copy(
                        name = user.name.orEmpty(),
                        avatarUrl = user.avatarUrl.orEmpty(),
                        sisUserId = user.sisUserId.orEmpty(),
                    )
                }
            }
            .getOrNull()
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
                throw ApiException.Parse("分页数量超过上限（${policy.maxPages}），已停止请求以免无休止拉取")
            }
            val reply = request(next)
            val array =
                runCatching { JsonParser.parseString(reply.body).asJsonArray }
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
     * 重试期间一直持有 [gate]，即元数据请求严格串行；[sleeper] 可注入， 使单测能在毫秒内验证退避数值。
     */
    private suspend fun request(
        urlString: String,
        method: String = "GET",
        body: RequestBody? = null,
        throttle: Boolean = true,
    ): HttpResponse =
        gate.withLock {
            var attempt = 0
            while (true) {
                attempt += 1
                if (throttle) throttle()
                val reply =
                    try {
                        if (method == "GET") transport.get(urlString)
                        else transport.execute(method, urlString, body)
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
                    // Canvas also uses 401/403 for a course whose Files/Pages/etc.
                    // navigation is disabled.  Treating every such response as a
                    // dead session aborts the entire sync at the first restricted
                    // course.  Verify the session against the account endpoint:
                    // only a failed probe means the user must sign in again.
                    val selfUrl = url(SELF_PATH)
                    if (urlString != selfUrl) {
                        val sessionProbe =
                            try {
                                transport.get(selfUrl)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        if (sessionProbe?.isSuccessful == true) {
                            throw ApiException.Server(
                                reply.code,
                                urlString,
                                "当前课程未开放此内容或没有访问权限（HTTP ${reply.code}）",
                            )
                        }
                    }
                    throw ApiException.Auth()
                }
                if (!policy.isRetryable(reply.code, rateLimited)) {
                    throw ApiException.Server(reply.code, urlString, "请求失败（HTTP ${reply.code}）")
                }
                if (attempt >= policy.maxAttempts) {
                    throw if (rateLimited) {
                        ApiException.RateLimited(
                            policy.retryAfterMs(reply.header("Retry-After")),
                            "请求过于频繁，请稍后再试",
                        )
                    } else {
                        ApiException.Server(
                            reply.code,
                            urlString,
                            "服务端错误（HTTP ${reply.code}），已重试 $attempt 次",
                        )
                    }
                }
                val waitMs =
                    if (rateLimited) {
                        policy.retryAfterMs(reply.header("Retry-After"))
                    } else {
                        policy.backoffMs(attempt)
                    }
                sleeper(waitMs)
            }
            @Suppress("UNREACHABLE_CODE") throw ApiException.Server(0, urlString, "请求未完成")
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
