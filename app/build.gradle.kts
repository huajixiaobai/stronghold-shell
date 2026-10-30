plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.stronghold.shell"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stronghold.shell"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "2.0.3"
        // 只保留 arm64（与游戏前端一致，体积最小）
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // ===== 构建变体：stable（正式）/ lab（联机实验） =====
    // 两个变体共用同一份源码，只有 applicationId、版本号和一个开关不同。
    //   stable：com.stronghold.shell       2.0.3      不含多人游戏入口
    //   lab   ：com.stronghold.shell.lab   0.2.1-lab  含多人游戏入口
    // 包名不同 → 可以和正式版同时装在手机上，互不覆盖。
    //
    // 注意：flavor 名不能叫 "main"，会和默认 sourceSet 撞车（Gradle 报
    // "Multiple entries with same key: main=[] and main=[]"）。
    flavorDimensions += "channel"
    productFlavors {
        create("stable") {
            dimension = "channel"
            applicationId = "com.stronghold.shell"
            versionName = "2.0.3"
            versionCode = 5
            buildConfigField("boolean", "ENABLE_MULTIPLAYER", "false")
            manifestPlaceholders["appLabel"] = "卫戍协议启动器"
        }
        create("lab") {
            dimension = "channel"
            applicationId = "com.stronghold.shell.lab"
            versionName = "0.2.1-lab"
            versionCode = 3
            buildConfigField("boolean", "ENABLE_MULTIPLAYER", "true")
            manifestPlaceholders["appLabel"] = "卫戍协议启动器 Lab"
        }
    }

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
    // 有 keystore（app/stronghold.jks，由 CI 从 Secrets 还原）时用它签名，
    // 保证每次构建证书一致 → 可覆盖升级、不丢已导入的游戏包。
    // 没有则回退 debug 签名（本地随手构建用）。
    val ksFile = rootProject.file("app/stronghold.jks")
    signingConfigs {
        if (ksFile.exists()) {
            create("release") {
                storeFile = ksFile
                storePassword = System.getenv("KEY_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
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