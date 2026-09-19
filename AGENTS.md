# 复小学项目开发与交接规范

> 本文件是仓库级开发说明，也是后续 Codex/开发者接手本项目时的首要入口。
> 它同时记录当前实现、目标状态、不可破坏的行为约束、验证方法和已知缺口。
> 初始审计基线：2026-09-18，版本 `1.0.4`，提交 `8dc75bd9f92344bc0305c9a3ae90098e0dfff831`；其后的首个发布目标为 `1.0.5`。
> 代码继续变化时，应在同一次改动中同步更新本文件；不要把历史快照当成永远正确的事实。

## 1. 新会话接手顺序

新 Codex 或新开发者开始工作时，按以下顺序建立上下文：

1. 完整阅读本文件，再读根目录 `README.md`、相关子项目 README 和本次任务涉及的源码。
2. 执行 `git status --short`、`git log -1 --oneline`、`git remote -v`，确认分支、工作区和远端；已有未提交改动一律视为用户工作，不得覆盖、回滚或清理。
3. 读取 `elearning-sync/VERSION` 和 `android-app/app/build.gradle.kts`，确认实际版本。源码与 README 冲突时，以构建文件和实现为准，并修正文档。
4. 根据改动范围先运行最小测试，修改后再运行完整相关测试。不要用“能启动”替代回归测试。
5. 不读取、不展示、不提交任何真实凭据、Cookie、课程资料、数据库或签名密钥。
6. 发现现状与本文件不符时，先以源码和测试验证，再在同一变更中更新本文件。

当前 GitHub 远端为 `https://github.com/lsx626/fuxiaoxue.git`，主分支为 `main`。只有在用户明确要求提交或上传时才提交、推送；推送前必须再次检查差异和敏感文件。

项目所有者已明确提出长期交付要求：持续完成整项目检查、修复、功能补齐和发布验收，并将成果上传到上述 GitHub 仓库。`v1.0.5` 与 `v1.0.6` 是已发布的阶段版本。`v1.0.6` 修复了桌面端下载鉴权导致的同步失败、桌面设置页布局与用户名截断问题，并补齐了 Android 应用内预览（PDF/图片/文本/音视频/Office 结构化降级）与 Android 界面重绘；但不代表最初的全部双端需求已经完成：Android 分页/限流/可靠下载（`.part`、续传、原子替换）等 P0 缺口必须继续如实保留。每次上传前都要复核目标分支、敏感文件和产物，上传后反馈 commit SHA、标签和 Release 地址。v1.0.7（仅 Android）实装了文件分享（修复 ApplicationContext 启动崩溃、修正 OOXML MIME）、Office 六格式（doc/docx/ppt/pptx/xls/xlsx）应用内逐页渲染（POI 解析 + Canvas 绘制）、PDF/Office 纵向连续滚动与双指缩放；Office 图表/SmartArt/OLE 等复杂元素仍只做限制说明，不做高保真还原。`v1.0.8`（仅 Android）在 v1.0.7 基础上补齐 Word 的「完整页面」：`.doc`/`.docx` 现在提取内嵌图片、表格（可跨页切分）与逐段字符格式（字号/颜色/加粗/斜体/下划线），并修正 `.docx` 图片尺寸单位（`XWPFPicture.getWidth()/getDepth()` 返回磅而非 EMU，旧换算有误）；六格式均为应用内逐页完整渲染，而非纯文字提取。
`v1.0.9`（仅 Android）修复预览体验五件事：一是大 PPT/PPTX 解析失败——POI 的单记录 100 MiB 安全上限（`IOUtils`）在含超大内嵌记录的文档上被触发，现按设备内存自适应放宽上限并给出内存不足的友好说明（不再暴露原始异常串），大文档自动降低渲染分辨率、内嵌大图入库前降采样、页位图缓存按堆大小预算；二是 PowerPoint 形状保真——自动形状（含无文字装饰形状、填充/描边/旋转）、连接线箭头、组合形状递归平移现在会渲染，图表/SmartArt/OLE/视频改为应用内占位卡加限制说明（不做高保真还原）；三是系统返回键——预览与文件列表层均接 `BackHandler`，且课程选中状态上提到 `AppViewModel`，预览返回后仍在原文件列表；四是预览顶栏文件名乱码——顶栏改用列表显示名（Canvas `display_name`），`sanitize` 落盘前做安全百分号解码；五是补「关于」页、预览页跳页/回到页首与失败时的「用其他应用打开」入口。可靠性缺口（Canvas 分页/限流/重试、`.part` 续传与原子落盘）仍保留，列入 v1.0.10。

`v1.0.10` 补齐上一版留下的可靠性缺口，并同步修桌面端 P1 缺陷（桌面端版本号随之升到 `1.0.7`，但**本轮未重新打包安装器**，见第 22 节流程）：

- Android `CanvasApi`：Link 分页 + 串行低频 + 429/`Retry-After` + 剩余额度减速 + 指数退避，失败抛 `ApiException` 子类，绝不再用「空列表」冒充成功（`CanvasApiTest`）。
- Android 可靠下载：`.part` + Range 续传 + 长度校验 + 原子改名 + 同名避让 + 退避重试 + 登录页嗅探（`DownloadPlanTest`）。
- Android 同步语义：课程/文件列表失败显式报错并计入 `failedCourses`，只有列表完整成功才标记 `remote_missing`，且只改状态不删本地文件；手动同步与后台 Worker 用 `SyncGate` 互斥；退出登录会取消后台任务（`SyncPolicyTest`）。
- Android 增量与迁移：增量加入 `updated_at` 与本地存在性判定；schema v2 非破坏性迁移（只加列，`onDowngrade` 不动数据）。
- Android UI：同步失败横幅（说明原因 + 重试/关闭）、顶栏「全量同步/刷新列表」、失败文件「重试」、设置页显示上次同步结果、列表缓存改为按数据版本刷新。
- Android 会话：同时接受 `_normandy_session` 与 `_canvas_session`（`ApiClient.sessionCookieName` 贯穿登录与请求）。
- Android 抓取能力对齐：`sync/CourseCrawler.kt` 按桌面端顺序抓取「文件主列表 → 目录树 → 模块 → 页面 → 作业 → 公告 → 大纲」，下载时按 Canvas 目录重建本地子目录、引用型文件自动补元数据、跨来源按 `file_id` 去重并保留首次发现的权威路径；远端目录名经 `DownloadPlan.safeRelativeDir` 逐组件清洗 + `isInside` 包含性校验，杜绝目录穿越。**删除闸门不变**：只有文件主列表完整成功（`filesListedOk`）才允许判定远端删除，模块/页面等来源失败只减少额外发现。
- 桌面端：`StateStore` 写事务异常回滚；CLI `login --method cookie` 回写 `auth.method`；认证中间页错误文案不再携带响应正文（只留 HTTP 状态与长度）；退出时改为等待同步线程结束（不再强杀 QThread）；排除扩展名统一归一化为 `.ext`；版本入口（`VERSION`/`__init__`/`setup.iss`）与 README 对齐。

`v1.0.11`（仅 Android，versionCode 12）针对用户反馈的四类问题做修复，桌面端**无代码改动**（沿用 v1.0.10 里发布的 `1.0.7` 安装包）：

- **同步 404（根因，真机实测）**：Canvas 的文件下载 URL 带 verifier 且会过期，大课程同步到后半程时最早抓到的 URL 已失效。现在下载遇 404/403 会重新请求 `/courses/:id/files/:id` 换取新签名 URL 并重试一次（`SyncEngine.downloadWithFreshUrlIfNeeded`）。
- **同步 404（未启用来源）**：课程关闭「页面/作业/公告」等标签页时 Canvas 直接返回 404。这类 404 现在**静默处理并记入 `Prefs.disabledSources`**，后续同步直接跳过，既不刷错误提示也省请求。
- **同步速度**：文件内容下载改为 **3 路并发**（`DOWNLOAD_CONCURRENCY`），元数据请求仍严格串行以遵守限流约束；叠加「跳过已确认未启用的来源」，每轮少发一批无谓请求。
- **字符显示异常**：新增 `office/TextSanitizer.kt`，处理 PPT 软换行 `\u000B`、OOXML 字面转义 `_x000B_`（**7 个字符**，早期实现按 8 个会吃掉下一个字）、控制字符，保留 emoji 代理对；应用于 PPT/Word/Excel 文本与文本预览；文本预览在 UTF-8 出现替换字符时回退 **GB18030**。
- **PPT 缺件**：① 之前完全没渲染**母版/版式里的非占位装饰图形**（校徽、色带、装饰线），现已渲染（跳过占位符避免重影）；② **渐变填充的形状会整块消失**，现在用加权平均色兜底；③ 主题色经 `DrawPaint.applyColorTransform` 应用 tint/shade。

`v1.0.12`（Android 1.0.12 / 桌面 1.0.8）收尾一批 P2/P1 缺口：

- **桌面页面归档稳定命名**（原 P2「页面归档改为稳定覆盖/版本化」）：`SyncEngine._archive_pages` 不再每轮调用 `unique_path`，同一页面固定写同一个文件名，正文未变（用 `<!-- fxx-body-sha1:… -->` 标记比对）就不重写，正文变了用「临时文件 + `os.replace`」原子覆盖；同一轮内不同页面重名才追加 `(n)`，并顺带清理历史遗留的 `页面 - 标题 (n).html` 副本。回归见 `tests/test_page_archive_stability.py`。
- **桌面课程目录定位复用引擎规则**（原 P1）：`MainWindow._course_local_dir` 现在对课程名与课程代码都调用 `sanitize_path_component`，与 `SyncEngine.course_local_dir` 完全一致；此前含非法字符的课程名会让「打开文件夹」指向不存在的路径。
- **Android 权限与 FileProvider 收窄**（原 P2）：移除未使用的 `READ/WRITE_EXTERNAL_STORAGE`（应用只写自己的专属目录并经 FileProvider 分享）、`WAKE_LOCK`/`RECEIVE_BOOT_COMPLETED`/`FOREGROUND_SERVICE*`（由 androidx.work 的库清单声明，应用自身不需要），删除 `usesCleartextTraffic="true"`（全部接口为 HTTPS）；`res/xml/file_paths.xml` 从 `<external-path path="." />`（整块外部存储）收窄为 `<external-files-path path="elearning/" />` + 应用私有 files/cache。
- 新增根目录 `CHANGELOG.md`（原 P2「建立 CHANGELOG」），记录 v1.0.5 起各版本的用户可见变化；发布说明仍以 GitHub Release 为准。

## 2. 信息优先级

发生冲突时按以下优先级判断：

1. 用户当前明确要求。
2. 可执行源码、构建脚本和自动化测试。
3. 本文件中标记为“硬性不变量”的约束。
4. 本文件的当前实现说明。
5. 根 README 与子项目 README。

Android 当前使用 `SQLiteOpenHelper` 而非 Room；应用内文档预览与媒体播放已实现（见 3.1 与第 17 节）。文档或实现发生冲突时必须重新核对源码，不得把目标能力写成已经完成。

## 3. 产品目标与不可回退能力

产品名是“复小学”，用于同步并管理用户有权访问的复旦大学 eLearning/Canvas 课程资料。目标发布形态是 Windows 桌面程序和 Android 应用。

最终产品要求：

- 支持 UIS 账号密码登录，并安全保存用户选择记住的凭据；桌面端还支持 Token、Cookie 和 Playwright 浏览器登录。
- 定时进行增量同步，允许手动“立即同步”和全量同步，明确展示进度、结果和错误。
- 采集课程文件，以及模块、页面、作业、公告、大纲中的文件引用；桌面端还归档正文。
- PDF、Word、Excel、PowerPoint、ODF、常见图片、文本、音频和视频必须在软件内部直接查看，不自动跳转第三方应用；无法高保真时也应在应用内显示结构化降级内容和限制说明。
- 音视频播放器至少具备播放/暂停、停止、前后跳转、可拖动进度、音量和单曲循环。
- 分享至少具备安全地分享本地文件；桌面端还提供复制文件、另存副本、复制路径和文件夹定位。
- UI 必须达到可发布质量：主流程完整，状态明确，窄窗口和长文本不遮挡按钮，键盘/触控可用，错误可恢复。
- 用户本地资料和凭据默认保留且不外传；网络或权限失败绝不能被误判为远端删除。

### 3.1 当前能力矩阵

