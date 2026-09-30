# -*- coding: utf-8 -*-
"""Small, dependency-light checks for the extended in-app previewer."""
from __future__ import annotations

import os
import subprocess
import sys
import threading
import zipfile
from datetime import datetime
from types import MethodType, SimpleNamespace

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

from fudan_sync.bootstrap import ensure_qtmultimedia
from fudan_sync.gui.previewer import (
    DocumentPreviewDialog,
    _decode_rtf_text,
    _detect_type,
    _extract_docx_blocks,
    _extract_pptx_slides,
    _extract_xlsx_rows,
)


def _zip_file(path, files):
    with zipfile.ZipFile(path, "w") as archive:
        for name, content in files.items():
            archive.writestr(name, content)


def _office_result_holder():
    holder = SimpleNamespace(
        _cleaned_up=False,
        _resource_lock=threading.Lock(),
        _pending_office_dirs=set(),
        _original_file_path="lesson.docx",
        _temp_pdf_path=None,
        _temp_pdf_dir=None,
        file_path="lesson.docx",
    )
    holder._claim_office_result = MethodType(
        DocumentPreviewDialog._claim_office_result, holder
    )
    # v1.1.2：真实对话框在转换后会恢复阅读进度；测试桩只需无操作占位
    holder._restore_progress = lambda: None
    return holder


def test_detects_common_media_and_office_extensions(tmp_path):
    assert _detect_type("lecture.MP3") == "audio"
    assert _detect_type("lecture.webm") == "video"
    assert _detect_type("slides.PPTX") == "office"
    assert _detect_type("notes.PDF") == "pdf"
    assert _detect_type("table.csv") == "text"

    nameless_pdf = tmp_path / "download"
    nameless_pdf.write_bytes(b"%PDF-1.7\n")
    assert _detect_type(str(nameless_pdf)) == "pdf"

    typescript = tmp_path / "lesson.ts"
    typescript.write_text("export const answer = 42;", encoding="utf-8")
    assert _detect_type(str(typescript)) == "text"
    transport_stream = tmp_path / "lecture.ts"
    transport_stream.write_bytes(bytes([0x47]) + bytes(187) + bytes([0x47]))
    assert _detect_type(str(transport_stream)) == "video"


def test_rtf_decodes_cp936_hex_bytes():
    source = r"{\rtf1\ansi\ansicpg936 \'b2\'e2\'ca\'d4}"
    assert _decode_rtf_text(source) == "测试"


def test_rtf_decodes_unicode_and_skips_fallback_characters():
    source = r"{\rtf1\ansi\uc1 \u27979?\u35797?}"
    assert _decode_rtf_text(source) == "测试"


def test_rtf_preserves_common_text_controls_and_escaped_symbols():
    source = r"{\rtf1 A\par B\tab C\~D \\ \{x\}}"
    assert _decode_rtf_text(source) == "A\nB\tC\u00a0D \\ {x}"


def test_extracts_docx_blocks(tmp_path):
    path = tmp_path / "notes.docx"
    _zip_file(
        path,
        {
            "word/document.xml": (
                '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
                '<w:body><w:p><w:r><w:t>Hello</w:t></w:r></w:p>'
                '<w:p><w:r><w:t>World</w:t></w:r></w:p></w:body></w:document>'
            )
        },
    )
    assert _extract_docx_blocks(str(path)) == ["Hello", "World"]


def test_extracts_xlsx_and_pptx_text(tmp_path):
    xlsx = tmp_path / "table.xlsx"
    _zip_file(
        xlsx,
        {
            "xl/workbook.xml": (
                '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
                'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
                '<sheets><sheet name="Sheet1" r:id="rId1"/></sheets></workbook>'
            ),
            "xl/_rels/workbook.xml.rels": (
                '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                '<Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>'
            ),
            "xl/worksheets/sheet1.xml": (
                '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
                '<sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>value</t></is></c>'
                '</row></sheetData></worksheet>'
            ),
        },
    )
    assert _extract_xlsx_rows(str(xlsx)) == [("Sheet1", ["value"])]

    pptx = tmp_path / "slides.pptx"
    _zip_file(
        pptx,
        {
            "ppt/slides/slide1.xml": (
                '<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">'
                '<p:cSld><p:spTree><a:t xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">Title</a:t>'
                '</p:spTree></p:cSld></p:sld>'
            )
        },
    )
    assert _extract_pptx_slides(str(pptx)) == ["Title"]


