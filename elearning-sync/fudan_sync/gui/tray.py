"""系统托盘：最小化到托盘、快捷操作。"""

from __future__ import annotations

from PySide6.QtCore import QObject, Signal
from PySide6.QtWidgets import QMenu, QSystemTrayIcon

from .icon import app_icon


class TrayController(QObject):
    """托盘图标与右键菜单，所有用户操作以信号发出，由主窗口响应。"""

    show_requested = Signal()
    sync_requested = Signal()
    settings_requested = Signal()
    autostart_toggled = Signal(bool)
    quit_requested = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.tray = QSystemTrayIcon(app_icon(), parent)
        self.tray.setToolTip("复小学")
        self.tray.activated.connect(self._on_activated)

        menu = QMenu()
        self.show_action = menu.addAction("显示主界面")
        menu.addSeparator()
        self.sync_action = menu.addAction("立即同步")
        self.settings_action = menu.addAction("设置…")
        menu.addSeparator()
        self.autostart_action = menu.addAction("开机自动启动")
        self.autostart_action.setCheckable(True)
        menu.addSeparator()
        self.quit_action = menu.addAction("退出")

        self.show_action.triggered.connect(self.show_requested.emit)
        self.sync_action.triggered.connect(self.sync_requested.emit)
        self.settings_action.triggered.connect(self.settings_requested.emit)
        self.autostart_action.toggled.connect(self.autostart_toggled.emit)
        self.quit_action.triggered.connect(self.quit_requested.emit)

        self.tray.setContextMenu(menu)
        self._busy = False

    def show(self) -> None:
        self.tray.show()

    def set_busy(self, busy: bool) -> None:
        self._busy = busy
        self.sync_action.setEnabled(not busy)
        self.tray.setToolTip("正在同步…" if busy else "复小学")

    def set_autostart(self, enabled: bool) -> None:
        # blockSignals 避免回写时触发信号，导致设置对话框与注册表互相覆盖
        self.autostart_action.blockSignals(True)
        self.autostart_action.setChecked(enabled)
        self.autostart_action.blockSignals(False)

    def notify(self, title: str, message: str) -> None:
        try:
            self.tray.showMessage(title, message, QSystemTrayIcon.Information, 4000)
        except Exception:  # pylint: disable=broad-except
            pass

    def _on_activated(self, reason) -> None:
        if reason == QSystemTrayIcon.DoubleClick or reason == QSystemTrayIcon.Trigger:
            self.show_requested.emit()
