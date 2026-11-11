package com.stronghold.shell

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/**
 * 通用 zip 解压器（游戏包 / 插件共用）。
 *
 * 【为什么单独抽出来】
 *   0.3.0 之后启动器要装的东西不止一种：游戏包（800MB 级）、插件（几 MB）。
 *   解压逻辑里有安全处理（防目录穿越）、进度回调、大小预检，
 *   重复实现三遍很容易在某个分支漏掉安全检查。
 *
 * 【安全要点】
 *   1. 每个条目都校验 canonicalPath 是否仍在目标目录内（防 ../../ 穿越）
 *   2. 目标目录必须先清空，避免上次失败残留混进新包
 *   3. 跳过 __MACOSX 与 .DS_Store（macOS 打包产物，上游没有）
 */
object ZipInstaller {

    private const val TAG = "StrongholdShell"

    /**
     * 进度回调的最小间隔（已解压字节数）。
     *
     * 【为什么按字节而不是按文件数】原来是每 50 个文件回调一次。游戏包里有
     * libnode.so 这种 60MB 的单个大文件，中间一次都不回调 —— 进度条会卡住
     * 十几秒不动，看起来像死机。改成按字节后，大文件也能持续上报。
     */
    private const val REPORT_STEP = 4L * 1024 * 1024

    /**
     * 从 content Uri 解压（用户通过文件选择器选的 zip）。
     *
     * @param onProgress (已解压条目数, 已解压字节) → 在调用线程回调
     * @return 解压出的文件数量
     */
    fun extract(
        context: Context,
        uri: Uri,
        destDir: File,
        onProgress: ((Int, Long) -> Unit)? = null
    ): Int {
        destDir.mkdirs()
        val destPath = destDir.canonicalPath
        var count = 0
        var bytes = 0L
        var lastReport = 0L

        context.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!name.endsWith("/") && !skipEntry(name)) {
                        val out = File(destDir, name)
                        // 防目录穿越：真实路径必须仍在目标目录内
                        if (out.canonicalPath.startsWith(destPath)) {
                            out.parentFile?.mkdirs()
                            out.outputStream().buffered().use { os ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zis.read(buf)
                                    if (n <= 0) break
                                    os.write(buf, 0, n)
                                    bytes += n
                                    if (bytes - lastReport >= REPORT_STEP) {
                                        lastReport = bytes
                                        onProgress?.invoke(count, bytes)
                                    }
                                }
                            }
                            count++
                        } else {
                            Log.w(TAG, "跳过可疑路径（疑似穿越）：$name")
                            FileLogger.log("跳过可疑路径：$name")
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: throw IllegalStateException("无法打开所选文件")

        onProgress?.invoke(count, bytes)
        Log.i(TAG, "解压完成：$count 个文件，${bytes / 1048576}MB")
        return count
    }

    /**
     * 从本地 File 解压（下载好的 zip）。
     */
    fun extractFile(
        zip: File,
        destDir: File,
        onProgress: ((Int, Long) -> Unit)? = null
    ): Int {
        destDir.mkdirs()
        val destPath = destDir.canonicalPath
        var count = 0
        var bytes = 0L
        var lastReport = 0L

        FileInputStream(zip).use { ins ->
            ZipInputStream(ins.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!name.endsWith("/") && !skipEntry(name)) {
                        val out = File(destDir, name)
                        if (out.canonicalPath.startsWith(destPath)) {
                            out.parentFile?.mkdirs()
                            out.outputStream().buffered().use { os ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zis.read(buf)
                                    if (n <= 0) break
                                    os.write(buf, 0, n)
                                    bytes += n
                                    if (bytes - lastReport >= REPORT_STEP) {
                                        lastReport = bytes
                                        onProgress?.invoke(count, bytes)
                                    }
                                }
                            }
                            count++
                        } else {
                            Log.w(TAG, "跳过可疑路径（疑似穿越）：$name")
                            FileLogger.log("跳过可疑路径：$name")
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        onProgress?.invoke(count, bytes)
        Log.i(TAG, "解压完成：$count 个文件，${bytes / 1048576}MB")
        // 一个有效的游戏包不可能 0 文件。解出 0 个说明 zip 是坏的
        // （常见于下载到的是 HTML 错误页：ZipInputStream 遇到非 zip 直接返回 null，
        //  不抛异常，于是"成功解压了 0 个文件"，上层误判为安装成功）
        if (count == 0) {
            throw IllegalStateException("压缩包无效：解出 0 个文件（可能下载到的是错误页）")
        }
        return count
    }

    private fun skipEntry(name: String): Boolean =
        name.startsWith("__MACOSX/") ||
        name.endsWith(".DS_Store") ||
        name.startsWith("._")

    /**
     * 空间预检：估算解压所需字节数。
     *
     * ZipInputStream 的 entry.size 常为 -1（未知），所以：
     *   - 已知 size 的条目就累加
     *   - 已知比例过低时，用「压缩包大小 × 3」保守估算
     *
     * @return 预计需要的字节数；无法判断时返回压缩包大小 × 3
     */
    fun estimateUnzipped(zip: File): Long {
        val compressed = zip.length()
        var sum = 0L
        var known = 0
        var total = 0
        try {
            FileInputStream(zip).use { ins ->
                ZipInputStream(ins.buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        total++
                        if (e.size >= 0) {
                            sum += e.size
                            known++
                        }
                        z.closeEntry()
                        e = z.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "估算解压大小失败：${e.message}")
        }
        return if (total > 0 && known.toDouble() / total > 0.5) sum else compressed * 3
    }

    /** 检查可用空间是否足够解压 */
    fun hasSpaceFor(zip: File, destDir: File): Boolean {
        val need = estimateUnzipped(zip)
        val usable = destDir.usableSpace
        Log.i(TAG, "空间预检：需要 ${need / 1048576}MB，可用 ${usable / 1048576}MB")
        return usable > need + 100L * 1048576L
    }
}
