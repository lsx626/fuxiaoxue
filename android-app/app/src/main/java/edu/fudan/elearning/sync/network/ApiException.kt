package edu.fudan.elearning.sync.network

/**
 * Canvas 请求的可区分错误。
 *
 * 旧实现把所有异常都吞成空列表，界面于是把「登录失效」「被限流」「网络断了」 一律显示成「同步完成、0 个文件」。这里按语义分类，调用方必须显式处理：
 * - [Auth]：会话失效/无权限，需要重新登录，**绝不能**当成「远端没有文件」；
 * - [RateLimited]：被限流且重试后仍失败，带建议等待时间；
 * - [Server]：4xx/5xx（不含限流与鉴权）；
 * - [Network]：连接/超时等 IO 失败；
 * - [Parse]：响应不是预期 JSON。
 */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class Auth(message: String = "登录状态已失效，请重新登录") : ApiException(message)

    class RateLimited(val retryAfterMs: Long, message: String = "请求过于频繁，请稍后再试") :
        ApiException(message)

    class Server(val code: Int, val url: String, message: String = "请求失败（HTTP $code）") :
        ApiException(message)

    class Network(cause: Throwable) :
        ApiException("网络错误：" + (cause.message ?: cause.javaClass.simpleName), cause)

    class Parse(message: String, cause: Throwable? = null) : ApiException(message, cause)
}
