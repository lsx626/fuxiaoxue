"""回归测试：登录时未勾选“记住密码”，后续同步不得报“API Token 为空”。

复现路径：首次登录 remember=False 时，旧代码会把 auth.method 持久化成
password，但钥匙串里其实没有密码；下一次 build_auth 走到 password 分支，
login_with_stored_credentials 取不到密码就抛“钥匙串中没有保存的密码”，
更早的版本甚至直接抛 “API Token 为空”。正确行为是回退到本次登录已保存的
会话 cookie，并把认证方式改写为 cookie。
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import fudan_sync.password_login as pl  # noqa: E402
from fudan_sync.auth import AuthError, CookieAuth, build_auth  # noqa: E402
from fudan_sync.config import load_config  # noqa: E402

USERNAME = "20300123456"


def _write_cookie(cookie_file: str) -> None:
    with open(cookie_file, "w", encoding="utf-8") as handle:
        json.dump(
            {"cookies": [{"name": "_canvas_session", "value": "x", "domain": "", "path": "/"}]},
            handle,
        )


class NoRememberLoginTests(unittest.TestCase):
    """remember=False 的登录链路。"""

    def setUp(self):
        self._orig_load = pl.load_password
        self._orig_login = pl.login_with_stored_credentials
        pl.load_password = lambda username: None  # 钥匙串里没有密码
        self.tmp = tempfile.mkdtemp()
        self.cfg_path = os.path.join(self.tmp, "config.yaml")
        self.cookie_file = os.path.join(self.tmp, "cookies.json")

    def tearDown(self):
        pl.load_password = self._orig_load
        pl.login_with_stored_credentials = self._orig_login

    def _cfg(self, method: str):
        with open(self.cfg_path, "w", encoding="utf-8") as handle:
            handle.write(f"""base_url: https://elearning.fudan.edu.cn
auth:
  method: {method}
  uis_username: {USERNAME}
  cookie_file: {self.cookie_file}
root_dir: ./files
""")
        return load_config(self.cfg_path)

    def test_password_method_without_keyring_falls_back_to_cookie(self):
        """配置是 password 但钥匙串没密码：应回退到已有 cookie，并改写配置。"""
        _write_cookie(self.cookie_file)
        cfg = self._cfg("password")

        auth = build_auth(cfg)
        self.assertIsInstance(auth, CookieAuth)

        # 配置已被改写为 cookie，下次启动直接走 cookie
        with open(self.cfg_path, "r", encoding="utf-8") as handle:
            self.assertIn("method: cookie", handle.read())

    def test_no_cookie_and_no_password_gives_clear_error(self):
        """既无密码又无 cookie：提示重新登录，而不是“API Token 为空”。"""
        cfg = self._cfg("password")
        with self.assertRaises(AuthError) as ctx:
            build_auth(cfg)
        self.assertNotIn("API Token 为空", str(ctx.exception))
        self.assertIn("重新登录", str(ctx.exception))

    def test_remembered_password_relogins(self):
        """记住了密码：走静默重登，得到 cookie 认证。"""
        pl.load_password = lambda username: "secret"
        pl.login_with_stored_credentials = lambda base, user, cookie: (_write_cookie(cookie), None)[
            1
        ]
        cfg = self._cfg("password")
        self.assertIsInstance(build_auth(cfg), CookieAuth)


if __name__ == "__main__":
    unittest.main(verbosity=2)
