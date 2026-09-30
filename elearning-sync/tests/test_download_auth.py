"""下载器认证会话回归测试。

核心断言：
1. 文件内容下载必须复用带 Canvas 会话的 api_session（携带 Cookie），
   而不是裸 requests.get —— 否则会被重定向到 UIS 登录页（6328 字节 HTML），
   表现为 416 Range Not Satisfiable 或“大小不匹配: 实际 6328”。
2. 会话过期（被重定向到登录页）时不得把登录页 HTML 当成文件存盘。
3. 残留 .part 是登录页时，续传拼接出的坏文件必须被识别并清除。
4. 416 越界时丢弃残留 .part 重试，而不是陷入永久失败。
"""
from __future__ import annotations

import os
import sys
import tempfile
import unittest
from unittest.mock import MagicMock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from fudan_sync.downloader import (  # noqa: E402
    DownloadTask, Downloader, _is_auth_redirect, _is_html_target,
    _looks_like_auth_page,
)

AUTH_HTML = b"<!DOCTYPE html><html><body>UIS login page</body></html>"
FILE_BODY = b"PDF-1.7 real content " * 20
SIGNED_URL = "https://signed.example/files/1/download"


class FakeResponse:
    def __init__(self, status_code=200, url="", content=FILE_BODY,
                 content_length=None, headers=None):
        self.status_code = status_code
        self.url = url
        self._content = content
        self.headers = headers or {}
        if content_length is not None:
            self.headers["Content-Length"] = str(content_length)
        self.closed = False

    def raise_for_status(self):
        if 400 <= self.status_code:
            from requests import HTTPError
            raise HTTPError(f"{self.status_code} Client Error for url: {self.url}")

    def iter_content(self, chunk_size):
        data = self._content
        for i in range(0, len(data), chunk_size):
            yield data[i:i + chunk_size]

    def close(self):
        self.closed = True

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
        return False


class FakeApiSession:
    """模拟带 Cookie 的 CanvasAPI.session：区分元数据请求与文件下载请求。"""

    def __init__(self, download_resp_factory):
        self.download_resp_factory = download_resp_factory
        self.get_calls = []   # (url, kwargs)
        self.cookies = "session-cookie-present"

    def get(self, url, **kwargs):
        self.get_calls.append((url, kwargs))
        if "/api/v1" in url or url.endswith("/courses/1/files/1"):
            # 元数据请求：返回签名下载 URL
            return _JsonResponse({"url": SIGNED_URL})
        # 文件内容下载
        return self.download_resp_factory(url, kwargs)


