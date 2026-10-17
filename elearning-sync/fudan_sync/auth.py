"""认证模块：支持 Canvas API Token、浏览器 Cookie、Playwright 交互式登录."""

from __future__ import annotations

import base64
import json
import os
import re
import secrets
import time
from typing import Any, Dict, List, Optional

import requests

CSRF_META_RE = re.compile(r'<meta\s+name="csrf-token"\s+content="([^"]+)"', re.IGNORECASE)
COOKIE_KEY_SERVICE = "fudan-elearning-sync-cookie-key"


class AuthError(RuntimeError):
    """认证失败。"""


class BaseAuth:
    """所有认证方式的公共接口。"""

    name = "base"

    def apply(self, session: requests.Session) -> None:
        raise NotImplementedError

    def verify(self, session: requests.Session, api_base: str) -> bool:
        """调用 /api/v1/users/self 验证认证是否有效。"""
        try:
            response = session.get(f"{api_base}/users/self", timeout=30)
            if response.status_code == 200:
                data = response.json()
                return bool(data.get("id"))
            return False
        except (requests.RequestException, ValueError):
            return False


class TokenAuth(BaseAuth):
    """Canvas Personal Access Token（Bearer）。"""

    name = "token"

    def __init__(self, token: str):
        if not token:
            raise AuthError("API Token 为空")
        self.token = token.strip()

    def apply(self, session: requests.Session) -> None:
        session.headers.update({"Authorization": f"Bearer {self.token}"})


class CookieAuth(BaseAuth):
    """使用浏览器会话 Cookie 访问 API（只读 GET 请求无需 CSRF）。"""

    name = "cookie"

    def __init__(self, cookie_file: str):
        self.cookie_file = cookie_file
        self.cookies: List[Dict[str, Any]] = []
        self.csrf_token: Optional[str] = None
        self._load()

    def _load(self) -> None:
        if not os.path.exists(self.cookie_file):
            raise AuthError(f"Cookie 文件不存在: {self.cookie_file}（请先运行 login 或手动导出）")
        with open(self.cookie_file, "r", encoding="utf-8") as handle:
            raw = json.load(handle)
        if isinstance(raw, dict) and raw.get("encrypted") is True:
            try:
                raw = _decrypt_cookie_payload(raw)
            except Exception as exc:  # pylint: disable=broad-except
                raise AuthError("Cookie 文件无法解密，请在原设备上重新登录") from exc

        cookies: List[Dict[str, Any]] = []
        if isinstance(raw, list):  # Playwright context.cookies() 格式
            cookies = [c for c in raw if isinstance(c, dict) and c.get("name")]
        elif isinstance(raw, dict):
            if isinstance(raw.get("cookies"), list):  # {"cookies": [...], "csrf_token": ...}
                cookies = [c for c in raw["cookies"] if isinstance(c, dict) and c.get("name")]
                self.csrf_token = raw.get("csrf_token")
            elif isinstance(raw.get("cookie_header"), str):  # 原始 header 字符串
                for pair in raw["cookie_header"].split(";"):
                    if "=" in pair:
                        name, value = pair.strip().split("=", 1)
                        cookies.append({"name": name, "value": value, "domain": "", "path": "/"})
            else:  # 简单 {name: value} 映射
                cookies = [
                    {"name": str(k), "value": str(v), "domain": "", "path": "/"}
                    for k, v in raw.items()
                    if v is not None
                ]
        else:
            raise AuthError(f"无法识别的 Cookie 文件格式: {self.cookie_file}")

        if not cookies:
            raise AuthError("Cookie 文件中未找到有效 cookie")
        self.cookies = cookies

    def apply(self, session: requests.Session) -> None:
        for cookie in self.cookies:
            session.cookies.set(
                cookie["name"],
                cookie["value"],
                domain=cookie.get("domain") or "",
                path=cookie.get("path") or "/",
            )
        if self.csrf_token:
            session.headers.update({"X-CSRF-Token": self.csrf_token})

    def extract_csrf(self, session: requests.Session, base_url: str) -> Optional[str]:
        """从已登录首页 HTML 中提取 CSRF token（提升 API 访问稳定性）。"""
        try:
            response = session.get(base_url, timeout=30, allow_redirects=True)
            match = CSRF_META_RE.search(response.text or "")
            if match:
                self.csrf_token = match.group(1)
                session.headers.update({"X-CSRF-Token": self.csrf_token})
        except requests.RequestException:
            pass
        return self.csrf_token


