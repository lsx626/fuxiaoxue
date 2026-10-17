"""新版 UIS（id.fudan.edu.cn）账号密码登录 + 系统钥匙串安全存储。

登录流程（基于 2026 年新版身份认证平台）：
  1. 访问 elearning 登录页 → 跳转到 id.fudan.edu.cn，获取 lck 和 entityId
  2. POST /idp/authn/queryAuthMethods → 获取 authChainCode
  3. POST /idp/authn/getJsPublicKey → 获取 RSA 公钥（DER base64）
  4. RSA PKCS1_v1_5 加密密码
  5. POST /idp/authn/authExecute → 提交加密后的账号密码 → 获得 loginToken
  6. POST /idp/authCenter/authnEngine (form) → 完成 SSO 跳转回 eLearning
  7. 从首页 meta 提取 CSRF token，保存 cookie 到本地

之后每次启动：读钥匙串密码 → 自动登录 → 同步，用户无感知。

密码安全存储：
  - 账号存配置文件（非敏感）
  - 密码存系统钥匙串（Windows Credential Manager / macOS Keychain / Linux Secret Service）
"""

from __future__ import annotations

import base64
import re
from typing import Any, Dict, List, Optional, Tuple

import requests

from .auth import AuthError, save_cookies

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
)

KEYRING_SERVICE = "fudan-elearning-sync"

CSRF_META_RE = re.compile(r'<meta\s+name="csrf-token"\s+content="([^"]+)"', re.IGNORECASE)


class PasswordLoginError(AuthError):
    """账号密码登录失败。"""

    def __init__(self, message: str, need_captcha: bool = False):
        super().__init__(message)
        self.need_captcha = need_captcha


def _new_session() -> requests.Session:
    session = requests.Session()
    session.headers.update(
        {
            "User-Agent": USER_AGENT,
            "Accept": "application/json, text/plain, */*",
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        }
    )
    return session


def _rsa_encrypt(public_key_b64: str, plaintext: str) -> str:
    """RSA PKCS1_v1_5 加密（与 JSEncrypt 兼容），返回 base64 密文。"""
    try:
        from Crypto.Cipher import PKCS1_v1_5  # type: ignore
        from Crypto.PublicKey import RSA  # type: ignore
    except ImportError as exc:
        raise PasswordLoginError("未安装 pycryptodome。请执行：pip install pycryptodome") from exc

    der_bytes = base64.b64decode(public_key_b64)
    key = RSA.import_key(der_bytes)
    cipher = PKCS1_v1_5.new(key)
    encrypted = cipher.encrypt(plaintext.encode("utf-8"))
    return base64.b64encode(encrypted).decode("ascii")


