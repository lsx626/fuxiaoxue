"""作业截止日期对话框：按时间排列全部课程的待办作业。

数据来自同步时采集的 Canvas assignments（`assignments` 表，v1.1.0 起）；
在此之前两端都请求了 assignments 接口却把 `due_at` 字段直接丢弃。

支持导出标准 .ics（CRLF 行尾、UTC TZID），可以导入系统日历或
Google Calendar；导出失败弹出明确错误，不静默吞掉。
"""
from __future__ import annotations

import datetime
from typing import List, Optional

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (QDialog, QDialogButtonBox, QFileDialog, QLabel,
                               QListWidget, QListWidgetItem, QMessageBox,
                               QVBoxLayout, QWidget)

from ..state import StateStore
from ..utils import clean_course_name, format_size  # noqa: F401  （保持与其他对话框一致的导入习惯）
from .styles import (ACCENT, CARD, DANGER, SUCCESS, TEXT, TEXT_SECONDARY, WARNING)


def _parse_due(value: str) -> Optional[datetime.datetime]:
    """Canvas 的 due_at 是 ISO8601（可能带 Z 或偏移）；解析失败返回 None。"""
    if not value:
        return None
    try:
        text = value.replace("Z", "+00:00")
        parsed = datetime.datetime.fromisoformat(text)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=datetime.timezone.utc)
        return parsed
    except ValueError:
        return None


class DeadlinesDialog(QDialog):
    """列出按截止时间升序排列的作业。"""

    def __init__(self, state_db: str, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setWindowTitle("作业截止日期")
        self.setMinimumSize(560, 420)
        self.resize(640, 520)
        self.state_db = state_db
        self._assignments: List[dict] = []
        self._build_ui()
        self._load()
        self.setAttribute(Qt.WA_DeleteOnClose, True)

    def _build_ui(self) -> None:
        layout = QVBoxLayout(self)
        layout.setContentsMargins(18, 16, 18, 14)
        layout.setSpacing(10)

        hint = QLabel("按截止时间排列全部课程的作业；可导出 .ics 导入系统日历。")
        hint.setStyleSheet(f"color: {TEXT_SECONDARY}; font-size: 12px;")
        layout.addWidget(hint)

        self.status_label = QLabel("")
        self.status_label.setStyleSheet(f"color: {TEXT_SECONDARY}; font-size: 12px;")
        layout.addWidget(self.status_label)

        self.list_widget = QListWidget()
        self.list_widget.setStyleSheet(
            f"QListWidget {{ background: {CARD}; border: 1px solid #E2E7F1;"
            f" border-radius: 8px; }}"
            f"QListWidget::item {{ padding: 8px 10px; }}")
        layout.addWidget(self.list_widget, 1)

        buttons = QDialogButtonBox()
        self.export_button = buttons.addButton("导出 .ics…", QDialogButtonBox.AcceptRole)
        self.export_button.clicked.connect(self._export_ics)
        buttons.addButton(QDialogButtonBox.Close)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def _load(self) -> None:
        try:
            store = StateStore(self.state_db)
        except Exception:  # pylint: disable=broad-except
            self.status_label.setText("无法打开本地状态库")
            return
        try:
            self._assignments = store.list_assignments(limit=200)
        except Exception:  # pylint: disable=broad-except
            self._assignments = []
        finally:
            store.close()
        self._render()

    def _render(self) -> None:
        self.list_widget.clear()
        now = datetime.datetime.now(datetime.timezone.utc)
        for item in self._assignments:
            due = _parse_due(item.get("due_at") or "")
            name = item.get("name") or "未命名作业"
            course = clean_course_name(item.get("course_name") or f"课程 {item.get('course_id')}")
            if due is None:
                text = f"{name}　·　{course}　·　（无截止时间）"
                color = TEXT_SECONDARY
            else:
                local = due.astimezone()
                stamp = local.strftime("%m-%d %H:%M")
                text = f"【{stamp}】{name}　·　{course}"
                if due < now:
                    color = DANGER
                elif (due - now).days <= 7:
                    color = WARNING
                else:
                    color = ACCENT
            list_item = QListWidgetItem(text)
            list_item.setToolTip(text)
            list_item.setForeground(_qcolor(color))
            self.list_widget.addItem(list_item)
        count = len(self._assignments)
        overdue = sum(
            1 for item in self._assignments
            if _parse_due(item.get("due_at") or "") is not None
            and _parse_due(item.get("due_at") or "") < now
        )
        if count == 0:
            self.status_label.setText("暂无带截止时间的作业。同步后自动从课程采集。")
        else:
            note = f"共 {count} 项待办"
            if overdue:
                note += f"（{overdue} 项已过期，红色标注）"
            self.status_label.setText(note)

    def _export_ics(self) -> None:
        if not self._assignments:
            QMessageBox.information(self, "导出", "没有可导出的作业。")
            return
        target, _ = QFileDialog.getSaveFileName(
            self, "导出日历", "fuxiaoxue-deadlines.ics", "iCalendar (*.ics)")
        if not target:
            return
        try:
            content = _build_ics(self._assignments)
            with open(target, "w", encoding="utf-8", newline="") as handle:
                handle.write(content)
        except OSError as exc:
            QMessageBox.warning(self, "导出失败", f"无法写入文件：{exc}")
            return
        QMessageBox.information(self, "导出", f"已导出 {len(self._assignments)} 项作业到\n{target}")


def _qcolor(token: str):
    from PySide6.QtGui import QColor
    return QColor(token)


def _build_ics(assignments: List[dict]) -> str:
    """构造标准 iCalendar：CRLF 行尾、UTC 时间。"""
    lines = [
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//复小学//elearning sync//ZH",
        "CALSCALE:GREGORIAN",
    ]
    now_stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    for item in assignments:
        due = _parse_due(item.get("due_at") or "")
        if due is None:
            continue
        due_stamp = due.astimezone(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        name = (item.get("name") or "未命名作业").replace(",", r"\,").replace(";", r"\;")
        course = (item.get("course_name") or "").replace(",", r"\,").replace(";", r"\;")
        lines += [
            "BEGIN:VEVENT",
            f"UID:assignment-{item.get('id')}@fuxiaoxue",
            f"DTSTAMP:{now_stamp}",
            f"DTSTART:{due_stamp}",
            f"SUMMARY:{name}（{course}）",
            "END:VEVENT",
        ]
    lines.append("END:VCALENDAR")
    return "\r\n".join(lines) + "\r\n"