| 能力 | Windows/Python 当前状态 | Android 当前状态 | 目标 |
|---|---|---|---|
| UIS 登录 | 已实现 | 已实现 | 两端协议保持一致 |
| Token/Cookie/浏览器登录 | 已实现 | 未实现，非当前必需 | 桌面端保持 |
| Canvas 分页与限流 | 已实现 | `v1.0.10` 起已实现（Link 分页、最小间隔、429/`Retry-After`、剩余额度减速、可区分错误类型） | 保持两端一致 |
| 完整来源爬取 | 文件/目录/模块/页面/作业/公告/大纲 | `v1.0.10` 起已对齐（目录重建 + 模块/页面/作业/公告/大纲的文件引用，正文归档仍仅桌面端） | 保持对齐（正文归档可选） |
| 可靠增量下载 | `.part`、续传、大小校验、原子替换 | `v1.0.10` 起已实现（`.part` + Range 续传 + 长度校验 + 原子改名 + 同名避让 + 退避重试） | 两端共享同一套安全语义 |
| 应用内 PDF/Office/图片/文本预览 | 已实现，部分格式有降级 | `v1.0.7` 起：PDF 与 Office（doc/docx/ppt/pptx/xls/xlsx，POI 解析 + Canvas 逐页渲染）均为纵向连续滚动 + 双指/双击缩放；`.doc`/`.docx` 自 `v1.0.8` 起渲染内嵌图片、跨页表格与逐段字符格式（完整页面）；`v1.0.9` 起 PPT 渲染自动形状/连接线箭头/组合形状，图表、SmartArt、OLE 与视频以占位卡加限制说明呈现，大文档（含超大内嵌记录）可解析；图片（含 GIF/HEIF）、文本/CSV（2MiB 上限）已实现；ODF/HTML 仍为结构化降级 | 两端对齐富文本渲染 |
| 应用内音视频 | 已实现 | `v1.0.6` 起用 Media3/ExoPlayer 实现：播放/暂停、停止、±10 秒、进度拖动、音量、单曲循环、错误界面 | 保持 |
| 分享 | 本地文件菜单已实现 | `v1.0.7` 起实装系统 ShareSheet：修复 ApplicationContext 启动崩溃、修正 OOXML MIME | 保持并补充错误处理 |
| 后台同步 | 托盘定时同步 | WorkManager 周期同步 | 保持可靠、互斥、可观测 |

任何发布说明都必须按“当前状态”描述，不能把目标能力写成已经完成。

## 4. 仓库地图和所有权边界

```text
.
├── AGENTS.md                         本开发交接规范
├── README.md                         产品级说明
├── elearning-sync/                   Python 3.10+ 桌面端与 CLI
│   ├── gui.py                        GUI 入口、单实例、启动配置
│   ├── sync.py                       CLI 入口
│   ├── VERSION                       桌面版本号
│   ├── requirements.txt              桌面依赖
│   ├── 复小学.spec                   唯一正式 PyInstaller 配方
│   ├── installer/setup.iss           Inno Setup 安装器
│   ├── fudan_sync/                   同步核心
│   │   ├── auth.py                   Token/Cookie/浏览器认证
│   │   ├── password_login.py         UIS 密码认证
│   │   ├── canvas_api.py             Canvas API、分页、限流
│   │   ├── crawler.py                课程内容发现
│   │   ├── downloader.py             并发、续传、原子下载
│   │   ├── state.py                  SQLite 状态库
│   │   ├── sync_engine.py            同步编排与删除保护
│   │   ├── daemon.py                 定时守护循环
│   │   └── gui/                      PySide6 界面层
│   └── tests/                        桌面自动化测试
└── android-app/                      Kotlin/Jetpack Compose Android 端
    ├── app/build.gradle.kts          Android SDK、依赖、版本和签名配置
    ├── gradle/wrapper/               Gradle Wrapper
    └── app/src/
        ├── main/java/edu/fudan/elearning/sync/
        │   ├── auth/                 UIS 认证
        │   ├── network/              OkHttp 与 Canvas API
        │   ├── data/                 SQLiteOpenHelper、模型和仓库
        │   ├── sync/                 同步与下载
        │   ├── worker/               WorkManager 与通知
        │   ├── preview/              统一预览路由、纵向翻页列表与各格式预览屏
        │   ├── office/               Office 六格式页模型、POI 提取与逐页渲染
        │   └── ...（桩不在主源码内，见下）
        │   ├── util/                 偏好、安全存储、文件 Intent
        │   └── ui/                   Compose UI 与 ViewModel
        └── androidTest/              设备/模拟器插桩测试
```

两端共享业务协议和产品语义，但不共享 Cookie、密码、SQLite 文件或本地绝对路径。PC 的 `sync_state.db` 与 Android 的 `fudan_sync.db` 结构不兼容，绝不能互相复制或宣称“结构一致”。

## 5. 开发环境

### 5.1 Windows/Python 桌面端

最低基线：

- Python 3.10+；审计过 Python 3.13.9。
- Windows 是主要发布平台；源码也保留 macOS/Linux 分支，但发布验收以 Windows 为准。
- PySide6 与 `PySide6-Addons` 必须来自同一发行源、同一版本的完整 wheel。
- 推荐独立 CPython venv。不要把 conda Qt、pip Qt、仓库旧 `vendor/` 和手工复制 DLL 混在一起。
- 浏览器登录可选安装 Playwright Chromium。
- Office 高保真预览可使用 Microsoft Office COM 或 LibreOffice；没有时走结构化解析降级。
- 音视频实际可播放范围还取决于 Qt/系统媒体后端和编解码器。
- Windows 安装器需要外部安装 Inno Setup 6；仓库不内置可依赖的编译器。

PowerShell 建议流程：

```powershell
cd elearning-sync
python -m venv .venv
.\.venv\Scripts\python -m pip install -U pip
.\.venv\Scripts\python -m pip install -r requirements.txt pytest pyinstaller
.\.venv\Scripts\python -m playwright install chromium  # 仅浏览器登录需要
.\.venv\Scripts\python gui.py
```

发布环境导入探针：

```powershell
.\.venv\Scripts\python -c "from PySide6.QtMultimedia import QMediaPlayer; from PySide6.QtMultimediaWidgets import QVideoWidget; from PySide6.QtPdf import QPdfDocument"
```

硬性要求：`fudan_sync/bootstrap.py` 只做能力探测，不允许通过修改 `PATH`、`QT_PLUGIN_PATH` 或拼接旧 DLL“修复”Qt。若导入失败，应重建干净环境并安装匹配依赖。

桌面测试运行提示（Windows）：若 `%TEMP%\pytest-of-lsx` 或仓库内 `.pytest_cache` 的 ACL 已被破坏（表现为 `PermissionError: [WinError 5]`，且与源码无关），用下面这条命令绕开它们——把临时根放到可写位置并关闭缓存插件：

```powershell
cd elearning-sync
.\.packaging-venv\Scripts\python.exe -m pytest -q -p no:cacheprovider --basetemp="$env:TEMP\fxx-pytest-bt"
```

`v1.0.7` 起桌面测试基线为 **68 passed**（含新增 `tests/test_desktop_p1_fixes.py`）。

### 5.4 构建 JDK（2026-09-19 起的关键约束）

Android Studio 从 261.x 起自带 **JBR 25**，而 Gradle 8.13 / AGP 8.13.0 **不支持 JDK 25**：配置阶段就会失败，错误信息是 `* What went wrong: 25.0.3`（堆栈里是 Kotlin 的 `JavaVersion.parse` 抛 `IllegalArgumentException`）。**这与源码无关**，必须让 Gradle 跑在 JDK 17–21 上：

```powershell
# 命令行（本轮验证用的 Temurin 21 解压在临时目录）
$env:JAVA_HOME = "$env:TEMP\jdk21\jdk-21.0.12.1+1"
cd android-app; .\gradlew.bat --offline --console=plain :app:assembleDebug
```

Android Studio 里对应设置为：**Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK → 选择 JDK 21**（`Download JDK…` 也可；**不要**继续用 Android Studio 自带 JBR，否则同样报 25.0.3）。长期方案是把 Android Studio 的 Gradle JDK 固定到 21，或在升级 Gradle/AGP 到支持 JDK 25 的版本后移除该约束。

**改构建/配置文件必须用补丁工具，不要用 PowerShell 字符串替换**：`Set-Content -NoNewline`（默认 ANSI 编码）会把 `build.gradle.kts` 里的中文注释写坏，表现为 `Unexpected symbol`（`v1.0.11` 开发中真实踩过，整个仓库一度不可构建）。同类文件还有 `settings.gradle.kts`、`gradle.properties`、`.iss`、`.spec`。若要改版本号，用 `apply_patch` 精确改动对应行。

**本机 git 配了本地代理，代理挂掉时推送与 Release API 都会失败**：`git config --global http.proxy` 指向 `http://127.0.0.1:7892`，该代理不可用时表现为 `TLS connect error: SSL routines::unexpected eof while reading`（openssl 后端）或 `schannel: failed to receive handshake`，而 `curl https://github.com/.../info/refs?service=git-upload-pack` 却是 200——据此可快速判断是代理而不是仓库/凭据问题。绕过方式（本机实测可用）：

```powershell
# 推送：显式清空代理
git -c http.proxy= -c http.sslBackend=openssl push https://<token>@github.com/lsx626/fuxiaoxue.git HEAD:refs/heads/main
# Release：PowerShell 的 Invoke-RestMethod 会走系统代理而失败，改用 curl
curl.exe -sS -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" `
  --data-binary "@release.json" https://api.github.com/repos/lsx626/fuxiaoxue/releases
curl.exe -sS -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/octet-stream" `
  --data-binary "@release/fuxiaoxue-vX.Y.Z.apk" `
  "https://uploads.github.com/repos/lsx626/fuxiaoxue/releases/<id>/assets?name=fuxiaoxue-vX.Y.Z.apk"
```

另外：给发布产物算 SHA-256 时若文件刚被烟测/杀软占用会报 `cannot be read: being used by another process`，可直接取 GitHub 资产接口返回的 `digest` 字段（与本地一致时即可确认）。

### 5.2 Android

构建基线：

- 单模块 `:app`，包名/namespace 为 `edu.fudan.elearning.sync`。
- Gradle Wrapper 8.13，Android Gradle Plugin 8.13.0。
- Kotlin 与 Compose 插件 2.1.0，Compose BOM 2025.01.00。
- JDK 17 为最低和首选基线；Java/Kotlin 目标均为 17。
- `compileSdk=36`，`targetSdk=35`，`minSdk=26`（Android 8.0+）。
- 依赖仓库先用阿里云镜像，再回退 Google/Maven Central/Gradle Plugin Portal。

`local.properties` 示例只允许在本机使用：

```properties
sdk.dir=<Android SDK 绝对路径>
fudanSign.storeFile=../release.keystore
fudanSign.storePassword=<secret>
fudanSign.keyAlias=fudansync
fudanSign.keyPassword=<secret>
```

不得把真实路径、口令或密钥提交到 Git。

### 5.3 外部服务与网络

- eLearning：`https://elearning.fudan.edu.cn`
- UIS：`https://id.fudan.edu.cn`
- Canvas 接口：`<base_url>/api/v1`

测试真实登录前确认任务确实需要外部网络和真实账户。常规单元测试应使用 mock/fake，不应访问学校服务，也不应把真实响应存成测试夹具。

## 6. 配置、路径和本地数据

### 6.1 桌面端配置

配置模型位于 `fudan_sync/config.py`，示例见 `config.example.yaml`。关键项包括：

- `base_url`
- `auth.method/token/username/method_cookie_file`
- `root_dir/state_db/log_file`
- `sync.interval_minutes/only_favorites/enrollment_type`
- `sync.include_courses/exclude_courses/include_terms`
- `sync.download.concurrency/max_retries/max_file_size_mb/min_free_space_gb/exclude_extensions`
- `sync.prune/archive_pages`

硬性不变量：配置中 `root_dir`、`state_db`、`log_file`、Cookie 路径的相对值，永远相对于该 `config.yaml` 所在目录解析，不得改为相对进程当前目录。快捷方式、托盘、自启和 `--config` 都依赖这一规则。

GUI 路径策略位于 `gui/paths.py`：

1. 若可执行文件/项目目录或当前目录已有 `config.yaml`，进入便携模式并复用它。
2. 否则先复用非空的旧目录 `%APPDATA%\fudan-elearning-sync`。
3. 再使用 `%APPDATA%\复小学`。
4. 默认下载目录为用户文档目录下的 `elearning_files`，但会优先复用已有课程目录。

CLI 默认使用当前目录的 `config.yaml`，GUI 可能使用用户数据目录配置。排障时必须先确认两者是否指向同一文件，必要时显式传 `--config`。

`gui/config_io.py::update_config()` 采用局部合并并保留未知字段；不要用重建整个 YAML 的方式丢弃未来字段。

### 6.2 Android 数据

- 普通设置：应用私有 `SharedPreferences("fudan_sync")`。
- 密码：Android Keystore + AES-256-GCM 加密后存私有偏好。
- 数据库：应用私有 `fudan_sync.db`，当前 schema 版本 2（`v1.0.10` 起新增 `files.updated_at` 与 `sync_runs.files_failed`/`error`）。
- 下载：应用专属外部目录 `<external-files>/elearning/`，卸载应用时通常由系统删除。

`v1.0.10` 起 `onUpgrade()` 按版本逐步执行非破坏性迁移（`ALTER TABLE ADD COLUMN`，并对已存在列做幂等判断），`onDowngrade()` 故意不动数据；只有 `oldVersion < 1`（从未发布过的异常状态）才回退到重建。**后续任何 schema 变更都必须沿用这一模式**，不得改回删表重建。

### 6.3 敏感文件和生成物

