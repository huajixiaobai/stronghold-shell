package com.stronghold.shell

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 启动器自身的更新检查与安装。
 *
 * 【和 UpdateChecker 的区别】
 *   UpdateChecker 查的是**游戏包**（sganggs/Stronghold-Protocol），
 *   LauncherUpdater 查的是**启动器自己**（blwsh-1/stronghold-shell）。
 *   两者资产地址形状相同，所以下载可以完全复用 PackDownloader 的镜像体系。
 *
 * 【为什么下载要走 PackDownloader】
 *   GitHub 在国内经常连不上或半路截断。PackDownloader 已经有
 *   「7 个代理测速 → 断点续传 → 大小强校验 → 多轮重试 → 直连兜底」，
 *   没必要为 70MB 的 APK 再写一套。
 *
 * 【安全检查】
 *   下载完必须用 [verifySignature] 比对证书。系统本身也会拒装签名不符的包，
 *   但那时用户看到的是「应用未安装」，不知道原因；提前拦住能给出明确提示，
 *   也能防住下载源被替换的情况。
 */
object LauncherUpdater {

    private const val TAG = "StrongholdShell"

    const val OWNER = "blwsh-1"
    const val REPO = "stronghold-shell"

    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val API_VIA_PROXY = "https://ghproxy.net/$API_LATEST"
    private const val WEB_LATEST = "https://github.com/$OWNER/$REPO/releases/latest"

    private const val PREF = "launcher_update"
    private const val KEY_CHECKED_AT = "checkedAt"

    /** 静默检查的最小间隔：24 小时内不重复打 GitHub API */
    private const val CACHE_MS = 24L * 3600 * 1000

    data class Update(
        val tag: String,          // launcher-v2.1.1
        val version: String,      // 2.1.1
        val sizeBytes: Long,
        val downloadUrl: String,
        val notes: String
    ) {
        val sizeMb: Int get() = (sizeBytes / 1048576L).toInt()
    }

    // ==================== 版本比较 ====================

    /**
     * 只比数字段，忽略 -lab / +build 之类后缀。
     * 2.1.0 vs 2.0.10 → 前者大（按段比，不是按字符串比）。
     */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.split('.', '-', '+', '_').mapNotNull { it.toIntOrNull() }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // ==================== 检查缓存 ====================

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun recentlyChecked(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_CHECKED_AT, 0L) < CACHE_MS

    fun markChecked(context: Context) {
        prefs(context).edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply()
    }

    // ==================== 查询 ====================

    /** 查最新版本；已是最新（或查询失败）返回 null。注意：是阻塞调用，别在主线程跑。 */
    fun check(currentVersion: String): Update? {
        val u = fetch() ?: return null
        return if (isNewer(u.version, currentVersion)) u else null
    }

    /**
     * 带「是否查成功」的版本。
     *
     * 静默检查只关心有没有新版；但用户手动点按钮时，必须能区分
     * 「已经是最新」和「网络没通」—— 两种情况给同一句 toast 会让人以为检查坏了。
     *
     * @return first = 有新版时的 Update；second = 网络请求是否成功
     */
    fun checkDetailed(currentVersion: String): Pair<Update?, Boolean> {
        val u = fetch() ?: return null to false
        val newer = isNewer(u.version, currentVersion)
        Log.i(TAG, "启动器更新检查：远端 ${u.version}，本地 $currentVersion，有新版本=$newer")
        return (if (newer) u else null) to true
    }

