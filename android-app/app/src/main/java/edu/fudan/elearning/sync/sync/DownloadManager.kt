package edu.fudan.elearning.sync.sync

import android.content.Context
import edu.fudan.elearning.sync.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** 单个文件的下载结果。 */
sealed class DownloadOutcome {
    data class Success(
        val path: File,
        val bytes: Long,
        /** 是否是断点续传完成的（用于界面/日志区分）。 */
        val resumed: Boolean = false
    ) : DownloadOutcome()

    /**
     * 失败。[retryable] 为 false 表示重试也不会成功（会话失效、权限不足、
     * 服务端明确拒绝），调用方应立即停止重试并给出可操作提示。
     */
    data class Failed(val reason: String, val retryable: Boolean = true) : DownloadOutcome()
}

/**
 * 文件下载管理器：把 Canvas 文件可靠地落到应用专属外部目录。
 *
 * 可靠性流程（与桌面端同构）：
 * 1. 写入 `目标.part`；
 * 2. 已有 `.part` 用 `Range` 续传；服务端忽略 Range（返回 200）时从头重写；
 * 3. 已知远端大小时校验完整长度，不完整保留断点；
 * 4. 成功后原子改名到最终路径（先删旧文件，再 rename，同目录内 rename 是原子的）；
 * 5. 失败按指数退避重试；会话失效/登录页 HTML 立刻停止重试。
 */
