"""作业截止日期与同步变更摘要的回归测试（v1.1.0）。

覆盖：
1. Crawler 从 assignments 接口采集 due_at（之前两端都直接丢弃）；
2. StateStore 的 assignments 表（整课替换 + 按时间升序）；
3. sync_changes 变更记录（new/updated/removed）、保留窗口；
4. _build_ics 输出标准 iCalendar（CRLF 行尾、跳过非法时间）；
5. 同步引擎把变更写入 sync_changes（下载成功 + 远端删除路径）。

运行：python -m pytest elearning-sync/tests/test_deadlines_and_changes.py
"""
from __future__ import annotations

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from fudan_sync.crawler import Crawler, CrawlResult  # noqa: E402
from fudan_sync.state import StateStore  # noqa: E402
from fudan_sync.sync_engine import CourseInfo, SyncEngine  # noqa: E402
from fudan_sync.downloader import DownloadResult, DownloadTask  # noqa: E402
from fudan_sync.config import AppConfig  # noqa: E402


class FakeAssignmentsApi:
    """假 Canvas API：只提供 assignments 与 folders/files 最小行为。"""

    session = None

    def __init__(self, with_due: bool = True):
        self.with_due = with_due

    def iter_pages(self, path, params=None, max_pages=None):
        if "/assignments" in path:
            return iter([
                {"id": 1, "name": "作业一", "description": "见附件",
                 "due_at": "2026-10-05T23:59:00Z", "html_url": "https://x/a1"},
                {"id": 2, "name": "作业二", "description": "",
                 "due_at": "2026-10-01T08:00:00Z", "html_url": "https://x/a2"},
                # 无截止时间的作业也得能采集
                {"id": 3, "name": "作业三", "description": "", "html_url": "https://x/a3"},
            ])
        if "/files" in path:
            return iter([])
        if "/folders" in path:
            return iter([])
        return iter([])

    def get(self, path, params=None):
        return {}


class CrawlAssignmentsTests(unittest.TestCase):
    def test_due_at_is_collected(self):
        crawler = Crawler(FakeAssignmentsApi())
        result: CrawlResult = crawler.crawl_course(42, collect_pages=False)
        self.assertEqual(len(result.assignments), 3)
        by_name = {a.name: a for a in result.assignments}
        self.assertEqual(by_name["作业一"].due_at, "2026-10-05T23:59:00Z")
        self.assertEqual(by_name["作业二"].due_at, "2026-10-01T08:00:00Z")
        self.assertEqual(by_name["作业三"].due_at, "")  # 无截止时间不是错误
        self.assertEqual(by_name["作业一"].html_url, "https://x/a1")

    def test_assignment_source_failure_is_reported_not_silent(self):
        class FailingApi(FakeAssignmentsApi):
            def iter_pages(self, path, params=None, max_pages=None):
                if "/assignments" in path:
                    raise RuntimeError("模拟 502")
                return super().iter_pages(path, params, max_pages)

        crawler = Crawler(FailingApi())
        result = crawler.crawl_course(42, collect_pages=False)
        self.assertEqual(result.assignments, [])
        self.assertTrue(any("assignments" in error for error in result.errors))


class StoreAssignmentsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = StateStore(os.path.join(self.tmp.name, "state.db"))

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def test_upsert_and_list_sorted_by_due(self):
        self.store.upsert_course(1, "机器学习", "CS229", "2025秋", "student", False)
        self.store.upsert_assignments(1, [
            {"assignment_id": 1, "name": "作业一", "due_at": "2026-10-05T23:59:00Z",
             "html_url": "u1"},
            {"assignment_id": 2, "name": "作业二", "due_at": "2026-10-01T08:00:00Z",
             "html_url": "u2"},
        ])
        items = self.store.list_assignments()
        self.assertEqual([i["name"] for i in items], ["作业二", "作业一"])
        self.assertEqual(items[0]["course_name"], "机器学习")

    def test_upsert_replaces_per_course(self):
        self.store.upsert_course(1, "机器学习", "CS229", "2025秋", "student", False)
        self.store.upsert_assignments(1, [
            {"assignment_id": 1, "name": "作业一", "due_at": "2026-10-05T23:59:00Z"}])
        self.store.upsert_assignments(1, [
            {"assignment_id": 9, "name": "新作业", "due_at": "2026-11-01T00:00:00Z"}])
        items = self.store.list_assignments()
        self.assertEqual([i["name"] for i in items], ["新作业"])

    def test_du_without_due_excluded_from_list(self):
        self.store.upsert_course(1, "机器学习", "CS229", "2025秋", "student", False)
        self.store.upsert_assignments(1, [
            {"assignment_id": 1, "name": "作业一", "due_at": ""},
            {"assignment_id": 2, "name": "作业二", "due_at": "2026-10-01T08:00:00Z"}])
        items = self.store.list_assignments()
        self.assertEqual([i["name"] for i in items], ["作业二"])


class ChangeRecordTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = StateStore(os.path.join(self.tmp.name, "state.db"))

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def _seed_file(self, file_id: int, filename: str, status: str = "pending"):
        self.store.upsert_file({
            "file_id": file_id, "course_id": 1, "filename": filename,
            "display_name": filename, "size": 10, "content_type": "",
            "folder_id": None, "folder_path": "", "created_at": "t1",
            "updated_at": "t1", "modified_at": "t1", "locked_for_user": False,
            "hidden": False, "source": "files", "context": "", "local_path": "",
        })
        if status == "downloaded":
            self.store.mark_downloaded(file_id, f"/tmp/{filename}", 10)

    def test_record_and_recent(self):
        self.store.record_file_change(0, 10, 1, "a.pdf", "new", "2026-09-30T10:00:00Z")
        self.store.record_file_change(0, 11, 1, "b.pdf", "updated", "2026-09-30T11:00:00Z")
        items = self.store.recent_changes(limit=10)
        self.assertEqual(len(items), 2)
        self.assertEqual(items[0]["filename"], "b.pdf")  # 最新在前
        self.assertEqual(items[0]["change"], "updated")

    def test_trim_keeps_latest(self):
        for i in range(10):
            self.store.record_file_change(0, i, 1, f"f{i}.pdf", "new", "t")
        self.store.trim_changes(keep=3)
        self.assertEqual(len(self.store.recent_changes(limit=100)), 3)

    def test_removed_changes_recorded_before_marking(self):
        # 文件本轮未见且仍非 remote_missing -> 应记 removed
        self.store.upsert_course(1, "机器学习", "CS229", "2025秋", "student", False)
        self._seed_file(10, "gone.pdf", "downloaded")
        self.store.record_removed_changes(1, [999])
        changes = self.store.recent_changes(limit=10)
        self.assertEqual([c["change"] for c in changes], ["removed"])
        # 标记之后再查：不再重复记录
        self.store.mark_missing_files(1, [999])
        self.store.record_removed_changes(1, [999])
        self.assertEqual(len(self.store.recent_changes(limit=10)), 1)


class IcsExportTests(unittest.TestCase):
    def test_build_ics(self):
        from fudan_sync.gui.deadlines_dialog import _build_ics
        ics = _build_ics([
            {"id": 1, "name": "作业,一", "course_name": "机器;学习",
             "due_at": "2026-10-05T23:59:00Z"},
            {"id": 2, "name": "无时间作业", "course_name": "x", "due_at": ""},
        ])
        self.assertIn("BEGIN:VCALENDAR", ics)
        self.assertTrue(ics.endswith("\r\n"))  # 行尾必须是 CRLF
        self.assertIn("DTSTART:20261005T235900Z", ics)
        # 逗号/分号必须转义
        self.assertIn("SUMMARY:作业\\,一（机器\\;学习）", ics)
        # 无截止时间的作业不进入日历
        self.assertNotIn("无时间作业", ics)


class EngineChangeTests(unittest.TestCase):
    """下载成功与远端删除都会写入 sync_changes。"""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.cfg = AppConfig(root_dir=os.path.join(self.tmp.name, "files"),
                             state_db=os.path.join(self.tmp.name, "st.db"))
        os.makedirs(self.cfg.root_dir, exist_ok=True)
        self.state = StateStore(self.cfg.state_db)
        self.state.upsert_course(1, "机器学习", "CS229", "2025秋", "student", False)

    def tearDown(self):
        self.state.close()
        self.tmp.cleanup()

    def _engine(self):
        return SyncEngine(self.cfg, _NullApi(), self.state)

    def _downloaded(self, file_id, filename, content="x" * 10):
        local = os.path.join(self.cfg.root_dir, filename)
        with open(local, "w", encoding="utf-8") as handle:
            handle.write(content)
        task = DownloadTask(
            file_id=file_id, course_id=1, filename=filename, folder_path="",
            course_dir=self.cfg.root_dir, size=len(content),
            api_path=f"/courses/1/files/{file_id}", fallback_url="")
        result = DownloadResult(task)
        result.success = True
        result.local_path = local
        result.bytes = len(content)
        return result

    def _record(self, file_id, filename):
        self.state.upsert_file({
            "file_id": file_id, "course_id": 1, "filename": filename,
            "display_name": filename, "size": 10, "content_type": "",
            "folder_id": None, "folder_path": "", "created_at": "t1",
            "updated_at": "t1", "modified_at": "t1", "locked_for_user": False,
            "hidden": False, "source": "files", "context": "",
            "local_path": os.path.join(self.cfg.root_dir, filename),
        })

    def test_download_success_records_new(self):
        engine = self._engine()
        result = self._downloaded(100, "new.txt")
        self._record(100, "new.txt")  # 待下载状态 -> new
        engine._on_download_done(result, _Stats())
        changes = self.state.recent_changes(limit=5)
        self.assertEqual([c["change"] for c in changes], ["new"])

    def test_download_success_records_updated(self):
        engine = self._engine()
        self._record(200, "old.txt")
        self.state.mark_downloaded(200, "/tmp/old.txt", 10)  # 之前已下载过
        result = self._downloaded(200, "old.txt")
        engine._on_download_done(result, _Stats())
        changes = self.state.recent_changes(limit=5)
        self.assertEqual([c["change"] for c in changes], ["updated"])


class _NullApi:
    session = None


class _Stats:
    """SyncStats 的最小替身（只用到计数累加）。"""

    def __init__(self):
        self.files_downloaded = 0
        self.bytes_downloaded = 0
        self.files_failed = 0
        self.errors = 0


if __name__ == "__main__":
    unittest.main()
