# 复小学 · 复旦大学 eLearning 课程同步

自动同步复旦大学 eLearning（Canvas LMS）平台的课程文件到本地，支持 PC 与 Android 双端。

## 功能特性

- **自动登录**：首次输入 UIS 账号密码后，自动登录、自动同步，密码安全存储于系统密钥库
- **定时同步**：默认每 15 分钟增量同步，大幅减少流量消耗
- **双端支持**：Windows 桌面程序（安装包）+ Android 应用（APK，支持 Android 8~16）
- **文件预览**：双端均应用内预览 PDF / Word / Excel / PPT / ODF / 图片 / 文本 / 音视频，不跳转第三方应用；Android 端 PDF 与 Office 为纵向连续滚动（下拉式翻页）+ 双指缩放
- **全文搜索**（双端 v1.1.0 起）：跨课程搜索全部已下载文件的文件名与正文内容，中文直接可用（桌面 Ctrl+K 全局搜索、Ctrl+F 课程内过滤；Android 顶栏搜索入口，结果直达预览）
- **作业截止日期**（双端 v1.1.0 起）：同步时采集各课程作业截止时间；桌面端「截止日期」对话框按时间排序并可导出 .ics 导入系统日历，Android 课程列表顶部显示「最近截止」
- **音视频播放**：PC 端内置音视频播放器，支持拖动进度、音量控制和单曲循环
- **文件分享**：复制文件到剪贴板、另存副本、复制路径或在文件夹中定位
- **存储管理**：按学期、课程批量管理（删除）文件
- **变更摘要**（双端 v1.1.0 起）：同步通知按课程列出新增/更新/删除的文件名，桌面端「最近变更」可回看最近 100 条；在此之前通知只有「新增 N 个文件」
- **阅读进度**（双端 v1.2.0 起）：预览自动记录页码/播放位置，重开文件续读到上次位置；文件列表显示「未读 / 第 12/40 页 · 30% / 播放进度」，并支持「继续阅读」直达上次文件；预览内可翻同课程的上一个/下一个文件
- **未读优先**（双端 v1.2.0 起）：课程文件列表把没读过的文件排在前面，已读按最近阅读时间降序
- **通知提醒**：下载新文件后弹 Windows 通知 / Android 通知
- **内容过滤**：自动过滤课程封面图、安装包（exe/msi 等）、空课程站点
- **后台同步**：PC 支持开机自启，Android 支持后台定时同步

## 版本

当前版本：桌面端 **1.2.2** / Android **1.2.2**

## 目录结构

```
├── elearning-sync/          # PC 端（Python + PySide6）
│   ├── gui.py               # GUI 入口（桌面端“复小学”）
│   ├── sync.py              # CLI 入口
│   ├── fudan_sync/          # 核心同步引擎
│   │   ├── password_login.py    # 新版 UIS 登录（RSA 加密）
│   │   ├── auth.py              # 认证构建
│   │   ├── canvas_api.py        # Canvas REST API
│   │   ├── sync_engine.py       # 同步引擎
│   │   ├── state.py             # 本地状态库
│   │   └── gui/                 # GUI 界面
│   ├── installer/           # Inno Setup 安装脚本
│   └── requirements.txt
│
└── android-app/             # Android 端（Kotlin + Jetpack Compose）
    ├── app/src/main/java/edu/fudan/elearning/sync/
    │   ├── auth/            # UIS 登录（RSA 加密）
    │   ├── network/         # Canvas API 客户端
    │   ├── data/            # 数据模型 + SQLite
    │   ├── sync/            # 同步引擎 + 下载器
    │   ├── worker/          # WorkManager 后台同步 + 通知
    │   ├── util/            # 安全存储 / 文件工具
    │   └── ui/              # Compose 界面
    └── app/build.gradle.kts
```

## 登录方式

复旦大学 eLearning 已切换至新版 UIS（id.fudan.edu.cn）。本工具实现了完整的认证流程：

1. 访问登录页获取上下文（lck + entityId）
2. 查询认证方式获取 authChainCode
3. 获取 RSA 公钥
4. RSA PKCS1_v1_5 加密密码
5. 提交登录获取 loginToken
6. 完成 SSO 回跳，获得会话 Cookie

## 构建

### PC 端

```bash
cd elearning-sync
pip install -r requirements.txt
python gui.py
```

打包前应确认安装的是同一版本的完整 `PySide6` / `PySide6-Addons`，然后使用项目 spec，
以便收集 QtMultimedia、QtPdf 和媒体后端：

```bash
pip install pyinstaller
python -m PyInstaller 复小学.spec
```

安装包：Inno Setup 编译 `installer/setup.iss`

### Android 端

```bash
cd android-app
./gradlew assembleRelease
```

APK 输出：`app/build/outputs/apk/release/app-release.apk`

## 下载

见 [Releases](https://github.com/lsx626/fuxiaoxue/releases)

## License

MIT
