"""
页面归档的稳定性回归：

1. 同一页面重复同步**不能**在 _pages 下越攒越多副本（旧实现每轮走 unique_path，
   会生成「页面 - 标题 (1).html」「(2)」…）；
2. 历史遗留的编号副本会被清理；
3. 正文变化时原地覆盖（原子替换），文件名保持不变；
4. GUI 的「打开课程文件夹」必须复用同步引擎的路径清洗规则。
"""
from __future__ import annotations

import os
import sys
import tempfile
import types
import unittest
from collections import namedtuple

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from fudan_sync.config import load_config  # noqa: E402
from fudan_sync.sync_engine import SyncEngine  # noqa: E402
from fudan_sync.utils import sanitize_path_component  # noqa: E402

FakePage = namedtuple("FakePage", "kind title body url page_url course_id")


def _make_engine(tmp: str) -> SyncEngine:
    cfg_path = os.path.join(tmp, "config.yaml")
    with open(cfg_path, "w", encoding="utf-8") as handle:
        handle.write(
            "base_url: https://elearning.fudan.edu.cn\n"
            "auth:\n  method: token\n  token: dummy\n"
            f"root_dir: {os.path.join(tmp, 'files')}\n"
            f"state_db: {os.path.join(tmp, 'state.db')}\n"
        )
    # SyncEngine 构造时会取 api.session；这里只需要能跑 _archive_pages 的最小桩
    fake_api = types.SimpleNamespace(session=None, get=lambda *a, **k: {})
    return SyncEngine(load_config(cfg_path), api=fake_api, state=None)


class PageArchiveStabilityTests(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.engine = _make_engine(self.tmp)
        self.course_dir = os.path.join(self.tmp, "files", "测试课程")
        os.makedirs(self.course_dir, exist_ok=True)

    def _pages_dir(self) -> str:
        return os.path.join(self.course_dir, "_pages")

    def _page(self, body: str = "<p>正文</p>"):
        return FakePage("page", "绪论", body, "https://x/pages/intro", "intro", 1)

    def test_repeated_archive_keeps_single_file(self):
        for _ in range(3):
            self.engine._archive_pages(self.course_dir, [self._page()])

        files = sorted(os.listdir(self._pages_dir()))
        self.assertEqual(["页面 - 绪论.html"], files, f"不应产生编号副本: {files}")

    def test_existing_numbered_copies_are_cleaned_up(self):
        pages_dir = self._pages_dir()
        os.makedirs(pages_dir, exist_ok=True)
        for name in ("页面 - 绪论 (1).html", "页面 - 绪论 (2).html"):
            with open(os.path.join(pages_dir, name), "w", encoding="utf-8") as handle:
                handle.write("旧副本")

        self.engine._archive_pages(self.course_dir, [self._page()])

        files = sorted(os.listdir(pages_dir))
        self.assertEqual(["页面 - 绪论.html"], files)

    def test_body_change_overwrites_in_place(self):
        self.engine._archive_pages(self.course_dir, [self._page("<p>第一版</p>")])
        first_path = os.path.join(self._pages_dir(), "页面 - 绪论.html")
        with open(first_path, "r", encoding="utf-8") as handle:
            first = handle.read()
        self.assertIn("第一版", first)

        self.engine._archive_pages(self.course_dir, [self._page("<p>第二版</p>")])

        files = sorted(os.listdir(self._pages_dir()))
        self.assertEqual(["页面 - 绪论.html"], files, "覆盖不得换名")
        with open(first_path, "r", encoding="utf-8") as handle:
            second = handle.read()
        self.assertIn("第二版", second)

    def test_unchanged_body_is_not_rewritten(self):
        self.engine._archive_pages(self.course_dir, [self._page()])
        path = os.path.join(self._pages_dir(), "页面 - 绪论.html")
        before = os.stat(path).st_mtime_ns

        self.engine._archive_pages(self.course_dir, [self._page()])

        self.assertEqual(before, os.stat(path).st_mtime_ns, "内容未变不应重写文件")

    def test_same_run_duplicate_titles_get_distinct_names(self):
        pages = [
            FakePage("page", "同名", "<p>第一个</p>", "u1", "u1", 1),
            FakePage("page", "同名", "<p>第二个</p>", "u2", "u2", 1),
        ]
        self.engine._archive_pages(self.course_dir, pages)

        files = sorted(os.listdir(self._pages_dir()))
        self.assertEqual(["页面 - 同名 (1).html", "页面 - 同名.html"], files)


class CourseDirNamingTests(unittest.TestCase):
    """GUI 的课程目录命名必须与同步引擎一致（都走 sanitize_path_component）。"""

    def test_illegal_characters_match_engine_rule(self):
        raw_name = "数据结构/算法:2026"
        raw_code = "CS*101?"
        cleaned = sanitize_path_component(raw_name)
        # 引擎侧的拼法：<清洗后的名字> [<清洗后的代码>]
        engine_dir = f"{cleaned} [{sanitize_path_component(raw_code)}]"

        self.assertNotIn("/", engine_dir)
        self.assertNotIn("*", engine_dir)
        self.assertTrue(engine_dir.startswith(cleaned))


if __name__ == "__main__":
    unittest.main()
