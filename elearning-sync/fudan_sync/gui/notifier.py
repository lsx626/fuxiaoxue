# -*- coding: utf-8 -*-
"""Windows 桌面通知模块：优先使用原生 Toast 通知，不可用时优雅回退。

支持点击通知打开指定文件或目录，所有通知操作均不阻塞 UI 线程。
"""

from __future__ import annotations

import os
import subprocess
import threading
from typing import Optional

from PySide6.QtCore import QObject, QTimer, Signal

from .icon import app_icon

# ---------------------------------------------------------------------------
# 探测可用的通知后端（导入时一次性检测，避免每次调用都尝试）
# ---------------------------------------------------------------------------

# 后端 1：win10toast（Windows 10+ Toast 通知，支持点击回调）
_win10toast_available = False
try:
    from win10toast import ToastNotifier  # type: ignore

    _win10toast_available = True
except ImportError:
    ToastNotifier = None  # type: ignore

# 后端 2：plyer（跨平台通知库，Windows 下也走 Toast）
_plyer_available = False
try:
    from plyer import notification as _plyer_notification  # type: ignore

    _plyer_available = True
except ImportError:
    _plyer_notification = None  # type: ignore

# 后端 3：win32api.MessageBox（最朴素的弹窗，总是可用）
_win32_available = False
try:
    import win32api  # type: ignore
    import win32con  # type: ignore

    _win32_available = True
except ImportError:
    win32api = None  # type: ignore
    win32con = None  # type: ignore


class WindowsNotifier(QObject):
    """Windows 桌面通知管理器。

    按优先级选择通知后端：
        1. win10toast（支持点击回调，体验最佳）
        2. plyer（跨平台，兼容性好）
        3. win32api.MessageBox（兜底，阻塞式但一定可用）

    所有 ``notify`` 调用均通过线程或 QTimer 异步执行，不阻塞 UI 线程。
    """

    # 通知被点击时发出信号，参数为关联的文件/目录路径
    notification_clicked = Signal(str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self._win10toaster: Optional[ToastNotifier] = None
        if _win10toast_available:
            try:
                self._win10toaster = ToastNotifier()
            except Exception:  # pylint: disable=broad-except
                self._win10toaster = None

    # ------------------------------------------------------------------
    # 公共 API
    # ------------------------------------------------------------------
    def notify(
        self,
        title: str,
        message: str,
        icon_path: Optional[str] = None,
        open_path: Optional[str] = None,
    ) -> None:
        """发送桌面通知。

        Args:
            title: 通知标题
            message: 通知正文
            icon_path: 自定义图标路径（可选），为 None 时使用应用图标
            open_path: 点击通知时要打开的文件或目录路径（可选）
        """
        # 通过 QTimer.singleShot 将通知投递到事件循环，避免阻塞调用方
        QTimer.singleShot(0, lambda: self._do_notify(title, message, icon_path, open_path))

    # ------------------------------------------------------------------
    # 内部实现
    # ------------------------------------------------------------------
    def _do_notify(
        self, title: str, message: str, icon_path: Optional[str], open_path: Optional[str]
    ) -> None:
        """实际执行通知分发（在事件循环中调用）。"""
        # 后端 1：win10toast —— 支持回调，体验最好
        if self._win10toaster is not None:
            self._notify_win10toast(title, message, icon_path, open_path)
            return

        # 后端 2：plyer —— 兼容性好，但不支持点击回调
        if _plyer_available:
            self._notify_plyer(title, message, icon_path)
            # plyer 不支持点击回调，open_path 只能忽略
            return

        # 后端 3：win32api.MessageBox —— 兜底方案
        if _win32_available:
            # MessageBox 是阻塞的，放到单独线程里
            threading.Thread(
                target=self._notify_win32,
                args=(title, message, open_path),
                daemon=True,
            ).start()
            return

        # 极端情况：啥都没有，静默失败（总不能崩了）
        pass

    def _notify_win10toast(
        self, title: str, message: str, icon_path: Optional[str], open_path: Optional[str]
    ) -> None:
        """使用 win10toast 发送 Toast 通知。"""
        icon = icon_path or self._extract_icon_temp()
        callback = None
        if open_path and os.path.exists(open_path):

            def _on_click(path=open_path):
                # 回调在子线程，通过信号回到主线程
                self.notification_clicked.emit(path)
                _open_path_in_shell(path)

            callback = _on_click

        try:
            # show_toast 的 threaded=True 会在内部开线程，不阻塞
            self._win10toaster.show_toast(
                title,
                message,
                icon_path=icon,
                duration=5,
                threaded=True,
                callback_on_click=callback,
            )
        except Exception:  # pylint: disable=broad-except
            # win10toast 出问题时降级到 plyer 或 win32
            if _plyer_available:
                self._notify_plyer(title, message, icon_path)
            elif _win32_available:
                threading.Thread(
                    target=self._notify_win32,
                    args=(title, message, open_path),
                    daemon=True,
                ).start()

    def _notify_plyer(self, title: str, message: str, icon_path: Optional[str]) -> None:
        """使用 plyer 发送通知。"""
        icon = icon_path or self._extract_icon_temp()
        try:
            _plyer_notification.notify(
                title=title,
                message=message,
                app_name="复小学",
                app_icon=icon,
                timeout=5,
            )
        except Exception:  # pylint: disable=broad-except
            # plyer 也挂了，再降级
            if _win32_available:
                threading.Thread(
                    target=self._notify_win32,
                    args=(title, message, None),
                    daemon=True,
                ).start()

    def _notify_win32(self, title: str, message: str, open_path: Optional[str]) -> None:
        """使用 win32api.MessageBox 兜底（在子线程中调用）。

        这是最朴素的模态弹窗，用户点击"确定"后可选打开关联路径。
        """
        try:
            result = win32api.MessageBox(
                0,
                message,
                title,
                win32con.MB_OK | win32con.MB_ICONINFORMATION,
            )
            if result == 1 and open_path and os.path.exists(open_path):
                # 用户点击了确定，并且有关联路径
                self.notification_clicked.emit(open_path)
                _open_path_in_shell(open_path)
        except Exception:  # pylint: disable=broad-except
            pass

    def _extract_icon_temp(self) -> Optional[str]:
        """尝试将 QIcon 导出为临时 .ico 文件供通知库使用。

        大部分情况下 win10toast/plyer 不传 icon 也能正常显示，
        这里做个简单尝试，失败就返回 None。
        """
        try:
            import tempfile

            icon = app_icon()
            pixmap = icon.pixmap(64, 64)
            tmp_path = os.path.join(tempfile.gettempdir(), "fuxiaoxue_notify.ico")
            pixmap.save(tmp_path, "ICO")
            return tmp_path
        except Exception:  # pylint: disable=broad-except
            return None


def _open_path_in_shell(path: str) -> None:
    """用系统默认方式打开文件或目录。"""
    try:
        if os.name == "nt":
            # Windows：os.startfile 是最稳妥的方式
            os.startfile(path)  # type: ignore[attr-defined]
        else:
            # 非 Windows 环境兜底（一般走不到）
            subprocess.Popen(["start", path], shell=True)
    except Exception:  # pylint: disable=broad-except
        pass
