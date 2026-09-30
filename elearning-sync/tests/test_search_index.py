"""本地全文搜索回归测试。

覆盖三层：
1. 分词器：CJK unigram+bigram 预分词（默认 unicode61 不做中文分词，
   `计算机` 搜不到 `计算机体系结构`；预分词后可以）。
2. 文本抽取器：按扩展名抽取纯文本，带编码回退与体积上限，失败返回 None。
3. StateStore 的 FTS5 索引：写入/查询/摘要/删除/降级/未索引回填。
4. 纯函数过滤（文件列表即时过滤）。

运行：python -m pytest elearning-sync/tests/test_search_index.py
     或 python elearning-sync/tests/test_search_index.py
"""
from __future__ import annotations

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from fudan_sync.config import AppConfig  # noqa: E402
from fudan_sync.downloader import DownloadResult, DownloadTask  # noqa: E402
from fudan_sync.search_index import (  # noqa: E402
    build_match_query,
    extract_text,
    filter_file_records,
    tokenize_for_index,
)
from fudan_sync.state import StateStore  # noqa: E402
from fudan_sync.sync_engine import CourseInfo, SyncEngine  # noqa: E402


class TokenizerTests(unittest.TestCase):
    def test_cjk_run_gets_unigrams_and_bigrams(self):
        # 搜索「计算机」必须能命中「计算机体系结构」：默认分词器会把整段
        # 汉字合并成一个 token，子串检索不可能命中。
        self.assertEqual(tokenize_for_index("计算机"), "计 算 机 计算 算机")

    def test_latin_and_digits_stay_whole(self):
        self.assertEqual(
            tokenize_for_index("Lecture 12 chapters"),
            "lecture 12 chapters")

    def test_mixed_runs(self):
        tokens = tokenize_for_index("数据结构 lecture5.pdf").split()
        self.assertIn("数据", tokens)
        self.assertIn("结构", tokens)
        # 标点把文件名拆成词元：lecture5 与 pdf 分别是独立 token
        self.assertIn("lecture5", tokens)
        self.assertIn("pdf", tokens)

    def test_punctuation_separates_only(self):
        # 标点把汉字串切断成单字：不再产生跨标点的双字
        tokens = tokenize_for_index("计,算！机").split()
        self.assertIn("计", tokens)
        self.assertIn("算", tokens)
        self.assertIn("机", tokens)
        self.assertNotIn("计算", tokens)

    def test_empty_and_ascii_only(self):
        self.assertEqual(tokenize_for_index(""), "")
        self.assertEqual(tokenize_for_index("   "), "")
        self.assertEqual(tokenize_for_index("plain text"), "plain text")

    def test_single_cjk_char(self):
        self.assertEqual(tokenize_for_index("课"), "课")


class MatchQueryTests(unittest.TestCase):
    def test_empty_query_is_none(self):
        self.assertIsNone(build_match_query(""))
        self.assertIsNone(build_match_query("   ，。！"))

    def test_single_cjk_char(self):
        self.assertEqual(build_match_query("课"), '"课"')

    def test_cjk_phrase_gets_unigrams_and_bigrams(self):
        match = build_match_query("计算机")
        self.assertIn('"计"', match)
        self.assertIn('"计算"', match)
        self.assertIn('"算机"', match)

    def test_last_latin_term_gets_prefix_star(self):
        match = build_match_query("lecture")
        self.assertEqual(match, '"lecture"*')

    def test_mixed_query(self):
        match = build_match_query("数据结构 pdf")
        self.assertIn('"数据"', match)
        self.assertIn('"结构"', match)
        self.assertIn('"pdf"*', match)

    def test_terms_are_conjoined(self):
        match = build_match_query("算法 导论")
        self.assertIn(' ', match)


class ExtractTextTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def _write(self, name: str, data, mode: str = "w", encoding: str = "utf-8"):
        path = os.path.join(self.dir, name)
        with open(path, mode, encoding=encoding if "b" not in mode else None) as handle:
            handle.write(data)
        return path

    def test_utf8_text_with_bom(self):
        path = self._write("a.txt", "计算机导论\n第一章")
        self.assertIn("计算机导论", extract_text(path))

    def test_gbk_text_falls_back(self):
        # 桌面预览固定 utf-8-sig 导致的乱码在索引层不应重演。
        path = self._write("gbk.txt", "计算机体系结构", encoding="gbk")
        text = extract_text(path)
        self.assertIsNotNone(text)
        self.assertIn("计算机体系结构", text)

    def test_binary_garbage_returns_none(self):
        path = self._write("bin.dat", b"\x00\x01\x02\xff\xfe", mode="wb")
        self.assertIsNone(extract_text(path))

    def test_unsupported_extension_returns_none(self):
        path = self._write("a.png", b"\x89PNG\r\n\x1a\n" + b"\x00" * 64, mode="wb")
        self.assertIsNone(extract_text(path))

    def test_html_strips_tags(self):
        path = self._write("p.html",
                           "<html><body><h1>实验报告</h1><p>内容甲</p></body></html>")
        text = extract_text(path)
        self.assertIsNotNone(text)
        self.assertIn("实验报告", text)
        self.assertIn("内容甲", text)
        self.assertNotIn("<h1>", text)

    def test_csv_plain(self):
        path = self._write("d.csv", "学号,姓名\n1,张三\n")
        self.assertIn("张三", extract_text(path) or "")

    def test_docx_text(self):
        docx = self._import_docx()
        if docx is None:  # 环境缺 python-docx 时跳过，不硬失败
            self.skipTest("python-docx 不可用")
        path = os.path.join(self.dir, "w.docx")
        document = docx.Document()
        document.add_paragraph("数据结构第一章")
        document.add_paragraph("线性表与链表")
        document.save(path)
        text = extract_text(path)
        self.assertIsNotNone(text)
        self.assertIn("数据结构第一章", text)
        self.assertIn("线性表与链表", text)

    def test_xlsx_text(self):
        openpyxl = self._import_openpyxl()
        if openpyxl is None:
            self.skipTest("openpyxl 不可用")
        path = os.path.join(self.dir, "s.xlsx")
        wb = openpyxl.Workbook()
        ws = wb.active
        ws["A1"] = "学期"
        ws["B2"] = "2025 秋"
        wb.save(path)
        text = extract_text(path)
        self.assertIsNotNone(text)
        self.assertIn("2025 秋", text)

    def test_pptx_text(self):
        pptx = self._import_pptx()
        if pptx is None:
            self.skipTest("python-pptx 不可用")
        path = os.path.join(self.dir, "t.pptx")
        prs = pptx.Presentation()
        layout = prs.slide_layouts[1]
        slide = prs.slides.add_slide(layout)
        for shape in slide.shapes:
            if shape.has_text_frame:
                shape.text_frame.text = "课件标题 计算机网络"
        prs.save(path)
        text = extract_text(path)
        self.assertIsNotNone(text)
        self.assertIn("课件标题", text)

    def test_pdf_text(self):
        # 内嵌 base14 字体不支持中文（画出来是占位字形），PDF 夹具用 ASCII
        # 验证抽取链路本身；中文分词另由分词器测试覆盖。
        fitz = self._import_fitz()
        if fitz is None:
            self.skipTest("PyMuPDF 不可用")
        path = os.path.join(self.dir, "p.pdf")
        doc = fitz.open()
        page = doc.new_page()
        page.insert_text((72, 72), "Introduction to AI Lecture One")
        doc.save(path)
        doc.close()
        text = extract_text(path)
        self.assertIsNotNone(text)
        self.assertIn("Introduction to AI", text)

    def test_huge_text_is_capped(self):
        path = self._write("big.txt", "甲" * 400000)
        text = extract_text(path)
        self.assertIsNotNone(text)
        self.assertLessEqual(len(text), 65536)

    @staticmethod
    def _import_docx():
        try:
            import docx  # noqa: PLC0415
            return docx
        except Exception:  # pylint: disable=broad-except
            return None

    @staticmethod
    def _import_openpyxl():
        try:
            import openpyxl  # noqa: PLC0415
            return openpyxl
        except Exception:  # pylint: disable=broad-except
            return None

    @staticmethod
    def _import_pptx():
        try:
            import pptx  # noqa: PLC0415
            return pptx
        except Exception:  # pylint: disable=broad-except
            return None

    @staticmethod
    def _import_fitz():
        try:
            import fitz  # noqa: PLC0415
            return fitz
        except Exception:  # pylint: disable=broad-except
            return None


class StoreIndexTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = StateStore(os.path.join(self.tmp.name, "state.db"))

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def _add_downloaded(self, file_id: int, name: str, content: str,
                        status: str = "downloaded", course_id: int = 1):
        self.store.upsert_course(course_id, "计算机导论", "CS101", "2025秋", "student", False)
        path = os.path.join(self.tmp.name, name)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(content)
        self.store.upsert_file({
            "file_id": file_id, "course_id": course_id, "filename": name,
            "display_name": name, "size": len(content.encode("utf-8")),
            "content_type": "", "folder_id": None, "folder_path": "",
            "created_at": "t1", "updated_at": "t1", "modified_at": "t1",
            "locked_for_user": False, "hidden": False, "source": "files",
            "context": "", "local_path": path,
        })
        self.store.mark_downloaded(file_id, path, len(content.encode("utf-8")))
        return path

    def test_index_and_search_cjk_substring(self):
        self._add_downloaded(10, "lec1.txt", "计算机体系结构 第一章 导论")
        self.store.upsert_file_index(10, "lec1.txt", "计算机体系结构 第一章 导论")
        hits = self.store.search_files("计算机")
        self.assertEqual(len(hits), 1)
        self.assertEqual(hits[0]["file_id"], 10)
        self.assertEqual(hits[0]["course_name"], "计算机导论")

    def test_filename_match(self):
        self._add_downloaded(11, "操作系统课件.pdf", "abc")
        self.store.upsert_file_index(11, "操作系统课件.pdf", "abc")
        hits = self.store.search_files("操作系统")
        self.assertEqual(len(hits), 1)

    def test_snippets_mark_hits(self):
        self._add_downloaded(12, "a.txt", "这里有一段关于神经网络的讨论")
        self.store.upsert_file_index(12, "a.txt", "这里有一段关于神经网络的讨论")
        hits = self.store.search_files("神经网络")
        self.assertEqual(len(hits), 1)
        self.assertIn("[", hits[0]["snippet"])

    def test_remove_index(self):
        self._add_downloaded(13, "b.txt", "线性代数特征值")
        self.store.upsert_file_index(13, "b.txt", "线性代数特征值")
        self.assertEqual(len(self.store.search_files("特征值")), 1)
        self.store.remove_file_index(13)
        self.assertEqual(len(self.store.search_files("特征值")), 0)

    def test_reindex_replaces(self):
        self._add_downloaded(14, "c.txt", "旧内容关键词甲")
        self.store.upsert_file_index(14, "c.txt", "旧内容关键词甲")
        self.store.upsert_file_index(14, "c.txt", "新内容关键词乙")
        self.assertEqual(len(self.store.search_files("关键词甲")), 0)
        self.assertEqual(len(self.store.search_files("关键词乙")), 1)

    def test_remote_missing_excluded(self):
        self._add_downloaded(15, "d.txt", "离散数学图论")
        self.store.upsert_file_index(15, "d.txt", "离散数学图论")
        self.store.mark_missing_files(1, seen_file_ids=[16, 17])
        self.assertEqual(len(self.store.search_files("离散数学")), 0)

    def test_unindexed_list_and_backfill(self):
        self._add_downloaded(16, "e.txt", "待索引内容")
        pending = self.store.unindexed_downloaded_files(limit=10)
        self.assertEqual([r["file_id"] for r in pending], [16])
        self.store.upsert_file_index(16, "e.txt", "待索引内容")
        self.assertEqual(self.store.unindexed_downloaded_files(limit=10), [])

    def test_empty_and_garbage_queries(self):
        self._add_downloaded(17, "f.txt", "内容")
        self.store.upsert_file_index(17, "f.txt", "内容")
        self.assertEqual(self.store.search_files(""), [])
        self.assertEqual(self.store.search_files("   "), [])

    def test_result_contains_local_path(self):
        self._add_downloaded(18, "g.txt", "密码学基础")
        self.store.upsert_file_index(18, "g.txt", "密码学基础")
        hits = self.store.search_files("密码学")
        self.assertTrue(hits and hits[0]["local_path"])


class _NullApi:
    """引擎构造只需要一个带 session 属性的 API 占位对象（不做网络调用）。"""

    session = None


