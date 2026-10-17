# 复小学 · Android 版

「复小学」复旦大学 eLearning (Canvas LMS) 课程文件同步工具的 Android 客户端，
与 [桌面端](../elearning-sync/) 共用同一套 eLearning 认证与同步协议，
UI 风格、配色与应用图标保持一致。

## 功能特性

- **统一身份认证登录**：UIS 账号密码（RSA 加密）；选择记住密码后启动自动重登并同步，无网络时仍可读本地资料
- **课程列表**：学期、文件数、已下载大小一目了然
- **文件列表**：按课程查看全部文件与下载状态
- **应用内文件预览**：PDF / Word / Excel / PPT（doc/docx/ppt/pptx/xls/xlsx）/ 图片
  （含 GIF 动图、SVG、HEIF，具体编码受设备支持限制）/ 文本 / 音视频全部应用内打开，不自动跳转第三方应用；
  PDF 与 Office 为**纵向连续滚动**（下拉式翻页）+ 双指/双击缩放，逐页按需渲染并做
  有预算的 LRU 位图缓存，减少大文档内存压力；Office 保留形状坐标、逐段字符格式、内嵌图片与表格
  （Word 表格可跨页），是完整页面渲染而非纯文字提取
- **文件分享**：系统 ShareSheet 分享本地文件（仅授予临时只读 URI 权限），
  OOXML 文件类型正确声明，接收方可识别
- **后台同步**：WorkManager 定时增量同步（默认 15 分钟）
- **下载通知**：新文件下载完成时发送系统通知
- **存储管理**：按学期 / 课程筛选删除文件，释放空间
- **现代资料库界面**：课程 / 动态 / 存储 / 设置四个页面，手机底部导航、平板侧栏，支持浅色/深色/跟随系统；全文搜索、文件状态筛选、排序与续读
- **阅读控制**：固定页码/缩放工具栏，PDF/Office 页间紧密连续，支持以手指位置为锚点的缩放和平移；文本分块布局与正文查找，图片缩放按钮，ZIP 类目录浏览；搜索进入预览后返回保留结果
- **账号、说明与关于**：登录后同步 eLearning 个人姓名和头像并缓存于本机，账号学号/工号继续展示；设置页内置登录、同步、阅读和任务提交说明；关于页列明用途、隐私、许可及课程资料版权归属
- **原生任务详情**：学习动态直接展示截止/开放/锁定时间、分值、提交次数、说明与图片、附件、评分标准、提交状态、分数和教师评语；原生支持多文件/文字/网址提交，不再使用 WebView
- **按下载方式同步**：所有课程的文件清单持续更新；进入课程后在「自动下载全部资料（含未来文件）」与「手动选择要下载的资料」之间二选一。手动模式支持多选、全选和清空

## 技术栈

- Kotlin + Jetpack Compose（Material 3）
- MVVM：`AppViewModel` + `StateFlow`
- OkHttp + 自实现 Canvas API（与桌面端 `fudan_sync` 协议同源）
- `SQLiteOpenHelper` 本地状态库（与桌面端数据库相互独立、结构不同）
- WorkManager 后台周期同步
- Office 预览：Apache POI 解析 doc/docx/ppt/pptx/xls/xlsx，自定义 Canvas 逐页
  渲染（形状坐标、逐段字符格式、内嵌图片、跨页表格）；Android 平台无 `java.awt`/`javax.xml.stream`/`javax.xml.catalog`，桩源码放 `app/src/awtstub/java/`，
  用 `--limit-modules java.base` 单独编译成 jar，以 `implementation` 同时进入编译期与运行期 classpath
  （`android.jar` 完全不含 java.awt，编译期符号只能由桩提供；曾实测 `runtimeOnly` 会导致编译期 `Cannot access class`）；`javax.xml.namespace` 由 android.jar
  提供，桩 jar 打包时 exclude 以免重复

## 项目结构

