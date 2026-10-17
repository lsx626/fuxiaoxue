# -*- coding: utf-8 -*-
"""预览功能回归测试。

主要防止 previewer 里 PDF 预览因枚举误用（QPdfDocument.MultiPage）
而整体崩溃：该写法会抛 AttributeError，且不会被 except ImportError 捕获，
导致所有 PDF / Office 预览都只显示"预览加载失败"。
"""

import os
import subprocess
import sys
import textwrap

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


def _run(script: str) -> subprocess.CompletedProcess:
    env = dict(os.environ)
    env["QT_QPA_PLATFORM"] = "offscreen"
    env["PYTHONIOENCODING"] = "utf-8"
    return subprocess.run(
        [sys.executable, "-c", script],
        cwd=ROOT,
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=120,
    )


def test_pdf_preview_renders_without_attribute_error():
    """QPdfView 可用时，加载 PDF 应得到 QPdfView 而不是错误卡片。"""
    script = (
        "import os, tempfile\n"
        "from PySide6.QtWidgets import QApplication\n"
        "from PySide6.QtCore import QTimer\n"
        "from PySide6.QtGui import QPainter, QPdfWriter\n"
        "from fudan_sync.gui.previewer import DocumentPreviewDialog, _detect_type\n"
        "app = QApplication([])\n"
        "pdf = os.path.join(tempfile.gettempdir(), f'preview-test-{os.getpid()}.pdf')\n"
        "writer = QPdfWriter(pdf)\n"
        "painter = QPainter(writer)\n"
        "painter.drawText(100, 100, 'preview test')\n"
        "painter.end()\n"
        "del painter, writer\n"
        "assert _detect_type(pdf) == 'pdf'\n"
        "dlg = DocumentPreviewDialog(pdf)\n"
        "dlg.show()\n"
        "def inspect_and_close():\n"
        "    widget = dlg.preview_area.widget()\n"
        "    print('WIDGET=' + type(widget).__name__)\n"
        "    dlg.close()\n"
        "    os.remove(pdf)\n"
        "    app.quit()\n"
        "QTimer.singleShot(800, inspect_and_close)\n"
        "app.exec()\n"
    )
    result = _run(script)
    assert result.returncode == 0, result.stderr
    assert "WIDGET=QPdfView" in result.stdout, result.stdout


def test_pdf_preview_degrades_gracefully_without_qtqpdf():
    """即使 QtPdf 不可用，_load_preview 也不应抛出未捕获异常。"""
    script = (
        "import builtins, os, tempfile\n"
        "from PySide6.QtWidgets import QApplication\n"
        "from PySide6.QtCore import QTimer\n"
        "from PySide6.QtGui import QPainter, QPdfWriter\n"
        "from fudan_sync.gui import previewer\n"
        "app = QApplication([])\n"
        "pdf = os.path.join(tempfile.gettempdir(), f'preview-fallback-{os.getpid()}.pdf')\n"
        "writer = QPdfWriter(pdf)\n"
        "painter = QPainter(writer)\n"
        "painter.drawText(100, 100, 'fallback test')\n"
        "painter.end()\n"
        "del painter, writer\n"
        "real_import = builtins.__import__\n"
        "def fake_import(name, *a, **k):\n"
        "    if name.startswith('PySide6.QtPdf'):\n"
        "        raise ImportError('simulated: QtPdf unavailable')\n"
        "    return real_import(name, *a, **k)\n"
        "builtins.__import__ = fake_import\n"
        "dlg = previewer.DocumentPreviewDialog(pdf)\n"
        "dlg.show()\n"
        "def inspect_and_close():\n"
        "    print('OK_NO_CRASH')\n"
        "    builtins.__import__ = real_import\n"
        "    dlg.close()\n"
        "    os.remove(pdf)\n"
        "    app.quit()\n"
        "QTimer.singleShot(400, inspect_and_close)\n"
        "app.exec()\n"
    )
    result = _run(script)
    assert result.returncode == 0, result.stderr
    assert "OK_NO_CRASH" in result.stdout


def test_generated_pdf_is_released_before_temp_directory_cleanup():
    """Closing an Office preview must release QPdfDocument's Windows handle."""
    script = textwrap.dedent(
        """
        import os, tempfile
        from PySide6.QtWidgets import QApplication
        from PySide6.QtGui import QPainter, QPdfWriter
        from fudan_sync.gui.previewer import DocumentPreviewDialog

        temp_dir = tempfile.mkdtemp(prefix='preview_cleanup_test_')
        generated = os.path.join(temp_dir, 'office-preview.pdf')
        app = QApplication([])
        writer = QPdfWriter(generated)
        painter = QPainter(writer)
        painter.drawText(100, 100, 'cleanup test')
        painter.end()
        del painter, writer
        dialog = DocumentPreviewDialog(generated)
        dialog._temp_pdf_dir = temp_dir
        dialog._temp_pdf_path = generated
        dialog.file_path = generated
        dialog._load_pdf_preview(generated)
        dialog.accept()
        print('REMOVED=' + str(not os.path.exists(temp_dir)))
        """
    )
    result = _run(script)
    assert result.returncode == 0, result.stderr
    assert "REMOVED=True" in result.stdout, result.stdout
