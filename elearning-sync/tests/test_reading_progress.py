"""阅读进度（v1.1.2）的桌面测试：状态库读写与「未读优先」排序。

预览器把页码/媒体秒写入 reading_progress；文件列表渲染进度列并按
「未读过 → 最近读过」排序。进度只与界面有关，不影响增量/删除语义。
"""
import os
import sqlite3

from fudan_sync.state import StateStore


def _store(tmp_path):
    return StateStore(str(tmp_path / "state.db"))


def _add_file(store, file_id, course_id, filename, status="downloaded"):
    store.upsert_file({
        "file_id": file_id,
        "course_id": course_id,
        "filename": filename,
        "display_name": filename,
        "folder_path": "",
        "local_path": f"/tmp/{filename}",
        "size": 100,
        "status": status,
        "updated_at": "",
        "modified_at": "",
        "url": "",
        "downloaded_at": "",
    })


def test_progress_roundtrip_and_overwrite(tmp_path):
    store = _store(tmp_path)
    try:
        _add_file(store, 10, 1, "a.pdf")
        assert store.get_reading_progress(10) is None
        store.set_reading_progress(10, 1, 4, 20)
        prog = store.get_reading_progress(10)
        assert prog["position"] == 4 and prog["total"] == 20
        assert prog["is_media"] == 0
        # 重复调用是覆盖而非累积
        store.set_reading_progress(10, 1, 8, 20)
        prog = store.get_reading_progress(10)
        assert prog["position"] == 8
        assert "10" not in str(store.get_reading_progress(11))
        store.clear_reading_progress(10)
        assert store.get_reading_progress(10) is None
    finally:
        store.close()


def test_progress_by_course_isolated(tmp_path):
    store = _store(tmp_path)
    try:
        _add_file(store, 11, 1, "a.pdf")
        _add_file(store, 12, 1, "b.pdf")
        _add_file(store, 13, 2, "c.pdf")
        store.set_reading_progress(11, 1, 3, 10)
        store.set_reading_progress(12, 1, 5, 10)
        store.set_reading_progress(13, 2, 7, 10)
        course1 = store.progress_by_course(1)
        assert set(course1.keys()) == {11, 12}
        assert course1[12]["position"] == 5
        course2 = store.progress_by_course(2)
        assert list(course2.keys()) == [13]
    finally:
        store.close()


def test_file_id_by_path(tmp_path):
    store = _store(tmp_path)
    try:
        _add_file(store, 21, 3, "x.pdf")
        row = store.file_id_by_path("/tmp/x.pdf")
        assert row is not None and row["file_id"] == 21
        assert store.file_id_by_path("/tmp/missing.pdf") is None
    finally:
        store.close()


def test_clear_course_progress(tmp_path):
    store = _store(tmp_path)
    try:
        _add_file(store, 31, 5, "y.mp4")
        store.set_reading_progress(31, 5, 90, 180, is_media=True)
        assert store.get_reading_progress(31)["position"] == 90
        store.clear_course_reading_progress(5)
        assert store.get_reading_progress(31) is None
    finally:
        store.close()


def test_progress_survives_reopen(tmp_path):
    """关库再开（预览对话框每次开关库）进度必须持久。"""
    db = str(tmp_path / "state.db")
    store = StateStore(db)
    try:
        _add_file(store, 41, 7, "z.pdf")
        store.set_reading_progress(41, 7, 12, 30)
    finally:
        store.close()
    store2 = StateStore(db)
    try:
        assert store2.get_reading_progress(41)["position"] == 12
    finally:
        store2.close()


def test_unread_first_sorting(tmp_path):
    """文件列表排序：未读在前，已读按最近阅读时间降序。"""
    from fudan_sync.gui.main_window import MainWindow

    files = [
        {"file_id": 1, "filename": "01-intro.pdf"},
        {"file_id": 2, "filename": "02-history.pdf"},
        {"file_id": 3, "filename": "03-methods.pdf"},
        {"file_id": 4, "filename": "04-results.pdf"},
    ]
    progress = {
        2: {"position": 5, "total": 10, "updated_at": "2026-09-01 10:00:00"},
        3: {"position": 2, "total": 10, "updated_at": "2026-09-30 18:00:00"},
        4: {"position": 0, "total": 10, "updated_at": ""},  # position=0 视为未读
    }
    ordered = MainWindow._sort_files_unread_first(files, progress)
    ids = [f["file_id"] for f in ordered]
    # 未读组（1、4）在前，稳定保序；已读组 3 比 2 新，排前
    assert ids == [1, 4, 3, 2]


def test_pdf_fallback_renders_all_pages(tmp_path):
    """PyMuPDF 降级为所有页建立占位（v1.2.3 起按需渲染，位图只在可见时装载）。"""
    import fitz
    from PySide6.QtCore import QTimer
    from PySide6.QtWidgets import QApplication, QScrollArea
    from fudan_sync.gui.previewer import DocumentPreviewDialog, _detect_type
    from types import SimpleNamespace

    app = QApplication.instance() or QApplication([])

    pdf_path = str(tmp_path / "doc.pdf")
    doc = fitz.open()
    for _ in range(3):
        doc.new_page()
    doc.save(pdf_path)
    doc.close()

    assert _detect_type(pdf_path) == "pdf"
    timer = QTimer()
    timer.setSingleShot(True)
    timer.setInterval(120)
    holder = SimpleNamespace(
        _show_unsupported_card=lambda *args: None,
        _show_image_preview=lambda *args: None,
        preview_area=QScrollArea(),
        _pdf_fallback_scroll=None,
        _pdf_fallback_pages=[],
        _pdf_fallback_doc=None,
        _pdf_fallback_rendered=set(),
        _pdf_fallback_render_width=900,
        _pdf_fallback_page_count=0,
        _pdf_fallback_timer=timer,
        # SimpleNamespace 不能自动绑定类方法，手动注入
        _pdf_fallback_target_width=lambda: 900,
    )
    # 未 show 的控件上 mapTo 几何无效，渲染函数应安全返回而非崩溃
    holder._schedule_fallback_render = lambda: timer.start()
    holder._set_preview_widget = lambda widget: collected.append(widget)
    collected = []

    DocumentPreviewDialog._load_pdf_fallback(holder, pdf_path)
    # 每页都有一条占位记录（版面高度已保留）；三页 PDF 应得三条
    assert len(holder._pdf_fallback_pages) == 3
    assert [index for index, _label in holder._pdf_fallback_pages] == [0, 1, 2]
    assert collected  # 已挂到预览区
    # v1.2.3：文档句柄保持打开（供按需渲染读取页面），总页数记录准确
    assert holder._pdf_fallback_doc is not None
    assert holder._pdf_fallback_page_count == 3
    # 初始时尚无任何位图（定时器延迟渲染）
    assert holder._pdf_fallback_rendered == set()
    holder._pdf_fallback_doc.close()

    # 按需渲染一次：未 show 时几何全零，应安全地一页都不渲染
    DocumentPreviewDialog._render_visible_fallback_pages(holder)
    assert holder._pdf_fallback_rendered == set()