除非用户明确要求对某个脱敏测试文件进行处理，否则不要读取、展示或提交以下敏感内容：

- `cookies.json`、`*.cookie.json`
- `elearning-sync/config.yaml`
- `elearning-sync/cookies.json`
- `elearning-sync/sync_state.db` 及 `-wal`/`-shm`
- `elearning-sync/elearning_files/`
- `sync.log`、`gui_state.json`、下载中的 `*.part`
- `android-app/local.properties`
- `android-app/release.keystore`
- `FUDAN_ELEARNING_TOKEN`、`FUDAN_UIS_PASSWORD` 等环境变量值
- 真实日志中可能包含的认证 URL、课程名或个人信息

`vendor/`、`build/`、`dist/`、APK/AAB、安装包等生成物不得提交，也不得被当成源码或运行时依赖；但在明确的构建/发布验收中可以只读检查其文件清单、DLL、签名、体积和运行行为。

提交前至少运行 `git status --short` 和 `git diff --check`，并确认 `.gitignore` 仍覆盖上述内容。

## 7. 桌面端架构

### 7.1 入口与生命周期

GUI 入口 `elearning-sync/gui.py::main()`：

- 先执行无副作用的 QtMultimedia 能力探测。
- 用 `QLocalServer/QLocalSocket` 保证单实例；二次启动只唤醒已有主窗口。
- `QApplication.setQuitOnLastWindowClosed(False)`；点击主窗口关闭按钮默认隐藏到托盘。
- 只有 `MainWindow._quit()` 才是真正退出路径。
- 参数为 `python gui.py [--minimized] [--config PATH]`。

CLI 入口 `sync.py` 支持：

```powershell
python sync.py login --method password
python sync.py login --method token
python sync.py login --method cookie
python sync.py login --method browser
python sync.py courses
python sync.py sync
python sync.py sync --full
python sync.py sync --courses 12345 67890
python sync.py daemon --interval 15
python sync.py daemon --single
python sync.py status --courses
python sync.py files --course 12345
```

守护进程收到 SIGINT/SIGTERM 后协作式停止；不要用线程强杀代替清理。

### 7.2 数据流

```text
load_config
  -> build_auth
  -> CanvasAPI
  -> SyncEngine.discover_courses
  -> Crawler.crawl_course
  -> StateStore.upsert_file / 增量判定
  -> Downloader.download_many
  -> StateStore.mark_downloaded / mark_failed
  -> 页面归档与 SyncStats
```

GUI 数据流：

```text
MainWindow
  -> LoginWorker / SilentLoginWorker (QThread)
  -> SyncWorker (QThread)
  -> SyncEngine
  -> Qt Signal
  -> 主线程更新界面
```

登录、网络、同步、Office 转换不得阻塞 Qt 主线程。工作线程不得直接操作 QWidget。

## 8. Android 架构

主要职责：

- `App.kt`：Application，创建通知渠道。
- `MainActivity.kt`：Compose 宿主、通知权限、登录/主页路由。
- `auth/UisAuthenticator.kt`：UIS/RSA/CAS 登录。
- `network/ApiClient.kt`：OkHttp 会话；`CanvasApi.kt`：课程/文件接口。
- `data/DatabaseHelper.kt`：SQLiteOpenHelper；`Repo.kt`：CRUD 与统计。
- `sync/SyncEngine.kt`：课程发现、增量判定、下载和落库。
- `sync/DownloadManager.kt`：应用专属目录和流式下载。
- `worker/SyncWorker.kt`：WorkManager 周期/单次同步。
- `ui/AppViewModel.kt`：MVVM 状态编排；`LoginScreen`/`HomeScreen`：Compose UI。
- `preview/`：统一预览路由（`PreviewScreen` + `FileTypes`）、`VerticalPageList`（纵向连续滚动 + 双指缩放 + LRU 位图缓存）与 PDF/图片/文本/音视频/降级预览屏。
- `office/`：Office 六格式页模型（`PageModel`）、POI 提取器（`SlideExtractor`/`WordExtractor`/`SheetExtractor`）、`OfficeExtractor` 语义化结果与 `PageRenderer` 逐页 Canvas 渲染；Word 路径提取内嵌图片、表格（`FlowBlock.Table`，按列权重定列宽、按真实文本测量行高并跨页切分）与逐段字符格式。
- `awtstub/`（`app/src/awtstub/java/`）：`java.awt`/`java.awt.geom`/`javax.xml.stream`/`javax.xml.catalog` 的最小桩**源码**，由独立 `JavaCompile` 任务编译为 `java-platform-stubs.jar`，以 `implementation` 同时进入**编译期与运行期** classpath（机制与硬性约束见第 17 节）。

保持 `AppViewModel + StateFlow` 的主结构，但数据库、网络和文件 I/O 必须明确切到 IO dispatcher。业务异常需要显式错误状态，不能把空列表或 `Result.success()` 当作所有失败的统一结果。

WorkManager 周期任务名为 `fudan_sync_periodic_work`，使用 `ExistingPeriodicWorkPolicy.UPDATE`，最短周期 15 分钟且要求联网。当前只有成功交互登录后会安排任务；未保存密码时后台任务会直接退出。

## 9. UIS 认证算法与安全要求

PC 和 Android 必须保持同一协议契约：

1. 访问 eLearning `/login`，从跳转后的 URL 获取 `lck` 和 `entityId`。
2. 调用 `/idp/authn/queryAuthMethods`，选择 `userAndPwd` 认证链。
3. 调用 `/idp/authn/getJsPublicKey`，取得 Base64 DER RSA 公钥。
4. 使用 RSA PKCS#1 v1.5 加密 UTF-8 密码，再 Base64 编码。
5. 调用 `/idp/authn/authExecute`，取得 `loginToken`。
6. 表单提交 `/idp/authCenter/authnEngine`，解析 `locationValue` 并完成 CAS ticket 回跳。
7. 访问 eLearning 首页验证 Canvas 会话并提取 CSRF token。

桌面端接受 `_normandy_session` 或 `_canvas_session`；Android 当前只接受 `_normandy_session`，后续应对齐。认证协议变化时必须同时检查两端，不能只修一个客户端。

安全红线：

- 禁止记录或展示密码、Cookie、CSRF、`loginToken`、Token、签名口令、公钥响应全文或完整认证响应。
- 桌面明文密码只允许短暂存在内存，并只在用户选择时写入系统钥匙串；服务名为 `fudan-elearning-sync`。
- Android 只在用户选择记住密码时，用 Keystore 保护的 AES-GCM 落盘。
- Cookie 文件写入后，桌面端应在 Windows 尝试收紧 ACL，在 POSIX 设为 0600。
- OkHttp/requests 响应必须及时关闭；Android 使用 `Response.use`。

桌面认证回退规则是兼容旧配置的硬性行为：

- token 为空但钥匙串有该用户名密码时，切换为 password 并持久化。
- password 模式没有钥匙串密码但 Cookie 可用时，切换为 cookie 并持久化。
- GUI“不记住密码”仍可保存会话 Cookie，但不得写钥匙串；后续必须走 cookie，而不是回到空 password/token。

## 10. Canvas API、分页和限流

桌面 `CanvasAPI` 的约束：

- 元数据 API 严格低频串行；仅文件内容下载可以并发。
- 默认最小请求间隔 0.15 秒。
- 网络异常和 5xx 指数退避，最长约 30 秒。
- 429 或含 `Rate Limit Exceeded` 的 403 尊重 `Retry-After`，缺省约 8 秒。
- `X-Rate-Limit-Remaining < 15` 时主动减速。
- 分页必须原样跟随响应 `Link` 中的 `rel=next`；该 URL 是不透明值，不得自行重建页码或 bookmark。
- 只有第一页附初始 params，后续 next URL 已包含全部参数。

`v1.0.10` 起 Android 与桌面端对齐：`network/CanvasApi.kt` 原样跟随 `Link: rel="next"` 分页（`LinkHeader` 只挑 next，URL 视为不透明值），元数据请求经 `Mutex` 严格串行并保持最小间隔（默认 150ms），429 或含 `Rate Limit Exceeded` 的 403 尊重 `Retry-After`（缺省 8s），`X-Rate-Limit-Remaining < 15` 时追加减速，5xx/408/限流指数退避（500ms 起、30s 封顶、最多 4 次）。失败一律抛 `ApiException` 的具体子类（`Auth`/`RateLimited`/`Server`/`Network`/`Parse`），**绝不返回空列表冒充成功**；页数超过 `RequestPolicy.maxPages`（200）时明确报错而不是无限翻页。这些行为由 `CanvasApiTest`（假传输 + 假时钟，离线、12 个用例）覆盖。

## 11. 课程发现和爬取算法

桌面端一般课程列表由 Canvas API 应用 enrollment role，再由引擎应用课程 ID 白名单、课程 ID 黑名单和学期名称子串过滤，最后写入课程表。`only_favorites=true` 时当前直接调用收藏接口，该请求没有传 `enrollment_type`，因此收藏模式下并未额外执行角色过滤；不要把两种模式描述成完全相同，后续应补显式本地角色校验或说明产品语义。

`Crawler.crawl_course()` 的发现顺序：

1. folders：建立 `folder_id -> 相对路径` 映射，递归时用 seen 防环。
2. files：课程文件主列表。
3. modules：模块附件。
4. pages、assignments、announcements、syllabus：正文和引用文件。

文件以 Canvas `file_id` 去重。第一次发现保留权威路径，后续来源只追加 source/context。引用型文件通过 `/courses/:id/files/:file_id` 补完整元数据。

删除安全闸门是硬性不变量：只有课程 files 主列表完整成功后，`CrawlResult.files_listed_ok` 才能为真；只有它为真，才允许把本轮未出现的 ID 判定为远端删除。网络失败、权限错误、解析失败或结果不可信时，绝不能标记 missing 或删除本地文件。

页面归档写入 `<课程>/_pages/`，并将相对链接改写为平台绝对 URL。当前并未下载所有外链资源，因此这是“正文归档”，不是完全离线镜像；文案必须准确。

## 12. 增量同步、命名和删除语义

桌面端 `StateStore.upsert_file()` 的增量重下条件：

- 第一次见到该 `file_id`。
- 当前状态不是 `downloaded`。
- size、updated_at、modified_at 或 filename 变化。
- 数据库记录的本地文件不存在。

全量同步必须仍先 upsert 每个远端文件，再决定是否调度下载。`v1.0.5` 起实现为：

```python
state_needs_download = self.state.upsert_file(record)
needs = full or state_needs_download
```

禁止改回使用 `full or upsert_file(...)` 的短路表达式；“空数据库执行 full 后 files 记录完整”已有回归测试。

Downloader 只处理同步引擎已经判定需要下载的任务，不能再次按目标文件大小短路；远端内容可能在大小不变时更新。`v1.0.5` 起，全量同步会强制重新下载所有未被排除的文件，增量同步也会正确覆盖同大小的变更文件。

发布目标的文件命名和路径安全规则：

- 课程目录和路径组件必须清洗 Windows 非法字符、控制字符、尾随点/空格和保留名。
- 同一目录同名但不同 `file_id` 必须生成 `name (n).ext`，绝不能静默覆盖。
- 路径必须保持在配置的下载根目录内，任何远端名称都不得造成目录穿越。
- 课程改名目前可能生成新目录而保留旧目录；修改迁移策略前不得自动批量删除旧数据。

`v1.0.5` 起，源码会逐组件清洗 `folder_path` 和本地文件名，以 `realpath/commonpath` 验证课程根目录边界，并结合数据库历史占用、磁盘已有文件与 `.part` 文件稳定避让同名。状态库的 `filename/display_name` 有意保留 Canvas 远端原名，安全后的实际名称只用于 `DownloadTask`，权威落盘位置记录在 `local_path`；不要把本地安全名写回远端 filename，否则后续会误判远端重命名。

远端删除语义：

- `prune=false`：仅把数据库状态改为 `remote_missing`，保留本地文件。
- `prune=true`：只有通过 `files_listed_ok` 安全闸门后，且 `local_path` 经真实路径检查仍位于当前课程目录内，才允许删除对应本地文件。旧数据库中的越界路径只标记 missing，不删除目标。
- 存储管理器的“用户主动删除”会同时删文件和数据库行，下次同步会重新下载；UI 应明确这一结果。

## 13. 下载算法

硬性不变量：文件内容下载必须复用携带会话 Cookie 的 `api_session`（`self.api_session.get()`），绝不能改回模块级 `requests.get()`。否则 Canvas 下载链接会重定向到 UIS 登录页（固定约 6328 字节 HTML），表现为“下载成功但内容是登录页”或 416 错误。`DownloadAuthError`、`_is_auth_redirect()`、416 时丢弃 `.part` 重试和登录页 HTML 嗅探是这条不变量的测试锚点（见 `tests/test_download_auth.py`）。`_on_download_done` 必须区分成功与失败，不能把失败记录成完成。

桌面端可靠下载流程：

