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

_SCRIPT = r'''
import os
import sys

from PySide6.QtWidgets import QApplication

from fudan_sync.gui.main_window import MainWindow
from fudan_sync.gui.search_dialog import SearchDialog
from fudan_sync.state import StateStore

base = os.environ["FXS_SMOKE_BASE"]
state_db = os.path.join(base, "st.db")

state = StateStore(state_db)
state.upsert_course(1, "机器学习导论", "CS229", "2025秋", "student", False)
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
window = MainWindow(os.path.join(base, "config.yaml"))
window.show()
app.processEvents()  # 只跑很短的周期，不触发 200ms 后的登录窗定时器

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

# ---- 快捷键已接线 ----
assert window._shortcut_search.key().toString() == "Ctrl+K"
assert window._shortcut_filter.key().toString() == "Ctrl+F"

if window.search_dialog is not None:
    window.search_dialog = None
window.close()
window.deleteLater()
app.processEvents()
print("SEARCH_UI_OK")
'''


@pytest.fixture
def smoke_base(tmp_path):
    base = str(tmp_path)
    config = os.path.join(base, "config.yaml")
    with open(config, "w", encoding="utf-8") as handle:
        handle.write(
            "base_url: https://elearning.fudan.edu.cn\n"
            "auth:\n  method: token\n  token: \"\"\n"
            f"root_dir: {os.path.join(base, 'files')}\n"
            f"state_db: {os.path.join(base, 'st.db')}\n"
            "log_file: \"\"\n")
    return base


def test_search_dialog_and_main_window_flow(smoke_base):
    env = dict(os.environ)
    env["QT_QPA_PLATFORM"] = "offscreen"
    env["APPDATA"] = smoke_base          # 沙箱化 GUI 状态与数据目录
    env["LOCALAPPDATA"] = smoke_base
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    env["FXS_SMOKE_BASE"] = smoke_base
    env["PYTHONPATH"] = ROOT  # fudan_sync 包在 elearning-sync/ 下
    result = subprocess.run(
        [sys.executable, "-c", _SCRIPT],
        cwd=smoke_base,                  # 便携模式 config.yaml 落在临时目录
        env=env,
        capture_output=True,
        text=True,
        timeout=60,
    )
    assert result.returncode == 0, result.stderr
    assert "SEARCH_UI_OK" in result.stdout, result.stdout + result.stderr


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-q", "-p", "no:cacheprovider"]))
