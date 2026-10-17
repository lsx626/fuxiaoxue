"""同步引擎：编排课程发现 -> 爬取 -> 增量比对 -> 下载 -> 页面归档。"""

from __future__ import annotations

import hashlib
import html as html_lib
import os
import re
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Set, Tuple

from .config import AppConfig
from .crawler import Crawler, CrawlResult, RemoteFile
from .downloader import Downloader, DownloadResult, DownloadTask
from .state import StateStore
from .utils import format_size, free_space_gb, is_installer_file, now_utc, sanitize_path_component

_PAGES_SUBDIR = "_pages"
_IMG_SRC_RE = re.compile(r'src="(?!https?://|/)([^"]+)"')


@dataclass
class CourseInfo:
    id: int
    name: str
    code: str
    term: str
    enrollment_type: str
    is_favorite: bool
    raw: Dict


@dataclass
class SyncStats:
    started_at: str = field(default_factory=now_utc)
    mode: str = "incremental"
    courses: int = 0
    files_found: int = 0
    files_downloaded: int = 0
    files_skipped: int = 0
    files_locked: int = 0
    files_failed: int = 0
    files_removed: int = 0
    bytes_downloaded: int = 0
    pages_archived: int = 0
    errors: int = 0
    notes: List[str] = field(default_factory=list)

    def add(self, other: "SyncStats") -> None:
        for field_name in other.__dataclass_fields__:
            value = getattr(other, field_name)
            if field_name in ("started_at", "mode"):
                continue
            if field_name == "notes":
                self.notes.extend(other.notes)
                continue
            if isinstance(value, (int, float)):
                setattr(self, field_name, getattr(self, field_name) + value)