1. 下载前汇总任务大小，保证下载后仍不低于 `min_free_space_gb`。
2. 优先通过 `/courses/:course/files/:file` 获取新的签名 URL；失败才使用爬取对象的备用 URL。
3. 写入 `目标文件.part`。
4. 已有 `.part` 时使用 HTTP Range 续传；服务忽略 Range 并返回 200 时从头覆盖。
5. 以 1 MiB chunk 流式写入，并在 chunk 间检查停止事件。
6. 已知远端大小时必须校验完整长度。
7. 成功后用 `os.replace(part, dest)` 原子替换。
8. 失败保留 `.part` 供下次续传，并把错误写入状态库。

并发只用于文件内容下载，`ThreadPoolExecutor` 上限由配置控制。完成回调运行在线程上下文，不能直接操作 GUI。

`v1.0.10` 起 Android 与桌面端同构：`sync/DownloadManager.kt` 写 `目标.part`，已有断点时用 `Range` 续传（服务端返回 200 则从头重写，避免旧字节留在文件开头），已知远端大小时校验完整长度（不完整则保留断点），成功后同目录 `renameTo` 原子落盘（不支持时退化为复制后删除），失败按 500ms/1s/2s 指数退避重试（最多 3 次）。HTTP 401/403 与「HTML + 登录标记」的登录页嗅探会立刻判定为会话失效并**停止重试**；同名不同 `file_id` 通过 `DownloadPlan.uniqueDestination()` 避让成 `name (n).ext`，绝不覆盖。续传判定、长度校验、同名避让、登录页嗅探与退避数值由 `DownloadPlanTest` 覆盖。

## 14. 状态库与状态机

### 14.1 桌面 SQLite

- WAL 模式，`synchronous=NORMAL`。
- 表：`courses`、`files`、`sync_runs`、`kv`。
- 文件状态：`pending`、`downloaded`、`failed`、`remote_missing`。
- 每个线程独立 SQLite connection，连接和 cursor 绝不能跨线程传递。
- 类级写锁串行化写事务。
- `close()` 只关闭调用线程自己的连接。

`_write_lock_cursor()` 当前异常路径仍会 commit，没有 rollback 分支。新增多语句事务前先修复该上下文管理器，或显式证明操作原子性并补失败测试。

### 14.2 Android SQLite

- 使用 `SQLiteOpenHelper`，不是 Room。
- 表：`courses`、`files`、`sync_runs`。
- Android 字段少于桌面端，状态和增量元数据也不完整。
- 不允许把桌面数据库文件导入 Android，反之亦然。
- `v1.0.10` 起增量同时比较 status、size、远端 `updated_at`、本地文件是否存在与长度是否相符；失败状态写回 `failed` 供界面提示与重试；schema v2 迁移为只加列的非破坏性迁移。

## 15. GUI 线程、窗口和资源生命周期

### 15.1 桌面线程规则

- `LoginWorker`、`SilentLoginWorker`、`SyncWorker` 都是 QThread。
- 工作线程通过 Signal 报告日志、进度、成功和失败；只允许 Qt 主线程更新控件。
- 每个工作线程在自己线程内创建并关闭 auth/API/StateStore/SyncEngine，不得把连接缓存给主线程。
- 每次手动同步前重新加载配置，使设置修改立即生效。
- 同一时刻只允许一个同步 worker。
- 停止必须调用 `SyncEngine.stop()` 协作取消，不得使用 `QThread.terminate()`。

当前退出只等待同步线程约 5 秒，阻塞请求超过该时间可能触发 `QThread still running`。后续应设计可靠退出等待和可取消请求，而不是强杀线程。

### 15.2 主窗口和布局

- 主窗口最小支持尺寸为 `880x620`。
- “立即同步”“全量同步”“停止”等主操作在 880x620、125%/150% DPI、长用户名和长状态文本下必须完整可见。
- 长课程名、文件名、用户名和路径使用省略显示并提供 tooltip，不能挤掉操作区。
- 固定格式控件应有稳定的最小尺寸，busy/loading/error 不得导致布局跳动。
- 主窗口关闭默认进托盘；忙碌时托盘同步动作应禁用。
- 单实例二次启动必须正确唤醒已有窗口。
- 删除、退出、覆盖等不可逆操作必须有明确确认和结果反馈。

桌面主题应保持安静、实用、适合高频资料管理，不要用营销式大标题、装饰性浮层或层层嵌套卡片。既有主色为 `#4F46E5`，交互深色 `#4338CA`，更深色 `#312E81`，背景 `#F6F7FC`；新增状态色应具备足够对比度，不能让界面变成单一紫色层级。

### 15.3 Android UI

- 保持 Material 3 与现有品牌色，支持明暗主题。
- 图标按钮必须设置可理解的 `contentDescription`；纯装饰图标可为 null。
- 新增用户文案优先放资源文件，避免扩大硬编码字符串。
- 长文本必须换行或省略，触控目标不得过小。
- 必须覆盖窄屏、超长中文、无数据、加载、失败、离线、权限拒绝和同步中状态。
- 删除必须二次确认；分享仅授予临时只读 URI 权限。
- 随数据库变化的列表不能用缺少 key 的 `remember` 永久缓存旧结果。

## 16. 桌面应用内预览规范

入口为 `gui/previewer.py::_detect_type()` 和 `DocumentPreviewDialog`。

### 16.1 格式矩阵

- PDF：优先 `QPdfDocument + QPdfView`，多页并适配宽度；失败时用 PyMuPDF 降级，目前只渲染第一页。
- 图片：PNG、JPEG、GIF、BMP、WebP、ICO、SVG、TIFF、AVIF、HEIC、HEIF。GIF 用 QMovie，SVG 用 QtSvg；Qt 解码失败时用 Pillow/pillow-heif。
- 文本和代码：常见源码、日志、Markdown、JSON、XML 等；UTF-8-sig 解码并容错，最多读取 2 MiB。
- HTML：QTextBrowser 内显示，最多 4 MiB，外部导航默认受控。
- CSV/TSV：最多 1000 行、40 列，避免超大文件冻结 UI。
- RTF：自有控制字、Unicode 和代码页解码。
- Word/Excel/PowerPoint/ODF：优先 Office/LibreOffice 转 PDF；失败后用 python-docx/openpyxl/python-pptx/odfpy 结构化提取。
- 音视频：QtMultimedia；格式被识别不代表当前系统一定有对应 codec。

旧二进制 `.doc/.xls/.ppt` 在没有 Office 或 LibreOffice 时无法高保真解析，必须显示清晰的降级说明，不能假装成功。结构化解析只保证可读内容，不保证分页、图表、动画、宏和复杂布局保真。

`.ts` 有双重含义：MPEG-TS 与 TypeScript。必须通过 188 字节同步字节特征判断媒体，不能仅按扩展名分类。

### 16.2 Office 转换安全

- 转换在 Python 后台线程执行，不得在该线程创建或操作 Qt GUI 对象。
- Windows COM 打开文档必须只读、禁提示，并设置 `AutomationSecurity=3` 禁用宏。
- LibreOffice 使用独立临时 UserInstallation profile，设超时并支持取消。
- 临时目录以 `fudan_preview_` 创建。
- 对话框关闭后迟到的转换结果必须立即清理，不能向已销毁 QObject 发结果。

### 16.3 媒体控制

播放器必须保留：

- 播放/暂停。
- 停止并归零。
- 后退/前进 10 秒。
- 用户可拖动进度条；不可 seek 时禁用或明确表现。
- 0-100 音量。
- 单曲循环；兼容 Qt6 loops 枚举并保留 EndOfMedia 兜底。
- 播放错误在播放器内部可见，不应只写日志。

### 16.4 资源清理硬规则

`DocumentPreviewDialog` 必须使用 `WA_DeleteOnClose`，并让 `done()`、`closeEvent()` 和应用退出清理都进入幂等路径。关闭顺序必须涵盖：

1. 取消 Office 转换。
2. 停止并解除 QMediaPlayer/QAudioOutput/QVideoWidget。
3. 停止 GIF/QMovie。
4. 让 PDF view 脱离 document。
5. `QPdfDocument.close()`，并在 Windows 同步销毁底层对象。
6. 最后删除临时 PDF 和临时目录。

不能只调用 `deleteLater()` 就立刻删除临时 PDF；Windows 文件句柄尚未释放会导致失败或崩溃。主窗口保存预览窗口弱引用时，释放回调必须比较对象身份，旧窗口延迟 finished 不能清掉新窗口引用。

## 17. Android 内置预览目标

`v1.0.7` 起，Android 应用内预览：`AppViewModel.openPreview()` 驱动 `PreviewScreen` 统一路由，`FileUtils` 不再使用 `ACTION_VIEW` 打开预览（分享仍用 `ACTION_SEND`）。已实现：

- PDF：平台 `PdfRenderer`，**纵向连续滚动**（`VerticalPageList`，下拉式翻页）+ 双指/双击缩放；复用单一渲染器实例，位图按需 LRU 缓存（展示中的页被钉住，不回收）。
- Office 六格式（doc/docx/ppt/pptx/xls/xlsx）：Apache POI 解析为 `DocPage` 页模型，Canvas 逐页渲染，保留形状坐标、文本格式、图片与表格；`pptx/ppt` 一张幻灯片一页，`docx/doc` 按真实文本测量分页（图片按内容宽度等比适配、超高图片限高、表格可跨页切分），`xlsx/xls` 按工作表分页（超大行数按固定页高切割）；同样纵向连续滚动 + 缩放。`v1.0.8` 起 `.doc`/`.docx` 的内嵌图片、表格与逐段字符格式被完整提取渲染，不再是纯文字。
- 图片（Coil，含 GIF 动图与 HEIF，双指缩放 + 双击复位）、文本/CSV（`2 MiB` 上限流式读取 + 截断提示）、音视频（Media3/ExoPlayer，完整传输控制与错误界面）。
- ODF/HTML 仍为结构化降级（轻量文本抽取 + 明确限制说明）。

`v1.0.9` 起在同一预览链路上补齐预览体验：

- **PPT 形状保真**：自动形状（含**无文字的装饰形状**、填充/描边/旋转）、连接线（含箭头端点，允许高或宽为 0 的水平/垂直直线）、组合形状（递归展开，子坐标按组合锚点平移，**组内缩放不还原**属已知限制）都会渲染；图表 / SmartArt / OLE 嵌入 / 视频输出 `PageItem.Placeholder` 占位卡 + 限制说明，不做高保真还原。文本框还原内边距与垂直对齐。
- **大文件可解析**：`App.onCreate` 调 `OfficeExtractor.applyPoiLimits()`，把 POI 的单记录上限按堆大小自适应放宽到 `100–384 MiB`（`IOUtils.setByteArrayMaxOverride`，初始缓冲压到 1 MiB）。单记录超限或 OOM 会转成可读说明（`OfficeLimits.MEMORY_HINT`），不再把「Tried to allocate an array of length …」原始异常串暴露给用户；取消（离开预览）原样抛出，绝不当成解析失败。
- **可进度可取消**：`OfficeExtractor.extract()` 是 `suspend`，内部切到 `Dispatchers.IO`，逐张幻灯片回调 `(已完成, 总数)`，预览页显示「正在解析… n/m」。
- **内存控制**：文件 > 16 MiB 时渲染宽度降到 1080–1440px；页数 > 60 时页模型整体缩放到 75%；单页长边 > 4096px 时先缩模型再渲染（旧实现直接返回「该页无法渲染」）；内嵌图片 > 2 MiB 先降采样再入库；`PageBitmapCache` 预算改为堆的 1/8（32–96 MiB）。
- **导航与文案**：`BackHandler` 覆盖预览与文件列表层，课程选中状态上提到 `AppViewModel`（预览返回后仍在原文件列表）；顶栏用列表显示名（Canvas `display_name`）；预览页有跳页 / 回到页首 / 重置缩放，失败页有「用其他应用打开」按钮；设置页新增「关于」。

仍缺：HTML 富文本渲染（当前显示源文本）、Office 图表/SmartArt/OLE 等复杂元素的高保真还原；损坏文件与不支持格式的降级已有插桩测试覆盖（corruptFile_reportsFailureNotCrash、officePreview_corruptShowsErrorPage），音视频 codec 不支持的端到端测试仍缺。

**POI 实色两种类型（易错点，已实证）**：POI 的实色既可能返回 `ColorStyle`（主题色/配色变换），也可能返回 `PaintStyle.SolidPaint`（直接 RGB，实现类是 `DrawPaint.SimpleSolidPaint`）。XSLF 的描边色与逐段文字颜色走的是后者：只判断 `paint is ColorStyle` 会让所有 PPT 描边与文字颜色静默丢失（`v1.0.9` 前就是这个缺陷）。`SlideExtractor.paintArgb()` 必须两种都处理。

**POI 图表夹具（影响测试）**：`XSLFSlide.addChart()` 不会立刻把图形框挂到幻灯片上，只有 `write()` 之后才能读到该 `XSLFGraphicFrame`（`hasChart()==true`）。构造含图表的夹具必须用 `show.createChart(slide)`，并在写盘后重新打开文件再断言。

