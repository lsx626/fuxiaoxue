"""登录认证回退回归测试。

复现的真实 bug：配置里 auth.method 缺失时 load_config 会回退成 "token"，
此前只有静默登录处做了内存级回退，而主界面每次同步前会重读配置，
导致回退丢失、build_auth 抛 “API Token 为空”——表现为“登录成功但一点
同步就报没有 Token”。

运行：python elearning-sync/tests/test_auth_fallback.py
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
            {"cookies": [{"name": "_normandy_session", "value": "x", "domain": "", "path": "/"}]},
            handle,
        )


class AuthFallbackTests(unittest.TestCase):
    """build_auth 必须统一处理 method 缺失回退，并把结果持久化。"""

    def setUp(self):
        self._orig_has = pl.has_stored_password
        self._orig_load = pl.load_password
        self._orig_login = pl.login_with_stored_credentials

    def tearDown(self):
        pl.has_stored_password = self._orig_has
        pl.load_password = self._orig_load
        pl.login_with_stored_credentials = self._orig_login

    def _make_cfg(self, yaml_body: str):
        tmp = tempfile.mkdtemp()
        cfg_path = os.path.join(tmp, "config.yaml")
        with open(cfg_path, "w", encoding="utf-8") as handle:
            handle.write(yaml_body)
        cookie_file = os.path.join(tmp, "cookies.json")
        cfg = load_config(cfg_path)
        # 模拟钥匙串里存着密码、登录会话可直接建立
        pl.has_stored_password = lambda u: u == USERNAME
        pl.load_password = lambda u: "secret" if u == USERNAME else None
        pl.login_with_stored_credentials = lambda base, user, cookie: (_write_cookie(cookie), None)[
            1
        ]
        return cfg, cfg_path, cookie_file

    def test_missing_method_falls_back_to_password_and_persists(self):
        """老配置（只有 cookie_file、无 method）应回退到密码登录并写回配置。"""
        cfg, cfg_path, cookie_file = self._make_cfg(f"""base_url: https://elearning.fudan.edu.cn
auth:
  method_cookie_file: cookies.json
  uis_username: {USERNAME}
root_dir: ./files
""")
        self.assertEqual(cfg.auth_method, "token")  # load_config 的回退
        self.assertEqual(cfg.token, "")

        auth = build_auth(cfg)
        self.assertIsInstance(auth, CookieAuth)

        # 关键断言：配置文件里已被写回 method: password
        with open(cfg_path, "r", encoding="utf-8") as handle:
            persisted = handle.read()
        self.assertIn("method: password", persisted)

        # 模拟主界面“每次同步前重读配置”：重读后不再是 token，同步链路不会再报
        # “API Token 为空”
        cfg2 = load_config(cfg_path)
        self.assertEqual(cfg2.auth_method, "password")
        auth2 = build_auth(cfg2)
        self.assertIsInstance(auth2, CookieAuth)

    def test_empty_method_with_password_keyring(self):
        """method 显式为空时同样回退。"""
        cfg, _cfg_path, _cookie = self._make_cfg(f"""auth:
  method:
  uis_username: {USERNAME}
""")
        self.assertIsInstance(build_auth(cfg), CookieAuth)
        self.assertEqual(cfg.auth_method, "password")

    def test_no_credentials_raises_clear_error(self):
        """既无 token 又无保存的密码：应给出清晰提示，而不是“API Token 为空”。"""
        cfg, _cfg_path, _cookie = self._make_cfg("""auth:
  method: token
  uis_username: someone_else
""")
        pl.has_stored_password = lambda u: False
        with self.assertRaises(AuthError) as ctx:
            build_auth(cfg)
        self.assertNotIn("API Token 为空", str(ctx.exception))
        self.assertIn("尚未配置登录凭据", str(ctx.exception))

    def test_token_method_still_works(self):
        """正常配置 token 的用户不受影响。"""
        cfg, _cfg_path, _cookie = self._make_cfg("""auth:
  method: token
  token: abc123
""")
        from fudan_sync.auth import TokenAuth

        self.assertIsInstance(build_auth(cfg), TokenAuth)


if __name__ == "__main__":
    unittest.main(verbosity=2)