def password_login(
    base_url: str, username: str, password: str, timeout: int = 60
) -> Tuple[requests.Session, Optional[str]]:
    """用新版 UIS 账号密码登录 eLearning，返回已建立会话的 Session 与 CSRF token。

    Args:
        base_url: eLearning 基础地址，如 https://elearning.fudan.edu.cn
        username: 学号 / 工号
        password: 密码
        timeout: 请求超时秒数

    Returns:
        (session, csrf_token) 元组

    Raises:
        PasswordLoginError: 登录失败
    """
    if not username or not password:
        raise PasswordLoginError("账号或密码不能为空")

    base = base_url.rstrip("/")
    idp_base = "https://id.fudan.edu.cn/idp"
    session = _new_session()

    # ---------- Step 1: 获取登录上下文（lck + entityId） ----------
    try:
        response = session.get(f"{base}/login", allow_redirects=True, timeout=timeout)
    except requests.RequestException as exc:
        raise PasswordLoginError(f"无法访问登录页：{exc}") from exc

    # lck/entityId 位于重定向后 URL 的 fragment（# 之后），兼容不同网络环境下的
    # 重定向行为：依次从最终地址、各跳重定向头、页面正文中查找。
    candidates = []
    if response.url:
        candidates.append(response.url)
    for hop in response.history:
        location = hop.headers.get("Location")
        if location:
            candidates.append(location)
    candidates.append(response.text or "")

    lck = entity_id = None
    for cand in candidates:
        lck_match = re.search(r"lck=([^&\"'#\s]+)", cand)
        entity_match = re.search(r"entityId=([^&\"'#\s]+)", cand)
        if lck_match and entity_match:
            lck = lck_match.group(1)
            entity_id = entity_match.group(1)
            break
    if not lck or not entity_id:
        raise PasswordLoginError("无法获取登录上下文（lck/entityId 缺失），请检查网络或稍后重试")

    # ---------- Step 2: 查询认证方式，获取 authChainCode ----------
    try:
        response = session.post(
            f"{idp_base}/authn/queryAuthMethods",
            json={"lck": lck, "entityId": entity_id},
            timeout=timeout,
        )
        data = response.json()
    except (requests.RequestException, ValueError) as exc:
        raise PasswordLoginError(f"查询认证方式失败：{exc}") from exc

    auth_chain_code = None
    methods = data.get("data") or []
    for item in methods:
        if (
            item.get("authModuleCode") == "userAndPwd"
            or item.get("moduleNameShortZh") == "账号密码"
        ):
            auth_chain_code = item.get("authChainCode")
            break
    if not auth_chain_code and methods:
        auth_chain_code = methods[0].get("authChainCode")
    if not auth_chain_code:
        raise PasswordLoginError("未找到账号密码认证方式，请检查平台配置")

    # ---------- Step 3: 获取 RSA 公钥 ----------
    try:
        response = session.post(f"{idp_base}/authn/getJsPublicKey", timeout=timeout)
        pk_data = response.json()
    except (requests.RequestException, ValueError) as exc:
        raise PasswordLoginError(f"获取加密公钥失败：{exc}") from exc

    pubkey_b64 = pk_data.get("data") if str(pk_data.get("code")) == "200" else None
    if not pubkey_b64:
        raise PasswordLoginError(f"获取加密公钥失败：{pk_data.get('message', '未知错误')}")

    # ---------- Step 4: RSA 加密密码 ----------
    encrypted_pwd = _rsa_encrypt(pubkey_b64, password)

    # ---------- Step 5: 提交登录 ----------
    try:
        response = session.post(
            f"{idp_base}/authn/authExecute",
            json={
                "authModuleCode": "userAndPwd",
                "authChainCode": auth_chain_code,
                "entityId": entity_id,
                "requestType": "chain_type",
                "lck": lck,
                "authPara": {
                    "loginName": username,
                    "password": encrypted_pwd,
                    "verifyCode": "",
                },
            },
            timeout=timeout,
        )
        result = response.json()
    except (requests.RequestException, ValueError) as exc:
        raise PasswordLoginError(f"登录请求失败：{exc}") from exc

    code = str(result.get("code"))
    message = result.get("message", "") or ""
    if code == "200":
        login_token = result.get("loginToken") or result.get("data", {}).get("loginToken")
        if not login_token:
            raise PasswordLoginError("登录成功但未获取到 loginToken")
    elif code == "4340":
        # 需要进一步操作（如二次验证）
        visit_url = (result.get("data") or {}).get("visitUrl", "")
        raise PasswordLoginError(f"登录需要额外验证：{message or visit_url or '未知'}")
    elif "captcha" in message.lower() or "验证码" in message:
        raise PasswordLoginError(
            "平台要求输入验证码。请稍后重试或在浏览器中完成登录后导出 Cookie 使用。",
            need_captcha=True,
        )
    else:
        # 其他错误统一归类为账号密码错误
        raise PasswordLoginError(message or "账号或密码错误，请重新输入")

    # ---------- Step 6: 回调 authCenter，拿到 CAS ticket 跳转地址 ----------
    try:
        response = session.post(
            "https://id.fudan.edu.cn/idp/authCenter/authnEngine",
            data={"loginToken": login_token},
            allow_redirects=False,
            timeout=timeout,
        )
    except requests.RequestException as exc:
        raise PasswordLoginError(f"完成 SSO 跳转失败：{exc}") from exc

    # authnEngine 返回的是含 JS 跳转的中间页，需解析出 CAS ticket 地址；
    # 个别网络环境下也可能直接 302 到 ticket 地址，一并列作候选。
    body = response.text or ""
    ticket_candidates = []
    ticket_match = re.search(r'locationValue\s*=\s*["\']([^"\']+)["\']', body)
    if ticket_match:
        ticket_candidates.append(ticket_match.group(1))
    ticket_match = re.search(r'["\'](https?://[^"\']*/login/cas\?ticket=[^"\']+)["\']', body)
    if ticket_match:
        ticket_candidates.append(ticket_match.group(1))
    redirect = response.headers.get("Location", "")
    if redirect.startswith("http") and "ticket=" in redirect:
        ticket_candidates.append(redirect)

    if not ticket_candidates:
        raise PasswordLoginError(_missing_ticket_message(response.status_code, body))

    cas_ok = False
    for ticket_url in ticket_candidates:
        try:
            session.get(ticket_url, allow_redirects=True, timeout=timeout)
            cas_ok = True
            break
        except requests.RequestException:
            continue  # 尝试下一个候选地址
    if not cas_ok:
        raise PasswordLoginError("CAS 认证失败：所有回调地址均无法访问，请检查网络")

    # ---------- Step 7: 访问 eLearning 首页，确认登录成功并提取 CSRF ----------
    try:
        home = session.get(f"{base}/", allow_redirects=True, timeout=timeout)
    except requests.RequestException as exc:
        raise PasswordLoginError(f"访问首页失败：{exc}") from exc

    body = home.text or ""
    # 确认已登录（存在 Canvas 会话 Cookie）
    session_names = {c.name for c in session.cookies}
    if not (session_names & {"_normandy_session", "_canvas_session"}):
        raise PasswordLoginError(
            "登录未成功：未获取到 eLearning 会话。请确认账号密码正确；"
            f"如一直失败请检查网络代理。当前 Cookie：{sorted(session_names) or '无'}"
        )

    csrf_match = CSRF_META_RE.search(body)
    csrf_token = csrf_match.group(1) if csrf_match else None

    return session, csrf_token


