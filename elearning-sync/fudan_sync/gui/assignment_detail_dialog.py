"""桌面端应用内作业详情与提交评语查看。"""

from __future__ import annotations

import html
from typing import Optional

from PySide6.QtCore import Qt, QThread, Signal
from PySide6.QtWidgets import QDialog, QLabel, QMessageBox, QTextBrowser, QVBoxLayout, QWidget

from ..auth import build_auth
from ..canvas_api import CanvasAPI


class _AssignmentWorker(QThread):
    loaded = Signal(dict)
    failed = Signal(str)

    def __init__(self, cfg, course_id: int, assignment_id: int):
        super().__init__()
        self.cfg = cfg
        self.course_id = course_id
        self.assignment_id = assignment_id

    def run(self) -> None:
        try:
            api = CanvasAPI(self.cfg.base_url, build_auth(self.cfg))
            self.loaded.emit(api.get_assignment(self.course_id, self.assignment_id))
        except Exception as exc:  # pylint: disable=broad-except
            self.failed.emit(str(exc))


class AssignmentDetailDialog(QDialog):
    """在软件内展示作业正文、提交状态和教师评论。"""

    def __init__(self, cfg, assignment: dict, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.cfg = cfg
        self.assignment = assignment
        self.setWindowTitle(f"任务 · {assignment.get('name') or '未命名作业'}")
        self.resize(820, 680)
        self.setAttribute(Qt.WA_DeleteOnClose, True)
        layout = QVBoxLayout(self)
        self.status = QLabel("正在加载任务详情…")
        self.status.setStyleSheet("color: #6B7280;")
        layout.addWidget(self.status)
        self.browser = QTextBrowser()
        self.browser.setOpenExternalLinks(True)
        self.browser.setPlaceholderText("暂无任务内容")
        layout.addWidget(self.browser, 1)
        self.worker = _AssignmentWorker(
            cfg, int(assignment.get("course_id")), int(assignment.get("id"))
        )
        self.worker.loaded.connect(self._show)
        self.worker.failed.connect(self._failed)
        self.worker.finished.connect(self.worker.deleteLater)
        self.worker.start()

    def _show(self, data: dict) -> None:
        name = html.escape(data.get("name") or self.assignment.get("name") or "未命名作业")
        body = data.get("description") or "<p>教师未提供任务说明。</p>"
        submission = data.get("submission") or {}
        comments = data.get("submission_comments") or submission.get("submission_comments") or []
        parts = [f"<h2>{name}</h2>"]
        if data.get("due_at"):
            parts.append(f"<p><b>截止时间：</b>{html.escape(str(data['due_at']))}</p>")
        parts.append("<hr>")
        parts.append(body)
        if submission:
            parts.append("<h3>我的提交</h3>")
            parts.append(f"<p>状态：{html.escape(str(submission.get('workflow_state') or '已提交'))}</p>")
            if submission.get("submitted_at"):
                parts.append(f"<p>提交时间：{html.escape(str(submission['submitted_at']))}</p>")
        if comments:
            parts.append("<h3>已提交内容的评论</h3>")
            for comment in comments:
                author_info = comment.get("author") if isinstance(comment.get("author"), dict) else {}
                author = html.escape(str(comment.get("author_name") or author_info.get("display_name") or "教师"))
                text = comment.get("comment") or comment.get("text") or ""
                parts.append(f"<p><b>{author}</b>：{html.escape(str(text))}</p>")
        else:
            parts.append("<h3>已提交内容的评论</h3><p>暂无评论。</p>")
        self.browser.setHtml("".join(parts))
        self.status.setText("任务详情已加载")

    def _failed(self, message: str) -> None:
        self.status.setText("任务详情加载失败")
        QMessageBox.warning(self, "无法加载任务", f"请检查登录状态后重试。\n{message}")
