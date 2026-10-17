"""用 QPainter 绘制统一的品牌图标与线性操作图标。"""

from __future__ import annotations

from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import (
    QBrush,
    QColor,
    QIcon,
    QImage,
    QLinearGradient,
    QPainter,
    QPen,
    QPixmap,
    QPolygonF,
    QRadialGradient,
)
from PySide6.QtWidgets import QApplication

# 与 styles.py / Android Theme.kt 共用的品牌色
ICON_COLOR_TOP = "#2457D6"
ICON_COLOR_MID = "#1948BD"
ICON_COLOR_BOTTOM = "#102A56"


def _paint_cap(painter: QPainter, size: float) -> None:
    """绘制向中央折叠的双页书标，避免常见学士帽模板感。"""
    painter.save()
    painter.setRenderHint(QPainter.Antialiasing, True)
    painter.setPen(Qt.NoPen)
    left = QPolygonF(
        [
            QPointF(size * 0.18, size * 0.30),
            QPointF(size * 0.45, size * 0.38),
            QPointF(size * 0.45, size * 0.75),
            QPointF(size * 0.18, size * 0.66),
        ]
    )
    right = QPolygonF(
        [
            QPointF(size * 0.82, size * 0.30),
            QPointF(size * 0.55, size * 0.38),
            QPointF(size * 0.55, size * 0.75),
            QPointF(size * 0.82, size * 0.66),
        ]
    )
    spine = QPolygonF(
        [
            QPointF(size * 0.45, size * 0.38),
            QPointF(size * 0.50, size * 0.43),
            QPointF(size * 0.55, size * 0.38),
            QPointF(size * 0.55, size * 0.75),
            QPointF(size * 0.50, size * 0.80),
            QPointF(size * 0.45, size * 0.75),
        ]
    )
    painter.setBrush(QBrush(QColor("#FFFFFF")))
    painter.drawPolygon(left)
    painter.setBrush(QBrush(QColor("#D8F2EF")))
    painter.drawPolygon(right)
    painter.setBrush(QBrush(QColor("#FFFFFF")))
    painter.drawPolygon(spine)
    painter.setPen(QPen(QColor("#86D9D3"), max(1.2, size * 0.025), Qt.SolidLine, Qt.RoundCap))
    painter.drawLine(QPointF(size * 0.25, size * 0.47), QPointF(size * 0.38, size * 0.51))
    painter.drawLine(QPointF(size * 0.62, size * 0.51), QPointF(size * 0.75, size * 0.47))
    painter.restore()


def render_pixmap(size: int, device_pixel_ratio: float = 1.0) -> QPixmap:
    """绘制指定尺寸的应用图标位图。"""
    image = QImage(
        int(size * device_pixel_ratio),
        int(size * device_pixel_ratio),
        QImage.Format_ARGB32_Premultiplied,
    )
    image.setDevicePixelRatio(device_pixel_ratio)
    image.fill(Qt.transparent)

    painter = QPainter(image)
    painter.setRenderHint(QPainter.Antialiasing, True)

    # 底：圆角方块 + 径向渐变（左上偏亮）
    rect = QRectF(0, 0, size, size)
    radius = size * 0.24
    gradient = QRadialGradient(size * 0.32, size * 0.26, size * 1.15)
    gradient.setColorAt(0.0, QColor(ICON_COLOR_TOP))
    gradient.setColorAt(0.65, QColor(ICON_COLOR_MID))
    gradient.setColorAt(1.0, QColor(ICON_COLOR_BOTTOM))
    painter.setBrush(QBrush(gradient))
    painter.setPen(Qt.NoPen)
    painter.drawRoundedRect(rect, radius, radius)

    _paint_cap(painter, size)

    # 顶部高光，增加质感
    highlight = QLinearGradient(0, 0, 0, size * 0.5)
    highlight.setColorAt(0.0, QColor(255, 255, 255, 34))
    highlight.setColorAt(1.0, QColor(255, 255, 255, 0))
    painter.setBrush(QBrush(highlight))
    painter.drawRoundedRect(rect, radius, radius)

    painter.end()
    return QPixmap.fromImage(image)


def app_icon() -> QIcon:
    """返回应用图标（自适应高分屏）。"""
    app = QApplication.instance()
    dpr = app.devicePixelRatio() if app is not None else 1.0
    icon = QIcon()
    for size in (16, 24, 32, 48, 64, 128, 256):
        icon.addPixmap(render_pixmap(size, dpr))
    return icon


def line_icon(name: str, color: str = "#5E6878", size: int = 20) -> QIcon:
    """绘制桌面端统一的 2px 圆角线性图标。"""
    image = QImage(size, size, QImage.Format_ARGB32_Premultiplied)
    image.fill(Qt.transparent)
    p = QPainter(image)
    p.setRenderHint(QPainter.Antialiasing, True)
    pen = QPen(QColor(color), 1.8, Qt.SolidLine, Qt.RoundCap, Qt.RoundJoin)
    p.setPen(pen)
    p.setBrush(Qt.NoBrush)
    s = float(size)
    if name == "search":
        p.drawEllipse(QRectF(s * 0.18, s * 0.18, s * 0.48, s * 0.48))
        p.drawLine(QPointF(s * 0.61, s * 0.61), QPointF(s * 0.84, s * 0.84))
    elif name == "calendar":
        p.drawRoundedRect(QRectF(s * 0.15, s * 0.22, s * 0.70, s * 0.63), 2, 2)
        p.drawLine(QPointF(s * 0.15, s * 0.38), QPointF(s * 0.85, s * 0.38))
        p.drawLine(QPointF(s * 0.32, s * 0.12), QPointF(s * 0.32, s * 0.29))
        p.drawLine(QPointF(s * 0.68, s * 0.12), QPointF(s * 0.68, s * 0.29))
    elif name == "settings":
        p.drawEllipse(QRectF(s * 0.22, s * 0.22, s * 0.56, s * 0.56))
        p.drawEllipse(QRectF(s * 0.41, s * 0.41, s * 0.18, s * 0.18))
    elif name == "more":
        p.setBrush(QBrush(QColor(color)))
        [p.drawEllipse(QPointF(s * x, s * 0.5), 1.5, 1.5) for x in (0.25, 0.5, 0.75)]
    elif name == "sync":
        p.drawArc(QRectF(s * 0.18, s * 0.18, s * 0.64, s * 0.64), 30 * 16, 250 * 16)
        p.drawLine(QPointF(s * 0.72, s * 0.17), QPointF(s * 0.82, s * 0.35))
        p.drawLine(QPointF(s * 0.72, s * 0.17), QPointF(s * 0.55, s * 0.22))
    elif name == "folder":
        p.drawRoundedRect(QRectF(s * 0.12, s * 0.28, s * 0.76, s * 0.55), 2, 2)
        p.drawLine(QPointF(s * 0.12, s * 0.36), QPointF(s * 0.88, s * 0.36))
        p.drawLine(QPointF(s * 0.18, s * 0.28), QPointF(s * 0.40, s * 0.28))
    elif name == "play":
        p.setBrush(QBrush(QColor(color)))
        p.drawPolygon(
            QPolygonF(
                [
                    QPointF(s * 0.32, s * 0.22),
                    QPointF(s * 0.78, s * 0.5),
                    QPointF(s * 0.32, s * 0.78),
                ]
            )
        )
    p.end()
    return QIcon(QPixmap.fromImage(image))