def save_cookies(
    cookie_file: str, cookies: List[Dict[str, Any]], csrf_token: Optional[str] = None
) -> None:
    """保存 cookie（Playwright 格式）到 JSON 文件，并限制文件权限。"""
    payload = {"csrf_token": csrf_token, "saved_at": int(time.time()), "cookies": cookies}
    try:
        payload = _encrypt_cookie_payload(payload)
    except Exception:
        # 无法使用系统钥匙串时保留兼容能力，同时仍收紧文件权限。
        pass
    os.makedirs(os.path.dirname(os.path.abspath(cookie_file)), exist_ok=True)
    with open(cookie_file, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
    _restrict_file_permissions(cookie_file)


def _cookie_key() -> bytes:
    """从系统钥匙串取得本机 Cookie 加密密钥。"""
    import keyring

    encoded = keyring.get_password(COOKIE_KEY_SERVICE, "default")
    if not encoded:
        encoded = base64.urlsafe_b64encode(secrets.token_bytes(32)).decode("ascii")
        keyring.set_password(COOKIE_KEY_SERVICE, "default", encoded)
    return base64.urlsafe_b64decode(encoded.encode("ascii"))


def _encrypt_cookie_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    from Crypto.Cipher import AES

    cipher = AES.new(_cookie_key(), AES.MODE_GCM)
    ciphertext, tag = cipher.encrypt_and_digest(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    )
    return {
        "encrypted": True,
        "algorithm": "AES-256-GCM",
        "nonce": base64.b64encode(cipher.nonce).decode("ascii"),
        "ciphertext": base64.b64encode(ciphertext).decode("ascii"),
        "tag": base64.b64encode(tag).decode("ascii"),
    }


def _decrypt_cookie_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    from Crypto.Cipher import AES

    cipher = AES.new(_cookie_key(), AES.MODE_GCM, nonce=base64.b64decode(payload["nonce"]))
    plaintext = cipher.decrypt_and_verify(
        base64.b64decode(payload["ciphertext"]), base64.b64decode(payload["tag"])
    )
    decoded = json.loads(plaintext.decode("utf-8"))
    if not isinstance(decoded, dict):
        raise ValueError("invalid cookie payload")
    return decoded


def _restrict_file_permissions(path: str) -> None:
    """尽可能限制敏感文件的可访问范围（Windows 用 icacls，POSIX 用 chmod）。"""
    try:
        if os.name == "nt":
            import subprocess

            subprocess.run(
                [
                    "icacls",
                    path,
                    "/inheritance:r",
                    "/grant:r",
                    f"{os.environ.get('USERNAME', '')}:F",
                ],
                check=False,
                capture_output=True,
                timeout=15,
            )
        else:
            os.chmod(path, 0o600)
    except Exception:  # pylint: disable=broad-except
        pass


def _persist_method(cfg, method: str) -> None:
    """把自动识别出的认证方式写回配置文件，避免每次启动 / 同步都重新探测。

    写盘失败时只更新内存配置（不致影响本次使用）。这里刻意只用 yaml，
    不依赖 PySide6，保证 CLI 环境也能用。
    """
    cfg.auth_method = method
    path = getattr(cfg, "config_path", "") or ""
    if not path:
        return
    try:
        import yaml  # pylint: disable=import-outside-toplevel

        data = {}
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as handle:
                data = yaml.safe_load(handle) or {}
        data.setdefault("auth", {})["method"] = method
        with open(path, "w", encoding="utf-8") as handle:
            yaml.safe_dump(data, handle, allow_unicode=True, sort_keys=False)
    except Exception:  # pylint: disable=broad-except
        pass


def build_auth(cfg) -> BaseAuth:
    """根据配置构造认证对象（CLI 与 GUI 共用，避免逻辑重复）。

    cfg 只需提供属性：auth_method, token, cookie_file, base_url, uis_username,
    config_path。
    password 方式会先用钥匙串里的密码静默登录，再返回 Cookie 认证。

    兼容老配置：auth.method 缺失时 load_config 会回退成 "token"。此时若没有
    配置 token 但钥匙串里存着密码，应按密码登录处理并写回配置，而不是抛
    “API Token 为空”——否则会出现“登录成功但一点同步就报没有 Token”。
    """
    method = (getattr(cfg, "auth_method", "") or "").lower()
    token = (getattr(cfg, "token", "") or os.environ.get("FUDAN_ELEARNING_TOKEN", "") or "").strip()

    if method in ("", "token") and not token:
        # 没配 token：钥匙串里有密码就走密码登录，并把这个决定持久化
        from .password_login import has_stored_password  # pylint: disable=import-outside-toplevel

        username = getattr(cfg, "uis_username", "") or ""
        if username and has_stored_password(username):
            _persist_method(cfg, "password")
            method = "password"
        elif method == "token":
            raise AuthError(
                "尚未配置登录凭据：认证方式为 token 但未设置 token，"
                "也没有保存的账号密码。请在软件中登录。"
            )

    if method == "token":
        return TokenAuth(token)
    if method in ("cookie", "browser"):
        return CookieAuth(cfg.cookie_file)
    if method == "password":
        # 延迟导入，避免与 password_login 模块形成循环导入
        from .password_login import (  # pylint: disable=import-outside-toplevel
            load_password,
            login_with_stored_credentials,
        )

        username = getattr(cfg, "uis_username", "") or ""
        cookie_file = getattr(cfg, "cookie_file", "") or "cookies.json"
        # 记住了密码：静默重登，刷新会话，并把方式固化成 password
        if username and load_password(username) is not None:
            _persist_method(cfg, "password")
            login_with_stored_credentials(cfg.base_url, username, cookie_file)
            return CookieAuth(cookie_file)
        # 没记住密码（登录时未勾选“记住密码”）：复用已保存的会话 cookie，
        # 并把认证方式改写为 cookie，避免下次启动再走 password 却找不到密码
        if cookie_file and os.path.exists(cookie_file):
            _persist_method(cfg, "cookie")
            return CookieAuth(cookie_file)
        raise AuthError(
            "登录会话已失效且未保存密码，请在软件中重新登录（或在登录时勾选“记住密码”）"
        )
    raise AuthError(f"未知认证方式: {method}")


def browser_login(
    base_url: str, cookie_file: str, headless: bool = False, timeout_seconds: int = 900
) -> CookieAuth:
    """启动真实浏览器引导用户完成 UIS 登录，成功后自动保存 Cookie。

    需要：pip install playwright && python -m playwright install chromium
    """
    try:
        from playwright.sync_api import sync_playwright  # pylint: disable=import-outside-toplevel
    except ImportError as exc:
        raise AuthError(
            "未安装 playwright。请执行：\n"
            "  pip install playwright\n"
            "  python -m playwright install chromium\n"
            "或改用 token / cookie 方式登录。"
        ) from exc

    login_url = f"{base_url.rstrip('/')}/login"
    host = base_url.rstrip("/").split("//", 1)[-1]
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=headless, args=["--start-maximized"])
        context = browser.new_context(ignore_https_errors=True)
        page = context.new_page()
        print(f"[browser_login] 正在打开 {login_url}")
        print("[browser_login] 请在浏览器中完成复旦大学统一身份认证（UIS）登录。")
        print(f"[browser_login] 登录成功后程序会自动捕获 Cookie（最长等待 {timeout_seconds} 秒）。")
        page.goto(login_url, wait_until="domcontentloaded")

        deadline = time.time() + timeout_seconds
        logged_in = False
        while time.time() < deadline:
            current = page.url or ""
            # 已登录标志：URL 回到 elearning 域且不在登录页，且页面含用户菜单
            if host in current and "/login" not in current:
                try:
                    page.wait_for_selector(
                        "button[aria-label*='账户'], button[aria-label*='Account'], "
                        "#global_nav_profile_link, .ic-avatar",
                        timeout=15000,
                    )
                    logged_in = True
                    break
                except Exception:  # pylint: disable=broad-except
                    # 选择器没找到但 URL 已回主页，也视为登录成功
                    if "/login" not in current:
                        logged_in = True
                        break
            time.sleep(1.5)

        if not logged_in:
            browser.close()
            raise AuthError("登录超时或未检测到登录成功状态。")

        cookies = [c for c in context.cookies() if host in (c.get("domain") or "")]
        csrf_token = None
        try:
            csrf_token = page.evaluate(
                "() => (document.querySelector('meta[name=csrf-token]') || {}).value || null"
            )
        except Exception:  # pylint: disable=broad-except
            pass
        browser.close()

    if not cookies:
        raise AuthError("未捕获到 elearning 域的 Cookie，登录可能未完成。")
    save_cookies(cookie_file, cookies, csrf_token)
    print(f"[browser_login] 已保存 {len(cookies)} 条 Cookie 到 {cookie_file}")
    return CookieAuth(cookie_file)
