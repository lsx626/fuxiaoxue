"""Desktop file sharing actions used by the main window and previewer."""

from __future__ import annotations

import os
import shutil
import subprocess

from PySide6.QtCore import QMimeData, QPoint, QUrl
from PySide6.QtGui import QGuiApplication
from PySide6.QtWidgets import QFileDialog, QMenu, QMessageBox, QWidget


def _show_in_folder(path: str) -> None:
    """Open the platform file manager with *path* selected when possible."""
    if os.name == "nt":
        subprocess.Popen(["explorer.exe", "/select,", os.path.normpath(path)])
    elif os.sys.platform == "darwin":
        subprocess.Popen(["open", "-R", path])
    else:
        subprocess.Popen(["xdg-open", os.path.dirname(path)])


def _copy_file(path: str) -> None:
    """Put a file URL on the clipboard so it can be pasted into other apps."""
    mime = QMimeData()
    mime.setUrls([QUrl.fromLocalFile(path)])
    mime.setText(path)
    QGuiApplication.clipboard().setMimeData(mime)


def _save_copy(parent: QWidget, path: str) -> None:
    target, _ = QFileDialog.getSaveFileName(
        parent,
        "分享文件 - 保存副本",
        os.path.join(os.path.expanduser("~"), os.path.basename(path)),
        "所有文件 (*.*)",
    )
    if not target:
        return
    try:
        if os.path.normcase(os.path.abspath(target)) == os.path.normcase(os.path.abspath(path)):
            QMessageBox.information(parent, "分享文件", "所选位置就是原文件，无需复制。")
            return
        os.makedirs(os.path.dirname(os.path.abspath(target)), exist_ok=True)
        shutil.copy2(path, target)
        QMessageBox.information(parent, "分享完成", f"文件副本已保存到：\n{target}")
    except OSError as exc:
        QMessageBox.warning(parent, "分享失败", f"无法保存文件副本：{exc}")


def show_share_menu(
    parent: QWidget,
    path: str,
    *,
    anchor: QWidget | None = None,
    global_position: QPoint | None = None,
) -> None:
    """Show consistent, fully local sharing options for one file."""
    path = os.path.abspath(path)
    if not os.path.isfile(path):
        QMessageBox.warning(parent, "分享失败", "文件不存在或尚未下载完成。")
        return

    menu = QMenu(parent)
    copy_file_action = menu.addAction("复制文件到剪贴板")
    save_copy_action = menu.addAction("另存副本…")
    menu.addSeparator()
    copy_path_action = menu.addAction("复制文件路径")
    show_action = menu.addAction("在文件夹中显示")

    if global_position is None:
        if anchor is not None:
            global_position = anchor.mapToGlobal(anchor.rect().bottomLeft())
        else:
            global_position = parent.mapToGlobal(parent.rect().center())
    chosen = menu.exec(global_position)

    try:
        if chosen == copy_file_action:
            _copy_file(path)
        elif chosen == save_copy_action:
            _save_copy(parent, path)
        elif chosen == copy_path_action:
            QGuiApplication.clipboard().setText(path)
        elif chosen == show_action:
            _show_in_folder(path)
    except OSError as exc:
        QMessageBox.warning(parent, "分享失败", f"无法完成此操作：{exc}")
