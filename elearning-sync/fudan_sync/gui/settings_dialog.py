"""设置对话框：同步目录、间隔、过滤策略、开机自启、退出登录。"""
from __future__ import annotations

import os

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QGuiApplication
from PySide6.QtWidgets import (QCheckBox, QFileDialog, QFormLayout,
                               QFrame, QGroupBox, QHBoxLayout, QLabel, QLineEdit,
                               QMessageBox, QPushButton, QScrollArea, QSizePolicy,
                               QSpinBox, QVBoxLayout, QWidget)

from ..config import load_config
from ..password_login import clear_credentials
from . import autostart
from .config_io import update_config
from .icon import app_icon
from .styles import DANGER


class SettingsDialog(QFrame):
    """非模态窗口；applied() 表示用户已保存设置，logout_requested() 表示已退出登录。"""

    applied = Signal()
    logout_requested = Signal()

    def __init__(self, cfg, config_path: str, parent=None):
        super().__init__(parent)
        self.cfg = cfg
        self.config_path = config_path
        self.setWindowTitle("设置 · 复小学")
        self.setWindowIcon(app_icon())
        self.setObjectName("root")
        self.setMinimumSize(600, 560)
        self.resize(640, 720)
        self._build_ui()
        self._center()

    def _center(self) -> None:
        screen = QGuiApplication.primaryScreen().availableGeometry()
        self.move(screen.center().x() - self.width() // 2,
                  screen.center().y() - self.height() // 2)

    # ------------------------------------------------------------------
    def _build_ui(self) -> None:
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        # 内容区可滚动：窗口较矮或高 DPI 下也不会裁切掉分组与按钮
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.NoFrame)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        content = QWidget()
        content.setObjectName("root")
        content_layout = QVBoxLayout(content)
        content_layout.setContentsMargins(28, 24, 28, 20)
        content_layout.setSpacing(16)
        scroll.setWidget(content)
        layout.addWidget(scroll, 1)

        title = QLabel("设置")
        title.setObjectName("titleLabel")
        content_layout.addWidget(title)

        # ---- 同步 ----
        sync_box = QGroupBox("同步")
        sync_form = QFormLayout(sync_box)
        sync_form.setSpacing(12)
        sync_form.setVerticalSpacing(12)

        self.root_edit = QLineEdit(self.cfg.root_dir)
        browse_button = QPushButton("浏览…")
        browse_button.setCursor(Qt.PointingHandCursor)
        browse_button.clicked.connect(self._browse_root)
        root_row = QHBoxLayout()
        root_row.setSpacing(8)
        root_row.addWidget(self.root_edit, 1)
        root_row.addWidget(browse_button)
        sync_form.addRow("同步根目录", root_row)

        self.interval_spin = QSpinBox()
        self.interval_spin.setRange(1, 1440)
        self.interval_spin.setSuffix(" 分钟")
        self.interval_spin.setValue(self.cfg.sync.interval_minutes)
        sync_form.addRow("自动同步间隔", self.interval_spin)

        self.concurrency_spin = QSpinBox()
        self.concurrency_spin.setRange(1, 32)
        self.concurrency_spin.setValue(self.cfg.sync.download.concurrency)
        sync_form.addRow("并发下载数", self.concurrency_spin)

        self.only_favorites_check = QCheckBox("只同步收藏的课程（在 eLearning 里点亮的星标）")
        self.only_favorites_check.setChecked(bool(self.cfg.sync.only_favorites))
        sync_form.addRow("", self.only_favorites_check)

        self.archive_check = QCheckBox("归档页面 / 作业 / 公告为 HTML（保存非文件类内容）")
        self.archive_check.setChecked(bool(self.cfg.sync.archive_pages))
        sync_form.addRow("", self.archive_check)

        self.prune_check = QCheckBox("远端已删除的文件，本地也同步删除")
        self.prune_check.setChecked(bool(self.cfg.sync.prune))
        sync_form.addRow("", self.prune_check)

        self.empty_check = QCheckBox("跳过空课程与组织站点（不在本地建空目录）")
        self.empty_check.setChecked(bool(self.cfg.sync.skip_empty_courses))
        sync_form.addRow("", self.empty_check)

        content_layout.addWidget(sync_box)

        # ---- 内容过滤 ----
        filter_box = QGroupBox("内容过滤")
        filter_form = QFormLayout(filter_box)
        filter_form.setSpacing(12)
        filter_form.setVerticalSpacing(12)

        self.installer_check = QCheckBox("不同步安装包（.exe / .msi / .apk 等）")
        self.installer_check.setChecked(bool(self.cfg.sync.download.exclude_installer_files))
        filter_form.addRow("", self.installer_check)

        self.folders_edit = QLineEdit(", ".join(self.cfg.sync.download.exclude_folders))
        self.folders_edit.setPlaceholderText("course_image")
        filter_form.addRow("排除目录（逗号分隔）", self.folders_edit)

        self.exts_edit = QLineEdit(", ".join(self.cfg.sync.download.exclude_extensions))
        self.exts_edit.setPlaceholderText("留空表示不按扩展名过滤")
        filter_form.addRow("排除扩展名（逗号分隔）", self.exts_edit)
        content_layout.addWidget(filter_box)

        # ---- 启动 ----
        startup_box = QGroupBox("启动")
        startup_form = QFormLayout(startup_box)
        self.autostart_check = QCheckBox("开机自动启动并后台同步")
        self.autostart_check.setChecked(autostart.is_autostart_enabled())
        startup_form.addRow("", self.autostart_check)
        content_layout.addWidget(startup_box)

        # ---- 账号 ----
        account_box = QGroupBox("账号")
        account_form = QFormLayout(account_box)
        method_names = {"password": "账号密码（自动登录）", "cookie": "会话 Cookie",
                        "browser": "浏览器登录", "token": "API Token"}
        method_text = method_names.get(self.cfg.auth_method, self.cfg.auth_method or "未设置")
        account_label = QLabel(f"{self.cfg.uis_username or '（未登录）'}"
                               f"（登录方式：{method_text}）")
        account_label.setWordWrap(True)
        account_form.addRow("当前账号", account_label)
        logout_button = QPushButton("退出登录并清除保存的密码")
        logout_button.setObjectName("danger")
        logout_button.setCursor(Qt.PointingHandCursor)
        logout_button.clicked.connect(self._on_logout)
        account_form.addRow("", logout_button)
        content_layout.addWidget(account_box)

        content_layout.addStretch()

        button_row = QHBoxLayout()
        button_row.setContentsMargins(28, 0, 28, 16)
        button_row.addStretch()
        cancel_button = QPushButton("取消")
        cancel_button.setCursor(Qt.PointingHandCursor)
        cancel_button.clicked.connect(self.close)
        self.save_button = QPushButton("保存设置")
        self.save_button.setObjectName("primary")
        self.save_button.setCursor(Qt.PointingHandCursor)
        self.save_button.clicked.connect(self._on_save)
        button_row.addWidget(cancel_button)
        button_row.addWidget(self.save_button)
        layout.addLayout(button_row)

    # ------------------------------------------------------------------
    def _browse_root(self) -> None:
        directory = QFileDialog.getExistingDirectory(
            self, "选择同步根目录",
            self.root_edit.text() or os.path.expanduser("~"))
        if directory:
            self.root_edit.setText(directory)

    def _on_logout(self) -> None:
        confirm = QMessageBox.question(
            self, "退出登录",
            "将清除本机保存的账号密码，下次打开软件需要重新登录。确认退出登录吗？",
            QMessageBox.Yes | QMessageBox.No, QMessageBox.No)
        if confirm != QMessageBox.Yes:
            return
        clear_credentials(self.cfg.uis_username or None)
        # 清掉配置里的登录方式，下次启动进入登录引导
        update_config(self.config_path, [
            (("auth", "method"), None),
            (("auth", "uis_username"), None),
            (("auth", "token"), None),
        ])
        self.logout_requested.emit()
        self.close()

    def _on_save(self) -> None:
        root = self.root_edit.text().strip()
        if not root:
            self._warn("同步根目录不能为空")
            return
        try:
            os.makedirs(os.path.expanduser(root), exist_ok=True)
        except OSError as exc:
            self._warn(f"无法创建同步目录：{exc}")
            return

        folders = [f.strip().lower() for f in self.folders_edit.text().split(",") if f.strip()]
        exts = [e.strip().lower().lstrip(".") for e in self.exts_edit.text().split(",") if e.strip()]

        try:
            # 注意：键路径必须是单元素元组 ("root_dir",)。若误传字符串 "root_dir"，
            # update_config 会逐字符迭代它，把根目录写进
            # r:{o:{o:{t:{_:{d:{i:{r: …}}}}}}} 这样的垃圾嵌套键，
            # 用户改的同步目录则永远不生效。
            update_config(self.config_path, [
                (("root_dir",), os.path.expanduser(root)),
                (("sync", "interval_minutes"), int(self.interval_spin.value())),
                (("sync", "only_favorites"), bool(self.only_favorites_check.isChecked())),
                (("sync", "archive_pages"), bool(self.archive_check.isChecked())),
                (("sync", "prune"), bool(self.prune_check.isChecked())),
                (("sync", "skip_empty_courses"), bool(self.empty_check.isChecked())),
                (("sync", "download", "concurrency"), int(self.concurrency_spin.value())),
                (("sync", "download", "exclude_folders"), folders),
                (("sync", "download", "exclude_extensions"), exts),
                (("sync", "download", "exclude_installer_files"),
                 bool(self.installer_check.isChecked())),
            ])
        except OSError as exc:
            self._warn(f"保存配置失败：{exc}")
            return

        try:
            autostart.set_autostart(bool(self.autostart_check.isChecked()))
        except Exception as exc:  # pylint: disable=broad-except
            self._warn(f"开机自启设置失败：{exc}")
            return

        self.cfg = load_config(self.config_path)
        self.applied.emit()
        self.close()

    def _warn(self, message: str) -> None:
        QMessageBox.warning(self, "设置", message)
