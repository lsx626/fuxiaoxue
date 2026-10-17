#!/usr/bin/env python3
"""复小学 - 复旦大学 eLearning (Canvas LMS) 课程文件同步工具（命令行入口）。

常用命令：
  python sync.py login --method password   # 用 UIS 账号密码登录（密码存入系统钥匙串，之后自动登录）
  python sync.py login --method browser     # 浏览器交互式登录，自动保存 Cookie
  python sync.py login --method token       # 使用 API Token 登录并验证
  python sync.py courses                    # 列出可同步的课程
  python sync.py sync                       # 执行一次增量同步
  python sync.py sync --full                # 全量重新同步
  python sync.py daemon                     # 守护进程：定时增量同步（实时更新）
  python sync.py status                     # 查看本地同步状态
"""

from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fudan_sync.auth import (
    AuthError,
    CookieAuth,
    TokenAuth,  # noqa: E402
    browser_login,
    build_auth,
)
from fudan_sync.canvas_api import CanvasAPI  # noqa: E402
from fudan_sync.config import (
    AppConfig,
    ensure_runtime_dirs,
    load_config,  # noqa: E402
    validate_config,
)
from fudan_sync.daemon import SyncDaemon  # noqa: E402
from fudan_sync.password_login import PasswordLoginError, login_and_save  # noqa: E402
from fudan_sync.state import StateStore  # noqa: E402
from fudan_sync.sync_engine import SyncEngine  # noqa: E402
from fudan_sync.utils import format_size, setup_logger  # noqa: E402

DEFAULT_CONFIG = "config.yaml"


__all__ = ["build_auth", "main"]


def cmd_login(args, cfg: AppConfig, logger) -> int:
    method = args.method
    if method == "browser":
        logger.info("启动浏览器登录（UIS 统一身份认证）...")
        try:
            auth = browser_login(
                cfg.base_url, cfg.cookie_file, headless=args.headless, timeout_seconds=args.timeout
            )
        except AuthError as exc:
            logger.error("浏览器登录失败: %s", exc)
            return 1
        cfg.auth_method = "cookie"
        _persist_auth_method(cfg, "cookie", logger)
    elif method == "token":
        token = args.token or os.environ.get("FUDAN_ELEARNING_TOKEN") or cfg.token
        if not token:
            logger.error("未提供 Token，请使用 --token 参数或设置环境变量 FUDAN_ELEARNING_TOKEN")
            return 1
        cfg.token = token
        cfg.auth_method = "token"
        _persist_auth_method(cfg, "token", logger)
        auth = TokenAuth(token)
    elif method == "cookie":
        if not os.path.exists(cfg.cookie_file):
            logger.error(
                "Cookie 文件不存在: %s，请先使用 browser 方式登录，或手动导出 Cookie",
                cfg.cookie_file,
            )
            return 1
        # 持久化认证方式：否则下次直接运行 sync/daemon 仍会按旧方式认证而失败
        cfg.auth_method = "cookie"
        _persist_auth_method(cfg, "cookie", logger)
        auth = CookieAuth(cfg.cookie_file)
    elif method == "password":
        username = args.username or cfg.uis_username
        if not username:
            logger.error("请使用 --username 指定 UIS 账号（学号/邮箱）")
            return 1
        password = args.password or os.environ.get("FUDAN_UIS_PASSWORD")
        if not password:
            import getpass

            try:
                password = getpass.getpass(f"请输入 UIS 账号 {username} 的密码: ")
            except (EOFError, KeyboardInterrupt):
                logger.error("未输入密码")
                return 1
            if not password:
                logger.error("密码不能为空")
                return 1
        try:
            login_and_save(
                cfg.base_url,
                username,
                password,
                cfg.cookie_file,
                store_credentials=not args.no_save,
            )
        except PasswordLoginError as exc:
            logger.error("登录失败: %s", exc)
            return 1
        cfg.uis_username = username
        cfg.auth_method = "password"
        _persist_auth_method(cfg, "password", logger)
        auth = CookieAuth(cfg.cookie_file)
    else:
        logger.error("未知登录方式: %s", method)
        return 1

    # 验证认证可用性
    api = CanvasAPI(cfg.base_url, auth, logger=logger)
    user = api.get_current_user()
    if not user.get("id"):
        logger.error("认证无效或已过期，请重新登录")
        return 1
    logger.info(
        "登录成功！用户: %s（id=%s）", user.get("name") or user.get("short_name"), user.get("id")
    )
    return 0


