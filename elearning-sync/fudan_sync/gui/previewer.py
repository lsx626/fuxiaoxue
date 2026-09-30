# -*- coding: utf-8 -*-
"""In-app preview widgets for downloaded course material.

The previewer keeps heavyweight integrations optional.  QtPdf is used for
PDFs when available, QtMultimedia powers local audio/video playback, and
modern Office files are parsed directly from their ZIP/XML representation
before falling back to native Office/LibreOffice conversion.
"""
from __future__ import annotations

import codecs
import csv
import io
import mimetypes
import os
import re
import shutil
import subprocess
import tempfile
import threading
import zipfile
from datetime import date, datetime, time
from itertools import islice
from pathlib import Path
from time import monotonic
from typing import List, Optional, Sequence
from xml.etree import ElementTree as ET

from PySide6.QtCore import QObject, QUrl, QSize, Qt, QTimer, Signal
from PySide6.QtGui import QGuiApplication, QKeySequence, QMovie, QPixmap, QShortcut
from PySide6.QtWidgets import (
    QCheckBox,
    QDialog,
    QFrame,
    QHBoxLayout,
    QLabel,
    QMessageBox,
    QPlainTextEdit,
    QPushButton,
    QScrollArea,
    QSlider,
    QStyle,
    QTableWidget,
    QTableWidgetItem,
    QTextBrowser,
    QToolButton,
    QVBoxLayout,
    QWidget,
)

from ..utils import format_size
from .icon import app_icon
from .sharing import show_share_menu
from .styles import ACCENT, BORDER, CARD, TEXT, TEXT_SECONDARY

# ---------------------------------------------------------------------------
# File type classification
# ---------------------------------------------------------------------------

IMAGE_EXTS = {
    ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".ico", ".svg",
    ".tif", ".tiff", ".avif", ".heic", ".heif",
}
TEXT_EXTS = {
    ".txt", ".md", ".rst", ".log", ".csv", ".tsv", ".json", ".xml",
    ".html", ".htm", ".css", ".js", ".py", ".java", ".c", ".cpp",
    ".h", ".hpp", ".cs", ".go", ".rs", ".ts", ".tsx", ".jsx",
    ".sh", ".bat", ".cmd", ".ps1", ".yaml", ".yml", ".toml", ".ini",
    ".conf", ".cfg", ".sql", ".r", ".m", ".php", ".rb", ".swift",
    ".kt", ".dart", ".vue", ".scss", ".less", ".tex", ".properties",
}
PDF_EXTS = {".pdf"}
WORD_EXTS = {".doc", ".docx", ".docm", ".rtf", ".odt", ".ott"}
EXCEL_EXTS = {".xls", ".xlsx", ".xlsm", ".xlt", ".xltx", ".ods", ".ots"}
PPT_EXTS = {".ppt", ".pptx", ".pptm", ".pps", ".ppsx", ".odp", ".otp"}
AUDIO_EXTS = {
    ".mp3", ".wav", ".flac", ".m4a", ".aac", ".ogg", ".oga", ".opus",
    ".wma", ".aiff", ".aif", ".mid", ".midi", ".amr", ".ape",
}
VIDEO_EXTS = {
    ".mp4", ".m4v", ".mkv", ".avi", ".mov", ".webm", ".wmv", ".mpeg",
    ".mpg", ".mpe", ".3gp", ".3g2", ".ts", ".mts", ".m2ts", ".flv",
}
OFFICE_EXTS = WORD_EXTS | EXCEL_EXTS | PPT_EXTS


def _detect_type(file_path: str) -> str:
    """Return a stable preview kind for *file_path*.

    ``office`` is retained for every Office variant for backwards
    compatibility. CSV remains ``text`` and receives a table renderer.
    """
    ext = os.path.splitext(str(file_path))[1].lower()
    if ext == ".ts":
        # .ts is shared by TypeScript and MPEG transport streams. MPEG-TS
        # packets start with a 0x47 sync byte every 188 bytes, so content
        # sniffing cleanly distinguishes real course videos from source code.
        try:
            with open(file_path, "rb") as stream:
                header = stream.read(377)
            if len(header) >= 189 and header[0] == 0x47 and header[188] == 0x47:
                return "video"
        except OSError:
            pass
        return "text"
    if ext in PDF_EXTS:
        return "pdf"
    if ext in IMAGE_EXTS:
        return "image"
    if ext in AUDIO_EXTS:
        return "audio"
    if ext in VIDEO_EXTS:
        return "video"
    if ext in TEXT_EXTS:
        return "text"
    if ext in OFFICE_EXTS:
        return "office"
    guessed, _ = mimetypes.guess_type(str(file_path))
    if guessed:
        if guessed == "application/pdf":
            return "pdf"
        if guessed.startswith("image/"):
            return "image"
        if guessed.startswith("audio/"):
            return "audio"
        if guessed.startswith("video/"):
            return "video"
        if guessed.startswith("text/"):
            return "text"
    # Content sniffing is intentionally conservative and only runs for files
    # that did not have a recognized extension.  This covers Canvas downloads
    # whose names occasionally lose their suffix during synchronization.
    try:
        with open(file_path, "rb") as stream:
            header = stream.read(4096)
        if header.startswith(b"%PDF-"):
            return "pdf"
        if header.startswith((b"\x89PNG", b"\xff\xd8\xff", b"GIF8", b"BM")):
            return "image"
        if header.startswith(b"PK\x03\x04"):
            with zipfile.ZipFile(file_path) as archive:
                names = set(archive.namelist())
                if "word/document.xml" in names:
                    return "office"
                if "xl/workbook.xml" in names:
                    return "office"
                if any(name.startswith("ppt/slides/") for name in names):
                    return "office"
        if header.startswith((b"ID3", b"\xff\xfb", b"\xff\xf3", b"\xff\xf2")):
            return "audio"
        if len(header) >= 12 and header[4:8] == b"ftyp":
            brand = header[8:12]
            return "audio" if brand in {b"M4A ", b"M4B ", b"M4P "} else "video"
        if header.startswith(b"RIFF") and header[8:12] == b"WAVE":
            return "audio"
        if header.startswith(b"RIFF") and header[8:12] == b"AVI ":
            return "video"
    except (OSError, zipfile.BadZipFile):
        pass
    return "unknown"


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _xml_text(element: ET.Element) -> str:
    """Join visible text nodes in an OOXML/ODF element."""
    return "".join(
        (node.text or "")
        for node in element.iter()
        if _local_name(node.tag) in {"t", "text", "tab"}
    )


def _zip_xml(path: str, member: str) -> Optional[ET.Element]:
    try:
        with zipfile.ZipFile(path) as archive:
            with archive.open(member) as stream:
                return ET.fromstring(stream.read())
    except (KeyError, OSError, ET.ParseError, zipfile.BadZipFile):
        return None


def _extract_docx_blocks(path: str) -> list[str]:
    """Extract paragraphs/table rows from DOCX or ODT packages."""
    ext = os.path.splitext(path)[1].lower()
    if ext in {".odt", ".ott"}:
        try:
            from odf import teletype  # type: ignore
            from odf.opendocument import load  # type: ignore
            from odf.table import Table, TableCell, TableRow  # type: ignore
            from odf.text import P  # type: ignore

            document = load(path)
            blocks = []
            # ODF paragraphs provide the closest readable representation;
            # append tab-separated table rows so column relationships remain
            # visible even when styles are not rendered.
            for paragraph in document.text.getElementsByType(P):
                value = teletype.extractText(paragraph).strip()
                if value:
                    blocks.append(value)
            for table in document.text.getElementsByType(Table):
                for row in table.getElementsByType(TableRow):
                    values = [
                        teletype.extractText(cell).strip()
                        for cell in row.getElementsByType(TableCell)
                    ]
                    if any(values):
                        blocks.append("\t".join(values))
            if blocks:
                return blocks
        except Exception:  # pylint: disable=broad-except
            # Optional parsers may reject damaged/partially downloaded
            # packages with library-specific exception types. Continue to the
            # lightweight ZIP/XML reader in that case.
            pass
    elif ext in {".docx", ".docm"}:
        try:
            from docx import Document  # type: ignore

            document = Document(path)
            blocks = [paragraph.text.strip() for paragraph in document.paragraphs if paragraph.text.strip()]
            for table in document.tables:
                for row in table.rows:
                    values = [cell.text.strip() for cell in row.cells]
                    if any(values):
                        blocks.append("\t".join(values))
            if blocks:
                return blocks
        except Exception:  # pylint: disable=broad-except
            pass

    root = _zip_xml(path, "word/document.xml")
    if root is None:
        root = _zip_xml(path, "content.xml")
    if root is None:
        return []
    blocks: list[str] = []
    for node in root.iter():
        kind = _local_name(node.tag)
        if kind == "p":
            value = _xml_text(node).strip()
            if value:
                blocks.append(value)
        elif kind == "tr":
            cells = []
            for cell in node.iter():
                if _local_name(cell.tag) in {"tc", "table-cell"}:
                    text = _xml_text(cell).strip()
                    if text:
                        cells.append(text)
            if cells:
                blocks.append("\t".join(cells))
    if not blocks:
        value = " ".join(part.strip() for part in root.itertext() if part.strip())
        if value:
            blocks.append(value)
    return blocks


def _format_office_value(value) -> str:
    """Convert a spreadsheet value without losing dates or formulas."""
    if value is None:
        return ""
    if isinstance(value, datetime):
        return value.isoformat(sep=" ", timespec="seconds")
    if isinstance(value, (date, time)):
        return value.isoformat()
    return str(value)


