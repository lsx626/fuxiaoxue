"""端到端验证：老配置（无 auth.method）下，静默登录成功后重读配置再同步，
build_auth 不得抛 “API Token 为空”。"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import fudan_sync.password_login as pl  # noqa: E402
from fudan_sync.config import load_config  # noqa: E402
from PySide6.QtCore import QCoreApplication  # noqa: E402

USERNAME = "20300123456"


class LoginFlowTests(unittest.TestCase):
    def setUp(self):
        self._orig_has = pl.has_stored_password
        self._orig_load = pl.load_password
        self._orig_login = pl.login_with_stored_credentials
        pl.has_stored_password = lambda u: u == USERNAME
        pl.load_password = lambda u: "secret" if u == USERNAME else None
        self.tmp = tempfile.mkdtemp()
        self.cfg_path = os.path.join(self.tmp, "config.yaml")
        with open(self.cfg_path, "w", encoding="utf-8") as handle:
            handle.write(f"""base_url: https://elearning.fudan.edu.cn
auth:
  method_cookie_file: cookies.json
  uis_username: {USERNAME}
root_dir: ./files
state_db: ./st.db
""")
        pl.login_with_stored_credentials = self._fake_login

    def tearDown(self):
        pl.has_stored_password = self._orig_has
        pl.load_password = self._orig_load
        pl.login_with_stored_credentials = self._orig_login

    def _fake_login(self, base, user, cookie_file):
        with open(cookie_file, "w", encoding="utf-8") as handle:
            json.dump(
                {
                    "cookies": [
                        {"name": "_normandy_session", "value": "x", "domain": "", "path": "/"}
                    ]
                },
                handle,
            )
        return cookie_file, None

    def test_silent_login_then_sync(self):
        self.app = QCoreApplication.instance() or QCoreApplication(sys.argv)

        # 1) 静默登录（工作在子线程，此处直接跑其逻辑以保持确定性）
        cfg = load_config(self.cfg_path)
        self.assertEqual(cfg.auth_method, "token")  # load_config 回退
        self.assertEqual(cfg.token, "")

        from fudan_sync.auth import build_auth

        auth1 = build_auth(cfg)  # 静默登录内部调用
        self.assertTrue(auth1.name, "cookie")

        # 2) 主界面同步前会重读配置
        cfg2 = load_config(self.cfg_path)
        self.assertEqual(
            cfg2.auth_method, "password", "重读配置后应为 password，否则同步会报 Token 为空"
        )

        # 3) 同步链路再调 build_auth 不得抛“API Token 为空”
        auth2 = build_auth(cfg2)
        self.assertEqual(auth2.name, "cookie")

        # 4) 二次启动：配置已是 password，直接可用
        cfg3 = load_config(self.cfg_path)
        self.assertEqual(cfg3.auth_method, "password")
        self.assertEqual(build_auth(cfg3).name, "cookie")


if __name__ == "__main__":
    unittest.main(verbosity=2)
