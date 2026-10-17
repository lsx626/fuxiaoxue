"""首次登录引导窗：只需输入 UIS 账号密码。"""

from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QGuiApplication
from PySide6.QtWidgets import (
    QCheckBox,
    QDialog,
    QFormLayout,
    QFrame,
    QLabel,
    QLineEdit,
    QPushButton,
    QVBoxLayout,
)

from .icon import app_icon
from .styles import ACCENT, DANGER, TEXT_SECONDARY
from .workers import LoginWorker


class LoginWindow(QDialog):
    """登录成功后发射 logged_in(user: dict)。"""

    logged_in = Signal(dict)

    def __init__(self, cfg, parent=None):
        super().__init__(parent)
        self.cfg = cfg
        self.worker: LoginWorker | None = None
        self.setWindowTitle("登录 · 复小学")
        self.setWindowIcon(app_icon())
        self.setFixedSize(480, 620)
        self.setObjectName("root")
        # 作为独立顶层窗口，有标题栏和关闭按钮
        self.setWindowFlags(Qt.Dialog | Qt.WindowCloseButtonHint)
        self._build_ui()
        self._center()

    def _center(self) -> None:
        screen = QGuiApplication.primaryScreen().availableGeometry()
        self.move(screen.center().x() - self.width() // 2, screen.center().y() - self.height() // 2)

    # ------------------------------------------------------------------
    def _build_ui(self) -> None:
        root = QVBoxLayout(self)
        root.setContentsMargins(36, 30, 36, 26)
        root.setSpacing(0)

        icon_label = QLabel()
        icon_label.setPixmap(app_icon().pixmap(88, 88))
        icon_label.setAlignment(Qt.AlignCenter)
        root.addSpacing(6)
        root.addWidget(icon_label)

        title = QLabel("复小学")
        title.setObjectName("titleLabel")
        title.setAlignment(Qt.AlignCenter)
        root.addSpacing(14)
        root.addWidget(title)

        subtitle = QLabel("首次使用，请输入复旦大学统一身份认证（UIS）账号与密码")
        subtitle.setObjectName("subtitleLabel")
        subtitle.setAlignment(Qt.AlignCenter)
        subtitle.setWordWrap(True)
        root.addSpacing(8)
        root.addWidget(subtitle)
        root.addSpacing(26)

        card = QFrame()
        card.setObjectName("card")
        form = QFormLayout(card)
        form.setContentsMargins(24, 22, 24, 22)
        form.setSpacing(12)
        form.setLabelAlignment(Qt.AlignRight | Qt.AlignVCenter)

        self.username_edit = QLineEdit()
        self.username_edit.setPlaceholderText("学号 / 邮箱（如 23300123456）")
        self.username_edit.setText(self.cfg.uis_username or "")
        self.username_edit.returnPressed.connect(self._on_login)

        self.password_edit = QLineEdit()
        self.password_edit.setPlaceholderText("UIS 密码")
        self.password_edit.setEchoMode(QLineEdit.Password)
        self.password_edit.returnPressed.connect(self._on_login)

        username_row = QLabel("账号")
        username_row.setStyleSheet(f"color: {TEXT_SECONDARY};")
        password_row = QLabel("密码")
        password_row.setStyleSheet(f"color: {TEXT_SECONDARY};")
        form.addRow(username_row, self.username_edit)
        form.addRow(password_row, self.password_edit)
        root.addWidget(card)

        self.remember_check = QCheckBox("记住密码（保存在本机系统钥匙串，不上传）")
        self.remember_check.setChecked(True)
        self.remember_check.setCursor(Qt.PointingHandCursor)
        root.addSpacing(14)
        root.addWidget(self.remember_check)

        self.error_label = QLabel("")
        self.error_label.setStyleSheet(f"color: {DANGER};")
        self.error_label.setWordWrap(True)
        self.error_label.setAlignment(Qt.AlignCenter)
        root.addSpacing(8)
        root.addWidget(self.error_label)

        self.login_button = QPushButton("登 录 并 同 步")
        self.login_button.setObjectName("primary")
        self.login_button.setCursor(Qt.PointingHandCursor)
        self.login_button.setMinimumHeight(42)
        self.login_button.clicked.connect(self._on_login)
        root.addSpacing(10)
        root.addWidget(self.login_button)

        self.progress = QFrame()  # 登录中的细条进度
        bar = QFrame()
        bar.setFixedHeight(3)
        bar.setStyleSheet(f"background: {ACCENT}; border-radius: 2px;")
        layout = QVBoxLayout(self.progress)
        layout.setContentsMargins(0, 12, 0, 0)
        layout.addWidget(bar)
        self.progress.setVisible(False)
        root.addWidget(self.progress)

        root.addStretch()

        hint = QLabel(
            "密码仅用于在本机完成 eLearning 登录，不会发送到任何第三方服务器。\n"
            "登录成功后软件会自动保存会话，之后打开即同步。"
        )
        hint.setObjectName("hint")
        hint.setAlignment(Qt.AlignCenter)
        hint.setWordWrap(True)
        root.addWidget(hint)

        self._toggle_busy(False)

        # 默认聚焦到密码框（账号已记忆时）或账号框
        if self.cfg.uis_username:
            self.password_edit.setFocus()
        else:
            self.username_edit.setFocus()

    # ------------------------------------------------------------------
    def _toggle_busy(self, busy: bool) -> None:
        self.login_button.setEnabled(not busy)
        self.login_button.setText("正在登录…" if busy else "登 录 并 同 步")
        self.username_edit.setEnabled(not busy)
        self.password_edit.setEnabled(not busy)
        self.remember_check.setEnabled(not busy)
        self.progress.setVisible(busy)

    def _on_login(self) -> None:
        username = self.username_edit.text().strip()
        password = self.password_edit.text()
        if not username:
            self.error_label.setText("请输入账号")
            self.username_edit.setFocus()
            return
        if not password:
            self.error_label.setText("请输入密码")
            self.password_edit.setFocus()
            return
        self.error_label.setText("")
        self._toggle_busy(True)

        self.worker = LoginWorker(
            self.cfg, username, password, remember=self.remember_check.isChecked()
        )
        self.worker.succeeded.connect(self._on_success)
        self.worker.failed.connect(self._on_failure)
        self.worker.start()

    def _on_success(self, user: dict) -> None:
        self._toggle_busy(False)
        self.logged_in.emit(user)
        self.accept()

    def _on_failure(self, message: str, need_captcha: bool) -> None:
        self._toggle_busy(False)
        self.error_label.setText(message)
        self.password_edit.setFocus()
        self.password_edit.selectAll()

    def keyPressEvent(self, event) -> None:
        if event.key() == Qt.Key_Escape and self.worker is not None and self.worker.isRunning():
            return  # 登录中不允许 ESC 关闭
        super().keyPressEvent(event)
