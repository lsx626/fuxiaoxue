"""最近变更对话框：展示本轮/最近几轮同步的文件级 diff。

数据来自 `sync_changes` 表（v1.1.0 起；同步引擎在下载成功与远端删除时
写入）。在此之前的同步通知只说「新增 N 个文件」，用户不知道下了什么。
"""
from __future__ import annotations

import datetime
from typing import List, Optional

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor
from PySide6.QtWidgets import (QDialog, QDialogButtonBox, QLabel, QListWidget,
                               QListWidgetItem, QVBoxLayout, QWidget)

from ..state import StateStore
from .styles import (ACCENT, CARD, DANGER, TEXT_SECONDARY)

_CHANGE_TEXT = {"new": "新增", "updated": "更新", "removed": "删除"}
_CHANGE_COLOR = {"new": ACCENT, "updated": ACCENT, "removed": DANGER}


def _relative_time(value: str) -> str:
    if not value:
        return "—"
    try:
        text = value.replace("Z", "+00:00")
        parsed = datetime.datetime.fromisoformat(text)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=datetime.timezone.utc)
        seconds = int((datetime.datetime.now(datetime.timezone.utc) - parsed).total_seconds())
        if seconds < 0:
            return "刚刚"
        if seconds < 3600:
            return f"{seconds // 60} 分钟前"
        if seconds < 86400:
            return f"{seconds // 3600} 小时前"
        if seconds < 86400 * 30:
            return f"{seconds // 86400} 天前"
        return parsed.astimezone().strftime("%Y-%m-%d")
    except ValueError:
        return value


class RecentChangesDialog(QDialog):
    """列出最近的文件变更（新增/更新/删除）。"""

    def __init__(self, state_db: str, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setWindowTitle("最近变更")
        self.setMinimumSize(560, 420)
        self.resize(640, 520)
        self.state_db = state_db
        self._changes: List[dict] = []
        self._build_ui()
        self._load()
        self.setAttribute(Qt.WA_DeleteOnClose, True)

    def _build_ui(self) -> None:
        layout = QVBoxLayout(self)
        layout.setContentsMargins(18, 16, 18, 14)
        layout.setSpacing(10)

        hint = QLabel("最近几轮同步的文件变化；同步失败时这里不会出现对应记录。")
        hint.setStyleSheet(f"color: {TEXT_SECONDARY}; font-size: 12px;")
        layout.addWidget(hint)

        self.list_widget = QListWidget()
        self.list_widget.setStyleSheet(
            f"QListWidget {{ background: {CARD}; border: 1px solid #E2E7F1;"
            f" border-radius: 8px; }}"
            f"QListWidget::item {{ padding: 8px 10px; }}")
        layout.addWidget(self.list_widget, 1)

        buttons = QDialogButtonBox(QDialogButtonBox.Close)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def _load(self) -> None:
        try:
            store = StateStore(self.state_db)
        except Exception:  # pylint: disable=broad-except
            return
        try:
            self._changes = store.recent_changes(limit=100)
        except Exception:  # pylint: disable=broad-except
            self._changes = []
        finally:
            store.close()
        self._render()

    def _render(self) -> None:
        self.list_widget.clear()
        for item in self._changes:
            change = item.get("change") or ""
            kind = _CHANGE_TEXT.get(change, change)
            color = _CHANGE_COLOR.get(change, TEXT_SECONDARY)
            filename = item.get("filename") or "未知文件"
            course = item.get("course_name") or f"课程 {item.get('course_id')}"
            stamp = _relative_time(item.get("occurred_at") or "")
            text = f"[{kind}] {filename}　·　{course}　·　{stamp}"
            list_item = QListWidgetItem(text)
            list_item.setToolTip(text)
            list_item.setForeground(QColor(color))
            self.list_widget.addItem(list_item)
        if not self._changes:
            list_item = QListWidgetItem("暂无变更记录。每次同步的新增/更新/删除都会展示在这里。")
            self.list_widget.addItem(list_item)
