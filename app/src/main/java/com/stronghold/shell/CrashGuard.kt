package com.stronghold.shell

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 全局未捕获异常兜底 —— 把崩溃栈写进 FileLogger 的 shell.log。
 *
 * 【为什么需要它】
 * 这个壳是侧载安装的（自己打 APK 装到手机），出问题时拿不到 logcat。
 * 一「闪退」就什么都看不到，只能靠猜。
 * 装上它之后：闪退 → 重新打开 App → 设置页「运行环境诊断」→ 日志末尾
 * 就是完整的崩溃栈（异常类型 + message + 逐行堆栈），不用连电脑。
 *
 * 【它不做什么】
 * 只负责**记录**，不吞异常 —— 记完仍然交回系统默认处理器
 * （也就是该崩还是崩）。这样不会把真正的错误掩盖成
 * 「点了没反应」这种更难查的状态。
 *
 * 【放置位置】ImportActivity.onCreate() 里 FileLogger.init() 之后调用一次。
 * 因为启动器是入口 Activity，它先跑，之后所有页面的崩溃都能被抓到。
 */
object CrashGuard {

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true

        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                FileLogger.init(app)
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                FileLogger.log("========== 崩溃 ==========")
                FileLogger.log("线程：" + (thread?.name ?: "?"))
                FileLogger.log(sw.toString())
            } catch (_: Throwable) {
                // 记录本身失败就算了，绝不能因此掩盖原始崩溃
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
