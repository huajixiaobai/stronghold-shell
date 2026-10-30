package com.stronghold.shell

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 增量包（-update.zip）覆盖器。
 *
 * ============================================================================
 * 【实测上游 update.zip 结构】v0.3.3，41 条目，876,557 字节
 *
 *   Stronghold-Protocol/                  ← 有顶层目录，直接解压会多套一层
 *     UPDATE.json                         ← 增量清单
 *     MANIFEST.json                       ← 整包清单
 *     package.json                        ← 版本号 0.3.3
 *     server/ public/ shared/ docs/ ...
 *     node_modules/.package-lock.json     ← 只有一个文件，不是完整 node_modules
 *
 * 【实测 UPDATE.json】三个版本的真实内容（字段含义已确认）
 *
 *   v0.3.3: app=0.3.3 from=["0.3.2"] count=21   removed=[]
 *   v0.3.2: app=0.3.2 from=["0.3.1"] count=1097 removed=[{path,sha256:[...]}, ×3]
 *   v0.3.1: app=0.3.1 from=["0.3.0"] count=388  removed=[{path,sha256:[...]}, ×N]
 *
 *   {"format":1, "app":"0.3.3", "from":["0.3.2"], "count":21, "bytes":1513567,
 *    "removed":[{"path":"server/sim/damage.js","sha256":["ae64cbdc..."]}],
 *    "files":{"CHANGELOG.md":{"size":222597,"sha256":"c15643a1..."}, ...}}
 *
 *   · from     —— 允许的基线版本。**只对紧邻的上一版有效**，所以跨版本必须逐级升
 *                 （见 SteppedUpdater），不能拿 0.3.3 的补丁直接打 0.3.1。
 *   · removed  —— 要删除的旧文件，**是对象数组不是字符串数组**，
 *                 每个带 sha256：只有当前文件确实是这些哈希之一才删，
 *                 用户改过的文件 / mod 覆盖过的文件不会被误删。
 *   · files    —— 覆盖后逐个校验 size + sha256。
 *
 * ============================================================================
 * 【桌面启动器（updater.cjs）的可借鉴点】
 *   1. findProjectRoot  —— 剥包裹目录找真正的项目根（本类 stripWrapper）
 *   2. blackList        —— 覆盖时保护外壳自己的文件（本类 PROTECTED_PREFIXES）
 *   3. 强制同步版本号    —— localPkg.version = remoteVersion（本类 forceVersion）
 *   4. 阶梯式递增        —— 见 SteppedUpdater.kt
 *
 * ============================================================================
 * 【安全顺序】任何一步失败都能回到原样
 *   解压 → 剥包裹 → 校验 from → 原地覆盖（旧文件先移进备份区）→ 按 removed 删
 *   → 校验 sha256 → 目录改名 v<新版本> → 写索引
 *
 *   注意「原地覆盖」这四个字：增量包只含变化的文件（0.3.3 只有 21 个），
 *   其余四万多个文件必须原样保留。只备份「被覆盖 / 被删除」的那几十个文件，
 *   失败时精确回滚，不碰其余文件。
 */
object PackUpdater {

    private const val TAG = "StrongholdShell"

    enum class ZipKind { FULL, UPDATE, UNKNOWN }

    /**
     * 超过这个体积必然是完整包。
     * 实测（v0.3.3）：完整包 861 MB / 41212 条目；增量包最大 20.8 MB；lite 28.9 MB。
     * 差一个数量级，所以 100 MB 这条线足够安全。
     */
    private const val BIG_PACK_BYTES = 100L * 1024 * 1024

    private const val MAX_SCAN_ENTRIES = 600
    private const val SCAN_TIME_BUDGET_MS = 8000L

    /**
     * 覆盖时**永远不碰**的目录。
     * 对应桌面版 updater.cjs 的 blackList（那边保护 main.cjs/preload.cjs 等壳文件），
     * 安卓壳这边没有壳文件在游戏目录里，真正要保护的是玩家的 mod 与 mod 状态：
     *   workshop/  Forge 工坊 mod
     *   var/       mod-registry.json 等运行时状态
     * 上游目前不会往这两个目录塞文件，但补丁里若出现同名路径，跳过比覆盖安全。
     */
    private val PROTECTED_PREFIXES = listOf("workshop/", "var/")

    // ==================== 包类型判定 ====================

