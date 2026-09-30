"""展示层工具测试：课程名清洗（剥离内嵌选课代码）。

用户反馈课程列表里课程名前的选课代码没有阅读价值。清洗只在展示层做，
数据库与本地目录仍用 Canvas 原名（路径语义必须与引擎一致，见 §12）。
"""
from fudan_sync.utils import clean_course_name


class TestCleanCourseName:
    def test_trailing_code(self):
        assert clean_course_name("数据处理与数据库 DATA130012.01") == "数据处理与数据库"

    def test_leading_code(self):
        assert clean_course_name("CHEM10003.03 普通化学A（上）") == "普通化学A（上）"

    def test_parenthesized_code_removed_with_parens(self):
        assert clean_course_name("机器学习导论（DATA130012.01）") == "机器学习导论"

    def test_name_without_code_untouched(self):
        assert clean_course_name("软件工程") == "软件工程"

    def test_english_words_and_numbers_kept(self):
        assert clean_course_name("Linear Algebra 2") == "Linear Algebra 2"
        assert clean_course_name("C++ 程序设计") == "C++ 程序设计"

    def test_code_only_falls_back_to_raw(self):
        # 全是代码时不能返回空字符串
        assert clean_course_name("DATA130012.01") == "DATA130012.01"

    def test_multiple_codes(self):
        assert clean_course_name("复变函数 MATH120011.01 MATH120011.02") == "复变函数"

    def test_empty(self):
        assert clean_course_name("") == ""

    def test_assembly_language_code(self):
        name = clean_course_name("汇编语言 CSCI130018.01")
        assert name == "汇编语言"