def _missing_ticket_message(status_code: int, body: str) -> str:
    """
    认证中间页缺少 CAS 回调地址时的错误文案。

    安全硬性要求：认证中间页可能含跳转地址与认证上下文，异常文案里**绝不能**
    带上响应正文片段；这里只保留 HTTP 状态与响应长度，既能排障又不泄露内容。
    """
    return (
        "登录跳转异常：服务器应答中没有 CAS 回调地址。"
        f"（HTTP {status_code}，响应长度 {len(body)} 字节；"
        "可稍后重试，或检查网络代理设置）"
    )


def session_cookies(session: requests.Session) -> List[Dict[str, Any]]:
    """把 requests 会话的 cookie 转成 Playwright 风格（CookieAuth 可直接加载）。"""
    cookies: List[Dict[str, Any]] = []
    for cookie in session.cookies:
        if not cookie.name:
            continue
        cookies.append(
            {
                "name": cookie.name,
                "value": cookie.value or "",
                "domain": cookie.domain or "",
                "path": cookie.path or "/",
            }
        )
    return cookies


def login_and_save(
    base_url: str, username: str, password: str, cookie_file: str, store_credentials: bool = True
) -> Tuple[str, Optional[str]]:
    """账号密码登录并持久化：cookie 落盘 + 密码存钥匙串。

    返回 (cookie 文件路径, csrf_token)。
    """
    session, csrf_token = password_login(base_url, username, password)
    cookies = session_cookies(session)
    if not any(c["name"] in ("_normandy_session", "_canvas_session") for c in cookies):
        raise PasswordLoginError("登录后未获得会话 Cookie，登录可能未真正成功")
    save_cookies(cookie_file, cookies, csrf_token)
    if store_credentials:
        save_credentials(username, password)
    return cookie_file, csrf_token


# ---------------------------------------------------------------------------
# 系统钥匙串（Windows Credential Manager / macOS Keychain / Secret Service）
# ---------------------------------------------------------------------------


def _get_keyring():
    try:
        import keyring  # pylint: disable=import-outside-toplevel

        return keyring
    except ImportError as exc:
        raise PasswordLoginError("未安装 keyring。请执行 pip install keyring 后重试") from exc


def save_credentials(username: str, password: str) -> None:
    """把密码存入系统钥匙串（账号本身不敏感，存配置文件）。"""
    kr = _get_keyring()
    kr.set_password(KEYRING_SERVICE, username, password)


def load_password(username: str) -> Optional[str]:
    """从钥匙串读取密码；不存在或钥匙串不可用时返回 None。"""
    if not username:
        return None
    try:
        return _get_keyring().get_password(KEYRING_SERVICE, username)
    except Exception:  # pylint: disable=broad-except
        return None


def clear_credentials(username: Optional[str] = None) -> None:
    """清除钥匙串中的凭据。"""
    try:
        kr = _get_keyring()
    except PasswordLoginError:
        return
    if username:
        try:
            kr.delete_password(KEYRING_SERVICE, username)
        except Exception:  # pylint: disable=broad-except
            pass
        return
    try:
        for entry in kr.get_credential(KEYRING_SERVICE, "") or []:
            try:
                kr.delete_password(KEYRING_SERVICE, entry.username)
            except Exception:  # pylint: disable=broad-except
                pass
    except Exception:  # pylint: disable=broad-except
        pass


def has_stored_password(username: str) -> bool:
    """探测钥匙串里是否存有指定账号的密码（不返回密码本身，失败返回 False）。

    供认证方式自动回退使用：配置里 auth.method 缺失（会被 load_config 回退成
    token）但没有配 token，若钥匙串里有密码，就应当按密码登录处理，
    而不是误报“API Token 为空”。
    """
    if not username:
        return False
    try:
        return load_password(username) is not None
    except Exception:  # pylint: disable=broad-except
        return False


def login_with_stored_credentials(
    base_url: str, username: str, cookie_file: str
) -> Tuple[str, Optional[str]]:
    """用已存的账号密码静默登录（启动时自动调用）。"""
    password = load_password(username)
    if not password:
        raise PasswordLoginError("钥匙串中没有保存的密码，请重新登录")
    return login_and_save(base_url, username, password, cookie_file, store_credentials=False)