def test_html_preview_builds_in_app_widget(tmp_path):
    """HTML archives must render inside the dialog instead of erroring."""
    html = tmp_path / "page.html"
    html.write_text("<h1>Course page</h1><p>Offline content</p>", encoding="utf-8")
    script = f"""
from PySide6.QtWidgets import QApplication, QTextBrowser
from fudan_sync.gui.previewer import DocumentPreviewDialog
app = QApplication([])
dialog = DocumentPreviewDialog({str(html)!r})
dialog._load_html_preview()
app.processEvents()
widget = dialog.preview_area.widget()
assert isinstance(widget, QTextBrowser), type(widget).__name__
assert 'Offline content' in widget.toPlainText()
dialog.close()
print('HTML_PREVIEW_OK')
"""
    env = dict(os.environ)
    env["QT_QPA_PLATFORM"] = "offscreen"
    env["PYTHONIOENCODING"] = "utf-8"
    result = subprocess.run(
        [sys.executable, "-c", script],
        cwd=os.path.dirname(HERE),
        env=env,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert result.returncode == 0, result.stderr
    assert "HTML_PREVIEW_OK" in result.stdout


def test_preview_widget_can_be_replaced_repeatedly(tmp_path):
    """QScrollArea owns and destroys each previous preview widget."""
    source = tmp_path / "notes.txt"
    source.write_text("preview", encoding="utf-8")
    script = f"""
from PySide6.QtWidgets import QApplication, QLabel
from fudan_sync.gui.previewer import DocumentPreviewDialog
app = QApplication([])
dialog = DocumentPreviewDialog({str(source)!r})
dialog._set_preview_widget(QLabel('loading'))
dialog._set_preview_widget(QLabel('rendered'))
assert dialog.preview_area.widget().text() == 'rendered'
dialog.close()
print('WIDGET_SWAP_OK')
"""
    env = dict(os.environ)
    env["QT_QPA_PLATFORM"] = "offscreen"
    result = subprocess.run(
        [sys.executable, "-c", script],
        cwd=os.path.dirname(HERE),
        env=env,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert result.returncode == 0, result.stderr
    assert "WIDGET_SWAP_OK" in result.stdout


def test_openpyxl_reader_preserves_empty_cells_formulas_and_dates(tmp_path):
    openpyxl = __import__("openpyxl")
    path = tmp_path / "rich-table.xlsx"
    workbook = openpyxl.Workbook()
    sheet = workbook.active
    sheet.title = "Data"
    sheet["A1"] = "name"
    sheet["C1"] = "=1+2"
    sheet["D1"] = datetime(2026, 9, 18, 10, 30)
    workbook.save(path)
    workbook.close()

    rows = _extract_xlsx_rows(str(path))
    assert rows[0][0] == "Data"
    assert rows[0][1][0:3] == ["name", "", "=1+2"]
    assert rows[0][1][3].startswith("2026-09-18 10:30")


def test_media_loop_uses_qt6_player_enum():
    """QMediaPlayer.loops() returns int; its enum belongs to the class."""
    class FakePlayer:
        class Loops:
            Infinite = -1
            Once = 1

        def __init__(self):
            self.value = None

        def setLoops(self, value):
            self.value = value

    player = FakePlayer()
    holder = SimpleNamespace(media_player=player)
    DocumentPreviewDialog._set_media_loop(holder, True)
    assert player.value == -1
    DocumentPreviewDialog._set_media_loop(holder, False)
    assert player.value == 1


def test_media_seekability_updates_slider():
    class FakeSlider:
        def __init__(self):
            self.enabled = None

        def setEnabled(self, enabled):
            self.enabled = enabled

    slider = FakeSlider()
    holder = SimpleNamespace(position_slider=slider)
    DocumentPreviewDialog._on_media_seekable(holder, True)
    assert slider.enabled is True
    DocumentPreviewDialog._on_media_seekable(holder, False)
    assert slider.enabled is False


def test_media_error_is_reported_inside_player():
    class FakeLabel:
        def __init__(self):
            self.text = ""
            self.visible = False

        def setText(self, text):
            self.text = text

        def show(self):
            self.visible = True

    label = FakeLabel()
    holder = SimpleNamespace(media_message_label=label, media_player=None)
    DocumentPreviewDialog._on_media_error(holder, None, "unsupported codec")
    assert "unsupported codec" in label.text
    assert label.visible is True


def test_accept_cleans_generated_preview_files(tmp_path):
    """The Close button uses accept(), which must still release temp files."""
    source = tmp_path / "source.txt"
    source.write_text("preview", encoding="utf-8")
    generated_dir = tmp_path / "generated"
    generated_dir.mkdir()
    generated = generated_dir / "source.pdf"
    generated.write_bytes(b"preview")
    script = f"""
from PySide6.QtWidgets import QApplication
from fudan_sync.gui.previewer import DocumentPreviewDialog
app = QApplication([])
dialog = DocumentPreviewDialog({str(source)!r})
dialog._temp_pdf_path = {str(generated)!r}
dialog._temp_pdf_dir = {str(generated_dir)!r}
dialog.accept()
print('CLEANED=' + str(not __import__('os').path.exists({str(generated_dir)!r})))
"""
    env = dict(os.environ)
    env["QT_QPA_PLATFORM"] = "offscreen"
    result = subprocess.run(
        [sys.executable, "-c", script],
        cwd=os.path.dirname(HERE),
        env=env,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert result.returncode == 0, result.stderr
    assert "CLEANED=True" in result.stdout


def test_closed_preview_dialog_is_destroyed(tmp_path):
    """Repeated previews must not accumulate hidden child widget trees."""
    source = tmp_path / "source.txt"
    source.write_text("preview", encoding="utf-8")
    script = f"""
from PySide6.QtCore import QCoreApplication, QEvent
from PySide6.QtWidgets import QApplication
import shiboken6
from fudan_sync.gui.previewer import DocumentPreviewDialog
app = QApplication([])
dialog = DocumentPreviewDialog({str(source)!r})
dialog.show()
dialog.accept()
QCoreApplication.sendPostedEvents(None, QEvent.DeferredDelete)
app.processEvents()
print('DESTROYED=' + str(not shiboken6.isValid(dialog)))
"""
    env = dict(os.environ)
    env["QT_QPA_PLATFORM"] = "offscreen"
    result = subprocess.run(
        [sys.executable, "-c", script],
        cwd=os.path.dirname(HERE),
        env=env,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert result.returncode == 0, result.stderr
    assert "DESTROYED=True" in result.stdout


def test_closed_media_preview_releases_player_and_process_exits(tmp_path):
    """A hidden QMediaPlayer used to keep short-lived GUI processes alive."""
    if not ensure_qtmultimedia():
        pytest.skip("QtMultimedia is not installed in this test environment")

    source = tmp_path / "sample.mp3"
    source.write_bytes(b"ID3\x04\x00\x00\x00\x00\x00\x00")
    script = f"""
from PySide6.QtCore import QCoreApplication, QEvent
from PySide6.QtWidgets import QApplication
import shiboken6
from fudan_sync.gui.previewer import DocumentPreviewDialog
app = QApplication([])
dialog = DocumentPreviewDialog({str(source)!r})
dialog._load_media_preview(False)
assert dialog.media_player is not None
dialog.accept()
QCoreApplication.sendPostedEvents(None, QEvent.DeferredDelete)
app.processEvents()
print('MEDIA_DESTROYED=' + str(not shiboken6.isValid(dialog)))
app.quit()
"""
    env = dict(os.environ)
    env["QT_QPA_PLATFORM"] = "offscreen"
    result = subprocess.run(
        [sys.executable, "-c", script],
        cwd=os.path.dirname(HERE),
        env=env,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert result.returncode == 0, result.stderr
    assert "MEDIA_DESTROYED=True" in result.stdout


def test_finished_old_preview_does_not_clear_new_preview_reference():
    old_preview = object()
    new_preview = object()
    holder = SimpleNamespace(preview_dialog=new_preview)

    from fudan_sync.gui.main_window import MainWindow

    MainWindow._release_preview_dialog(holder, old_preview)
    assert holder.preview_dialog is new_preview
    MainWindow._release_preview_dialog(holder, new_preview)
    assert holder.preview_dialog is None


def test_delayed_preview_load_is_ignored_after_cleanup():
    """The constructor's single-shot timer may fire after an immediate close."""
    holder = SimpleNamespace(_cleaned_up=True)
    DocumentPreviewDialog._load_preview(holder)


def test_successful_office_result_hands_pdf_to_gui(tmp_path):
    output_dir = tmp_path / "rendered"
    output_dir.mkdir()
    output_path = output_dir / "lesson.pdf"
    output_path.write_bytes(b"%PDF-1.7\n")
    holder = _office_result_holder()
    holder._pending_office_dirs.add(str(output_dir))
    loaded = []
    holder._load_pdf_preview = loaded.append
    holder._load_structured_office_preview = lambda _ext: None

    DocumentPreviewDialog._on_office_rendered(
        holder, str(output_path), str(output_dir)
    )

    assert holder.file_path == str(output_path)
    assert holder._temp_pdf_path == str(output_path)
    assert holder._temp_pdf_dir == str(output_dir)
    assert loaded == [str(output_path)]
    assert not holder._pending_office_dirs


def test_failed_office_result_cleans_directory_and_falls_back_once(tmp_path):
    output_dir = tmp_path / "failed-render"
    output_dir.mkdir()
    holder = _office_result_holder()
    holder._pending_office_dirs.add(str(output_dir))
    fallbacks = []
    holder._load_pdf_preview = lambda _path: None
    holder._load_structured_office_preview = fallbacks.append

    DocumentPreviewDialog._on_office_rendered(
        holder, str(output_dir / "missing.pdf"), str(output_dir)
    )

    assert fallbacks == [".docx"]
    assert not output_dir.exists()
    assert not holder._pending_office_dirs


def test_office_result_arriving_after_close_is_removed(tmp_path):
    output_dir = tmp_path / "late-render"
    output_dir.mkdir()
    output_path = output_dir / "lesson.pdf"
    output_path.write_bytes(b"%PDF-1.7\n")
    holder = _office_result_holder()
    holder._cleaned_up = True
    loaded = []
    fallbacks = []
    holder._load_pdf_preview = loaded.append
    holder._load_structured_office_preview = fallbacks.append

    DocumentPreviewDialog._on_office_rendered(
        holder, str(output_path), str(output_dir)
    )

    assert not output_dir.exists()
    assert loaded == []
    assert fallbacks == []


def test_office_worker_cleans_output_after_dialog_is_destroyed(tmp_path):
    """A converter finishing after WA_DeleteOnClose must not leak its temp dir."""
    source = tmp_path / "lesson.docx"
    source.write_bytes(b"placeholder")
    output_dir = tmp_path / "late-office-render"
    output_dir.mkdir()
    output_path = output_dir / "lesson.pdf"
    output_path.write_bytes(b"%PDF-1.7\n")
    script = f"""
import os, threading
from PySide6.QtCore import QCoreApplication, QEvent
from PySide6.QtWidgets import QApplication
import shiboken6
from fudan_sync.gui.previewer import DocumentPreviewDialog
app = QApplication([])
started = threading.Event()
release = threading.Event()
def convert(_source, _cancel_event):
    started.set()
    assert release.wait(timeout=5)
    return {str(output_path)!r}, {str(output_dir)!r}
dialog = DocumentPreviewDialog({str(source)!r})
dialog._convert_office_file = convert
dialog._start_office_render()
assert started.wait(timeout=5)
worker = dialog._office_thread
dialog.accept()
QCoreApplication.sendPostedEvents(None, QEvent.DeferredDelete)
app.processEvents()
print('DESTROYED=' + str(not shiboken6.isValid(dialog)))
release.set()
worker.join(timeout=5)
print('WORKER_STOPPED=' + str(not worker.is_alive()))
print('OUTPUT_CLEANED=' + str(not os.path.exists({str(output_dir)!r})))
"""
    env = dict(os.environ)
    env["QT_QPA_PLATFORM"] = "offscreen"
    result = subprocess.run(
        [sys.executable, "-c", script],
        cwd=os.path.dirname(HERE),
        env=env,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert result.returncode == 0, result.stderr
    assert "DESTROYED=True" in result.stdout
    assert "WORKER_STOPPED=True" in result.stdout
    assert "OUTPUT_CLEANED=True" in result.stdout


def test_worker_completion_after_close_cleans_without_gui_event_loop(tmp_path):
    source = tmp_path / "lesson.docx"
    source.write_bytes(b"placeholder")
    output_dir = tmp_path / "background-render"
    output_dir.mkdir()
    output_path = output_dir / "lesson.pdf"
    output_path.write_bytes(b"%PDF-1.7\n")
    conversion_started = threading.Event()
    release_conversion = threading.Event()
    emissions = []

    def convert(_source, _cancel_event):
        conversion_started.set()
        assert release_conversion.wait(timeout=5)
        return str(output_path), str(output_dir)

    holder = SimpleNamespace(
        _cleaned_up=False,
        _resource_lock=threading.Lock(),
        _pending_office_dirs=set(),
        _office_render_started=False,
        _office_cancel_event=threading.Event(),
        _office_signals=SimpleNamespace(
            finished=SimpleNamespace(emit=lambda *result: emissions.append(result))
        ),
        _office_thread=None,
        _original_file_path=str(source),
        _convert_office_file=convert,
    )
    holder._register_office_result = MethodType(
        DocumentPreviewDialog._register_office_result, holder
    )
    holder._discard_office_result = MethodType(
        DocumentPreviewDialog._discard_office_result, holder
    )

    DocumentPreviewDialog._start_office_render(holder)
    assert conversion_started.wait(timeout=5)
    with holder._resource_lock:
        holder._cleaned_up = True
        holder._office_cancel_event.set()
    release_conversion.set()
    holder._office_thread.join(timeout=5)

    assert not holder._office_thread.is_alive()
    assert not output_dir.exists()
    assert emissions == []


def test_cancelled_converter_is_never_reported_as_success():
    class FinishedProcess:
        def __init__(self):
            self.terminated = False

        def terminate(self):
            self.terminated = True

        def wait(self, timeout):
            del timeout
            return 0

    process = FinishedProcess()
    cancelled = threading.Event()
    cancelled.set()

    result = DocumentPreviewDialog._wait_for_converter(process, cancelled)

    assert result == -1
    assert process.terminated is True
