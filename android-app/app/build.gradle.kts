import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "edu.fudan.elearning.sync"
    compileSdk = 36

    defaultConfig {
        applicationId = "edu.fudan.elearning.sync"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.0.12"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // POI 方法数较多，需要 multidex
        multiDexEnabled = true
    }

    signingConfigs {
        create("release") {
            // 签名密钥与密码只放在 local.properties（不入库），克隆后若无配置则
            // 自动回退 debug 签名，保证仓库可独立构建。
            val props = Properties().apply {
                rootProject.file("local.properties").takeIf { it.exists() }
                    ?.inputStream()?.use { load(it) }
            }
            val store = file(props.getProperty("fudanSign.storeFile") ?: "../release.keystore")
            if (store.exists()) {
                storeFile = store
                storePassword = props.getProperty("fudanSign.storePassword") ?: ""
                keyAlias = props.getProperty("fudanSign.keyAlias") ?: "fudansync"
                keyPassword = props.getProperty("fudanSign.keyPassword") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.getByName("release")
            // 无发布密钥时回退 debug 签名，保证仓库克隆后可直接构建。
            signingConfig =
                if (releaseSigning.storeFile?.exists() == true) releaseSigning
                else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

// ============================================================================
// Java 平台桩（编译期与运行期都需要，但绝不能与 JDK / Android 平台类冲突）
// Android 平台没有 java.awt / javax.xml.stream / javax.xml.catalog，而 Apache
// POI（Office 解析）与 xmlbeans 的 API 签名与字节码都引用了它们。处理方式：
//   1) 编译期：用独立 JavaCompile 任务把桩源码编译成 jar，并以 implementation
//      加进 classpath——应用源码（如 SlideExtractor 引用 java.awt.Dimension）
//      与 POI 的 API 签名都靠它解析符号；android.jar 里没有这些类，不引入则报
//      Unresolved reference。
//   2) 运行期：该 jar 随 APK 一起进 dex，设备上 POI/xmlbeans 的字节码才能解析
//      到类定义。
// 三个硬性要点：
//   - javac 追加 --limit-modules java.base：隐藏 JDK 自带的完整 java.desktop /
//     java.xml 模块，否则源码级「package java.awt already exists」冲突。
//   - 编译期 classpath 上同时存在 JDK 的 java.awt 与本 jar 的同名类，符号解析
//     时以 JDK 为准；只要两者公共 API 签名一致即可（桩只实现 POI 解析路径用到
//     的少数方法，按运行期需要增量补齐）。
//   - javax.xml.namespace（QName / NamespaceContext）由 android.jar 提供，
//     打 jar 时必须 exclude，否则 checkDebugDuplicateClasses 报重复类。
// ============================================================================
// 已实证（2026-09-19，API 36 模拟器）：dexdump 可见 java.awt.* / javax.xml.*
// 共 33 个桩类被定义在 classes15.dex；OfficeRendererInstrumentedTest 对
// pptx/docx/xlsx/xls/ppt 五种格式的「解析 + Canvas 渲染」全部通过。
// ============================================================================
// 编译期符号解析：Kotlin（compileDebugKotlin）先于变体 javac 执行，拿不到 javac
// 产出的桩类，因此先用独立任务把 java.awt / javax.xml.* 编译成 jar。
val compileJavaStubs by tasks.registering(JavaCompile::class) {
    // 本任务没有 classpath 且 --limit-modules java.base，javax.xml.namespace 必须
    // 由同源集的桩源码提供（QName / NamespaceContext）；这两个类运行期由
    // android.jar 提供，打 jar 时 exclude，不会重复打包。
    source("src/awtstub/java")
    classpath = files()
    sourceCompatibility = "17"
    targetCompatibility = "17"
    options.compilerArgs = listOf("--limit-modules", "java.base", "-encoding", "UTF-8")
    destinationDirectory.set(layout.buildDirectory.dir("stubs/classes"))
}

// 打成 jar：kotlinc 不接受目录形式的 java.* 桩类，但接受 jar 形式。该 jar 以
// implementation 引入，既提供编译期符号，也进入 APK 供运行期加载。
val javaStubsJar by tasks.registering(Jar::class) {
    dependsOn(compileJavaStubs)
    archiveFileName.set("java-platform-stubs.jar")
    from(compileJavaStubs.map { it.destinationDirectory })
    // android.jar 已提供 javax.xml.namespace，无需打入本 jar。
    exclude("javax/xml/namespace/**")
}
dependencies {
    implementation(files(javaStubsJar))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.multidex:multidex:2.0.1")

    // Office 文档渲染：解析 doc/docx/ppt/pptx/xls/xlsx，由 Canvas 逐页绘制位图
    implementation("org.apache.poi:poi-ooxml:5.2.5") {
        exclude(group = "org.apache.xmlgraphics")
        exclude(group = "xml-apis")
    }
    implementation("org.apache.poi:poi-scratchpad:5.2.5") {
        exclude(group = "org.apache.xmlgraphics")
        exclude(group = "xml-apis")
    }

    // 应用内预览：图片解码（含 GIF 动图）、Media3 音视频播放；PDF 使用平台 PdfRenderer
        implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // JVM 单元测试：用真实合成夹具验证 Office 页模型提取（解析层不依赖 Android）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.apache.poi:poi-ooxml:5.2.5")
    testImplementation("org.apache.poi:poi-scratchpad:5.2.5")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.01.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
