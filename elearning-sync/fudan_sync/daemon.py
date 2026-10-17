"""守护进程：按间隔循环执行增量同步，实现"实时"本地与网站一致。

说明：Canvas LMS 不会向学生推送文件变更事件，真正的实时推送不可行；
本工具采用「定时轮询 + 增量比对」的方式，间隔越短越接近实时。
首次运行为全量同步，之后每轮仅下载新增/变更的文件。
"""

from __future__ import annotations

import signal
import threading
import time
from datetime import datetime, timezone

from .config import AppConfig
from .state import StateStore
from .sync_engine import SyncEngine


class SyncDaemon:
    def __init__(
        self,
        config: AppConfig,
        engine: SyncEngine,
        state: StateStore,
        interval_minutes: int,
        logger=None,
        single: bool = False,
    ):
        self.cfg = config
        self.engine = engine
        self.state = state
        self.interval = max(1, int(interval_minutes)) * 60
        self.log = logger
        self.single = single
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._running = False

    def _log(self, level: str, msg: str, *args) -> None:
        if self.log is not None:
            getattr(self.log, level)(msg, *args)

    def stop(self, *_args) -> None:
        self._log("info", "正在停止守护进程（等待当前同步周期结束）...")
        self._stop.set()
        try:
            self.engine.downloader.stop()
        except Exception:  # pylint: disable=broad-except
            pass

    def install_signal_handlers(self) -> None:
        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                signal.signal(sig, self.stop)
            except (OSError, ValueError):
                pass

    def run(self) -> int:
        self.install_signal_handlers()
        self._log("info", "守护进程启动：每 %d 分钟同步一次（Ctrl+C 退出）", self.interval // 60)
        run_count = 0

        while not self._stop.is_set():
            with self._lock:
                self._running = True
            started = datetime.now(timezone.utc)
            try:
                full = run_count == 0  # 首次全量，之后增量
                self._log(
                    "info",
                    "==== 第 %d 轮同步开始（%s）====",
                    run_count + 1,
                    "全量" if full else "增量",
                )
                stats = self.engine.run(full=full)
                run_count += 1
                if stats.errors:
                    self._log("warning", "本轮存在 %d 个错误，详见日志", stats.errors)
            except Exception as exc:  # pylint: disable=broad-except
                self._log("error", "同步周期发生未捕获异常: %s", exc)
            finally:
                with self._lock:
                    self._running = False

            if self.single or self._stop.is_set():
                break

            # 等待下一周期（可被打断）
            elapsed = (datetime.now(timezone.utc) - started).total_seconds()
            sleep_secs = max(10.0, self.interval - elapsed)
            self._log("info", "下一轮同步将在 %.0f 分钟后开始", sleep_secs / 60)
            self._interruptible_sleep(sleep_secs)

        self._log("info", "守护进程已退出（共完成 %d 轮同步）", run_count)
        return 0

    def _interruptible_sleep(self, seconds: float) -> None:
        deadline = time.time() + seconds
        while not self._stop.is_set():
            remaining = deadline - time.time()
            if remaining <= 0:
                break
            time.sleep(min(remaining, 5.0))

    @property
    def is_running(self) -> bool:
        return self._running
