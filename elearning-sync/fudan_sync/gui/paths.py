"""文件路径定位：配置、状态库、日志、同步根目录。

优先级：
  1. 便携模式：可执行文件（或开发期项目目录）旁边已有 config.yaml 就直接用它，
     这样用户已经下载好的课程文件可以原样复用，不必重新下载。
  2. 否则使用用户数据目录（Windows: %APPDATA%\\fudan-elearning-sync）。
"""

from __future__ import annotations

import os
import sys

# 应用显示名（v1.0.3 起品牌更名为“复小学”，但数据目录名保持不变，
# 老用户已下载的课程文件、配置与状态库可以原样复用）。
APP_NAME = "复小学"
_LEGACY_APP_NAME = "fudan-elearning-sync"


def is_frozen() -> bool:
    return bool(getattr(sys, "frozen", False))


def bundle_dir() -> str:
    """可执行文件 / 项目根目录。"""
    if is_frozen():
        return os.path.dirname(sys.executable)
    # fudan_sync/gui/paths.py -> fudan_sync/ -> 项目根
    return os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _legacy_data_dir() -> str | None:
    """老版本的数据目录；存在且非空时返回，便于平滑迁移。"""
    if sys.platform == "win32":
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
    elif sys.platform == "darwin":
        base = os.path.expanduser("~/Library/Application Support")
    else:
        base = os.environ.get("XDG_DATA_HOME") or os.path.expanduser("~/.local/share")
    legacy = os.path.join(base, _LEGACY_APP_NAME)
    try:
        if os.path.isdir(legacy) and os.listdir(legacy):
            return legacy
    except OSError:
        # 目录存在但不可读（权限受限等）：无法判断是否有数据，当作没有
        pass
    return None


def app_data_dir() -> str:
    """用户数据目录（存放配置、状态库、日志）。

    老版本（<=1.0.2）使用 fudan-elearning-sync 目录，里面可能有用户已
    下载的课程文件与状态库；改名复小学后优先复用该目录，避免重新下载。
    """
    legacy = _legacy_data_dir()
    if legacy:
        return legacy
    if sys.platform == "win32":
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
    elif sys.platform == "darwin":
        base = os.path.expanduser("~/Library/Application Support")
    else:
        base = os.environ.get("XDG_DATA_HOME") or os.path.expanduser("~/.local/share")
    return os.path.join(base, APP_NAME)


def _portable_config_path() -> str | None:
    """便携模式：exe 或项目目录里已有的 config.yaml。"""
    for directory in (bundle_dir(), os.getcwd()):
        candidate = os.path.join(directory, "config.yaml")
        if os.path.exists(candidate):
            return candidate
    return None


def config_path() -> str:
    found = _portable_config_path()
    if found:
        return found
    return os.path.join(app_data_dir(), "config.yaml")


def gui_state_path() -> str:
    """GUI 自身状态（窗口大小、开机自启开关等），与业务配置分开存放。"""
    found = _portable_config_path()
    base = os.path.dirname(found) if found else app_data_dir()
    return os.path.join(base, "gui_state.json")


def default_root_dir() -> str:
    """默认同步根目录：优先复用已存在的课程目录。"""
    candidates = []
    documents = os.path.join(os.path.expanduser("~"), "Documents")
    candidates.append(os.path.join(documents, "elearning_files"))
    for directory in (bundle_dir(), os.getcwd()):
        candidates.append(os.path.join(directory, "elearning_files"))
    for candidate in candidates:
        if os.path.isdir(candidate) and os.listdir(candidate):
            return candidate
    return candidates[0]


def ensure_data_dirs(config_file: str) -> None:
    """配置文件所在目录必须先存在，否则后续写状态库/日志会失败。"""
    directory = os.path.dirname(os.path.abspath(config_file))
    os.makedirs(directory, exist_ok=True)