**非 ASCII 工程路径会使 Gradle 单元测试 worker 失败（本机环境限制）**：本仓库路径含中文（`D:\Projects\学习资料自动收集`）。Gradle 把测试 worker 的 classpath 写入 UTF-8 的 `@argfile`，而 Windows 上 JDK 启动器按 ANSI 代码页解码该文件，结果测试类全部报 `ClassNotFoundException`（连未被改动的测试也失败，可作为判据）。可行做法是把 `android-app` 复制到纯 ASCII 路径（例如 `%TEMP%\fxx-ut`）后再跑 `gradlew testDebugUnitTest`；或在 Windows 开启「Beta: 使用 Unicode UTF-8 提供全球语言支持」。这与源码缺陷无关，不要据此改动业务代码。

### 17.1 设备端插桩测试（`v1.0.10` 实测基线）

2026-09-19 在 API 36.1 模拟器上跑通全部四类插桩测试，**23 个用例全绿**：

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `office.OfficeRendererInstrumentedTest` | 10 | POI 六格式解析 + Canvas 渲染 + 形状/连接线/组合/图表占位卡 + 损坏文件降级 |
| `office.OfficePreviewUiTest` | 2 | Office 预览页组合（页脚页码、保真度提示卡、损坏文件错误页） |
| `HomeScreenTest` | 8 | 课程/存储/设置三页、中文状态、学期筛选、导航 |
| `data.DatabaseMigrationInstrumentedTest` | 3 | 全新安装 schema、v1→v2 迁移保留数据、同步失败信息落库 |

**无头模拟器**（本机实测可用的参数组合，缺 `-feature -Vulkan` 会因 SwiftShader 的 Vulkan 设备创建失败而立刻退出）：

```powershell
& 'D:\Sdk\emulator\emulator.exe' -avd fxx_test_api36 -no-window -no-audio -no-boot-anim `
  -gpu swiftshader_indirect -feature -Vulkan -no-snapshot-load -ports 5554,5555
```

**不走 Gradle 跑插桩测试**（Gradle/JDK 出问题时的备用通道，也是本轮实际使用的方式）：

```powershell
adb install -r -t app\build\outputs\apk\debug\app-debug.apk
adb install -r -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -r -e class edu.fudan.elearning.sync.office.OfficeRendererInstrumentedTest `
  edu.fudan.elearning.sync.test/androidx.test.runner.AndroidJUnitRunner
```

**`v1.0.10` 由设备端测试发现、JVM 测试永远发现不了的两个真实缺陷**（修好后才有上面的绿色基线）：

1. `DatabaseHelper.onCreate()` 只建了 v1 列，**全新安装**首次写入 `files.updated_at` 直接抛
   `SQLiteException: table files has no column named updated_at` —— 即新用户第一次同步必崩。
   规则：**`onCreate` 与 `onUpgrade` 必须同步维护**，新增列两处都要有；`DatabaseMigrationInstrumentedTest` 已锁定。
2. `java.awt` 桩缺方法导致 `org.apache.poi.sl.draw.DrawPaint` 静态初始化失败，进而在设备上
   **把 PPT 的填充与描边全部静默丢弃**（`SlideExtractor` 的 `runCatching` 把异常吞掉了）。
   根因在 logcat：`Rejecting re-init on previously-failed class ... NoSuchMethodError: No direct method <init>(FFFF)V in class Ljava/awt/Color;`。
   已补：`Color` 的 `(FFFF)V`/`(I Z)V` 构造器、`getRGBComponents`/`getRGBColorComponents`/`getComponents`/`getColorComponents`、小写预定义色常量；
   新增 `java.awt.Paint/Shape/Graphics2D/RenderingHints/MultipleGradientPaint/LinearGradientPaint/RadialGradientPaint`、`java.awt.image.BufferedImage/IndexColorModel`、`java.awt.geom.AffineTransform` 桩。

**写设备端夹具的规矩**：不要用 POI 的 AWT 写 API（`setFillColor`/`setLineColor`/`setLineDecoration` 等），
它们在 Android 上依赖的 AWT 面比读取路径宽得多。直接写 OOXML，等价于真实文档，也更稳：

```kotlin
val spPr = (shape.xmlObject as CTShape).spPr
spPr.addNewSolidFill().addNewSrgbClr().setVal(byteArrayOf(0x4F, 0x46, 0xE5.toByte()))
spPr.addNewLn().setW(38100)   // 12700 EMU = 1pt
spPr.ln.addNewSolidFill().addNewSrgbClr().setVal(byteArrayOf(0, 0, 0))
spPr.ln.addNewTailEnd().setType(STLineEndType.TRIANGLE)
```

**排查设备专属类加载问题的固定套路**：现象是测试里看到外层 `NoClassDefFoundError`，但根因在 logcat 的
`Rejecting re-init on previously-failed class` 行（会给出真正的 `NoSuchMethodError`/`NoSuchFieldError`）。
先用 `adb logcat -c` 清空，跑一次失败用例，再 `adb logcat -d | Select-String "previously-failed|Caused by"`。

**POI 与 java.* 桩（硬性约束，已实证）**：Android 平台（`android.jar`）**没有** `java.awt`、`javax.xml.stream`、`javax.xml.catalog`（用 zip 条目枚举 `android-36/android.jar` 确认为 0 个），而 Apache POI 与 xmlbeans 的 API 签名与字节码都引用了它们；`app/src/awtstub/java/` 提供满足其调用面的最小桩**源码**，包名声明为 `java.awt`/`java.awt.geom`/`javax.xml.stream`/`javax.xml.catalog`，以及仅为编译桩源码而存在的 `javax.xml.namespace`。该 jar 必须以 `implementation`（**不是** `runtimeOnly`）引入：`android.jar` 完全不含 `java.awt`，编译期符号只能由本桩提供；已实测改用 `runtimeOnly` 会让 `compileDebugKotlin` 对全部 `java.awt.*` 引用报 `Cannot access class`。

这些桩**必须同时进入编译期与运行期 classpath**（`implementation(files(javaStubsJar))`），原因与陷阱如下：

1. **编译期需要**：AGP 以 `android.jar` 作为平台类路径，其中没有 `java.awt`。应用源码（如 `SlideExtractor` 引用 `java.awt.Dimension`）与 POI 的 API 签名都必须靠本桩才能解析符号——不引入则 Kotlin 报 `Cannot access class 'java.awt.Dimension'` / `Unresolved reference`。
2. **运行期需要**：桩类被打进 APK（ multidex 已开启），POI/xmlbeans 的字节码在设备上才能解析到类定义。已实证（dexdump）：33 个桩类全部定义在 debug APK 的 classes15.dex；模拟器插桩测试已验证 pptx/docx/xlsx/xls/ppt 五种格式的解析+渲染。.doc 走与 .docx 相同的 WordExtractor 路径，本机因 Office COM 自动化挂起未能单独造出旧格式夹具验证，属已知验证缺口。
3. **绝不重复打包 Android 已有的类**：`android.jar` **提供** `javax.xml.namespace.QName`/`NamespaceContext`。桩源码里需要它们才能通过 `--limit-modules java.base` 编译，但 `javaStubsJar` 打包时必须 `exclude("javax/xml/namespace/**")`，否则编译期与 `android.jar` 重复。
4. **不能把桩放进主源码集用 Kotlin 编译**：那样桩源码与 JDK 平台的同名类产生 FQN 冲突（`Supertype initialization is impossible`/`Conflicting overloads`）。因此用独立源集 + 独立 `JavaCompile` 任务、参数 `--limit-modules java.base -source 17 -target 17`（不能用 `--release`，会触发 javac 退出码 3），`classpath = files()`。

修改 Office 解析相关代码时不得删除这些桩、不得改回 Kotlin 源码集、也不得把 `implementation` 改成 `compileOnly`（运行期会缺类）。新增平台类引用必须补桩并在模拟器上用合成文档验证（桌面 JVM 无法验证运行期行为：Android 的 PathClassLoader 允许定义 `java.*` 类，而桌面 JVM 的 `ClassLoader` 会抛 `Prohibited package name`）。覆盖面靠 logcat 的 `NoClassDefFoundError` 增量补齐，`runCatching` 已兜底为「解析失败」错误页，不会崩溃。

修改 Office 解析相关代码时不得删除这些桩或改成 `implementation`；新增平台类引用必须补桩并在模拟器上用合成文档验证（桌面 JVM 无法验证：`ClassLoader` 禁止定义 `java.*` 包的类，会抛 `Prohibited package name`）。**POI 坐标单位（易错点，已实证）**：`SlideShow.getPageSize()`、`Shape.getAnchor()`、字号与 `TextParagraph.getSpaceAfter()` **都返回磅**。`setPageSize` 也以磅为单位（传 EMU 会触发 `Units.toEMU` 溢出饱和到 `Integer.MAX_VALUE`，读回变成正方形）。`SlideExtractor` 的「磅→像素」因子 `scale = targetWidthPx / pageWpt`，页面高度用 `Math.round` 避免比例性截断；**不要**再对 `getPageSize()` 二次套用 `Units.toPoints`。

**POI 写 API 的一个坑（影响测试夹具）**：对同一行 `createRow(n)` 只能调用一次并复用引用；重复 `createRow(n)` 会让前一次行对象及其已加单元格在落盘时丢失（表现为每行首列消失）。

实现时建议分层，而不是为每个扩展名堆独立 Activity：

1. 统一 `PreviewRoute(file, detectedType)` 和 MIME/魔数识别。
2. PDF 使用受支持的应用内 PDF 渲染方案，支持多页、缩放和错误状态。
3. 图片用 Compose/可靠图片解码器，覆盖动图、超大图和 HEIF 能力降级。
4. 文本/CSV/HTML 使用有大小上限的流式或分页读取，禁止一次把超大文件塞进内存。
5. 音视频优先使用成熟的 Media3/ExoPlayer，而不是自行实现解码和播放状态机；实现进度拖动、音量、单曲循环和后台/生命周期释放。
6. Office/ODF 需要明确可维护的渲染/转换策略；若格式不能保真，显示结构化降级和限制，不得偷偷跳外部应用。
7. 预览页面保留安全分享入口，并处理文件缺失、损坏、权限和 codec 不支持。

实现每一类格式时都要有合成测试文件，禁止把真实课程资料加入仓库。

## 18. 分享语义

桌面 `gui/sharing.py` 当前提供：

- 复制文件 URL 和路径到剪贴板。
- 另存副本。
- 复制路径。
- 在文件管理器中定位。

这是本地文件操作，不是网络上传或生成公开链接。新增云分享或上传前必须单独设计授权、隐私、进度、取消和失败恢复。

Android 使用 `ACTION_SEND + FileProvider + ShareSheet`。只授予临时只读权限，限制 FileProvider 暴露目录，分享前确认文件仍存在。中文、空格、撇号和长路径都必须测试。

## 19. 自动化测试与验收

### 19.1 桌面测试

在 `elearning-sync/`：

```powershell
$env:QT_QPA_PLATFORM='offscreen'
$env:PYTHONDONTWRITEBYTECODE='1'
python -m pytest -q -rs -p no:cacheprovider
```

`v1.0.6` 发布基线：完整 Qt 隔离环境为 `61 passed`、无跳过（`v1.0.5` 的 53 个基线测试 + 下载鉴权回归 8 个）。缺少 QtMultimedia 的基础环境会跳过媒体生命周期测试，只适合日常逻辑检查；发布验收不能接受该 skip。

现有测试覆盖配置路径、认证回退、不记住密码、登录流程、删除安全、PDF 预览、扩展预览生命周期、分享和 QtMultimedia 探测。以下改动必须追加定向测试：

- full 同步先 upsert 再调度。
- Android/桌面分页、429 和 5xx 退避。
- 同名文件冲突和路径穿越。
- 数据库迁移、事务 rollback。
- Office 转换失败、超时、关闭后迟到结果。
- 媒体 seek、循环、错误和关闭进程退出。

### 19.2 Android 测试

Windows PowerShell：

```powershell
cd android-app
$env:JAVA_HOME = "<JDK 17 路径>"
.\gradlew.bat --version
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
.\gradlew.bat connectedDebugAndroidTest
```

`connectedDebugAndroidTest` 需要 API 26+ 设备/模拟器。

**JVM 单测的路径限制（已实证）**：`testDebugUnitTest` 的测试 worker 是独立 JVM。当项目路径含非 ASCII 字符（本机为 `D:\Projects\学习资料自动收集`）时，Gradle 传递给 worker 的类路径会无法解析项目自身的测试类，报 `ClassNotFoundException: ...OfficeExtractorTest`（注意：直接用 `java -cp "<中文路径>"` 是好的，纯属 Gradle worker 的传递问题；设 `file.encoding`/`sun.jnu.encoding` 无效）。**解决办法**：把项目目录做一个 ASCII 路径的目录联结（junction），在联结路径下跑单测：`cmd /c mklink /J C:\fxs "D:\Projects\学习资料自动收集"`，然后 `cd C:\fxs\android-app` 执行 `gradlew testDebugUnitTest`。编译 APK（`assembleDebug`）不受影响，原路径即可。插桩测试现状（2026-09-19，API 36 模拟器实测）：OfficeRendererInstrumentedTest 9 项 + OfficePreviewUiTest 2 项全部通过（覆盖 pptx/docx/xlsx/xls/ppt 的解析+Canvas 渲染、本机 Office COM 生成的真实 .doc/.docx/.ppt 夹具的表格与图片渲染、损坏文件降级、不支持格式降级、预览界面纵向翻页与错误页）；HomeScreenTest 8 项中 6 项通过，settingsTab_showsAccountAndInterval 与 courseDetail_showsChineseFileStatus 2 项失败——已用 v1.0.6 标签的全新 worktree 同样复现失败，确认为 v1.0.6 既存问题而非 v1.0.7 回归，本周期未修复（超出 v1.0.7 范围）。仍缺认证、分页、数据库迁移、下载完整性、Worker 重试的单元/集成测试。

