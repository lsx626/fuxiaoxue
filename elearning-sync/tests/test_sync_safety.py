"""同步引擎数据安全回归测试。

核心断言：课程文件列表拉取失败时，绝不能把本地已下载的文件误判为
远端删除（只有完整列表成功后才会真正删除本地副本与记录）。
运行：python -m pytest elearning-sync/tests/test_sync_safety.py
    或 python elearning-sync/tests/test_sync_safety.py
"""

from __future__ import annotations

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from fudan_sync.config import AppConfig  # noqa: E402
from fudan_sync.crawler import CrawlResult, RemoteFile  # noqa: E402
from fudan_sync.downloader import (  # noqa: E402
    Downloader,
    DownloadResult,
    DownloadTask,
)
from fudan_sync.state import StateStore  # noqa: E402
from fudan_sync.sync_engine import CourseInfo, SyncEngine  # noqa: E402

FOLDER_ID = 77


class FakeAPI:
    """假 Canvas API：files 接口可按需失败 / 返回空 / 返回一个文件。"""

    def __init__(self, mode: str = "fail"):
        self.mode = mode  # fail | empty | one_file
        self.session = type("S", (), {})()

    def list_courses(self, **kwargs):
        return [{"id": 1, "name": "数学", "course_code": "MATH", "term": {"name": "2025"}}]

    def get_course(self, course_id):
        return {"syllabus_body": ""}

    def iter_pages(self, path, params=None, max_pages=None):
        if "/files" in path:
            if self.mode == "fail":
                raise RuntimeError("模拟 503 网络故障")
            if self.mode == "one_file":
                return iter(
                    [
                        {
                            "id": 10,
                            "display_name": "a.pdf",
                            "filename": "a.pdf",
                            "size": 100,
                            "folder_id": FOLDER_ID,
                            "updated_at": "t1",
                            "url": "https://invalid/a.pdf",
                        }
                    ]
                )
            return iter([])  # empty：文件确实已被远端删除
        if "/folders" in path:
            # 一个根目录 + 其下的"课件"子目录，使 folder_path 解析为 "课件"
            return iter(
                [
                    {"id": 1, "name": "course files", "parent_folder_id": None},
                    {"id": FOLDER_ID, "name": "课件", "parent_folder_id": 1},
                ]
            )
        return iter([])

    def get(self, path, params=None):
        return {}


def _make_env(prune: bool = False):
    tmp = tempfile.mkdtemp()
    cfg = AppConfig(root_dir=os.path.join(tmp, "files"), state_db=os.path.join(tmp, "st.db"))
    cfg.sync.prune = prune
    os.makedirs(cfg.root_dir, exist_ok=True)
    state = StateStore(cfg.state_db)
    state.upsert_course(1, "数学", "MATH", "2025", "student", False)
    local = os.path.join(cfg.root_dir, "数学 [MATH]", "课件", "a.pdf")
    os.makedirs(os.path.dirname(local), exist_ok=True)
    with open(local, "wb") as handle:
        handle.write(b"x" * 100)
    state.upsert_file(
        {
            "file_id": 10,
            "course_id": 1,
            "filename": "a.pdf",
            "size": 100,
            "updated_at": "t1",
            "local_path": local,
            "folder_path": "课件",
        }
    )
    state.mark_downloaded(10, local, 100)
    course = CourseInfo(1, "数学", "MATH", "2025", "student", False, {})
    return cfg, state, local, course


