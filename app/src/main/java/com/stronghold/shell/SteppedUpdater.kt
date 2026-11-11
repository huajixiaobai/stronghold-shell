package com.stronghold.shell

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 阶梯式跨版本升级（移植自桌面启动器 updater.cjs 的 checkForUpdates/applyUpdate）。
 *
 * ============================================================================
 * 【为什么必须逐级升】
 *
 * 上游每个 -update.zip 的 UPDATE.json 里只有紧邻上一版（实测）：
 *   v0.3.1  from=["0.3.0"]   13.8 MB
 *   v0.3.2  from=["0.3.1"]   20.8 MB
 *   v0.3.3  from=["0.3.2"]   876 KB
 *
 * 所以 0.3.1 不能直接打 0.3.3 的补丁（会缺文件）。桌面版的做法是：
 *   拉全部 release → 找「比本地新的里面版本号最小的那个」→ 下它的补丁 → 覆盖
 *   → 重启后再查 → 再升一级 → 直到没有更新的。
 * 这里循环跑完全部步骤，中途不需要用户干预，也不需要重启。
 *
 * ============================================================================
 * 【只用 -update.zip，不回退 lite 包】
 *
 * 桌面版在缺 update 包时会退回 -lite.zip。实测这条路不可靠，已去掉：
 *   · lite 是不含素材的另一套完整分发形态，目录内容与增量补丁不是同一语义
 *   · 覆盖到已装版本上会留下「素材在、代码是 lite 的」这种混合状态，
 *     上游 README 也明确写了「不适用于精简安装」
 * 所以遇到某一级没有 -update.zip 时，链条就地停下，明确告诉用户
 * 「从 vX 起只能用完整包」，而不是硬塞一个 lite 进去。
 *
 * ============================================================================
 * 【与桌面版的对应关系】
 *   updater.cjs checkForUpdates()    → 本类 fetchAll() + plan()
 *   updater.cjs applyUpdate()        → 本类 run() 里逐步调用 PackUpdater
 *   findProjectRoot()                → PackUpdater.stripWrapper()
 *   blackList                        → PackUpdater.PROTECTED_PREFIXES
 *   localPkg.version = remoteVersion → PackUpdater.forceVersion()
 */
object SteppedUpdater {

    private const val TAG = "StrongholdShell"
    private const val MAX_STEPS = 15
    private const val PER_PAGE = 30

    data class Step(
        val from: String,
        val to: String,
        val asset: UpdateChecker.Asset
    ) {
        val sizeText: String
            get() = if (asset.sizeBytes > 0) "%.1f MB".format(asset.sizeMb) else "大小未知"
    }

    /**
     * 升级计划。
     * @param steps     依次要走的步骤
     * @param blockedAt 因为「该版本没有 -update.zip」而停下的那个版本号；
     *                  为 null 表示能一路升到最新
     */
    data class Plan(val steps: List<Step>, val blockedAt: String?)

    data class Result(
        val ok: Boolean,
        val fromVersion: String?,
        val toVersion: String?,
        val applied: List<String>,
        val message: String
    )

    // ==================== 拉版本列表 ====================

