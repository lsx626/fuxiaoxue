"""桌面端 P2 修复的回归测试：

1. 设置页保存「同步根目录」必须真正写入 root_dir（历史缺陷：键路径误传
   字符串，把值写进 r:{o:{o:{t:{_:{d:{i:{r:…}}}}}}} 垃圾嵌套键，
   用户改的目录永不生效）；
2. update_config 的多级键路径与删除语义；
3. 开机自启命令行只能包一层引号（双重引号会让 CreateProcess 以
   ERROR_INVALID_PARAMETER(87) 拒绝启动）；
4. save_yaml 原子写入，且不残留 .tmp。
"""
from __future__ import annotations

import os
import shlex
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import yaml  # noqa: E402

from fudan_sync.config import load_config  # noqa: E402
from fudan_sync.gui import autostart  # noqa: E402
from fudan_sync.gui.config_io import load_yaml, save_yaml, update_config  # noqa: E402


def _write_config(path: str, root: str = "./old_files") -> None:
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(
            "base_url: https://elearning.fudan.edu.cn\n"
            "auth:\n"
            "  method: token\n"
            "  token: dummy\n"
            f"root_dir: {root}\n"
            "state_db: ./st.db\n"
            "log_file:\n"
            "sync:\n"
            "  interval_minutes: 15\n"
            "  download:\n"
            "    concurrency: 4\n"
        )


class UpdateConfigKeyPathTests(unittest.TestCase):
    """键路径必须是元组；误传字符串会逐字符迭代出垃圾嵌套键。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.path = os.path.join(self.tmp, "config.yaml")
        _write_config(self.path)

    def test_tuple_key_path_writes_top_level_value(self):
        update_config(self.path, [(("root_dir",), "D:/new_files")])
        data = load_yaml(self.path)
        self.assertEqual("D:/new_files", data["root_dir"])

    def test_string_key_path_is_rejected(self):
        # settings_dialog 曾经把 "root_dir" 当键路径传入，update_config 会逐字符
        # 迭代出 r:{o:{o:{t:…}}} 垃圾嵌套键；现在必须显式报错，杜绝静默写坏。
        with self.assertRaises(TypeError):
            update_config(self.path, [("root_dir", "D:/new_files")])
        # 配置文件保持原样
        self.assertEqual("./old_files", load_yaml(self.path)["root_dir"])
        self.assertNotIn("r", load_yaml(self.path))

    def test_nested_path_and_delete(self):
        update_config(self.path, [
            (("sync", "download", "concurrency"), 8),
            (("auth", "token"), None),
        ])
        data = load_yaml(self.path)
        self.assertEqual(8, data["sync"]["download"]["concurrency"])
        self.assertNotIn("token", data["auth"])
        # 未涉及的键必须保留（update_config 的既有契约）
        self.assertEqual("dummy-missing", data.get("not_exists", "dummy-missing"))
        self.assertEqual(15, data["sync"]["interval_minutes"])


class SettingsRootDirSaveTests(unittest.TestCase):
    """设置对话框保存后，config.yaml 的 root_dir 必须是用户选择的新目录。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.path = os.path.join(self.tmp, "config.yaml")
        _write_config(self.path)
        # SettingsDialog 在没有 QApplication 的进程里无法构造；有则用真实控件路径
        from PySide6.QtWidgets import QApplication
        import fudan_sync.gui.settings_dialog as sd

        self.app = QApplication.instance() or QApplication([])
        self.sd = sd
        self.calls = []
        self.original_set_autostart = sd.autostart.set_autostart
        sd.autostart.set_autostart = lambda enabled: self.calls.append(enabled)

    def tearDown(self):
        self.sd.autostart.set_autostart = self.original_set_autostart

    def test_save_root_dir_actually_persists(self):
        new_root = os.path.join(self.tmp, "new_files")
        dialog = self.sd.SettingsDialog(load_config(self.path), self.path)
        dialog.root_edit.setText(new_root)
        dialog._on_save()
        dialog.deleteLater()
        self.app.processEvents()

        data = load_yaml(self.path)
        self.assertEqual(new_root, data["root_dir"])
        # 垃圾嵌套键不得出现
        self.assertNotIn("r", data)
        # 重新加载后配置对象也指向新目录
        cfg = load_config(self.path)
        self.assertEqual(new_root, cfg.root_dir)


class AutostartQuotingTests(unittest.TestCase):
    """自启命令行不得被双重引号包坏（Windows Run 键按 CreateProcess 语义执行）。"""

    def test_startup_target_single_layer_quoting(self):
        target = autostart._startup_target()
        self.assertTrue(target.startswith('"'))
        # 双重引号（""…"" 形式）是旧缺陷的直接特征
        self.assertFalse(target.startswith('""'))
        self.assertTrue(target.endswith("--minimized"))

        if not getattr(sys, "frozen", False):
            # 源码模式：<python> <gui.py> --minimized，恰好两对引号
            self.assertEqual(4, target.count('"'))
            parts = [p for p in target.split('"') if p.strip()]
            self.assertEqual(parts[-1].strip(), "--minimized")
            self.assertTrue(os.path.exists(parts[0]), f"{parts[0]} 应是真实路径")


class SaveYamlAtomicTests(unittest.TestCase):
    def test_save_leaves_no_tmp_and_writes_content(self):
        tmp = tempfile.mkdtemp()
        path = os.path.join(tmp, "config.yaml")
        save_yaml(path, {"a": 1, "b": {"c": "中文"}})
        self.assertEqual({"a": 1, "b": {"c": "中文"}}, load_yaml(path))
        self.assertFalse(os.path.exists(path + ".tmp"))
        self.assertEqual([], [f for f in os.listdir(tmp) if f.endswith(".tmp")])


if __name__ == "__main__":
    unittest.main(verbosity=2)
