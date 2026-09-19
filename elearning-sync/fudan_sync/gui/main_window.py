"""主界面：总览、课程列表、文件预览与自动同步调度。"""
from __future__ import annotations

import datetime
import os
import webbrowser
from typing import Optional
from weakref import ref

from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtGui import (QAction, QColor, QFont, QFontMetrics, QGuiApplication,
                           QTextCursor)
from PySide6.QtWidgets import (QAbstractItemView, QFrame,
                               QGridLayout, QHBoxLayout, QHeaderView, QLabel,
                               QMainWindow, QMenu, QMessageBox, QProgressBar,
                               QPushButton, QSplitter, QStatusBar, QTableWidget,
                               QTableWidgetItem, QTextEdit, QVBoxLayout, QWidget,
                               QSizePolicy)

from .. import __version__
from ..auth import AuthError
from ..config import load_config
from ..state import StateStore
from ..utils import format_size, sanitize_path_component
from .config_io import load_gui_state, save_gui_state
from .icon import app_icon
from .login_window import LoginWindow
from .notifier import WindowsNotifier
from .previewer import DocumentPreviewDialog
from .settings_dialog import SettingsDialog
from .sharing import show_share_menu
from .storage_manager import StorageManagerDialog
from .styles import (ACCENT, BG, CARD, SUCCESS, TEXT, TEXT_SECONDARY, WARNING, DANGER)
from .tray import TrayController
from .workers import SilentLoginWorker, SyncWorker


def _parse_iso(value: str) -> Optional[datetime.datetime]:
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


def _relative_time(value: str) -> str:
    """把 ISO 时间转成“x 小时前”这种相对描述。"""
    parsed = _parse_iso(value)
    if parsed is None:
        return "—"
    now = datetime.datetime.now(datetime.timezone.utc)
    delta = now - parsed
    seconds = int(delta.total_seconds())
    if seconds < 0:
        return "刚刚"
    if seconds < 60:
        return f"{seconds} 秒前"
    if seconds < 3600:
        return f"{seconds // 60} 分钟前"
    if seconds < 86400:
        return f"{seconds // 3600} 小时前"
    if seconds < 86400 * 30:
        return f"{seconds // 86400} 天前"
    return parsed.astimezone().strftime("%Y-%m-%d")


class ElidedLabel(QLabel):
    """单行文本标签：空间不足时省略末尾，并保留完整文本提示。"""

    def __init__(self, text: str = "", parent: Optional[QWidget] = None):
        self._full_text = ""
        super().__init__(parent)
        self.setSizePolicy(QSizePolicy.Ignored, QSizePolicy.Preferred)
        self.setText(text)

    def setText(self, text: str) -> None:  # noqa: N802 - Qt API name
        self._full_text = str(text or "")
        self._refresh_elided_text()

    def text(self) -> str:  # noqa: N802 - Qt API name
        """返回未截断文本，便于登录状态和辅助功能读取。"""
        return self._full_text

    def resizeEvent(self, event) -> None:  # noqa: N802 - Qt API name
        super().resizeEvent(event)
        self._refresh_elided_text()

    def _refresh_elided_text(self) -> None:
        width = max(0, self.contentsRect().width())
        if width:
            rendered = QFontMetrics(self.font()).elidedText(
                self._full_text, Qt.ElideRight, width)
        else:
            rendered = self._full_text
        if QLabel.text(self) != rendered:
            QLabel.setText(self, rendered)
        self.setToolTip(self._full_text if rendered != self._full_text else "")


