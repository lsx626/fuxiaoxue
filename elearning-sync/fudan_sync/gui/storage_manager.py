# -*- coding: utf-8 -*-
"""存储管理对话框：浏览、筛选、批量删除本地同步的课程文件。

左侧按学期筛选并展示课程列表（含文件数与占用空间），
右侧展示选中课程的文件列表，支持多选、按大小排序与批量删除。
删除操作同时清除本地文件与状态数据库记录。
"""
from __future__ import annotations

import os
from typing import Dict, List, Optional, Set

from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtGui import QColor, QGuiApplication
from PySide6.QtWidgets import (QAbstractItemView, QCheckBox, QDialog,
                               QFrame, QHBoxLayout, QHeaderView, QLabel,
                               QListWidget, QListWidgetItem, QMessageBox,
                               QPushButton, QSizePolicy, QSplitter,
                               QTableWidget, QTableWidgetItem, QVBoxLayout,
                               QWidget)

from ..state import StateStore
from ..utils import format_size
from .icon import app_icon
from .styles import (ACCENT, BG, BORDER, CARD, DANGER, TEXT, TEXT_SECONDARY,
                     WARNING)


class StorageManagerDialog(QDialog):
    """存储管理对话框。

    从 StateStore 读取课程与文件数据，提供学期筛选、课程浏览、
    文件多选和批量删除功能。

    Signals:
        files_deleted: 删除完成后发出，参数为删除的文件数量
    """

    files_deleted = Signal(int)

    def __init__(self, state_store: StateStore, parent=None):
        super().__init__(parent)
        self.store = state_store

        self.setWindowTitle("存储管理 · 复小学")
        self.setWindowIcon(app_icon())
        self.setObjectName("root")
        self.setMinimumSize(960, 620)
        self.resize(1080, 700)

        # 状态数据
        self._all_courses: List[Dict] = []       # 所有课程（带统计信息）
        self._all_terms: List[str] = []          # 所有学期
        self._selected_terms: Set[str] = set()   # 当前勾选的学期
        self._selected_course_id: Optional[int] = None  # 当前选中的课程
        self._sort_by_size_desc: bool = True     # 文件列表是否按大小降序

        self._build_ui()
        self._center()

        # 延迟加载数据，让窗口先显示
        QTimer.singleShot(50, self._load_data)

    # ------------------------------------------------------------------
    # UI 构建
    # ------------------------------------------------------------------
    def _center(self) -> None:
        """窗口居中显示。"""
        screen = QGuiApplication.primaryScreen().availableGeometry()
        self.move(screen.center().x() - self.width() // 2,
                  screen.center().y() - self.height() // 2)

    def _build_ui(self) -> None:
        """构建界面布局。"""
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 14, 16, 14)
        layout.setSpacing(12)

        # ---- 顶部标题 ----
        title_row = QHBoxLayout()
        title_label = QLabel("存储管理")
        title_label.setObjectName("titleLabel")
        subtitle_label = QLabel("管理本地已同步的课程文件，释放磁盘空间")
        subtitle_label.setObjectName("subtitleLabel")

        title_left = QVBoxLayout()
        title_left.setSpacing(2)
        title_left.addWidget(title_label)
        title_left.addWidget(subtitle_label)

        title_row.addLayout(title_left)
        title_row.addStretch()
        layout.addLayout(title_row)

        # ---- 主分割区：左（筛选+课程） / 右（文件列表） ----
        splitter = QSplitter(Qt.Horizontal)
        splitter.setHandleWidth(1)
        splitter.setStyleSheet(f"""
            QSplitter::handle {{
                background: {BORDER};
            }}
        """)

        # 左侧
        splitter.addWidget(self._build_left_panel())
        # 右侧
        splitter.addWidget(self._build_right_panel())

        splitter.setStretchFactor(0, 1)
        splitter.setStretchFactor(1, 2)
        splitter.setSizes([320, 720])

        layout.addWidget(splitter, 1)

        # ---- 底部：统计 + 操作 ----
        layout.addLayout(self._build_bottom_bar())

    def _build_left_panel(self) -> QWidget:
        """构建左侧面板：学期筛选 + 课程列表。"""
        panel = QWidget()
        panel_layout = QVBoxLayout(panel)
        panel_layout.setContentsMargins(0, 0, 0, 0)
        panel_layout.setSpacing(10)

        # 学期筛选卡片
        filter_card = QFrame()
        filter_card.setObjectName("card")
        filter_layout = QVBoxLayout(filter_card)
        filter_layout.setContentsMargins(14, 12, 14, 14)
        filter_layout.setSpacing(8)

        filter_title = QLabel("学期筛选")
        filter_title.setObjectName("cardTitle")
        filter_layout.addWidget(filter_title)

        # 全选/取消按钮行
        btn_row = QHBoxLayout()
        btn_row.setSpacing(6)
        self.select_all_terms_btn = QPushButton("全选")
        self.select_all_terms_btn.setCursor(Qt.PointingHandCursor)
        self.select_all_terms_btn.setStyleSheet("padding: 4px 10px; font-size: 12px;")
        self.select_all_terms_btn.clicked.connect(self._select_all_terms)

        self.deselect_all_terms_btn = QPushButton("清空")
        self.deselect_all_terms_btn.setCursor(Qt.PointingHandCursor)
        self.deselect_all_terms_btn.setStyleSheet("padding: 4px 10px; font-size: 12px;")
        self.deselect_all_terms_btn.clicked.connect(self._deselect_all_terms)

        btn_row.addWidget(self.select_all_terms_btn)
        btn_row.addWidget(self.deselect_all_terms_btn)
        btn_row.addStretch()
        filter_layout.addLayout(btn_row)

        # 学期复选框列表（可滚动）
        self.term_list_widget = QListWidget()
        self.term_list_widget.setFrameShape(QFrame.NoFrame)
        self.term_list_widget.setFixedHeight(120)
        self.term_list_widget.setStyleSheet(f"""
            QListWidget {{
                background: transparent;
                border: none;
                outline: none;
            }}
            QListWidget::item {{
                padding: 2px 2px;
            }}
        """)
        self.term_list_widget.itemChanged.connect(self._on_term_check_changed)
        filter_layout.addWidget(self.term_list_widget)

        panel_layout.addWidget(filter_card)

        # 课程列表卡片
        course_card = QFrame()
        course_card.setObjectName("card")
        course_layout = QVBoxLayout(course_card)
        course_layout.setContentsMargins(14, 12, 14, 14)
        course_layout.setSpacing(8)

        course_title_row = QHBoxLayout()
        course_title = QLabel("课程列表")
        course_title.setObjectName("cardTitle")
        self.course_count_label = QLabel("")
        self.course_count_label.setStyleSheet(f"color: {TEXT_SECONDARY}; font-size: 12px;")

        course_title_row.addWidget(course_title)
        course_title_row.addStretch()
        course_title_row.addWidget(self.course_count_label)
        course_layout.addLayout(course_title_row)

        # 课程列表
        self.course_list_widget = QListWidget()
        self.course_list_widget.setFrameShape(QFrame.NoFrame)
        self.course_list_widget.setStyleSheet(f"""
            QListWidget {{
                background: transparent;
                border: none;
                outline: none;
            }}
            QListWidget::item {{
                padding: 10px 8px;
                border-bottom: 1px solid #EEF1F7;
            }}
            QListWidget::item:selected {{
                background: #EAF0FB;
                color: {TEXT};
                border-radius: 8px;
            }}
        """)
        self.course_list_widget.currentItemChanged.connect(self._on_course_selected)
        course_layout.addWidget(self.course_list_widget, 1)

        panel_layout.addWidget(course_card, 1)

        return panel

    def _build_right_panel(self) -> QWidget:
        """构建右侧面板：文件列表。"""
        panel = QWidget()
        panel_layout = QVBoxLayout(panel)
        panel_layout.setContentsMargins(0, 0, 0, 0)
        panel_layout.setSpacing(10)

        # 文件列表卡片
        file_card = QFrame()
        file_card.setObjectName("card")
        file_layout = QVBoxLayout(file_card)
        file_layout.setContentsMargins(14, 12, 14, 14)
        file_layout.setSpacing(8)

        # 标题行：课程名 + 排序 + 全选
        title_row = QHBoxLayout()

        self.file_title_label = QLabel("选择一门课程查看文件")
        self.file_title_label.setObjectName("cardTitle")
        self.file_title_label.setWordWrap(True)

        self.sort_size_btn = QPushButton("按大小排序 ↓")
        self.sort_size_btn.setCursor(Qt.PointingHandCursor)
        self.sort_size_btn.setStyleSheet("padding: 4px 10px; font-size: 12px;")
        self.sort_size_btn.clicked.connect(self._toggle_sort_by_size)
        self.sort_size_btn.setEnabled(False)

        self.select_all_files_btn = QPushButton("全选文件")
        self.select_all_files_btn.setCursor(Qt.PointingHandCursor)
        self.select_all_files_btn.setStyleSheet("padding: 4px 10px; font-size: 12px;")
        self.select_all_files_btn.clicked.connect(self._toggle_select_all_files)
        self.select_all_files_btn.setEnabled(False)

        title_row.addWidget(self.file_title_label, 1)
        title_row.addWidget(self.sort_size_btn)
        title_row.addWidget(self.select_all_files_btn)
        file_layout.addLayout(title_row)

        # 文件表格
        self.file_table = QTableWidget(0, 4)
        self.file_table.setHorizontalHeaderLabels(["", "文件名", "大小", "状态"])
        self.file_table.setColumnWidth(0, 36)  # 复选框列
        self.file_table.setColumnWidth(2, 90)
        self.file_table.setColumnWidth(3, 90)

        header = self.file_table.horizontalHeader()
        header.setSectionResizeMode(0, QHeaderView.Fixed)
        header.setSectionResizeMode(1, QHeaderView.Stretch)
        header.setSectionResizeMode(2, QHeaderView.Fixed)
        header.setSectionResizeMode(3, QHeaderView.Fixed)

        self.file_table.verticalHeader().setVisible(False)
        self.file_table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.file_table.setSelectionMode(QAbstractItemView.NoSelection)
        self.file_table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.file_table.setShowGrid(False)
        self.file_table.setAlternatingRowColors(False)
        self.file_table.setStyleSheet(f"""
            QTableWidget {{
                background: transparent;
                border: none;
                gridline-color: transparent;
                outline: none;
            }}
            QTableWidget::item {{
                padding: 6px 8px;
                border-bottom: 1px solid #EEF1F7;
            }}
            QHeaderView::section {{
                background: transparent;
                color: {TEXT_SECONDARY};
                font-size: 12px;
                font-weight: 600;
                padding: 4px 8px;
                border: none;
                border-bottom: 1px solid {BORDER};
            }}
        """)
        self.file_table.itemChanged.connect(self._on_file_check_changed)

        file_layout.addWidget(self.file_table, 1)

        panel_layout.addWidget(file_card, 1)

        return panel

    def _build_bottom_bar(self) -> QHBoxLayout:
        """构建底部统计与操作栏。"""
        bar = QHBoxLayout()
        bar.setSpacing(12)

        # 左侧统计信息
        self.stats_label = QLabel("请选择课程以查看文件")
        self.stats_label.setStyleSheet(f"color: {TEXT_SECONDARY}; font-size: 12px;")

        # 右侧操作按钮
        self.delete_btn = QPushButton("删除选中文件")
        self.delete_btn.setObjectName("danger")
        self.delete_btn.setCursor(Qt.PointingHandCursor)
        self.delete_btn.setEnabled(False)
        self.delete_btn.clicked.connect(self._on_delete_clicked)

        self.close_btn = QPushButton("关闭")
        self.close_btn.setObjectName("primary")
        self.close_btn.setCursor(Qt.PointingHandCursor)
        self.close_btn.clicked.connect(self.accept)

        bar.addWidget(self.stats_label)
        bar.addStretch()
        bar.addWidget(self.delete_btn)
        bar.addWidget(self.close_btn)

        return bar

    # ------------------------------------------------------------------
    # 数据加载
    # ------------------------------------------------------------------
    def _load_data(self) -> None:
        """从 StateStore 加载课程和学期数据。"""
        # 加载课程统计信息（含文件数、大小）
        courses = self.store.course_progress()
        self._all_courses = courses

        # 收集所有学期（去重并排序，最新的在前）
        terms_set = set()
        for c in courses:
            term = c.get("term") or "未知学期"
            terms_set.add(term)
        self._all_terms = sorted(terms_set, reverse=True)

        # 默认全选所有学期
        self._selected_terms = set(self._all_terms)

        # 填充学期复选框
        self._populate_term_list()

        # 填充课程列表
        self._populate_course_list()

    def _populate_term_list(self) -> None:
        """填充学期复选框列表。"""
        self.term_list_widget.blockSignals(True)
        self.term_list_widget.clear()

        for term in self._all_terms:
            item = QListWidgetItem(term)
            item.setFlags(item.flags() | Qt.ItemIsUserCheckable)
            item.setCheckState(Qt.Checked if term in self._selected_terms else Qt.Unchecked)
            self.term_list_widget.addItem(item)

        self.term_list_widget.blockSignals(False)

    def _populate_course_list(self) -> None:
        """根据当前学期筛选，填充课程列表。"""
        self.course_list_widget.blockSignals(True)
        self.course_list_widget.clear()

        # 筛选课程
        filtered = [
            c for c in self._all_courses
            if (c.get("term") or "未知学期") in self._selected_terms
        ]

        # 按课程名排序
        filtered.sort(key=lambda c: c.get("name", ""))

        for course in filtered:
            name = course.get("name", "未知课程")
            files_total = course.get("files_total", 0) or 0
            bytes_done = course.get("bytes_downloaded", 0) or 0
            size_str = format_size(bytes_done)

            # 自定义 item 显示
            item = QListWidgetItem()
            item.setData(Qt.UserRole, course["id"])
            item.setToolTip(f"{name}\n共 {files_total} 个文件，已下载 {size_str}")

            # 使用自定义 widget 显示两行信息
            widget = QWidget()
            layout = QVBoxLayout(widget)
            layout.setContentsMargins(4, 2, 4, 2)
            layout.setSpacing(2)

            name_label = QLabel(name)
            name_label.setStyleSheet("font-size: 13px; font-weight: 500;")
            name_label.setWordWrap(True)

            info_label = QLabel(f"{files_total} 个文件 · {size_str}")
            info_label.setStyleSheet(f"color: {TEXT_SECONDARY}; font-size: 11px;")

            layout.addWidget(name_label)
            layout.addWidget(info_label)

            # 计算 item 高度
            item.setSizeHint(widget.sizeHint())

            self.course_list_widget.addItem(item)
            self.course_list_widget.setItemWidget(item, widget)

        self.course_count_label.setText(f"{len(filtered)} 门课程")
        self.course_list_widget.blockSignals(False)

        # 自动选中第一个课程
        if self.course_list_widget.count() > 0:
            self.course_list_widget.setCurrentRow(0)

    def _populate_file_list(self, course_id: int) -> None:
        """加载并显示指定课程的文件列表。"""
        files = self.store.list_files_by_course(course_id)

        # 按大小排序（默认降序）
        files.sort(key=lambda f: f.get("size", 0) or 0,
                   reverse=self._sort_by_size_desc)

        self.file_table.blockSignals(True)
        self.file_table.setRowCount(0)

        for file_info in files:
            row = self.file_table.rowCount()
            self.file_table.insertRow(row)

            # 复选框
            check_item = QTableWidgetItem()
            check_item.setFlags(check_item.flags() | Qt.ItemIsUserCheckable)
            check_item.setCheckState(Qt.Unchecked)
            check_item.setData(Qt.UserRole, file_info["file_id"])
            check_item.setTextAlignment(Qt.AlignCenter)
            self.file_table.setItem(row, 0, check_item)

            # 文件名
            name = file_info.get("display_name") or file_info.get("filename", "未知文件")
            name_item = QTableWidgetItem(name)
            name_item.setToolTip(name)
            self.file_table.setItem(row, 1, name_item)

            # 大小
            size = file_info.get("size", 0) or 0
            size_item = QTableWidgetItem(format_size(size))
            size_item.setTextAlignment(Qt.AlignRight | Qt.AlignVCenter)
            size_item.setData(Qt.UserRole, size)
            self.file_table.setItem(row, 2, size_item)

            # 状态
            status = file_info.get("status", "pending")
            status_text, status_color = self._format_status(status)
            status_item = QTableWidgetItem(status_text)
            status_item.setTextAlignment(Qt.AlignCenter)
            status_item.setForeground(QColor(status_color))
            self.file_table.setItem(row, 3, status_item)

        self.file_table.blockSignals(False)

        # 更新按钮状态
        has_files = len(files) > 0
        self.sort_size_btn.setEnabled(has_files)
        self.select_all_files_btn.setEnabled(has_files)
        self._update_stats()

    def _format_status(self, status: str) -> tuple:
        """将状态码转换为中文显示和颜色。"""
        mapping = {
            "downloaded": ("已下载", "#1E9E63"),
            "pending": ("待下载", "#D98A0B"),
            "failed": ("下载失败", "#D64545"),
            "remote_missing": ("远端已删", "#6B7488"),
        }
        return mapping.get(status, (status, TEXT_SECONDARY))

    # ------------------------------------------------------------------
    # 事件处理
    # ------------------------------------------------------------------
    def _on_term_check_changed(self, item: QListWidgetItem) -> None:
        """学期复选框状态变化。"""
        term = item.text()
        if item.checkState() == Qt.Checked:
            self._selected_terms.add(term)
        else:
            self._selected_terms.discard(term)

        self._populate_course_list()

    def _select_all_terms(self) -> None:
        """全选所有学期。"""
        self._selected_terms = set(self._all_terms)
        self._populate_term_list()
        self._populate_course_list()

    def _deselect_all_terms(self) -> None:
        """清空所有学期选择。"""
        self._selected_terms.clear()
        self._populate_term_list()
        self._populate_course_list()

    def _on_course_selected(self, current: QListWidgetItem,
                            previous: QListWidgetItem) -> None:
        """课程列表选中项变化。"""
        if current is None:
            self._selected_course_id = None
            self.file_title_label.setText("选择一门课程查看文件")
            self.file_table.setRowCount(0)
            self.sort_size_btn.setEnabled(False)
            self.select_all_files_btn.setEnabled(False)
            self._update_stats()
            return

        course_id = current.data(Qt.UserRole)
        self._selected_course_id = course_id

        # 更新标题
        course_data = next(
            (c for c in self._all_courses if c["id"] == course_id), None)
        if course_data:
            name = course_data.get("name", "未知课程")
            total = course_data.get("files_total", 0) or 0
            self.file_title_label.setText(f"{name}（{total} 个文件）")

        self._populate_file_list(course_id)

    def _on_file_check_changed(self, item: QTableWidgetItem) -> None:
        """文件复选框状态变化，更新统计和按钮。"""
        self._update_stats()

    def _toggle_sort_by_size(self) -> None:
        """切换文件按大小排序的方向。"""
        self._sort_by_size_desc = not self._sort_by_size_desc
        self.sort_size_btn.setText(
            "按大小排序 ↓" if self._sort_by_size_desc else "按大小排序 ↑")
        if self._selected_course_id is not None:
            self._populate_file_list(self._selected_course_id)

    def _toggle_select_all_files(self) -> None:
        """全选或取消全选当前课程的所有文件。"""
        if self.file_table.rowCount() == 0:
            return

        # 检查当前是否全选
        all_checked = True
        for row in range(self.file_table.rowCount()):
            item = self.file_table.item(row, 0)
            if item.checkState() != Qt.Checked:
                all_checked = False
                break

        # 切换状态
        target = Qt.Unchecked if all_checked else Qt.Checked
        self.file_table.blockSignals(True)
        for row in range(self.file_table.rowCount()):
            item = self.file_table.item(row, 0)
            item.setCheckState(target)
        self.file_table.blockSignals(False)

        self.select_all_files_btn.setText("取消全选" if target == Qt.Checked else "全选文件")
        self._update_stats()

    def _update_stats(self) -> None:
        """更新底部统计信息和删除按钮状态。"""
        selected_count = 0
        selected_size = 0
        total_count = self.file_table.rowCount()
        total_size = 0

        for row in range(self.file_table.rowCount()):
            size_item = self.file_table.item(row, 2)
            size = size_item.data(Qt.UserRole) or 0
            total_size += size

            check_item = self.file_table.item(row, 0)
            if check_item.checkState() == Qt.Checked:
                selected_count += 1
                selected_size += size

        if total_count == 0:
            self.stats_label.setText("请选择课程以查看文件")
        else:
            self.stats_label.setText(
                f"共 {total_count} 个文件（{format_size(total_size)}），"
                f"已选中 {selected_count} 个（{format_size(selected_size)}）"
            )

        self.delete_btn.setEnabled(selected_count > 0)

    # ------------------------------------------------------------------
    # 删除操作
    # ------------------------------------------------------------------
    def _on_delete_clicked(self) -> None:
        """点击删除按钮：确认后执行批量删除。"""
        # 收集选中的文件
        selected_files = []
        for row in range(self.file_table.rowCount()):
            check_item = self.file_table.item(row, 0)
            if check_item.checkState() == Qt.Checked:
                file_id = check_item.data(Qt.UserRole)
                name_item = self.file_table.item(row, 1)
                size_item = self.file_table.item(row, 2)
                selected_files.append({
                    "file_id": file_id,
                    "name": name_item.text(),
                    "size": size_item.data(Qt.UserRole) or 0,
                })

        if not selected_files:
            return

        total_size = sum(f["size"] for f in selected_files)
        count = len(selected_files)

        # 确认对话框
        confirm = QMessageBox.question(
            self, "确认删除",
            f"确定要删除选中的 {count} 个文件吗？\n\n"
            f"将释放 {format_size(total_size)} 磁盘空间。\n"
            f"本地文件和同步记录都会被删除，下次同步时会重新下载。",
            QMessageBox.Yes | QMessageBox.No,
            QMessageBox.No,
        )
        if confirm != QMessageBox.Yes:
            return

        # 执行删除
        deleted = self._delete_files(selected_files)

        if deleted > 0:
            QMessageBox.information(
                self, "删除完成",
                f"已删除 {deleted} 个文件，释放 {format_size(total_size)} 空间。")
            self.files_deleted.emit(deleted)

            # 刷新数据
            self._load_data()

    def _delete_files(self, files: List[Dict]) -> int:
        """批量删除文件（本地文件 + 数据库记录）。

        Returns:
            成功删除的文件数量
        """
        deleted_count = 0

        for file_info in files:
            file_id = file_info["file_id"]

            # 先获取本地路径
            file_record = self.store.get_file(file_id)
            if not file_record:
                continue

            local_path = file_record.get("local_path")

            # 删除本地文件
            if local_path and os.path.exists(local_path):
                try:
                    os.remove(local_path)
                except OSError:
                    # 删除失败也继续尝试删除数据库记录
                    pass

            # 从数据库删除记录（同时清搜索索引）
            try:
                self.store.delete_file(file_id)
                deleted_count += 1
            except Exception:  # pylint: disable=broad-except
                pass

        return deleted_count
