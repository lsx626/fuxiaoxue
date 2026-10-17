"""GUI 搜索烟测：搜索对话框 + 主窗口联动（过滤、定位、预览入口）。

放在子进程里跑，与 previewer 烟测同一模式：QApplication 不能在 pytest
主进程里反复建。子进程的 cwd / APPDATA 都指向临时目录，避免碰到用户
真实配置、状态库或 GUI 状态。
"""

from __future__ import annotations

import os
import subprocess
import sys

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

_SCRIPT = r"""
import os
import sys

from PySide6.QtWidgets import QApplication
from PySide6.QtCore import Qt
from PySide6.QtGui import QFontDatabase
from PySide6.QtTest import QTest

from fudan_sync.gui.main_window import MainWindow
from fudan_sync.gui.search_dialog import SearchDialog
from fudan_sync.gui.styles import QSS, apply_palette
from fudan_sync.state import StateStore

base = os.environ["FXS_SMOKE_BASE"]
state_db = os.path.join(base, "st.db")

state = StateStore(state_db)
state.upsert_course(1, "机器学习导论", "CS229", "2025秋", "student", False)
state.upsert_course(2, "普通化学A（上）", "CHEM101", "2026春", "student", False)
content = "本节讲授卷积神经网络与反向传播算法"
path = os.path.join(base, "lec1.txt")
with open(path, "w", encoding="utf-8") as handle:
    handle.write(content)
state.upsert_file({
    "file_id": 100, "course_id": 1, "filename": "lec1.txt",
    "display_name": "lec1.txt", "size": len(content.encode("utf-8")),
    "content_type": "text/plain", "folder_id": None, "folder_path": "",
    "created_at": "t1", "updated_at": "t1", "modified_at": "t1",
    "locked_for_user": False, "hidden": False, "source": "files",
    "context": "", "local_path": path,
})
state.mark_downloaded(100, path, len(content.encode("utf-8")))
state.upsert_file_index(100, "lec1.txt", content)
state.close()

app = QApplication([])
# Windows 的 offscreen 插件不枚举系统字体，显式加载本机字体用于真实排版检查。
if sys.platform == "win32":
    for name in ("msyh.ttc", "segoeui.ttf"):
        font_path = os.path.join(os.environ.get("WINDIR", "C:/Windows"), "Fonts", name)
        if os.path.exists(font_path):
            QFontDatabase.addApplicationFont(font_path)
app.setStyle("Fusion")
apply_palette(app)
app.setStyleSheet(QSS)
window = MainWindow(os.path.join(base, "config.yaml"))
window.show()
app.processEvents()  # 只跑很短的周期，不触发 200ms 后的登录窗定时器

# 小窗口内主要操作可见，学期与名称筛选可组合，并保留完整名称提示。
window.resize(880, 620)
window.user_chip.setText("超长用户名" * 20)
app.processEvents()
assert window.width() == 880
assert window.sync_button.visibleRegion().boundingRect() == window.sync_button.rect()
window.course_filter_edit.setText("chem101")
for row in range(2):
    assert window.course_table.isRowHidden(row) == (window.course_table.item(row, 6).text() != "2")
window.term_filter.setCurrentIndex(window.term_filter.findData("2025秋"))
assert all(window.course_table.isRowHidden(i) for i in range(2))
assert window.course_empty_label.isVisible()
window.course_filter_edit.clear()
window.term_filter.setCurrentIndex(0)

# ---- 搜索对话框 ----
dialog = SearchDialog(state_db, parent=None)
dialog.query_edit.setText("卷积神经网络")
dialog._run_search()  # 跳过防抖定时器直接执行
assert dialog.result_list.count() == 1, dialog.status_label.text()
assert "共 1 项结果" in dialog.status_label.text(), dialog.status_label.text()
captured = []
dialog.file_open_requested.connect(captured.append)
dialog._open_selected()
assert captured == [100], captured
dialog.deleteLater()

# ---- 主窗口深链：定位到课程文件列表 ----
window._open_file_from_search(100)
assert window._file_panel_visible, "文件面板应展开"
assert window.file_panel.isVisible()
assert window.file_table.rowCount() == 1, window.file_table.rowCount()
assert window._current_file_course_id == 1
app.processEvents()
if window.preview_dialog is not None:
    window.preview_dialog.close()
    window.preview_dialog = None

# ---- 文件表即时过滤 ----
window._apply_file_filter("lec")
assert window.file_table.rowCount() == 1
window._apply_file_filter("卷积")  # 文件名不含，但不过滤源字段也不含 -> 0 项
assert window.file_table.rowCount() == 0
assert window.file_empty_label.isVisible()
window._apply_file_filter("")
assert window.file_table.rowCount() == 1
window.file_status_filter.setCurrentIndex(window.file_status_filter.findData("failed"))
assert window.file_table.rowCount() == 0
window.file_status_filter.setCurrentIndex(0)
assert window.file_table.rowCount() == 1
window.file_table.selectRow(0)
assert window.preview_button.isEnabled()
window._apply_file_filter("不存在")
assert not window.preview_button.isEnabled()
window._apply_file_filter("")

# 同步刷新不丢失课程选中和文件上下文。
window._refresh_stats()
assert window._selected_course_id() == 1
assert window.file_table.rowCount() == 1

# ---- 快捷键已接线 ----
assert window._shortcut_search.key().toString() == "Ctrl+K"
assert window._shortcut_filter.key().toString() == "Ctrl+F"
assert window.file_panel_title.width() > 100
window.open_course_button.click()
assert window._file_panel_visible
window.file_table.selectRow(0)
QTest.keyClick(window.file_table, Qt.Key_Return)
assert window.preview_dialog is not None
window.preview_dialog.close()

# 可选截图输出只使用当前合成数据，便于检查不同 DPI 下的真实布局。
qa_output = os.environ.get("FXS_QA_OUTPUT")
if qa_output:
    os.makedirs(qa_output, exist_ok=True)
    for width, height in ((880, 620), (1200, 820)):
        window.resize(width, height)
        app.processEvents()
        assert window.width() == width
        for button in (window.sync_button, window.full_sync_button, window.preview_button):
            assert button.visibleRegion().boundingRect() == button.rect(), button.text()
        window.grab().save(os.path.join(qa_output, f"desktop-{width}.png"))

if window.search_dialog is not None:
    window.search_dialog = None
window.close()
window.deleteLater()
app.processEvents()
print("SEARCH_UI_OK")
"""


