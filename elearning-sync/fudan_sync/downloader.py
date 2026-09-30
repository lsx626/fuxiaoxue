"""文件下载器：流式下载、断点续传、并发、完整性校验。"""
from __future__ import annotations

import os
import shutil
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional

import requests

from .utils import format_size


class DownloadAuthError(RuntimeError):
    """下载请求被重定向到 UIS 登录页：Canvas 会话已过期。"""


def _is_auth_redirect(resp) -> bool:
    """响应是否被重定向到 UIS 统一认证页（会话过期的可靠特征）。"""
    return "id.fudan.edu.cn" in (getattr(resp, "url", "") or "")


@dataclass
class DownloadTask:
    file_id: int
    course_id: int
    filename: str
    folder_path: str
    course_dir: str        # 本地课程目录（绝对）
    size: int
    api_path: str          # 用于换取下载 URL 的 API 路径，如 /courses/123/files/456
    fallback_url: str = "" # 直接可用的下载 URL（可能已过期，备用）

    @property
    def dest_path(self) -> str:
        rel = os.path.join(self.folder_path, self.filename) if self.folder_path \
            else self.filename
        return os.path.join(self.course_dir, *rel.split("/")) if rel else self.course_dir


_HTML_EXTENSIONS = {".html", ".htm", ".xhtml"}


def _is_html_target(task) -> bool:
    """目标本身是 HTML 时不能把 HTML 开头当作异常。"""
    ext = os.path.splitext(task.filename)[1].lower()
    if ext in _HTML_EXTENSIONS:
        return True
    ctype = (task.content_type or "").lower() if hasattr(task, "content_type") else ""
    return ctype.startswith("text/html")


def _looks_like_auth_page(path: str) -> bool:
    """读取开头字节，判断是否被误存为登录页 HTML。"""
    try:
        with open(path, "rb") as handle:
            head = handle.read(64)
    except OSError:
        return False
    head = head.lstrip(b"\xef\xbb\xbf \t\r\n").lower()
    return head.startswith(b"<!doctype html") or head.startswith(b"<html")


class DownloadResult:
    def __init__(self, task: DownloadTask):
        self.task = task
        self.success = False
        self.local_path = ""
        self.bytes = 0
        self.error = ""
        self.skipped = False