class SyncEngine:
    def __init__(
        self,
        config: AppConfig,
        api,
        state: StateStore,
        logger=None,
        progress_cb: Optional[Callable[[str, Dict], None]] = None,
    ):
        self.config = config
        self.api = api
        self.state = state
        self.log = logger
        self.progress_cb = progress_cb
        self.crawler = Crawler(api, logger=logger)
        self.downloader = Downloader(
            api_session=api.session,
            api_base=config.api_base,
            concurrency=config.sync.download.concurrency,
            max_retries=config.sync.download.max_retries,
            logger=logger,
        )

    def stop(self) -> None:
        """请求停止同步（给 GUI 的“停止”按钮用）。"""
        self.downloader.stop()

    def _emit(self, kind: str, payload: Dict) -> None:
        if self.progress_cb is not None:
            try:
                self.progress_cb(kind, payload)
            except Exception:  # pylint: disable=broad-except
                # 进度回调失败不能影响同步本身
                pass

    def _log(self, level: str, msg: str, *args) -> None:
        if self.log is not None:
            getattr(self.log, level)(msg, *args)

    # ------------------------------------------------------------------
    def course_local_dir(self, course: CourseInfo) -> str:
        name = sanitize_path_component(course.name or f"course_{course.id}")
        if course.code:
            code = sanitize_path_component(course.code)
            name = f"{name} [{code}]"
        return os.path.join(self.config.root_dir, name)

    @staticmethod
    def _path_key(path: str) -> str:
        """返回适合本地路径占用表的大小写不敏感键。"""
        return os.path.normcase(os.path.abspath(path)).casefold()

    @staticmethod
    def _is_within_course(course_directory: str, path: str, allow_course_dir: bool = False) -> bool:
        """使用真实路径判断 path 是否仍位于课程目录内。"""
        try:
            course_real = os.path.realpath(os.path.abspath(course_directory))
            path_real = os.path.realpath(os.path.abspath(path))
            common = os.path.commonpath((course_real, path_real))
        except (OSError, ValueError):
            return False
        same = os.path.normcase(common) == os.path.normcase(course_real)
        if not same:
            return False
        if allow_course_dir:
            return True
        return os.path.normcase(path_real) != os.path.normcase(course_real)

    @staticmethod
    def _safe_folder_path(folder_path: str) -> str:
        """逐组件清洗 Canvas 目录路径，并始终返回相对 POSIX 风格路径。"""
        components = []
        for component in str(folder_path or "").split("/"):
            if not component:
                continue
            components.append(sanitize_path_component(component, fallback="folder"))
        return "/".join(components)

    def _existing_path_owners(self, course_id: int, course_directory: str) -> Dict[str, Set[int]]:
        """读取历史路径占用，忽略不在当前课程目录中的旧/异常记录。"""
        owners: Dict[str, Set[int]] = {}
        for stored in self.state.list_files_by_course(course_id):
            local_path = stored.get("local_path")
            if not local_path or not self._is_within_course(course_directory, local_path):
                continue
            key = self._path_key(local_path)
            owners.setdefault(key, set()).add(int(stored["file_id"]))
        return owners

    def _allocate_local_path(
        self, course_directory: str, remote: RemoteFile, owners: Dict[str, Set[int]]
    ) -> Tuple[str, str, str]:
        """为远端文件分配安全、稳定且不会覆盖其他文件的本地路径。"""
        safe_folder = self._safe_folder_path(remote.folder_path)
        safe_filename = sanitize_path_component(remote.filename, fallback=f"file_{remote.file_id}")
        parent = (
            os.path.join(course_directory, *safe_folder.split("/"))
            if safe_folder
            else course_directory
        )
        if not self._is_within_course(course_directory, parent, allow_course_dir=True):
            raise RuntimeError(f"文件 {remote.file_id} 的目标目录越过课程目录")

        stem, ext = os.path.splitext(safe_filename)
        for index in range(10000):
            local_filename = safe_filename if index == 0 else f"{stem} ({index}){ext}"
            candidate = os.path.join(parent, local_filename)

            # 已存在的符号链接可能把 realpath 指向课程目录之外；该名称视为占用，
            # 后续带序号的候选仍可正常尝试。
            if not self._is_within_course(course_directory, candidate):
                continue

            key = self._path_key(candidate)
            path_owners = owners.get(key, set())
            owned_by_other = bool(path_owners - {remote.file_id})
            own_record = remote.file_id in path_owners
            disk_occupied = os.path.lexists(candidate) or os.path.lexists(f"{candidate}.part")
            if owned_by_other or (disk_occupied and not own_record):
                continue

            owners.setdefault(key, set()).add(remote.file_id)
            if index:
                self._log(
                    "info", "文件 %s 的目标路径已占用，改名为 %s", remote.filename, local_filename
                )
            return safe_folder, local_filename, candidate

        raise RuntimeError(f"无法为文件 {remote.file_id} 分配安全的本地路径")

    def discover_courses(self) -> List[CourseInfo]:
        sync_config = self.config.sync
        raw_courses = self.api.list_courses(
            enrollment_type=sync_config.enrollment_type,
            only_favorites=sync_config.only_favorites,
        )
        favorite_ids = set()
        if sync_config.only_favorites:
            favorite_ids = set(self.api.list_favorite_ids())

        courses: List[CourseInfo] = []
        for raw in raw_courses:
            course_id = int(raw.get("id"))
            term = (
                ((raw.get("term") or {}).get("name"))
                if isinstance(raw.get("term"), dict)
                else (raw.get("term") or "")
            )
            info = CourseInfo(
                id=course_id,
                name=raw.get("name") or raw.get("course_code") or f"课程{course_id}",
                code=raw.get("course_code") or raw.get("sis_course_id") or "",
                term=term or "",
                enrollment_type=sync_config.enrollment_type,
                is_favorite=course_id in favorite_ids,
                raw=raw,
            )

            if sync_config.include_courses and course_id not in sync_config.include_courses:
                continue
            if course_id in sync_config.exclude_courses:
                continue
            if sync_config.include_terms and not any(
                t in info.term for t in sync_config.include_terms
            ):
                continue
            courses.append(info)

        # 持久化课程清单
        for info in courses:
            self.state.upsert_course(
                info.id, info.name, info.code, info.term, info.enrollment_type, info.is_favorite
            )
        self._log(
            "info",
            "发现 %d 门课程%s",
            len(courses),
            "（仅收藏）" if sync_config.only_favorites else "",
        )
        return courses

    # ------------------------------------------------------------------
    def run(self, full: bool = False, course_ids: Optional[List[int]] = None) -> SyncStats:
        overall = SyncStats(mode="full" if full else "incremental")

        courses = self.discover_courses()
        if course_ids:
            courses = [c for c in courses if c.id in set(course_ids)]
        overall.courses = len(courses)

        for idx, course in enumerate(courses, start=1):
            if self.downloader.is_stopped():
                self._log("warning", "收到中断信号，停止后续同步")
                break
            self._emit(
                "course_start",
                {
                    "id": course.id,
                    "name": course.name,
                    "code": course.code,
                    "term": course.term,
                    "index": idx,
                    "total": len(courses),
                },
            )
            try:
                stats = self.sync_course(course, full=full)
                overall.add(stats)
                self.state.mark_course_synced(course.id)
                self._emit(
                    "course_done",
                    {
                        "id": course.id,
                        "name": course.name,
                        "files_found": stats.files_found,
                        "files_downloaded": stats.files_downloaded,
                        "bytes_downloaded": stats.bytes_downloaded,
                        "pages_archived": stats.pages_archived,
                    },
                )
            except Exception as exc:  # pylint: disable=broad-except
                overall.errors += 1
                overall.notes.append(f"课程 {course.name}: {exc}")
                self._log("error", "同步课程 %s 失败: %s", course.name, exc)

        self.state.record_run(
            started_at=overall.started_at,
            mode=overall.mode,
            courses=overall.courses,
            files_found=overall.files_found,
            files_downloaded=overall.files_downloaded,
            bytes_downloaded=overall.bytes_downloaded,
            files_failed=overall.files_failed,
            files_removed=overall.files_removed,
            errors=overall.errors,
            note="; ".join(overall.notes)[:500],
        )
        self._backfill_search_index()
        self.state.trim_changes(500)
        self._summarize(overall)
        return overall

    def _summarize(self, stats: SyncStats) -> None:
        self._log(
            "info",
            "同步完成 [%s]: 课程 %d | 发现文件 %d | 新增/更新 %d（%s）"
            " | 跳过 %d | 锁定 %d | 失败 %d | 远端移除 %d | 页面归档 %d",
            stats.mode,
            stats.courses,
            stats.files_found,
            stats.files_downloaded,
            format_size(stats.bytes_downloaded),
            stats.files_skipped,
            stats.files_locked,
            stats.files_failed,
            stats.files_removed,
            stats.pages_archived,
        )
        if stats.notes:
            for note in stats.notes[:10]:
                self._log("warning", "备注: %s", note)

    # ------------------------------------------------------------------
    def sync_course(self, course: CourseInfo, full: bool = False) -> SyncStats:
        stats = SyncStats(mode="full" if full else "incremental")
        course_state = next(
            (item for item in self.state.list_courses() if int(item.get("id")) == course.id),
            {},
        )
        automatic_download = bool(course_state.get("selected", 1))
        course_directory = self.course_local_dir(course)
        os.makedirs(course_directory, exist_ok=True)

        result: CrawlResult = self.crawler.crawl_course(
            course.id, collect_pages=self.config.sync.archive_pages
        )
        stats.files_found = len(result.files)
        seen_file_ids = [r.file_id for r in result.files.values()]

        self._mark_remote_removed(course, result, stats, seen_file_ids=seen_file_ids)
        self._store_assignments(course, result.assignments)

        if self.config.sync.skip_empty_courses and not result.files and not result.pages:
            if os.path.isdir(course_directory) and not os.listdir(course_directory):
                os.rmdir(course_directory)
            self._log("debug", "课程 [%s] 无任何文件与页面，跳过", course.name)
            return stats

        tasks: List[DownloadTask] = []
        path_owners = self._existing_path_owners(course.id, course_directory)
        for remote in result.files.values():
            skip_reason = self._should_skip(remote)
            if skip_reason:
                stats.files_skipped += 1
                self._log("debug", "跳过文件 %s（%s）", remote.filename, skip_reason)
                continue

            rel_folder, local_filename, base_path = self._allocate_local_path(
                course_directory, remote, path_owners
            )

            record = {
                "file_id": remote.file_id,
                "course_id": remote.course_id,
                "filename": remote.filename,
                "display_name": remote.filename,
                "size": remote.size,
                "content_type": remote.content_type,
                "folder_id": remote.folder_id,
                "folder_path": rel_folder,
                "created_at": remote.created_at,
                "updated_at": remote.updated_at,
                "modified_at": remote.modified_at,
                "locked_for_user": remote.locked_for_user,
                "hidden": remote.hidden,
                "source": remote.source,
                "context": remote.context,
                "local_path": base_path,
                "extra": "",
            }
            state_needs_download = self.state.upsert_file(record)
            if remote.locked_for_user:
                self.state.mark_locked(remote.file_id, "教师已锁定，暂不开放下载")
                stats.files_locked += 1
                self._log("info", "文件 [%s] 已被教师锁定，跳过下载", remote.filename)
                continue
            stored = self.state.get_file(remote.file_id) or {}
            manually_selected = bool(stored.get("selected", 0))
            needs = (full or state_needs_download) and (automatic_download or manually_selected)
            if needs:
                tasks.append(
                    DownloadTask(
                        file_id=remote.file_id,
                        course_id=remote.course_id,
                        filename=local_filename,
                        folder_path=rel_folder,
                        course_directory=course_directory,
                        size=remote.size,
                        api_path=f"/courses/{remote.course_id}/files/{remote.file_id}",
                        fallback_url=remote.url,
                    )
                )

        self._require_disk_space(tasks)

        if tasks:
            self._log(
                "info",
                "课程 [%s] 需下载 %d 个文件（%s）",
                course.name,
                len(tasks),
                format_size(sum(t.size for t in tasks)),
            )
            self._emit(
                "course_download_start",
                {
                    "id": course.id,
                    "name": course.name,
                    "pending": len(tasks),
                    "bytes": sum(t.size for t in tasks),
                },
            )
            download_results = self.downloader.download_many(
                tasks,
                on_done=lambda r: self._on_download_done(r, stats),
            )
            self._apply_download_results(download_results, stats)
        else:
            self._log(
                "info",
                "课程 [%s] 无新增/变更文件（共 %d 个文件已是最新）",
                course.name,
                stats.files_found,
            )

        if self.config.sync.archive_pages and result.pages:
            stats.pages_archived = self._archive_pages(course_directory, result.pages)

        return stats

    def _store_assignments(self, course: CourseInfo, assignments) -> None:
        if not assignments:
            return
        records = [
            {
                "assignment_id": assignment.assignment_id,
                "name": assignment.name,
                "due_at": assignment.due_at,
                "html_url": assignment.html_url,
            }
            for assignment in assignments
        ]
        try:
            self.state.upsert_assignments(course.id, records)
        except Exception as exc:  # pylint: disable=broad-except
            self._log("debug", "作业截止日期落库失败 %s: %s", course.name, exc)

    def _require_disk_space(self, tasks: List[DownloadTask]) -> None:
        if not tasks:
            return
        needed_gb = sum(task.size for task in tasks) / (1024**3)
        free_gb = free_space_gb(self.config.root_dir)
        if (
            free_gb != float("inf")
            and free_gb - needed_gb < self.config.sync.download.min_free_space_gb
        ):
            raise RuntimeError(f"磁盘空间不足：需要 {needed_gb:.2f}GB，可用 {free_gb:.2f}GB")

    def _apply_download_results(
        self, download_results: List[DownloadResult], stats: SyncStats
    ) -> None:
        for download_result in download_results:
            if not download_result.success:
                stats.files_failed += 1
                stats.errors += 1
                self.state.mark_failed(download_result.task.file_id, download_result.error)
                continue
            if not download_result.skipped:
                stats.files_downloaded += 1
                stats.bytes_downloaded += download_result.bytes
            task = download_result.task
            self.state.mark_downloaded(task.file_id, download_result.local_path, task.size)
            self._index_downloaded_file(download_result)

    def _mark_remote_removed(
        self,
        course: CourseInfo,
        result: CrawlResult,
        stats: SyncStats,
        seen_file_ids: Optional[List[int]],
    ) -> None:
        """在主文件列表完整时清理远端已删除的文件。"""
        if not result.files_listed_ok:
            if result.errors:
                stats.notes.append(f"课程 {course.name} 文件列表拉取失败，已保留本地文件，下轮重试")
            return
        ids = seen_file_ids if seen_file_ids is not None else []
        self.state.record_removed_changes(course.id, ids)
        removed = self.state.purge_missing_files(
            course.id, ids, course_root=self.course_local_dir(course)
        )
        stats.files_removed += removed
        if removed:
            self._log(
                "info", "课程 [%s] 远端已删除 %d 个文件，已清理本地副本与记录", course.name, removed
            )

    # ------------------------------------------------------------------
    def _index_downloaded_file(self, download_result: DownloadResult) -> None:
        """下载成功后把文件内容写进本地全文索引。

        抽取失败（损坏文件、无解析器的旧格式、二进制）只索引文件名，
        绝不能让搜索建立失败影响同步本身。
        """
        from .search_index import extract_text

        try:
            text = extract_text(download_result.local_path)
            self.state.upsert_file_index(
                download_result.task.file_id, download_result.task.filename, text
            )
        except Exception as exc:  # pylint: disable=broad-except
            self._log("debug", "建立搜索索引失败 %s: %s", download_result.task.filename, exc)

    def _backfill_search_index(self) -> None:
        """给已下载但未索引的文件补索引（每轮限量，大库分多轮完成）。"""
        try:
            pending = self.state.unindexed_downloaded_files(limit=200)
        except Exception:  # pylint: disable=broad-except
            return
        if not pending:
            return
        from .search_index import extract_text

        self._log("info", "为 %d 个已下载文件建立内容索引…", len(pending))
        done = 0
        for item in pending:
            if self.downloader.is_stopped():
                break
            try:
                text = extract_text(item.get("local_path"))
                self.state.upsert_file_index(item["file_id"], item.get("filename") or "", text)
                done += 1
            except Exception as exc:  # pylint: disable=broad-except
                self._log("debug", "建立搜索索引失败 %s: %s", item.get("filename"), exc)
        if done:
            self._log("info", "内容索引已建立：%d 个文件", done)

    def _on_download_done(self, download_result: DownloadResult, stats: SyncStats) -> None:
        # 只有真正成功才报"完成"；失败统一由本回调显式标记，
        # 避免像以前那样把失败也记成"[完成]"，掩盖真实下载失败。
        name = os.path.basename(download_result.local_path or download_result.task.filename)
        if download_result.success:
            # 变更摘要：此时数据库里还是**旧**状态（mark_downloaded 在回调之后
            # 才执行），据此区分「新增」与「更新」。跳过的文件不算变更。
            change_kind = "updated"
            try:
                if not download_result.skipped:
                    previous = self.state.get_file(download_result.task.file_id)
                    if previous is None or previous.get("status") != "downloaded":
                        change_kind = "new"
                    self.state.record_file_change(
                        0,
                        download_result.task.file_id,
                        download_result.task.course_id,
                        download_result.task.filename,
                        change_kind,
                    )
            except Exception:  # pylint: disable=broad-except
                pass
            status = "跳过(已存在)" if download_result.skipped else "完成"
            self._log(
                "info",
                "  [%s] %s（%s）",
                status,
                name,
                format_size(download_result.bytes or download_result.task.size),
            )
        else:
            self._log("warning", "  [失败] %s: %s", name, download_result.error or "未知错误")
        self._emit(
            "file_done",
            {
                "file_id": download_result.task.file_id,
                "course_id": download_result.task.course_id,
                "filename": download_result.task.filename,
                "folder_path": download_result.task.folder_path,
                "size": download_result.bytes or download_result.task.size,
                "skipped": download_result.skipped,
                "success": download_result.success,
                "error": download_result.error or "",
            },
        )

    # ------------------------------------------------------------------
    def _should_skip(self, remote: RemoteFile) -> Optional[str]:
        """返回跳过原因字符串，None 表示需要下载。

        v1.2.2 起 `locked` 不再是跳过原因：锁定文件会入库标记为 `locked`
        状态并显示在列表里（见下方的任务构建循环）。
        """
        # Canvas 系统目录（课程封面图等）
        folders = [p.lower() for p in (remote.folder_path or "").split("/") if p]
        for bad in self.config.sync.download.exclude_folders:
            if bad and bad in folders:
                return f"系统目录 {bad}"
        ext = os.path.splitext(remote.filename)[1].lower()
        if ext in self.config.sync.download.exclude_extensions:
            return f"扩展名 {ext}"
        if self.config.sync.download.exclude_installer_files and is_installer_file(remote.filename):
            return "安装包"
        limit = self.config.sync.download.max_file_size_mb
        if limit and remote.size > limit * 1024 * 1024:
            return f"超过大小上限 {limit}MB"
        return None

    # ------------------------------------------------------------------
    def _archive_pages(self, course_directory: str, pages) -> int:
        """
        把页面/作业/公告正文导出为 HTML，本地化非文件类内容。

        稳定命名（v1.0.12 起）：同一页面在同一课程目录下**始终写同一个文件名**，
        正文没变就不重写，正文变了原地原子覆盖；同一轮里不同页面重名才追加 `(n)`。
        旧实现每轮都走 `unique_path`，同步几次就会攒出
        「页面 - 标题 (1).html」「页面 - 标题 (2).html」等一堆副本。
        同时在写入时清理**同名编号副本**（只删与当前页面同一组名字的历史副本，
        不动用户自己的文件）。
        """
        out_dir = os.path.join(course_directory, _PAGES_SUBDIR)
        os.makedirs(out_dir, exist_ok=True)
        count = 0
        used_names: set = set()
        for page in pages:
            try:
                title = sanitize_path_component(page.title or "untitled") or "untitled"
                kind_tag = {
                    "page": "页面",
                    "assignment": "作业",
                    "announcement": "公告",
                    "syllabus": "大纲",
                }.get(page.kind, page.kind)
                # 同一轮内重名（不同页面）才避让；跨轮固定使用第一个名字
                index = 0
                while True:
                    suffix = "" if index == 0 else f" ({index})"
                    filename = f"{kind_tag} - {title}{suffix}.html"
                    if filename.casefold() not in used_names:
                        break
                    index += 1
                used_names.add(filename.casefold())
                dest = os.path.join(out_dir, filename)
                if index == 0:
                    self._remove_stale_page_copies(out_dir, kind_tag, title)
                body = self._rewrite_links(page.body or "", page)
                marker = (
                    "<!-- fxx-body-sha1:%s -->" % hashlib.sha1(body.encode("utf-8")).hexdigest()
                )
                existing = self._read_text(dest)
                if existing is not None and marker in existing:
                    count += 1  # 内容没变：不重写，保留首次归档时间
                    continue
                document = (
                    '<!DOCTYPE html>\n<html lang="zh-CN">\n<head>\n'
                    '<meta charset="utf-8">\n'
                    f"<title>{html_lib.escape(title)}</title>\n"
                    '<meta name="generator" content="FuXiaoXue">\n'
                    f"{marker}\n"
                    "<style>body{font-family:sans-serif;max-width:900px;"
                    "margin:2em auto;padding:0 1em;line-height:1.6}"
                    "img{max-width:100%}table{border-collapse:collapse}"
                    "td,th{border:1px solid #ccc;padding:4px 8px}</style>\n"
                    "</head>\n<body>\n"
                    f"<h1>{html_lib.escape(title)}</h1>\n"
                    f"<p><small>类型：{kind_tag} | 归档时间：{now_utc()}</small></p>\n"
                    f"{body}\n</body>\n</html>\n"
                )
                self._write_atomic(dest, document)
                count += 1
            except OSError as exc:
                self._log("warning", "归档页面失败 [%s]: %s", page.title, exc)
        return count

    @staticmethod
    def _read_text(path: str):
        try:
            with open(path, "r", encoding="utf-8") as handle:
                return handle.read()
        except OSError:
            return None

    @staticmethod
    def _write_atomic(path: str, text: str) -> None:
        """临时文件 + os.replace，避免中途失败留下半截 HTML。"""
        temporary_path = path + ".temporary_path"
        with open(temporary_path, "w", encoding="utf-8") as handle:
            handle.write(text)
        os.replace(temporary_path, path)

    def _remove_stale_page_copies(self, out_dir: str, kind_tag: str, title: str) -> None:
        """删除旧版本留下的「同名 (n)」副本（只针对这一组名字）。"""
        for stale in range(1, 50):
            stale_path = os.path.join(out_dir, f"{kind_tag} - {title} ({stale}).html")
            if os.path.exists(stale_path):
                try:
                    os.remove(stale_path)
                    self._log("info", "清理历史页面副本: %s", os.path.basename(stale_path))
                except OSError:  # pragma: no cover - 权限异常时忽略
                    pass

    def _rewrite_links(self, body: str, page) -> str:
        """把相对资源链接改为绝对路径，避免本地 HTML 无法加载资源。"""
        base = self.config.base_url.rstrip("/")

        def _abs_src(match):
            return f'src="{base}/{match.group(1).lstrip("/")}"'

        text = _IMG_SRC_RE.sub(_abs_src, body or "")
        # 相对链接 href（如 /courses/123/pages/xxx）
        text = re.sub(
            r'href="(?!https?://|mailto:|#)([^"]+)"',
            lambda m: f'href="{base}/{m.group(1).lstrip("/")}"',
            text,
        )
        return text
