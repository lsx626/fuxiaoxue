"""SQLite 状态存储：记录课程、文件与同步元数据，支撑增量同步。"""

from __future__ import annotations

import json
import os
import sqlite3
import threading
from contextlib import contextmanager
from typing import Any, Dict, List, Optional

from .utils import now_utc


class StateStore:
    """线程安全的 SQLite 状态库（WAL 模式）。"""

    def __init__(self, db_path: str, logger=None):
        self.db_path = db_path
        self.log = logger
        self._local = threading.local()
        os.makedirs(os.path.dirname(os.path.abspath(db_path)), exist_ok=True)
        self._init_db()

    # ------------------------------------------------------------------
    @property
    def conn(self) -> sqlite3.Connection:
        """每个线程一个连接（sqlite3 连接不可跨线程共享）。"""
        if not hasattr(self._local, "conn"):
            conn = sqlite3.connect(self.db_path, timeout=30.0)
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA synchronous=NORMAL")
            self._local.conn = conn
        return self._local.conn

    def _init_db(self) -> None:
        with self._write_lock_cursor() as cursor:
            cursor.executescript("""
            CREATE TABLE IF NOT EXISTS courses (
                id INTEGER PRIMARY KEY,
                name TEXT,
                code TEXT,
                term TEXT,
                enrollment_type TEXT,
                is_favorite INTEGER DEFAULT 0,
                selected INTEGER DEFAULT 1,
                first_seen_at TEXT,
                last_synced_at TEXT
            );

            CREATE TABLE IF NOT EXISTS files (
                file_id INTEGER PRIMARY KEY,
                course_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                display_name TEXT,
                size INTEGER DEFAULT 0,
                content_type TEXT,
                folder_id INTEGER,
                folder_path TEXT DEFAULT '',
                created_at TEXT,
                updated_at TEXT,
                modified_at TEXT,
                locked_for_user INTEGER DEFAULT 0,
                hidden INTEGER DEFAULT 0,
                source TEXT DEFAULT '',
                context TEXT DEFAULT '',
                local_path TEXT,
                status TEXT DEFAULT 'pending',   -- pending|downloaded|failed|locked
                last_seen_at TEXT,
                downloaded_at TEXT,
                last_error TEXT,
                selected INTEGER DEFAULT 0,
                extra TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_files_course ON files(course_id);
            CREATE INDEX IF NOT EXISTS idx_files_status ON files(status);
            CREATE INDEX IF NOT EXISTS idx_files_path ON files(local_path);

            CREATE TABLE IF NOT EXISTS sync_runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at TEXT,
                finished_at TEXT,
                mode TEXT,
                courses INTEGER DEFAULT 0,
                files_found INTEGER DEFAULT 0,
                files_downloaded INTEGER DEFAULT 0,
                bytes_downloaded INTEGER DEFAULT 0,
                files_failed INTEGER DEFAULT 0,
                files_removed INTEGER DEFAULT 0,
                errors INTEGER DEFAULT 0,
                note TEXT
            );

            CREATE TABLE IF NOT EXISTS kv (
                key TEXT PRIMARY KEY,
                value TEXT
            );

            CREATE TABLE IF NOT EXISTS assignments (
                id INTEGER PRIMARY KEY,
                course_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                due_at TEXT DEFAULT '',
                html_url TEXT DEFAULT '',
                fetched_at TEXT DEFAULT ''
            );

            CREATE TABLE IF NOT EXISTS sync_changes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id INTEGER DEFAULT 0,
                file_id INTEGER NOT NULL,
                course_id INTEGER NOT NULL,
                filename TEXT DEFAULT '',
                change TEXT DEFAULT '',        -- new | updated | removed
                occurred_at TEXT DEFAULT ''
            );

            CREATE TABLE IF NOT EXISTS reading_progress (
                file_id INTEGER PRIMARY KEY,
                course_id INTEGER DEFAULT 0,
                -- 页码（0 起）或媒体播放秒数，由 is_media 区分
                position INTEGER DEFAULT 0,
                total INTEGER DEFAULT 0,       -- 总页数或总秒数，用于算百分比
                is_media INTEGER DEFAULT 0,
                updated_at TEXT DEFAULT ''
            );            """)
            self._ensure_column(cursor, "courses", "selected", "INTEGER DEFAULT 1")
            self._ensure_column(cursor, "files", "selected", "INTEGER DEFAULT 0")
        # FTS5 is optional; failure leaves the rest of the schema usable.
        self._fts_available = True
        try:
            self.conn.execute(
                "CREATE VIRTUAL TABLE IF NOT EXISTS files_fts USING fts5("
                "filename, content, file_id UNINDEXED)"
            )
        except sqlite3.OperationalError as exc:
            self._fts_available = False
            if self.log:
                self.log.warning("当前 SQLite 不支持 FTS5，搜索将退化为文件名匹配: %s", exc)

    _write_lock = threading.Lock()

    @contextmanager
    def _write_lock_cursor(self):
        with self._write_lock:
            cursor = self.conn.cursor()
            try:
                yield cursor
                self.conn.commit()
            except Exception:
                self.conn.rollback()
                raise
            finally:
                cursor.close()

    # ------------------------------------------------------------------
    # 课程
    # ------------------------------------------------------------------
    def upsert_course(
        self,
        course_id: int,
        name: str,
        code: str,
        term: str,
        enrollment_type: str,
        is_favorite: bool,
    ) -> None:
        with self._write_lock_cursor() as cursor:
            cursor.execute(
                """INSERT INTO courses (id, name, code, term, enrollment_type,
                                        is_favorite, first_seen_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(id) DO UPDATE SET
                       name=excluded.name, code=excluded.code, term=excluded.term,
                       enrollment_type=excluded.enrollment_type,
                       is_favorite=excluded.is_favorite""",
                (course_id, name, code, term, enrollment_type, int(is_favorite), now_utc()),
            )

    def mark_course_synced(self, course_id: int) -> None:
        with self._write_lock_cursor() as cursor:
            cursor.execute("UPDATE courses SET last_synced_at=? WHERE id=?", (now_utc(), course_id))

    def list_courses(self) -> List[Dict[str, Any]]:
        cursor = self.conn.execute("SELECT * FROM courses ORDER BY name")
        return [dict(r) for r in cursor.fetchall()]

    @staticmethod
    def _ensure_column(cursor: sqlite3.Cursor, table: str, column: str, definition: str) -> None:
        columns = {row[1] for row in cursor.execute(f"PRAGMA table_info({table})")}
        if column not in columns:
            cursor.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")

    def course_ids_matching_file_name(self, query: str) -> set[int]:
        text = str(query or "").strip()
        if not text:
            return set()
        escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        pattern = f"%{escaped}%"
        rows = self.conn.execute(
            """SELECT DISTINCT course_id FROM files
               WHERE status != 'remote_missing'
                 AND (filename LIKE ? ESCAPE '\\' OR display_name LIKE ? ESCAPE '\\')""",
            (pattern, pattern),
        ).fetchall()
        return {int(row[0]) for row in rows}

    def course_progress(self) -> List[Dict[str, Any]]:
        """课程维度汇总（界面表格用）：文件数 / 已下载数 / 本地容量 / 最近同步。"""
        cursor = self.conn.execute(
            """SELECT c.id, c.name, c.code, c.term, c.last_synced_at, c.selected,
                      COUNT(f.file_id) AS files_total,
                      SUM(CASE WHEN f.status='downloaded' THEN 1 ELSE 0 END) AS files_done,
                      SUM(CASE WHEN f.status='downloaded' THEN COALESCE(f.size, 0)
                               ELSE 0 END) AS bytes_downloaded
               FROM courses c LEFT JOIN files f ON f.course_id = c.id
               GROUP BY c.id, c.name, c.code, c.term, c.last_synced_at, c.selected
               ORDER BY c.name"""
        )
        return [dict(r) for r in cursor.fetchall()]

    # ------------------------------------------------------------------
    # 文件
    # ------------------------------------------------------------------
    def get_file(self, file_id: int) -> Optional[Dict[str, Any]]:
        cursor = self.conn.execute("SELECT * FROM files WHERE file_id=?", (file_id,))
        row = cursor.fetchone()
        return dict(row) if row else None

    def upsert_file(self, remote: Dict[str, Any]) -> bool:
        """写入远端文件信息；返回 True 表示本地副本需要（重新）下载。

        判定条件：本地不存在 / 状态非 downloaded / 大小或时间戳变化 / 路径变化。
        """
        needs_download = False
        with self._write_lock_cursor() as cursor:
            cursor.execute("SELECT * FROM files WHERE file_id=?", (remote["file_id"],))
            row = cursor.fetchone()

            if row is None:
                needs_download = True
            else:
                stored = dict(row)
                if stored.get("status") != "downloaded":
                    needs_download = True
                elif (
                    int(stored.get("size") or -1) != int(remote.get("size") or 0)
                    or stored.get("updated_at") != remote.get("updated_at")
                    or stored.get("modified_at") != remote.get("modified_at")
                    or stored.get("filename") != remote.get("filename")
                    or stored.get("local_path") != remote.get("local_path")
                ):
                    needs_download = True
                # 本地文件丢失也需重下
                local_path = stored.get("local_path")
                if not local_path or not os.path.exists(local_path):
                    needs_download = True

            cursor.execute(
                """INSERT INTO files (file_id, course_id, filename, display_name, size,
                                      content_type, folder_id, folder_path, created_at,
                                      updated_at, modified_at, locked_for_user, hidden,
                                      source, context, local_path, status, last_seen_at,
                                      last_error, selected, extra)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                           COALESCE((SELECT status FROM files WHERE file_id=?), 'pending'),
                           ?, NULL, COALESCE((SELECT selected FROM files WHERE file_id=?), 0),
                           COALESCE((SELECT extra FROM files WHERE file_id=?), ?))
                   ON CONFLICT(file_id) DO UPDATE SET
                       course_id=excluded.course_id, filename=excluded.filename,
                       display_name=excluded.display_name, size=excluded.size,
                       content_type=excluded.content_type, folder_id=excluded.folder_id,
                       folder_path=excluded.folder_path, created_at=excluded.created_at,
                       updated_at=excluded.updated_at, modified_at=excluded.modified_at,
                       locked_for_user=excluded.locked_for_user, hidden=excluded.hidden,
                       source=excluded.source, context=excluded.context,
                       local_path=excluded.local_path, last_seen_at=excluded.last_seen_at""",
                (
                    remote["file_id"],
                    remote["course_id"],
                    remote["filename"],
                    remote.get("display_name"),
                    remote.get("size") or 0,
                    remote.get("content_type", ""),
                    remote.get("folder_id"),
                    remote.get("folder_path", ""),
                    remote.get("created_at"),
                    remote.get("updated_at"),
                    remote.get("modified_at"),
                    int(bool(remote.get("locked_for_user"))),
                    int(bool(remote.get("hidden"))),
                    remote.get("source", ""),
                    remote.get("context", ""),
                    remote.get("local_path"),
                    remote["file_id"],
                    now_utc(),
                    remote["file_id"],
                    remote["file_id"],
                    remote.get("extra"),
                ),
            )
        return needs_download

    def set_local_path(self, file_id: int, local_path: str) -> None:
        with self._write_lock_cursor() as cursor:
            cursor.execute("UPDATE files SET local_path=? WHERE file_id=?", (local_path, file_id))

    def mark_downloaded(self, file_id: int, local_path: str, size: int) -> None:
        with self._write_lock_cursor() as cursor:
            cursor.execute(
                """UPDATE files SET status='downloaded', local_path=?, downloaded_at=?,
                                   last_error=NULL WHERE file_id=?""",
                (local_path, now_utc(), file_id),
            )

    def mark_failed(self, file_id: int, error: str) -> None:
        with self._write_lock_cursor() as cursor:
            cursor.execute(
                "UPDATE files SET status='failed', last_error=? WHERE file_id=?",
                (error[:500], file_id),
            )

    def mark_locked(self, file_id: int, reason: str) -> None:
        with self._write_lock_cursor() as cursor:
            cursor.execute(
                "UPDATE files SET status='locked', last_error=?, local_path=NULL WHERE file_id=?",
                (reason[:500], file_id),
            )

    @staticmethod
    def _is_managed_file(path: str, course_root: str) -> bool:
        try:
            root = os.path.realpath(os.path.abspath(course_root))
            candidate = os.path.realpath(os.path.abspath(path))
            common = os.path.commonpath((root, candidate))
        except (OSError, ValueError):
            return False
        return os.path.normcase(common) == os.path.normcase(root) and os.path.normcase(
            candidate
        ) != os.path.normcase(root)

    def _remove_managed_copy(self, local_path: str, course_root: str) -> bool:
        candidates = (local_path, local_path + ".part")
        existing = [path for path in candidates if os.path.exists(path)]
        if not existing:
            return True
        if not self._is_managed_file(local_path, course_root):
            if self.log:
                self.log.warning("历史路径位于课程目录外，仅移除数据库记录: %s", local_path)
            return True

        removed = True
        for path in existing:
            try:
                os.remove(path)
            except OSError as exc:
                removed = False
                if self.log:
                    self.log.warning("删除远端已移除的本地文件失败 %s: %s", path, exc)
        return removed

    def purge_missing_files(
        self, course_id: int, seen_file_ids: List[int], course_root: str
    ) -> int:
        """Delete local copies and records for files absent from a complete listing."""
        placeholders = ",".join("?" * len(seen_file_ids)) if seen_file_ids else "0"
        cursor = self.conn.execute(
            f"""SELECT file_id, local_path FROM files
                WHERE course_id=? AND file_id NOT IN ({placeholders})""",
            (course_id, *seen_file_ids),
        )
        rows = [dict(r) for r in cursor.fetchall()]
        if not rows:
            return 0
        removable_ids = [
            row["file_id"]
            for row in rows
            if not row.get("local_path")
            or self._remove_managed_copy(row["local_path"], course_root)
        ]

        if removable_ids:
            with self._write_lock_cursor() as write_cursor:
                for file_id in removable_ids:
                    write_cursor.execute("DELETE FROM reading_progress WHERE file_id=?", (file_id,))
                    if self._fts_available:
                        write_cursor.execute("DELETE FROM files_fts WHERE file_id=?", (file_id,))
                    write_cursor.execute("DELETE FROM files WHERE file_id=?", (file_id,))
        return len(removable_ids)

    def list_files_by_course(self, course_id: int) -> List[Dict[str, Any]]:
        cursor = self.conn.execute(
            "SELECT * FROM files WHERE course_id=? ORDER BY folder_path, filename", (course_id,)
        )
        return [dict(r) for r in cursor.fetchall()]

    def set_course_selected(self, course_id: int, selected: bool) -> None:
        with self._write_lock_cursor() as cursor:
            cursor.execute(
                "UPDATE courses SET selected=? WHERE id=?", (int(bool(selected)), course_id)
            )
            if selected:
                cursor.execute(
                    "UPDATE files SET selected=0 WHERE course_id=?", (course_id,)
                )

    def set_file_selected(self, file_id: int, selected: bool) -> None:
        with self._write_lock_cursor() as cursor:
            cursor.execute(
                "UPDATE files SET selected=? WHERE file_id=?", (int(bool(selected)), file_id)
            )

    def set_course_files_selected(self, course_id: int, selected: bool) -> None:
        with self._write_lock_cursor() as cursor:
            cursor.execute(
                "UPDATE files SET selected=? WHERE course_id=? AND status IN ('pending','failed')",
                (int(bool(selected)), course_id),
            )

    def file_id_by_path(self, local_path: str) -> Optional[Dict[str, Any]]:
        """按本地路径反查文件记录（预览入口只知道路径，翻页与进度需要 file_id）。"""
        cursor = self.conn.execute("SELECT * FROM files WHERE local_path=? LIMIT 1", (local_path,))
        row = cursor.fetchone()
        return dict(row) if row else None

    # ------------------------------------------------------------------
    # 阅读进度
    # ------------------------------------------------------------------
    def get_reading_progress(self, file_id: int) -> Optional[Dict[str, Any]]:
        cursor = self.conn.execute(
            "SELECT position, total, is_media, updated_at FROM reading_progress WHERE file_id=?",
            (file_id,),
        )
        row = cursor.fetchone()
        return dict(row) if row else None

    def set_reading_progress(
        self, file_id: int, course_id: int, position: int, total: int, is_media: bool = False
    ) -> None:
        """记录阅读位置（页码或媒体秒）。重复调用为覆盖。"""
        with self._write_lock_cursor() as cursor:
            cursor.execute(
                "INSERT INTO reading_progress (file_id, course_id, position, total,"
                " is_media, updated_at) VALUES (?, ?, ?, ?, ?, ?)"
                " ON CONFLICT(file_id) DO UPDATE SET"
                " course_id=excluded.course_id, position=excluded.position,"
                " total=excluded.total, is_media=excluded.is_media,"
                " updated_at=excluded.updated_at",
                (file_id, course_id, int(position), int(total), 1 if is_media else 0, now_utc()),
            )

    def progress_by_course(self, course_id: int) -> Dict[int, Dict[str, Any]]:
        """整门课的阅读进度（供文件列表批量渲染进度列，避免逐行查询）。"""
        cursor = self.conn.execute(
            "SELECT file_id, position, total, is_media, updated_at FROM reading_progress "
            "WHERE course_id=?",
            (course_id,),
        )
        return {row["file_id"]: dict(row) for row in cursor.fetchall()}

    def clear_reading_progress(self, file_id: int) -> None:
        with self._write_lock_cursor() as cursor:
            cursor.execute("DELETE FROM reading_progress WHERE file_id=?", (file_id,))

    def clear_course_reading_progress(self, course_id: int) -> None:
        """课程文件被全部删除时清理对应进度（避免残留指向已删文件）。"""
        with self._write_lock_cursor() as cursor:
            cursor.execute("DELETE FROM reading_progress WHERE course_id=?", (course_id,))

    # ------------------------------------------------------------------
    # 本地全文索引
    # ------------------------------------------------------------------
    def upsert_file_index(self, file_id: int, filename: str, text: Optional[str]) -> None:
        """写入/更新某个文件的全文索引（文件名 + 内容）。

        索引列存的是 CJK 预分词后的文本（默认 unicode61 不做中文分词，
        原样写入会让「计算机」永远命中不了「计算机体系结构」）。
        重复调用是覆盖语义（先删后插），已有索引不会重复累积。
        """
        if not self._fts_available:
            return
        from .search_index import tokenize_for_index

        with self._write_lock_cursor() as cursor:
            cursor.execute("DELETE FROM files_fts WHERE file_id=?", (file_id,))
            cursor.execute(
                "INSERT INTO files_fts (filename, content, file_id) VALUES (?, ?, ?)",
                (tokenize_for_index(filename or ""), tokenize_for_index(text or ""), file_id),
            )

    def remove_file_index(self, file_id: int) -> None:
        """文件记录被删除时同步清掉索引，避免搜到已经不存在的文件。"""
        if not self._fts_available:
            return
        with self._write_lock_cursor() as cursor:
            cursor.execute("DELETE FROM files_fts WHERE file_id=?", (file_id,))

    def delete_file(self, file_id: int) -> None:
        """删除单个文件记录（连同搜索索引）。

        存储管理器此前跨类调用 `_write_lock_cursor` 直接执行 DELETE，
        既绕过封装也漏掉搜索索引；此方法把删除收拢为一条原子路径。
        """
        with self._write_lock_cursor() as cursor:
            cursor.execute("DELETE FROM reading_progress WHERE file_id=?", (file_id,))
            cursor.execute("DELETE FROM files WHERE file_id=?", (file_id,))
            if self._fts_available:
                cursor.execute("DELETE FROM files_fts WHERE file_id=?", (file_id,))

    # ------------------------------------------------------------------
    # 作业截止日期与变更摘要
    # ------------------------------------------------------------------
    def upsert_assignments(self, course_id: int, assignments) -> None:
        """整课替换式写入作业（id/name/due_at/html_url）。"""
        with self._write_lock_cursor() as cursor:
            cursor.execute("DELETE FROM assignments WHERE course_id=?", (course_id,))
            for item in assignments:
                cursor.execute(
                    "INSERT OR REPLACE INTO assignments (id, course_id, name, due_at, html_url) "
                    "VALUES (?, ?, ?, ?, ?)",
                    (
                        item["assignment_id"],
                        course_id,
                        item["name"],
                        item.get("due_at") or "",
                        item.get("html_url") or "",
                    ),
                )

    def list_assignments(self, limit: Optional[int] = 200) -> List[Dict[str, Any]]:
        """按截止时间升序列出作业；limit=None 时返回全部。"""
        sql = """SELECT a.id, a.course_id, a.name, a.due_at, a.html_url,
                         c.name AS course_name
                  FROM assignments a LEFT JOIN courses c ON c.id = a.course_id
                  WHERE a.due_at != ''
                  ORDER BY a.due_at ASC"""
        params = ()
        if limit is not None:
            sql += " LIMIT ?"
            params = (limit,)
        cursor = self.conn.execute(sql, params)
        return [dict(r) for r in cursor.fetchall()]

    def record_file_change(
        self,
        run_id: int,
        file_id: int,
        course_id: int,
        filename: str,
        change: str,
        occurred_at: str = "",
    ) -> None:
        """记录一个文件级变更（new / updated / removed）。"""
        with self._write_lock_cursor() as cursor:
            cursor.execute(
                "INSERT INTO sync_changes (run_id, file_id, course_id, filename, change, occurred_at) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (run_id, file_id, course_id, filename, change, occurred_at),
            )

    def record_removed_changes(self, course_id: int, seen_file_ids: List[int]) -> None:
        """Record files that a complete listing no longer contains."""
        placeholders = ",".join("?" * len(seen_file_ids)) if seen_file_ids else "0"
        cursor = self.conn.execute(
            f"""SELECT file_id, filename FROM files
                WHERE course_id=? AND status != 'remote_missing'
                  AND file_id NOT IN ({placeholders})""",
            (course_id, *seen_file_ids),
        )
        rows = [dict(r) for r in cursor.fetchall()]
        if not rows:
            return
        now = now_utc()
        with self._write_lock_cursor() as write_cursor:
            for row in rows:
                write_cursor.execute(
                    "INSERT INTO sync_changes (run_id, file_id, course_id, filename, change, occurred_at) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    (0, row["file_id"], course_id, row.get("filename") or "", "removed", now),
                )

    def recent_changes(self, limit: int = 50) -> List[Dict[str, Any]]:
        """最近的文件变更（通知摘要与「最近变更」视图的来源）。"""
        cursor = self.conn.execute(
            """SELECT s.file_id, s.course_id, s.filename, s.change, s.occurred_at,
                      c.name AS course_name
               FROM sync_changes s LEFT JOIN courses c ON c.id = s.course_id
               ORDER BY s.id DESC LIMIT ?""",
            (limit,),
        )
        return [dict(r) for r in cursor.fetchall()]

    def trim_changes(self, keep: int = 500) -> None:
        """变更摘要只保留最近 [keep] 条（无界增长没有意义）。"""
        with self._write_lock_cursor() as cursor:
            cursor.execute(
                "DELETE FROM sync_changes WHERE id NOT IN "
                "(SELECT id FROM sync_changes ORDER BY id DESC LIMIT ?)",
                (keep,),
            )

    def unindexed_downloaded_files(self, limit: int = 200) -> List[Dict[str, Any]]:
        """已下载但尚未建立内容索引的文件（老库迁移与增量回填用）。"""
        try:
            cursor = self.conn.execute(
                """SELECT f.file_id, f.filename, f.display_name, f.local_path
                   FROM files f
                   WHERE f.status='downloaded' AND f.local_path IS NOT NULL
                     AND f.file_id NOT IN (SELECT file_id FROM files_fts)
                   ORDER BY f.downloaded_at DESC LIMIT ?""",
                (limit,),
            )
            return [dict(r) for r in cursor.fetchall()]
        except sqlite3.OperationalError:
            return []

    def search_files(self, query: str, limit: int = 100) -> List[Dict[str, Any]]:
        """跨课程搜索本地文件（文件名 + 内容）。remote_missing 不参与搜索。"""
        from .search_index import build_match_query

        match = build_match_query(query)
        if match is None:
            return []
        if self._fts_available:
            try:
                return self._search_fts(match, limit)
            except sqlite3.OperationalError:
                # 前缀语法等边界情况失败时退化为文件名匹配，不能让搜索崩
                pass
        return self._search_filename_like(query, limit)

    def _search_fts(self, match: str, limit: int) -> List[Dict[str, Any]]:
        cursor = self.conn.execute(
            """SELECT f.file_id, f.course_id, f.filename, f.display_name, f.size,
                      f.status, f.local_path, c.name AS course_name,
                      COALESCE(snippet(files_fts, 1, '[', ']', '…', 12),
                               snippet(files_fts, 0, '[', ']', '…', 12)) AS snippet
               FROM files_fts
               JOIN files f ON f.file_id = files_fts.file_id
               LEFT JOIN courses c ON c.id = f.course_id
               WHERE files_fts MATCH ? AND f.status != 'remote_missing'
               ORDER BY rank LIMIT ?""",
            (match, limit),
        )
        return [dict(r) for r in cursor.fetchall()]

    @staticmethod
    def _like_term(query: str) -> str:
        cleaned = "%" + "".join(ch for ch in query if ch not in "%_").replace(" ", "%") + "%"
        return cleaned

    def _search_filename_like(self, query: str, limit: int) -> List[Dict[str, Any]]:
        """FTS5 不可用（或不支持的语法）时的降级搜索：只匹配文件名。"""
        terms = [term for term in (query or "").split() if term]
        if not terms:
            return []
        clauses = []
        params: List[Any] = []
        for term in terms:
            clauses.append("(LOWER(f.filename) LIKE ? OR LOWER(f.display_name) LIKE ?)")
            like = self._like_term(term.lower())
            params.extend([like, like])
        params.append(limit)
        cursor = self.conn.execute(
            f"""SELECT f.file_id, f.course_id, f.filename, f.display_name, f.size,
                       f.status, f.local_path, c.name AS course_name, NULL AS snippet
                FROM files f LEFT JOIN courses c ON c.id = f.course_id
                WHERE f.status != 'remote_missing' AND ({" AND ".join(clauses)})
                ORDER BY f.filename LIMIT ?""",
            params,
        )
        return [dict(r) for r in cursor.fetchall()]

    # ------------------------------------------------------------------
    def record_run(self, **stats) -> int:
        with self._write_lock_cursor() as cursor:
            cursor.execute(
                """INSERT INTO sync_runs (started_at, finished_at, mode, courses,
                                          files_found, files_downloaded,
                                          bytes_downloaded, files_failed, files_removed,
                                          errors, note)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    stats.get("started_at"),
                    now_utc(),
                    stats.get("mode", "incremental"),
                    stats.get("courses", 0),
                    stats.get("files_found", 0),
                    stats.get("files_downloaded", 0),
                    stats.get("bytes_downloaded", 0),
                    stats.get("files_failed", 0),
                    stats.get("files_removed", 0),
                    stats.get("errors", 0),
                    stats.get("note", ""),
                ),
            )
            return cursor.lastrowid or 0

    def last_run(self) -> Optional[Dict[str, Any]]:
        cursor = self.conn.execute("SELECT * FROM sync_runs ORDER BY id DESC LIMIT 1")
        row = cursor.fetchone()
        return dict(row) if row else None

    def stats(self) -> Dict[str, Any]:
        cursor = self.conn.execute(
            """SELECT
                 (SELECT COUNT(*) FROM courses) AS courses,
                 (SELECT COUNT(*) FROM files) AS files_total,
                 (SELECT COUNT(*) FROM files WHERE status='downloaded') AS files_downloaded,
                 (SELECT COUNT(*) FROM files WHERE status='pending') AS files_pending,
                 (SELECT COUNT(*) FROM files WHERE status='failed') AS files_failed,
                 (SELECT COUNT(*) FROM files WHERE status='remote_missing') AS files_missing,
                 (SELECT COALESCE(SUM(size), 0) FROM files WHERE status='downloaded') AS bytes"""
        )
        return dict(cursor.fetchone())

    def set_kv(self, key: str, value: Any) -> None:
        with self._write_lock_cursor() as cursor:
            cursor.execute(
                "INSERT INTO kv (key, value) VALUES (?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (key, json.dumps(value, ensure_ascii=False)),
            )

    def get_kv(self, key: str, default: Any = None) -> Any:
        cursor = self.conn.execute("SELECT value FROM kv WHERE key=?", (key,))
        row = cursor.fetchone()
        if row is None:
            return default
        try:
            return json.loads(row["value"])
        except (ValueError, TypeError):
            return row["value"]

    def close(self) -> None:
        if hasattr(self._local, "conn"):
            self._local.conn.close()
            del self._local.conn