### 19.3 人工发布验收

桌面至少检查：

- 880x620、125%/150% DPI、长用户名/课程名时主操作可见。
- 首次登录、记住/不记住密码、静默登录、退出登录。
- 增量、全量、停止、网络失败、429、磁盘不足、远端空列表。
- 托盘隐藏/唤醒、单实例、开机自启、真正退出。
- PDF 多页、DOCX/XLSX/PPTX/ODF、常见图片、GIF、中文文本/CSV/HTML/RTF。
- MP3/MP4 播放、拖动、前后跳转、音量、单曲循环、关闭后进程退出。
- 中文/空格/撇号路径下的复制、另存、定位和分享。

Android 至少检查：

- Android 8、主流新版本和 API 36 模拟器/真机中的登录与同步。
- 前后台切换、进程重建、离线、权限拒绝、Worker 重试。
- 窄屏、深色、超长中文、空状态、错误状态。
- 删除确认、分享 URI 权限、卸载后的数据语义。
- 内置预览完成后逐类验证 PDF/Office/图片/文本/音视频及生命周期。

## 20. Windows 构建和发布

正式构建只能使用 `elearning-sync/复小学.spec`：

```powershell
cd elearning-sync
.\.packaging-venv\Scripts\python -m pytest -q
.\.packaging-venv\Scripts\python -m PyInstaller --noconfirm 复小学.spec
```

产物是 `dist\复小学\复小学.exe`。spec 会显式收集 QtMultimedia、QtPdf、Office/ODF、Pillow/HEIF 和 PyMuPDF；缺少匹配的 QtMultimedia 时应立即失败。不要用仓库旧 `vendor/` 拼 DLL。首次准备 `.packaging-venv` 见 22.2；只有满足 22.7 的缓存失效条件才追加 `--clean`。

再用 Inno Setup 6 编译 `installer/setup.iss`。`v1.0.5` 已验证的隔离构建基线约为 339.6 MiB、525 个文件，且不应混入 NumPy/MKL；该数字只是异常膨胀检测参考，不是固定验收值。安装器的固定 `AppId` 关系到覆盖升级，未经迁移设计不得修改。

发布前同步版本：

- `elearning-sync/VERSION`
- `elearning-sync/fudan_sync/__init__.py::__version__`
- `elearning-sync/installer/setup.iss` 的 `MyAppVersion`
- `OutputBaseFilename`
- 根 README、桌面 README、`android-app/README.md` 中展示的版本
- Android `versionName`，并严格递增 `versionCode`

`v1.0.5` 起安装器不再使用 `[UninstallDelete]` 递归删除 AppData，卸载时默认保留配置、Cookie、数据库、日志和课程资料。`test_installer_safety.py` 防止该危险规则被重新引入。

发布产物应在干净 Windows x64 虚拟机完成全新安装、覆盖升级、自启、卸载和核心预览验收，生成 SHA-256；公开发布建议对 EXE 和安装器进行代码签名。仓库中任何旧 `release/` 文件都不能代表当前 HEAD。`v1.0.5` 使用新标签发布，禁止移动、覆盖或强推已有的 `v1.0.4` 标签。

`v1.0.5` 的本机发布验收记录（2026-09-18）：

- 桌面完整测试 `53 passed`、无跳过；打包后的 EXE 离屏启动 8 秒正常存活。
- PyInstaller 产物 525 个文件、约 339.6 MiB；Inno Setup 6.7.3 编译成功。
- Windows 安装器 SHA-256：`33814C57BEF0681900361E98B9A72F8EECF8D10977822528B6D94CCD4972BB77`。
- Windows 安装器未做 Authenticode 代码签名，用户可能看到“未知发布者”；不得声称已签名。
- Android `lintDebug` 通过，release APK/AAB 构建成功；无连接设备，因此本轮未运行 `connectedDebugAndroidTest`。
- APK SHA-256：`D663289390B69E997362D76967CE10BC1A1149E165AD0634AE5821EEAD825F42`。
- AAB SHA-256：`F95A2EB80A91FE8CE0D085F77270B3201CB2439228A7287F0F10B7F7A972FA52`。

## 21. Android 构建和发布

```powershell
cd android-app
.\gradlew.bat lintDebug
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat assembleRelease
.\gradlew.bat bundleRelease
```

产物：

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- Release APK：`app/build/outputs/apk/release/app-release.apk`
- Release AAB：`app/build/outputs/bundle/release/app-release.aab`

没有真实 release keystore 时，构建脚本会回退 debug 签名，这只用于保证克隆后可构建，绝不能公开发布。发布前用 `apksigner verify --verbose --print-certs` 验证证书；当前预期 SHA-256 指纹为：

```text
1dc096a7647e931084dba2387487b6283f407c83b1feadb5b329cae70f1ef077
```

发布版目前未启用 minify。若启用 R8/ProGuard，必须增加并验证 Gson 模型、OkHttp、Compose 和反射相关规则。

## 22. 快速打包、签名与 GitHub 发布 SOP

本节用于“改动已经稳定，只需尽快产出并发布”的场景。深度审计、修复新缺陷和首次搭建环境不计入发布耗时。工具与缓存准备完成后，文档改动通常应在数分钟内完成推送；双端缓存构建应争取在 10-20 分钟内完成，而不是每次从零安装环境、重新下载工具或重复无关平台的测试。

### 22.1 加速原则

1. **先冻结候选代码，再打包。** 版本号、源码、安装器文案和会进入安装包的 `elearning-sync/README.md` 必须先定稿。打包后若又改了被打包内容，只重建受影响的平台和下游产物。
2. **环境只准备一次。** 保留 `.packaging-venv`、Gradle 用户缓存、Android SDK、Inno Setup、Windows SDK SignTool 和 GitHub CLI；不要在每次发布时重装或临时下载。
3. **默认使用增量缓存。** PyInstaller 默认不加 `--clean`，Gradle 默认不执行 `clean`。只有 22.7 列出的失效条件成立时才做干净重建。
4. **两端并行。** 桌面测试/打包与 Android lint/构建互不依赖，应在两个终端或并行工具调用中同时运行。Inno Setup 必须等待桌面 dist 和桌面签名完成。
5. **按改动范围验证。** 不改 Android 就不重复构建 Android；只改仓库文档就不生成任何二进制。版本发布同时提升双端版本时，两端都必须重建。
6. **标签不可变。** 先验证产物，再创建新标签并原子推送。公开标签或 Release 有错误时发布新的补丁版本，禁止移动旧标签或静默替换公开资产。
7. **凭据不进命令历史。** Android 密钥口令只在 `local.properties`；Windows 代码签名证书优先导入证书库后按指纹使用；GitHub 使用 `gh auth login` 的凭据存储。禁止把令牌、PFX 口令或 keystore 复制到脚本和发布说明。

### 22.2 一次性准备

本节命令统一要求 PowerShell 7.3+。PowerShell 7 能把 PyInstaller、Gradle、ISCC、SignTool、Git 和 `gh` 的非零退出码转换为终止错误，防止命令失败后继续签名或上传旧产物。每个并行发布终端都必须先运行：

```powershell
if ($PSVersionTable.PSVersion -lt [version]"7.3") {
    throw "快速发布 SOP 要求 PowerShell 7.3+"
}
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $true
```

只在新机器或工具升级时安装 GitHub CLI 与 Inno Setup。系统有 `winget` 时执行：

```powershell
winget install --exact --id GitHub.cli
winget install --exact --id JRSoftware.InnoSetup
```

没有 `winget` 时，从 GitHub CLI 官方 Releases 安装 MSI，并从 Inno Setup 官方下载页安装；不得使用来源不明的打包工具。安装完成后执行：

```powershell
gh auth login -h github.com -p https --web
gh auth status
```

安装 Windows SDK，以获得 `signtool.exe`。正式发布 Python 环境只创建一次；当前机器可以复用被 `.gitignore` 排除的 `elearning-sync/.packaging-venv`：

```powershell
cd elearning-sync
python -m venv .packaging-venv
.\.packaging-venv\Scripts\python -m pip install -U pip
.\.packaging-venv\Scripts\python -m pip install -r requirements.txt pytest pyinstaller
.\.packaging-venv\Scripts\python -c "from PySide6.QtMultimedia import QMediaPlayer; from PySide6.QtPdf import QPdfDocument"
```

每个 PowerShell 会话只设置路径，不重新安装：

```powershell
$Repo = (Resolve-Path "<仓库根目录>").Path
$Desktop = Join-Path $Repo "elearning-sync"
$Android = Join-Path $Repo "android-app"
$Repository = "lsx626/fuxiaoxue"
$Python = Join-Path $Desktop ".packaging-venv\Scripts\python.exe"
$Iscc = @(
    (Get-Command ISCC.exe -ErrorAction SilentlyContinue).Source
    (Join-Path $env:LOCALAPPDATA "Programs\Inno Setup 6\ISCC.exe")
    $(if (${env:ProgramFiles(x86)}) {
        Join-Path ${env:ProgramFiles(x86)} "Inno Setup 6\ISCC.exe"
    })
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
if (-not $Iscc) { throw "未找到 Inno Setup 6 的 ISCC.exe" }
$SignTool = (Get-Command signtool.exe -ErrorAction SilentlyContinue).Source
$env:JAVA_HOME = "<JDK 17 或 Android Studio jbr>"
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE ".gradle"
```

如果 `signtool.exe` 不在 PATH，获得可信 Windows 代码签名证书后再把 `$SignTool` 设置为本机 Windows SDK 中的实际路径；不要把本机绝对路径写入仓库。`android-app/local.properties` 必须提前配置 SDK 与既有发布密钥。包名已经公开后绝不能重新生成 Android keystore，否则旧用户无法升级；密钥必须离线备份。

### 22.3 改动到重建范围的映射

| 本次改动 | 桌面 pytest / PyInstaller | Inno 安装器 | Android lint / APK / AAB |
|---|---|---|---|
| 仅 `AGENTS.md`、根 `README.md` 或非打包文档 | 不需要 | 不需要 | 不需要 |
| 仅 Release Notes | 不需要 | 不需要 | 不需要 |
| `elearning-sync/README.md` 或 `installer/setup.iss` | 不需要重新生成 dist | 必须 | 不需要 |
| 桌面 Python、requirements、spec 或 `build_assets` | 必须 | 必须 | 不需要 |
| Android Kotlin、资源、Manifest 或 Gradle 配置 | 不需要 | 不需要 | 必须 |
| 产品版本同时升级 | 必须 | 必须 | 必须，并递增 `versionCode` |
| 签名证书或签名策略变化 | 重新签名；必要时重打包 | 必须重新编译/签名 | 重新构建并验证签名 |

`build_assets/app.ico` 同时进入桌面 EXE 和安装器，因此修改它要重建两者。`elearning-sync/README.md` 被 `InfoBeforeFile` 收入安装器，修改它只需重编译安装器。任何不确定的生成物都视为失效，不要为了省几分钟发布无法证明来源的旧二进制。

### 22.4 快速发布顺序

下面以 `$Version = "1.0.6"` 为例。不要照抄旧版本号：

#### A. 预检和版本冻结

```powershell
cd $Repo
$Version = "1.0.6"
$Tag = "v$Version"

git fetch origin --prune --tags
if ((git branch --show-current) -ne "main") { throw "必须从 main 分支发布" }
$Origin = git remote get-url origin
if ($Origin -notmatch 'github\.com[:/]lsx626/fuxiaoxue(?:\.git)?$') {
    throw "origin 不是 lsx626/fuxiaoxue：$Origin"
}
git status --short
git diff --check
git log -1 --oneline
git remote -v
```

一次性同步这些版本入口，然后再开始构建：

- `elearning-sync/VERSION`
- `elearning-sync/fudan_sync/__init__.py`
- `elearning-sync/installer/setup.iss` 的版本和输出文件名
- 根 README 的展示版本
- `android-app/README.md` 的展示版本
- Android `versionName`，并严格递增 `versionCode`

用 `rg` 检查残留旧版本，不要靠肉眼逐个打开：

```powershell
rg -n "versionName|versionCode|MyAppVersion|OutputBaseFilename|__version__|当前版本" `
  README.md android-app elearning-sync AGENTS.md
