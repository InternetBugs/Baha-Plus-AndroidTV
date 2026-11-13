package app.bahaplus.tv.diagnostics

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import androidx.webkit.WebViewCompat
import app.bahaplus.tv.core.Preferences
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class TvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.initialize(this)
    }
}

/** One application-scoped recorder; no logcat, background service or periodic polling. */
object Diagnostics {
    private lateinit var application: Context
    private lateinit var journal: DiagnosticLog
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 2, TimeUnit.SECONDS, ArrayBlockingQueue(256),
        { runnable -> Thread(runnable, "feedback-log").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }

    val recording: Boolean get() = ::journal.isInitialized && journal.recording
    val failed: Boolean get() = ::journal.isInitialized && journal.failed

    fun initialize(context: Context) {
        application = context.applicationContext
        journal = DiagnosticLog(File(application.filesDir, "diagnostics"))
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { journal.crash(error) }.onFailure { journal.abort() }
            original?.uncaughtException(thread, error)
        }
    }

    fun record(event: String, vararg fields: Pair<String, Any>) {
        if (!recording) return
        // A full queue drops diagnostics instead of blocking playback or the remote.
        runCatching {
            worker.execute { runCatching { journal.record(event, *fields) }.onFailure { journal.abort() } }
        }
    }

    fun start(environment: String, callback: (Result<Unit>) -> Unit) = task(callback) { journal.start(environment) }
    fun stop(callback: (Result<Unit>) -> Unit) = task(callback) { journal.stop() }
    fun inspect(callback: (Result<Long>) -> Unit) = task(callback) { journal.size() }
    fun clear(callback: (Result<Unit>) -> Unit) = task(callback) {
        journal.clear()
        reportsDirectory().listFiles()?.filter { it.isFile }?.forEach { check(it.delete()) }
        Unit
    }
    fun preview(environment: String, callback: (Result<String>) -> Unit) =
        task(callback) { journal.snapshot(environment).takeLast(24000) }

    fun export(environment: String, callback: (Result<File>) -> Unit) = task(callback) {
        journal.stop()
        check(journal.size() > 0) { "diagnostic-empty" }
        val directory = reportsDirectory()
        check(directory.isDirectory || directory.mkdirs()) { "diagnostic-export" }
        // Keep a few recent files so another app can finish reading its granted URI.
        directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?.drop(2)?.forEach { it.delete() }
        File(directory, "BahaPlusTV-log-${UUID.randomUUID()}.txt").apply {
            writeText(journal.snapshot(environment), Charsets.UTF_8)
        }
    }

    private fun reportsDirectory() = File(application.cacheDir, "feedback")

    private fun <T> task(callback: (Result<T>) -> Unit, action: () -> T) {
        val accepted = runCatching {
            worker.execute {
                val result = runCatching(action).onFailure { journal.abort() }
                main.post { callback(result) }
            }
        }
        accepted.exceptionOrNull()?.let { error -> main.post { callback(Result.failure(error)) } }
    }

    /** Only system/app configuration. No account identifiers, searches, titles or URLs. */
    fun environment(context: Context, preferences: Preferences): String {
        @Suppress("DEPRECATION")
        val version = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) version.longVersionCode else {
            @Suppress("DEPRECATION") version.versionCode.toLong()
        }
        val webView = runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.versionName }.getOrNull()
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return DiagnosticPrivacy.environment(
            "Baha Plus TV ${version.versionName} ($code)\n" +
                "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n" +
                "Device ${Build.MANUFACTURER} ${Build.MODEL}\nABI ${Build.SUPPORTED_ABIS.joinToString()}\n" +
                "WebView ${webView ?: "Unavailable"}\nApp process PSS ${memory.totalPss / 1024} MiB\n" +
                "Low resources ${preferences.lowResources}\nAuto next ${preferences.autoNext}\n" +
                "Preferred quality ${preferences.quality}\n"
        )
    }
}