class SyncSafetyTests(unittest.TestCase):
    """拉取失败时保留本地文件；真正删除时正确检测；正常同步不误删。"""

    def test_crawl_failure_preserves_files(self):
        """场景 A：文件列表拉取失败 -> 不得标记远端删除，不得删本地文件。"""
        for prune in (False, True):
            cfg, state, local, course = _make_env(prune=prune)
            engine = SyncEngine(cfg, FakeAPI("fail"), state)
            stats = engine.sync_course(course)
            after = state.stats()
            state.close()
            self.assertEqual(after["files_missing"], 0, "不应标记为远端已删除")
            self.assertTrue(os.path.exists(local), "本地文件必须保留")
            self.assertEqual(stats.files_removed, 0)

    def test_real_removal_deletes_file_and_record(self):
        """场景 B：列表成功且远端确实无此文件 -> 清理副本与记录。"""
        cfg, state, local, course = _make_env(prune=False)
        engine = SyncEngine(cfg, FakeAPI("empty"), state)
        stats = engine.sync_course(course)
        after = state.stats()
        record = state.get_file(10)
        state.close()
        self.assertEqual(after["files_missing"], 0)
        self.assertIsNone(record)
        self.assertFalse(os.path.exists(local))
        self.assertEqual(stats.files_removed, 1)

    def test_legacy_prune_setting_does_not_change_cleanup(self):
        """场景 C：旧 prune 配置不再改变删除语义。"""
        cfg, state, local, course = _make_env(prune=True)
        engine = SyncEngine(cfg, FakeAPI("empty"), state)
        stats = engine.sync_course(course)
        state.close()
        self.assertEqual(stats.files_removed, 1)
        self.assertFalse(os.path.exists(local), "prune 开启时应删除本地文件")

    def test_normal_sync_keeps_files(self):
        """场景 D：远端文件仍在 -> 即使重下载失败，本地文件也不得被误删。"""
        for prune in (False, True):
            cfg, state, local, course = _make_env(prune=prune)
            engine = SyncEngine(cfg, FakeAPI("one_file"), state)

            def fail_downloads(tasks, on_done=None):
                results = []
                for task in tasks:
                    result = DownloadResult(task)
                    result.error = "模拟下载失败"
                    results.append(result)
                    if on_done:
                        on_done(result)
                return results

            engine.downloader.download_many = fail_downloads
            stats = engine.sync_course(course)
            after = state.stats()
            state.close()
            self.assertTrue(os.path.exists(local), "远端仍存在的文件不得被误删")
            self.assertEqual(stats.files_removed, 0)
            self.assertEqual(after["files_missing"], 0)

    def test_full_sync_upserts_new_file_before_download(self):
        """场景 E：全量同步也必须先把远端文件写入空状态库。"""
        with tempfile.TemporaryDirectory() as tmp:
            cfg = AppConfig(
                root_dir=os.path.join(tmp, "files"), state_db=os.path.join(tmp, "st.db")
            )
            os.makedirs(cfg.root_dir, exist_ok=True)
            state = StateStore(cfg.state_db)
            state.upsert_course(1, "数学", "MATH", "2025", "student", False)
            course = CourseInfo(1, "数学", "MATH", "2025", "student", False, {})
            engine = SyncEngine(cfg, FakeAPI("one_file"), state)

            # 本测试只关心调度下载前的状态写入，不发起任何网络请求。
            engine.downloader.download_many = lambda tasks, on_done=None: []
            try:
                engine.sync_course(course, full=True)
                record = state.get_file(10)
            finally:
                state.close()

            self.assertIsNotNone(record)
            self.assertEqual(record["course_id"], 1)
            self.assertEqual(record["filename"], "a.pdf")
            self.assertEqual(record["status"], "pending")

    def test_remote_paths_are_sanitized_and_contained(self):
        """恶意目录和 Windows 保留名必须被清洗，目标仍位于课程目录内。"""
        with tempfile.TemporaryDirectory() as tmp:
            cfg = AppConfig(
                root_dir=os.path.join(tmp, "files"), state_db=os.path.join(tmp, "st.db")
            )
            os.makedirs(cfg.root_dir, exist_ok=True)
            state = StateStore(cfg.state_db)
            state.upsert_course(1, "数学", "MATH", "2025", "student", False)
            course = CourseInfo(1, "数学", "MATH", "2025", "student", False, {})
            engine = SyncEngine(cfg, FakeAPI("empty"), state)
            result = CrawlResult(course_id=1, files_listed_ok=True)
            result.add_file(
                RemoteFile(
                    file_id=20,
                    course_id=1,
                    filename="CON.txt",
                    size=1,
                    folder_path="/../../C:\\secret/周次:<1>|",
                    updated_at="u1",
                    modified_at="m1",
                )
            )
            engine.crawler.crawl_course = lambda *args, **kwargs: result
            captured = []
            engine.downloader.download_many = (
                lambda tasks, on_done=None: captured.extend(tasks) or []
            )

            try:
                engine.sync_course(course)
                record = state.get_file(20)
            finally:
                state.close()

            self.assertEqual(len(captured), 1)
            target = captured[0].destination_path
            course_dir = engine.course_local_dir(course)
            self.assertEqual(
                os.path.normcase(
                    os.path.commonpath((os.path.realpath(course_dir), os.path.realpath(target)))
                ),
                os.path.normcase(os.path.realpath(course_dir)),
            )
            relative_parts = os.path.relpath(target, course_dir).split(os.sep)
            self.assertNotIn("..", relative_parts)
            self.assertEqual(os.path.basename(target), "_CON.txt")
            for part in relative_parts:
                self.assertFalse(any(char in part for char in '<>:"/\\|?*'))
            self.assertEqual(record["filename"], "CON.txt")
            self.assertEqual(record["local_path"], target)

    def test_untracked_disk_file_gets_unique_name(self):
        """磁盘已有但数据库未登记的同名文件不得被覆盖。"""
        with tempfile.TemporaryDirectory() as tmp:
            cfg = AppConfig(
                root_dir=os.path.join(tmp, "files"), state_db=os.path.join(tmp, "st.db")
            )
            os.makedirs(cfg.root_dir, exist_ok=True)
            state = StateStore(cfg.state_db)
            state.upsert_course(1, "数学", "MATH", "2025", "student", False)
            course = CourseInfo(1, "数学", "MATH", "2025", "student", False, {})
            engine = SyncEngine(cfg, FakeAPI("empty"), state)
            course_dir = engine.course_local_dir(course)
            os.makedirs(course_dir, exist_ok=True)
            existing = os.path.join(course_dir, "same.pdf")
            with open(existing, "wb") as handle:
                handle.write(b"original")

            result = CrawlResult(course_id=1, files_listed_ok=True)
            result.add_file(
                RemoteFile(
                    file_id=30,
                    course_id=1,
                    filename="same.pdf",
                    size=1,
                    updated_at="u1",
                    modified_at="m1",
                )
            )
            engine.crawler.crawl_course = lambda *args, **kwargs: result
            captured = []
            engine.downloader.download_many = (
                lambda tasks, on_done=None: captured.extend(tasks) or []
            )
            try:
                engine.sync_course(course)
                record = state.get_file(30)
            finally:
                state.close()

            self.assertEqual(os.path.basename(captured[0].destination_path), "same (1).pdf")
            self.assertEqual(record["local_path"], captured[0].destination_path)
            with open(existing, "rb") as handle:
                self.assertEqual(handle.read(), b"original")

    def test_duplicate_names_keep_stable_paths_across_runs(self):
        """数据库历史占用应让不同 file_id 稳定避让，并复用自己的路径。"""
        with tempfile.TemporaryDirectory() as tmp:
            cfg = AppConfig(
                root_dir=os.path.join(tmp, "files"), state_db=os.path.join(tmp, "st.db")
            )
            os.makedirs(cfg.root_dir, exist_ok=True)
            state = StateStore(cfg.state_db)
            state.upsert_course(1, "数学", "MATH", "2025", "student", False)
            course = CourseInfo(1, "数学", "MATH", "2025", "student", False, {})

            def make_result(order):
                result = CrawlResult(course_id=1, files_listed_ok=True)
                for file_id in order:
                    result.add_file(
                        RemoteFile(
                            file_id=file_id,
                            course_id=1,
                            filename="same.pdf",
                            size=1,
                            folder_path="课件",
                            updated_at="u1",
                            modified_at="m1",
                        )
                    )
                return result

            first_engine = SyncEngine(cfg, FakeAPI("empty"), state)
            first_engine.crawler.crawl_course = lambda *args, **kwargs: make_result((40, 41))
            first_tasks = []
            first_engine.downloader.download_many = (
                lambda tasks, on_done=None: first_tasks.extend(tasks) or []
            )
            first_engine.sync_course(course)
            first_paths = {file_id: state.get_file(file_id)["local_path"] for file_id in (40, 41)}
            self.assertNotEqual(first_paths[40], first_paths[41])
            self.assertEqual(os.path.basename(first_paths[40]), "same.pdf")
            self.assertEqual(os.path.basename(first_paths[41]), "same (1).pdf")

            for file_id, path in first_paths.items():
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "wb") as handle:
                    handle.write(b"x")
                state.mark_downloaded(file_id, path, 1)

            # 反转远端返回顺序；路径仍须按 file_id 复用，而不是重新抢占。
            second_engine = SyncEngine(cfg, FakeAPI("empty"), state)
            second_engine.crawler.crawl_course = lambda *args, **kwargs: make_result((41, 40))
            second_tasks = []
            second_engine.downloader.download_many = (
                lambda tasks, on_done=None: second_tasks.extend(tasks) or []
            )
            try:
                second_engine.sync_course(course)
                second_paths = {
                    file_id: state.get_file(file_id)["local_path"] for file_id in (40, 41)
                }
            finally:
                state.close()

            self.assertEqual(second_paths, first_paths)
            self.assertEqual(second_tasks, [])

    def test_changed_same_size_file_is_replaced(self):
        """同步引擎已调度的文件不能仅因目标大小相同而跳过下载。"""

        class FakeResponse:
            status_code = 200
            headers = {"Content-Length": "4"}

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc_value, traceback):
                return False

            def raise_for_status(self):
                return None

            def iter_content(self, chunk_size):
                del chunk_size
                yield b"new!"

        with tempfile.TemporaryDirectory() as tmp:
            destination = os.path.join(tmp, "same-size.bin")
            with open(destination, "wb") as handle:
                handle.write(b"old!")
            task = DownloadTask(
                file_id=50,
                course_id=1,
                filename="same-size.bin",
                folder_path="",
                course_directory=tmp,
                size=4,
                api_path="/courses/1/files/50",
                fallback_url="https://invalid/same-size.bin",
            )
            # 修复后文件内容下载必须走带会话的 api_session.get，
            # 这里用最小假会话验证“同大小文件仍被重新下载并替换”。
            fake_session = type("Session", (), {"get": lambda self, url, **kw: FakeResponse()})()
            downloader = Downloader(fake_session, "https://invalid")
            downloader._resolve_download_url = lambda download_task: download_task.fallback_url

            result = downloader._download_one(task)

            self.assertFalse(result.skipped)
            with open(destination, "rb") as handle:
                self.assertEqual(handle.read(), b"new!")

    def test_prune_preserves_legacy_path_outside_course(self):
        """旧数据库中的越界 local_path 不得在远端删除时被清理。"""
        with tempfile.TemporaryDirectory() as tmp:
            cfg = AppConfig(
                root_dir=os.path.join(tmp, "files"), state_db=os.path.join(tmp, "st.db")
            )
            cfg.sync.prune = True
            os.makedirs(cfg.root_dir, exist_ok=True)
            victim = os.path.join(tmp, "must-stay.txt")
            with open(victim, "wb") as handle:
                handle.write(b"user data")

            state = StateStore(cfg.state_db)
            state.upsert_course(1, "数学", "MATH", "2025", "student", False)
            state.upsert_file(
                {
                    "file_id": 60,
                    "course_id": 1,
                    "filename": "legacy.txt",
                    "size": 9,
                    "updated_at": "u1",
                    "local_path": victim,
                    "folder_path": "../../",
                }
            )
            state.mark_downloaded(60, victim, 9)
            course = CourseInfo(1, "数学", "MATH", "2025", "student", False, {})
            engine = SyncEngine(cfg, FakeAPI("empty"), state)

            try:
                engine.sync_course(course)
                record = state.get_file(60)
            finally:
                state.close()

            self.assertTrue(os.path.exists(victim))
            self.assertIsNone(record)


if __name__ == "__main__":
    unittest.main(verbosity=2)