```

先提交候选源码，使构建来源有明确 commit。此时不要创建标签。下面的文件名只是示例，必须替换为本轮逐项审阅过的完整路径列表，禁止无审阅地执行 `git add -A`：

```powershell
git add -- AGENTS.md README.md
git diff --cached --check
git diff --cached --stat
git commit -m "release: 发布复小学 v$Version"
$ReleaseCommit = git rev-parse HEAD
if (git status --porcelain) { throw "候选提交后工作区不干净" }
```

#### B. 并行验证

如果 `requirements.txt` 相对上一发布版本发生变化，必须先同步打包环境；`PyInstaller --clean` 只清分析缓存，不会安装或升级依赖：

```powershell
& $Python -m pip install -r (Join-Path $Desktop "requirements.txt") pytest pyinstaller
& $Python -m pip check
```

桌面发生变化时，在终端 A 运行：

```powershell
cd $Desktop
$env:QT_QPA_PLATFORM = "offscreen"
$env:PYTHONDONTWRITEBYTECODE = "1"
& $Python -m pip check
& $Python -c "from PySide6.QtMultimedia import QMediaPlayer; from PySide6.QtMultimediaWidgets import QVideoWidget; from PySide6.QtPdf import QPdfDocument"
& $Python -m pytest -q -rs -p no:cacheprovider
```

Android 发生变化时，在终端 B 同时运行：

```powershell
cd $Android
.\gradlew.bat lintDebug assembleRelease bundleRelease
```

只有连接了设备/模拟器且 Android 行为或 UI 有变化时追加：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

没有设备时必须在发布说明中写明未运行，不能伪造通过。若仅改文档，跳过 B-E，提交后只执行 `git push origin main`；不要创建新版本、标签或 Release。

#### C. 增量构建 Windows

```powershell
cd $Desktop
& $Python -m PyInstaller --noconfirm "复小学.spec"
```

不要默认加 `--clean`。PyInstaller 会根据源码和 spec 更新 `build/`，通常明显快于从零分析依赖。构建后核对体积、文件数和关键插件：

```powershell
$Dist = Join-Path $Desktop "dist\复小学"
$Files = Get-ChildItem -LiteralPath $Dist -File -Recurse
[pscustomobject]@{
    Files = $Files.Count
    MiB = [math]::Round((($Files | Measure-Object Length -Sum).Sum / 1MB), 1)
}

