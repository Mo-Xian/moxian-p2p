package com.cp12064.moxianp2p

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 注册全局未捕获异常处理器 把 crash 写到 filesDir/crash.log
 * 下次启动 MainActivity 读这个文件显示在日志区
 * 解决"没 adb 抓不到崩溃原因"的问题
 */
class MoxianApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val crashFile = File(filesDir, CRASH_FILE)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                pw.println("=== CRASH @ $ts ===")
                pw.println("thread: ${thread.name}")
                pw.println("error:  ${throwable.javaClass.name}: ${throwable.message}")
                pw.println()
                throwable.printStackTrace(pw)
                var cause = throwable.cause
                var depth = 0
                while (cause != null && depth < 5) {
                    pw.println()
                    pw.println("--- Caused by ---")
                    cause.printStackTrace(pw)
                    cause = cause.cause
                    depth++
                }
                pw.flush()
                crashFile.appendText(sw.toString() + "\n")
            } catch (_: Throwable) {
                // 写文件失败 无能为力
            }
            // 让原 handler 继续处理（默认会打印到 logcat 并杀进程）
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_FILE = "crash.log"

        /** 读取并清空 crash log */
        fun consumeCrashLog(app: Application): String? {
            val f = File(app.filesDir, CRASH_FILE)
            if (!f.exists() || f.length() == 0L) return null
            return try {
                val text = f.readText()
                f.delete()
                text
            } catch (_: Throwable) {
                null
            }
        }
    }
}
