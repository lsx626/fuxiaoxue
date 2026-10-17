"""Desktop file sharing regression tests."""

from __future__ import annotations

import os

from fudan_sync.gui import sharing


class _Clipboard:
    def __init__(self):
        self.mime = None

    def setMimeData(self, mime):  # noqa: N802 - Qt API shape
        self.mime = mime


def test_copy_file_places_file_url_and_path_on_clipboard(tmp_path, monkeypatch):
    source = tmp_path / "课程资料.pdf"
    source.write_bytes(b"%PDF-test")
    clipboard = _Clipboard()
    monkeypatch.setattr(sharing.QGuiApplication, "clipboard", lambda: clipboard)

    sharing._copy_file(str(source))

    assert clipboard.mime is not None
    assert clipboard.mime.text() == str(source)
    assert os.path.normcase(os.path.normpath(clipboard.mime.urls()[0].toLocalFile())) == (
        os.path.normcase(os.path.normpath(str(source)))
    )


def test_save_copy_preserves_file_contents(tmp_path, monkeypatch):
    source = tmp_path / "source.docx"
    target = tmp_path / "shared" / "copy.docx"
    source.write_bytes(b"course material")
    messages = []
    monkeypatch.setattr(
        sharing.QFileDialog,
        "getSaveFileName",
        lambda *args, **kwargs: (str(target), ""),
    )
    monkeypatch.setattr(
        sharing.QMessageBox,
        "information",
        lambda *args, **kwargs: messages.append(args[2]),
    )

    sharing._save_copy(None, str(source))

    assert target.read_bytes() == b"course material"
    assert os.path.basename(str(target)) in messages[0]
