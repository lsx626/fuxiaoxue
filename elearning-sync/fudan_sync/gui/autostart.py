"""开机自启动（Windows 注册表 / macOS LaunchAgent / Linux .desktop）。"""
from __future__ import annotations

import os
import sys

APP_DISPLAY_NAME = "复小学"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def _startup_target() -> str:
    """自启时执行的命令行。

    Windows Run 键的值作为 CreateProcess 的 lpCommandLine执行：其中的
    可执行路径只能包**一层**引号。旧实现在源码运行模式下把
    `'"python.exe" "gui.py"'` 整体再包一层引号，得到
    `""python.exe" "gui.py"" --minimized`，CreateProcess 以
    ERROR_INVALID_PARAMETER(87) 拒绝启动，开机自启完全失效。
    """
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}" --minimized'
    gui_py = os.path.join(_project_root(), "gui.py")
    return f'"{sys.executable}" "{gui_py}" --minimized'


def _project_root() -> str:
    return os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def is_autostart_enabled() -> bool:
    if sys.platform == "win32":
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0,
                                winreg.KEY_READ) as key:
                value, _ = winreg.QueryValueEx(key, APP_DISPLAY_NAME)
                return bool(value)
        except FileNotFoundError:
            return False
        except OSError:
            return False
    if sys.platform == "darwin":
        return os.path.exists(_launchagent_path())
    return os.path.exists(_desktop_path())


def set_autostart(enabled: bool) -> None:
    if sys.platform == "win32":
        _set_autostart_windows(enabled)
    elif sys.platform == "darwin":
        _set_autostart_mac(enabled)
    else:
        _set_autostart_linux(enabled)


def _set_autostart_windows(enabled: bool) -> None:
    import winreg
    if enabled:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0,
                            winreg.KEY_SET_VALUE) as key:
            winreg.SetValueEx(key, APP_DISPLAY_NAME, 0, winreg.REG_SZ,
                              _startup_target())
        return
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0,
                            winreg.KEY_SET_VALUE) as key:
            winreg.DeleteValue(key, APP_DISPLAY_NAME)
    except FileNotFoundError:
        pass


def _launchagent_path() -> str:
    return os.path.expanduser(
        "~/Library/LaunchAgents/com.fudan.elearning-sync.plist")


def _set_autostart_mac(enabled: bool) -> None:
    path = _launchagent_path()
    if not enabled:
        if os.path.exists(path):
            os.remove(path)
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    content = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" '
        '"http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n'
        '<plist version="1.0">\n<dict>\n'
        '  <key>Label</key><string>com.fudan.elearning-sync</string>\n'
        '  <key>ProgramArguments</key>\n'
        '  <array>\n'
        f'    <string>{sys.executable}</string>\n'
        f'    <string>{_project_root()}/gui.py</string>\n'
        '    <string>--minimized</string>\n'
        '  </array>\n'
        '  <key>RunAtLoad</key><true/>\n'
        '</dict>\n</plist>\n'
    )
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(content)


def _desktop_path() -> str:
    return os.path.expanduser("~/.config/autostart/fudan-elearning-sync.desktop")


def _set_autostart_linux(enabled: bool) -> None:
    path = _desktop_path()
    if not enabled:
        if os.path.exists(path):
            os.remove(path)
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    content = (
        "[Desktop Entry]\n"
        "Type=Application\n"
        f"Name={APP_DISPLAY_NAME}\n"
        f"Exec={sys.executable} {_project_root()}/gui.py --minimized\n"
        "X-GNOME-Autostart-enabled=true\n"
    )
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(content)
