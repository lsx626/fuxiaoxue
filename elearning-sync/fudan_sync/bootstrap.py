"""Runtime checks for the optional Qt multimedia modules.

Qt extension modules must come from the same PySide6 distribution. Loading a
standalone binding or Qt DLL beside a different Essentials build can crash the
process before Python can report an import error. The application therefore
uses only a complete, matching PySide6-Addons installation and lets the
previewer show a graceful in-app fallback when it is absent.
"""

from __future__ import annotations

import importlib.util


def ensure_qtmultimedia() -> bool:
    """Return whether matching Qt multimedia bindings are installed.

    The name is retained as the GUI startup contract. Older development builds
    used it to mutate DLL search paths; it is intentionally side-effect free
    now so an incomplete local vendor directory cannot contaminate Qt loading.
    """
    try:
        return (
            importlib.util.find_spec("PySide6.QtMultimedia") is not None
            and importlib.util.find_spec("PySide6.QtMultimediaWidgets") is not None
        )
    except (ImportError, ModuleNotFoundError, AttributeError, ValueError):
        return False
