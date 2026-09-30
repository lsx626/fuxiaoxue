"""业务配置（config.yaml）与 GUI 状态（gui_state.json）的读写助手。

直接在 YAML 字典上做局部更新，不依赖数据类的序列化能力，也能保留未知字段。
"""
from __future__ import annotations

import json
import os
from typing import Any, Dict, List, Tuple

import yaml


def load_yaml(path: str) -> Dict[str, Any]:
    if not os.path.exists(path):
        return {}
    with open(path, "r", encoding="utf-8") as handle:
        return yaml.safe_load(handle) or {}


def save_yaml(path: str, data: Dict[str, Any]) -> None:
    """原子写入：先写临时文件再 os.replace，避免崩溃/交错写入产生半截 YAML
    导致下次启动 load_config 失败（GUI 里有多个线程会回写配置）。
    """
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as handle:
        yaml.safe_dump(data, handle, allow_unicode=True, sort_keys=False)
    os.replace(tmp, path)


def update_config(path: str, updates: List[Tuple[str, Any]]) -> Dict[str, Any]:
    """把 (键路径, 值) 列表合并写回配置文件，键路径支持多级，如 ("sync", "interval_minutes")。

    值为 None 时表示删除该键。返回写回后的完整字典。
    """
    data = load_yaml(path)
    for keys, value in updates:
        if isinstance(keys, str):
            # 字符串会被当作键路径逐字符迭代，把值写进单字母嵌套垃圾键——
            # 曾经的 settings_dialog root_dir 缺陷就是这个形态。必须显式报错。
            raise TypeError(
                f"update_config 的键路径必须是元组/列表（如 (\"sync\", \"interval_minutes\")），"
                f"收到字符串：{keys!r}"
            )
        node = data
        for key in keys[:-1]:
            if not isinstance(node.get(key), dict):
                node[key] = {}
            node = node[key]
        if value is None:
            node.pop(keys[-1], None)
        else:
            node[keys[-1]] = value
    save_yaml(path, data)
    return data


def load_gui_state(path: str) -> Dict[str, Any]:
    if not os.path.exists(path):
        return {}
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle) or {}
    except (json.JSONDecodeError, OSError):
        return {}


def save_gui_state(path: str, state: Dict[str, Any]) -> None:
    try:
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(state, handle, ensure_ascii=False, indent=2)
    except OSError:
        pass