class Downloader:
    """文件下载器。

    - API 元数据调用串行（走限流客户端）；文件数据下载并发。
    - 下载到 .part 临时文件，成功后原子改名。
    - 支持 HTTP Range 断点续传。
    """

    def __init__(self, api_session: requests.Session, api_base: str,
                 concurrency: int = 4, max_retries: int = 5,
                 chunk_size: int = 1 << 20, logger=None,
                 progress_callback: Optional[Callable[[DownloadTask, int, int], None]] = None):
        self.api_session = api_session      # 复用 CanvasAPI 的会话（含认证）
        self.api_base = api_base.rstrip("/")
        self.concurrency = max(1, concurrency)
        self.max_retries = max(1, max_retries)
        self.chunk_size = chunk_size
        self.log = logger
        self.progress_callback = progress_callback
        self._stop = threading.Event()
        self._total_bytes = 0
        self._lock = threading.Lock()

    def stop(self) -> None:
        self._stop.set()

    def is_stopped(self) -> bool:
        """供引擎在长流程中协作检查（不再让调用方读 `_stop` 私有字段）。"""
        return self._stop.is_set()

    @property
    def total_bytes(self) -> int:
        return self._total_bytes

    # ------------------------------------------------------------------
    def _resolve_download_url(self, task: DownloadTask) -> str:
        """优先用 API 换取新鲜的签名下载 URL，失败时回退到文件对象自带的 url。"""
        url = f"{self.api_base}{task.api_path}"
        try:
            resp = self.api_session.get(url, timeout=30,
                                        headers={"Accept": "application/json"})
            if resp.status_code == 200:
                data = resp.json()
                if isinstance(data, dict) and data.get("url"):
                    return data["url"]
        except (requests.RequestException, ValueError) as exc:
            if self.log:
                self.log.debug("换取下载 URL 失败 %s（将使用备用 URL）: %s",
                               task.api_path, exc)
        if task.fallback_url:
            return task.fallback_url
        raise RuntimeError(f"无法获取文件 {task.file_id} 的下载地址")

    def _download_one(self, task: DownloadTask) -> DownloadResult:
        result = DownloadResult(task)
        dest = task.dest_path
        os.makedirs(os.path.dirname(dest), exist_ok=True)

        tmp = f"{dest}.part"
        attempt = 0
        while attempt < self.max_retries and not self._stop.is_set():
            attempt += 1
            try:
                download_url = self._resolve_download_url(task)
            except Exception as exc:  # pylint: disable=broad-except
                result.error = f"获取下载地址失败: {exc}"
                if attempt >= self.max_retries:
                    return result
                time.sleep(min(2 ** attempt, 20))
                continue

            offset = 0
            # 覆盖会话默认的 Accept: application/json，文件下载要的是二进制流
            headers = {"Accept": "*/*"}
            mode = "wb"
            if os.path.exists(tmp):  # 断点续传
                offset = os.path.getsize(tmp)
                if task.size > 0 and offset >= task.size:
                    offset = 0  # 临时文件异常，重下
                if offset > 0:
                    headers["Range"] = f"bytes={offset}-"
                    mode = "ab"

            try:
                # 必须复用带 Canvas 会话的 api_session：模块级 requests.get
                # 不携带 Cookie，文件下载链接会把请求重定向到 UIS 登录页，
                # 拿回一段固定 6328 字节的 HTML 而不是文件内容。
                with self.api_session.get(download_url, stream=True, headers=headers,
                                          timeout=(15, 300), allow_redirects=True) as resp:
                    # 416：续传区间越界（多半是上次把登录页等错误响应写进了
                    # .part）。丢弃残留 .part 从零重试，否则会陷入永久 416。
                    if resp.status_code == 416:
                        resp.close()
                        if os.path.exists(tmp):
                            os.remove(tmp)
                        result.error = "续传区间无效，已重置临时文件并重试"
                        if attempt < self.max_retries and not self._stop.is_set():
                            time.sleep(min(2 ** attempt, 20))
                            continue
                        return result

                    # 会话过期：被重定向到 UIS 统一认证页，重试也只能拿到登录页。
                    if _is_auth_redirect(resp):
                        resp.close()
                        if os.path.exists(tmp):
                            os.remove(tmp)
                        raise DownloadAuthError("Canvas 会话已过期，请重新登录")

                    # 服务器忽略了 Range 请求 -> 重新完整写入
                    if offset > 0 and resp.status_code == 200:
                        offset, mode = 0, "wb"
                    resp.raise_for_status()

                    content_length = resp.headers.get("Content-Length")
                    expected = int(content_length) + offset if content_length else None

                    written = offset
                    with open(tmp, mode) as handle:
                        for chunk in resp.iter_content(self.chunk_size):
                            if self._stop.is_set():
                                result.error = "用户中断"
                                return result
                            if not chunk:
                                continue
                            handle.write(chunk)
                            written += len(chunk)
                            if self.progress_callback:
                                self.progress_callback(task, written,
                                                       expected or task.size or 0)
                    result.bytes = written
            except DownloadAuthError:
                # 会话过期：重试无意义，直接把明确的原因返回给上层
                result.error = "Canvas 会话已过期，请重新登录后重试"
                return result
            except (requests.RequestException, OSError) as exc:
                result.error = f"{type(exc).__name__}: {exc}"
                if attempt < self.max_retries and not self._stop.is_set():
                    wait = min(2 ** attempt, 20)
                    if self.log:
                        self.log.debug("下载失败，%ds 后重试（%d/%d）%s: %s",
                                       wait, attempt, self.max_retries,
                                       task.filename, exc)
                    time.sleep(wait)
                continue

            # 登录页误存防护：会话过期时可能把认证页 HTML 当文件存下；
            # 上一轮残留的 .part 续传也会让真实内容前面拼上一段登录页。
            if not _is_html_target(task) and _looks_like_auth_page(tmp):
                os.remove(tmp)
                result.error = "下载到的是登录页而非文件内容（会话可能已过期）"
                if attempt < self.max_retries and not self._stop.is_set():
                    time.sleep(min(2 ** attempt, 20))
                    continue
                return result

            # 完整性校验：大小须匹配（未知时放行）
            if task.size > 0 and result.bytes != task.size:
                result.error = (f"大小不匹配: 期望 {task.size} 实际 {result.bytes}")
                if attempt < self.max_retries:
                    if os.path.exists(tmp):
                        os.remove(tmp)
                    time.sleep(min(2 ** attempt, 20))
                    continue
                return result

            # 原子改名到目标路径：同一 file_id 的内容更新必须覆盖旧文件
            # （state.local_path 是该 file_id 的权威路径）；
            # 不同 file_id 的同名冲突由同步引擎在构建任务时改名避让。
            os.replace(tmp, dest)
            result.local_path = dest
            result.success = True
            result.error = ""
            with self._lock:
                self._total_bytes += result.bytes
            return result

        return result

    # ------------------------------------------------------------------
    def download_many(self, tasks: List[DownloadTask],
                      on_done: Optional[Callable[[DownloadResult], None]] = None) \
            -> List[DownloadResult]:
        results: List[DownloadResult] = []
        if not tasks:
            return results

        workers = min(self.concurrency, max(1, len(tasks)))
        if self.log:
            self.log.info("开始下载 %d 个文件（并发 %d）...", len(tasks), workers)

        with ThreadPoolExecutor(max_workers=workers) as pool:
            future_map = {pool.submit(self._download_one, t): t for t in tasks}
            for future in as_completed(future_map):
                task = future_map[future]
                try:
                    result = future.result()
                except Exception as exc:  # pylint: disable=broad-except
                    result = DownloadResult(task)
                    result.error = f"未预期错误: {exc}"
                results.append(result)
                if on_done:
                    on_done(result)
        return results


from .utils import free_space_gb  # noqa: F401  （统一实现，避免与 utils 重复）


def safe_rmtree(path: str) -> None:
    try:
        shutil.rmtree(path, ignore_errors=True)
    except OSError:
        pass
