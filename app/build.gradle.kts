import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 签名密码的来源，优先级：
 *   1. signing.properties（本地文件，已在 .gitignore 里 —— 仓库是公开的，密码不能进库）
 *   2. 环境变量（CI 从 GitHub Secrets 注入）
 */
val signingProps = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String =
    signingProps.getProperty(propKey) ?: System.getenv(envKey) ?: ""

android {
    namespace = "com.stronghold.shell"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stronghold.shell"
        minSdk = 24
        targetSdk = 34
        // versionCode 用 major×10000 + minor×100 + patch 自动推导，
        // 改 versionName 顺手就能算出 code，不会再出现「版本号涨了但 code 没动」
        // 导致覆盖安装被判成降级。
        versionCode = 20100            // 2.1.0
        versionName = "2.1.0"
        // 联机已合并进正式版，不再有 lab 变体
        buildConfigField("boolean", "ENABLE_MULTIPLAYER", "true")
        manifestPlaceholders["appLabel"] = "卫戍协议启动器"
        // 只保留 arm64（与游戏前端一致，体积最小）
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // ===== 只有一个构建变体 =====
    // 2.1.0 起合并了原来的 stable / lab：
    //   - lab 的多人联机入口直接进正式版（ENABLE_MULTIPLAYER = true）
    //   - 只保留 com.stronghold.shell 一个包名，CI 也只产一个 APK
    //   - 旧的 com.stronghold.shell.lab 不再更新；它的数据在独立包名下，读不到，
    //     装过 lab 版的人需要重新导入游戏包（已确认可以放弃这批用户）

    // 允许不压缩 app_bundle.zip，加速打包（可选）
    androidResources {
        // zip：游戏包本身已是压缩格式，再压无意义且耗时
        // so ：libnode.so 60MB，必须 store（不压缩），否则每次加载要先解压到内存，
        //      启动变慢甚至 OOM。这是 AGP 8+ 的默认行为，显式声明防止将来被改回。
        noCompress += listOf("zip", "so")
    }

    // ⭐ AGP 8+ 默认关闭 BuildConfig 生成，
    //    但我们的顶部栏 / 关于页都引用了 BuildConfig.VERSION_NAME，必须显式开启。
    buildFeatures {
        buildConfig = true
    }

    // 我们把预编译的 libnode.so 放到 app/src/main/jniLibs/arm64-v8a/
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    // ===== 固定签名配置 =====
    // 有 keystore（app/stronghold.jks）时所有构建都用它签名，保证每次构建证书一致
    //   → 可以覆盖升级、不丢已导入的游戏包（包在 filesDir 里，卸载即清空）。
    // 没有则回退 debug 签名（换台机器临时构建用，但那种包装不到手机上）。
    val ksFile = rootProject.file("app/stronghold.jks")
    signingConfigs {
        if (ksFile.exists()) {
            create("release") {
                storeFile = ksFile
                storePassword = signingValue("storePassword", "KEY_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // ⚠️ debug 也用同一把钥匙。
        //   否则 `assembleDebug` 出来的是 Android Debug 签名，装不到已经用正式钥匙
        //   签名的手机上 —— 系统会报 INSTALL_FAILED_UPDATE_INCOMPATIBLE，
        //   只能卸载重装，而卸载会清掉 filesDir 里已导入的游戏包（800MB 要重下）。
        debug {
            signingConfig = if (ksFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有固定 keystore 就用它；否则退回 debug（仅本地应急）
            signingConfig = if (ksFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // assets 默认会打包 app/src/main/assets 下的所有文件，
    // 我们把游戏前端打成 assets/app_bundle.zip 放进去即可。
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 显式声明 activity（registerForActivityResult / 文件选择器需要）
    implementation("androidx.activity:activity-ktx:1.9.2")

    // ⭐ JNA：直接调用 libnode.so 的 node::Start（与现有壳同款做法）
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}