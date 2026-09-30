"""课程内容爬虫：发现每门课程中的全部可下载文件。

覆盖范围（确保"所有页面的所有文件"）：
1. 课程"文件"工具的全部文件（GET /courses/:id/files，含所有子目录）
2. 目录树（GET /courses/:id/folders）—— 用于重建本地目录结构
3. 模块(Module)中的文件附件
4. 课程页面(Page)正文中引用的文件链接
5. 作业(Assignment)说明中引用的文件
6. 公告(Announcement)的附件与正文引用
7. 课程大纲(Syllabus)中引用的文件
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Set

from .canvas_api import CanvasAPI

# 匹配 Canvas HTML 中的文件引用，如
#   /courses/123/files/456/download?verifier=xxx
#   /courses/123/files/456/preview
#   /api/v1/courses/123/files/456
FILE_LINK_RE = re.compile(r"/(?:api/v1/)?courses/\d+/files/(\d+)(?:/|$)")
# 匹配 file_ref 或 files/:id 的其他常见写法
FILE_UUID_RE = re.compile(r"/files/(\d+)/preview")


@dataclass
class RemoteFile:
    file_id: int
    course_id: int
    filename: str            # 优先 display_name，回退 filename
    size: int = 0
    content_type: str = ""
    folder_id: Optional[int] = None
    folder_path: str = ""    # 相对课程根的路径（如 "课件/第1周"）
    created_at: Optional[str] = None
    updated_at: Optional[str] = None
    modified_at: Optional[str] = None
    url: str = ""
    locked_for_user: bool = False
    hidden: bool = False
    source: str = "files"    # 发现来源：files | module | page | assignment | announcement | syllabus
    context: str = ""        # 来源描述，如 "模块: 第1周 / 页面: 课件1"

    @property
    def key(self) -> str:
        return f"{self.course_id}:{self.file_id}"


@dataclass
class CoursePage:
    """课程页面/作业/公告的正文归档信息。"""
    course_id: int
    kind: str                # page | assignment | announcement | syllabus
    title: str
    url: str = ""
    body: str = ""
    page_url: str = ""       # Canvas page 的 url slug


@dataclass
class AssignmentInfo:
    """课程作业（带截止日期）。采集自 Canvas assignments 接口。

    Python 端与 Android 端的 `CanvasAssignment` 保持同一字段口径
    （id/name/due_at/html_url），两端待办视图共用同一套数据语义。
    """
    assignment_id: int
    course_id: int
    name: str
    due_at: str = ""         # Canvas 返回的 ISO8601，可能为空（无截止时间）
    html_url: str = ""


@dataclass
class CrawlResult:
    course_id: int
    files: Dict[int, RemoteFile] = field(default_factory=dict)
    pages: List[CoursePage] = field(default_factory=list)
    assignments: List[AssignmentInfo] = field(default_factory=list)
    errors: List[str] = field(default_factory=list)
    # 课程"文件"工具列表是否成功拉取。
    # 只有成功拉取时，"本轮未见的文件 = 远端已删除"这一推断才成立；
    # 拉取失败（网络抖动 / 限流 / 403）时不能据此删除本地文件。
    files_listed_ok: bool = False

    def add_file(self, remote: RemoteFile, context: str = "") -> None:
        existing = self.files.get(remote.file_id)
        if existing is None:
            if context:
                remote.context = context
            self.files[remote.file_id] = remote
        else:
            # 多处引用同一文件：保留首次路径信息，补充来源上下文
            existing.source = f"{existing.source}+{remote.source}"
            if context and not existing.context:
                existing.context = context


class Crawler:
    def __init__(self, api: CanvasAPI, logger=None):
        self.api = api
        self.log = logger

    def _log(self, level: str, msg: str, *args) -> None:
        if self.log is not None:
            getattr(self.log, level)(msg, *args)

    # ------------------------------------------------------------------
    def crawl_course(self, course_id: int, collect_pages: bool = True) -> CrawlResult:
        """完整爬取一门课程的所有文件。"""
        result = CrawlResult(course_id=course_id)
        self._log("info", "开始爬取课程 %d ...", course_id)

        # 1) 目录树（必须在文件之前，用于把 folder_id 映射为路径）
        folder_paths = self._crawl_folders(course_id, result)

        # 2) 课程文件（主力来源，覆盖文件工具中的全部文件）
        self._crawl_course_files(course_id, folder_paths, result)

        # 3) 模块中的文件
        self._crawl_modules(course_id, result)

        # 4) 页面 / 作业 / 公告 / 大纲 中引用的文件
        self._crawl_referenced_files(course_id, result, collect_pages)

        self._log("info", "课程 %d 爬取完成：发现 %d 个文件", course_id, len(result.files))
        return result

    # ------------------------------------------------------------------
    def _crawl_folders(self, course_id: int, result: CrawlResult) -> Dict[int, str]:
        """构建 folder_id -> 相对路径 的映射。"""
        folder_name: Dict[int, str] = {}
        parent: Dict[int, Optional[int]] = {}
        try:
            for folder in self.api.iter_pages(f"/courses/{course_id}/folders",
                                              params={"per_page": 100}):
                fid = folder.get("id")
                if fid is None:
                    continue
                folder_name[fid] = folder.get("name") or ""
                parent[fid] = folder.get("parent_folder_id")
        except Exception as exc:  # pylint: disable=broad-except
            self._log("warning", "课程 %d 目录树获取失败: %s", course_id, exc)
            result.errors.append(f"folders: {exc}")
            return {}

        def resolve(fid: int, seen: Optional[Set[int]] = None) -> str:
            seen = seen or set()
            if fid in seen or fid is None:
                return ""
            seen.add(fid)
            name = folder_name.get(fid, "")
            pid = parent.get(fid)
            if pid is None:  # 根目录（"course files"）
                return ""
            parent_path = resolve(pid, seen)
            return f"{parent_path}/{name}".strip("/") if parent_path else name

        return {fid: resolve(fid) for fid in folder_name}

    # ------------------------------------------------------------------
    def _crawl_course_files(self, course_id: int, folder_paths: Dict[int, str],
                            result: CrawlResult) -> None:
        try:
            for item in self.api.iter_pages(f"/courses/{course_id}/files",
                                            params={"per_page": 100}):
                self._add_file_entry(item, course_id, folder_paths, result, "files")
        except Exception as exc:  # pylint: disable=broad-except
            self._log("warning", "课程 %d 文件列表获取失败: %s（本次不判定远端删除）",
                      course_id, exc)
            result.errors.append(f"course_files: {exc}")
            return
        result.files_listed_ok = True

    def _add_file_entry(self, item: Dict, course_id: int,
                        folder_paths: Dict[int, str], result: CrawlResult,
                        source: str, context: str = "") -> None:
        file_id = item.get("id")
        if file_id is None:
            return
        remote = RemoteFile(
            file_id=int(file_id),
            course_id=course_id,
            filename=item.get("display_name") or item.get("filename") or f"file_{file_id}",
            size=int(item.get("size") or 0),
            content_type=item.get("content-type") or item.get("content_type") or "",
            folder_id=item.get("folder_id"),
            folder_path=folder_paths.get(item.get("folder_id"), ""),
            created_at=item.get("created_at"),
            updated_at=item.get("updated_at"),
            modified_at=item.get("modified_at") or item.get("updated_at"),
            url=item.get("url") or "",
            locked_for_user=bool(item.get("locked_for_user") or item.get("locked") or False),
            hidden=bool(item.get("hidden") or item.get("hidden_for_user") or False),
            source=source,
            context=context,
        )
        result.add_file(remote, context)

    # ------------------------------------------------------------------
    def _crawl_modules(self, course_id: int, result: CrawlResult) -> None:
        """模块中的 File 类型条目。"""
        try:
            for module in self.api.iter_pages(f"/courses/{course_id}/modules",
                                              params={"per_page": 100, "include[]": "items"}):
                module_name = module.get("name") or ""
                items = module.get("items") or []
                if not items and module.get("items_count"):
                    items = list(self.api.iter_pages(
                        f"/courses/{course_id}/modules/{module['id']}/items",
                        params={"per_page": 100}))
                for item in items:
                    if (item.get("type") or "").lower() != "file":
                        continue
                    content_id = item.get("content_id")
                    if not content_id:
                        continue
                    ctx = f"模块: {module_name} / {item.get('title') or ''}".strip(" /")
                    self._fetch_and_add(course_id, int(content_id), result, "module", ctx)
        except Exception as exc:  # pylint: disable=broad-except
            self._log("warning", "课程 %d 模块获取失败: %s", course_id, exc)
            result.errors.append(f"modules: {exc}")

    def _fetch_and_add(self, course_id: int, file_id: int, result: CrawlResult,
                       source: str, context: str) -> None:
        """对引用型文件，单独拉取元数据后加入结果（会自动去重）。"""
        if file_id in result.files:
            existing = result.files[file_id]
            existing.source = f"{existing.source}+{source}"
            if context and not existing.context:
                existing.context = context
            return
        try:
            item = self.api.get(f"/courses/{course_id}/files/{file_id}")
        except Exception as exc:  # pylint: disable=broad-except
            self._log("debug", "文件 %d 元数据获取失败: %s", file_id, exc)
            return
        if isinstance(item, dict) and item.get("id"):
            self._add_file_entry(item, course_id, {}, result, source, context)

    # ------------------------------------------------------------------
    def _crawl_referenced_files(self, course_id: int, result: CrawlResult,
                                collect_pages: bool) -> None:
        """页面/作业/公告/大纲：提取正文中的文件链接，并可选归档正文 HTML。"""
        self._crawl_pages(course_id, result, collect_pages)
        self._crawl_assignments(course_id, result, collect_pages)
        self._crawl_announcements(course_id, result, collect_pages)
        self._crawl_syllabus(course_id, result, collect_pages)

    def _collect_file_links(self, html: str) -> Iterable[int]:
        if not html:
            return []
        ids = {int(m) for m in FILE_LINK_RE.findall(html)}
        ids |= {int(m) for m in FILE_UUID_RE.findall(html)}
        return [i for i in ids if i > 0]

    def _add_referenced(self, course_id: int, html: str, result: CrawlResult,
                        source: str, context: str) -> None:
        for file_id in self._collect_file_links(html):
            self._fetch_and_add(course_id, file_id, result, source, context)

    def _crawl_pages(self, course_id: int, result: CrawlResult, collect: bool) -> None:
        try:
            for page in self.api.iter_pages(f"/courses/{course_id}/pages",
                                            params={"per_page": 100}):
                page_url = page.get("url") or page.get("page_url") or ""
                title = page.get("title") or page_url or "untitled"
                body = page.get("body") or ""
                if not body and page_url:  # 列表接口不含 body，按需拉详情
                    try:
                        detail = self.api.get(f"/courses/{course_id}/pages/{page_url}")
                        body = (detail or {}).get("body") or ""
                    except Exception:  # pylint: disable=broad-except
                        pass
                self._add_referenced(course_id, body, result, "page", f"页面: {title}")
                if collect and body:
                    result.pages.append(CoursePage(
                        course_id, "page", title, url=page_url, body=body, page_url=page_url))
        except Exception as exc:  # pylint: disable=broad-except
            self._log("warning", "课程 %d 页面列表获取失败: %s", course_id, exc)
            result.errors.append(f"pages: {exc}")

    def _crawl_assignments(self, course_id: int, result: CrawlResult, collect: bool) -> None:
        try:
            for assignment in self.api.iter_pages(f"/courses/{course_id}/assignments",
                                                  params={"per_page": 100}):
                title = assignment.get("name") or "untitled"
                body = assignment.get("description") or ""
                self._add_referenced(course_id, body, result, "assignment", f"作业: {title}")
                # 截止日期（可能为空）：这是待办视图的唯一权威来源，之前一直被丢弃
                result.assignments.append(AssignmentInfo(
                    assignment_id=int(assignment.get("id") or 0),
                    course_id=course_id, name=title,
                    due_at=str(assignment.get("due_at") or ""),
                    html_url=str(assignment.get("html_url") or "")))
                if collect and body:
                    result.pages.append(CoursePage(
                        course_id, "assignment", title,
                        url=str(assignment.get("html_url") or ""), body=body))
        except Exception as exc:  # pylint: disable=broad-except
            self._log("warning", "课程 %d 作业列表获取失败: %s", course_id, exc)
            result.errors.append(f"assignments: {exc}")

    def _crawl_announcements(self, course_id: int, result: CrawlResult, collect: bool) -> None:
        try:
            for ann in self.api.iter_pages(f"/courses/{course_id}/announcements",
                                           params={"per_page": 100, "include[]": "attachments"}):
                title = ann.get("title") or "untitled"
                body = ann.get("message") or ann.get("body") or ""
                self._add_referenced(course_id, body, result, "announcement", f"公告: {title}")
                # 附件（attachments 可能直接是文件对象）
                for attachment in ann.get("attachments") or []:
                    if isinstance(attachment, dict) and attachment.get("id"):
                        self._add_file_entry(
                            attachment, course_id, {}, result, "announcement",
                            f"公告附件: {title}")
                if collect and body:
                    result.pages.append(CoursePage(
                        course_id, "announcement", title,
                        url=str(ann.get("html_url") or ""), body=body))
        except Exception as exc:  # pylint: disable=broad-except
            self._log("warning", "课程 %d 公告列表获取失败: %s", course_id, exc)
            result.errors.append(f"announcements: {exc}")

    def _crawl_syllabus(self, course_id: int, result: CrawlResult, collect: bool) -> None:
        try:
            course = self.api.get_course(course_id)
            body = course.get("syllabus_body") or ""
            if body:
                self._add_referenced(course_id, body, result, "syllabus", "课程大纲")
                if collect:
                    result.pages.append(CoursePage(
                        course_id, "syllabus", "课程大纲", body=body))
        except Exception as exc:  # pylint: disable=broad-except
            self._log("debug", "课程 %d 大纲获取失败: %s", course_id, exc)
