"""界面样式表：统一、克制的现代风格。

复小学品牌配色：靛蓝（Indigo）为主色调，浅色背景 + 白色卡片，强调可读性。
本文件与 icon.py、Android 端 ui/Theme.kt 共用同一套色板，保证双端风格一致。
"""
from __future__ import annotations

FONT_FAMILY = ('"Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", '
               '"Noto Sans CJK SC", "WenQuanYi Micro Hei", "Segoe UI", sans-serif')

ACCENT = "#4F46E5"
ACCENT_HOVER = "#4338CA"
ACCENT_PRESSED = "#3730A3"
BG = "#F6F7FC"
CARD = "#FFFFFF"
BORDER = "#E2E7F1"
TEXT = "#1E2433"
TEXT_SECONDARY = "#6B7488"
TEXT_DISABLED = "#A2A9BB"
SUCCESS = "#16A34A"
WARNING = "#D97706"
DANGER = "#D64545"

QSS = f"""
* {{
    font-family: {FONT_FAMILY};
    font-size: 13px;
    color: {TEXT};
}}

QWidget#root {{
    background: {BG};
}}

QFrame#card {{
    background: {CARD};
    border: 1px solid {BORDER};
    border-radius: 14px;
}}

QLabel#cardTitle {{
    font-size: 15px;
    font-weight: 600;
}}
QLabel#statValue {{
    font-size: 24px;
    font-weight: 700;
    color: {TEXT};
}}
QLabel#statCaption {{
    font-size: 12px;
    color: {TEXT_SECONDARY};
}}
QLabel#hint {{
    color: {TEXT_SECONDARY};
    font-size: 12px;
}}
QLabel#titleLabel {{
    font-size: 19px;
    font-weight: 700;
    color: "{TEXT}";
}}
QLabel#subtitleLabel {{
    color: {TEXT_SECONDARY};
    font-size: 12px;
}}

QPushButton {{
    border-radius: 9px;
    padding: 8px 16px;
    border: 1px solid {BORDER};
    background: {CARD};
}}
QPushButton:hover {{
    border-color: #C6CEDF;
    background: #F7F9FD;
}}
QPushButton:pressed {{
    background: #EDF1F8;
}}
QPushButton:disabled {{
    color: {TEXT_DISABLED};
    background: #F1F3F8;
}}

QPushButton#primary {{
    background: {ACCENT};
    border: 1px solid {ACCENT};
    color: #FFFFFF;
    font-weight: 600;
}}
QPushButton#primary:hover {{
    background: {ACCENT_HOVER};
    border-color: {ACCENT_HOVER};
}}
QPushButton#primary:pressed {{
    background: {ACCENT_PRESSED};
    border-color: {ACCENT_PRESSED};
}}
QPushButton#primary:disabled {{
    background: #B9C3DA;
    border-color: #B9C3DA;
    color: #FFFFFF;
}}

QPushButton#danger {{
    color: {DANGER};
    border-color: #EFD4D4;
}}
QPushButton#danger:hover {{
    background: #FDF3F3;
    border-color: #E5BEBE;
}}

QLineEdit, QSpinBox, QComboBox {{
    border: 1px solid {BORDER};
    border-radius: 9px;
    padding: 8px 10px;
    background: {CARD};
    selection-background-color: {ACCENT};
    selection-color: #FFFFFF;
}}
QLineEdit:focus, QSpinBox:focus, QComboBox:focus {{
    border-color: {ACCENT};
}}
QLineEdit:disabled {{
    background: #F5F6FA;
    color: {TEXT_DISABLED};
}}

QCheckBox {{
    spacing: 8px;
}}
QCheckBox::indicator {{
    width: 16px;
    height: 16px;
    border-radius: 5px;
    border: 1.5px solid #C2C9D9;
    background: {CARD};
}}
QCheckBox::indicator:hover {{
    border-color: {ACCENT};
}}
QCheckBox::indicator:checked {{
    background: {ACCENT};
    border-color: {ACCENT};
    image: none;
}}

QProgressBar {{
    border: none;
    border-radius: 7px;
    background: #E9EDF5;
    height: 10px;
    text-align: center;
    font-size: 11px;
    color: {TEXT_SECONDARY};
}}
QProgressBar::chunk {{
    border-radius: 7px;
    background: {ACCENT};
}}

QTableWidget {{
    background: transparent;
    border: none;
    gridline-color: transparent;
    outline: none;
}}
QTableWidget::item {{
    padding: 8px 10px;
    border-bottom: 1px solid #EEF1F7;
}}
QTableWidget::item:selected {{
    background: #EAF0FB;
    color: {TEXT};
}}
QHeaderView::section {{
    background: transparent;
    color: {TEXT_SECONDARY};
    font-size: 12px;
    font-weight: 600;
    padding: 6px 10px;
    border: none;
    border-bottom: 1px solid {BORDER};
}}

QTextEdit#logView {{
    background: #20242E;
    border: 1px solid #20242E;
    border-radius: 12px;
    color: #D7DBE4;
    font-family: "Cascadia Mono", "Consolas", "Microsoft YaHei UI", monospace;
    font-size: 12px;
    padding: 10px;
    selection-background-color: #3A4150;
}}

QScrollBar:vertical {{
    background: transparent;
    width: 10px;
    margin: 4px 2px;
}}
QScrollBar::handle:vertical {{
    background: #CBD2E0;
    border-radius: 5px;
    min-height: 30px;
}}
QScrollBar::handle:vertical:hover {{
    background: #B3BCD1;
}}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {{
    height: 0;
}}
QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical {{
    background: transparent;
}}

QToolTip {{
    background: #2A2F3C;
    color: #FFFFFF;
    border: none;
    border-radius: 8px;
    padding: 6px 10px;
}}

QStatusBar {{
    background: transparent;
    color: {TEXT_SECONDARY};
    border-top: 1px solid {BORDER};
}}

QMenu {{
    background: {CARD};
    border: 1px solid {BORDER};
    border-radius: 10px;
    padding: 6px;
}}
QMenu::item {{
    padding: 7px 14px;
    border-radius: 7px;
}}
QMenu::item:selected {{
    background: #EAF0FB;
    color: {TEXT};
}}
QMenu::separator {{
    height: 1px;
    background: {BORDER};
    margin: 4px 8px;
}}

QDialog {{
    background: {BG};
}}
"""


def apply_palette(app) -> None:
    """调色板层面的兜底，避免某些控件不受 QSS 影响。"""
    from PySide6.QtGui import QPalette, QColor
    palette = app.palette()
    palette.setColor(QPalette.Window, QColor(BG))
    palette.setColor(QPalette.WindowText, QColor(TEXT))
    palette.setColor(QPalette.Base, QColor(CARD))
    palette.setColor(QPalette.Text, QColor(TEXT))
    palette.setColor(QPalette.Button, QColor(CARD))
    palette.setColor(QPalette.ButtonText, QColor(TEXT))
    app.setPalette(palette)