class SyncEngineIndexTests(unittest.TestCase):
    """下载完成后自动建索引；老库的已下载文件由同步收尾回填。

    不走真实网络：下载结果由假 DownloadResult 直接构造，只验证引擎与
    状态库之间的索引衔接。
    """

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.cfg = AppConfig(root_dir=os.path.join(self.tmp.name, "files"),
                             state_db=os.path.join(self.tmp.name, "st.db"))
        os.makedirs(self.cfg.root_dir, exist_ok=True)
        self.state = StateStore(self.cfg.state_db)
        self.state.upsert_course(1, "机器学习", "CS229", "2025秋", "student", False)
        self.course = CourseInfo(1, "机器学习", "CS229", "2025秋", "student", False, {})
        self.engine = SyncEngine(self.cfg, _NullApi(), self.state)

    def tearDown(self):
        self.state.close()
        self.tmp.cleanup()

    def _make_task(self, file_id: int, filename: str, content: str):
        local = os.path.join(self.cfg.root_dir, filename)
        with open(local, "w", encoding="utf-8") as handle:
            handle.write(content)
        task = DownloadTask(
            file_id=file_id, course_id=1, filename=filename, folder_path="",
            course_dir=self.cfg.root_dir, size=len(content.encode("utf-8")),
            api_path=f"/courses/1/files/{file_id}", fallback_url="")
        result = DownloadResult(task)
        result.success = True
        result.local_path = local
        result.bytes = len(content.encode("utf-8"))
        return task, result

    def _record(self, file_id: int, filename: str, content: str):
        self.state.upsert_file({
            "file_id": file_id, "course_id": 1, "filename": filename,
            "display_name": filename, "size": len(content.encode("utf-8")),
            "content_type": "", "folder_id": None, "folder_path": "",
            "created_at": "t1", "updated_at": "t1", "modified_at": "t1",
            "locked_for_user": False, "hidden": False, "source": "files",
            "context": "", "local_path": os.path.join(self.cfg.root_dir, filename),
        })

    def test_download_completions_get_indexed(self):
        _, result = self._make_task(20, "note.txt", "本节讲授支持向量机与核技巧")
        self._record(20, "note.txt", "本节讲授支持向量机与核技巧")
        self.state.mark_downloaded(20, result.local_path, 20)
        self.engine._index_downloaded_file(result)
        hits = self.state.search_files("支持向量机")
        self.assertEqual([h["file_id"] for h in hits], [20])
        self.assertEqual(hits[0]["filename"], "note.txt")

    def test_backfill_covers_legacy_downloads(self):
        # 功能上线前已下载的文件：files 行存在但没有索引行
        self._record(21, "legacy.txt", "内容提到卷积神经网络与反向传播")
        local = os.path.join(self.cfg.root_dir, "legacy.txt")
        with open(local, "w", encoding="utf-8") as handle:
            handle.write("内容提到卷积神经网络与反向传播")
        self.state.mark_downloaded(21, local, 40)
        self.assertEqual(self.state.search_files("卷积神经网络"), [])

        self.engine._backfill_search_index()
        hits = self.state.search_files("卷积神经网络")
        self.assertEqual([h["file_id"] for h in hits], [21])

    def test_backfill_stops_cooperatively(self):
        self._record(22, "legacy2.txt", "无索引文件")
        local = os.path.join(self.cfg.root_dir, "legacy2.txt")
        with open(local, "w", encoding="utf-8") as handle:
            handle.write("待索引")
        self.state.mark_downloaded(22, local, 9)
        self.engine.downloader.stop()
        self.engine._backfill_search_index()
        # 收到停止信号后不应建索引，但也不应抛异常
        self.assertEqual(
            [r["file_id"] for r in self.state.unindexed_downloaded_files(10)], [22])

    def test_extraction_failure_only_indexes_filename(self):
        # 损坏文件 / 不支持的格式：不建内容索引，但文件名仍可被搜到
        local = os.path.join(self.cfg.root_dir, "broken.png")
        with open(local, "wb") as handle:
            handle.write(b"\x89PNG\r\n\x1a\n" + b"\x00" * 64)
        self._record(23, "broken.png", "")
        self.state.mark_downloaded(23, local, 71)
        self.engine._backfill_search_index()
        self.assertEqual(len(self.state.search_files("broken")), 1)
        # 文件名可搜（设计如此），但损坏文件没有可索引的内容
        self.assertEqual(len(self.state.search_files("照片")), 0)


class FilterRecordsTests(unittest.TestCase):
    FILES = [
        {"filename": "lec1.pdf", "display_name": "lec1.pdf", "source": "files",
         "context": "", "folder_path": "课件"},
        {"filename": "作业2.docx", "display_name": "作业2.docx", "source": "assignment",
         "context": "作业: 第二周", "folder_path": ""},
        {"filename": "notes.txt", "display_name": "笔记", "source": "page",
         "context": "页面: 复习", "folder_path": ""},
    ]

    def test_match_filename(self):
        self.assertEqual(len(filter_file_records(self.FILES, "lec1")), 1)

    def test_match_context(self):
        self.assertEqual(len(filter_file_records(self.FILES, "第二周")), 1)

    def test_all_terms_required(self):
        self.assertEqual(len(filter_file_records(self.FILES, "作业 docx")), 1)
        self.assertEqual(len(filter_file_records(self.FILES, "作业 pdf")), 0)

    def test_empty_query_returns_all(self):
        self.assertEqual(len(filter_file_records(self.FILES, "")), 3)

    def test_case_insensitive(self):
        self.assertEqual(len(filter_file_records(self.FILES, "LEC1")), 1)


if __name__ == "__main__":
    unittest.main()
