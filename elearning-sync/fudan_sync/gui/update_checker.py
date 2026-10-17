"""GitHub Release 更新检查。"""

from __future__ import annotations

import re

import requests
from PySide6.QtCore import QThread, Signal


class UpdateWorker(QThread):
    result = Signal(dict)
    failed = Signal(str)

    def run(self) -> None:
        try:
            response = requests.get(
                "https://api.github.com/repos/lsx626/fuxiaoxue/releases/latest",
                headers={"Accept": "application/vnd.github+json", "User-Agent": "FuXiaoXue"},
                timeout=8,
            )
            response.raise_for_status()
            data = response.json()
            self.result.emit(
                {
                    "tag": str(data.get("tag_name") or ""),
                    "url": str(data.get("html_url") or "https://github.com/lsx626/fuxiaoxue/releases"),
                }
            )
        except Exception as exc:  # pylint: disable=broad-except
            self.failed.emit(str(exc))


def version_tuple(value: str) -> tuple[int, ...]:
    numbers = re.findall(r"\d+", value or "")
    return tuple(int(item) for item in numbers[:4]) or (0,)


def has_update(current: str, latest: str) -> bool:
    return version_tuple(latest) > version_tuple(current)
