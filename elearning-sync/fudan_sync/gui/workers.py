"""后台线程：登录与同步，避免阻塞界面。"""

from __future__ import annotations

import logging
from dataclasses import asdict, is_dataclass
from typing import Optional

from PySide6.QtCore import QThread, Signal

from ..auth import AuthError, CookieAuth, build_auth
from ..canvas_api import CanvasAPI
from ..config import ensure_runtime_dirs
from ..password_login import (
    PasswordLoginError,
    has_stored_password,  # noqa: F401
    login_and_save,
)
from ..state import StateStore
from ..sync_engine import SyncEngine


def _to_dict(obj) -> dict:
    if is_dataclass(obj):
        return {k: (list(v) if isinstance(v, tuple) else v) for k, v in asdict(obj).items()}
    return dict(obj) if obj else {}


class GuiLogHandler(logging.Handler):
    """把日志通过回调推到界面线程。"""

    def __init__(self, sink):
        super().__init__(level=logging.DEBUG)
        self._sink = sink

    def emit(self, record: logging.LogRecord) -> None:
        try:
            self._sink(record.levelname.lower(), record.getMessage())
        except Exception:  # pylint: disable=broad-except
            pass


class LoginWorker(QThread):
    """首次登录：账号密码 -> 保存 -> 验证 -> 回写配置。"""

    succeeded = Signal(dict)  # 当前用户信息
    failed = Signal(str, bool)  # (错误信息, 是否需要验证码)
    log = Signal(str, str)

    def __init__(self, cfg, username: str, password: str, remember: bool = True):
        super().__init__()
        self.cfg = cfg
        self.username = username.strip()
        self.password = password
        self.remember = remember

    def run(self) -> None:
        logger = logging.getLogger("gui_login")
        handler = GuiLogHandler(self.log.emit)
        logger.addHandler(handler)
        logger.setLevel(logging.DEBUG)
        logger.propagate = False
        try:
            login_and_save(
                self.cfg.base_url,
                self.username,
                self.password,
                self.cfg.cookie_file,
                store_credentials=self.remember,
            )
            # 登录已把会话 cookie 落盘，直接用 cookie 验证用户信息。
            # 不能调 build_auth(self.cfg)：首次登录时配置里 method 还是空的，
            # load_config 会把它回退成默认的 "token"，于是走到 TokenAuth("") 抛
            # “API Token 为空”。
            auth = CookieAuth(self.cfg.cookie_file)
            api = CanvasAPI(self.cfg.base_url, auth, logger=logger)
            user = api.get_current_user()
            if not user.get("id"):
                self.failed.emit("登录后无法获取用户信息，请重试", False)
                return
            # 立刻把认证方式持久化为密码登录，之后启动即可静默登录。
            self._persist_auth_method()
            self.succeeded.emit(
                {
                    "name": user.get("name") or user.get("short_name") or self.username,
                    "id": user.get("id"),
                    "username": self.username,
                    "remember": self.remember,
                }
            )
        except PasswordLoginError as exc:
            self.failed.emit(str(exc), exc.need_captcha)
        except Exception as exc:  # pylint: disable=broad-except
            self.failed.emit(f"登录失败：{exc}", False)
        finally:
            logger.removeHandler(handler)

    def _persist_auth_method(self) -> None:
        """把认证方式落盘，并同步更新内存中的配置。

        记住了密码就写 password（之后静默重登）；没记住就写 cookie，
        复用本次登录已保存的会话。两者都记下账号名，方便界面展示。
        """
        from .config_io import update_config

        method = "password" if self.remember else "cookie"
        try:
            update_config(
                self.cfg.config_path,
                [
                    (("auth", "method"), method),
                    (("auth", "uis_username"), self.username),
                ],
            )
            self.cfg.auth_method = method
            self.cfg.uis_username = self.username
        except OSError:
            # 配置写失败不影响本次登录结果，主界面会在登录成功后再写一次
            pass


class SilentLoginWorker(QThread):
    """启动时静默登录（用钥匙串里的密码），只在失败时提示用户重新登录。"""

    succeeded = Signal(dict)
    failed = Signal(str)

    def __init__(self, cfg):
        super().__init__()
        self.cfg = cfg

    def run(self) -> None:
        try:
            method = (self.cfg.auth_method or "").lower()
            token = self.cfg.token or ""
            # 既没配 token、钥匙串里也没存密码：引导用户去登录。
            # 认证方式的自动回退（老配置 method 缺失 => password）与持久化
            # 统一交给 build_auth 处理，这里只做"是否有可用凭据"的预判。
            if method in ("", "token") and not token:
                if not (self.cfg.uis_username and has_stored_password(self.cfg.uis_username)):
                    self.failed.emit("尚未配置登录凭据，请先登录")
                    return
            auth = build_auth(self.cfg)
            api = CanvasAPI(self.cfg.base_url, auth)
            user = api.get_current_user()
            if not user.get("id"):
                self.failed.emit("登录已过期，请重新登录")
                return
            self.succeeded.emit(
                {
                    "name": user.get("name") or user.get("short_name") or self.cfg.uis_username,
                    "id": user.get("id"),
                }
            )
        except (AuthError, PasswordLoginError) as exc:
            self.failed.emit(str(exc))
        except Exception as exc:  # pylint: disable=broad-except
            msg = str(exc)
            # 401 统一描述为登录已过期
            if "401" in msg:
                self.failed.emit("登录已过期，请使用 UIS 账号密码重新登录")
            else:
                self.failed.emit(f"自动登录失败：{exc}")


class SyncWorker(QThread):
    """后台同步：实时输出日志与进度，可中途停止。"""

    log = Signal(str, str)
    progress = Signal(str, dict)
    finished_ok = Signal(dict)
    failed = Signal(str)

    def __init__(self, cfg, full: bool = False, course_ids=None):
        super().__init__()
        self.cfg = cfg
        self.full = full
        self.course_ids = list(course_ids) if course_ids else None
        self._engine: Optional[SyncEngine] = None
        self._state: Optional[StateStore] = None

    def stop(self) -> None:
        if self._engine is not None:
            self._engine.stop()

    def run(self) -> None:
        logger = logging.getLogger("gui_sync")
        handler = GuiLogHandler(self.log.emit)
        logger.addHandler(handler)
        logger.setLevel(logging.DEBUG)
        logger.propagate = False
        try:
            ensure_runtime_dirs(self.cfg)
            if self.cfg.auth_method == "password":
                self.progress.emit("phase", {"text": "正在登录…"})
            auth = build_auth(self.cfg)
            api = CanvasAPI(self.cfg.base_url, auth, logger=logger)
            self._state = StateStore(self.cfg.state_db, logger=logger)
            self._engine = SyncEngine(
                self.cfg,
                api,
                self._state,
                logger=logger,
                progress_cb=lambda kind, payload: self.progress.emit(kind, payload),
            )
            stats = self._engine.run(full=self.full, course_ids=self.course_ids)
            self.finished_ok.emit(_to_dict(stats))
        except (AuthError, PasswordLoginError) as exc:
            self.failed.emit(str(exc))
        except Exception as exc:  # pylint: disable=broad-except
            self.failed.emit(f"同步出错：{exc}")
        finally:
            if self._state is not None:
                try:
                    self._state.close()
                except Exception:  # pylint: disable=broad-except
                    pass
            logger.removeHandler(handler)
            self._engine = None
            self._state = None
