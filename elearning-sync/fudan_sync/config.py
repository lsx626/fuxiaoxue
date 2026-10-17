"""配置加载与校验."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

import yaml


@dataclass
class DownloadConfig:
    concurrency: int = 4
    max_retries: int = 5
    max_file_size_mb: float = 0
    min_free_space_gb: float = 1
    exclude_extensions: List[str] = field(default_factory=list)
    # Canvas 系统生成的目录（如课程封面图），默认不同步
    exclude_folders: List[str] = field(default_factory=lambda: ["course_image"])
    # 安装包类垃圾文件，默认不同步
    exclude_installer_files: bool = True


@dataclass
class SyncConfig:
    interval_minutes: int = 15
    only_favorites: bool = False
    enrollment_type: str = "student"
    include_courses: List[int] = field(default_factory=list)
    exclude_courses: List[int] = field(default_factory=list)
    include_terms: List[str] = field(default_factory=list)
    download: DownloadConfig = field(default_factory=DownloadConfig)
    prune: bool = False
    archive_pages: bool = True
    # 没有任何文件与页面的"空课程"（含组织站点）不创建本地目录
    skip_empty_courses: bool = True


@dataclass
class AppConfig:
    base_url: str = "https://elearning.fudan.edu.cn"
    auth_method: str = "token"
    token: str = ""
    cookie_file: str = "cookies.json"
    # UIS 账号密码登录：账号存配置（非敏感），密码存系统钥匙串
    uis_username: str = ""
    root_dir: str = "./elearning_files"
    state_db: str = "./sync_state.db"
    log_file: Optional[str] = "./sync.log"
    sync: SyncConfig = field(default_factory=SyncConfig)
    config_path: str = "config.yaml"

    @property
    def api_base(self) -> str:
        return self.base_url.rstrip("/") + "/api/v1"


def _normalize_ext(value: Any) -> str:
    """
    统一排除扩展名的写法：小写、去空白、并且总是带前导点。

    引擎用 `os.path.splitext(name)[1]` 比较（形如 ".exe"），而 GUI 历史配置里
    存的是 "exe"；不归一化会让排除规则静默失效（用户以为排除了，实际照下）。
    """
    text = str(value or "").strip().lower()
    if not text:
        return ""
    return text if text.startswith(".") else "." + text


def _deep_get(data: Dict[str, Any], *keys, default=None):
    node = data
    for key in keys:
        if not isinstance(node, dict):
            return default
        node = node.get(key)
        if node is None:
            return default
    return node


def _resolve_config_path(value: Any, config_dir: str) -> str:
    """Resolve a path from YAML relative to the YAML file itself.

    The command line and GUI can be launched from arbitrary working
    directories.  Resolving relative paths against ``os.getcwd()`` makes a
    config silently write cookies, state, or downloads somewhere else (and
    is particularly surprising for password-login fallback).  Keep absolute
    paths untouched while expanding ``~`` and anchoring relative values to
    the config directory.
    """
    raw = os.path.expanduser(str(value))
    if os.path.isabs(raw):
        return os.path.normpath(raw)
    return os.path.normpath(os.path.join(config_dir, raw))


def load_config(path: str) -> AppConfig:
    """从 YAML 加载配置；文件不存在时使用默认值。"""
    # A config is a self-contained unit: all relative data paths are rooted
    # beside it, independent of the process working directory.
    config_path = os.path.abspath(os.path.expanduser(path))
    config_dir = os.path.dirname(config_path)
    cfg = AppConfig(config_path=config_path)
    cfg.root_dir = _resolve_config_path(cfg.root_dir, config_dir)
    cfg.state_db = _resolve_config_path(cfg.state_db, config_dir)
    cfg.cookie_file = _resolve_config_path(cfg.cookie_file, config_dir)
    if cfg.log_file:
        cfg.log_file = _resolve_config_path(cfg.log_file, config_dir)
    if not os.path.exists(config_path):
        return cfg

    with open(config_path, "r", encoding="utf-8") as handle:
        data = yaml.safe_load(handle) or {}

    cfg.base_url = data.get("base_url", cfg.base_url).rstrip("/")
    cfg.root_dir = _resolve_config_path(data.get("root_dir", cfg.root_dir), config_dir)
    cfg.state_db = _resolve_config_path(data.get("state_db", cfg.state_db), config_dir)
    log_file = data.get("log_file")
    cfg.log_file = _resolve_config_path(log_file, config_dir) if log_file else None

    auth = data.get("auth") or {}
    cfg.auth_method = (auth.get("method") or "token").lower()
    cfg.token = auth.get("token") or ""
    cfg.uis_username = str(auth.get("uis_username") or "")
    # 兼容三种键名：auth.method_cookie_file（旧）/ auth.cookie_file / 顶层 cookie_file
    cfg.cookie_file = _resolve_config_path(
        auth.get("method_cookie_file")
        or auth.get("cookie_file")
        or data.get("cookie_file")
        or "cookies.json",
        config_dir,
    )
    # 环境变量覆盖（便于 CI / 脚本注入，避免明文写在配置里）
    cfg.token = os.environ.get("FUDAN_ELEARNING_TOKEN", cfg.token)

    sync_data = data.get("sync") or {}
    cfg.sync = SyncConfig(
        interval_minutes=int(sync_data.get("interval_minutes", 15)),
        only_favorites=bool(sync_data.get("only_favorites", False)),
        enrollment_type=sync_data.get("enrollment_type", "student"),
        include_courses=[int(c) for c in (sync_data.get("include_courses") or [])],
        exclude_courses=[int(c) for c in (sync_data.get("exclude_courses") or [])],
        include_terms=[str(t) for t in (sync_data.get("include_terms") or [])],
        download=DownloadConfig(
            concurrency=int(_deep_get(sync_data, "download", "concurrency", default=4)),
            max_retries=int(_deep_get(sync_data, "download", "max_retries", default=5)),
            max_file_size_mb=float(_deep_get(sync_data, "download", "max_file_size_mb", default=0)),
            min_free_space_gb=float(
                _deep_get(sync_data, "download", "min_free_space_gb", default=1)
            ),
            exclude_extensions=[
                # 统一规范：小写、去空白、并且**总是带前导点**。
                # 引擎用 os.path.splitext() 得到的是 ".exe" 这类带点形式，
                # 而 GUI 历史上会把用户输入存成 "exe"，导致排除规则永远匹配不上。
                _normalize_ext(e)
                for e in (_deep_get(sync_data, "download", "exclude_extensions", default=[]) or [])
                if _normalize_ext(e)
            ],
            exclude_folders=[
                str(f).strip().lower()
                for f in (
                    _deep_get(sync_data, "download", "exclude_folders", default=["course_image"])
                    or []
                )
            ],
            exclude_installer_files=bool(
                _deep_get(sync_data, "download", "exclude_installer_files", default=True)
            ),
        ),
        prune=bool(sync_data.get("prune", False)),
        archive_pages=bool(sync_data.get("archive_pages", True)),
        skip_empty_courses=bool(sync_data.get("skip_empty_courses", True)),
    )
    return cfg


def validate_config(cfg: AppConfig) -> List[str]:
    """校验配置，返回问题列表（空列表表示通过）。"""
    problems: List[str] = []
    if not cfg.base_url.startswith("http"):
        problems.append(f"base_url 无效: {cfg.base_url}")
    if cfg.auth_method not in ("token", "cookie", "browser", "password"):
        problems.append(f"未知 auth.method: {cfg.auth_method}")
    if cfg.auth_method == "token" and not cfg.token:
        problems.append(
            "auth.method=token 但未配置 auth.token（可设置环境变量 FUDAN_ELEARNING_TOKEN）"
        )
    if cfg.auth_method == "password" and not cfg.uis_username:
        problems.append("auth.method=password 但未配置 auth.uis_username")
    if cfg.sync.download.concurrency < 1:
        problems.append("download.concurrency 必须 >= 1")
    if cfg.sync.interval_minutes < 1:
        problems.append("sync.interval_minutes 必须 >= 1")
    return problems


def ensure_runtime_dirs(cfg: AppConfig) -> None:
    os.makedirs(cfg.root_dir, exist_ok=True)
    os.makedirs(os.path.dirname(os.path.abspath(cfg.state_db)), exist_ok=True)