@(
  "_internal\PySide6\Qt6Multimedia.dll",
  "_internal\PySide6\Qt6Pdf.dll",
  "_internal\PySide6\plugins\multimedia\ffmpegmediaplugin.dll"
) | ForEach-Object {
    if (-not (Test-Path (Join-Path $Dist $_))) { throw "打包缺少 $_" }
}
```

用隔离的 AppData 做 8 秒启动烟测，避免污染真实用户数据。开始前先关闭所有正在运行的“复小学”实例；程序带有单实例保护，旧实例存在时新进程会立即退出，从而造成假失败。隔离目录位于已忽略的 `.packaging-runtime`，可留给排障；需要清理时必须先解析并确认目标仍位于该目录内：

```powershell
$SmokeRoot = Join-Path $Desktop ".packaging-runtime\smoke-$Version"
New-Item -ItemType Directory -Force -Path $SmokeRoot | Out-Null
$SmokeConfig = Join-Path $SmokeRoot "config.yaml"
$OldAppData = $env:APPDATA
$OldLocalAppData = $env:LOCALAPPDATA
try {
    $env:APPDATA = $SmokeRoot
    $env:LOCALAPPDATA = $SmokeRoot
    $Process = Start-Process `
      -FilePath (Join-Path $Dist "复小学.exe") `
      -ArgumentList "--config `"$SmokeConfig`"" `
      -WorkingDirectory $SmokeRoot `
      -PassThru
    Start-Sleep -Seconds 8
    if ($Process.HasExited) { throw "打包程序提前退出：$($Process.ExitCode)" }
    Stop-Process -Id $Process.Id
} finally {
    $env:APPDATA = $OldAppData
    $env:LOCALAPPDATA = $OldLocalAppData
}
```

#### D. Windows 代码签名与安装器

当前公开的 `v1.0.5` 没有 Authenticode 签名。获得可信代码签名证书后，先将 PFX 安全导入 `Cert:\CurrentUser\My`，后续只按证书指纹签名，不在命令中传明文密码：

```powershell
$CertThumbprint = "<代码签名证书 SHA-1 指纹>"
$Timestamp = "http://timestamp.digicert.com"
$AppExe = Join-Path $Dist "复小学.exe"
$Setup = Join-Path $Desktop "installer\release\复小学-Setup-v$Version.exe"
if (-not $SignTool) { throw "未找到 Windows SDK 的 signtool.exe" }

& $SignTool sign /sha1 $CertThumbprint /s My /fd SHA256 /td SHA256 /tr $Timestamp $AppExe
& $SignTool verify /pa /all /v $AppExe

& $Iscc (Join-Path $Desktop "installer\setup.iss")

& $SignTool sign /sha1 $CertThumbprint /s My /fd SHA256 /td SHA256 /tr $Timestamp $Setup
& $SignTool verify /pa /all /v $Setup
```

顺序必须是“签桌面 EXE -> 编译 Inno -> 签安装器”；后续重建会使旧签名失效。没有 Windows 证书时直接编译安装器，然后检查并如实记录 `NotSigned`：

```powershell
& $Iscc (Join-Path $Desktop "installer\setup.iss")
$Setup = Join-Path $Desktop "installer\release\复小学-Setup-v$Version.exe"
Get-AuthenticodeSignature -LiteralPath $Setup | Format-List Status,StatusMessage
```

不得为了消除“未知发布者”提示使用自签名证书冒充公开可信签名。

#### E. Android 签名验证与发布文件

Gradle release 任务会读取 `local.properties` 并使用既有 keystore。构建日志成功不等于使用了正确的发布证书，必须再验证：

```powershell
$Apk = Join-Path $Android "app\build\outputs\apk\release\app-release.apk"
$Aab = Join-Path $Android "app\build\outputs\bundle\release\app-release.aab"
$ApkSigner = "<Android SDK>\build-tools\<版本>\apksigner.bat"
$ExpectedCert = "1dc096a7647e931084dba2387487b6283f407c83b1feadb5b329cae70f1ef077"

$VerifyApk = (& $ApkSigner verify --verbose --print-certs $Apk 2>&1) -join [Environment]::NewLine
if ($LASTEXITCODE -ne 0 -or $VerifyApk -notmatch $ExpectedCert) {
    throw "APK 未使用正式发布证书，禁止上传"
}
$VerifyApk

$VerifyAab = (& "$env:JAVA_HOME\bin\jarsigner.exe" -verify -verbose -certs $Aab 2>&1) -join [Environment]::NewLine
if ($LASTEXITCODE -ne 0) { throw "AAB 签名验证失败" }

$AabCert = (& "$env:JAVA_HOME\bin\keytool.exe" -printcert -jarfile $Aab 2>&1) -join [Environment]::NewLine
if ($LASTEXITCODE -ne 0 -or (($AabCert -replace ':', '') -notmatch $ExpectedCert)) {
    throw "AAB 未使用正式发布证书，禁止上传"
}
```

预期 APK 证书 SHA-256 为本文件第 21 节记录的指纹。若出现 debug 证书、指纹变化、`versionName` 错误或 `versionCode` 未递增，立即停止发布。既有包名绝不能用新 keystore 覆盖发布。

将附件复制到被忽略的 `release/`，统一使用 ASCII 文件名，避免上传工具和 URL 编码问题：

```powershell
$ReleaseDir = Join-Path $Repo "release"
New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null
$SetupArtifact = Join-Path $ReleaseDir "fuxiaoxue-Setup-v$Version.exe"
$ApkArtifact = Join-Path $ReleaseDir "fuxiaoxue-v$Version.apk"
$AabArtifact = Join-Path $ReleaseDir "fuxiaoxue-v$Version.aab"
Copy-Item $Setup $SetupArtifact -Force
Copy-Item $Apk $ApkArtifact -Force
Copy-Item $Aab $AabArtifact -Force

$Artifacts = @(
    foreach ($Path in @($SetupArtifact, $ApkArtifact, $AabArtifact)) {
        $File = Get-Item -LiteralPath $Path
        [pscustomobject]@{
            Name = $File.Name
            Path = $File.FullName
            Size = [int64]$File.Length
            Sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
        }
    }
)
$Artifacts | Format-Table Name, Size, Sha256
```

把三个 SHA-256 写入 Release Notes。AAB 用于应用商店，不是用户直接安装包；面向用户的 Android 下载项是 APK。

#### F. 标签、原子推送和 Release

在标签前做最后一次轻量检查。若构建后只补了不进入安装包的 `AGENTS.md` 发布记录，可以追加一个文档提交；若改了 Python、Android、spec、资源或 `elearning-sync/README.md`，返回映射表重建对应产物。

```powershell
cd $Repo
git status --short
git diff --check
git log -1 --oneline
if (git status --porcelain) { throw "发布前工作区不干净" }
if (git tag -l $Tag) { throw "标签 $Tag 已存在，禁止覆盖" }
if ((git branch --show-current) -ne "main") { throw "必须从 main 分支发布" }

$Head = git rev-parse HEAD
git tag -a $Tag -m "复小学 $Tag"
$TagCommit = git rev-list -n 1 $Tag
if ($TagCommit -ne $Head) { throw "标签没有指向当前 HEAD" }

git push --atomic origin `
  "HEAD:refs/heads/main" `
  "refs/tags/${Tag}:refs/tags/${Tag}"

$RemoteMain = ((git ls-remote origin "refs/heads/main") -split '\s+')[0]
$RemoteTag = ((git ls-remote origin "refs/tags/$Tag^{}") -split '\s+')[0]
if ($RemoteMain -ne $Head -or $RemoteTag -ne $Head) {
    throw "远端 main、标签和本地 HEAD 不一致"
}
```

用 GitHub CLI 一次创建**草稿** Release 并上传三个附件。发布说明先写入本地忽略目录，例如 `.packaging-runtime/release-notes-v$Version.md`，严禁包含令牌、Cookie 或真实课程信息：

```powershell
$Notes = Join-Path $Desktop ".packaging-runtime\release-notes-v$Version.md"
$ArtifactPaths = @($Artifacts | ForEach-Object Path)
gh release create $Tag $ArtifactPaths `
  --repo $Repository `
  --title "复小学 $Tag" `
  --notes-file $Notes `
  --verify-tag `
  --draft
```

对草稿执行机器校验；名称、数量、大小、上传状态、SHA-256 或草稿状态有任何不一致都会停止：

```powershell
$RemoteRelease = gh api "repos/$Repository/releases/tags/$Tag" | ConvertFrom-Json
if (-not $RemoteRelease.draft -or $RemoteRelease.prerelease) {
    throw "Release 必须仍为 draft 且不能是 prerelease"
}
if ($RemoteRelease.tag_name -ne $Tag) { throw "Release 标签不匹配" }
if (@($RemoteRelease.assets).Count -ne $Artifacts.Count) {
    throw "远端附件数量不匹配"
}

foreach ($Artifact in $Artifacts) {
    $Matches = @($RemoteRelease.assets | Where-Object { $_.name -eq $Artifact.Name })
    if ($Matches.Count -ne 1) { throw "附件缺失或重名：$($Artifact.Name)" }
    $Remote = $Matches[0]
    $ExpectedDigest = "sha256:$($Artifact.Sha256)"
    if ($Remote.state -ne "uploaded" -or
        [int64]$Remote.size -ne $Artifact.Size -or
        [string]$Remote.digest -ne $ExpectedDigest) {
        throw "附件校验失败：$($Artifact.Name)"
    }
}

$RemoteRelease | Select-Object html_url, tag_name, draft, prerelease
$RemoteRelease.assets | Select-Object name, size, state, digest
```

机器校验通过后，仍保持草稿，打开输出的 `html_url` 人工检查标题、说明和附件。确认无误后单独执行下面的公开命令；输入完整标签是最后一道防误触确认：

```powershell
$Confirmation = Read-Host "确认公开 $Tag；请输入完整标签"
if ($Confirmation -cne $Tag) { throw "已取消公开" }
gh release edit $Tag --repo $Repository --draft=false --latest

$Published = gh api "repos/$Repository/releases/tags/$Tag" | ConvertFrom-Json
if ($Published.draft -or $Published.prerelease) { throw "Release 公开状态异常" }
$Published.html_url
```

必须确认远端 asset 的 `digest` 与本地 SHA-256 一致，再向用户报告完成。

### 22.5 上传失败的快速恢复

- Release 草稿已创建但缺少附件：不要重建或重打标签，确认本地 staging 文件仍与清单一致后用 `gh release upload $Tag <文件>` 续传，不要默认加 `--clobber`。
- Release 草稿中存在错误的同名附件：先核对本地路径、大小和 SHA-256，再删除错误附件并重传；只有明确要替换草稿中的同名附件时才使用 `--clobber`。已经公开的附件错误应发布新的补丁版本。
- 发布说明有误：使用 `gh release edit $Tag --notes-file $Notes`，不需要重新上传二进制。
- 标签尚未推送且指错 commit：可在本地删除后重建；标签一旦推送或 Release 一旦公开，禁止移动，改发新的补丁版本。
- `main` 已推送但 Release 创建失败：确认远端标签正确后直接重跑 `gh release create`。
- 上传后 digest 不一致：先删除错误 asset，再从已验证的本地 staging 文件重传；不要从不明旧目录寻找同名产物。
- 发现代码缺陷：停止发布；修复后只重跑受影响平台的测试、打包和签名。公开 Release 已有人下载时应发新补丁版本，不要静默替换。

### 22.6 最短检查清单

- [ ] 工作区只有预期改动，敏感文件和生成物仍被忽略。
- [ ] 版本入口一致，Android `versionCode` 已递增。
- [ ] 只运行改动范围要求的测试；双端任务已并行。
- [ ] 构建来自已记录的 commit，构建后没有改动被打包源码。
- [ ] Windows EXE/安装器和 Android APK 的签名状态已实际验证。
- [ ] EXE 启动烟测通过，APK 包名/版本/证书指纹正确。
- [ ] AAB 完整性与证书指纹验证通过。
- [ ] SHA-256 已写入 Release Notes。
- [ ] 注释标签和 `main` 已原子推送到同一目标 commit。
- [ ] GitHub Release 非 draft、非 prerelease，三个附件状态为 uploaded 且远端 digest 匹配。
- [ ] 发布说明准确写出未运行的测试、未签名状态和仍未实现的功能。

### 22.7 必须干净重建的条件

桌面端满足任一条件时使用：

```powershell
& $Python -m PyInstaller --clean --noconfirm "复小学.spec"
```

如果 `requirements.txt` 有变化，必须先按 22.4 B 同步 `.packaging-venv`，再执行干净构建；`--clean` 不能替代依赖安装。

- Python、PyInstaller、PySide6/Qt 或其他打包依赖版本改变。
- `requirements.txt`、spec 的 hiddenimports/datas/binaries 或 Qt 插件集合改变。
- 从另一台机器、另一虚拟环境或不同架构生成正式包。
- 上次构建被中断、`build/` 来源不明、插件缺失、体积异常或运行烟测失败。
- 安全修复涉及动态导入/二进制依赖，增量分析无法充分证明完整性。

Android 只有在 Gradle 缓存损坏、AGP/Kotlin/Gradle 大版本切换或增量结果明显异常时才运行 `.\gradlew.bat clean`。普通 Kotlin、资源和版本号变化让 Gradle 自己做增量构建。Inno Setup 本身编译很快且必须在 dist/签名变化后重跑，但编译器只安装一次。

## 23. UI/UX 发布标准

所有新增功能必须具备完整状态，而不是只有 happy path：

- 默认、hover/focus/pressed、disabled、loading、success、empty、error。
- 操作可取消时提供取消；不能立即取消时说明正在安全停止。
- 破坏性操作在动作附近解释影响，并允许取消。
- 进度文字和数值一致，不显示虚假的 100%。
- 错误告诉用户发生了什么、数据是否安全、下一步能做什么。
- 图标优先使用现有图标体系；未知图标有 tooltip/contentDescription。
- 不用文字按钮代替已形成共识的播放、暂停、停止、前后跳转等图标，但必须保留无障碍名称。
- 不用弹窗掩盖可以在当前上下文解决的错误；长期任务不要冻结主界面。
- 不在界面内堆叠开发说明、快捷键教程或实现细节。

UI 改动完成后必须实际运行并截图检查关键尺寸；仅阅读代码不能证明按钮没有遮挡。

## 24. 安全与隐私标准

- 只同步当前账户有权访问的内容，遵守学校和课程资料使用规则。
- 不新增遥测、上传、外链分享或第三方分析，除非用户明确授权且有隐私说明。
- 日志默认脱敏；URL query、headers、Cookie 和认证响应不完整输出。
- 所有远端文件名都视为不可信输入，防目录穿越、保留名和覆盖攻击。
- Office 自动化一律禁宏、只读、禁提示；预览不执行文档内代码。
- HTML 预览不得自动执行脚本或任意导航。
- Android FileProvider 路径应最小化，Manifest 不应长期保留无必要的 cleartext、存储、前台服务或开机权限。
- `allowBackup` 与 Keystore 恢复后的失效行为需要显式设计；解密失败应清理失效密文并重新登录。
- 删除本地课程资料、数据库迁移、卸载清理等操作应优先可恢复或明确确认。

当前 `password_login.py` 在找不到 CAS 回调时，会把 `authnEngine` HTML 前 200 字符拼进异常信息；该片段可能含跳转或认证上下文。修复前不要把此异常全文写入公开日志/Issue，后续应只保留 HTTP 状态和稳定错误码并补脱敏测试。

## 25. 已知缺口和优先级

### v1.0.5 已解决的发布阻断

1. 桌面 full 同步会无条件 upsert，再合并 full 调度判定。
2. 桌面远端路径逐组件清洗、限制在课程根目录内，并稳定避让跨轮次同名文件。
3. 已调度的同大小变更文件不再被下载器误跳过；full 会真正覆盖全部未排除文件。
4. prune 删除增加课程目录边界，旧数据库中的越界路径不会被删除。
5. 完整 Qt 发布环境已验证 QtMultimedia/QtPdf，桌面测试通过且无跳过。
6. 安装器卸载不再无提示递归删除 AppData 用户数据。

### P0：完整产品要求验收前必须处理

1. ~~Android 实现应用内 PDF/Office/图片/文本预览和音视频播放器~~ —— `v1.0.6`～`v1.0.9` 已完成。
2. ~~Android 下载改为临时文件、完整性校验和原子替换~~ —— `v1.0.10` 已完成（`.part` + 续传 + 长度校验 + 原子改名 + 同名避让 + 重试）。
3. ~~Android Canvas API 实现 Link 分页、限流、可区分错误~~ —— `v1.0.10` 已完成；界面在任何失败路径都不会显示「同步完成」。

### P0-新：`v1.0.10` 之后仍需验证/补齐

1. ~~Android 端真机/模拟器验收~~ —— `v1.0.10` 已在 API 36.1 模拟器上跑通全部 23 个插桩用例（见 17.1）。仍建议发布前在**真机**（尤其 Android 8/9 老设备与低内存设备）复跑一次 `OfficeRendererInstrumentedTest`。
2. 桌面端 `v1.0.7` 未打包：`VERSION`/`setup.iss` 已升到 1.0.7，但未执行 PyInstaller + Inno Setup + 签名流程，未产出安装包，也未做 EXE 启动烟测。
3. Android `WorkManager` 只有「保存了密码」时才会真正同步；未保存密码的账号仍只能靠手动同步。

### P1：高优先级可靠性

- ~~Android 抓取能力与桌面端对齐：目录、模块、页面、作业、公告、大纲和安全删除。~~ —— `v1.0.10` 已完成（`CourseCrawler`；正文归档仍只有桌面端做）。
- ~~Android UI 同步与 WorkManager 增加全局互斥；失败使用 retry/failure 的正确语义。~~ —— `v1.0.10` 已完成（`SyncGate` + `Result.retry/failure`）。
- ~~Android 数据库使用非破坏性迁移；增量加入时间戳和本地存在性。~~ —— `v1.0.10` 已完成（schema v2）。
- ~~Android 修复文件页/学期页 `remember` 缓存导致的数据滞后。~~ —— `v1.0.10` 已完成（`dataVersion` 作为缓存 key）。
- ~~Android 退出登录取消周期工作；不记住密码时清理旧密码。~~ —— `v1.0.10` 已完成。
- ~~Android 同时接受两种 Canvas 会话 Cookie~~ —— `v1.0.10` 已完成；User-Agent 已在 `v1.0.5` 改为跟随构建版本。
- ~~桌面统一排除扩展名规范~~ —— `v1.0.7` 已完成（`_normalize_ext` 统一为小写 `.ext`）。
- ~~桌面 CLI `login --method cookie` 成功后持久化 auth.method。~~ —— `v1.0.7` 已完成。
- ~~桌面认证失败信息移除 `authnEngine` HTML 片段~~ —— `v1.0.7` 已完成（只保留 HTTP 状态与响应长度）。
- 桌面课程本地目录定位复用同步引擎的路径清洗规则。
- ~~桌面退出流程可靠等待协作停止，避免运行中的 QThread 被销毁。~~ —— `v1.0.7` 已完成（等待并延后退出，绝不强杀）。
- ~~StateStore 写事务异常时 rollback。~~ —— `v1.0.7` 已完成。
- Android 真机/模拟器插桩测试尚未在本环境跑通（见 P0-新第 1 条），发布前必须补跑。

### P2：一致性和维护性

- ~~Android README 已在 `v1.0.5` 修正版本、SQLite 和后台同步描述；仍须在内置预览完成后更新预览说明。~~ —— 预览、同步、安全说明已在 v1.0.10～v1.0.12 补齐。
- 根 README 的 Releases 链接已在 `v1.0.5` 对齐实际 `origin`。
- 修正文档中“HTML 完全离线”的表述，或真正下载依赖资源。
- ~~页面归档改为稳定覆盖/版本化，避免每轮产生 `(1)/(2)` 重复文件。~~ —— `v1.0.12` 已完成（稳定命名 + 原子覆盖 + 清理历史副本）。
- 记录可复现的桌面发布依赖锁定清单；不要用整套本机 conda 环境充当锁文件。
- ~~收窄 Android FileProvider、清理无用权限和未实现的 Service/Receiver 声明。~~ —— `v1.0.12` 已完成权限与 FileProvider 收窄（Service/Receiver 声明本就不存在）。
- 为认证、数据库、下载、Worker 和预览补充 Android 单元/集成测试。
- ~~建立 CHANGELOG~~ —— `v1.0.12` 已新增根目录 `CHANGELOG.md`。仍缺：CI（GitHub Actions）、可复现的依赖锁定清单、统一 pytest 配置。

修复缺口时一次只解决清晰范围，先加测试再改行为，避免同时重写两端架构。

## 26. 常见改动操作手册

### 26.1 新增一种桌面预览格式

1. 在 `_detect_type()` 增加扩展名、MIME 或魔数识别，处理扩展名冲突。
2. 选择现有预览类别，确需新增时才增加 widget/loader。
3. 设置大小/行列/页数上限，重活放后台。
4. 把可选依赖加入 `requirements.txt`，懒加载并提供缺依赖提示。
5. 若打包时动态导入，更新 `复小学.spec` hiddenimports/datas/binaries。
6. 增加合成文件测试、损坏文件测试、反复打开关闭测试和打包后人工验收。

### 26.2 修改认证

1. 对照 PC 与 Android 的请求字段、Cookie、跳转和 RSA 编码。
2. 用 mock 响应覆盖正常、密码错误、验证码/协议变化、缺字段和超时。
3. 确认日志无敏感数据，响应均关闭。
4. 验证桌面四种认证方法和旧配置回退。
5. 验证 Android 记住/不记住、自动登录、退出和 Worker。

### 26.3 修改同步或删除逻辑

1. 先画清“发现成功”和“空结果”的区别。
2. 保持 file ID 去重、路径安全和删除闸门。
3. 测试新文件、变更文件、本地丢失、远端删除、API 失败、full、停止和磁盘不足。
4. 检查数据库状态和实际文件系统结果一致。
5. 绝不使用真实课程目录做破坏性测试。

### 26.4 修改数据库 schema

1. 写出旧版本到新版本的显式迁移。
2. 用包含真实形状但脱敏/合成的数据副本测试迁移。
3. 测试中途失败不会半迁移或清空用户数据。
4. 更新查询、模型、统计、备份/恢复说明和本文件。

### 26.5 修改主界面

1. 保持网络/磁盘工作在线程或协程 IO 上。
2. 补齐 loading/empty/error/disabled/busy 状态。
3. 验证最低尺寸、DPI、长文本和键盘/触控。
4. 实际截图检查，不以布局代码推断结果。
5. 确保主要操作始终可见，尤其是“立即同步”。

## 27. 完成定义

一项改动只有同时满足以下条件才算完成：

- 行为满足用户要求，且没有把未实现功能写成已实现。
- 保持本文件中的硬性不变量。
- 对新增或修复行为有与风险相称的自动化测试。
- 相关测试、构建、静态检查实际运行并记录结果；不能运行的部分明确说明原因。
- GUI 改动经过桌面/设备实际视觉检查和关键交互检查。
- 不含凭据、真实课程资料、本机配置、构建垃圾或无关格式化。
- README、版本、安装器、发布说明与实现一致。
- `git diff --check` 通过，`git status --short` 中只有预期文件。
- 用户要求上传时，提交信息准确、推送目标核对无误，并反馈 commit/分支/远端。

## 28. 交接输出模板

后续 Codex 完成一轮工作时，应向用户简要报告：

1. 已修复/新增的用户可见结果。
2. 关键修改文件。
3. 实际运行的测试与结果。
4. 尚未解决的风险或环境限制。
5. 若已提交/推送：分支、commit SHA 和远端仓库。

不要只报告“代码已修改”；必须让下一位开发者能判断项目是否真的可运行、可测试、可发布。
