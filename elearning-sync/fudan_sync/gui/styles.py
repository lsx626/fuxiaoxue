"""复小学“书页蓝”界面系统：深色品牌栏、轻量工作区与清晰的状态色。"""

from __future__ import annotations

FONT_FAMILY = (
    '"Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", '
    '"Noto Sans CJK SC", "WenQuanYi Micro Hei", "Segoe UI", sans-serif'
)

ACCENT = "#2457D6"
ACCENT_HOVER = "#1948BD"
ACCENT_PRESSED = "#123A9C"
BG = "#F5F7FB"
CARD = "#FFFFFF"
BORDER = "#DCE2EC"
TEXT = "#171B24"
TEXT_SECONDARY = "#5E6878"
TEXT_DISABLED = "#9AA3B2"
SUCCESS = "#0A7C79"
WARNING = "#B85C00"
DANGER = "#C43D4D"

QSS = f"""
* {{
    font-family: {FONT_FAMILY};
    font-size: 13px;
    color: {TEXT};
}}

QWidget#root {{
    background: {BG};
}}

QFrame#appHeader {{
    background: #102A56;
    border: 1px solid #17386D;
    border-radius: 18px;
}}
QLabel#brandTitle {{
    color: #FFFFFF;
    font-size: 20px;
    font-weight: 700;
}}
QLabel#brandSubtitle {{
    color: #B9C8E6;
    font-size: 12px;
}}
QLabel#userChip {{
    color: #EAF1FF;
    background: #193968;
    border: 1px solid #315184;
    border-radius: 11px;
    padding: 6px 12px;
}}
QPushButton#headerAction {{
    color: #EEF4FF;
    background: #193968;
    border: 1px solid #315184;
    padding: 7px 12px;
}}
QPushButton#headerAction:hover {{
    color: #FFFFFF;
    background: #245083;
    border-color: #4E72A6;
}}

QFrame#card {{
    background: {CARD};
    border: 1px solid {BORDER};
    border-radius: 16px;
}}
QFrame#metricCard {{
    background: {CARD};
    border: 1px solid {BORDER};
    border-left: 4px solid {ACCENT};
    border-radius: 14px;
}}

QLabel#cardTitle {{
    font-size: 15px;
    font-weight: 600;
}}
QLabel#statValue {{
    font-size: 22px;
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
    border-radius: 8px;
    padding: 7px 14px;
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
    border-radius: 8px;
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
    background: {CARD};
    border: none;
    gridline-color: transparent;
    outline: none;
}}
QTableWidget::item {{
    padding: 9px 11px;
    border-bottom: 1px solid #EDF0F5;
}}
QTableWidget::item:selected {{
    background: #E4ECFF;
    color: {TEXT};
}}
QHeaderView::section {{
    background: #F8FAFD;
    color: {TEXT_SECONDARY};
    font-size: 12px;
    font-weight: 600;
    padding: 8px 10px;
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
    background: #F0F3F8;
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
    from PySide6.QtGui import QColor, QPalette

    palette = app.palette()
    palette.setColor(QPalette.Window, QColor(BG))
    palette.setColor(QPalette.WindowText, QColor(TEXT))
    palette.setColor(QPalette.Base, QColor(CARD))
    palette.setColor(QPalette.Text, QColor(TEXT))
    palette.setColor(QPalette.Button, QColor(CARD))
    palette.setColor(QPalette.ButtonText, QColor(TEXT))
    app.setPalette(palette)