def _extract_xlsx_rows(
    path: str, max_rows: int = 500, max_cols: int = 40
) -> list[tuple[str, list[str]]]:
    """Extract sheet rows from XLSX/ODS without requiring Excel."""
    rows: list[tuple[str, list[str]]] = []
    ext = os.path.splitext(path)[1].lower()
    if ext in {".ods", ".ots"}:
        try:
            from odf import teletype  # type: ignore
            from odf.opendocument import load  # type: ignore
            from odf.table import Table, TableCell, TableRow  # type: ignore

            document = load(path)
            for table in document.spreadsheet.getElementsByType(Table):
                sheet_name = table.getAttribute("name") or "工作表"
                for row in table.getElementsByType(TableRow)[:max_rows]:
                    values: list[str] = []
                    for cell in row.getElementsByType(TableCell):
                        repeat = int(cell.getAttribute("numbercolumnsrepeated") or 1)
                        value = teletype.extractText(cell).strip()
                        values.extend([value] * min(repeat, max_cols - len(values)))
                        if len(values) >= max_cols:
                            break
                    if values:
                        rows.append((sheet_name, values[:max_cols]))
            if rows:
                return rows
        except Exception:  # pylint: disable=broad-except
            pass
        root = _zip_xml(path, "content.xml")
        if root is None:
            return rows
        for table in (node for node in root.iter() if _local_name(node.tag) == "table"):
            sheet_name = str(next((v for k, v in table.attrib.items() if _local_name(k) == "name"), "工作表"))
            count = 0
            for row in (node for node in table if _local_name(node.tag) == "table-row"):
                values = []
                for cell in (node for node in row if _local_name(node.tag) == "table-cell"):
                    text = " ".join(part.strip() for part in cell.itertext() if part.strip())
                    values.append(text)
                if values:
                    rows.append((sheet_name, values[:max_cols]))
                    count += 1
                if count >= max_rows:
                    break
        return rows

    try:
        from openpyxl import load_workbook  # type: ignore

        # v1.2.1：公式单元格优先显示缓存计算值（data_only=True，与 Android 端
        # DataFormatter 同口径）；openpyxl 等工具写出的文件没有缓存值，此时回退
        # 显示公式文本本身，而不是空白。
        cached = load_workbook(path, read_only=True, data_only=True, keep_links=False)
        formulas = load_workbook(path, read_only=True, data_only=False, keep_links=False)
        try:
            for sheet, formula_sheet in zip(cached.worksheets, formulas.worksheets):
                max_column = min(max(sheet.max_column or 1, 1), max_cols)
                max_row = min(max(sheet.max_row or 1, 1), max_rows)
                cached_rows = sheet.iter_rows(min_row=1, max_row=max_row, max_col=max_column)
                formula_rows = formula_sheet.iter_rows(min_row=1, max_row=max_row, max_col=max_column)
                for cells, formula_cells in zip(cached_rows, formula_rows):
                    values = []
                    for cell, formula_cell in zip(cells, formula_cells):
                        value = cell.value
                        if value is None:
                            fallback = formula_cell.value
                            if isinstance(fallback, str) and fallback.startswith("="):
                                value = fallback
                        values.append(_format_office_value(value))
                    if any(values):
                        rows.append((sheet.title, values))
        finally:
            cached.close()
            formulas.close()
        if rows:
            return rows
    except Exception:  # pylint: disable=broad-except
        pass

    try:
        with zipfile.ZipFile(path) as archive:
            shared: list[str] = []
            if "xl/sharedStrings.xml" in archive.namelist():
                root = ET.fromstring(archive.read("xl/sharedStrings.xml"))
                shared = [_xml_text(item) for item in root if _local_name(item.tag) == "si"]
            workbook = ET.fromstring(archive.read("xl/workbook.xml"))
            rels: dict[str, str] = {}
            if "xl/_rels/workbook.xml.rels" in archive.namelist():
                relroot = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
                for rel in relroot:
                    rid = rel.attrib.get("Id")
                    target = rel.attrib.get("Target", "")
                    if rid:
                        rels[rid] = target.lstrip("/")
            sheets = [node for node in workbook.iter() if _local_name(node.tag) == "sheet"]
            for index, sheet in enumerate(sheets):
                name = sheet.attrib.get("name", f"工作表 {index + 1}")
                rid = next((value for key, value in sheet.attrib.items() if _local_name(key) == "id"), None)
                target = rels.get(rid or "", f"xl/worksheets/sheet{index + 1}.xml")
                if not target.startswith("xl/"):
                    target = "xl/" + target
                if target not in archive.namelist():
                    continue
                sheet_root = ET.fromstring(archive.read(target))
                count = 0
                for row in (node for node in sheet_root.iter() if _local_name(node.tag) == "row"):
                    values = []
                    for cell in (node for node in row if _local_name(node.tag) == "c"):
                        kind = cell.attrib.get("t", "")
                        value_node = next((child for child in cell if _local_name(child.tag) == "v"), None)
                        inline = next((child for child in cell if _local_name(child.tag) == "is"), None)
                        value = _xml_text(inline) if inline is not None else (value_node.text if value_node is not None else "")
                        if kind == "s":
                            try:
                                value = shared[int(value or 0)]
                            except (ValueError, IndexError):
                                pass
                        values.append(value or "")
                    if values:
                        rows.append((name, values[:max_cols]))
                        count += 1
                    if count >= max_rows:
                        break
    except (KeyError, OSError, ET.ParseError, zipfile.BadZipFile):
        return rows
    return rows


def _extract_pptx_slides(path: str, max_slides: int = 100) -> list[str]:
    """Extract visible text from PPTX/ODP slides."""
    ext = os.path.splitext(path)[1].lower()
    if ext in {".odp", ".otp"}:
        try:
            from odf import teletype  # type: ignore
            from odf.draw import Page  # type: ignore
            from odf.opendocument import load  # type: ignore
            from odf.text import P  # type: ignore

            document = load(path)
            result = []
            for slide in document.presentation.getElementsByType(Page)[:max_slides]:
                lines = [
                    teletype.extractText(paragraph).strip()
                    for paragraph in slide.getElementsByType(P)
                ]
                text = "\n".join(line for line in lines if line)
                if text:
                    result.append(text)
            if result:
                return result
        except Exception:  # pylint: disable=broad-except
            pass
    else:
        try:
            from pptx import Presentation  # type: ignore

            presentation = Presentation(path)
            result = []
            for slide in list(presentation.slides)[:max_slides]:
                lines = []
                for shape in slide.shapes:
                    if getattr(shape, "has_text_frame", False):
                        text = "\n".join(
                            paragraph.text.strip()
                            for paragraph in shape.text_frame.paragraphs
                            if paragraph.text.strip()
                        )
                        if text:
                            lines.append(text)
                    if getattr(shape, "has_table", False):
                        for row in shape.table.rows:
                            values = [cell.text.strip() for cell in row.cells]
                            if any(values):
                                lines.append("\t".join(values))
                if lines:
                    result.append("\n".join(lines))
            if result:
                return result
        except Exception:  # pylint: disable=broad-except
            pass

    try:
        with zipfile.ZipFile(path) as archive:
            if ext in {".odp", ".otp"}:
                root = ET.fromstring(archive.read("content.xml"))
                result = []
                for slide in [node for node in root.iter() if _local_name(node.tag) == "page"][:max_slides]:
                    text = " ".join(part.strip() for part in slide.itertext() if part.strip())
                    if text:
                        result.append(text)
                return result
            names = sorted(
                (name for name in archive.namelist() if re.fullmatch(r"ppt/slides/slide\d+\.xml", name)),
                key=lambda value: int(re.search(r"(\d+)", value).group(1)),
            )
            result = []
            for name in names[:max_slides]:
                root = ET.fromstring(archive.read(name))
                text = " ".join(part.strip() for part in root.itertext() if part.strip())
                if text:
                    result.append(text)
            return result
    except (KeyError, OSError, ET.ParseError, zipfile.BadZipFile):
        return []