```text
android-app/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/edu/fudan/elearning/sync/
│       │   ├── App.kt                    # Application 入口
│       │   ├── MainActivity.kt           # Compose 宿主
│       │   ├── auth/UisAuthenticator.kt  # UIS 登录（RSA + id.fudan.edu.cn）
│       │   ├── awtstub/ (src)             # java.awt/javax.xml 桩源码 -> implementation jar
│       │   ├── data/                     # SQLiteOpenHelper、模型与 Repo
│       │   ├── network/                  # OkHttp、CanvasApi、CookieJar
│       │   ├── office/                   # Office 六格式页模型 + POI 解析 + 逐页渲染
│       │   ├── preview/                  # 统一预览路由、纵向翻页列表、各格式预览屏
│       │   ├── sync/                     # 同步引擎、下载管理
│       │   ├── ui/                       # AppContent 导航、独立功能页、共用组件与主题
│       │   ├── util/                     # Prefs、SecurePrefs、FileUtils
│       │   └── worker/                   # SyncWorker、通知
│       └── res/
│           ├── drawable/ic_launcher.xml         # 双页书品牌图标矢量
│           ├── drawable-anydpi-v26/ic_launcher.xml  # 自适应图标
│           ├── values/strings.xml               # app_name = 复小学
│           └── values/themes.xml                # Theme.FuXiaoXue
└── release.keystore                     # 本机私有签名文件（不入库）
```

## 应用信息

- **应用名称**：复小学
- **包名**：`edu.fudan.elearning.sync`
- **版本**：1.3.2（versionCode 22）
- **本版改进**：全面重做 UI 并拆分功能页；离线资料库、可取消下载、同步/删除互斥、搜索与预览返回状态、失败恢复与后台策略，详见 [重构验收记录](REFACTOR_AUDIT.md)。
- **数据库**：schema **v8** 新增课程勾选列 `courses.selected`，并保留 `files.selected`；v5 的 `files.error`、v6 的原文摘要缓存与既有课程、下载路径、截止日期和阅读进度都保留。
- **验证**：本版 JVM **141 passed**，无头 API 36 模拟器的 Compose 多点手势测试通过；`lintDebug`、`spotlessCheck`、debug/release APK 构建通过。真实设备用真实账号完成姓名/头像、紧凑动态、完全原生任务详情、文件选择器、无页间隙预览、课程/文件勾选和 schema v8 迁移冒烟验收。
- **同步**：Link 分页 + 限流退避；`.part` 断点续传 + 长度校验 + 原子落盘 + 同名避让；抓取范围与桌面端对齐。只有课程文件主列表完整成功时才判定远端删除，随后清理本地副本、断点和关联记录
- **最低 Android 版本**：8.0（API 26）
- **目标 Android 版本**：15（API 35）

## 开发环境搭建

### 前置要求

- Android Studio（Ladytail 或更新）
- Android SDK（compileSdk 36）
- JDK 17+（Gradle 与 Kotlin 均以 17 为目标）

### 构建运行

```bash
cd android-app
./gradlew assembleRelease     # 或在 Android Studio 中直接 Run
./gradlew installRelease      # 安装到已连接设备
./gradlew testDebugUnitTest   # JVM 单元测试（Office 解析层）
```

> 签名密钥 `release.keystore` 和密码配置都只保存在发布者本机，均不入库。
> 克隆后若未配置会自动回退 debug 签名以便构建，但该产物不能公开发布。

## 与桌面端的统一

| 项目 | 桌面端（PySide6） | Android 端（Compose） |
|------|-------------------|----------------------|
| 主色 | `#4F46E5` Indigo 600 | `0xFF4F46E5` |
| 悬停 / 深色 | `#4338CA` / `#312E81` | `0xFF4338CA` / `0xFF312E81` |
| 背景 | `#F6F7FC` | 浅色 `#F7F8FC` / 深色 `#10131C` |
| 图标 | 深蓝圆角底 + 向中央折叠的双页书 | 同一矢量几何 |
| 状态栏 | — | 随应用明暗主题调整图标对比度 |

图标为同一双页书几何，桌面端 `build_assets/app.ico`
与 Android `ic_launcher.xml` 共用设计。

## Android 权限

| 权限 | 用途 |
|------|------|
| `INTERNET` | 访问 eLearning 平台 |
| `ACCESS_NETWORK_STATE` | 检查网络状态 |
| `POST_NOTIFICATIONS` | 下载 / 同步通知（Android 13+） |

应用自身只声明上述三项权限，专属下载目录不需要通用存储权限。WorkManager 的库清单
可能合并其所需的唤醒锁/启动权限；应用没有自定义前台 Service 或开机 Receiver。
周期同步由 WorkManager 管理，只有保存密码的账号才能在会话失效后自动重新登录同步。

## License

MIT
