"""通用工具：日志、路径安全处理、格式化、时间解析."""
from __future__ import annotations

import logging
import os
import re
import sys
from datetime import datetime, timezone

# Windows 文件/目录名非法字符（含控制字符）
_ILLEGAL_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
_RESERVED_NAMES = {
    "CON", "PRN", "AUX", "NUL",
    *(f"COM{i}" for i in range(1, 10)),
    *(f"LPT{i}" for i in range(1, 10)),
}
_MAX_NAME_LEN = 200

# 安装包类文件扩展名（VSCode 安装程序、Python manager 等），属"驳杂"内容
_INSTALLER_EXTS = {".exe", ".msi", ".msp", ".apk", ".dmg", ".deb", ".rpm", ".appimage"}


def setup_logger(name: str = "fudan_sync", log_file: str | None = None,
                 level: int = logging.INFO) -> logging.Logger:
    """配置并返回全局日志器（控制台 + 可选文件）。"""
    logger = logging.getLogger(name)
    if logger.handlers:  # 防止重复添加
        return logger
    logger.setLevel(level)
    logger.propagate = False

    fmt = logging.Formatter("[%(asctime)s] [%(levelname)s] %(message)s",
                            datefmt="%Y-%m-%d %H:%M:%S")
    console = logging.StreamHandler(sys.stdout)
    console.setFormatter(fmt)
    logger.addHandler(console)

    if log_file:
        try:
            os.makedirs(os.path.dirname(os.path.abspath(log_file)), exist_ok=True)
            fh = logging.FileHandler(log_file, encoding="utf-8")
            fh.setFormatter(fmt)
            logger.addHandler(fh)
        except OSError as exc:  # 日志路径不可写时不应中断程序
            logger.warning("无法写入日志文件 %s: %s", log_file, exc)
    return logger


def sanitize_path_component(name: str, fallback: str = "unnamed") -> str:
    """清洗单个路径分量，保证可安全用作 Windows/POSIX 文件名。"""
    if not name:
        return fallback
    cleaned = _ILLEGAL_CHARS.sub("_", str(name)).strip()
    # 去除首尾的点、空格（Windows 下结尾点/空格会被截断）与因替换产生的尾部下划线
    cleaned = cleaned.strip(" .")
    while cleaned.endswith("_"):
        cleaned = cleaned[:-1]
    cleaned = cleaned.strip(" .")
    if not cleaned:
        cleaned = fallback
    # Windows 设备名即使带扩展名也保留，例如 CON.txt、LPT1.log。
    # 只检查完整字符串会让这些名称在创建文件时仍然失败。
    reserved_stem = cleaned.split(".", 1)[0].rstrip(" .").upper()
    if reserved_stem in _RESERVED_NAMES:
        cleaned = f"_{cleaned}"
    if len(cleaned) > _MAX_NAME_LEN:
        stem, dot, ext = cleaned.rpartition(".")
        if dot and len(ext) <= 20:
            cleaned = stem[: _MAX_NAME_LEN - len(ext) - 1] + "." + ext
        else:
            cleaned = cleaned[:_MAX_NAME_LEN]
    return cleaned


def unique_path(path: str) -> str:
    """若 path 已存在，追加 (1)/(2)... 后缀返回可用路径。"""
    if not os.path.exists(path):
        return path
    base, ext = os.path.splitext(path)
    idx = 1
    while True:
        candidate = f"{base} ({idx}){ext}"
        if not os.path.exists(candidate):
            return candidate
        idx += 1


def format_size(num_bytes: float) -> str:
    """字节数转人类可读字符串。"""
    size = float(num_bytes)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(size) < 1024.0 or unit == "TB":
            return f"{size:.1f} {unit}" if unit != "B" else f"{int(size)} B"
        size /= 1024.0
    return f"{size:.1f} TB"


def clean_course_name(raw: str) -> str:
    """从 Canvas 课程名中剥离内嵌的选课代码（纯展示用，不改数据库）。

    复旦课程名常内嵌选课代码，如「数据处理与数据库 DATA130012.01」或
    「DATA130012.01 数据处理与数据库」；剥掉代码与残留的空括号/破折号。
    清洗结果为空时退回原名裁剪，避免界面显示空白。
    """
    if not raw:
        return raw
    code = r"[A-Z]{2,6}\d{5,6}(?:\.\d{1,2})?"
    # 1)「（CODE）」式：整对括号一起去（括号本身可能是名字的一部分，如「普通化学A（上）」）
    name = re.sub(rf"[（(]\s*{code}\s*[）)]", " ", raw)
    # 2) 裸代码：连同两侧空白一起吞掉
    name = re.sub(rf"\s*{code}\s*", " ", name)
    name = re.sub(r"\s+", " ", name).strip()
    return name or raw.strip()


def parse_iso8601(value: str | None) -> str | None:
    """统一 ISO8601 时间格式（去掉毫秒/时区差异），失败则原样返回。"""
    if not value:
        return None
    try:
        # 兼容带 Z / 带偏移的 Canvas 时间戳
        normalized = value.replace("Z", "+00:00")
        dt = datetime.fromisoformat(normalized)
        return dt.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
    except (ValueError, TypeError):
        return value


def now_utc() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")


def is_installer_file(filename: str) -> bool:
    """判断是否为安装包类垃圾文件（如 VSCodeUserSetup-x64.exe、python-manager.msi）。"""
    if not filename:
        return False
    ext = filename.rsplit(".", 1)[-1].lower()
    return f".{ext}" in _INSTALLER_EXTS


def free_space_gb(path: str) -> float:
    """获取 path 所在磁盘的可用空间（GB）。"""
    try:
        usage = os.statvfs(path) if hasattr(os, "statvfs") else None
        if usage is not None:
            return usage.f_bavail * usage.f_frsize / (1024 ** 3)
    except OSError:
        pass
    # Windows / 无 statvfs
    try:
        import ctypes
        free_bytes = ctypes.c_ulonglong(0)
        ctypes.windll.kernel32.GetDiskFreeSpaceExW(
            ctypes.c_wchar_p(path), None, None, ctypes.pointer(free_bytes))
        return free_bytes.value / (1024 ** 3)
    except Exception:  # pylint: disable=broad-except
        return float("inf")