class DownloadManager(
    private val context: Context,
    private val maxAttempts: Int = 3
) {

    private val rootDir: File
        get() = File(context.getExternalFilesDir(null), "elearning").apply { mkdirs() }

    fun rootPath(): String = rootDir.absolutePath

    /** 计算某个课程目录下的文件绝对路径。 */
    fun courseDir(courseName: String): File =
        File(rootDir, sanitize(courseName)).apply { mkdirs() }

    /** 课程目录里所有已落盘文件（含 `.part`），用于同名避让与清理。 */
    fun existingFileNames(courseDir: File): List<String> =
        courseDir.listFiles()?.map { it.name } ?: emptyList()

    /**
     * 选一个不会覆盖其它文件的目标路径。
     *
     * [taken] 传入同一目录下其它文件（不同 `file_id`）的文件名。
     */
    fun destinationFor(courseDir: File, fileName: String, taken: Set<String>): File =
        DownloadPlan.uniqueDestination(courseDir, fileName, taken)

    /**
     * 下载 [url] 到 [dest]。
     *
     * [expectedSize] 为 Canvas 声明的字节数（0 表示未知，跳过长度校验）。
     */
    suspend fun download(url: String, dest: File, expectedSize: Long = 0): DownloadOutcome =
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            val part = DownloadPlan.partFile(dest)

            // 断点已等于远端大小时直接收尾，避免多打一次请求
            if (expectedSize > 0 && part.exists() && part.length() == expectedSize) {
                return@withContext finalize(part, dest, expectedSize, resumed = true)
            }
            if (expectedSize > 0 && part.exists() && part.length() > expectedSize) {
                // 断点比远端还大：上一次的 .part 不可信，重下
                part.delete()
            }

            var lastReason = "下载失败"
            for (attempt in 1..maxAttempts.coerceAtLeast(1)) {
                when (val outcome = attemptOnce(url, dest, part, expectedSize)) {
                    is DownloadOutcome.Success -> return@withContext outcome
                    is DownloadOutcome.Failed -> {
                        lastReason = outcome.reason
                        if (!outcome.retryable) return@withContext outcome
                        if (attempt < maxAttempts) delay(DownloadPlan.backoffMs(attempt))
                    }
                }
            }
            DownloadOutcome.Failed(lastReason)
        }

    private fun attemptOnce(
        url: String,
        dest: File,
        part: File,
        expectedSize: Long
    ): DownloadOutcome {
        val partLength = if (part.exists()) part.length() else 0L
        val rangeHeader = DownloadPlan.rangeHeader(partLength)
        val response = try {
            ApiClient.download(url, rangeHeader)
        } catch (error: Exception) {
            return DownloadOutcome.Failed("网络错误：${error.message ?: error.javaClass.simpleName}")
        }

        response.use { resp ->
            when {
                // 断点已失效（远端文件变小/被替换）
                resp.code == 416 -> {
                    part.delete()
                    return DownloadOutcome.Failed("断点已失效，将重新下载", retryable = true)
                }
                resp.code == 401 ->
                    return DownloadOutcome.Failed("登录状态已失效，请重新登录", retryable = false)
                resp.code == 403 ->
                    // v1.2.2：403 最常见的原因是教师锁定了文件（答案类 .py / 压缩包 /
                    // 答案文档常被锁定），而不是登录失效——把话说准，且不因此中止其余下载。
                    return DownloadOutcome.Failed(
                        "无权访问：该文件可能已被教师锁定", retryable = false
                    )
                resp.code != 200 && resp.code != 206 ->
                    return DownloadOutcome.Failed(
                        "下载失败（HTTP ${resp.code}）",
                        retryable = resp.code >= 500 || resp.code == 408 || resp.code == 429
                    )
            }

            val body = resp.body ?: return DownloadOutcome.Failed("响应内容为空")
            val contentType = body.contentType()?.toString()
            val append = DownloadPlan.appendToPart(resp.code, rangeHeader != null)
            if (!append) part.delete()
            val head = ByteArrayOutputStream()

            try {
                body.byteStream().use { input ->
                    // 续传时必须以追加模式打开（File.outputStream() 会截断已有字节），
                    // 否则 206 返回的尾部片段会覆盖掉已下载的头部，文件就此损坏。
                    DownloadPlan.partOutputStream(part, append).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            // 只留开头一小段用于登录页嗅探
                            if (head.size() < SNIFF_BYTES) {
                                head.write(buffer, 0, minOf(read, SNIFF_BYTES - head.size()))
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                // 保留 .part 供下次 Range 续传
                return DownloadOutcome.Failed("网络中断：${error.message ?: error.javaClass.simpleName}")
            }

            // 注意：ByteArrayOutputStream.toString(Charset) 是 JVM 10/Android API 33 的重载，
            // minSdk 26 不可用；ByteArray.toByteArray().toString(Charset) 是 Kotlin stdlib，全 API 安全。
            if (DownloadPlan.looksLikeLoginPage(contentType, head.toByteArray().toString(Charsets.UTF_8))) {
                part.delete()
                return DownloadOutcome.Failed("下载被重定向到登录页，会话已失效", retryable = false)
            }

            val actual = part.length()
            if (DownloadPlan.lengthMismatch(expectedSize, actual)) {
                return DownloadOutcome.Failed(
                    "下载不完整（$actual/$expectedSize 字节），已保留断点继续重试"
                )
            }
            return finalize(part, dest, actual, resumed = append)
        }
    }

    /** 原子落盘：同目录 rename；极端文件系统不支持时退化为复制后删除。 */
    private fun finalize(part: File, dest: File, bytes: Long, resumed: Boolean): DownloadOutcome {
        dest.parentFile?.mkdirs()
        if (dest.exists() && !dest.delete()) {
            return DownloadOutcome.Failed("无法覆盖已有文件：${dest.name}", retryable = false)
        }
        if (part.renameTo(dest)) {
            return DownloadOutcome.Success(dest, bytes, resumed)
        }
        return try {
            part.inputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            part.delete()
            DownloadOutcome.Success(dest, bytes, resumed)
        } catch (error: Exception) {
            DownloadOutcome.Failed("写入文件失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    companion object {
        /** 登录页嗅探只看开头 1 KiB。 */
        private const val SNIFF_BYTES = 1024

        /**
         * 净化文件名：先做**安全的百分号解码**，再移除非法字符。
         *
         * Canvas 的 filename 字段对含非 ASCII 字符的名字常返回百分号编码
         * （如 "%E6%9C%9F%E6%95%B0%E5%AD%A6.pdf"），直接落盘会让本地文件名
         * 变成乱码。这里只解码「% 后跟两个十六进制」的合法序列，并按 UTF-8
         * 重组；字面的百分号（如 "100%完成.pdf"）因 % 后不是十六进制而原样保留。
         * 解码整体失败时退回原字符串，绝不让文件名变空。
         */
        fun sanitize(name: String): String {
            val decoded = decodePercentSafely(name)
            return decoded.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().ifEmpty { "未命名" }
        }

        /** 严格解码 %XX 序列；非法序列保持原样，不抛异常。 */
        private fun decodePercentSafely(s: String): String {
            if ('%' !in s) return s
            return runCatching {
                val out = StringBuilder(s.length)
                val bytes = ByteArrayOutputStream()
                var i = 0
                while (i < s.length) {
                    if (s[i] == '%' && i + 2 < s.length) {
                        val hi = hexVal(s[i + 1])
                        val lo = hexVal(s[i + 2])
                        if (hi >= 0 && lo >= 0) {
                            bytes.write(hi shl 4 or lo)
                            i += 3
                            continue
                        }
                    }
                    flushBytes(out, bytes)
                    out.append(s[i])
                    i += 1
                }
                flushBytes(out, bytes)
                out.toString()
            }.getOrElse { s }
        }

        private fun flushBytes(out: StringBuilder, bytes: ByteArrayOutputStream) {
            if (bytes.size() > 0) {
                out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                bytes.reset()
            }
        }

        private fun hexVal(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }
}
