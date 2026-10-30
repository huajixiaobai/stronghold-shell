# StrongholdShell —— 卫戍协议：盟约 · 安卓启动器

> ## 🎮 游戏本体来自上游项目
>
> ### 👉 <https://github.com/sganggs/Stronghold-Protocol>
>
> **本仓库是一个「安卓启动器（壳）」：把上游游戏装进手机。它本身不含任何游戏素材。**>   
> 想看游戏源码、提游戏相关的 issue、了解玩法与开发，请去上游仓库。

自写的极简 Android 启动器：**WebView + 内嵌 Node 服务器**，把上游  
[`sganggs/Stronghold-Protocol`](https://github.com/sganggs/Stronghold-Protocol)  
这类「Node + 前端」的浏览器游戏装进手机，**离线单机可玩**，也能当房主让同一 Wi-Fi 的朋友连进来。

- 🎮 **游戏本体**：<https://github.com/sganggs/Stronghold-Protocol>（上游，**先看这个**）
- 📦 **启动器安装包**：见本仓库 [Releases](../../releases/latest)，文件名 `StrongholdShell-launcher-v<版本>-android.apk`
- 🔧 **壳源码完全自写**（13 个 Kotlin 文件 + 构建脚本），**不含游戏素材**
- 🔁 **上游更新时不用重装启动器**，导入新包即可

## ⚠️ 声明

> 本项目是玩家自制的**非官方同人作品**，与上海鹰角网络科技有限公司（Hypergryph）、Yostar>   
> 及其关联方**没有任何关系**，未获其授权或认可。
>
> 《明日方舟》及「卫戍协议」相关的名称、角色、美术、音乐、音效、文本与数据等素材，>   
> 版权归原权利人所有，**不适用**本项目的许可证；许可证只覆盖本项目自己编写的**壳代码**。
>
> 仅供学习交流与个人非商业使用，**严禁任何形式的盈利**。>   
> 本仓库源码**不含**游戏素材；**本启动器 APK 也不含**游戏素材>   
> （游戏内容由用户从上游 Releases 自行下载完整包后导入）。>   
> 权利人如认为本项目侵犯其权益，请通过 Issue 联系，**立即删除**相关内容。

## 这是什么 / 不是什么

|      |                                         |
| ---- | --------------------------------------- |
| ✅ 是  | 一个把你自己的 Node 游戏装进手机的 **Android 启动器（壳）** |
| ✅ 是  | **不含游戏素材**的合规空壳，游戏内容由用户导入               |
| ✅ 是  | 手机上**开箱即玩**的 APK（内嵌 Node，无需电脑、无需联网）     |
| ❌ 不是 | 游戏本身。游戏内容来自上游项目，本仓库只是「容器」               |
| ❌ 不是 | 上游的 PR。本项目是**独立仓库**（非 Fork），与上游无关联      |

## 快速开始

### 1. 装启动器

到 [Releases](../../releases/latest) 下载 `StrongholdShell-launcher-v<版本>-android.apk`  
（**约 70 MB，不含游戏素材**）。要求 **Android 7.0（API 24）及以上、arm64-v8a**。

> 校验：Release 说明里会给出安装包的 **SHA-256**，可自行核对。

### 2. 下载游戏完整包

到上游 [`sganggs/Stronghold-Protocol` Releases](https://github.com/sganggs/Stronghold-Protocol/releases)  
下载**完整包** `Stronghold-Protocol-vX.Y.Z.zip`（约 550 MB），存到手机任意位置。

> ⚠️ 请下**完整包**（含 `node_modules`），不要下 lite / 源码包；>   
> 启动器导入时会校验，缺 `node_modules` 会明确报错。

### 3. 导入开玩

1. 打开启动器 → 点「导入游戏包 (.zip)」
2. 选中刚下载的 zip → 等待解压（首次几十秒 ~ 1 分钟）
3. 解压完自动进入游戏；**之后从桌面图标进入即一键开玩**

> 启动器把游戏解压到 App 私有目录，**不会污染相册**。>   
> 导入后可删除 `/sdcard` 上的 zip 以释放空间（游戏运行只读私有目录）。

### 自己从源码构建

因为壳依赖 60 MB 的 `libnode.so`（不入库），构建流程由脚本自动准备：

```bash
bash scripts/fetch_libnode.sh    # 准备 libnode.so + libc++_shared.so → jniLibs
./gradlew :app:assembleRelease   # 出包：app/build/outputs/apk/release/
```

**没有本地 JDK/Android SDK？** 直接推到 GitHub，用 Actions 云端编译：  
仓库 → Actions → 「Build APK」→ Run workflow，几分钟后到 Releases 拿包。

## 跟上游更新

上游每出新版，**启动器本身不用重建**——只需重新下载上游完整包并「重新导入（覆盖）」即可。  
（只有在**壳代码**更新时才需要重建启动器。）

```bash
export GH_TOKEN=ghp_xxxx                        # 你的 GitHub token
bash scripts/sync-upstream.sh                    # 只看上游有没有新版
```

> **注意**：这是「仓库代码的同步」，**不是手机 App 自动更新**。>   
> 构建完的新 APK 仍需你**手动下载安装**（Android 不允许 App 自己给自己升级）。

## 结构

```
app/src/main/java/com/stronghold/shell/
  ImportActivity.kt   启动器入口：导入 / 启动 / 删除游戏包（包管理页）
  MainActivity.kt     游戏渲染页：WebView + 启动流程 + 沉浸式全屏
  NodeService.kt      前台服务：进程内启动 Node
  NodeRunner.kt       JNA 直调 libnode.so 的封装
  GamePack.kt         游戏包管理：导入 zip → 解压到私有目录 / 定位游戏根 / 卸载
  AndroidBridge.kt    JS↔Kotlin 桥（注入名 AndroidNative）
  AssetHelper.kt      内置 assets 解压（无导入包时的回退路径）
  FileLogger.kt       落盘日志
  NetworkUtils.kt     局域网 IP
.github/workflows/build.yml     GitHub Actions 云构建（产出正式命名 APK + Release）
scripts/fetch_libnode.sh        准备 libnode.so / libc++_shared.so
scripts/sync-upstream.sh        查上游更新
scripts/push_files.py           手机端无 git 时，走 Contents API 单文件推送
prebuilt/arm64-v8a/libc++_shared.so   入库的 C++ 运行时（见下方排错第 5 条）
ROADMAP.md                      功能清单 / 路线图（已实现 + 待做）
```

## 工作机制

```
导入：ImportActivity → GamePack.installFromUri(zip)
      → 空间预检 → 流式解压到 packs/active.installing
      → 定位游戏根（兼容单层/多层包裹）→ 原子换名成 packs/active
启动：MainActivity.onCreate → 启动 NodeService
      → NodeService 用 JNA 直调 libnode.so
      → JNA: NativeLibrary.getInstance("node").getFunction("_ZN4node5StartEiPPc")
             → node::Start(argc, argv)  （argv = ["node", <activeDir>/server/index.js]）
      → Node 监听 :3000，健康检查 /healthz
      → MainActivity：轮询 /healthz 健康后 WebView 加载 index.html
      → 前端通过 window.AndroidNative.reportClientState({booted:true}) 回报
```

### 为什么用 JNA 而不是 NDK 编译

JNA 直接拿 `libnode.so` 导出的 **C++ 符号** `_ZN4node5StartEiPPc`  
（即 `node::Start(int, char**)`），无需自己写 JNI 胶水、无需 NDK 编译。  
只需一个 Maven 依赖 `net.java.dev.jna:jna`。

## 云构建排错备忘（实测踩坑）

1. **不要用 `android-actions/setup-android@v3`**：新版 ubuntu runner 已预装 SDK，     
   该 action 会执行已废弃的 `sdkmanager tools` 而报 `Failed to find package 'tools'`。     
   直接用 runner 的 `ANDROID_HOME` 即可。
2. **必须提供 `mipmap/ic_launcher`**：manifest 引用了它，缺了会     
   `AAPT: error: resource mipmap/ic_launcher not found`。
3. `gradle-wrapper.jar` / `gradlew` 未提交时，workflow 会用 runner 全局     
   `gradle wrapper --gradle-version 8.7` 现场生成。
4. Gradle 8.7 + AGP 8.5.2 需 JDK 17（workflow 已配 temurin 17）。
5. **必须打包 `libc++_shared.so`**（关键！）：`libnode.so` 依赖 C++ 运行时     
   `libc++_shared.so`。若 APK 里只有 `libnode.so` 而没有它，运行时会报     
   `dlopen failed: library "libc++_shared.so" not found`，Node 起不来、WebView 一直白屏。
   - nodejs-mobile 的 release 包里**不含**这个 .so，runner 的 NDK 里也常常找不到。
   - 因此本仓库把 `prebuilt/arm64-v8a/libc++_shared.so`（约 6.7 MB）**直接入库**，       
     `scripts/fetch_libnode.sh` 会优先从这里复制到 `jniLibs`。
   - 可复用来源：任意 nodejs-mobile 安卓 App 的 `lib/arm64-v8a/libc++_shared.so`。
6. **入口脚本名**：上游入口是 `server/index.js`（不是 `index.js`/`main.js`）。     
   `GamePack.findEntry` / `NodeService` 用候选列表 `["server/index.js","index.js","main.js","app.js"]` 逐个探测。
7. **工作目录**：Node 启动前必须 `chdir` 到游戏根（用 JNA 调 libc 的 `chdir`），     
   `System.setProperty("user.dir")` 无效。
8. **targetSdk 34 的 .so 需要页对齐**：不要用「手工往 zip 追加 .so」的方式改包     
   （会破坏对齐，安装时报 `INSTALL_FAILED_INVALID_APK: Failed to extract native libraries`）。     
   交给 Gradle/AGP 构建，它会自动做 16KB 对齐。
9. **沉浸式全屏**：系统栏用 `WindowInsetsControllerCompat` 隐藏，     
   并在 `onWindowFocusChanged` 里重复隐藏；systemBarsBehavior 设为     
   `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`（下滑临时唤出）。
10. **首启慢 ≠ 崩溃**：上游 Node 首启要加载两万多个文件、需几十秒。      
    启动器已内置「正在启动游戏…」加载页（替代黑屏），轮询上限 90 秒。

## 合规红线

- 上游 README 声明素材版权归鹰角，禁止再分发。
- 因此：**含素材的 APK 不能发上游 PR**；本启动器**不含素材**，但它本身也不提交上游 PR。
- 本项目为**独立仓库**，不向任何上游仓库提交 PR，仅供自用分发。
- 自用请自行承担素材合规责任。

---

*功能清单与路线图见 [`ROADMAP.md`](ROADMAP.md)。* 🐟
