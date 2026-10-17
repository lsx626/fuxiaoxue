#!/usr/bin/env python3
"""复小学 - 复旦大学 eLearning 课程同步工具（图形界面入口）。

首次打开只需输入 UIS 账号密码，之后自动登录、自动同步。
"""

from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Probe multimedia support before importing Qt submodules. The previewer uses
# this same side-effect-free check to provide a clear fallback in minimal
# environments; release builds require the matching PySide6-Addons package.
from fudan_sync.bootstrap import ensure_qtmultimedia  # noqa: E402

ensure_qtmultimedia()

from fudan_sync.gui.config_io import save_yaml  # noqa: E402
from fudan_sync.gui.icon import app_icon  # noqa: E402
from fudan_sync.gui.main_window import MainWindow  # noqa: E402
from fudan_sync.gui.paths import (
    config_path,
    default_root_dir,  # noqa: E402
    ensure_data_dirs,
)
from fudan_sync.gui.styles import QSS, apply_palette  # noqa: E402
from PySide6.QtCore import Qt  # noqa: E402
from PySide6.QtNetwork import QLocalServer, QLocalSocket  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

SINGLE_INSTANCE_NAME = "fudan-elearning-sync-gui"


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="复小学：复旦大学 eLearning 课程同步（图形界面）")
    parser.add_argument("--minimized", action="store_true", help="启动后最小化到系统托盘")
    parser.add_argument("--config", help="指定配置文件（默认自动检测）")
    return parser.parse_args(argv)


def ensure_single_instance() -> bool:
    """同一时间只允许运行一个实例；重复启动时唤起已有窗口。"""
    socket = QLocalSocket()
    socket.connectToServer(SINGLE_INSTANCE_NAME)
    if socket.waitForConnected(500):
        socket.write(b"show")
        socket.flush()
        socket.waitForBytesWritten(1000)
        return False
    server = QLocalServer()
    # 上次退出非正常（崩溃 / 杀进程）时服务名会残留，先清理再监听，
    # 否则新实例会误以为已有实例在运行而启动失败。
    QLocalServer.removeServer(SINGLE_INSTANCE_NAME)
    server.listen(SINGLE_INSTANCE_NAME)
    return server


def bootstrap_config() -> str:
    """确定配置文件路径；不存在时生成一份开箱即用的默认配置。"""
    path = config_path()
    ensure_data_dirs(path)
    if not os.path.exists(path):
        save_yaml(
            path,
            {
                "base_url": "https://elearning.fudan.edu.cn",
                "auth": {
                    "method": "password",
                    "uis_username": "",
                    "cookie_file": os.path.join(os.path.dirname(path), "cookies.json"),
                },
                "root_dir": default_root_dir(),
                "state_db": os.path.join(os.path.dirname(path), "sync_state.db"),
                "log_file": os.path.join(os.path.dirname(path), "sync.log"),
                "sync": {
                    "interval_minutes": 15,
                    "only_favorites": False,
                    "enrollment_type": "student",
                    "archive_pages": True,
                    "skip_empty_courses": True,
                    "prune": False,
                    "download": {
                        "concurrency": 4,
                        "max_retries": 5,
                        "exclude_folders": ["course_image"],
                        "exclude_installer_files": True,
                    },
                },
            },
        )
    return path


def main(argv=None) -> int:
    args = parse_args(argv)

    app = QApplication(sys.argv)
    app.setApplicationName("复小学")
    app.setWindowIcon(app_icon())
    app.setQuitOnLastWindowClosed(False)  # 关闭窗口仍驻留托盘

    server = ensure_single_instance()
    if server is False:
        return 0

    def _on_instance_message():
        # 二次启动：把已有窗口显示出来
        for widget in app.topLevelWidgets():
            if isinstance(widget, MainWindow):
                widget.show()
                widget.setWindowState(widget.windowState() & ~Qt.WindowMinimized | Qt.WindowActive)
                widget.raise_()
                widget.activateWindow()

    if server is not False:
        server.newConnection.connect(_on_instance_message)

    apply_palette(app)
    app.setStyleSheet(QSS)

    config_file = args.config or bootstrap_config()
    window = MainWindow(config_file, start_minimized=args.minimized)
    if not args.minimized:
        window.show()

    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
