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
        var keepZipAfterInstall: Boolean = true, // 安装完成后是否保留 zip（方便分享/重装）
        // --- 高级：自定义环境变量（每行一个 KEY=VALUE） ---
        // 上游每次新增环境变量都可能带来一处安卓壳不兼容的地方（v0.4.0 的
        // match-host 就是例子）。有了这一项，用户不必等我们发版就能自救。
        var extraEnv: String = ""
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
                 keepZipAfterInstall = j.optBoolean("keepZipAfterInstall", true),
                 extraEnv = j.optString("extraEnv", "")
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
                put("extraEnv", p.extraEnv)
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
     * 【为什么需要】上游 server/index.js 读 process.env 决定行为。
     * 上游 v0.4.0 一共用到 20 个环境变量，完整清单见仓库根目录
     * .github/upstream-env-baseline.yml。
     *
     * 【三层结构】
     *   1. 强制的兼容基线（[FORCED_ENV]，代码写死、用户不可关）
     *   2. 设置页的可视化开关（端口 / 战斗结算 / 存档校验 / 插件脚本）
     *   3. 用户自定义（[Prefs.extraEnv]，覆盖上面两层）
     *
     * 第 3 层是「全版本兼容」的关键：上游每次新增环境变量都可能带来一处安卓
     * 壳不兼容的地方（v0.4.0 的 match-host 就是例子），有了透传入口，用户
     * 不必等我们发版就能自救。
     */
    fun toEnv(p: Prefs): Map<String, String> {
        val m = HashMap<String, String>()

        // ===== 第 1 层：强制的兼容基线 =====
        // 这里只放「不这样本壳就根本跑不起来」的项，不放偏好。
        m.putAll(FORCED_ENV)

        // ===== 第 2 层：设置页的可视化开关 =====
        m["PORT"] = p.port.toString()
        if (p.host.isNotEmpty()) m["HOST"] = p.host
        if (p.combatMode != "client") m["SP_COMBAT"] = p.combatMode
        if (p.verifyMode != "off") m["SP_VERIFY"] = p.verifyMode
        if (p.pluginScripts != "off") m["SP_PLUGIN_SCRIPTS"] = p.pluginScripts
        m["SP_NO_BROWSER"] = "1"

        // ===== 第 3 层：用户自定义（可覆盖前两层） =====
        m.putAll(parseExtraEnv(p.extraEnv))

        return m
    }

    /**
     * 强制的兼容基线 —— 用户不可关闭。
     *
     * 【SP_MATCH_HOST = worker】
     * 上游 0.4.0 的官方版本 / 玩法插件会把对局放进 match-host。上游默认
     * transport='process'，走 child_process.fork()；但本壳是 JNA 内嵌 libnode、
     * 进程内跑 node::Start，没有真实的 node 可执行文件 —— fork 出来的
     * "node 子进程"其实是 /system/bin/app_process64，一启动就 SIGABRT
     * ("Error changing dalvik-cache ownership : Permission denied")。
     * 改用 worker_threads（同一进程内的线程）可以绕开，不需要外部进程。
     *
     * 【这个选择的代价，必须知道】
     * 上游 server/match/host/index.js 的注释明确写了为什么没把 worker 当默认值：
     *   a worker thread that runs out of its memory limit ... aborts the WHOLE
     *   server process (exit 134) ... A child process that runs out of memory
     *   aborts alone (SIGABRT) — hence the default.
     * 也就是说，一旦某个插件把内存吃爆，worker 模式会带走整个服务进程，
     * 而不只是那一个对局。我们是被迫接受这个风险的，不是这里比上游高明。
     * 所以：不要因为「上游默认是 process」就把这一项删掉 —— 删掉就是必崩。
     */
    private val FORCED_ENV: Map<String, String> = mapOf(
        "SP_MATCH_HOST" to "worker"
    )

    /**
     * 解析用户自定义的环境变量文本。
     *
     * 格式：每行一个 KEY=VALUE；以 # 开头的行和空行忽略；KEY 两侧空白去掉。
     * VALUE 里可以带 =（只按第一个 = 切分），方便填 Json 之类的值。
     *
     * 【为什么这么宽容】这是逃生通道，宁可放过一行看着不对的，也不要因为
     * 一处格式问题把整段配置都丢掉 —— 用户填进来通常是因为游戏已经跑不起来了。
     */
    fun parseExtraEnv(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        if (text.isBlank()) return out
        for (raw in text.split('\n')) {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            // 只接受看起来像环境变量名的键，避免把误粘贴的整段文字塞进环境
            if (key.isEmpty() || !key.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*$"))) continue
            out[key] = value
        }
        return out
    }

    /** 当前生效的端口（给健康检查用） */
    fun currentPort(context: Context): Int = load(context).port
}