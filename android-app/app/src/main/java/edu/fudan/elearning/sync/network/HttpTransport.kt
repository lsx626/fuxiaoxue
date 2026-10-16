package edu.fudan.elearning.sync.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一次已读完全文的 HTTP 响应。
 *
 * Canvas 的元数据接口很小（课程/文件列表），一次读入内存换来的是可以精确测试
 * 分页与限流逻辑；真正的文件内容下载不走这里（见 `DownloadManager` 的流式写入）。
 */
data class HttpResponse(
    val code: Int,
    val headers: Map<String, String>,
    val body: String
) {
    val isSuccessful: Boolean get() = code in 200..299

    /** 大小写不敏感地取响应头。 */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * HTTP 传输抽象。
 *
 * 生产实现走 [OkHttpTransport]（复用 [ApiClient] 的会话 Cookie 与 User-Agent），
 * 单元测试用假实现驱动分页、限流、退避与错误映射，不需要真实网络。
 */
interface HttpTransport {
    suspend fun get(url: String): HttpResponse
}

/** 基于 OkHttp 的传输实现，复用带会话 Cookie 的 [ApiClient] 客户端。 */
class OkHttpTransport : HttpTransport {
    override suspend fun get(url: String): HttpResponse = withContext(Dispatchers.IO) {
        ApiClient.getCall(url).readCancellable { response, checkActive ->
            checkActive()
            val headers = response.headers.names().associateWith { name ->
                response.header(name) ?: ""
            }
            HttpResponse(
                code = response.code,
                headers = headers,
                body = response.body?.string() ?: ""
            )
        }
    }
}
