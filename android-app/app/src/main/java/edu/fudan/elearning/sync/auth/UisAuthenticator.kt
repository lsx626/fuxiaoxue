package edu.fudan.elearning.sync.auth

import android.util.Base64
import edu.fudan.elearning.sync.network.SessionCookieJar
import edu.fudan.elearning.sync.network.readCancellable
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** 登录会话：Cookie 与 CSRF token。 */
data class AuthSession(
    val canvasSessionCookie: String,
    val csrfToken: String?,
    /** 会话 Cookie 名：`_normandy_session` 或 `_canvas_session`。 */
    val cookieName: String = "_normandy_session",
)

/** 登录结果封装。 */
sealed class LoginResult {
    data class Success(val session: AuthSession) : LoginResult()

    data class Failure(val message: String, val retryable: Boolean = false) : LoginResult()
}

/**
 * 新版复旦大学 UIS（id.fudan.edu.cn）账号密码登录。
 *
 * 流程：
 * 1. 访问 elearning 登录页 → 跳转 id.fudan.edu.cn，取 lck + entityId
 * 2. POST /idp/authn/queryAuthMethods → authChainCode
 * 3. POST /idp/authn/getJsPublicKey → RSA 公钥（DER base64）
 * 4. RSA PKCS1_v1_5 加密密码
 * 5. POST /idp/authn/authExecute → loginToken
 * 6. POST /ac/authCenter/authnEngine (form) → 完成 SSO 回跳 eLearning
 */
