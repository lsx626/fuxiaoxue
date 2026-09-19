# 复小学 · Android 版

「复小学」复旦大学 eLearning (Canvas LMS) 课程文件同步工具的 Android 客户端，
与 [桌面端](../elearning-sync/) 共用同一套 eLearning 认证与同步协议，
UI 风格、配色与应用图标保持一致。

## 功能特性

- **统一身份认证登录**：UIS 账号密码（RSA 加密），会话持久化，打开即同步
- **课程列表**：学期、文件数、已下载大小一目了然
- **文件列表**：按课程查看全部文件与下载状态
- **应用内文件预览**：PDF / Word / Excel / PPT（doc/docx/ppt/pptx/xls/xlsx）/ 图片
  （含 GIF 动图、HEIF）/ 文本 / 音视频全部应用内打开，不跳转第三方应用；
  PDF 与 Office 为**纵向连续滚动**（下拉式翻页）+ 双指/双击缩放，逐页按需渲染并做
  LRU 位图缓存，大文档不 OOM；Office 保留形状坐标、逐段字符格式、内嵌图片与表格
  （Word 表格可跨页），是完整页面渲染而非纯文字提取
- **文件分享**：系统 ShareSheet 分享本地文件（仅授予临时只读 URI 权限），
  OOXML 文件类型正确声明，接收方可识别
- **后台同步**：WorkManager 定时增量同步（默认 15 分钟）
- **下载通知**：新文件下载完成时发送系统通知
- **存储管理**：按学期 / 课程筛选删除文件，释放空间

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
│       │   ├── ui/                       # LoginScreen / HomeScreen / Theme
│       │   ├── util/                     # Prefs、SecurePrefs、FileUtils
│       │   └── worker/                   # SyncWorker、通知
│       └── res/
│           ├── drawable/ic_launcher.xml         # 学士帽图标矢量
│           ├── drawable-anydpi-v26/ic_launcher.xml  # 自适应图标
│           ├── values/strings.xml               # app_name = 复小学
│           └── values/themes.xml                # Theme.FuXiaoXue
└── release.keystore                     # 本机私有签名文件（不入库）
```

## 应用信息

- **应用名称**：复小学
- **包名**：`edu.fudan.elearning.sync`
- **版本**：1.0.12（versionCode 13）
- **同步**：Link 分页 + 限流退避；`.part` 断点续传 + 长度校验 + 原子落盘 + 同名避让；抓取范围与桌面端对齐（目录树、模块、页面、作业、公告、大纲中的文件引用），只有课程文件主列表完整成功时才判定远端删除
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
| 背景 | `#F6F7FC` | `0xFFF6F7FC` |
| 图标 | 圆角靛蓝方形 + 白色学士帽 | 同一矢量几何 |
| 状态栏 | — | `#4338CA` |

图标为同一学士帽几何（帽板 / 帽箍 / 帽穗），桌面端 `build_assets/app.ico`
与 Android `ic_launcher.xml` 共用设计。

## Android 权限

| 权限 | 用途 |
|------|------|
| `INTERNET` | 访问 eLearning 平台 |
| `ACCESS_NETWORK_STATE` | 检查网络状态 |
| `POST_NOTIFICATIONS` | 下载 / 同步通知（Android 13+） |
| `WAKE_LOCK` | WorkManager 执行同步时保持任务运行 |
| `FOREGROUND_SERVICE` | 预留声明；当前没有自定义前台 Service |
| `FOREGROUND_SERVICE_DATA_SYNC` | 预留声明；当前没有数据同步前台 Service |
| `RECEIVE_BOOT_COMPLETED` | 预留声明；当前没有自定义开机 Receiver |
| `READ_EXTERNAL_STORAGE` | 旧系统兼容声明；应用专属下载目录不依赖此权限 |
| `WRITE_EXTERNAL_STORAGE` | 旧系统兼容声明；应用专属下载目录不依赖此权限 |

当前周期同步由 WorkManager 管理。未实现的前台 Service、开机 Receiver 和非必要
存储权限属于待清理项，不能据此宣称应用已有前台服务或自定义开机自启能力。

## License

MIT
