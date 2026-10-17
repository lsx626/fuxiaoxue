"""桌面端课程/文件下载选择的持久化与搜索回归。"""

from fudan_sync.state import StateStore


def _file(file_id: int, course_id: int, name: str) -> dict:
    return {
        "file_id": file_id,
        "course_id": course_id,
        "filename": name,
        "display_name": name,
        "size": 10,
        "content_type": "application/octet-stream",
        "folder_id": None,
        "folder_path": "",
        "created_at": "",
        "updated_at": "",
        "modified_at": "",
        "locked_for_user": False,
        "hidden": False,
        "source": "files",
        "context": "",
        "local_path": "",
        "extra": "",
    }


def test_course_and_file_download_selection_migrates_and_searches(tmp_path):
    store = StateStore(str(tmp_path / "state.db"))
    store.upsert_course(1, "程序设计", "CS101", "秋", "student", False)
    store.upsert_course(2, "普通化学", "CHEM101", "秋", "student", False)
    store.upsert_file(_file(11, 1, "第一次作业.pdf"))
    store.upsert_file(_file(12, 2, "实验讲义.pdf"))

    assert all(course["selected"] == 1 for course in store.list_courses())
    assert store.course_ids_matching_file_name("作业") == {1}

    store.set_course_selected(1, False)
    store.set_file_selected(11, True)
    assert store.get_file(11)["selected"] == 1
    assert next(course for course in store.list_courses() if course["id"] == 1)["selected"] == 0

    store.set_course_files_selected(1, True)
    assert store.get_file(11)["selected"] == 1
    store.set_course_files_selected(1, False)
    assert store.get_file(11)["selected"] == 0
    store.close()
