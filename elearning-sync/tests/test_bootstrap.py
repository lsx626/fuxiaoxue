"""Qt multimedia runtime detection regression tests."""

from __future__ import annotations

import importlib.util
import os

import PySide6
from fudan_sync.bootstrap import ensure_qtmultimedia


def test_qtmultimedia_detection_is_idempotent_and_side_effect_free():
    before_path = os.environ.get("PATH")
    before_plugins = os.environ.get("QT_PLUGIN_PATH")
    before_package_path = tuple(PySide6.__path__)
    expected = (
        importlib.util.find_spec("PySide6.QtMultimedia") is not None
        and importlib.util.find_spec("PySide6.QtMultimediaWidgets") is not None
    )
    assert ensure_qtmultimedia() is expected
    assert ensure_qtmultimedia() is expected
    assert os.environ.get("PATH") == before_path
    assert os.environ.get("QT_PLUGIN_PATH") == before_plugins
    assert tuple(PySide6.__path__) == before_package_path


def test_detected_qtmultimedia_is_really_importable():
    if not ensure_qtmultimedia():
        return
    from PySide6.QtMultimedia import QAudioOutput, QMediaPlayer
    from PySide6.QtMultimediaWidgets import QVideoWidget

    assert QAudioOutput is not None
    assert QMediaPlayer is not None
    assert QVideoWidget is not None
