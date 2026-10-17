"""Windows 安装器的数据保留回归测试。"""

import re
from pathlib import Path

SETUP_SCRIPT = Path(__file__).resolve().parents[1] / "installer" / "setup.iss"


def test_uninstaller_does_not_recursively_delete_appdata():
    script = SETUP_SCRIPT.read_text(encoding="utf-8")

    assert "[UninstallDelete]" not in script
    assert not re.search(
        r"(?im)^Type:\s*filesandordirs;.*(?:%APPDATA%|\{userappdata\})",
        script,
    )