def _format_time(milliseconds: int) -> str:
    seconds = max(0, int(milliseconds // 1000))
    hours, seconds = divmod(seconds, 3600)
    minutes, seconds = divmod(seconds, 60)
    return f"{hours}:{minutes:02d}:{seconds:02d}" if hours else f"{minutes}:{seconds:02d}"


_RTF_DESTINATIONS = frozenset({
    "annotation", "atnauthor", "atndate", "background", "blipuid",
    "bkmkend", "bkmkstart", "colortbl", "colorschememapping", "datafield",
    "datastore", "defchp", "defpap", "do", "doccomm", "docvar",
    "dptxbxtext", "factoidname", "falt", "fchars", "fldinst", "fontemb",
    "fontfile", "fonttbl", "footer", "footerf", "footerl", "footerr",
    "footnote", "formfield", "ftncn", "ftnsep", "ftnsepc", "generator",
    "header", "headerf", "headerl", "headerr", "hl", "hlfr", "hlinkbase",
    "info", "keycode", "latentstyles", "list", "listlevel", "listname",
    "listoverride", "listoverridetable", "listpicture", "listtable",
    "mmathpr", "nextfile", "nonesttables", "objalias", "objclass",
    "objdata", "object", "objname", "objsect", "pict", "pn", "pnseclvl",
    "private", "propname", "protend", "protstart", "protusertbl", "revtbl",
    "rsidtbl", "shp", "shpinst", "shprslt", "sn", "sp", "staticval",
    "stylesheet", "template", "themedata", "ud", "userprops", "xmlattrname",
    "xmlattrvalue", "xmlclose", "xmlname", "xmlnstbl", "xmlopen",
})

_RTF_CONTROL_TEXT = {
    "bullet": "\u2022",
    "cell": "\t",
    "emdash": "\u2014",
    "emspace": "\u2003",
    "endash": "\u2013",
    "enspace": "\u2002",
    "ldblquote": "\u201c",
    "line": "\n",
    "lquote": "\u2018",
    "page": "\n\n",
    "par": "\n",
    "qmspace": "\u2005",
    "rdblquote": "\u201d",
    "rquote": "\u2019",
    "row": "\n",
    "tab": "\t",
}


def _rtf_codepage(number: int) -> str:
    """Return a Python codec for an RTF ``\\ansicpg`` value."""
    aliases = {
        0: "cp1252",
        10000: "mac_roman",
        65001: "utf-8",
    }
    codec = aliases.get(number, f"cp{number}")
    try:
        codecs.lookup(codec)
    except LookupError:
        return "cp1252"
    return codec


def _decode_text_bytes(path: str, max_bytes: int) -> str:
    """读取文本文件并按 BOM 嗅探编码（v1.2.1 修复）。

    旧实现固定按 utf-8-sig 解：带 BOM 的 UTF-16/UTF-32 文件会显示夹带 \\x00
    的乱码。现在优先按 BOM 判定（UTF-32 必须在 UTF-16 之前检查：其 BOM 的
    前两字节与 UTF-16 相同）；无 BOM 时按 UTF-8 解，若出现替换字符则回退
    GB18030（校园常见编码，与 Android 端一致），仍失败按 latin-1 兜底。
    """
    with open(path, "rb") as stream:
        raw = stream.read(max_bytes)
    for bom, encoding in (
        (codecs.BOM_UTF8, "utf-8-sig"),
        (codecs.BOM_UTF32_LE, "utf-32"),
        (codecs.BOM_UTF32_BE, "utf-32"),
        (codecs.BOM_UTF16_LE, "utf-16"),
        (codecs.BOM_UTF16_BE, "utf-16"),
    ):
        if raw.startswith(bom):
            return raw.decode(encoding, errors="replace")
    text = raw.decode("utf-8", errors="replace")
    if "\ufffd" in text:
        try:
            return raw.decode("gb18030", errors="replace")
        except (UnicodeDecodeError, LookupError):
            return text
    return text


def _decode_rtf_text(content: str) -> str:
    """Decode the visible text in an RTF stream without requiring Word.

    The parser intentionally ignores formatting, embedded objects and metadata,
    but preserves the RTF features needed by ordinary documents: ANSI code
    pages, hexadecimal bytes, Unicode escapes and their fallback characters.
    Reading the source with Latin-1 keeps every original byte reversible.
    """
    states = [{"codepage": "cp1252", "uc_skip": 1, "ignored": False}]
    parts: list[str] = []
    encoded = bytearray()
    fallback_remaining = 0

    def state() -> dict[str, object]:
        return states[-1]

    def flush_encoded() -> None:
        if not encoded:
            return
        codec = str(state()["codepage"])
        parts.append(bytes(encoded).decode(codec, errors="replace"))
        encoded.clear()

    def consume_fallback() -> bool:
        nonlocal fallback_remaining
        if fallback_remaining <= 0:
            return False
        fallback_remaining -= 1
        return True

    def append_byte(value: int) -> None:
        if consume_fallback() or bool(state()["ignored"]):
            return
        encoded.append(value & 0xFF)

    def append_text(value: str, *, fallback_character: bool = False) -> None:
        if fallback_character and consume_fallback():
            return
        flush_encoded()
        if not bool(state()["ignored"]):
            parts.append(value)

    index = 0
    length = len(content)
    while index < length:
        character = content[index]
        if character == "{":
            flush_encoded()
            states.append(dict(state()))
            index += 1
            continue
        if character == "}":
            flush_encoded()
            if len(states) > 1:
                states.pop()
            index += 1
            continue
        if character != "\\":
            index += 1
            if character in "\r\n":
                continue
            value = ord(character)
            if value <= 0xFF:
                append_byte(value)
            else:
                append_text(character, fallback_character=True)
            continue

        index += 1
        if index >= length:
            break
        control = content[index]

        if control in "\\{}":
            append_text(control, fallback_character=True)
            index += 1
            continue
        if control == "'":
            digits = content[index + 1:index + 3]
            if len(digits) == 2 and all(value in "0123456789abcdefABCDEF" for value in digits):
                append_byte(int(digits, 16))
                index += 3
            else:
                index += 1
            continue
        if control == "*":
            flush_encoded()
            state()["ignored"] = True
            index += 1
            continue
        if control == "~":
            append_text("\u00a0", fallback_character=True)
            index += 1
            continue
        if control == "_":
            append_text("\u2011", fallback_character=True)
            index += 1
            continue
        if control == "-":
            append_text("\u00ad", fallback_character=True)
            index += 1
            continue
        if control in "\r\n":
            if control == "\r" and index + 1 < length and content[index + 1] == "\n":
                index += 1
            index += 1
            continue
        if not control.isalpha():
            index += 1
            continue

        word_start = index
        while index < length and content[index].isalpha():
            index += 1
        word = content[word_start:index].lower()
        sign = 1
        if index < length and content[index] == "-":
            sign = -1
            index += 1
        number_start = index
        while index < length and content[index].isdigit():
            index += 1
        parameter = None
        if index > number_start:
            parameter = sign * int(content[number_start:index])
        if index < length and content[index] == " ":
            index += 1

        if word == "bin" and parameter is not None:
            flush_encoded()
            index = min(length, index + max(0, parameter))
            continue
        if word in _RTF_DESTINATIONS:
            flush_encoded()
            state()["ignored"] = True
            continue
        if word == "ansicpg" and parameter is not None:
            flush_encoded()
            state()["codepage"] = _rtf_codepage(parameter)
            continue
        if word == "uc" and parameter is not None:
            state()["uc_skip"] = max(0, parameter)
            continue
        if word == "u" and parameter is not None:
            flush_encoded()
            code_unit = parameter & 0xFFFF
            if not bool(state()["ignored"]):
                parts.append(chr(code_unit))
            fallback_remaining = int(state()["uc_skip"])
            continue
        if word in _RTF_CONTROL_TEXT:
            append_text(_RTF_CONTROL_TEXT[word])

    flush_encoded()
    text = "".join(parts)
    # RTF stores non-BMP characters as UTF-16 surrogate pairs in consecutive
    # \u controls. Resolve valid pairs and replace isolated surrogates.
    text = text.encode("utf-16-le", errors="surrogatepass").decode("utf-16-le", errors="replace")
    return text.replace("\x00", "").strip()


class _OfficeRenderSignals(QObject):
    """Marshal a native Office conversion result back to the GUI thread."""

    # (output_path, output_dir, source_path)：source 让 GUI 能识别并丢弃
    # 「翻文件之后才到达」的旧文件转换结果（v1.2.1 回归修复）。
    finished = Signal(str, str, str)


class _ImagePreviewWidget(QWidget):
    """Resize-aware image canvas used inside the dialog's scroll area."""

    def __init__(self, path: str, parent=None):
        super().__init__(parent)
        self.path = path
        self._pixmap = QPixmap(path)
        self._movie: Optional[QMovie] = None
        self._label = QLabel()
        self._label.setAlignment(Qt.AlignCenter)
        self._label.setStyleSheet(f"background: {CARD}; padding: 12px;")
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(self._label)
        if os.path.splitext(path)[1].lower() == ".gif":
            movie = QMovie(path)
            if movie.isValid():
                self._movie = movie
                self._label.setMovie(movie)
                movie.start()
        self.setStyleSheet(f"background: {CARD};")
        self._refresh()

    @property
    def is_valid(self) -> bool:
        return self._movie is not None or not self._pixmap.isNull()

    def _refresh(self) -> None:
        available = self.size() - QSize(28, 28)
        if available.width() < 32 or available.height() < 32:
            available = QSize(max(32, self._pixmap.width()), max(32, self._pixmap.height()))
        if self._movie is not None:
            original = self._pixmap.size()
            self._movie.setScaledSize(
                original.scaled(available, Qt.KeepAspectRatio)
                if not original.isEmpty() else available
            )
            return
        if self._pixmap.isNull():
            return
        scaled = self._pixmap.scaled(available, Qt.KeepAspectRatio, Qt.SmoothTransformation)
        self._label.setPixmap(scaled)

    def resizeEvent(self, event) -> None:  # noqa: N802
        super().resizeEvent(event)
        self._refresh()

    def closeEvent(self, event) -> None:  # noqa: N802
        if self._movie is not None:
            self._movie.stop()
        super().closeEvent(event)


class DocumentPreviewDialog(QDialog):
    """Preview a local file without leaving the application."""

    def __init__(self, file_path: str, parent=None, course_id: Optional[int] = None,
                 state_db: Optional[str] = None):
        super().__init__(parent)
        # Preview dialogs are opened repeatedly from the file table.  Without
        # delete-on-close, every accepted/closed dialog remains a hidden child
        # of MainWindow and retains its document/media widget tree until the
        # entire application exits.
        self.setAttribute(Qt.WA_DeleteOnClose, True)
        self._original_file_path = os.path.abspath(file_path)
        self.file_path = self._original_file_path
        self._file_type = _detect_type(self.file_path)
        # v1.1.2 起：预览带着课程上下文，支持上一/下一文件与阅读进度。
        # course_id/state_db 为空（如从「最近变更」/外部入口打开）时只退化这两个
        # 功能，预览本身不受影响。
        self._course_id = course_id
        self._state_db = state_db
        # v1.2.1：对话框持有长生命周期的进度库连接——此前每翻一页/每 2 秒
        # 都新建 StateStore（跑完整建表脚本并获取与同步线程共用的类级写锁），
        # 既卡 UI 又与同步互相阻塞。
        self._progress_store_obj = None
        # v1.2.1：快速翻页时合并写库（500ms 防抖），与 Android 端口径一致。
        self._progress_save_timer = QTimer(self)
        self._progress_save_timer.setSingleShot(True)
        self._progress_save_timer.setInterval(500)
        self._progress_save_timer.timeout.connect(
            lambda: self._save_progress(force=True)
        )
        self._file_id: Optional[int] = None
        self._sibling_paths: List[str] = []
        self._last_saved_position = -1
        self._pdf_page = 0
        self._pdf_fallback_scroll: Optional[QScrollArea] = None
        self._pdf_fallback_pages: List[tuple] = []
        self._last_media_save_ms = 0
        # v1.2.1：媒体续读的待 seek 位置（LoadedMedia 后应用）
        self._pending_media_seek_ms = 0
        self._temp_pdf_path: Optional[str] = None
        self._temp_pdf_dir: Optional[str] = None
        self._pdf_document = None
        self._pdf_view = None
        self.media_player = None
        self.audio_output = None
        self.video_widget = None
        self.position_slider: Optional[QSlider] = None
        self.volume_slider: Optional[QSlider] = None
        self.play_button: Optional[QToolButton] = None
        self.loop_checkbox: Optional[QCheckBox] = None
        self.time_label: Optional[QLabel] = None
        self.media_message_label: Optional[QLabel] = None
        self._image_widget: Optional[_ImagePreviewWidget] = None
        self._cleaned_up = False
        self._resource_lock = threading.Lock()
        self._pending_office_dirs: set[str] = set()
        self._office_cancel_event = threading.Event()
        self._office_render_started = False
        self._office_signals = _OfficeRenderSignals(self)
        self._office_signals.finished.connect(self._on_office_rendered)
        self._office_thread: Optional[threading.Thread] = None
        self.setWindowTitle(f"预览 · {os.path.basename(self.file_path)}")
        self.setWindowIcon(app_icon())
        self.setObjectName("root")
        self.setMinimumSize(720, 560)
        self.resize(900, 680)
        self._build_ui()
        self._center()
        application = QGuiApplication.instance()
        if application is not None:
            application.aboutToQuit.connect(self._cleanup_resources)
        QTimer.singleShot(50, self._load_preview)

    def _center(self) -> None:
        screen = QGuiApplication.primaryScreen()
        if screen is None:
            return
        geometry = screen.availableGeometry()
        self.move(geometry.center().x() - self.width() // 2, geometry.center().y() - self.height() // 2)

    def _build_ui(self) -> None:
        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 16, 20, 16)
        layout.setSpacing(12)
        layout.addWidget(self._build_info_bar())
        self.preview_area = QScrollArea()
        self.preview_area.setWidgetResizable(True)
        self.preview_area.setFrameShape(QFrame.NoFrame)
        self.preview_area.setStyleSheet(
            f"QScrollArea {{ background: {CARD}; border: 1px solid {BORDER}; border-radius: 12px; }}"
        )
        self._preview_placeholder = QLabel("正在加载预览…")
        self._preview_placeholder.setAlignment(Qt.AlignCenter)
        self._preview_placeholder.setStyleSheet(f"color: {TEXT_SECONDARY}; padding: 40px;")
        self.preview_area.setWidget(self._preview_placeholder)
        layout.addWidget(self.preview_area, 1)
        button_row = QHBoxLayout()
        button_row.setSpacing(8)
        self.share_btn = QPushButton("分享…")
        self.share_btn.setToolTip("复制文件到其他位置")
        self.share_btn.setCursor(Qt.PointingHandCursor)
        self.share_btn.clicked.connect(self._on_share)
        self.open_default_btn = QPushButton("在默认应用打开")
        self.open_default_btn.setCursor(Qt.PointingHandCursor)
        self.open_default_btn.clicked.connect(self._on_open_default)
        self.close_btn = QPushButton("关闭")
        self.close_btn.setObjectName("primary")
        self.close_btn.setCursor(Qt.PointingHandCursor)
        self.close_btn.clicked.connect(self.accept)
        # v1.1.2：预览内直接翻到同一课程的上/下一个文件（像读电子书）
        self.prev_file_btn = QPushButton("← 上一个")
        self.prev_file_btn.setToolTip("同一课程的上一个文件（Alt+←）")
        self.prev_file_btn.setCursor(Qt.PointingHandCursor)
        self.prev_file_btn.clicked.connect(lambda: self._navigate_sibling(-1))
        self.next_file_btn = QPushButton("下一个 →")
        self.next_file_btn.setToolTip("同一课程的下一个文件（Alt+→）")
        self.next_file_btn.setCursor(Qt.PointingHandCursor)
        self.next_file_btn.clicked.connect(lambda: self._navigate_sibling(1))
        button_row.addWidget(self.prev_file_btn)
        button_row.addWidget(self.next_file_btn)
        button_row.addStretch()
        button_row.addWidget(self.share_btn)
        # 另加键盘快捷键（Alt+方向键翻文件）
        prev_shortcut = QShortcut(QKeySequence("Alt+Left"), self)
        prev_shortcut.activated.connect(lambda: self._navigate_sibling(-1))
        next_shortcut = QShortcut(QKeySequence("Alt+Right"), self)
        next_shortcut.activated.connect(lambda: self._navigate_sibling(1))
        button_row.addWidget(self.open_default_btn)
        button_row.addWidget(self.close_btn)
        layout.addLayout(button_row)

    def _build_info_bar(self) -> QFrame:
        card = QFrame()
        card.setObjectName("card")
        card.setMinimumHeight(72)
        card_layout = QHBoxLayout(card)
        card_layout.setContentsMargins(16, 12, 16, 12)
        info_layout = QVBoxLayout()
        info_layout.setSpacing(2)
        self._info_name_label = QLabel(os.path.basename(self.file_path))
        self._info_name_label.setStyleSheet("font-size: 14px; font-weight: 600;")
        self._info_name_label.setWordWrap(True)
        self._info_type_label = QLabel("")
        self._info_type_label.setStyleSheet(f"color: {TEXT_SECONDARY}; font-size: 12px;")
        self._info_type_label.setWordWrap(True)
        self._refresh_info_bar()
        info_layout.addWidget(self._info_name_label)
        info_layout.addWidget(self._info_type_label)
        card_layout.addLayout(info_layout, 1)
        return card

    def _refresh_info_bar(self) -> None:
        """切换文件后更新信息条（v1.1.2）。"""
        name_label = getattr(self, "_info_name_label", None)
        if name_label is not None:
            name_label.setText(os.path.basename(self.file_path))
        type_label = getattr(self, "_info_type_label", None)
        if type_label is not None:
            try:
                size_str = format_size(os.path.getsize(self.file_path))
            except OSError:
                size_str = "未知大小"
            type_label.setText(
                f"{self._type_label()}  ·  {size_str}  ·  {os.path.dirname(self.file_path)}")

    def _type_label(self) -> str:
        return {
            "pdf": "PDF 文档", "image": "图片", "text": "文本文件",
            "office": "Office 文档", "audio": "音频", "video": "视频",
            "unknown": "文件",
        }.get(self._file_type, "文件")

    def _load_preview(self) -> None:
        if self._cleaned_up:
            return
        if not os.path.exists(self.file_path):
            self._show_error("文件不存在")
            return
        # v1.1.2：先用原始路径解析 file_id 与同课程文件清单，
        # 之后（Office 转换）file_path 会被换成临时 PDF，此时上下文不再变化
        self._resolve_context()
        try:
            loaders = {
                "pdf": self._load_pdf_preview,
                "image": self._load_image_preview,
                "text": self._load_text_preview,
                "office": self._load_office_preview,
                "audio": lambda: self._load_media_preview(False),
                "video": lambda: self._load_media_preview(True),
            }
            loaders.get(self._file_type, self._load_unknown_preview)()
        except Exception as exc:  # pylint: disable=broad-except
            self._show_error(f"预览加载失败：{exc}")
        self._restore_progress()

    # -- 阅读进度与文件切换（v1.1.2） ---------------------------------
    def _progress_store(self):
        """长生命周期的进度库连接（GUI 线程内复用）；对话框关闭时释放。"""
        store = self._progress_store_obj
        if store is None:
            from ..state import StateStore
            store = StateStore(self._state_db)
            self._progress_store_obj = store
        return store

    def _resolve_context(self) -> None:
        """按原始路径反查 file_id 与同课程可见文件清单。"""
        self._file_id = None
        self._sibling_paths = []
        if not self._state_db:
            self._update_sibling_buttons()
            return
        try:
            store = self._progress_store()
        except Exception:  # pylint: disable=broad-except
            self._update_sibling_buttons()
            return
        try:
            row = store.file_id_by_path(self._original_file_path)
            if row:
                self._file_id = row.get("file_id")
                if self._course_id is None:
                    self._course_id = row.get("course_id")
            if self._course_id is not None:
                files = store.list_files_by_course(self._course_id)
                self._sibling_paths = [
                    f["local_path"] for f in files
                    if f.get("status") != "remote_missing"
                    and f.get("local_path") and os.path.exists(f["local_path"])
                ]
        except Exception:  # pylint: disable=broad-except
            pass
        self._update_sibling_buttons()

    def _current_position(self) -> Optional[tuple]:
        """当前后端的阅读位置：(页/秒, 总量, 是否媒体)。None 表示不记录。"""
        if self.media_player is not None:
            try:
                duration = self.media_player.duration() // 1000
                return (self.media_player.position() // 1000, duration, True)
            except Exception:  # pylint: disable=broad-except
                return None
        if self._pdf_view is not None:
            total = 0
            doc = self._pdf_document
            if doc is not None:
                try:
                    total = doc.pageCount
                except Exception:  # pylint: disable=broad-except
                    pass
            return (self._pdf_page, total, False)
        if self._pdf_fallback_scroll is not None:
            return (self._fallback_visible_page(), len(self._pdf_fallback_pages), False)
        widget = self.preview_area.widget()
        if widget is not None and hasattr(widget, "verticalScrollBar"):
            bar = widget.verticalScrollBar()
            return (bar.value(), bar.maximum(), False)
        return None

    def _fallback_visible_page(self) -> int:
        """PyMuPDF 降级链路：视口顶部的页码。"""
        scroll = self._pdf_fallback_scroll
        if scroll is None or not self._pdf_fallback_pages:
            return 0
        viewport_top = scroll.verticalScrollBar().value()
        for index, label in self._pdf_fallback_pages:
            if label.geometry().bottom() >= viewport_top:
                return index
        return self._pdf_fallback_pages[-1][0]

    def _save_progress(self, force: bool = False) -> None:
        if not self._file_id or not self._state_db:
            return
        try:
            info = self._current_position()
        except Exception:  # pylint: disable=broad-except
            return
        if info is None:
            return
        position, total, is_media = info
        if not force and position == self._last_saved_position:
            return
        self._last_saved_position = position
        try:
            store = self._progress_store()
            store.set_reading_progress(self._file_id, self._course_id or 0,
                                       position, total, is_media)
        except Exception:  # pylint: disable=broad-except
            pass

    def _restore_progress(self) -> None:
        if not self._file_id or not self._state_db:
            return
        try:
            store = self._progress_store()
            prog = store.get_reading_progress(self._file_id)
        except Exception:  # pylint: disable=broad-except
            return
        if not prog:
            return
        try:
            position = int(prog.get("position") or 0)
            total = int(prog.get("total") or 0)
            is_media = bool(prog.get("is_media"))
            if position <= 0:
                return
            if self.media_player is not None and is_media:
                # v1.2.1：媒体尚未加载完成时 setPosition 会被静默丢弃——先记下
                # 待恢复位置，等 LoadedMedia 状态到达再 seek（_on_media_status）。
                self._pending_media_seek_ms = position * 1000
                try:
                    if self.media_player.mediaStatus() == self.media_player.LoadedMedia:
                        self.media_player.setPosition(self._pending_media_seek_ms)
                        self._pending_media_seek_ms = 0
                except Exception:  # pylint: disable=broad-except
                    pass
            elif self._pdf_view is not None:
                target = min(position, max(total - 1, 0))
                self._pdf_page = target
                try:
                    self._pdf_view.pageNavigator().jump(target)
                except Exception:  # pylint: disable=broad-except
                    pass
            elif self._pdf_fallback_scroll is not None:
                index = min(position, len(self._pdf_fallback_pages) - 1)
                if 0 <= index < len(self._pdf_fallback_pages):
                    scroll = self._pdf_fallback_scroll
                    scroll.ensureWidgetVisible(self._pdf_fallback_pages[index][1])
            else:
                widget = self.preview_area.widget()
                if widget is not None and hasattr(widget, "verticalScrollBar"):
                    bar = widget.verticalScrollBar()
                    bar.setValue(min(position, bar.maximum()))
        except Exception:  # pylint: disable=broad-except
            pass

    def _navigate_sibling(self, delta: int) -> None:
        """切换到同课程的上/下一个文件：先存进度，再像首次打开一样重建。"""
        if not self._sibling_paths or delta == 0:
            return
        try:
            index = self._sibling_paths.index(self._original_file_path)
        except ValueError:
            return
        new_index = index + delta
        if not 0 <= new_index < len(self._sibling_paths):
            return
        self._save_progress(force=True)
        # 释放当前文件占用的 PDF/媒体/Office 资源，然后重新装填
        self._cleanup_resources()
        self._cleaned_up = False
        self._office_cancel_event = threading.Event()
        new_path = os.path.abspath(self._sibling_paths[new_index])
        self._original_file_path = new_path
        self.file_path = new_path
        self._file_type = _detect_type(new_path)
        self._pdf_page = 0
        self._pdf_view = None
        self._pdf_document = None
        self._pdf_fallback_scroll = None
        self._pdf_fallback_pages = []
        self._last_saved_position = -1
        self._last_media_save_ms = 0
        self._pending_media_seek_ms = 0
        self._image_widget = None
        self._office_render_started = False
        # v1.2.1：媒体对象与信号也必须随翻文件重建——_cleanup_resources 只停播清源，
        # 旧 player 的 positionChanged 等信号仍会驱动新播放控件、污染进度与时间
        # 显示，且旧实例会累积存活到对话框关闭。
        stale_player = self.media_player
        self.media_player = None
        self.audio_output = None
        self.video_widget = None
        self.position_slider = None
        self.time_label = None
        self.loop_checkbox = None
        if stale_player is not None:
            try:
                stale_player.stop()
                stale_player.setSource(QUrl())
            except (RuntimeError, AttributeError):
                pass
            stale_player.deleteLater()
        self.setWindowTitle(f"预览 · {os.path.basename(new_path)}")
        self._refresh_info_bar()
        self._resolve_context()
        self._load_preview()

    def _update_sibling_buttons(self) -> None:
        if getattr(self, "prev_file_btn", None) is None:
            return
        paths = self._sibling_paths or []
        count = len(paths)
        if count <= 1:
            self.prev_file_btn.setEnabled(False)
            self.next_file_btn.setEnabled(False)
            self.prev_file_btn.setToolTip("同一课程的上一个文件（Alt+←）")
            self.next_file_btn.setToolTip("同一课程的下一个文件（Alt+→）")
            return
        index = -1
        try:
            index = paths.index(self._original_file_path)
        except ValueError:
            pass
        has_prev = index > 0
        has_next = 0 <= index < count - 1
        self.prev_file_btn.setEnabled(has_prev)
        self.next_file_btn.setEnabled(has_next)
        position = f"第 {index + 1}/{count} 个" if index >= 0 else f"共 {count} 个"
        self.prev_file_btn.setToolTip(
            f"同一课程的上一个文件（Alt+←）· {position}" + ("" if has_prev else " · 已是第一个"))
        self.next_file_btn.setToolTip(
            f"同一课程的下一个文件（Alt+→）· {position}" + ("" if has_next else " · 已是最后一个"))

    def _set_preview_widget(self, widget: QWidget) -> None:
        # QScrollArea owns its current widget and destroys it when setWidget()
        # installs a replacement. Calling deleteLater() on that stale wrapper
        # raises RuntimeError in PySide during asynchronous Office -> PDF swaps.
        self.preview_area.setWidget(widget)

    # -- PDF -----------------------------------------------------------
    def _load_pdf_preview(self, path: Optional[str] = None) -> None:
        path = path or self.file_path
        try:
            from PySide6.QtPdf import QPdfDocument
            from PySide6.QtPdfWidgets import QPdfView
            pdf_view = QPdfView()
            pdf_doc = QPdfDocument(self)
            status = pdf_doc.load(path)
            if getattr(status, "name", "Ready") not in {"Ready", "None_", "Loading"}:
                raise RuntimeError(f"PDF 加载状态：{status}")
            pdf_view.setDocument(pdf_doc)
            pdf_view.setPageMode(QPdfView.PageMode.MultiPage)
            pdf_view.setZoomMode(QPdfView.ZoomMode.FitToWidth)
            self._pdf_document = pdf_doc
            self._pdf_view = pdf_view
            # v1.1.2：页码变化时记录阅读进度（翻页即存，关闭时强制再存一次）
            try:
                pdf_view.currentPageChanged.connect(self._on_pdf_page_changed)
            except AttributeError:
                # 极少数 Qt 构建没有该信号：退化为关闭时读取导航器当前页
                pass
            self._set_preview_widget(pdf_view)
        except Exception:
            self._load_pdf_fallback(path)

    def _on_pdf_page_changed(self, page: int) -> None:
        self._pdf_page = max(0, int(page))
        # v1.2.1：连续翻页只触发一次写库（防抖），滚动期间不再逐页建表。
        self._progress_save_timer.start()

    def _load_pdf_fallback(self, path: Optional[str] = None) -> None:
        """PyMuPDF 降级：渲染全部页面（纵向连续滚动，与 Android 链路观感一致）。

        v1.1.2 前只渲染第一页，长文档基本不可读。超大 PDF 出于内存安全限制
        首批渲染 30 页，并在页脚如实标注总页数。
        """
        path = path or self.file_path
        try:
            import fitz  # type: ignore
        except ImportError:
            self._show_unsupported_card(
                "PDF 预览组件不可用",
                "当前环境缺少 PDF 渲染组件，您可以使用系统默认应用打开此文件。")
            return
        try:
            with fitz.open(path) as doc:
                page_count = doc.page_count
                if not page_count:
                    raise RuntimeError("PDF 没有任何页面")
                render_limit = min(page_count, 30)
                container = QWidget()
                layout = QVBoxLayout(container)
                layout.setContentsMargins(0, 0, 0, 0)
                layout.setSpacing(14)
                labels: List[tuple] = []
                for index in range(render_limit):
                    pix = doc[index].get_pixmap(dpi=150)
                    image = QPixmap()
                    if not image.loadFromData(pix.tobytes("png")):
                        continue
                    page_label = QLabel()
                    page_label.setPixmap(image)
                    page_label.setAlignment(Qt.AlignCenter)
                    layout.addWidget(page_label)
                    labels.append((index, page_label))
                    footer = QLabel(f"第 {index + 1} / {page_count} 页")
                    footer.setAlignment(Qt.AlignCenter)
                    footer.setStyleSheet(f"color: {TEXT_SECONDARY}; font-size: 12px;")
                    layout.addWidget(footer)
                if page_count > render_limit:
                    note = QLabel(
                        f"为控制内存占用，此处渲染前 {render_limit} 页；"
                        f"PDF 共 {page_count} 页，可「在默认应用打开」查看完整内容。")
                    note.setWordWrap(True)
                    note.setAlignment(Qt.AlignCenter)
                    note.setStyleSheet(f"color: {TEXT_SECONDARY}; font-size: 12px; padding: 12px;")
                    layout.addWidget(note)
                layout.addStretch()
                scroll = QScrollArea()
                scroll.setWidgetResizable(True)
                scroll.setFrameShape(QFrame.NoFrame)
                scroll.setStyleSheet(
                    f"QScrollArea {{ background: {CARD}; border: 1px solid {BORDER};"
                    f" border-radius: 12px; }}")
                scroll.setWidget(container)
                self._pdf_fallback_scroll = scroll
                self._pdf_fallback_pages = labels
                self._set_preview_widget(scroll)
        except Exception:  # pylint: disable=broad-except
            self._show_unsupported_card(
                "PDF 预览组件不可用",
                "当前环境缺少 PDF 渲染组件，您可以使用系统默认应用打开此文件。")

    # -- images -------------------------------------------------------
    def _load_image_preview(self) -> None:
        widget = _ImagePreviewWidget(self.file_path)
        if not widget.is_valid:
            if self.file_path.lower().endswith(".svg"):
                try:
                    from PySide6.QtSvgWidgets import QSvgWidget
                    svg = QSvgWidget(self.file_path)
                    svg.setStyleSheet(f"background: {CARD};")
                    self._set_preview_widget(svg)
                    return
                except (ImportError, OSError):
                    pass
            # Qt's image plugins vary between distributions (notably for
            # HEIC/AVIF). Pillow is optional, but gives packaged builds a
            # reliable second decoder for those formats.
            try:
                from PIL import Image  # type: ignore
                extension = os.path.splitext(self.file_path)[1].lower()
                if extension in {".heic", ".heif"}:
                    from pillow_heif import register_heif_opener  # type: ignore
                    register_heif_opener()
                elif extension == ".avif":
                    # Pillow only gained a built-in AVIF plugin in recent
                    # releases.  The declared pillow-heif dependency keeps
                    # AVIF preview working with the older supported Pillow
                    # versions as well.
                    try:
                        from pillow_heif import register_avif_opener  # type: ignore
                        register_avif_opener()
                    except ImportError:
                        from pillow_heif import register_heif_opener  # type: ignore
                        register_heif_opener()
                image = Image.open(self.file_path).convert("RGBA")
                raw = image.tobytes("raw", "RGBA")
                from PySide6.QtGui import QImage
                qimage = QImage(raw, image.width, image.height, QImage.Format_RGBA8888).copy()
                widget = _ImagePreviewWidget.__new__(_ImagePreviewWidget)
                QWidget.__init__(widget)
                widget.path = self.file_path
                widget._pixmap = QPixmap.fromImage(qimage)
                widget._movie = None
                widget._label = QLabel()
                widget._label.setAlignment(Qt.AlignCenter)
                widget._label.setStyleSheet(f"background: {CARD}; padding: 12px;")
                layout = QVBoxLayout(widget)
                layout.setContentsMargins(0, 0, 0, 0)
                layout.addWidget(widget._label)
                widget.setStyleSheet(f"background: {CARD};")
                widget._refresh()
            except Exception:  # pylint: disable=broad-except
                pass
        if not widget.is_valid:
            self._show_error("无法加载图片")
            return
        self._image_widget = widget
        self._set_preview_widget(widget)

    def _show_image_preview(self, pixmap: QPixmap) -> None:
        widget = _ImagePreviewWidget.__new__(_ImagePreviewWidget)
        QWidget.__init__(widget)
        widget.path = ""
        widget._pixmap = pixmap
        widget._movie = None
        widget._label = QLabel()
        widget._label.setAlignment(Qt.AlignCenter)
        widget._label.setStyleSheet(f"background: {CARD}; padding: 12px;")
        layout = QVBoxLayout(widget)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(widget._label)
        widget.setStyleSheet(f"background: {CARD};")
        widget._refresh()
        self._image_widget = widget
        self._set_preview_widget(widget)

    # -- text and tables ----------------------------------------------
    def _load_text_preview(self) -> None:
        if os.path.splitext(self.file_path)[1].lower() == ".rtf":
            self._load_rtf_preview()
            return
        if os.path.splitext(self.file_path)[1].lower() in {".html", ".htm"}:
            self._load_html_preview()
            return
        if os.path.splitext(self.file_path)[1].lower() in {".csv", ".tsv"} and self._load_csv_preview():
            return
        max_bytes = 2 * 1024 * 1024
        try:
            file_size = os.path.getsize(self.file_path)
            content = _decode_text_bytes(self.file_path, max_bytes)
            if file_size > max_bytes:
                content += f"\n\n… 文件过大，仅预览前 {format_size(max_bytes)} …"
        except Exception as exc:  # pylint: disable=broad-except
            self._show_error(f"读取文件失败：{exc}")
            return
        text_edit = QPlainTextEdit()
        text_edit.setReadOnly(True)
        text_edit.setPlainText(content)
        text_edit.setLineWrapMode(QPlainTextEdit.NoWrap)
        text_edit.setStyleSheet(
            f"QPlainTextEdit {{ background: {CARD}; border: none; font-family: Consolas, monospace; "
            f"font-size: 12px; padding: 12px; color: {TEXT}; }}"
        )
        self._set_preview_widget(text_edit)

    def _load_html_preview(self) -> None:
        max_bytes = 4 * 1024 * 1024
        try:
            content = _decode_text_bytes(self.file_path, max_bytes)
        except OSError as exc:
            self._show_error(f"读取文件失败：{exc}")
            return
        browser = QTextBrowser()
        browser.setOpenExternalLinks(False)
        browser.setOpenLinks(False)
        browser.document().setBaseUrl(
            QUrl.fromLocalFile(os.path.dirname(self.file_path) + os.sep)
        )
        browser.setHtml(content)
        browser.setStyleSheet(f"QTextBrowser {{ background: {CARD}; border: none; padding: 12px; color: {TEXT}; }}")
        self._set_preview_widget(browser)

    def _load_rtf_preview(self) -> None:
        """Render the readable part of an RTF file without Word."""
        try:
            with open(self.file_path, "r", encoding="latin-1") as stream:
                content = stream.read(2 * 1024 * 1024)
        except OSError as exc:
            self._show_error(f"读取文件失败：{exc}")
            return
        self._show_text_blocks([_decode_rtf_text(content)], title="文档内容")

    def _load_csv_preview(self) -> bool:
        delimiter = "\t" if self.file_path.lower().endswith(".tsv") else ","
        max_rows, max_cols = 1000, 40
        try:
            # v1.2.1：与文本预览一致，按 BOM 嗅探编码（UTF-16 表格不再乱码）
            text = _decode_text_bytes(self.file_path, 4 * 1024 * 1024)
            values = list(
                islice(csv.reader(io.StringIO(text, newline=""), delimiter=delimiter), max_rows)
            )
        except (OSError, csv.Error):
            return False
        if not values:
            return False
        truncated_rows = len(values) >= max_rows
        truncated_cols = any(len(row) > max_cols for row in values)
        columns = min(max(len(row) for row in values), max_cols)
        table = QTableWidget(len(values), columns)
        table.setAlternatingRowColors(True)
        table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        for row, values_row in enumerate(values):
            for column, value in enumerate(values_row[:columns]):
                table.setItem(row, column, QTableWidgetItem(value))
        table.resizeColumnsToContents()
        # v1.2.1：超限不再静默截断——在表头 tooltip 与末行如实说明
        if truncated_rows or truncated_cols:
            detail = []
            if truncated_rows:
                detail.append(f"行数超过 {max_rows}")
            if truncated_cols:
                detail.append(f"列数超过 {max_cols}")
            table.setAccessibleDescription(
                "CSV/TSV 预览已截断：" + "、".join(detail)
            )
            note_row = len(values)
            table.insertRow(note_row)
            table.setItem(
                note_row, 0,
                QTableWidgetItem(f"… 已截断：{'、'.join(detail)}，仅显示前 {max_rows} 行 / 前 {max_cols} 列 …"),
            )
        self._set_preview_widget(table)
        return True

    # -- Office -------------------------------------------------------
    def _load_office_preview(self) -> None:
        ext = os.path.splitext(self._original_file_path)[1].lower()
        if self._office_renderer_available():
            loading = QLabel("正在生成完整预览…")
            loading.setAlignment(Qt.AlignCenter)
            loading.setStyleSheet(f"background: {CARD}; color: {TEXT_SECONDARY}; padding: 40px;")
            self._set_preview_widget(loading)
            self._start_office_render()
            return
        self._load_structured_office_preview(ext)

    def _load_structured_office_preview(self, ext: str) -> None:
        """Show readable Office content when no page renderer is available."""
        if ext == ".rtf":
            self._load_rtf_preview()
            return
        if ext in {".docx", ".docm", ".odt", ".ott"}:
            blocks = _extract_docx_blocks(self._original_file_path)
            if blocks:
                self._show_text_blocks(blocks, title="文档内容")
                return
        if ext in {".xlsx", ".xlsm", ".xltx", ".ods", ".ots"}:
            rows = _extract_xlsx_rows(self._original_file_path)
            if rows:
                self._show_spreadsheet_rows(rows)
                return
        if ext in {".pptx", ".pptm", ".ppsx", ".odp", ".otp"}:
            slides = _extract_pptx_slides(self._original_file_path)
            if slides:
                self._show_slide_text(slides)
                return
        self._show_unsupported_card(
            "Office 文档预览",
            "无法在当前环境解析此文件。安装 Microsoft Office 或 LibreOffice 后可显示完整页面，"
            "也可以使用下方按钮在系统默认应用中打开。",
        )

    def _office_renderer_available(self) -> bool:
        if any(shutil.which(name) for name in ("soffice", "libreoffice")):
            return True
        if os.name != "nt":
            return False
        prog_id = None
        ext = os.path.splitext(self._original_file_path)[1].lower()
        if ext in WORD_EXTS:
            prog_id = "Word.Application"
        elif ext in EXCEL_EXTS:
            prog_id = "Excel.Application"
        elif ext in PPT_EXTS:
            prog_id = "PowerPoint.Application"
        if prog_id is None:
            return False
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_CLASSES_ROOT, prog_id + r"\CLSID"):
                return True
        except (ImportError, FileNotFoundError, OSError):
            return False

    def _start_office_render(self) -> None:
        with self._resource_lock:
            if self._cleaned_up or self._office_render_started:
                return
            self._office_render_started = True
        source = self._original_file_path
        cancel_event = self._office_cancel_event
        signals = self._office_signals

        def render() -> None:
            try:
                output_path, output_dir = self._convert_office_file(source, cancel_event)
            except Exception:  # pylint: disable=broad-except
                output_path, output_dir = "", ""
            if not self._register_office_result(output_dir):
                if output_dir:
                    shutil.rmtree(output_dir, ignore_errors=True)
                return
            try:
                signals.finished.emit(output_path, output_dir, source)
            except RuntimeError:
                self._discard_office_result(output_dir)

        self._office_thread = threading.Thread(
            target=render,
            name="office-preview-render",
            daemon=True,
        )
        self._office_thread.start()

    def _register_office_result(self, output_dir: str) -> bool:
        """Register a worker result until the GUI either claims or cleans it."""
        with self._resource_lock:
            if self._cleaned_up:
                return False
            if output_dir:
                self._pending_office_dirs.add(output_dir)
            return True

    def _claim_office_result(self, output_dir: str) -> bool:
        with self._resource_lock:
            if output_dir:
                self._pending_office_dirs.discard(output_dir)
            return not self._cleaned_up

    def _discard_office_result(self, output_dir: str) -> None:
        with self._resource_lock:
            if output_dir:
                self._pending_office_dirs.discard(output_dir)
        if output_dir:
            shutil.rmtree(output_dir, ignore_errors=True)

    def _on_office_rendered(self, output_path: str, output_dir: str, source: str = "") -> None:
        if not self._claim_office_result(output_dir):
            if output_dir:
                shutil.rmtree(output_dir, ignore_errors=True)
            return
        # v1.2.1：翻文件后迟到的旧转换结果必须整体丢弃，否则旧 PDF 会覆盖
        # 新文件的预览，且新文件的临时目录被孤立泄漏。
        if source and os.path.abspath(source) != os.path.abspath(self._original_file_path):
            if output_dir:
                self._discard_office_result(output_dir)
            return
        if output_path and os.path.isfile(output_path):
            self._temp_pdf_path = output_path
            self._temp_pdf_dir = output_dir
            self.file_path = output_path
            self._load_pdf_preview(output_path)
            # v1.1.2：异步转换重建了 PDF 视图，此时再恢复一次阅读位置
            self._restore_progress()
            return
        if output_dir:
            shutil.rmtree(output_dir, ignore_errors=True)
        self._load_structured_office_preview(
            os.path.splitext(self._original_file_path)[1].lower()
        )

    def _show_text_blocks(self, blocks: Sequence[str], title: str = "") -> None:
        text_edit = QPlainTextEdit()
        text_edit.setReadOnly(True)
        text_edit.setPlainText((title + "\n\n" if title else "") + "\n\n".join(blocks))
        text_edit.setStyleSheet(
            f"QPlainTextEdit {{ background: {CARD}; border: none; font-family: Microsoft YaHei UI, sans-serif; "
            f"font-size: 13px; padding: 18px; color: {TEXT}; }}"
        )
        self._set_preview_widget(text_edit)

    def _show_spreadsheet_rows(self, rows: Sequence[tuple[str, Sequence[str]]]) -> None:
        max_columns = min(max(len(values) for _, values in rows), 40)
        table = QTableWidget(len(rows), max_columns + 1)
        table.setHorizontalHeaderLabels(["工作表"] + [f"列 {idx + 1}" for idx in range(max_columns)])
        table.setAlternatingRowColors(True)
        table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        for row, (sheet, values) in enumerate(rows):
            table.setItem(row, 0, QTableWidgetItem(sheet))
            for column, value in enumerate(values[:max_columns], start=1):
                table.setItem(row, column, QTableWidgetItem(str(value)))
        table.resizeColumnsToContents()
        self._set_preview_widget(table)

    def _show_slide_text(self, slides: Sequence[str]) -> None:
        self._show_text_blocks([f"第 {index + 1} 页\n{text}" for index, text in enumerate(slides)], title="演示文稿")

    @staticmethod
    def _wait_for_converter(process, cancel_event: Optional[threading.Event], timeout: float = 90.0) -> int:
        """Wait for a dedicated converter process while allowing dialog close."""
        deadline = monotonic() + timeout
        while True:
            if cancel_event is not None and cancel_event.is_set():
                break
            remaining = deadline - monotonic()
            if remaining <= 0:
                break
            try:
                return process.wait(timeout=min(0.2, remaining))
            except subprocess.TimeoutExpired:
                continue
        try:
            process.terminate()
            process.wait(timeout=3)
        except (OSError, subprocess.SubprocessError):
            try:
                process.kill()
                process.wait(timeout=3)
            except (OSError, subprocess.SubprocessError):
                pass
        return -1

    @staticmethod
    def _convert_office_file(
        source_path: str,
        cancel_event: Optional[threading.Event] = None,
    ) -> tuple[str, str]:
        """Render an Office file to PDF without touching Qt GUI objects."""
        ext = os.path.splitext(source_path)[1].lower()
        if ext not in OFFICE_EXTS or (cancel_event is not None and cancel_event.is_set()):
            return "", ""
        output_dir = tempfile.mkdtemp(prefix="fudan_preview_")
        output_path = os.path.join(output_dir, f"{Path(source_path).stem}.pdf")
        keep_output = False
        try:
            try:
                import win32com.client  # type: ignore
                import pythoncom  # type: ignore
            except (ImportError, OSError):
                win32com = pythoncom = None  # type: ignore
            if win32com is not None and pythoncom is not None:
                word = excel = ppt = doc = workbook = presentation = None
                com_initialized = False
                try:
                    pythoncom.CoInitialize()
                    com_initialized = True
                    if ext in WORD_EXTS:
                        word = win32com.client.DispatchEx("Word.Application")
                        word.Visible = False
                        word.DisplayAlerts = 0
                        word.AutomationSecurity = 3
                        doc = word.Documents.Open(
                            source_path,
                            ConfirmConversions=False,
                            ReadOnly=True,
                            AddToRecentFiles=False,
                            Visible=False,
                            OpenAndRepair=True,
                        )
                        doc.SaveAs(output_path, FileFormat=17)
                    elif ext in EXCEL_EXTS:
                        excel = win32com.client.DispatchEx("Excel.Application")
                        excel.Visible = False
                        excel.DisplayAlerts = False
                        excel.AutomationSecurity = 3
                        workbook = excel.Workbooks.Open(
                            source_path,
                            UpdateLinks=0,
                            ReadOnly=True,
                            IgnoreReadOnlyRecommended=True,
                            AddToMru=False,
                        )
                        workbook.ExportAsFixedFormat(0, output_path)
                    else:
                        ppt = win32com.client.DispatchEx("PowerPoint.Application")
                        ppt.AutomationSecurity = 3
                        # Several Office versions refuse export while the
                        # application is hidden. WithWindow hides the document.
                        ppt.Visible = True
                        presentation = ppt.Presentations.Open(
                            source_path, WithWindow=False, ReadOnly=True
                        )
                        presentation.SaveAs(output_path, 32)
                    if (
                        os.path.isfile(output_path)
                        and os.path.getsize(output_path) > 0
                        and not (cancel_event is not None and cancel_event.is_set())
                    ):
                        keep_output = True
                        return output_path, output_dir
                except Exception:  # pylint: disable=broad-except
                    pass
                finally:
                    for obj, args in ((doc, (False,)), (workbook, (False,)), (presentation, ())):
                        if obj is not None:
                            try:
                                obj.Close(*args)
                            except Exception:  # pylint: disable=broad-except
                                pass
                    for app in (word, excel, ppt):
                        if app is not None:
                            try:
                                app.Quit()
                            except Exception:  # pylint: disable=broad-except
                                pass
                    if com_initialized:
                        try:
                            pythoncom.CoUninitialize()
                        except Exception:  # pylint: disable=broad-except
                            pass
            if cancel_event is not None and cancel_event.is_set():
                return "", ""
            for executable in ("soffice", "libreoffice"):
                executable_path = shutil.which(executable)
                if executable_path is None:
                    continue
                try:
                    profile_dir = os.path.join(output_dir, "libreoffice-profile")
                    os.makedirs(profile_dir, exist_ok=True)
                    process = subprocess.Popen(
                        [
                            executable_path,
                            "--headless",
                            "--nologo",
                            "--nodefault",
                            "--nofirststartwizard",
                            f"-env:UserInstallation={Path(profile_dir).as_uri()}",
                            "--convert-to",
                            "pdf",
                            "--outdir",
                            output_dir,
                            source_path,
                        ],
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                        creationflags=(
                            getattr(subprocess, "CREATE_NO_WINDOW", 0)
                            if os.name == "nt" else 0
                        ),
                    )
                    return_code = DocumentPreviewDialog._wait_for_converter(
                        process, cancel_event
                    )
                    generated = os.path.join(output_dir, f"{Path(source_path).stem}.pdf")
                    if (
                        return_code == 0
                        and os.path.isfile(generated)
                        and os.path.getsize(generated) > 0
                        and not (cancel_event is not None and cancel_event.is_set())
                    ):
                        keep_output = True
                        return generated, output_dir
                except (OSError, subprocess.SubprocessError, ValueError):
                    pass
            return "", ""
        finally:
            if not keep_output:
                shutil.rmtree(output_dir, ignore_errors=True)

    # -- media --------------------------------------------------------
    @staticmethod
    def _multimedia_classes():
        try:
            from ..bootstrap import ensure_qtmultimedia
            ensure_qtmultimedia()
        except Exception:  # pragma: no cover
            pass
        from PySide6.QtMultimedia import QAudioOutput, QMediaPlayer
        try:
            from PySide6.QtMultimediaWidgets import QVideoWidget
        except ImportError:
            QVideoWidget = None
        return QAudioOutput, QMediaPlayer, QVideoWidget

    def _load_media_preview(self, is_video: bool) -> None:
        try:
            QAudioOutput, QMediaPlayer, QVideoWidget = self._multimedia_classes()
        except (ImportError, OSError) as exc:
            self._show_unsupported_card(
                "媒体播放组件不可用",
                f"当前环境无法加载 Qt 多媒体组件（{exc}）。请安装完整的 PySide6 或使用默认应用打开。",
            )
            return
        if is_video and QVideoWidget is None:
            self._show_unsupported_card("视频组件不可用", "当前环境缺少 QtMultimediaWidgets，无法在软件内显示视频。")
            return
        self.media_player = QMediaPlayer(self)
        self.audio_output = QAudioOutput(self)
        self.audio_output.setVolume(0.8)
        self.media_player.setAudioOutput(self.audio_output)
        container = QWidget()
        container.setStyleSheet(f"background: {CARD};")
        layout = QVBoxLayout(container)
        layout.setContentsMargins(16, 16, 16, 16)
        layout.setSpacing(12)
        if is_video:
            self.video_widget = QVideoWidget(container)
            self.video_widget.setMinimumHeight(300)
            self.video_widget.setStyleSheet("background: #111827; border-radius: 8px;")
            self.media_player.setVideoOutput(self.video_widget)
            layout.addWidget(self.video_widget, 1)
        else:
            banner = QLabel("音频预览")
            banner.setAlignment(Qt.AlignCenter)
            banner.setMinimumHeight(180)
            banner.setStyleSheet(
                f"background: #EEF1FB; border-radius: 10px; color: {ACCENT}; font-size: 24px; font-weight: 600;"
            )
            layout.addWidget(banner, 1)
        layout.addWidget(self._build_media_controls())
        self._set_preview_widget(container)
        self.media_player.setSource(QUrl.fromLocalFile(self.file_path))

    def _build_media_controls(self) -> QWidget:
        controls = QWidget()
        root = QVBoxLayout(controls)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(8)
        seek_row = QHBoxLayout()
        self.position_slider = QSlider(Qt.Horizontal)
        self.position_slider.setRange(0, 0)
        self.position_slider.setEnabled(False)
        self.position_slider.setToolTip("拖动调整播放位置")
        self.position_slider.sliderMoved.connect(self._seek_media)
        self.time_label = QLabel("0:00 / 0:00")
        self.time_label.setMinimumWidth(90)
        self.time_label.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        seek_row.addWidget(self.position_slider, 1)
        seek_row.addWidget(self.time_label)
        root.addLayout(seek_row)
        action_row = QHBoxLayout()
        self.play_button = QToolButton()
        self.play_button.setIcon(self.style().standardIcon(QStyle.StandardPixmap.SP_MediaPlay))
        self.play_button.setIconSize(QSize(18, 18))
        self.play_button.setFixedSize(36, 32)
        self.play_button.setAccessibleName("播放")
        self.play_button.setToolTip("播放/暂停")
        self.play_button.clicked.connect(self._toggle_media)
        stop_button = QToolButton()
        stop_button.setIcon(self.style().standardIcon(QStyle.StandardPixmap.SP_MediaStop))
        stop_button.setIconSize(QSize(18, 18))
        stop_button.setFixedSize(36, 32)
        stop_button.setAccessibleName("停止")
        stop_button.setToolTip("停止并回到开头")
        stop_button.clicked.connect(self._stop_media)
        back_button = QToolButton()
        back_button.setIcon(self.style().standardIcon(QStyle.StandardPixmap.SP_MediaSeekBackward))
        back_button.setIconSize(QSize(18, 18))
        back_button.setFixedSize(36, 32)
        back_button.setAccessibleName("后退 10 秒")
        back_button.setToolTip("后退 10 秒")
        back_button.clicked.connect(lambda: self._nudge_media(-10000))
        forward_button = QToolButton()
        forward_button.setIcon(self.style().standardIcon(QStyle.StandardPixmap.SP_MediaSeekForward))
        forward_button.setIconSize(QSize(18, 18))
        forward_button.setFixedSize(36, 32)
        forward_button.setAccessibleName("前进 10 秒")
        forward_button.setToolTip("前进 10 秒")
        forward_button.clicked.connect(lambda: self._nudge_media(10000))
        self.loop_checkbox = QCheckBox("单曲循环")
        self.loop_checkbox.setToolTip("播放结束后自动从头播放")
        self.loop_checkbox.toggled.connect(self._set_media_loop)
        volume_label = QLabel("音量")
        self.volume_slider = QSlider(Qt.Horizontal)
        self.volume_slider.setRange(0, 100)
        self.volume_slider.setValue(80)
        self.volume_slider.setMaximumWidth(110)
        self.volume_slider.valueChanged.connect(self._set_media_volume)
        for widget in (self.play_button, stop_button, back_button, forward_button, self.loop_checkbox):
            action_row.addWidget(widget)
        action_row.addStretch()
        action_row.addWidget(volume_label)
        action_row.addWidget(self.volume_slider)
        root.addLayout(action_row)
        self.media_message_label = QLabel()
        self.media_message_label.setWordWrap(True)
        self.media_message_label.setStyleSheet("color: #D64545; font-size: 12px;")
        self.media_message_label.hide()
        root.addWidget(self.media_message_label)
        self.media_player.positionChanged.connect(self._on_media_position)
        self.media_player.durationChanged.connect(self._on_media_duration)
        self.media_player.playbackStateChanged.connect(self._on_media_state)
        self.media_player.mediaStatusChanged.connect(self._on_media_status)
        if hasattr(self.media_player, "seekableChanged"):
            self.media_player.seekableChanged.connect(self._on_media_seekable)
        if hasattr(self.media_player, "errorOccurred"):
            self.media_player.errorOccurred.connect(self._on_media_error)
        return controls

    def _toggle_media(self) -> None:
        if self.media_player is None:
            return
        state = self.media_player.playbackState()
        playing = getattr(type(state), "PlayingState", None) or getattr(self.media_player, "PlayingState", 1)
        if state == playing:
            self.media_player.pause()
        else:
            self.media_player.play()

    def _stop_media(self) -> None:
        if self.media_player is not None:
            self.media_player.stop()
            self.media_player.setPosition(0)

    def _seek_media(self, position: int) -> None:
        if self.media_player is not None:
            self.media_player.setPosition(int(position))

    def _nudge_media(self, amount: int) -> None:
        if self.media_player is not None:
            self.media_player.setPosition(max(0, self.media_player.position() + amount))

    def _set_media_loop(self, enabled: bool) -> None:
        if self.media_player is None:
            return
        try:
            loops = type(self.media_player).Loops
            # Qt 6 exposes Infinite/Once (Qt 5 used CurrentItem in a few
            # bindings).  Prefer Infinite and retain the status-signal
            # fallback below for older or mocked players.
            target = getattr(loops, "Infinite", getattr(loops, "CurrentItem", -1)) if enabled else loops.Once
            self.media_player.setLoops(target)
        except (AttributeError, TypeError):
            pass

    def _set_media_volume(self, value: int) -> None:
        if self.audio_output is not None:
            self.audio_output.setVolume(max(0.0, min(1.0, value / 100.0)))

    def _on_media_position(self, position: int) -> None:
        if self.position_slider is not None and not self.position_slider.isSliderDown():
            self.position_slider.setValue(position)
        if self.time_label is not None and self.media_player is not None:
            self.time_label.setText(f"{_format_time(position)} / {_format_time(self.media_player.duration())}")
        # v1.1.2：每 2 秒落一次进度（关闭时 _cleanup_resources 会强制再存一次）
        if abs(position - self._last_media_save_ms) >= 2000:
            self._last_media_save_ms = position
            self._save_progress()

    def _on_media_duration(self, duration: int) -> None:
        if self.position_slider is not None:
            self.position_slider.setRange(0, max(0, duration))
        self._on_media_position(self.media_player.position() if self.media_player is not None else 0)

    def _on_media_state(self, state) -> None:
        if self.play_button is not None:
            playing = getattr(state, "name", "") == "PlayingState"
            icon = QStyle.StandardPixmap.SP_MediaPause if playing else QStyle.StandardPixmap.SP_MediaPlay
            self.play_button.setIcon(self.style().standardIcon(icon))
            self.play_button.setAccessibleName("暂停" if playing else "播放")

    def _on_media_seekable(self, seekable: bool) -> None:
        if self.position_slider is not None:
            self.position_slider.setEnabled(bool(seekable))

    def _on_media_error(self, _error=None, message: str = "") -> None:
        if self.media_message_label is None:
            return
        if not message and self.media_player is not None:
            try:
                message = self.media_player.errorString()
            except (AttributeError, RuntimeError):
                pass
        self.media_message_label.setText(
            f"无法播放此媒体：{message or '格式或编码不受当前系统支持'}"
        )
        self.media_message_label.show()

    def _on_media_status(self, status) -> None:
        if self.media_message_label is not None and getattr(status, "name", "") in {
            "LoadedMedia", "BufferedMedia", "BufferingMedia",
        }:
            self.media_message_label.clear()
            self.media_message_label.hide()
        # v1.2.1：续读位置在媒体真正加载完成后才应用
        if (self._pending_media_seek_ms > 0 and self.media_player is not None
                and getattr(status, "name", "") in {"LoadedMedia", "BufferedMedia"}):
            try:
                self.media_player.setPosition(self._pending_media_seek_ms)
            except Exception:  # pylint: disable=broad-except
                pass
            self._pending_media_seek_ms = 0
        if self.media_player is None or self.loop_checkbox is None or not self.loop_checkbox.isChecked():
            return
        if getattr(status, "name", "") == "EndOfMedia":
            self.media_player.setPosition(0)
            self.media_player.play()

    # -- generic cards/actions ---------------------------------------
    def _load_unknown_preview(self) -> None:
        self._show_unsupported_card("暂不支持预览此文件类型", "您可以点击下方按钮在系统默认应用中打开此文件。")

    def _show_error(self, message: str) -> None:
        self._show_info_card("预览失败", message, is_error=True)

    def _show_unsupported_card(self, title: str, description: str) -> None:
        self._show_info_card(title, description, is_error=False)

    def _show_info_card(self, title: str, description: str, is_error: bool = False) -> None:
        container = QWidget()
        container.setStyleSheet(f"background: {CARD};")
        outer = QVBoxLayout(container)
        outer.setAlignment(Qt.AlignCenter)
        outer.setContentsMargins(40, 60, 40, 60)
        card = QFrame()
        card.setObjectName("card")
        card.setMaximumWidth(520)
        card_layout = QVBoxLayout(card)
        card_layout.setContentsMargins(24, 20, 24, 20)
        card_layout.setSpacing(10)
        title_label = QLabel(title)
        title_label.setAlignment(Qt.AlignCenter)
        title_label.setStyleSheet(f"font-size: 15px; font-weight: 600; color: {'#D64545' if is_error else TEXT};")
        desc_label = QLabel(description)
        desc_label.setAlignment(Qt.AlignCenter)
        desc_label.setWordWrap(True)
        desc_label.setStyleSheet(f"color: {TEXT_SECONDARY}; font-size: 13px;")
        open_btn = QPushButton("在默认应用打开")
        open_btn.setObjectName("primary")
        open_btn.clicked.connect(self._on_open_default)
        card_layout.addWidget(title_label)
        card_layout.addWidget(desc_label)
        card_layout.addSpacing(6)
        card_layout.addWidget(open_btn)
        outer.addWidget(card)
        self._set_preview_widget(container)

    def _on_open_default(self) -> None:
        try:
            path = self._original_path()
            if os.name == "nt":
                os.startfile(path)  # type: ignore[attr-defined]
            else:
                subprocess.Popen(["xdg-open", path])
        except Exception as exc:  # pylint: disable=broad-except
            QMessageBox.warning(self, "打开失败", f"无法打开文件：{exc}")

    def _original_path(self) -> str:
        return self._original_file_path

    def _on_share(self) -> None:
        show_share_menu(self, self._original_path(), anchor=self.share_btn)

    def _cleanup_resources(self) -> None:
        """Release native handles and generated files exactly once."""
        with self._resource_lock:
            if self._cleaned_up:
                return
            self._cleaned_up = True
            self._office_cancel_event.set()
            pending_office_dirs = tuple(self._pending_office_dirs)
            self._pending_office_dirs.clear()
        # v1.1.2：释放媒体/PDF 句柄之前把阅读位置落库（此时 position() 仍有效）
        try:
            self._save_progress(force=True)
        except Exception:  # pylint: disable=broad-except
            pass
        # v1.2.1：停掉进度防抖定时器，并关闭长生命周期的库连接
        try:
            self._progress_save_timer.stop()
        except (RuntimeError, AttributeError):
            pass
        progress_store = self._progress_store_obj
        self._progress_store_obj = None
        if progress_store is not None:
            try:
                progress_store.close()
            except Exception:  # pylint: disable=broad-except
                pass
        if self.media_player is not None:
            try:
                self.media_player.stop()
                self.media_player.setSource(QUrl())
            except Exception:  # pylint: disable=broad-except
                pass
        if self._image_widget is not None and self._image_widget._movie is not None:
            self._image_widget._movie.stop()
        if self._pdf_view is not None:
            try:
                self._pdf_view.setDocument(None)
            except (TypeError, RuntimeError):
                pass
        if self._pdf_document is not None:
            document = self._pdf_document
            self._pdf_document = None
            try:
                document.close()
            except (AttributeError, RuntimeError):
                pass
            # On Windows QPdfDocument.close() changes its status to Null but
            # Qt keeps the native file handle until the QObject is destroyed.
            # Destroy it synchronously before removing an Office conversion.
            try:
                import shiboken6  # type: ignore
                shiboken6.delete(document)
            except (ImportError, RuntimeError):
                document.deleteLater()
        if self._temp_pdf_path and os.path.exists(self._temp_pdf_path):
            try:
                os.remove(self._temp_pdf_path)
            except OSError:
                pass
        if self._temp_pdf_dir and os.path.isdir(self._temp_pdf_dir):
            shutil.rmtree(self._temp_pdf_dir, ignore_errors=True)
        for output_dir in pending_office_dirs:
            if output_dir != self._temp_pdf_dir:
                shutil.rmtree(output_dir, ignore_errors=True)

    def done(self, result: int) -> None:
        """QDialog.accept/reject bypass closeEvent on some Qt platforms."""
        self._cleanup_resources()
        super().done(result)

    def closeEvent(self, event) -> None:  # noqa: N802
        self._cleanup_resources()
        super().closeEvent(event)
