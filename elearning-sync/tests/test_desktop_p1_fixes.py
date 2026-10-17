"""
桌面端 P1 修复的回归测试：

1. 状态库写事务在异常时必须回滚（否则会留下半成品数据）；
2. CLI `login --method cookie` 必须持久化 auth.method（否则后续 sync/daemon
   仍按旧方式认证而失败）；
3. 认证中间页缺少 CAS 回调地址时，错误文案不得携带响应正文（防凭据/上下文泄露）。
"""

from __future__ import annotations

import logging
import os
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import fudan_sync.password_login as pl  # noqa: E402
import sync as cli  # noqa: E402
import yaml  # noqa: E402
from fudan_sync.config import load_config  # noqa: E402
from fudan_sync.state import StateStore  # noqa: E402


class StateStoreRollbackTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.store = StateStore(os.path.join(self.tmp, "state.db"))
        self.store.upsert_course(1, "甲课程", "CS101", "2026春", "student", False)

    def tearDown(self):
        self.store.close()

    def test_exception_rolls_back_partial_writes(self):
        with self.assertRaises(sqlite3.Error):
            with self.store._write_lock_cursor() as cur:
                cur.execute("UPDATE courses SET name=? WHERE id=?", ("被改坏的名字", 1))
                # 第二条语句失败：整个事务必须回滚，第一句不能生效
                cur.execute("UPDATE courses SET non_existing_column=? WHERE id=?", (1, 1))

        self.assertEqual("甲课程", self._course_name())

    def test_successful_write_commits(self):
        with self.store._write_lock_cursor() as cur:
            cur.execute("UPDATE courses SET name=? WHERE id=?", ("新名字", 1))

        self.assertEqual("新名字", self._course_name())

    def _course_name(self) -> str:
        for course in self.store.list_courses():
            if course["id"] == 1:
                return course["name"]
        self.fail("课程 1 不存在")


class _FakeCanvasAPI:
    """替代真实网络：只用于验证 CLI 的配置持久化行为。"""

    def __init__(self, base_url, auth, logger=None):
        self.base_url = base_url
        self.auth = auth

    def get_current_user(self):
        return {"id": 1, "name": "测试用户"}


class CookieLoginPersistsMethodTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.cfg_path = os.path.join(self.tmp, "config.yaml")
        self.cookie_path = os.path.join(self.tmp, "cookies.json")
        with open(self.cfg_path, "w", encoding="utf-8") as handle:
            handle.write(
                "base_url: https://elearning.fudan.edu.cn\n"
                "auth:\n"
                "  method: token\n"
                "  token: dummy\n"
                "  method_cookie_file: cookies.json\n"
                "root_dir: ./files\n"
                "state_db: ./st.db\n"
            )
        # CookieAuth 只检查文件存在与 JSON 可读
        with open(self.cookie_path, "w", encoding="utf-8") as handle:
            handle.write('{"cookies": [{"name": "_normandy_session", "value": "x"}]}')

    def test_cookie_login_writes_auth_method_back(self):
        cfg = load_config(self.cfg_path)
        self.assertEqual("token", cfg.auth_method)

        original = cli.CanvasAPI
        cli.CanvasAPI = _FakeCanvasAPI
        try:
            args = type("Args", (), {"method": "cookie"})()
            code = cli.cmd_login(args, cfg, logging.getLogger("test"))
        finally:
            cli.CanvasAPI = original

        self.assertEqual(0, code)
        with open(self.cfg_path, "r", encoding="utf-8") as handle:
            data = yaml.safe_load(handle)
        self.assertEqual("cookie", data["auth"]["method"])

    def test_missing_cookie_file_fails_without_touching_config(self):
        os.remove(self.cookie_path)
        cfg = load_config(self.cfg_path)
        args = type("Args", (), {"method": "cookie"})()

        code = cli.cmd_login(args, cfg, logging.getLogger("test"))

        self.assertEqual(1, code)
        with open(self.cfg_path, "r", encoding="utf-8") as handle:
            data = yaml.safe_load(handle)
        self.assertEqual("token", data["auth"]["method"])


class AuthErrorMessageSafetyTests(unittest.TestCase):
    def test_missing_ticket_message_has_no_response_body(self):
        secret_body = (
            "<html><script>locationValue = 'https://elearning.fudan.edu.cn/login/cas"
            "?ticket=ST-secret-ticket'</script>cookie=_normandy_session=abc</html>"
        )
        message = pl._missing_ticket_message(200, secret_body)

        self.assertIn("HTTP 200", message)
        self.assertIn(str(len(secret_body)), message)
        # 正文片段、跳转地址与 Cookie 值都不得出现在错误文案里
        for leaked in ("locationValue", "ticket=", "_normandy_session", "<html", "<script"):
            self.assertNotIn(leaked, message)


class ExcludeExtensionNormalizationTests(unittest.TestCase):
    """
    排除扩展名必须归一化成引擎实际比较的形式。

    GUI 历史配置里存的是 `exe`，而引擎用 `os.path.splitext()` 得到 `.exe` 去比较，
    导致「排除安装包」设置静默失效；CLI 手写的 `.ZIP`、带空格的写法也要能兼容。
    """

    def _load(self, raw_exts):
        tmp = tempfile.mkdtemp()
        cfg_path = os.path.join(tmp, "config.yaml")
        with open(cfg_path, "w", encoding="utf-8") as handle:
            handle.write(
                "base_url: https://elearning.fudan.edu.cn\n"
                "auth:\n"
                "  method: token\n"
                "  token: dummy\n"
                "root_dir: ./files\n"
                "state_db: ./st.db\n"
                "sync:\n"
                "  download:\n"
                f"    exclude_extensions: {raw_exts}\n"
            )
        return load_config(cfg_path)

    def test_gui_style_and_cli_style_are_both_normalized(self):
        cfg = self._load('["exe", ".ZIP", " pdf ", ""]')
        self.assertEqual([".exe", ".zip", ".pdf"], cfg.sync.download.exclude_extensions)

    def test_normalized_values_match_engine_comparison(self):
        cfg = self._load('["exe", "msi"]')
        for name in ("setup.exe", "installer.msi"):
            self.assertIn(
                os.path.splitext(name)[1].lower(),
                cfg.sync.download.exclude_extensions,
                f"{name} 应命中排除规则",
            )


if __name__ == "__main__":
    unittest.main()
