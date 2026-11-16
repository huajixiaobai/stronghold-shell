package com.stronghold.shell

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 启动器设置 —— 持久化到 filesDir/prefs/launcher.json。
 *
 * 【为什么不用 SharedPreferences】配置项多、要按「版本」区分（每个版本
 * 可以有自己的环境变量），JSON 更灵活，也方便导出/分享。
 *
 * 【环境变量】上游支持一批环境变量控制行为（PORT / HOST / SP_COMBAT /
 * SP_VERIFY / SP_PLUGIN_SCRIPTS 等），手机上没命令行，这里做成可视化开关。
 */
object Settings {

    private const val TAG = "StrongholdShell"
    private const val FILE_NAME = "launcher.json"

    /** 一项设置 */
    data class Prefs(
        var port: Int = 3000,
        var host: String = "::",
        var combatMode: String = "client",     // client / server
        var verifyMode: String = "off",        // off / sample / all
        var pluginScripts: String = "off",     // off / sandbox
        var noBrowser: Boolean = true,         // 手机上无意义，保留
        // --- 自动检查更新：拆成两个独立开关，互不影响 ---
        var autoCheckPack: Boolean = true,     // 启动时自动查「游戏包」更新（上游 GitHub Release）
        var autoCheckApp: Boolean = true,      // 启动时自动查「启动器自身」更新（本项目 Release）
        var lastKnownLatest: String = "",      // 上次查到的游戏包最新版本
        var lastKnownAppVersion: String = "",  // 上次查到的启动器最新版本
        // --- 下载相关 ---
        var downloadMode: Int = 1,             // 0=应用私有 1=公共Download 2=自定义
        var downloadPath: String = "",         // mode=2 时用的绝对路径
        var keepZipAfterInstall: Boolean = true // 安装完成后是否保留 zip（方便分享/重装）
    )

    private fun file(context: Context): File {
        val dir = File(context.filesDir, "prefs")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    fun load(context: Context): Prefs {
        val f = file(context)
        if (!f.isFile) return Prefs()
        return try {
            val j = JSONObject(f.readText())
            Prefs(
                port = j.optInt("port", 3000),
                host = j.optString("host", "::"),
                combatMode = j.optString("combatMode", "client"),
                verifyMode = j.optString("verifyMode", "off"),
                pluginScripts = j.optString("pluginScripts", "off"),
                noBrowser = j.optBoolean("noBrowser", true),
                // 老配置里可能只有 autoCheckUpdate，兼容迁移：两边都继承旧值
                autoCheckPack = j.optBoolean("autoCheckPack", j.optBoolean("autoCheckUpdate", true)),
                autoCheckApp = j.optBoolean("autoCheckApp", j.optBoolean("autoCheckUpdate", true)),
lastKnownLatest = j.optString("lastKnownLatest", ""),
                 lastKnownAppVersion = j.optString("lastKnownAppVersion", ""),
                 downloadMode = j.optInt("downloadMode", 1),
                 downloadPath = j.optString("downloadPath", ""),
                 keepZipAfterInstall = j.optBoolean("keepZipAfterInstall", true)
             )
        } catch (e: Exception) {
            Log.w(TAG, "读设置失败: ${e.message}")
            Prefs()
        }
    }

    fun save(context: Context, p: Prefs): Boolean {
        return try {
            val j = JSONObject().apply {
                put("port", p.port)
                put("host", p.host)
                put("combatMode", p.combatMode)
                put("verifyMode", p.verifyMode)
                put("pluginScripts", p.pluginScripts)
                put("noBrowser", p.noBrowser)
                put("autoCheckPack", p.autoCheckPack)
                put("autoCheckApp", p.autoCheckApp)
                put("lastKnownLatest", p.lastKnownLatest)
                put("lastKnownAppVersion", p.lastKnownAppVersion)
                put("downloadMode", p.downloadMode)
                put("downloadPath", p.downloadPath)
                put("keepZipAfterInstall", p.keepZipAfterInstall)
            }
            file(context).writeText(j.toString(2))
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存设置失败", e)
            false
        }
    }

    /**
     * 转成环境变量 Map，供 NodeRunner 注入。
     *
     * 【为什么需要】上游 server/index.js 读 process.env 决定行为：
     *   PORT / HOST / SP_COMBAT / SP_VERIFY / SP_PLUGIN_SCRIPTS
     */
    fun toEnv(p: Prefs): Map<String, String> {
        val m = HashMap<String, String>()
        m["PORT"] = p.port.toString()
        if (p.host.isNotEmpty()) m["HOST"] = p.host
        if (p.combatMode != "client") m["SP_COMBAT"] = p.combatMode
        if (p.verifyMode != "off") m["SP_VERIFY"] = p.verifyMode
        if (p.pluginScripts != "off") m["SP_PLUGIN_SCRIPTS"] = p.pluginScripts
        m["SP_NO_BROWSER"] = "1"
        // ⭐ 0.4.0+ 的官方版本 / 玩法插件会把对局放进 match-host。
        //    上游默认 transport='process'，走 child_process.fork()；但本壳是 JNA 内嵌
        //    libnode、进程内跑 node::Start，没有真实的 node 可执行文件——fork 出来的
        //    "node 子进程"其实是 /system/bin/app_process64，一启动就 SIGABRT
        //    ("Error changing dalvik-cache ownership : Permission denied")。
        //    强制用 worker_threads（同一进程内的线程）即可绕开，不需要外部进程。
        m["SP_MATCH_HOST"] = "worker"
        return m
    }

    /** 当前生效的端口（给健康检查用） */
    fun currentPort(context: Context): Int = load(context).port
}