    /**
     * 取上游全部 release（按发布时间倒序）。对应桌面版请求
     * `/repos/owner/repo/releases` 那一步。直连失败时走 ghproxy。
     */
    fun fetchAll(): List<UpdateChecker.ReleaseInfo>? {
        val apiPath = "/repos/${UpdateChecker.OWNER}/${UpdateChecker.REPO}/releases?per_page=$PER_PAGE"
        val urls = listOf(
            "https://api.github.com$apiPath",
            "https://ghproxy.net/https://api.github.com$apiPath"
        )
        for (u in urls) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 12000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", "StrongholdShell-Launcher")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val code = conn.responseCode
                if (code != 200) {
                    conn.disconnect()
                    Log.w(TAG, "版本列表 $code：$u")
                    continue
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val list = parseReleases(text)
                if (list.isNotEmpty()) return list
            } catch (e: Exception) {
                Log.w(TAG, "版本列表请求失败：${e.message}")
            }
        }
        return null
    }

    private fun parseReleases(text: String): List<UpdateChecker.ReleaseInfo> {
        val out = ArrayList<UpdateChecker.ReleaseInfo>()
        val arr = JSONArray(text)
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val tag = j.optString("tag_name", "")
            if (tag.isEmpty()) continue
            val assets = ArrayList<UpdateChecker.Asset>()
            j.optJSONArray("assets")?.let { aa ->
                for (k in 0 until aa.length()) {
                    val a = aa.optJSONObject(k) ?: continue
                    assets.add(
                        UpdateChecker.Asset(
                            name = a.optString("name", ""),
                            sizeBytes = a.optLong("size", 0),
                            downloadUrl = a.optString("browser_download_url", "")
                        )
                    )
                }
            }
            out.add(
                UpdateChecker.ReleaseInfo(
                    tag = tag,
                    version = tag.removePrefix("v").removePrefix("V"),
                    name = j.optString("name", tag),
                    publishedAt = j.optString("published_at", ""),
                    body = j.optString("body", ""),
                    assets = assets
                )
            )
        }
        return out
    }

    // ==================== 规划升级路径 ====================

    /**
     * 算出从 current 到最新要走的每一步。
     * 只接受 -update.zip；某一级缺增量包就停在它前面（blockedAt 记下它），
     * 不会拿 lite 包顶替 —— lite 覆盖会得到「素材在、代码是 lite」的混合状态。
     */
    fun plan(current: String, releases: List<UpdateChecker.ReleaseInfo>): Plan {
        // 只留比本地新的，按版本升序
        val newer = releases.filter { UpdateChecker.isNewer(it.version, current) }
            .sortedWith { a, b -> if (UpdateChecker.isNewer(a.version, b.version)) 1 else -1 }

        val steps = ArrayList<Step>()
        var cur = current
        var blockedAt: String? = null

        for (r in newer) {
            if (!UpdateChecker.isNewer(r.version, cur)) continue
            val update = r.assets.firstOrNull { it.name.endsWith("-update.zip") }
            if (update == null) {
                // 缺这一级的补丁 -> 链条到此为止
                blockedAt = r.version
                break
            }
            steps.add(Step(from = cur, to = r.version, asset = update))
            cur = r.version
            if (steps.size >= MAX_STEPS) break
        }
        return Plan(steps, blockedAt)
    }

    // ==================== 执行 ====================

    /**
     * 从当前激活版本一路升到最新。每步：下载补丁 → PackUpdater 覆盖 → 继续下一级。
     * 任何一步失败就停在那里（已完成的步骤保留，不回退整条链）。
     */
    fun run(context: Context, onStage: (String) -> Unit = {}): Result {
        val activeId = PackManager.activeId(context)
            ?: return Result(false, null, null, emptyList(), "还没有安装游戏包")

        val activeDir = File(PackManager.packsRoot(context), activeId)
        val start = PackManager.readVersionFrom(activeDir)
            ?: return Result(false, null, null, emptyList(), "读不到当前版本号")

        onStage("正在查询版本列表…")
        val releases = fetchAll()
            ?: return Result(false, start, start, emptyList(), "查询版本列表失败（网络或 GitHub 限流）")

        val plan = plan(start, releases)
        val steps = plan.steps
        if (steps.isEmpty()) {
            val why = if (plan.blockedAt != null) {
                "v${plan.blockedAt} 没有增量包，请用完整包升级"
            } else {
                "已经是最新版本 v$start"
            }
            return Result(true, start, start, emptyList(), why)
        }

        val totalMb = steps.sumOf { if (it.asset.sizeBytes > 0) it.asset.sizeMb else 0.0 }
        onStage(
            "需要 ${steps.size} 步：" + steps.joinToString(" → ") { "v${it.to}" } +
                "（合计约 %.1f MB）".format(totalMb)
        )

        val applied = ArrayList<String>()
        var cur = start
        var index = 0

        for (step in steps) {
            index++
            // 每步都要重新取 activeId：PackUpdater 成功后会把目录改名成 v<新版本>
            val tid = PackManager.activeId(context) ?: activeId
            val dest = File(PackDownloader.downloadDir(context), step.asset.name)

            onStage("[$index/${steps.size}] 下载 v${step.to} 补丁（${step.sizeText}）…")
            val dlOk = PackDownloader.download(
                url = step.asset.downloadUrl,
                dest = dest,
                expectedSize = step.asset.sizeBytes
            )
            if (!dlOk) {
                dest.delete()
                return Result(
                    false, start, cur, applied,
                    "v${step.to} 的补丁下载失败（已升到 v$cur，可重试继续）"
                )
            }

            onStage("[$index/${steps.size}] 正在应用 v$cur → v${step.to}…")
            val r = PackUpdater.applyOverlayFile(context, dest, targetId = tid) { s ->
                onStage("[$index/${steps.size}] $s")
            }
            dest.delete()

            if (!r.ok) {
                return Result(
                    false, start, cur, applied,
                    "v${step.to} 应用失败：${r.message}\n（已升到 v$cur，当前版本可用）"
                )
            }
            cur = r.toVersion ?: step.to
            applied.add(cur)
        }

        val tail = if (plan.blockedAt != null) {
            "\nv${plan.blockedAt} 没有增量包，后续版本请用完整包"
        } else ""
        val msg = if (cur == start) "已经是最新版本 v$start" else "已从 v$start 升到 v$cur"
        onStage(msg + tail)
        FileLogger.log("阶梯升级完成：$msg（路径 ${applied.joinToString(" → ")}）")
        return Result(true, start, cur, applied, msg + tail)
    }
}