class UisAuthenticator(private val baseUrl: String = "https://elearning.fudan.edu.cn") {
    private val cookieJar = SessionCookieJar()
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain
                        .request()
                        .newBuilder()
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                        )
                        .header("Accept", "application/json, text/plain, */*")
                        .build()
                )
            }
            .build()

    suspend fun login(username: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            try {
                doLogin(username, password)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: java.io.IOException) {
                LoginResult.Failure("无法连接统一身份认证，请检查网络后重试", retryable = true)
            } catch (e: Exception) {
                LoginResult.Failure("登录服务返回异常，请稍后重试")
            }
        }

    /** 用已保存的账号密码静默登录。 */
    suspend fun silentLogin(username: String, password: String): LoginResult =
        login(username, password)

    private suspend fun doLogin(username: String, password: String): LoginResult {
        val idpBase = "https://id.fudan.edu.cn/idp"

        // Step 1: 获取登录上下文（lck/entityId 在 URL fragment 里，不在 query string）
        val fullUrl =
            client.newCall(Request.Builder().url("$baseUrl/login").get().build()).readCancellable {
                response,
                _ ->
                response.request.url.toString()
            }
        val lck = Regex("lck=([^&]+)").find(fullUrl)?.groupValues?.get(1) ?: ""
        var entityId = Regex("entityId=([^&]+)").find(fullUrl)?.groupValues?.get(1) ?: ""
        entityId = java.net.URLDecoder.decode(entityId, "UTF-8")
        if (lck.isEmpty() || entityId.isEmpty()) {
            return LoginResult.Failure("无法获取登录上下文，请检查网络")
        }

        // Step 2: 查询认证方式
        val authMethods =
            client
                .newCall(
                    Request.Builder()
                        .url("$idpBase/authn/queryAuthMethods")
                        .post(
                            JSONObject()
                                .apply {
                                    put("lck", lck)
                                    put("entityId", entityId)
                                }
                                .toString()
                                .toRequestBody("application/json".toMediaType())
                        )
                        .build()
                )
                .readCancellable { response, _ -> response.body?.string() ?: "{}" }
        val methodsJson = JSONObject(authMethods)
        val methodsArr = methodsJson.optJSONArray("data")
        var authChainCode = ""
        if (methodsArr != null && methodsArr.length() > 0) {
            for (i in 0 until methodsArr.length()) {
                val item = methodsArr.optJSONObject(i) ?: continue
                if (
                    item.optString("authModuleCode") == "userAndPwd" ||
                        item.optString("moduleNameShortZh") == "账号密码"
                ) {
                    authChainCode = item.optString("authChainCode")
                    break
                }
            }
            if (authChainCode.isEmpty()) {
                authChainCode = methodsArr.optJSONObject(0)?.optString("authChainCode") ?: ""
            }
        }
        if (authChainCode.isEmpty()) {
            return LoginResult.Failure("未找到账号密码认证方式")
        }

        // Step 3: 获取 RSA 公钥
        val pubKeyResp =
            client
                .newCall(
                    Request.Builder()
                        .url("$idpBase/authn/getJsPublicKey")
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .build()
                )
                .readCancellable { response, _ -> response.body?.string() ?: "{}" }
        val pubKeyJson = JSONObject(pubKeyResp)
        val pubKeyB64 =
            if (pubKeyJson.optString("code") == "200") pubKeyJson.optString("data") else ""
        if (pubKeyB64.isEmpty()) {
            return LoginResult.Failure("获取加密公钥失败：${pubKeyJson.optString("message", "未知错误")}")
        }

        // Step 4: RSA 加密密码
        val encryptedPwd = rsaEncrypt(pubKeyB64, password)

        // Step 5: 提交登录
        val authPara =
            JSONObject().apply {
                put("loginName", username)
                put("password", encryptedPwd)
                put("verifyCode", "")
            }
        val executeResp =
            client
                .newCall(
                    Request.Builder()
                        .url("$idpBase/authn/authExecute")
                        .post(
                            JSONObject()
                                .apply {
                                    put("authModuleCode", "userAndPwd")
                                    put("authChainCode", authChainCode)
                                    put("entityId", entityId)
                                    put("requestType", "chain_type")
                                    put("lck", lck)
                                    put("authPara", authPara)
                                }
                                .toString()
                                .toRequestBody("application/json".toMediaType())
                        )
                        .build()
                )
                .readCancellable { response, _ -> response.body?.string() ?: "{}" }
        val execJson = JSONObject(executeResp)
        val code = execJson.optString("code")
        if (code != "200") {
            val message = execJson.optString("message", "")
            return LoginResult.Failure(if (message.isNotEmpty()) message else "账号或密码错误，请重新输入")
        }
        var loginToken = execJson.optString("loginToken")
        if (loginToken.isEmpty()) {
            loginToken = execJson.optJSONObject("data")?.optString("loginToken") ?: ""
        }
        if (loginToken.isEmpty()) {
            return LoginResult.Failure("登录成功但未获取到登录令牌")
        }

        // Step 6: 回调 authnEngine，拿到 CAS ticket 跳转地址
        val engineBody =
            client
                .newCall(
                    Request.Builder()
                        .url("https://id.fudan.edu.cn/idp/authCenter/authnEngine")
                        .post(FormBody.Builder().add("loginToken", loginToken).build())
                        .build()
                )
                .readCancellable { response, _ -> response.body?.string().orEmpty() }
        val ticketUrl =
            Regex("""locationValue\s*=\s*["']([^"']+)["']""").find(engineBody)?.groupValues?.get(1)
        if (!ticketUrl.isNullOrEmpty()) {
            client.newCall(Request.Builder().url(ticketUrl).get().build()).readCancellable { _, _ ->
                Unit
            }
        }

        // Step 7: 访问首页，确认登录并取 CSRF
        val body =
            client.newCall(Request.Builder().url("$baseUrl/").get().build()).readCancellable {
                response,
                _ ->
                response.body?.string().orEmpty()
            }
        // Canvas 在不同部署/登录路径下会下发 _normandy_session 或 _canvas_session，
        // 两者都必须接受，否则会出现「密码正确却提示登录失败」。
        val sessionCookie =
            cookieJar.cookies.firstOrNull { it.name == "_normandy_session" }
                ?: cookieJar.cookies.firstOrNull { it.name == "_canvas_session" }
        val canvasCookie = sessionCookie?.value ?: ""
        if (canvasCookie.isEmpty()) {
            return LoginResult.Failure("登录未成功，请检查账号密码")
        }
        val csrf =
            Regex("""<meta\s+name="csrf-token"\s+content="([^"]+)"""")
                .find(body)
                ?.groupValues
                ?.get(1)

        return LoginResult.Success(
            AuthSession(canvasCookie, csrf, sessionCookie?.name ?: "_normandy_session")
        )
    }

    private fun rsaEncrypt(publicKeyB64: String, plaintext: String): String {
        val der = Base64.decode(publicKeyB64, Base64.DEFAULT)
        val keyFactory = KeyFactory.getInstance("RSA")
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(der))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }
}