def _persist_auth_method(cfg: AppConfig, method: str, logger) -> None:
    """把选定的认证方式写回配置文件，方便后续直接运行 sync/daemon。"""
    try:
        import yaml

        path = cfg.config_path
        data = {}
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as handle:
                data = yaml.safe_load(handle) or {}
        data.setdefault("auth", {})["method"] = method
        if method == "token" and cfg.token:
            # 持久化 Token，否则登录验证成功后后续 sync 仍会因缺 Token 失败
            data["auth"]["token"] = cfg.token
        if method == "password" and cfg.uis_username:
            # 账号写配置（非敏感），密码存在系统钥匙串里
            data["auth"]["uis_username"] = cfg.uis_username
        with open(path, "w", encoding="utf-8") as handle:
            yaml.safe_dump(data, handle, allow_unicode=True, sort_keys=False)
    except Exception as exc:  # pylint: disable=broad-except
        logger.debug("回写认证方式失败（不影响使用）: %s", exc)


def cmd_courses(args, cfg: AppConfig, logger) -> int:
    auth = build_auth(cfg)
    api = CanvasAPI(cfg.base_url, auth, logger=logger)
    courses = api.list_courses(
        enrollment_type=cfg.sync.enrollment_type, only_favorites=cfg.sync.only_favorites
    )
    if not courses:
        logger.warning("未找到课程，请检查认证状态或 enrollment_type 设置")
        return 1
    logger.info("共 %d 门课程：", len(courses))
    for course in courses:
        term = ""
        if isinstance(course.get("term"), dict):
            term = course["term"].get("name") or ""
        code = course.get("course_code") or course.get("sis_course_id") or ""
        logger.info(
            "  [%d] %s %s%s",
            course["id"],
            course.get("name", ""),
            f"({code})" if code else "",
            f" | {term}" if term else "",
        )
    return 0


def cmd_sync(args, cfg: AppConfig, logger) -> int:
    state = StateStore(cfg.state_db, logger=logger)
    auth = build_auth(cfg)
    api = CanvasAPI(cfg.base_url, auth, logger=logger)
    engine = SyncEngine(cfg, api, state, logger=logger)
    try:
        stats = engine.run(full=args.full, course_ids=args.courses or None)
        return 1 if (stats.errors and stats.files_downloaded == 0) else 0
    finally:
        state.close()


def cmd_daemon(args, cfg: AppConfig, logger) -> int:
    state = StateStore(cfg.state_db, logger=logger)
    auth = build_auth(cfg)
    api = CanvasAPI(cfg.base_url, auth, logger=logger)
    engine = SyncEngine(cfg, api, state, logger=logger)
    daemon = SyncDaemon(
        cfg,
        engine,
        state,
        interval_minutes=args.interval or cfg.sync.interval_minutes,
        logger=logger,
        single=args.single,
    )
    try:
        return daemon.run()
    finally:
        state.close()


def cmd_status(args, cfg: AppConfig, logger) -> int:
    if not os.path.exists(cfg.state_db):
        logger.warning("状态库不存在：尚未执行过同步。请先运行 sync。")
        return 1
    state = StateStore(cfg.state_db, logger=logger)
    try:
        stats = state.stats()
        last = state.last_run()
        logger.info("===== 同步状态 =====")
        logger.info("本地根目录: %s", cfg.root_dir)
        logger.info("课程数: %d", stats["courses"])
        logger.info(
            "文件总数: %d（已下载 %d，待下载 %d，失败 %d，远端已移除 %d）",
            stats["files_total"],
            stats["files_downloaded"],
            stats["files_pending"],
            stats["files_failed"],
            stats["files_missing"],
        )
        logger.info("已下载总量: %s", format_size(stats["bytes"]))
        if last:
            logger.info(
                "最近一次同步: %s（%s，成功 %d / 失败 %d）",
                last.get("finished_at"),
                last.get("mode"),
                last.get("files_downloaded"),
                last.get("files_failed"),
            )
        if args.courses:
            for course in state.list_courses():
                files = state.list_files_by_course(course["id"])
                downloaded = sum(1 for f in files if f["status"] == "downloaded")
                logger.info(
                    "  [%d] %s: %d/%d 已下载", course["id"], course["name"], downloaded, len(files)
                )
    finally:
        state.close()
    return 0


