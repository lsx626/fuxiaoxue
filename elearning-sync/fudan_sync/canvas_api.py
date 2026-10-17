"""Canvas LMS REST API 客户端：分页、动态限流、自动重试。

参考文档：
- Files API: https://canvas.instructure.com/doc/api/files.html
- 分页（Link 头）: https://canvas.instructure.com/doc/api/file.pagination.html
- 限流（429 + X-Rate-Limit-Remaining）: https://canvas.instructure.com/doc/api/file.throttling.html
"""

from __future__ import annotations

import time
from typing import Any, Dict, Iterator, List, Optional

import requests

from .auth import BaseAuth


class RateLimitError(RuntimeError):
    """超过 Canvas 限流且重试次数耗尽。"""


class CanvasAPI:
    """对 Canvas REST API 的薄封装。

    设计要点：
    - API 调用严格串行（Canvas 官方建议：单线程顺序请求几乎不会触发限流）；
      文件下载走独立的下载器并发。
    - 自动跟随 Link 头分页（兼容 page=N 与 bookmark 两种方案，因为链接被当作不透明值处理）。
    - 读取 X-Rate-Limit-Remaining 主动避让；收到 429 时按 Retry-After 退避重试。
    """

    def __init__(
        self,
        base_url: str,
        auth: BaseAuth,
        timeout: int = 60,
        max_retries: int = 5,
        rate_limit_floor: float = 15.0,
        min_request_interval: float = 0.15,
        logger=None,
    ):
        self.api_base = base_url.rstrip("/") + "/api/v1"
        self.timeout = timeout
        self.max_retries = max_retries
        self.rate_limit_floor = rate_limit_floor
        self.min_request_interval = min_request_interval
        self.log = logger
        self._last_request_ts = 0.0

        self.session = requests.Session()
        self.session.headers.update(
            {
                "Accept": "application/json",
                "User-Agent": "FuXiaoXue/1.0 (Canvas LMS sync tool)",
            }
        )
        auth.apply(self.session)

    # ------------------------------------------------------------------
    def _sleep_interval(self) -> None:
        """最小请求间隔，避免突发请求。"""
        elapsed = time.time() - self._last_request_ts
        if elapsed < self.min_request_interval:
            time.sleep(self.min_request_interval - elapsed)
        self._last_request_ts = time.time()

    def _log(self, level: str, msg: str, *args) -> None:
        if self.log is not None:
            getattr(self.log, level)(msg, *args)

    def request(
        self,
        method: str,
        url: str,
        *,
        params: Optional[Dict[str, Any]] = None,
        data: Any = None,
        json_body: Any = None,
        allow_redirects: bool = True,
    ) -> requests.Response:
        """带重试与限流处理的请求。"""
        attempt = 0
        while True:
            attempt += 1
            self._sleep_interval()
            try:
                response = self.session.request(
                    method,
                    url,
                    params=params,
                    data=data,
                    json=json_body,
                    timeout=self.timeout,
                    allow_redirects=allow_redirects,
                )
            except requests.RequestException as exc:
                if attempt > self.max_retries:
                    raise
                backoff = min(2**attempt, 30)
                self._log(
                    "warning",
                    "网络错误 %s（第 %d 次重试，%ds 后）: %s",
                    type(exc).__name__,
                    attempt,
                    backoff,
                    exc,
                )
                time.sleep(backoff)
                continue

            self._handle_rate_limit_headers(response)

            if response.status_code == 429 or (
                response.status_code == 403 and "Rate Limit Exceeded" in (response.text or "")
            ):
                if attempt > self.max_retries:
                    raise RateLimitError(f"Canvas 限流，重试 {self.max_retries} 次后仍失败: {url}")
                retry_after = self._parse_retry_after(response)
                self._log(
                    "warning",
                    "触发限流（429），等待 %.0fs 后重试（第 %d 次）",
                    retry_after,
                    attempt,
                )
                time.sleep(retry_after)
                continue

            if 500 <= response.status_code < 600:
                if attempt > self.max_retries:
                    response.raise_for_status()
                backoff = min(2**attempt, 30)
                self._log(
                    "warning",
                    "服务端错误 %d（第 %d 次重试，%ds 后）: %s",
                    response.status_code,
                    attempt,
                    backoff,
                    url,
                )
                time.sleep(backoff)
                continue

            if 400 <= response.status_code < 500 and response.status_code != 404:
                # 记录可读错误信息后抛出（404 由调用方按业务处理）
                self._log(
                    "error", "API 返回 %d: %s", response.status_code, (response.text or "")[:300]
                )
            response.raise_for_status()
            return response

    def _handle_rate_limit_headers(self, response: requests.Response) -> None:
        """根据剩余配额主动休眠，避免撞上 429。"""
        remaining = response.headers.get("X-Rate-Limit-Remaining")
        if remaining is None:
            return
        try:
            value = float(remaining)
        except (TypeError, ValueError):
            return
        if value < self.rate_limit_floor:
            sleep_for = min(max(2.0, 60.0 / max(value, 1e-6)), 30.0)
            self._log("debug", "剩余配额 %.0f，主动休眠 %.1fs", value, sleep_for)
            time.sleep(sleep_for)

    @staticmethod
    def _parse_retry_after(response: requests.Response) -> float:
        raw = response.headers.get("Retry-After")
        if raw:
            try:
                return max(float(raw), 1.0)
            except (TypeError, ValueError):
                pass
        return min(2**3, 30.0)

    # ------------------------------------------------------------------
    def get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Any:
        """GET 一个 API 路径，返回解析后的 JSON。"""
        url = self._abs(path)
        response = self.request("GET", url, params=params)
        if response.status_code == 204 or not response.content:
            return None
        return response.json()

    def iter_pages(
        self, path: str, params: Optional[Dict[str, Any]] = None, max_pages: Optional[int] = None
    ) -> Iterator[Any]:
        """迭代分页结果，自动跟随 Link 头的 rel=next。

        Link 头被当作不透明 URL 处理，因此兼容 page=N 与 page=bookmark:xxx 两种分页。
        """
        url: Optional[str] = self._abs(path)
        page = 0
        while url:
            page += 1
            response = self.request("GET", url, params=params if page == 1 else None)
            if not response.content:
                return
            payload = response.json()
            if isinstance(payload, list):
                if not payload:  # 空页，结束
                    return
                yield from payload
            else:
                yield payload
                return

            next_url = self._next_link(response)
            url = next_url
            params = None  # next 链接已含全部参数
            if max_pages and page >= max_pages:
                return

    @staticmethod
    def _next_link(response: requests.Response) -> Optional[str]:
        """从 Link 头解析 rel=next（头名大小写不敏感）。"""
        links = getattr(response, "links", None) or {}
        nxt = links.get("next")
        if isinstance(nxt, dict) and nxt.get("url"):
            return nxt["url"]
        # requests.links 未命中时，手动解析原始头（大小写不敏感）
        raw = response.headers.get("Link") or response.headers.get("link")
        if not raw:
            return None
        for part in raw.split(","):
            if 'rel="next"' in part or "rel=next" in part:
                start = part.find("<")
                end = part.find(">")
                if start != -1 and end != -1 and end > start:
                    return part[start + 1 : end]
        return None

    def _abs(self, path: str) -> str:
        if path.startswith("http://") or path.startswith("https://"):
            return path
        if path.startswith("/api/"):
            return self.api_base.rsplit("/api/v1", 1)[0] + path
        return self.api_base.rstrip("/") + "/" + path.lstrip("/")

    # ------------------------------------------------------------------
    # 便捷业务方法
    # ------------------------------------------------------------------
    def get_current_user(self) -> Dict[str, Any]:
        return self.get("/users/self") or {}

    def list_courses(
        self, enrollment_type: str = "student", only_favorites: bool = False
    ) -> List[Dict[str, Any]]:
        """列出当前用户可见课程（含学期信息）。"""
        courses: List[Dict[str, Any]] = []
        if only_favorites:
            for item in self.iter_pages("/users/self/favorites/courses", params={"per_page": 100}):
                item.setdefault("is_favorite", True)
                courses.append(item)
        else:
            params = {
                "per_page": 100,
                "include[]": "term,syllabus_body,public_description",
                "state[]": "available",
            }
            if enrollment_type:
                params["enrollment_type"] = enrollment_type
            for item in self.iter_pages("/courses", params=params):
                courses.append(item)
        return courses

    def get_course(self, course_id: int) -> Dict[str, Any]:
        return self.get(f"/courses/{course_id}", params={"include[]": "term,syllabus_body"}) or {}

    def list_favorite_ids(self) -> List[int]:
        return [int(c["id"]) for c in self.list_courses(only_favorites=True) if c.get("id")]
