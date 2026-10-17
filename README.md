# 复小学

复小学用于同步复旦大学 eLearning（Canvas LMS）课程资料，并在 Windows 与 Android 端提供统一的本地资料库。

## 功能

- 账号登录、定时增量同步与后台同步
- 课程、课程代码、学期和文件名搜索
- 自动下载全部资料，或按课程与文件手动选择同步范围
- PDF、Office、ODF、图片、文本、HTML、音视频应用内预览
- 文档缩放、连续阅读、页码跳转、阅读进度与续读
- 作业截止日期、任务正文、提交状态和教师评论查看
- 全文搜索、最近变更、截止日期导出、文件分享与存储管理
- 断点续传、失败重试、限流处理、原子落盘和远端删除保护

两端会持续刷新完整课程与文件清单。课程选择“自动下载全部资料”时，后续新增资料会自动下载；选择手动模式时，只下载已勾选文件，新文件仅加入清单。

## 版本

- Windows：**1.3.5**
- Android：**1.3.5**（versionCode 24）

详细变更见 [CHANGELOG](CHANGELOG.md)。

## 使用要求

### Windows

- Windows 10 或更高版本
- Python 3.10+（从源码运行时）
- 完整的 PySide6 与 PySide6-Addons

### Android

- Android 8.0 或更高版本
- 正式安装包见 [GitHub Releases](https://github.com/lsx626/fuxiaoxue/releases)

## Windows 使用

从源码运行：

```bash
cd elearning-sync
pip install -r requirements.txt
python gui.py
```

首次启动后使用 UIS 账号登录。登录成功后，软件会按设置定时同步课程；同步目录、同步间隔、下载并发数和内容过滤规则均可在设置中调整。

构建桌面程序：

```bash
cd elearning-sync
python -m PyInstaller 复小学.spec
```

## Android 构建

```bash
cd android-app
./gradlew assembleRelease
```

APK 输出于 `app/build/outputs/apk/release/app-release.apk`。

## 登录与版权

软件支持 UIS 账号密码、API Token、浏览器登录和 Cookie 登录。Windows 端的账号密码由系统凭据管理器保护，Cookie 文件使用系统钥匙串中的密钥进行 AES-GCM 加密；Android 端使用 Android Keystore 保护密码。课程资料与同步状态保存在用户指定的本地目录。

软件只同步当前账号有权访问的课程内容。课程课件、作业附件及其中的文字、图片和其他文件，版权归对应授课教师、教学团队或出版方所有。请在获得授权的范围内使用、保存和分享，不得将资料用于未经许可的传播。

## 项目结构

```text
elearning-sync/   Windows 桌面端、同步引擎和命令行工具
android-app/      Android 应用
CHANGELOG.md      版本变更记录
```

## 许可证

MIT