@pytest.fixture
def smoke_base(tmp_path):
    base = str(tmp_path)
    config = os.path.join(base, "config.yaml")
    with open(config, "w", encoding="utf-8") as handle:
        handle.write(
            "base_url: https://elearning.fudan.edu.cn\n"
            'auth:\n  method: token\n  token: ""\n'
            f"root_dir: {os.path.join(base, 'files')}\n"
            f"state_db: {os.path.join(base, 'st.db')}\n"
            'log_file: ""\n'
        )
    return base


def test_search_dialog_and_main_window_flow(smoke_base):
    env = dict(os.environ)
    env["QT_QPA_PLATFORM"] = "offscreen"
    env["APPDATA"] = smoke_base  # 沙箱化 GUI 状态与数据目录
    env["LOCALAPPDATA"] = smoke_base
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    env["FXS_SMOKE_BASE"] = smoke_base
    env["PYTHONPATH"] = ROOT  # fudan_sync 包在 elearning-sync/ 下
    result = subprocess.run(
        [sys.executable, "-c", _SCRIPT],
        cwd=smoke_base,  # 便携模式 config.yaml 落在临时目录
        env=env,
        capture_output=True,
        text=True,
        timeout=60,
    )
    assert result.returncode == 0, result.stderr
    assert "SEARCH_UI_OK" in result.stdout, result.stdout + result.stderr


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-q", "-p", "no:cacheprovider"]))