def cmd_list_files(args, cfg: AppConfig, logger) -> int:
    if not os.path.exists(cfg.state_db):
        logger.warning("状态库不存在：尚未执行过同步。")
        return 1
    state = StateStore(cfg.state_db, logger=logger)
    try:
        for course in state.list_courses():
            if args.course and course["id"] != args.course:
                continue
            logger.info("===== [%d] %s =====", course["id"], course["name"])
            for f in state.list_files_by_course(course["id"]):
                logger.info(
                    "  %-8s %s  %s",
                    f["status"],
                    format_size(f["size"] or 0),
                    os.path.join(f["folder_path"] or "", f["filename"]),
                )
    finally:
        state.close()
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog="sync.py",
        description="复旦大学 eLearning (Canvas LMS) 课程文件自动同步工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "-c", "--config", default=DEFAULT_CONFIG, help=f"配置文件路径（默认 {DEFAULT_CONFIG}）"
    )
    sub = parser.add_subparsers(dest="command")

    login = sub.add_parser("login", help="登录并保存凭据")
    login.add_argument(
        "--method",
        choices=["token", "cookie", "browser", "password"],
        default="browser",
        help="登录方式",
    )
    login.add_argument("--token", help="Canvas API Token（method=token 时使用）")
    login.add_argument("--username", help="UIS 账号（学号/邮箱，method=password 时使用）")
    login.add_argument("--password", help="UIS 密码（method=password 时使用；省略则交互输入）")
    login.add_argument("--no-save", action="store_true", help="不把密码存入系统钥匙串")
    login.add_argument(
        "--headless", action="store_true", help="无头浏览器（不推荐，UIS 可能需验证码）"
    )
    login.add_argument("--timeout", type=int, default=900, help="登录等待超时（秒）")
    login.set_defaults(func=cmd_login)

    courses = sub.add_parser("courses", help="列出可同步的课程")
    courses.set_defaults(func=cmd_courses)

    sync = sub.add_parser("sync", help="执行一次同步（默认增量）")
    sync.add_argument("--full", action="store_true", help="全量同步（忽略本地状态，重新下载）")
    sync.add_argument("--courses", type=int, nargs="*", help="仅同步指定课程 ID")
    sync.set_defaults(func=cmd_sync)

    daemon = sub.add_parser("daemon", help="守护进程：定时增量同步（保持实时更新）")
    daemon.add_argument("--interval", type=int, help="轮询间隔（分钟），覆盖配置文件")
    daemon.add_argument("--single", action="store_true", help="只运行一轮后退出（调试用）")
    daemon.set_defaults(func=cmd_daemon)

    status = sub.add_parser("status", help="查看同步状态")
    status.add_argument("--courses", action="store_true", help="显示每门课程的明细")
    status.set_defaults(func=cmd_status)

    files = sub.add_parser("files", help="列出本地已记录的文件")
    files.add_argument("--course", type=int, help="只列出指定课程")
    files.set_defaults(func=cmd_list_files)

    args = parser.parse_args(argv)

    # 加载配置
    cfg = load_config(args.config)
    if not os.path.exists(args.config):
        # 自动从示例生成一份配置
        example = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.example.yaml")
        if os.path.exists(example):
            import shutil

            shutil.copyfile(example, args.config)
            cfg = load_config(args.config)
            print(f"[config] 已从示例生成配置文件: {args.config}，请按需修改后重新运行。")

    logger = setup_logger(log_file=cfg.log_file)
    logger.info("eLearning 同步工具启动（配置: %s）", args.config)

    problems = validate_config(cfg)
    if problems and args.command != "login":
        for problem in problems:
            logger.error("配置问题: %s", problem)
        return 1

    if not args.command:
        parser.print_help()
        return 0

    ensure_runtime_dirs(cfg)
    try:
        return args.func(args, cfg, logger)
    except AuthError as exc:
        logger.error("认证错误: %s", exc)
        return 1
    except KeyboardInterrupt:
        logger.info("用户中断")
        return 130
    except Exception as exc:  # pylint: disable=broad-except
        logger.exception("执行失败: %s", exc)
        return 1


if __name__ == "__main__":
    sys.exit(main())