    private fun fetch(): Update? {
        // ---- 通道 1：官方 API（信息最全，能拿到体积和更新日志） ----
        try {
            val text = httpGet(API_LATEST)
            if (text != null) {
                parseRelease(text)?.let {
                    Log.i(TAG, "启动器更新：API 通道拿到 ${it.tag}")
                    return it
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "启动器更新 API 失败：${e.message}")
        }

        // ---- 通道 2：走代理的 API ----
        try {
            val text = httpGet(API_VIA_PROXY)
            if (text != null) {
                parseRelease(text)?.let {
                    Log.i(TAG, "启动器更新：代理 API 拿到 ${it.tag}")
                    return it
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "启动器更新代理 API 失败：${e.message}")
        }

        // ---- 通道 3：只读 302 的 Location 头拿 tag，资产地址按命名规律拼 ----
        // 命中率不高时也能救急：API 被限流（匿名 60 次/小时）后这是唯一通道。
        try {
            val tag = fetchTagFromLocation() ?: return null
            val version = normalizeVersion(tag)
            Log.i(TAG, "启动器更新：Location 通道拿到 $tag")
            return Update(
                tag = tag,
                version = version,
                sizeBytes = 0L,
                downloadUrl = assetUrlFor(tag),
                notes = ""
            )
        } catch (e: Exception) {
            Log.w(TAG, "启动器更新 Location 通道失败：${e.message}")
        }
        return null
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "StrongholdShell-Launcher")
        return try {
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.w(TAG, "HTTP $url -> ${conn.responseCode}")
                null
            }
        } finally {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun fetchTagFromLocation(): String? {
        val conn = URL(WEB_LATEST).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "StrongholdShell-Launcher")
        return try {
            if (conn.responseCode in 300..399) {
                val loc = conn.getHeaderField("Location") ?: return null
                val idx = loc.indexOf("/releases/tag/")
                if (idx < 0) return null
                loc.substring(idx + "/releases/tag/".length).trim().trimEnd('/')
                    .ifEmpty { null }
            } else null
        } finally {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun parseRelease(text: String): Update? {
        val o = JSONObject(text)
        val tag = o.optString("tag_name", "")
        if (tag.isEmpty()) return null
        val notes = o.optString("body", "")
        var url = ""
        var size = 0L
        val assets = o.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                // 2.1.0 起只有一个 APK；老 release 里的 -lab- 资产跳过
                if (name.endsWith(".apk") && !name.contains("-lab-")) {
                    url = a.optString("browser_download_url", "")
                    size = a.optLong("size", 0L)
                    break
                }
            }
        }
        if (url.isEmpty()) url = assetUrlFor(tag)
        return Update(tag, normalizeVersion(tag), size, url, notes)
    }

    /** launcher-v2.1.0 → 2.1.0 */
    private fun normalizeVersion(tag: String): String =
        tag.removePrefix("launcher-").removePrefix("v").trim()

    /** 命名规律兜底：StrongholdShell-launcher-v2.1.0-android.apk */
    private fun assetUrlFor(tag: String): String =
        "https://github.com/$OWNER/$REPO/releases/download/$tag/StrongholdShell-$tag-android.apk"

    // ==================== 下载 ====================

    /**
     * 下载 APK 到 cacheDir/update/。走 PackDownloader 的镜像体系，卡住会自动换源。
     * 阻塞调用，必须在后台线程执行。
     *
     * @return 下载好的文件；失败返回 null
     */
    fun download(
        context: Context,
        update: Update,
        listener: PackDownloader.Listener,
        onCancel: () -> Boolean = { false }
    ): File? {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        // 清掉上次残留，避免旧包和新包混在一起
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val dest = File(dir, "launcher-${update.version}.apk")
        val ok = PackDownloader.download(
            url = update.downloadUrl,
            dest = dest,
            expectedSize = update.sizeBytes,
            listener = listener,
            preferredMirrors = null,
            onCancel = onCancel
        )
        if (!ok) {
            Log.w(TAG, "启动器更新下载失败")
            return null
        }
        FileLogger.log("启动器更新包已下载：${dest.name}（${dest.length() / 1048576}MB）")
        return dest
    }

    // ==================== 校验与安装 ====================

    /**
     * 比对 APK 的签名证书和当前运行的应用是否一致。
     *
     * 不一致说明这个包不是用同一把钥匙签的 —— 装上去会被系统拒绝，
     * 或者更糟：是别人签的同名包。两种都不该放行。
     */
    fun verifySignature(context: Context, apk: File): Boolean {
        return try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES

            val archive = pm.getPackageArchiveInfo(apk.absolutePath, flag) ?: return false
            if (archive.packageName != context.packageName) {
                Log.w(TAG, "签名校验失败：包名不符 ${archive.packageName}")
                return false
            }

            @Suppress("DEPRECATION")
            val apkSigs: Set<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet() ?: emptySet()
            } else {
                archive.signatures?.map { it.toCharsString() }?.toSet() ?: emptySet()
            }

            val self = pm.getPackageInfo(context.packageName, flag)
            @Suppress("DEPRECATION")
            val selfSigs: Set<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                self.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet() ?: emptySet()
            } else {
                self.signatures?.map { it.toCharsString() }?.toSet() ?: emptySet()
            }

            val ok = apkSigs.isNotEmpty() && apkSigs == selfSigs
            if (!ok) Log.w(TAG, "签名校验失败：证书与当前应用不一致")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "签名校验异常：${e.message}")
            false
        }
    }

    /** Android 8+ 需要「安装未知应用」权限 */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.packageManager.canRequestPackageInstalls()
        else true

    /** 跳到本应用的「安装未知应用」设置页 */
    fun openInstallSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "打开安装权限设置失败：${e.message}")
        }
    }

    /** 拉起系统安装器。file_paths.xml 里已包含 cache-path，可直接授权。 */
    fun install(activity: Activity, apk: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", apk
            )
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(i)
            true
        } catch (e: Exception) {
            Log.w(TAG, "拉起安装器失败：${e.message}")
            FileLogger.log("拉起安装器失败：${e.message}")
            false
        }
    }
}
