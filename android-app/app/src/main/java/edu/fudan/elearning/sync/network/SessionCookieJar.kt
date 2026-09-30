package edu.fudan.elearning.sync.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 内存 Cookie 容器，供登录会话复用（按域正确隔离）。
 *
 * OkHttp 会在其 dispatcher 线程上并发调用 [saveFromResponse] / [loadForRequest]，
 * 因此容器访问必须线程安全（并发读写 MutableList 会抛
 * ConcurrentModificationException，进而让登录/请求整体失败）。
 */
class SessionCookieJar : CookieJar {
    private val _cookies = mutableListOf<Cookie>()

    /** 当前 Cookie 快照（线程安全）。 */
    val cookies: List<Cookie>
        get() = synchronized(_cookies) { _cookies.toList() }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(_cookies) {
            cookies.forEach { new ->
                // 按 name + domain 去重，避免不同域的同名 Cookie 互相覆盖
                val idx = _cookies.indexOfFirst {
                    it.name == new.name && it.domain == new.domain
                }
                if (idx >= 0) _cookies[idx] = new else _cookies.add(new)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        // 只返回与该 URL 域/路径匹配的 Cookie
        synchronized(_cookies) { _cookies.filter { it.matches(url) } }

    fun clear() = synchronized(_cookies) { _cookies.clear() }
}