class MainWindow(QMainWindow):
    """主窗口。启动时先静默登录，失败则弹出登录引导窗。"""

    login_needed = Signal()

    def __init__(self, config_path: str, start_minimized: bool = False):
        super().__init__()
        self.cfg = load_config(config_path)
        # Keep every later settings/auth write anchored to the same normalized
        # file that load_config read, including when --config was relative.
        self.config_path = self.cfg.config_path
        self.start_minimized = start_minimized
        self.sync_worker: Optional[SyncWorker] = None
        self.login_worker: Optional[SilentLoginWorker] = None
        self.login_window: Optional[LoginWindow] = None
        self.settings_dialog: Optional[SettingsDialog] = None
        self.storage_dialog: Optional[StorageManagerDialog] = None
        self.preview_dialog: Optional[DocumentPreviewDialog] = None
        self.user: dict = {}
        self._auto_sync_remaining = 0  # 距下次自动同步的秒数
        self._course_index = 0         # 当前同步到的课程序号（进度条用）
        self._quitting = False
        self._quit_pending = False
        self._current_file_course_id: Optional[int] = None  # 当前展开文件列表的课程 ID

        self.setObjectName("root")
        self.setWindowTitle(f"复小学 v{__version__}")
        self.setWindowIcon(app_icon())
        self.setMinimumSize(880, 620)
        self._restore_geometry()

        self.tray = TrayController(self)
        self._wire_tray()

        self.notifier = WindowsNotifier(self)

        self._build_ui()
        self._refresh_stats()
        self._start_auto_timer()

        if self._has_stored_credentials():
            self._begin_silent_login()
        else:
            self.user_chip.setText("未登录")
            # 延迟到主窗口显示后再弹登录框，确保对话框有可见父窗口
            QTimer.singleShot(200, self._show_login_window)

    # ==================================================================
    # 布局
    # ==================================================================
    def _build_ui(self) -> None:
        central = QWidget()
        central.setObjectName("root")
        layout = QVBoxLayout(central)
        layout.setContentsMargins(24, 18, 24, 14)
        layout.setSpacing(16)

        layout.addWidget(self._build_header())
        layout.addWidget(self._build_stats_row())
        layout.addWidget(self._build_sync_bar())
        layout.addWidget(self._build_course_splitter(), 1)
        layout.addWidget(self._build_log_panel())

        self.setCentralWidget(central)
        self.setStatusBar(self._build_status_bar())

    def _build_header(self) -> QWidget:
        header = QFrame()
        header_layout = QHBoxLayout(header)
        header_layout.setContentsMargins(2, 0, 2, 0)
        header_layout.setSpacing(12)

        icon_label = QLabel()
        icon_label.setPixmap(app_icon().pixmap(40, 40))
        header_layout.addWidget(icon_label)

        title_container = QWidget()
        title_container.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)
        titles = QVBoxLayout(title_container)
        titles.setSpacing(1)
        title = QLabel("复小学")
        title.setObjectName("titleLabel")
        subtitle = ElidedLabel(
            f"{self.cfg.base_url.replace('https://', '')} · 本地目录 {self.cfg.root_dir}")
        subtitle.setObjectName("subtitleLabel")
        titles.addWidget(title)
        titles.addWidget(subtitle)
        header_layout.addWidget(title_container, 1)

        self.user_chip = ElidedLabel("连接中…")
        # 姓名可能较长（含学号/全名），给足宽度并保留省略+tooltip 兜底，
        # 不能把常见姓名截成一半还显示“已登录”。
        self.user_chip.setMinimumWidth(96)
        self.user_chip.setMaximumWidth(260)
        self.user_chip.setSizePolicy(QSizePolicy.Preferred, QSizePolicy.Fixed)
        self.user_chip.setStyleSheet(
            f"background: {CARD}; border: 1px solid #E2E7F1; border-radius: 12px;"
            f"padding: 5px 12px; color: {TEXT_SECONDARY};")
        header_layout.addWidget(self.user_chip)

        for text, handler in (("打开同步目录", self._open_root_dir),
                              ("存储管理", self._open_storage_manager),
                              ("设置", self._open_settings),
                              ("日志", self._toggle_log_panel)):
            button = QPushButton(text)
            button.setCursor(Qt.PointingHandCursor)
            button.setMinimumHeight(34)
            button.clicked.connect(handler)
            header_layout.addWidget(button)
        return header

    def _build_stats_row(self) -> QWidget:
        row = QFrame()
        grid = QGridLayout(row)
        grid.setContentsMargins(0, 0, 0, 0)
        grid.setSpacing(12)

        self.stat_course = self._stat_card(grid, 0, "课程", "0")
        self.stat_files = self._stat_card(grid, 1, "已下载文件", "0")
        self.stat_size = self._stat_card(grid, 2, "本地容量", "0 B")
        self.stat_last = self._stat_card(grid, 3, "最近同步", "从未")
        for column in range(4):
            grid.setColumnStretch(column, 1)
        return row

    @staticmethod
    def _stat_card(parent_grid, column: int, caption: str, value: str) -> QLabel:
        card = QFrame()
        card.setObjectName("card")
        layout = QVBoxLayout(card)
        layout.setContentsMargins(18, 14, 18, 14)
        layout.setSpacing(4)
        value_label = QLabel(value)
        value_label.setObjectName("statValue")
        caption_label = QLabel(caption)
        caption_label.setObjectName("statCaption")
        layout.addWidget(caption_label)
        layout.addWidget(value_label)
        parent_grid.addWidget(card, 0, column)
        return value_label

    def _build_sync_bar(self) -> QWidget:
        bar = QFrame()
        bar.setObjectName("card")
        bar.setMinimumHeight(68)
        bar.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        layout = QHBoxLayout(bar)
        layout.setContentsMargins(18, 12, 18, 12)
        layout.setSpacing(14)

        self.sync_state_label = QLabel("准备就绪")
        self.sync_state_label.setStyleSheet(f"font-weight: 600; color: {TEXT};")
        self.sync_detail_label = ElidedLabel("")
        self.sync_detail_label.setStyleSheet(f"color: {TEXT_SECONDARY};")

        text_column = QVBoxLayout()
        text_column.setSpacing(2)
        text_column.addWidget(self.sync_state_label)
        text_column.addWidget(self.sync_detail_label)
        layout.addLayout(text_column, 1)

        self.progress_bar = QProgressBar()
        self.progress_bar.setMinimumWidth(120)
        self.progress_bar.setMaximumWidth(220)
        self.progress_bar.setSizePolicy(QSizePolicy.Preferred, QSizePolicy.Fixed)
        self.progress_bar.setRange(0, 100)
        self.progress_bar.setValue(0)
        self.progress_bar.setVisible(False)
        layout.addWidget(self.progress_bar)

        action_layout = QHBoxLayout()
        action_layout.setContentsMargins(0, 0, 0, 0)
        action_layout.setSpacing(8)

        self.sync_button = QPushButton("立即同步")
        self.sync_button.setObjectName("primary")
        self.sync_button.setCursor(Qt.PointingHandCursor)
        self.sync_button.setMinimumSize(100, 36)
        self.sync_button.setSizePolicy(QSizePolicy.Fixed, QSizePolicy.Fixed)
        self.sync_button.setToolTip("立即执行一次增量同步")
        self.sync_button.clicked.connect(lambda: self._start_sync(full=False))
        action_layout.addWidget(self.sync_button)

        self.full_sync_button = QPushButton("全量同步")
        self.full_sync_button.setCursor(Qt.PointingHandCursor)
        self.full_sync_button.setMinimumSize(100, 36)
        self.full_sync_button.setSizePolicy(QSizePolicy.Fixed, QSizePolicy.Fixed)
        self.full_sync_button.setToolTip("忽略本地状态，重新检查全部课程")
        self.full_sync_button.clicked.connect(self._confirm_full_sync)
        action_layout.addWidget(self.full_sync_button)

        self.stop_button = QPushButton("停止")
        self.stop_button.setObjectName("danger")
        self.stop_button.setCursor(Qt.PointingHandCursor)
        self.stop_button.setMinimumSize(72, 36)
        self.stop_button.setSizePolicy(QSizePolicy.Fixed, QSizePolicy.Fixed)
        self.stop_button.clicked.connect(self._stop_sync)
        self.stop_button.setVisible(False)
        action_layout.addWidget(self.stop_button)
        layout.addLayout(action_layout)
        return bar

    def _build_course_splitter(self) -> QWidget:
        """构建课程区 + 文件列表的垂直分割器。

        上半部分为课程表格，下半部分为选中课程的文件列表（默认隐藏）。
        双击课程行展开/折叠文件列表。
        """
        card = QFrame()
        card.setObjectName("card")
        layout = QVBoxLayout(card)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        self.course_splitter = QSplitter(Qt.Vertical)
        self.course_splitter.setHandleWidth(1)
        self.course_splitter.setStyleSheet(f"""
            QSplitter::handle {{
                background: #EEF1F7;
            }}
        """)

        # 上半：课程表格（带标题栏）
        course_panel = QWidget()
        course_layout = QVBoxLayout(course_panel)
        course_layout.setContentsMargins(0, 0, 0, 0)
        course_layout.setSpacing(0)

        header_row = QHBoxLayout()
        header_row.setContentsMargins(18, 12, 18, 4)
        title = QLabel("课程")
        title.setObjectName("cardTitle")
        header_row.addWidget(title)
        header_row.addStretch()
        self.course_count_label = QLabel("")
        self.course_count_label.setObjectName("hint")
        header_row.addWidget(self.course_count_label)
        course_layout.addLayout(header_row)

        self.course_table = QTableWidget(0, 7)
        self.course_table.setHorizontalHeaderLabels(
            ["课程", "学期", "文件数", "已下载", "本地大小", "最近同步", "ID"])
        self.course_table.setColumnHidden(6, True)
        self.course_table.verticalHeader().setVisible(False)
        self.course_table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.course_table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.course_table.setFocusPolicy(Qt.NoFocus)
        self.course_table.setShowGrid(False)
        self.course_table.setAlternatingRowColors(False)
        self.course_table.setContextMenuPolicy(Qt.CustomContextMenu)
        self.course_table.customContextMenuRequested.connect(self._course_context_menu)
        self.course_table.doubleClicked.connect(self._toggle_file_list)
        self.course_table.itemSelectionChanged.connect(self._on_course_selection_changed)

        header = self.course_table.horizontalHeader()
        header.setSectionResizeMode(0, QHeaderView.Stretch)
        for column in range(1, 6):
            header.setSectionResizeMode(column, QHeaderView.ResizeToContents)
        course_layout.addWidget(self.course_table)

        self.course_splitter.addWidget(course_panel)

        # 下半：文件列表（默认隐藏/折叠）
        self.file_panel = QWidget()
        file_layout = QVBoxLayout(self.file_panel)
        file_layout.setContentsMargins(0, 0, 0, 0)
        file_layout.setSpacing(0)

        file_header_row = QHBoxLayout()
        file_header_row.setContentsMargins(18, 10, 18, 4)
        self.file_panel_title = QLabel("课程文件")
        self.file_panel_title.setObjectName("cardTitle")
        file_header_row.addWidget(self.file_panel_title)
        file_header_row.addStretch()
        self.file_count_label = QLabel("")
        self.file_count_label.setObjectName("hint")
        file_header_row.addWidget(self.file_count_label)
        file_layout.addLayout(file_header_row)

        self.file_table = QTableWidget(0, 4)
        self.file_table.setHorizontalHeaderLabels(["文件名", "大小", "状态", "下载时间"])
        self.file_table.verticalHeader().setVisible(False)
        self.file_table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.file_table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.file_table.setFocusPolicy(Qt.NoFocus)
        self.file_table.setShowGrid(False)
        self.file_table.setAlternatingRowColors(False)
        self.file_table.setContextMenuPolicy(Qt.CustomContextMenu)
        self.file_table.customContextMenuRequested.connect(self._file_context_menu)
        self.file_table.doubleClicked.connect(self._preview_selected_file)

        file_header = self.file_table.horizontalHeader()
        file_header.setSectionResizeMode(0, QHeaderView.Stretch)
        for column in range(1, 4):
            file_header.setSectionResizeMode(column, QHeaderView.ResizeToContents)
        file_layout.addWidget(self.file_table)

        self.course_splitter.addWidget(self.file_panel)
        self.course_splitter.setStretchFactor(0, 3)
        self.course_splitter.setStretchFactor(1, 2)

        # 默认隐藏文件列表
        self.file_panel.setVisible(False)
        self._file_panel_visible = False

        layout.addWidget(self.course_splitter)
        return card

    def _build_log_panel(self) -> QWidget:
        """同步日志面板：默认折叠，点击"日志"展开。

        SyncWorker 已经通过 log 信号输出详细日志，此前一直被丢弃；
        这里把它接到界面上，方便用户排查同步 / 登录问题。
        """
        card = QFrame()
        card.setObjectName("card")
        layout = QVBoxLayout(card)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        header_row = QHBoxLayout()
        header_row.setContentsMargins(18, 10, 18, 4)
        title = QLabel("同步日志")
        title.setObjectName("cardTitle")
        header_row.addWidget(title)
        header_row.addStretch()
        hint = QLabel("仅显示本次运行以来的日志")
        hint.setObjectName("hint")
        header_row.addWidget(hint)
        clear_button = QPushButton("清空")
        clear_button.setCursor(Qt.PointingHandCursor)
        clear_button.setStyleSheet("padding: 4px 10px; font-size: 12px;")
        clear_button.clicked.connect(lambda: self.log_view.clear())
        header_row.addWidget(clear_button)
        layout.addLayout(header_row)

        self.log_view = QTextEdit()
        self.log_view.setObjectName("logView")
        self.log_view.setReadOnly(True)
        self.log_view.setMaximumHeight(180)
        self.log_view.setPlaceholderText("暂无日志，同步开始后这里会显示详细信息")
        layout.addWidget(self.log_view)

        self.log_panel = card
        self._log_visible = False
        card.setVisible(False)
        return card

    def _toggle_log_panel(self) -> None:
        self._log_visible = not self._log_visible
        self.log_panel.setVisible(self._log_visible)

    def _on_log(self, level: str, message: str) -> None:
        """把后台线程的日志写入面板（自动滚动、限制行数）。"""
        if not hasattr(self, "log_view"):
            return
        stamp = datetime.datetime.now().strftime("%H:%M:%S")
        self.log_view.append(f"[{stamp}] [{level.upper()}] {message}")
        bar = self.log_view.verticalScrollBar()
        bar.setValue(bar.maximum())
        doc = self.log_view.document()
        excess = doc.blockCount() - 2000
        if excess > 0:
            cursor = self.log_view.textCursor()
            cursor.movePosition(QTextCursor.Start)
            cursor.movePosition(QTextCursor.Down, QTextCursor.KeepAnchor, excess)
            cursor.removeSelectedText()

    def _build_status_bar(self) -> QStatusBar:
        bar = QStatusBar()
        bar.setSizeGripEnabled(False)
        self.countdown_label = QLabel("自动同步已启用")
        bar.addPermanentWidget(self.countdown_label)
        return bar

    # ==================================================================
    # 登录
    # ==================================================================
    def _has_stored_credentials(self) -> bool:
        """判断是否已有可用于静默登录的凭据。"""
        method = (self.cfg.auth_method or "").lower()
        if method in ("", "token") and not self.cfg.token:
            # 老配置 method 为空时 load_config 会回退成 "token"；若钥匙串里有
            # 密码，就按密码登录处理，避免老用户每次启动都被要求重新登录。
            from ..password_login import has_stored_password
            if self.cfg.uis_username and has_stored_password(self.cfg.uis_username):
                return True
            # 没记住密码但会话 cookie 还在：交给静默登录验证有效性即可
            return os.path.exists(self.cfg.cookie_file)
        if method == "token":
            return bool(self.cfg.token)
        if method == "password":
            from ..password_login import has_stored_password
            if self.cfg.uis_username and has_stored_password(self.cfg.uis_username):
                return True
            return os.path.exists(self.cfg.cookie_file)
        if method in ("cookie", "browser"):
            return os.path.exists(self.cfg.cookie_file)
        return False

    def _begin_silent_login(self) -> None:
        self.login_worker = SilentLoginWorker(self.cfg)
        self.login_worker.succeeded.connect(self._on_login_ok)
        self.login_worker.failed.connect(self._on_login_failed)
        self.login_worker.start()

    def _on_login_ok(self, user: dict) -> None:
        self.user = user
        self.user_chip.setText(f"👤 {user.get('name') or user.get('username', '')}")
        # 从未同步过的用户，打开后直接开始首次同步
        if not os.path.exists(self.cfg.state_db):
            self._start_sync(full=False)

    def _on_login_failed(self, message: str) -> None:
        self.user_chip.setText("未登录")
        self._show_login_window()

    def _show_login_window(self) -> None:
        if self.login_window is not None and self.login_window.isVisible():
            self.login_window.raise_()
            self.login_window.activateWindow()
            return
        self.login_window = LoginWindow(self.cfg, parent=None)  # 独立顶层窗口
        self.login_window.logged_in.connect(self._on_first_login)
        self.login_window.show()
        self.login_window.raise_()
        self.login_window.activateWindow()

    def _on_first_login(self, user: dict) -> None:
        """首次登录成功：把方式写回配置，之后自动登录。"""
        from .config_io import update_config
        method = "password" if user.get("remember") else "cookie"
        update_config(self.config_path, [
            (("auth", "method"), method),
            (("auth", "uis_username"), user.get("username", "")),
        ])
        self.cfg = load_config(self.config_path)
        if self.login_window is not None:
            self.login_window.close()
            self.login_window = None
        self._on_login_ok(user)
        self._start_sync(full=False)

    # ==================================================================
    # 同步
    # ==================================================================
    def _start_sync(self, full: bool = False, course_ids=None) -> None:
        if self.sync_worker is not None and self.sync_worker.isRunning():
            return
        if not self.user:
            self._show_login_window()
            return
        self.cfg = load_config(self.config_path)  # 每次同步前重读配置
        self.sync_worker = SyncWorker(self.cfg, full=full, course_ids=course_ids)
        self.sync_worker.progress.connect(self._on_progress)
        self.sync_worker.finished_ok.connect(self._on_sync_finished)
        self.sync_worker.failed.connect(self._on_sync_failed)
        self.sync_worker.log.connect(self._on_log)
        self._set_busy(True, full=full)
        self._on_log("info", f"开始{'全量' if full else '增量'}同步…")
        self.sync_worker.start()

    def _stop_sync(self) -> None:
        if self.sync_worker is not None:
            self.sync_worker.stop()

    def _confirm_full_sync(self) -> None:
        confirm = QMessageBox.question(
            self, "全量同步",
            "将忽略本地状态，重新比对并下载全部课程内容（文件已存在且未变更时会自动跳过，"
            "不会重复下载）。确认继续吗？",
            QMessageBox.Yes | QMessageBox.No, QMessageBox.No)
        if confirm == QMessageBox.Yes:
            self._start_sync(full=True)

    def _set_busy(self, busy: bool, full: bool = False) -> None:
        self.sync_button.setEnabled(not busy)
        self.full_sync_button.setEnabled(not busy)
        self.stop_button.setVisible(busy)
        self.progress_bar.setVisible(busy)
        self.tray.set_busy(busy)
        if busy:
            self.sync_state_label.setText("正在全量同步…" if full else "正在同步…")
            self.sync_detail_label.setText("连接平台中")
            self.progress_bar.setValue(0)
        else:
            self.sync_state_label.setText("准备就绪")
            self.sync_detail_label.setText("")

    def _on_progress(self, kind: str, payload: dict) -> None:
        if kind == "phase":
            self.sync_detail_label.setText(payload.get("text", ""))
            return
        if kind == "course_start":
            total = payload.get("total", 0)
            index = payload.get("index", 0)
            self._course_index = index
            name = payload.get("name", "")
            self.sync_state_label.setText(f"正在同步课程（{index}/{total}）")
            self.sync_detail_label.setText(name)
            if total:
                self.progress_bar.setRange(0, total * 100)
                self.progress_bar.setValue((index - 1) * 100)
            return
        if kind == "course_done":
            index = getattr(self, "_course_index", 0) or 0
            if self.progress_bar.maximum() > 0:
                self.progress_bar.setValue(index * 100)
            return
        if kind == "course_download_start":
            pending = payload.get("pending", 0)
            if pending:
                size = format_size(payload.get("bytes", 0))
                self.sync_detail_label.setText(
                    f"{payload.get('name', '')}：下载 {pending} 个文件（{size}）")
            return

    def _on_sync_finished(self, stats: dict) -> None:
        self._set_busy(False)
        downloaded = stats.get("files_downloaded", 0)
        bytes_downloaded = stats.get("bytes_downloaded", 0)
        if downloaded:
            # Windows 桌面通知（点击可打开同步目录）；托盘提示作为无通知组件时的兜底
            self.notifier.notify(
                "同步完成",
                f"新增 {downloaded} 个文件，共 {format_size(bytes_downloaded)}",
                open_path=self.cfg.root_dir,
            )
        self._refresh_stats()
        self._reset_countdown()
        if stats.get("errors"):
            self._on_log("warning", f"本轮同步有 {stats['errors']} 个错误，详见日志面板")
            self._log_visible = True
            self.log_panel.setVisible(True)
        # 如果当前展开了文件列表，同步后刷新
        if self._file_panel_visible and self._current_file_course_id is not None:
            self._populate_file_list(self._current_file_course_id)

    def _on_sync_failed(self, message: str) -> None:
        self._set_busy(False)
        self._on_log("error", f"同步失败：{message}")
        self._log_visible = True
        self.log_panel.setVisible(True)
        self.tray.notify("同步失败", message)
        if "登录" in message or "密码" in message or "认证" in message:
            self._show_login_window()

    # ==================================================================
    # 自动同步
    # ==================================================================
    def _start_auto_timer(self) -> None:
        self.auto_timer = QTimer(self)
        self.auto_timer.setInterval(1000)  # 每秒倒计时
        self.auto_timer.timeout.connect(self._tick)
        self._reset_countdown()
        self.auto_timer.start()

    def _reset_countdown(self) -> None:
        self._auto_sync_remaining = max(60, self.cfg.sync.interval_minutes * 60)

    def _tick(self) -> None:
        if self.sync_worker is not None and self.sync_worker.isRunning():
            self.countdown_label.setText("正在同步…")
            return
        self._auto_sync_remaining -= 1
        interval = max(60, self.cfg.sync.interval_minutes * 60)
        if self._auto_sync_remaining <= 0:
            self._auto_sync_remaining = interval
            self._start_sync(full=False)
            return
        remaining = self._auto_sync_remaining
        hours, rem = divmod(remaining, 3600)
        minutes, seconds = divmod(rem, 60)
        if hours:
            text = f"{hours} 小时 {minutes} 分后自动同步"
        elif minutes:
            text = f"{minutes} 分 {seconds} 秒后自动同步"
        else:
            text = f"{seconds} 秒后自动同步"
        self.countdown_label.setText(f"下一次自动同步：{text}")

    # ==================================================================
    # 数据展示
    # ==================================================================
    def _refresh_stats(self) -> None:
        try:
            state = StateStore(self.cfg.state_db)
        except Exception:  # pylint: disable=broad-except
            return
        try:
            stats = state.stats()
            last = state.last_run()
            courses = state.course_progress()
        finally:
            state.close()

        self.stat_course.setText(str(len(courses)))
        self.stat_files.setText(str(stats.get("files_downloaded", 0)))
        self.stat_size.setText(format_size(stats.get("bytes", 0) or 0))
        self.stat_last.setText(_relative_time(last.get("finished_at")) if last else "从未")

        self.course_table.setRowCount(len(courses))
        for row, course in enumerate(courses):
            self._set_table_item(row, 0, course.get("name") or f"课程 {course.get('id')}", bold=True)
            self._set_table_item(row, 1, course.get("term") or "")
            files_total = course.get("files_total") or 0
            files_done = course.get("files_done") or 0
            self._set_table_item(row, 2, str(files_total), align_right=True)
            done_item = QTableWidgetItem(str(files_done))
            done_item.setTextAlignment(Qt.AlignRight | Qt.AlignVCenter)
            if files_total and files_done >= files_total:
                done_item.setForeground(QColor(SUCCESS))
            elif files_total:
                done_item.setForeground(QColor(WARNING))
            self.course_table.setItem(row, 3, done_item)
            self._set_table_item(
                row, 4, format_size(course.get("bytes_downloaded") or 0), align_right=True)
            self._set_table_item(row, 5, _relative_time(course.get("last_synced_at")))
            self._set_table_item(row, 6, str(course.get("id")))
        self.course_count_label.setText(
            f"共 {len(courses)} 门课程 · {format_size(stats.get('bytes', 0) or 0)}")

    def _set_table_item(self, row: int, column: int, text: str,
                        bold: bool = False, align_right: bool = False) -> None:
        item = QTableWidgetItem(text)
        if align_right:
            item.setTextAlignment(Qt.AlignRight | Qt.AlignVCenter)
        else:
            item.setTextAlignment(Qt.AlignVCenter)
        if bold:
            font = item.font()
            font.setWeight(QFont.Weight.Bold)
            item.setFont(font)
        self.course_table.setItem(row, column, item)

    # ==================================================================
    # 文件列表
    # ==================================================================
    def _toggle_file_list(self) -> None:
        """双击课程行：展开/折叠下方的文件列表区域。"""
        course_id = self._selected_course_id()
        if course_id is None:
            return

        if self._file_panel_visible and self._current_file_course_id == course_id:
            # 再次双击同一课程：折叠
            self.file_panel.setVisible(False)
            self._file_panel_visible = False
            self._current_file_course_id = None
            return

        # 展开并加载文件
        self._current_file_course_id = course_id
        self._populate_file_list(course_id)
        self.file_panel.setVisible(True)
        self._file_panel_visible = True

    def _on_course_selection_changed(self) -> None:
        """课程选中行变化时，如果文件列表已展开，则刷新显示对应课程的文件。"""
        if not self._file_panel_visible:
            return
        course_id = self._selected_course_id()
        if course_id is not None and course_id != self._current_file_course_id:
            self._current_file_course_id = course_id
            self._populate_file_list(course_id)

    def _populate_file_list(self, course_id: int) -> None:
        """加载并显示指定课程的文件列表。"""
        try:
            state = StateStore(self.cfg.state_db)
        except Exception:  # pylint: disable=broad-except
            return
        try:
            files = state.list_files_by_course(course_id)
            # 获取课程名用于标题
            course_name = ""
            for course in state.list_courses():
                if course.get("id") == course_id:
                    course_name = course.get("name") or f"课程 {course_id}"
                    break
        finally:
            state.close()

        self.file_panel_title.setText(f"{course_name} · 文件列表")
        self.file_count_label.setText(f"共 {len(files)} 个文件")

        self.file_table.setRowCount(len(files))
        for row, file_info in enumerate(files):
            name = file_info.get("display_name") or file_info.get("filename", "未知文件")
            size = file_info.get("size", 0) or 0
            status = file_info.get("status", "pending")
            downloaded_at = file_info.get("downloaded_at") or ""

            # 文件名
            name_item = QTableWidgetItem(name)
            name_item.setToolTip(name)
            name_item.setData(Qt.UserRole, file_info.get("local_path", ""))
            self.file_table.setItem(row, 0, name_item)

            # 大小
            size_item = QTableWidgetItem(format_size(size))
            size_item.setTextAlignment(Qt.AlignRight | Qt.AlignVCenter)
            self.file_table.setItem(row, 1, size_item)

            # 状态
            status_text, status_color = self._format_file_status(status)
            status_item = QTableWidgetItem(status_text)
            status_item.setTextAlignment(Qt.AlignCenter)
            status_item.setForeground(QColor(status_color))
            self.file_table.setItem(row, 2, status_item)

            # 下载时间
            time_text = _relative_time(downloaded_at) if downloaded_at else "—"
            time_item = QTableWidgetItem(time_text)
            time_item.setTextAlignment(Qt.AlignCenter)
            self.file_table.setItem(row, 3, time_item)

    @staticmethod
    def _format_file_status(status: str) -> tuple:
        """将文件状态码转换为中文显示和颜色。"""
        mapping = {
            "downloaded": ("已下载", SUCCESS),
            "pending": ("待下载", WARNING),
            "failed": ("下载失败", DANGER),
            "remote_missing": ("远端已删", TEXT_SECONDARY),
        }
        return mapping.get(status, (status, TEXT_SECONDARY))

    def _selected_file_path(self) -> Optional[str]:
        """获取当前选中文件的本地路径。"""
        row = self.file_table.currentRow()
        if row < 0:
            return None
        item = self.file_table.item(row, 0)
        if item is None:
            return None
        path = item.data(Qt.UserRole)
        return path if path and os.path.exists(path) else None

    def _preview_selected_file(self) -> None:
        """双击文件列表项：打开预览对话框。"""
        file_path = self._selected_file_path()
        if not file_path:
            return
        if self.preview_dialog is not None and self.preview_dialog.isVisible():
            self.preview_dialog.close()
        dialog = DocumentPreviewDialog(file_path, parent=self)
        dialog_ref = ref(dialog)
        dialog.finished.connect(
            lambda _result, preview_ref=dialog_ref: self._release_preview_dialog(
                preview_ref()
            )
        )
        self.preview_dialog = dialog
        dialog.show()

    def _release_preview_dialog(
        self, dialog: Optional[DocumentPreviewDialog]
    ) -> None:
        """Forget only the preview that actually finished.

        The identity check prevents a delayed signal from an older background
        Office preview from clearing a newer dialog's reference.
        """
        if self.preview_dialog is dialog:
            self.preview_dialog = None

    def _file_context_menu(self, position) -> None:
        """文件列表右键菜单：预览文件、分享文件、打开课程目录。"""
        course_id = self._selected_course_id()
        file_path = self._selected_file_path()
        if course_id is None:
            return

        menu = QMenu(self)
        preview_action = menu.addAction("预览文件")
        preview_action.setEnabled(bool(file_path))
        share_action = menu.addAction("分享…")
        share_action.setEnabled(bool(file_path))
        menu.addSeparator()
        open_dir_action = menu.addAction("打开课程目录")

        action = menu.exec(self.file_table.viewport().mapToGlobal(position))
        if action == preview_action and file_path:
            self._preview_selected_file()
        elif action == share_action and file_path:
            show_share_menu(
                self,
                file_path,
                global_position=self.file_table.viewport().mapToGlobal(position),
            )
        elif action == open_dir_action:
            self._open_course_dir(course_id)

    # ==================================================================
    # 存储管理
    # ==================================================================
    def _open_storage_manager(self) -> None:
        """打开存储管理对话框。"""
        if self.storage_dialog is not None and self.storage_dialog.isVisible():
            self.storage_dialog.raise_()
            self.storage_dialog.activateWindow()
            return
        try:
            state = StateStore(self.cfg.state_db)
        except Exception as exc:  # pylint: disable=broad-except
            QMessageBox.warning(self, "存储管理", f"无法打开状态库：{exc}")
            return
        self.storage_dialog = StorageManagerDialog(state, parent=self)
        self.storage_dialog.files_deleted.connect(self._on_storage_files_deleted)
        self.storage_dialog.finished.connect(lambda _: self._on_storage_closed(state))
        self.storage_dialog.show()

    def _on_storage_files_deleted(self, count: int) -> None:
        """存储管理中删除文件后的回调。"""
        self._refresh_stats()
        if self._file_panel_visible and self._current_file_course_id is not None:
            self._populate_file_list(self._current_file_course_id)

    def _on_storage_closed(self, state: StateStore) -> None:
        """存储管理对话框关闭后，刷新统计和课程列表，并关闭状态库。"""
        try:
            state.close()
        except Exception:  # pylint: disable=broad-except
            pass
        self._refresh_stats()
        self.storage_dialog = None

    # ==================================================================
    # 用户操作
    # ==================================================================
    def _open_root_dir(self) -> None:
        os.makedirs(self.cfg.root_dir, exist_ok=True)
        webbrowser.open(f"file:///{self.cfg.root_dir.replace(os.sep, '/')}")

    def _course_local_dir(self, course_id: int) -> Optional[str]:
        """
        课程本地目录：**必须复用同步引擎的命名规则**（`sanitize_path_component`
        清洗课程名与课程代码），否则课程名含非法字符时 GUI 算出来的路径与引擎
        实际落盘目录不一致，"打开文件夹"会指向不存在的目录。
        """
        try:
            state = StateStore(self.cfg.state_db)
        except Exception:  # pylint: disable=broad-except
            return None
        try:
            for course in state.list_courses():
                if course.get("id") == course_id:
                    name = sanitize_path_component(
                        course.get("name") or f"course_{course_id}")
                    code = course.get("code") or ""
                    directory = name if not code else (
                        f"{name} [{sanitize_path_component(code)}]")
                    return os.path.join(self.cfg.root_dir, directory)
        finally:
            state.close()
        return None

    def _selected_course_id(self) -> Optional[int]:
        row = self.course_table.currentRow()
        if row < 0:
            return None
        item = self.course_table.item(row, 6)
        if item is None:
            return None
        try:
            return int(item.text())
        except ValueError:
            return None

    def _course_context_menu(self, position) -> None:
        course_id = self._selected_course_id()
        if course_id is None:
            return
        from PySide6.QtWidgets import QMenu
        menu = QMenu(self)
        open_action = menu.addAction("打开课程目录")
        sync_action = menu.addAction("仅同步此课程")
        portal_action = menu.addAction("在浏览器中打开课程")
        action = menu.exec(self.course_table.viewport().mapToGlobal(position))
        if action == open_action:
            self._open_course_dir(course_id)
        elif action == sync_action:
            self._start_sync(full=False, course_ids=[course_id])
        elif action == portal_action:
            webbrowser.open(f"{self.cfg.base_url}/courses/{course_id}")

    def _open_course_dir(self, course_id: int) -> None:
        directory = self._course_local_dir(course_id)
        if not directory or not os.path.isdir(directory):
            QMessageBox.information(self, "课程目录", "该课程还没有同步任何文件到本地。")
            return
        webbrowser.open(f"file:///{directory.replace(os.sep, '/')}")

    def _open_settings(self) -> None:
        if self.settings_dialog is not None and self.settings_dialog.isVisible():
            self.settings_dialog.raise_()
            self.settings_dialog.activateWindow()
            return
        self.settings_dialog = SettingsDialog(self.cfg, self.config_path, parent=self)
        self.settings_dialog.applied.connect(self._on_settings_applied)
        self.settings_dialog.logout_requested.connect(self._on_logout)
        self.settings_dialog.show()

    def _on_settings_applied(self) -> None:
        self.cfg = load_config(self.config_path)
        self._reset_countdown()
        self._refresh_stats()

    def _on_logout(self) -> None:
        self.user = {}
        self.user_chip.setText("未登录")
        self._show_login_window()

    # ==================================================================
    # 托盘与窗口生命周期
    # ==================================================================
    def _wire_tray(self) -> None:
        self.tray.show_requested.connect(self._show_from_tray)
        self.tray.sync_requested.connect(lambda: self._start_sync(full=False))
        self.tray.settings_requested.connect(self._open_settings)
        self.tray.autostart_toggled.connect(self._on_tray_autostart)
        self.tray.quit_requested.connect(self._quit)
        self.tray.set_autostart(self._autostart_enabled_safe())
        self.tray.show()

    @staticmethod
    def _autostart_enabled_safe() -> bool:
        from . import autostart
        try:
            return autostart.is_autostart_enabled()
        except Exception:  # pylint: disable=broad-except
            return False

    def _on_tray_autostart(self, enabled: bool) -> None:
        from . import autostart
        try:
            autostart.set_autostart(enabled)
        except Exception as exc:  # pylint: disable=broad-except
            QMessageBox.warning(self, "开机自启", f"设置失败：{exc}")
            self.tray.set_autostart(autostart.is_autostart_enabled())

    def _show_from_tray(self) -> None:
        self.show()
        self.setWindowState(self.windowState() & ~Qt.WindowMinimized | Qt.WindowActive)
        self.raise_()
        self.activateWindow()

    def closeEvent(self, event) -> None:
        """关闭按钮最小化到托盘，只有“退出”才真正退出。"""
        if self._quitting:
            event.accept()
            return
        event.ignore()
        self.hide()
        self.tray.notify("复小学",
                         "已在后台运行，课程文件会自动保持同步。右键托盘图标可退出。")

    def _quit(self) -> None:
        """
        退出：先请同步线程协作停止，停不下来就稍后重试，绝不强杀线程。

        历史实现只等 5 秒就继续销毁窗口，仍在运行的 QThread 会触发
        “QThread: Destroyed while thread is still running” 并在退出时崩溃。
        这里改为把退出延后，直到线程真正结束（同步请求有超时上限，不会无限等）。
        """
        worker = self.sync_worker
        if worker is not None and worker.isRunning():
            worker.stop()
            if not worker.wait(5000):
                self._quit_pending = True
                self.tray.notify(
                    "正在安全停止同步…",
                    "同步线程结束后会自动退出；也可以继续在托盘里使用。",
                )
                QTimer.singleShot(2000, self._retry_quit)
                return
        self._finish_quit()

    def _retry_quit(self) -> None:
        """等待同步线程结束的轮询；仍在运行就继续等，不做任何强制终止。"""
        if not self._quit_pending:
            return
        worker = self.sync_worker
        if worker is not None and worker.isRunning():
            QTimer.singleShot(2000, self._retry_quit)
            return
        self._finish_quit()

    def _finish_quit(self) -> None:
        self._quit_pending = False
        self._quitting = True
        self._save_geometry()
        self.tray.tray.hide()
        self.close()
        from PySide6.QtWidgets import QApplication
        QApplication.instance().quit()

    def _restore_geometry(self) -> None:
        from .paths import gui_state_path
        state = load_gui_state(gui_state_path())
        geometry = state.get("geometry")
        if geometry:
            self.restoreGeometry(bytes(geometry, encoding="latin1"))
        else:
            screen = QGuiApplication.primaryScreen().availableGeometry()
            width, height = 960, 680
            self.resize(width, height)
            self.move(screen.center().x() - width // 2,
                      screen.center().y() - height // 2)

    def _save_geometry(self) -> None:
        from .paths import gui_state_path
        state = load_gui_state(gui_state_path())
        state["geometry"] = self.saveGeometry().data().decode("latin1")
        save_gui_state(gui_state_path(), state)

    def showEvent(self, event) -> None:
        super().showEvent(event)
        if self.start_minimized:
            self.start_minimized = False
            self.hide()