    /**
     * 一次查出文件名与字节数，给 [detect] 用。查不到就返回 (null, -1)，绝不抛异常。
     *
     * 注意：`ACTION_OPEN_DOCUMENT` 拿到的 Uri 不是文件路径，只能靠 ContentResolver 查；
     * `uri.lastPathSegment` 在很多 provider 上是一串数字 id，靠它判包类型会误判。
     */
    fun queryMeta(context: Context, uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询文件信息失败：${e.message}")
        }
        // 有些 provider 不返回 SIZE 列，退一步用文件描述符的 statSize
        if (size <= 0) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    if (pfd.statSize > 0) size = pfd.statSize
                }
            } catch (e: Exception) {
                Log.w(TAG, "statSize 取值失败：${e.message}")
            }
        }
        return name to size
    }

    /**
     * 判定 zip 是完整包还是增量包。**按代价从低到高，能不问就不问**：
     *
     *   ① 文件名含 "-update"   → 增量包（上游命名固定，零成本）
     *   ② 体积 >= 100 MB       → 完整包（零成本，见 [BIG_PACK_BYTES]）
     *   ③ 剩下的小包才顺序扫条目：
     *        · 见到 UPDATE.json          → 增量包
     *        · 见到 >= 20 个 node_modules 文件 → 完整包
     *        · 都没有                    → 未知（lite / 源码包）
     *
     * 【为什么 ② 必须放在扫描之前】
     * ZipInputStream 只能顺序前进，读到第 n 个条目就得先把前 n-1 个的数据全部跳过
     * （deflate 的要真解压）。完整包 41212 个条目、前几条是 tools/ 而不是 node_modules，
     * 光为了"看到 node_modules"就可能要翻过成千上万个条目 —— 手机上几十秒到一分钟，
     * 而且一旦条目上限先到，还会把好端端的完整包误判成 UNKNOWN 直接拒绝导入。
     * 体积一条判断就把这条路彻底绕开了。
     */
    fun detect(
        context: Context,
        uri: Uri,
        fileName: String? = null,
        sizeBytes: Long = -1L
    ): ZipKind {
        // ① 文件名
        if (fileName != null && guessKindByName(fileName) == ZipKind.UPDATE) {
            return ZipKind.UPDATE
        }

        // ② 体积
        val size = if (sizeBytes > 0) sizeBytes else queryMeta(context, uri).second
        if (size >= BIG_PACK_BYTES) return ZipKind.FULL

        // ③ 小包才扫
        var hasUpdateJson = false
        var realNodeModules = 0
        var entries = 0
        val deadline = System.currentTimeMillis() + SCAN_TIME_BUDGET_MS
        try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                ZipInputStream(ins.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        val n = e.name
                        if (!n.endsWith("/")) {
                            entries++
                            if (n.endsWith("UPDATE.json")) hasUpdateJson = true
                            if (n.contains("node_modules/") && !n.endsWith(".package-lock.json")) {
                                realNodeModules++
                            }
                        }
                        if (hasUpdateJson || realNodeModules >= 20) break
                        if (entries >= MAX_SCAN_ENTRIES) break
                        if (System.currentTimeMillis() > deadline) {
                            FileLogger.log("探测包类型超时，已扫 $entries 个条目")
                            break
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "探测包类型失败：${ex.message}")
            return ZipKind.UNKNOWN
        }

        return when {
            hasUpdateJson -> ZipKind.UPDATE
            realNodeModules >= 20 -> ZipKind.FULL
            else -> ZipKind.UNKNOWN
        }
    }

    /** 只按文件名判断，不解压。给「本地包列表」用。 */
    fun guessKindByName(name: String): ZipKind = when {
        name.contains("-update", true) -> ZipKind.UPDATE
        name.contains("-lite", true) -> ZipKind.UNKNOWN
        else -> ZipKind.FULL
    }

    // ==================== 增量清单 ====================

    data class FileSig(val size: Long, val sha256: String)

    /** removed 里的一项：path + 允许被删除的哈希列表（防止误删用户改过的文件） */
    data class RemovedFile(val path: String, val sha256: List<String>)

    data class UpdateMeta(
        val app: String,
        val from: List<String>,
        val removed: List<RemovedFile>,
        val files: Map<String, FileSig>
    )

    fun readUpdateMeta(gameRoot: File): UpdateMeta? {
        val f = File(gameRoot, "UPDATE.json")
        if (!f.isFile) return null
        return try {
            val j = JSONObject(f.readText())

            val files = LinkedHashMap<String, FileSig>()
            j.optJSONObject("files")?.let { fo ->
                for (key in fo.keys()) {
                    val o = fo.optJSONObject(key) ?: continue
                    files[key] = FileSig(o.optLong("size", -1L), o.optString("sha256", ""))
                }
            }

            val from = ArrayList<String>()
            j.optJSONArray("from")?.let { fa ->
                for (i in 0 until fa.length()) from.add(fa.optString(i, ""))
            }

            // ⚠️ removed 是对象数组：[{"path":"...","sha256":["..."]}, ...]
            //    不是字符串数组。按字符串解析会全部读成空串，等于没删。
            val removed = ArrayList<RemovedFile>()
            j.optJSONArray("removed")?.let { ra ->
                for (i in 0 until ra.length()) {
                    when (val item = ra.opt(i)) {
                        is JSONObject -> {
                            val p = item.optString("path", "")
                            if (p.isBlank()) continue
                            val hashes = ArrayList<String>()
                            item.optJSONArray("sha256")?.let { ha ->
                                for (k in 0 until ha.length()) hashes.add(ha.optString(k, ""))
                            }
                            removed.add(RemovedFile(p, hashes))
                        }
                        is String -> if (item.isNotBlank()) removed.add(RemovedFile(item, emptyList()))
                    }
                }
            }

            UpdateMeta(
                app = j.optString("app", ""),
                from = from,
                removed = removed,
                files = files
            )
        } catch (e: Exception) {
            Log.w(TAG, "UPDATE.json 解析失败：${e.message}")
            null
        }
    }

    // ==================== 覆盖升级 ====================

    data class Result(
        val ok: Boolean,
        val fromVersion: String?,
        val toVersion: String?,
        val message: String
    )

    fun applyOverlay(
        context: Context,
        uri: Uri,
        targetId: String? = null,
        onStage: (String) -> Unit = {}
    ): Result = apply(context, targetId, onStage) { stage ->
        ZipInstaller.extract(context, uri, stage)
    }

    fun applyOverlayFile(
        context: Context,
        zip: File,
        targetId: String? = null,
        onStage: (String) -> Unit = {}
    ): Result = apply(context, targetId, onStage) { stage ->
        ZipInstaller.extractFile(zip, stage)
    }

    private fun apply(
        context: Context,
        targetId: String?,
        onStage: (String) -> Unit,
        extract: (File) -> Unit
    ): Result {
        val root = PackManager.packsRoot(context)
        val activeId = targetId ?: PackManager.activeId(context)
            ?: return Result(false, null, null, "本地还没有完整安装，增量包不能单独使用")

        val target = File(root, activeId)
        if (!File(target, "node_modules").isDirectory) {
            return Result(
                false, null, null,
                "当前版本 $activeId 缺少 node_modules，不是完整安装；请先导入完整包"
            )
        }
        val fromVer = PackManager.readVersionFrom(target)

        val stage = File(root, ".overlay")
        val backup = File(root, ".overlay-bak")
        var workDir: File = target
        var renamedTo: File? = null

        // 失败回滚用：
        //   written  —— 本次新写进去的相对路径（原本不存在的，回滚时删掉）
        //   backedUp —— 被移进备份区的原文件相对路径（回滚时放回去）
        val written = LinkedHashSet<String>()
        val backedUp = LinkedHashSet<String>()

        try {
            // ---- 1) 解压 ----
            stage.deleteRecursively()
            backup.deleteRecursively()
            stage.mkdirs()
            onStage("正在解压增量包…")
            extract(stage)

            // ---- 2) 剥包裹目录 ----
            val base = stripWrapper(stage)

            // ---- 3) 先判断适不适用，不适用就一个文件都不动 ----
            val meta = readUpdateMeta(base)
            if (meta != null && meta.from.isNotEmpty() && fromVer != null &&
                !meta.from.contains(fromVer)
            ) {
                return Result(
                    false, fromVer, null,
                    "这个增量包只适用于 v${meta.from.joinToString(" / v")}，本地是 v$fromVer。\n" +
                        "跨版本升级需要逐级进行（见 SteppedUpdater），或直接用完整包。"
                )
            }
            val toVer = meta?.app?.takeIf { it.isNotBlank() }
                ?: PackManager.readVersionFrom(base)
                ?: fromVer

            // ---- 4) 原地覆盖 ----
            // ⚠️ 这里**绝对不能**把整个目录 rename 走再 mkdirs 一个空目录：
            //    增量包里只有「变化的文件」（0.3.3 只有 21 个，完整包是 41212 个），
            //    其余文件必须原样留在原地。桌面版 updater.cjs 就是 copyFileSync 原地覆盖
            //    （updater.cjs:228），从不删任何旧文件。
            //    之前那版写法会把旧目录整个挪进备份、再建空目录只写 21 个文件，
            //    结果 3.1→3.2 因为补丁里恰好带 server/index.js 而「假成功」，
            //    3.2→3.3 补丁里没有 server/index.js，立刻被入口检查拦下。
            //
            //    为了失败可回滚，只把「即将被覆盖」的那几个文件先移进备份区（同分区 rename，秒级）。
            onStage("正在覆盖文件…")
            var moved = 0
            var copied = 0
            var skipped = 0
            base.walkTopDown().forEach { f ->
                if (f.isFile) {
                    val rel = f.relativeTo(base).path.replace('\\', '/')
                    if (PROTECTED_PREFIXES.any { rel.startsWith(it) }) {
                        skipped++
                    } else {
                        val dest = File(workDir, rel)
                        dest.parentFile?.mkdirs()
                        if (dest.isFile) {
                            // 旧文件先移进备份区，回滚时再移回来
                            val bk = File(backup, rel)
                            bk.parentFile?.mkdirs()
                            if (!dest.renameTo(bk)) {
                                throw IllegalStateException("备份 $rel 失败（文件被占用？），已中止")
                            }
                            backedUp.add(rel)
                        }
                        if (f.renameTo(dest)) {
                            moved++
                        } else {
                            f.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                            copied++
                        }
                        written.add(rel)
                    }
                }
            }
            if (skipped > 0) FileLogger.log("覆盖时跳过 $skipped 个 mod/状态目录内的文件")

            // ---- 5) 按 removed 清单删旧文件（带哈希校验，不误删用户改过的文件）----
            if (meta != null && meta.removed.isNotEmpty()) {
                var del = 0
                var keep = 0
                var prot = 0
                for (r in meta.removed) {
                    // 与覆盖同样保护 mod / mod 状态目录
                    if (PROTECTED_PREFIXES.any { r.path.startsWith(it) }) {
                        prot++
                        continue
                    }
                    val f = File(workDir, r.path)
                    if (!f.isFile || !f.canonicalPath.startsWith(workDir.canonicalPath)) continue
                    val okToDelete = if (r.sha256.isEmpty()) {
                        true
                    } else {
                        val actual = sha256(f)
                        r.sha256.any { it.equals(actual, true) }
                    }
                    if (okToDelete) {
                        // 不直接删，先移进备份区 —— 失败时能原样放回去
                        val bk = File(backup, r.path)
                        bk.parentFile?.mkdirs()
                        if (f.renameTo(bk)) {
                            backedUp.add(r.path)
                            del++
                        } else if (f.delete()) {
                            del++
                        }
                    } else {
                        keep++
                    }
                }
                FileLogger.log(
                    "按清单删除 $del 个旧文件" +
                        (if (keep > 0) "，$keep 个哈希不符已保留" else "") +
                        (if (prot > 0) "，$prot 个在受保护目录已跳过" else "")
                )
            }

            // ---- 6) 校验 ----
            onStage("正在校验文件…")
            if (meta != null && meta.files.isNotEmpty()) {
                val bad = ArrayList<String>()
                for ((rel, sig) in meta.files) {
                    val f = File(workDir, rel)
                    if (!f.isFile) {
                        bad.add("$rel（缺失）")
                    } else if (sig.size >= 0 && f.length() != sig.size) {
                        bad.add("$rel（大小 ${f.length()} ≠ ${sig.size}）")
                    } else if (sig.sha256.isNotEmpty() && !sha256(f).equals(sig.sha256, true)) {
                        bad.add("$rel（sha256 不符）")
                    }
                    if (bad.size >= 5) break
                }
                if (bad.isNotEmpty()) {
                    throw IllegalStateException(
                        "文件校验失败，增量包可能下坏了：\n" + bad.joinToString("\n")
                    )
                }
            }
            if (GamePack.findEntry(workDir) == null) {
                throw IllegalStateException("覆盖后找不到入口 server/index.js")
            }
            if (!File(workDir, "node_modules").isDirectory) {
                throw IllegalStateException("覆盖后 node_modules 丢了")
            }

            // ---- 7) 版本号对齐（照抄桌面版：localPkg.version = remoteVersion）----
            if (!toVer.isNullOrBlank()) forceVersion(workDir, toVer)
            val finalVer = PackManager.readVersionFrom(workDir) ?: toVer
                ?: throw IllegalStateException("覆盖后读不到版本号")

            // ---- 8) 目录名跟着版本走 + 写索引 ----
            val newId = PackManager.safeId("v$finalVer")
            if (newId != activeId) {
                val finalDir = File(root, newId)
                finalDir.deleteRecursively()
                if (workDir.renameTo(finalDir)) {
                    renamedTo = finalDir
                    workDir = finalDir
                }
            }
            PackManager.register(context, workDir, finalVer, source = "local-update", setActive = true)

            backup.deleteRecursively()
            stage.deleteRecursively()
            FileLogger.log(
                "增量覆盖完成：$fromVer -> $finalVer（rename $moved，copy $copied，" +
                    "校验 ${meta?.files?.size ?: 0} 个文件）"
            )
            return Result(true, fromVer, finalVer, "已升级到 v$finalVer")
        } catch (e: Exception) {
            // 回滚：把「本次写进去的」删掉，把「移进备份区的」放回去。
            // 因为只动了补丁涉及的那几十个文件，其余 4 万多个文件全程没被碰过，
            // 所以这里不需要（也不应该）整目录删除重建。
            try {
                // 目录若已改名成 v<新版本>，先改回原 id，再往里恢复文件
                if (renamedTo != null && !target.exists()) {
                    if (renamedTo!!.renameTo(target)) {
                        workDir = target
                        renamedTo = null
                    }
                }
                // 1) 删掉本次新写入、且原本不存在的文件
                for (rel in written) {
                    if (rel in backedUp) continue
                    File(workDir, rel).delete()
                }
                // 2) 把备份区的原文件放回原位
                for (rel in backedUp) {
                    val bk = File(backup, rel)
                    if (!bk.isFile) continue
                    val d = File(workDir, rel)
                    d.parentFile?.mkdirs()
                    d.delete()
                    bk.renameTo(d)
                }
            } catch (re: Exception) {
                Log.e(TAG, "回滚失败", re)
            }
            stage.deleteRecursively()
            backup.deleteRecursively()
            FileLogger.log("增量覆盖失败：" + (e.message ?: "未知错误"))
            return Result(false, fromVer, null, e.message ?: "未知错误")
        }
    }

    /**
     * 剥掉单层包裹目录（对应桌面版 findProjectRoot）。
     * 上游 update.zip 所有条目都在 Stronghold-Protocol/ 下面，不剥就会变成
     * packs/v0.3.2/Stronghold-Protocol/package.json —— 版本号读不到、游戏跑不起来。
     * 只在那层里确实有 package.json / server / UPDATE.json 时才剥，
     * 避免把「只改了 public/ 一个子目录」的补丁误判成包裹层。
     */
    private fun stripWrapper(stage: File): File {
        val kids = stage.listFiles()?.filter { it.name != "__MACOSX" && !it.name.startsWith(".") }
            ?: return stage
        if (kids.size != 1 || !kids[0].isDirectory) return stage
        val only = kids[0]
        val looksLikeRoot = File(only, "package.json").isFile ||
            File(only, "server").isDirectory ||
            File(only, "UPDATE.json").isFile
        return if (looksLikeRoot) only else stage
    }

    /**
     * 把 package.json 的 version 强制写成目标版本。
     * 桌面版 updater.cjs 就是这么做的（还顺便合并 dependencies）。
     * 目的：即使上游某个版本的 update.zip 忘了 bump package.json，
     *      壳里的版本号、目录名也不会和实际内容对不上。
     */
    private fun forceVersion(gameRoot: File, version: String) {
        val pkg = File(gameRoot, "package.json")
        if (!pkg.isFile) return
        try {
            val j = JSONObject(pkg.readText())
            if (j.optString("version", "") == version) return
            j.put("version", version)
            pkg.writeText(j.toString(2))
            FileLogger.log("package.json 版本号已强制对齐：$version")
        } catch (e: Exception) {
            Log.w(TAG, "写 package.json 版本号失败：${e.message}")
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val sb = StringBuilder()
        for (b in md.digest()) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