class _JsonResponse:
    def __init__(self, payload):
        self._payload = payload
        self.status_code = 200
        self.headers = {}

    def json(self):
        return self._payload

    def raise_for_status(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def _make_task(dest_dir: str, size: int = len(FILE_BODY)) -> DownloadTask:
    return DownloadTask(
        file_id=1, course_id=1, filename="a.pdf", folder_path="",
        course_dir=dest_dir, size=size,
        api_path="/courses/1/files/1", fallback_url="https://invalid/a.pdf",
    )


class DownloadSessionTests(unittest.TestCase):
    """下载必须使用带 Cookie 的 api_session，并正确处理认证失败。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()

    def _make_downloader(self, session):
        return Downloader(api_session=session, api_base="https://elearning.test",
                          max_retries=2, logger=MagicMock())

    def test_download_uses_api_session_with_cookies(self):
        """文件内容下载必须走 api_session（携带 Cookie）。"""
        session = FakeApiSession(
            lambda url, kw: FakeResponse(200, url=SIGNED_URL, content=FILE_BODY,
                                         content_length=len(FILE_BODY)))
        downloader = self._make_downloader(session)
        result = downloader._download_one(_make_task(self.tmp))

        self.assertTrue(result.success, f"应下载成功，错误: {result.error}")
        download_calls = [c for c in session.get_calls if c[0] == SIGNED_URL]
        self.assertEqual(len(download_calls), 1, "必须通过 api_session 下载文件内容")
        # 第一次是换取下载地址的元数据请求，第二次才是文件内容下载
        self.assertIn("/courses/1/files/1", session.get_calls[0][0])
        self.assertTrue(all(c[1].get("stream") for c in download_calls),
                        "文件下载必须是流式请求")

    def test_bare_requests_not_used(self):
        """不得使用模块级 requests.get（不带 Cookie，会被重定向到登录页）。"""
        import requests as requests_mod
        original = requests_mod.get
        raised = []

        def tripwire(*args, **kwargs):
            raised.append(args)
            return FakeResponse(200, content=FILE_BODY)

        requests_mod.get = tripwire
        session = FakeApiSession(
            lambda url, kw: FakeResponse(200, url=SIGNED_URL, content=FILE_BODY,
                                         content_length=len(FILE_BODY)))
        try:
            downloader = self._make_downloader(session)
            result = downloader._download_one(_make_task(self.tmp))
        finally:
            requests_mod.get = original
        self.assertEqual(raised, [], "禁止使用模块级 requests.get 下载文件内容")
        self.assertTrue(result.success)

    def test_auth_redirect_not_saved_as_file(self):
        """会话过期被重定向到 UIS 登录页时，不得把登录页存成目标文件。"""
        session = FakeApiSession(
            lambda url, kw: FakeResponse(
                200, url="https://id.fudan.edu.cn/ac/#/index?lck=context_CAS_x",
                content=AUTH_HTML, content_length=len(AUTH_HTML)))
        downloader = self._make_downloader(session)

        task = _make_task(self.tmp)
        result = downloader._download_one(task)
        self.assertFalse(result.success, "登录页不得被判为成功")
        self.assertNotEqual(result.bytes, len(AUTH_HTML), "不得把登录页字节数当作下载量")
        self.assertFalse(os.path.exists(task.dest_path), "登录页不得落盘为目标文件")
        self.assertFalse(os.path.exists(f"{task.dest_path}.part"), "登录页不得残留为 .part")

    def test_stale_part_with_auth_page_is_purged(self):
        """残留 .part 是登录页、且服务器接受 Range 时，拼接出的坏文件必须被清除。"""
        task = _make_task(self.tmp)
        os.makedirs(os.path.dirname(task.dest_path), exist_ok=True)
        with open(f"{task.dest_path}.part", "wb") as handle:
            handle.write(AUTH_HTML)

        def factory(url, kw):
            # 服务器接受 Range，只返回后半段真实内容 -> 与登录页拼接受损
            start = kw.get("headers", {}).get("Range")
            if start and start.startswith("bytes="):
                tail = FILE_BODY[len(AUTH_HTML):]
                return FakeResponse(206, url=url, content=tail,
                                    content_length=len(tail))
            return FakeResponse(200, url=url, content=FILE_BODY,
                                content_length=len(FILE_BODY))

        session = FakeApiSession(factory)
        downloader = self._make_downloader(session)
        result = downloader._download_one(task)

        # 关键不变量：即使最终成功，落盘文件也绝不能以登录页 HTML 开头
        if os.path.exists(task.dest_path):
            with open(task.dest_path, "rb") as handle:
                head = handle.read(len(AUTH_HTML) + 16)
            self.assertFalse(head.startswith(b"<!DOCTYPE html"),
                             "目标文件不得以登录页 HTML 开头")
        if result.success:
            # 重试成功后残留 .part 应已被原子改名清掉
            self.assertFalse(os.path.exists(f"{task.dest_path}.part"),
                             "成功后 .part 必须已被改名清除")

    def test_416_resets_part_and_retries(self):
        """416 越界时应丢弃 .part 重试，而不是反复失败。"""
        task = _make_task(self.tmp)
        os.makedirs(os.path.dirname(task.dest_path), exist_ok=True)
        with open(f"{task.dest_path}.part", "wb") as handle:
            handle.write(b"\x00" * 999)  # 比 task.size 大，触发异常 .part

        calls = {"n": 0}

        def factory(url, kw):
            calls["n"] += 1
            if calls["n"] == 1:
                return FakeResponse(416, url=url)
            return FakeResponse(200, url=url, content=FILE_BODY,
                                content_length=len(FILE_BODY))

        session = FakeApiSession(factory)
        downloader = self._make_downloader(session)
        result = downloader._download_one(task)
        self.assertTrue(result.success, f"重试后应成功: {result.error}")
        self.assertGreaterEqual(calls["n"], 2, "416 后必须重试")


class AuthPageHelperTests(unittest.TestCase):
    def test_is_auth_redirect_detects_uis(self):
        self.assertTrue(_is_auth_redirect(
            FakeResponse(200, url="https://id.fudan.edu.cn/ac/#/index?lck=x")))
        self.assertFalse(_is_auth_redirect(
            FakeResponse(200, url="https://elearning.fudan.edu.cn/files/1/download")))

    def test_looks_like_auth_page(self):
        tmp = tempfile.mkdtemp()
        path = os.path.join(tmp, "x.bin")
        with open(path, "wb") as handle:
            handle.write(AUTH_HTML)
        self.assertTrue(_looks_like_auth_page(path))
        with open(path, "wb") as handle:
            handle.write(b"%PDF-1.7 ...")
        self.assertFalse(_looks_like_auth_page(path))

    def test_html_target_not_misflagged(self):
        task = _make_task(tempfile.mkdtemp())
        task.filename = "notes.html"
        self.assertTrue(_is_html_target(task), "HTML 文件不应被当作登录页异常")
        task.filename = "a.pdf"
        self.assertFalse(_is_html_target(task))


class AuthFailureCircuitBreakerTests(unittest.TestCase):
    """v1.2.2：401/403 与登录页都不得重试 5 次，且 401/登录页要熔断其余文件。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()

    def _make_downloader(self, session):
        return Downloader(api_session=session, api_base="https://elearning.test",
                          max_retries=5, logger=MagicMock())

    def test_403_is_not_retried_and_names_locked_files(self):
        calls = {"n": 0}

        def factory(url, kw):
            calls["n"] += 1
            return FakeResponse(403, url=url)

        session = FakeApiSession(factory)
        downloader = self._make_downloader(session)
        result = downloader._download_one(_make_task(self.tmp))
        self.assertFalse(result.success)
        self.assertEqual(calls["n"], 1, "403 不得重试")
        self.assertIn("403", result.error)
        self.assertIn("锁定", result.error, "403 的文案要点明教师锁定这一常见原因")

    def test_401_halts_and_skips_queued_files(self):
        def factory(url, kw):
            return FakeResponse(401, url=url)

        session = FakeApiSession(factory)
        downloader = self._make_downloader(session)
        first = downloader._download_one(_make_task(self.tmp, size=10))
        self.assertFalse(first.success)
        self.assertIn("会话已过期", first.error)

        another = _make_task(self.tmp, size=10)
        another.file_id = 2
        another.filename = "b.pdf"
        second = downloader._download_one(another)
        self.assertFalse(second.success)
        self.assertIn("已跳过", second.error, "熔断后排队文件应直接跳过，不再请求")

    def test_login_page_body_halts_immediately(self):
        """200 但正文是登录页（未走重定向）：旧实现重试 5 次，现在熔断。"""
        calls = {"n": 0}

        def factory(url, kw):
            calls["n"] += 1
            return FakeResponse(200, url=SIGNED_URL, content=AUTH_HTML,
                                content_length=len(AUTH_HTML))

        session = FakeApiSession(factory)
        downloader = self._make_downloader(session)
        result = downloader._download_one(_make_task(self.tmp))
        self.assertFalse(result.success)
        self.assertEqual(calls["n"], 1, "登录页正文出现一次即熔断，不得重试")
        self.assertIn("会话已过期", result.error)
        self.assertTrue(downloader._auth_failed.is_set(), "登录页正文必须熔断后续文件")


class LockedFileStateTests(unittest.TestCase):
    """v1.2.2：教师锁定的文件入库为 locked 状态（可见、带原因），不再整文件消失。"""

    def test_mark_locked_keeps_row_with_reason(self):
        import shutil as _shutil
        from fudan_sync.state import StateStore
        tmp = tempfile.mkdtemp()
        try:
            db = os.path.join(tmp, "state.db")
            store = StateStore(db)
            store.upsert_file({
                "file_id": 101, "course_id": 7, "filename": "答案.py",
                "display_name": "答案.py", "size": 1024, "content_type": "",
                "folder_id": None, "folder_path": "", "created_at": "",
                "updated_at": "", "modified_at": "", "locked_for_user": 1,
                "hidden": 0, "source": "files", "context": "",
                "local_path": os.path.join(tmp, "答案.py"), "extra": "",
            })
            store.mark_locked(101, "教师已锁定，暂不开放下载")
            rows = store.list_files_by_course(7)
            self.assertEqual(len(rows), 1, "锁定文件必须留在列表里")
            self.assertEqual(rows[0]["status"], "locked")
            self.assertIn("锁定", rows[0]["last_error"])
            store.close()
        finally:
            _shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
