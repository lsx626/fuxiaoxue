"""全局搜索对话框：跨课程搜索本地文件（文件名 + 已抽取的正文内容）。

搜索范围是**已下载到本地的文件**；远端已删除（remote_missing）与从未下载的
文件不出现在结果里（没有本地副本谈不上「找到这份资料」）。

搜索索引由同步引擎在下载完成与同步收尾时增量建立（`StateStore.files_fts`，
CJK 预分词见 `fudan_sync/search_index.py`）；刚升级到带搜索的版本时，老库
需要等回填完成后才有正文命中，期间文件名搜索已经可用。
"""
from __future__ import annotations

from typing import List, Optional

from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtWidgets import (QDialog, QDialogButtonBox, QHBoxLayout, QLabel,
                               QLineEdit, QListWidget, QListWidgetItem,
                               QVBoxLayout, QWidget)

from ..state import StateStore
from ..utils import clean_course_name, format_size
from .styles import (ACCENT, CARD, TEXT, TEXT_SECONDARY)

_DEBOUNCE_MS = 250
_MAX_RESULTS = 200


class SearchDialog(QDialog):
    """输入关键字、列出命中文件，双击或回车打开。

    打开方式由主窗口决定（直接预览 / 定位到课程文件列表），对话框本身只
    通过 ``file_open_requested`` 传出 file_id，不持有任何业务对象。
    """

    file_open_requested = Signal(int)

    def __init__(self, state_db: str, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setWindowTitle("搜索本地课程文件")
        self.setMinimumSize(640, 460)
        self.resize(720, 560)
        self.state_db = state_db
        self._results: List[dict] = []
        self._timer = QTimer(self)
        self._timer.setSingleShot(True)
        self._timer.setInterval(_DEBOUNCE_MS)
        self._timer.timeout.connect(self._run_search)
        self._build_ui()
        # WA_DeleteOnClose：与设置/存储管理/预览对话框同一模式，常驻托盘的
        # 应用反复打开搜索不会累积隐藏窗口树。
        self.setAttribute(Qt.WA_DeleteOnClose, True)

    # ------------------------------------------------------------------
    def _build_ui(self) -> None:
        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 18, 20, 16)
        layout.setSpacing(10)

        hint = QLabel("输入关键字搜索全部已下载课程的文件名与内容（中文可搜，"
                      "如「神经网络」「傅里叶」）")
        hint.setStyleSheet(f"color: {TEXT_SECONDARY}; font-size: 12px;")
        layout.addWidget(hint)

        self.query_edit = QLineEdit()
        self.query_edit.setPlaceholderText("搜索文件名或内容…")
        self.query_edit.setMinimumHeight(38)
        self.query_edit.setStyleSheet(
            f"QLineEdit {{ background: {CARD}; border: 1px solid #E2E7F1;"
            f" border-radius: 8px; padding: 6px 12px; color: {TEXT}; font-size: 14px; }}"
            f"QLineEdit:focus {{ border: 1px solid {ACCENT}; }}")
        self.query_edit.textChanged.connect(self._schedule_search)
        self.query_edit.returnPressed.connect(self._open_selected)
        layout.addWidget(self.query_edit)

        self.status_label = QLabel("")
        self.status_label.setStyleSheet(f"color: {TEXT_SECONDARY}; font-size: 12px;")
        layout.addWidget(self.status_label)

        self.result_list = QListWidget()
        self.result_list.setSpacing(2)
        self.result_list.setStyleSheet(
            f"QListWidget {{ background: {CARD}; border: 1px solid #E2E7F1;"
            f" border-radius: 8px; }}"
            f"QListWidget::item {{ padding: 8px 10px; }}"
            f"QListWidget::item:selected {{ background: #EEF0FF; }}")
        self.result_list.itemDoubleClicked.connect(self._open_item)
        self.result_list.itemActivated.connect(self._open_item)
        layout.addWidget(self.result_list, 1)

        buttons = QDialogButtonBox(QDialogButtonBox.Close)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    # ------------------------------------------------------------------
    def _schedule_search(self) -> None:
        self._timer.start()

    def keyPressEvent(self, event) -> None:  # noqa: N802 - Qt API name
        if event.key() == Qt.Key_Escape:
            self.reject()
            return
        super().keyPressEvent(event)

    def _open_state(self) -> Optional[StateStore]:
        try:
            return StateStore(self.state_db)
        except Exception:  # pylint: disable=broad-except
            return None

    def _run_search(self) -> None:
        query = self.query_edit.text().strip()
        if not query:
            self._results = []
            self.result_list.clear()
            self.status_label.setText("输入关键字开始搜索")
            return
        self.status_label.setText("正在搜索…")
        state = self._open_state()
        if state is None:
            self.status_label.setText("无法打开本地状态库")
            return
        try:
            results = state.search_files(query, limit=_MAX_RESULTS)
            pending = len(state.unindexed_downloaded_files(limit=1))
        except Exception:  # pylint: disable=broad-except
            self.status_label.setText("搜索出错，请稍后重试")
            return
        finally:
            state.close()
        self._results = results
        self._render_results(pending)

    def _render_results(self, pending_count: int) -> None:
        self.result_list.clear()
        for hit in self._results:
            title = hit.get("display_name") or hit.get("filename") or "未知文件"
            course = clean_course_name(
                hit.get("course_name") or f"课程 {hit.get('course_id')}")
            size = format_size(hit.get("size") or 0)
            snippet = hit.get("snippet")
            first = f"{title}　·　{course}　·　{size}"
            item = QListWidgetItem(first if not snippet else f"{first}\n{snippet}")
            item.setToolTip(first)
            item.setData(Qt.UserRole, hit.get("file_id"))
            self.result_list.addItem(item)
        count = len(self._results)
        if count == 0:
            self.status_label.setText("没有命中文件。注意：搜索只覆盖已下载的本地文件。")
        else:
            note = f"共 {count} 项结果"
            if pending_count:
                # 老库还在回填：正文命中不全，必须告诉用户为什么
                note += f"（另有 {pending_count} 个已下载文件尚未完成内容索引）"
            self.status_label.setText(note)

    # ------------------------------------------------------------------
    def _selected_file_id(self) -> Optional[int]:
        item = self.result_list.currentItem() or self.result_list.item(0)
        if item is None:
            return None
        value = item.data(Qt.UserRole)
        try:
            return int(value)
        except (TypeError, ValueError):
            return None

    def _open_item(self, item) -> None:
        file_id = self._selected_file_id()
        if file_id is not None:
            self.file_open_requested.emit(file_id)
            self.accept()

    def _open_selected(self) -> None:
        file_id = self._selected_file_id()
        if file_id is not None:
            self.file_open_requested.emit(file_id)
            self.accept()
